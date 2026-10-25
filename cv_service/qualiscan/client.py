"""Backend client for communicating with the Spring Boot inspection service.

Provides asynchronous, non-blocking inspection result reporting, automatic retry
with exponential backoff, persistent outbox buffering for network resilience,
and profile synchronization.
"""

from datetime import datetime, timezone
import json
import logging
import os
from pathlib import Path
import queue
import threading
import time
from typing import Any, Dict, List, Optional

import requests

logger = logging.getLogger("qualiscan.client")


def format_utc_timestamp(dt: Optional[datetime] = None) -> str:
    """Format a datetime as a UTC ISO-8601 string ending with 'Z'.

    Args:
        dt: Optional datetime instance. If None, current UTC time is used.

    Returns:
        String timestamp in ISO-8601 UTC format, e.g. '2026-10-24T10:00:00Z'.
    """
    if dt is None:
        dt = datetime.now(timezone.utc)
    elif dt.tzinfo is None:
        # Prevent naive local datetime by assuming UTC if unassigned
        dt = dt.replace(tzinfo=timezone.utc)
    else:
        # Convert any other timezone to UTC
        dt = dt.astimezone(timezone.utc)

    # Format without microseconds offset, ending explicitly in 'Z'
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def format_inspection_payload(
    track_id: Any,
    product_type: str,
    confidence: float,
    verdict: str,
    failed_checks: Optional[List[str]] = None,
    area: float = 0.0,
    circularity: float = 0.0,
    aspect_ratio: float = 0.0,
    inspected_at: Optional[str] = None,
) -> Dict[str, Any]:
    """Build a dictionary matching the backend inspection payload contract.

    Contract fields:
        - trackId (str)
        - productType (str)
        - confidence (float)
        - verdict ('PASS', 'REJECT', or 'UNKNOWN')
        - failedChecks (list of strings)
        - area (float)
        - circularity (float)
        - aspectRatio (float)
        - inspectedAt (UTC ISO-8601 string ending in 'Z')
    """
    # Normalize verdict to contract enum values
    normalized_verdict = str(verdict).upper()
    if normalized_verdict not in ("PASS", "REJECT", "UNKNOWN"):
        normalized_verdict = "UNKNOWN"

    timestamp = inspected_at if inspected_at else format_utc_timestamp()

    return {
        "trackId": str(track_id),
        "productType": str(product_type),
        "confidence": float(confidence),
        "verdict": normalized_verdict,
        "failedChecks": list(failed_checks) if failed_checks else [],
        "area": float(area),
        "circularity": float(circularity),
        "aspectRatio": float(aspect_ratio),
        "inspectedAt": timestamp,
    }


def normalize_payload(data: Dict[str, Any]) -> Dict[str, Any]:
    """Convert an arbitrary inspection dictionary into the backend contract format."""
    track_id = data.get("trackId", data.get("track_id", "0"))
    product_type = data.get("productType", data.get("product", "unknown"))
    confidence = float(data.get("confidence", 0.0))
    verdict = data.get("verdict", "UNKNOWN")
    failed_checks = data.get("failedChecks", data.get("failed_checks", []))
    area = float(data.get("area", 0.0))
    circularity = float(data.get("circularity", 0.0))
    aspect_ratio = float(data.get("aspectRatio", data.get("aspect_ratio", 0.0)))
    inspected_at = data.get("inspectedAt", data.get("inspected_at", data.get("timestamp")))

    return format_inspection_payload(
        track_id=track_id,
        product_type=product_type,
        confidence=confidence,
        verdict=verdict,
        failed_checks=failed_checks,
        area=area,
        circularity=circularity,
        aspect_ratio=aspect_ratio,
        inspected_at=inspected_at,
    )


class BackendClient:
    """Client for posting inspections and fetching product profiles from the backend.

    Supports asynchronous queueing with a background worker thread, exponential
    backoff retries, persistent outbox buffering during outages, and HUD status metrics.
    """

    def __init__(
        self,
        base_url: str,
        api_key: Optional[str] = None,
        outbox_path: str | Path = "data/outbox.jsonl",
        timeout: float = 5.0,
        max_retries: int = 3,
        retry_delay: float = 0.1,
    ) -> None:
        """Initialize the backend client.

        Args:
            base_url: Base HTTP URL of the Spring Boot backend (e.g. 'http://localhost:8080').
            api_key: Optional device API key. QUALISCAN_API_KEY environment variable wins if set.
            outbox_path: Path to the JSONL outbox file for offline buffering.
            timeout: HTTP request timeout in seconds.
            max_retries: Maximum retry attempts for 5xx or network errors before outbox buffering.
            retry_delay: Base delay in seconds for exponential backoff retries.
        """
        self.base_url = base_url.rstrip("/")

        # Environment variable wins over constructor argument
        env_key = os.environ.get("QUALISCAN_API_KEY")
        if env_key is not None and env_key != "":
            self.api_key: Optional[str] = env_key
        else:
            self.api_key = api_key

        self.outbox_path = Path(outbox_path)
        self.timeout = float(timeout)
        self.max_retries = int(max_retries)
        self.retry_delay = float(retry_delay)

        # Thread synchronization and statistics
        self._online: bool = False
        self._sent_count: int = 0
        self._failed_count: int = 0
        self._stats_lock = threading.Lock()
        self._outbox_lock = threading.Lock()

        # Work queue and background thread
        self._queue: queue.Queue = queue.Queue()
        self._stop_event = threading.Event()
        self._worker_thread = threading.Thread(
            target=self._worker_loop,
            name="QualiScanBackendWorker",
            daemon=True,
        )
        self._worker_thread.start()

    def __repr__(self) -> str:
        """Safe representation that never exposes the API key."""
        return (
            f"BackendClient(base_url={self.base_url!r}, "
            f"online={self.online}, sent={self.sent_count}, "
            f"failed={self.failed_count}, queued={self.queued})"
        )

    @property
    def online(self) -> bool:
        """Returns True if the backend was reachable on the last communication attempt."""
        with self._stats_lock:
            return self._online

    @property
    def sent_count(self) -> int:
        """Total count of successfully posted inspections."""
        with self._stats_lock:
            return self._sent_count

    @property
    def failed_count(self) -> int:
        """Count of permanently failed/dropped inspections or outbox buffered items."""
        with self._stats_lock:
            return self._failed_count

    @property
    def queued(self) -> int:
        """Returns the in-memory queue size plus pending outbox lines on disk."""
        mem_size = self._queue.qsize()
        outbox_lines = self._count_outbox_lines()
        return mem_size + outbox_lines

    def _count_outbox_lines(self) -> int:
        """Count non-empty lines in the outbox file safely."""
        with self._outbox_lock:
            if not self.outbox_path.is_file():
                return 0
            try:
                with self.outbox_path.open("r", encoding="utf-8") as f:
                    return sum(1 for line in f if line.strip())
            except OSError:
                return 0

    def _sanitize(self, message: str) -> str:
        """Remove the API key from any string before logging."""
        if self.api_key and self.api_key in message:
            return message.replace(self.api_key, "[REDACTED_KEY]")
        return message

    def fetch_profiles(self) -> Dict[str, dict]:
        """Fetch product inspection profiles from GET /api/profiles.

        Returns:
            Dict mapping productType -> parsed rules dictionary.

        Raises:
            RuntimeError: If the request fails or response cannot be parsed.
        """
        url = f"{self.base_url}/api/profiles"
        headers: Dict[str, str] = {}
        if self.api_key:
            headers["X-API-Key"] = self.api_key

        try:
            resp = requests.get(url, headers=headers, timeout=self.timeout)
            if resp.status_code != 200:
                with self._stats_lock:
                    self._online = False
                err_msg = self._sanitize(f"Backend returned HTTP {resp.status_code} for profiles")
                raise RuntimeError(err_msg)

            with self._stats_lock:
                self._online = True

            data = resp.json()
            profiles: Dict[str, dict] = {}

            if isinstance(data, list):
                for item in data:
                    product_type = item.get("productType") or item.get("product")
                    rules_json = item.get("rulesJson", "{}")
                    if product_type:
                        if isinstance(rules_json, str):
                            profiles[product_type] = json.loads(rules_json)
                        elif isinstance(rules_json, dict):
                            profiles[product_type] = rules_json
            elif isinstance(data, dict):
                for p_type, item in data.items():
                    if isinstance(item, str):
                        profiles[p_type] = json.loads(item)
                    elif isinstance(item, dict):
                        rules_json = item.get("rulesJson", item)
                        profiles[p_type] = json.loads(rules_json) if isinstance(rules_json, str) else rules_json

            return profiles
        except Exception as e:
            with self._stats_lock:
                self._online = False
            if isinstance(e, RuntimeError):
                raise
            clean_err = self._sanitize(str(e))
            raise RuntimeError(f"Failed to fetch profiles from backend: {clean_err}") from e

    def post_inspection(
        self,
        result: Dict[str, Any],
        snapshot_path: Optional[str] = None,
    ) -> requests.Response:
        """Send a single inspection result synchronously to POST /api/inspections.

        Constructs a multipart/form-data request containing:
            - A 'result' part with JSON formatted to the exact backend contract.
            - An optional 'snapshot' part with the JPEG image bytes.

        Args:
            result: Inspection result dictionary (normalized to contract fields).
            snapshot_path: Optional path to cropped JPEG snapshot on disk.

        Returns:
            requests.Response object from the server.

        Raises:
            requests.HTTPError: On 5xx server errors.
            requests.RequestException: On connection failures, network errors, or timeouts.
        """
        payload = normalize_payload(result)
        url = f"{self.base_url}/api/inspections"

        headers: Dict[str, str] = {}
        if self.api_key:
            headers["X-API-Key"] = self.api_key

        # Prepare multipart files payload
        files: Dict[str, Any] = {
            "result": ("result.json", json.dumps(payload), "application/json"),
        }

        # Attach snapshot if file exists
        opened_file = None
        if snapshot_path:
            snap_path = Path(snapshot_path)
            if snap_path.is_file():
                try:
                    opened_file = snap_path.open("rb")
                    files["snapshot"] = (snap_path.name, opened_file.read(), "image/jpeg")
                except OSError as e:
                    logger.warning("Could not read snapshot file at %s: %s", snap_path, e)
                finally:
                    if opened_file:
                        opened_file.close()

        try:
            resp = requests.post(url, headers=headers, files=files, timeout=self.timeout)

            if 200 <= resp.status_code < 300:
                with self._stats_lock:
                    self._online = True
                    self._sent_count += 1
                return resp

            if 400 <= resp.status_code < 500:
                # 4xx client errors (bad data) are logged and dropped
                with self._stats_lock:
                    self._online = True
                    self._failed_count += 1
                sanitized_text = self._sanitize(resp.text)
                logger.warning(
                    "Inspection for track %s rejected with HTTP %s: %s",
                    payload["trackId"],
                    resp.status_code,
                    sanitized_text,
                )
                return resp

            # 5xx server errors
            with self._stats_lock:
                self._online = False
            resp.raise_for_status()
            return resp

        except requests.RequestException as e:
            with self._stats_lock:
                self._online = False
            clean_err = self._sanitize(str(e))
            logger.warning("Inspection network error for track %s: %s", payload["trackId"], clean_err)
            raise

    def enqueue(self, result: Dict[str, Any], snapshot_path: Optional[str] = None) -> None:
        """Enqueue an inspection result for asynchronous background delivery.

        Returns immediately without blocking the pipeline loop.

        Args:
            result: Inspection result dictionary.
            snapshot_path: Optional path to cropped snapshot image.
        """
        payload = normalize_payload(result)
        self._queue.put_nowait((payload, snapshot_path))

    def _append_to_outbox(self, result: Dict[str, Any], snapshot_path: Optional[str]) -> None:
        """Append an unsendable inspection to the outbox JSONL file."""
        entry = {
            "result": result,
            "snapshot_path": snapshot_path,
        }
        with self._outbox_lock:
            try:
                self.outbox_path.parent.mkdir(parents=True, exist_ok=True)
                with self.outbox_path.open("a", encoding="utf-8") as f:
                    f.write(json.dumps(entry) + "\n")
            except OSError as e:
                logger.error("Failed writing to outbox file %s: %s", self.outbox_path, e)

        with self._stats_lock:
            self._failed_count += 1

    def flush_outbox(self) -> None:
        """Retry sending stored items from the outbox file when backend is reachable."""
        with self._outbox_lock:
            if not self.outbox_path.is_file():
                return

            try:
                with self.outbox_path.open("r", encoding="utf-8") as f:
                    lines = [line.strip() for line in f if line.strip()]
            except OSError as e:
                logger.warning("Error reading outbox file %s: %s", self.outbox_path, e)
                return

            if not lines:
                try:
                    self.outbox_path.unlink()
                except OSError:
                    pass
                return

            remaining_lines: List[str] = []
            backend_down = False

            for line in lines:
                if backend_down:
                    remaining_lines.append(line)
                    continue

                try:
                    data = json.loads(line)
                    result = data.get("result", data)
                    snapshot_path = data.get("snapshot_path")
                except Exception as e:
                    logger.warning("Corrupt outbox entry dropped: %s", e)
                    with self._stats_lock:
                        self._failed_count += 1
                    continue

                # Attempt sending
                try:
                    resp = self.post_inspection(result, snapshot_path)
                    if 200 <= resp.status_code < 300:
                        # Successfully sent from outbox
                        pass
                    elif 400 <= resp.status_code < 500:
                        # 4xx dropped; do not keep in outbox
                        pass
                    else:
                        # Unexpected 5xx without exception
                        backend_down = True
                        remaining_lines.append(line)
                except Exception:
                    # Network error or 5xx; backend is down
                    backend_down = True
                    remaining_lines.append(line)

            try:
                if remaining_lines:
                    with self.outbox_path.open("w", encoding="utf-8") as f:
                        for rem in remaining_lines:
                            f.write(rem + "\n")
                else:
                    try:
                        self.outbox_path.unlink()
                    except OSError:
                        with self.outbox_path.open("w", encoding="utf-8") as f:
                            pass
            except OSError as e:
                logger.error("Error updating outbox file %s: %s", self.outbox_path, e)

    def _worker_loop(self) -> None:
        """Background worker thread consuming queue and flushing outbox."""
        # Initial outbox flush on startup
        try:
            self.flush_outbox()
        except Exception as e:
            logger.debug("Initial outbox flush notice: %s", e)

        last_flush = time.time()

        while not self._stop_event.is_set():
            # Check periodic 30-second outbox flush
            now = time.time()
            if now - last_flush >= 30.0:
                try:
                    self.flush_outbox()
                except Exception as e:
                    logger.debug("Periodic outbox flush notice: %s", e)
                last_flush = now

            try:
                item = self._queue.get(timeout=0.5)
            except queue.Empty:
                continue

            result, snapshot_path = item
            self._process_item(result, snapshot_path)
            self._queue.task_done()

    def _process_item(self, result: Dict[str, Any], snapshot_path: Optional[str]) -> None:
        """Send an item with exponential backoff retries, buffering to outbox on failure."""
        for attempt in range(self.max_retries + 1):
            try:
                resp = self.post_inspection(result, snapshot_path)
                if 400 <= resp.status_code < 500:
                    # 4xx client error dropped immediately
                    return
                # 2xx success
                return
            except Exception:
                if attempt < self.max_retries and not self._stop_event.is_set():
                    delay = self.retry_delay * (2 ** attempt)
                    time.sleep(delay)
                else:
                    # All retries exhausted; buffer in outbox
                    self._append_to_outbox(result, snapshot_path)
                    return

    def close(self, timeout: float = 3.0) -> None:
        """Gracefully flush remaining queued items and stop the worker thread.

        Args:
            timeout: Maximum seconds to wait for queued items to drain.
        """
        deadline = time.time() + max(0.1, timeout)
        while not self._queue.empty() and time.time() < deadline:
            time.sleep(0.05)

        self._stop_event.set()
        if self._worker_thread.is_alive():
            remaining_time = max(0.1, deadline - time.time())
            self._worker_thread.join(timeout=remaining_time)
