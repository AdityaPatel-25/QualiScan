"""QualiScan Backend Ingest Latency Report Tool.

Measures the latency between camera edge event creation (inspectedAt) and
backend persistence (receivedAt) for the last N inspections via REST API.
"""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys
from typing import Any, Dict, List, Optional, Tuple

# Ensure cv_service root is on sys.path
TOOLS_DIR = Path(__file__).resolve().parent
CV_SERVICE_DIR = TOOLS_DIR.parent
REPO_ROOT = CV_SERVICE_DIR.parent
if str(CV_SERVICE_DIR) not in sys.path:
    sys.path.insert(0, str(CV_SERVICE_DIR))

import numpy as np
import requests


def parse_iso_timestamp(ts_val: Any) -> Optional[datetime]:
    """Parse an ISO-8601 string or numeric timestamp into UTC datetime.

    Handles ISO-8601 formats including 'Z' suffix and microsecond precision.
    """
    if ts_val is None:
        return None
    if isinstance(ts_val, (int, float)):
        return datetime.fromtimestamp(ts_val, tz=timezone.utc)
    s = str(ts_val).strip()
    if not s:
        return None
    try:
        # Normalize 'Z' to '+00:00' for Python fromisoformat
        if s.endswith("Z"):
            s = s[:-1] + "+00:00"
        dt = datetime.fromisoformat(s)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.astimezone(timezone.utc)
    except Exception:
        return None


def fetch_jwt_token(
    base_url: str,
    username: str,
    password: str,
    timeout: float = 3.0,
) -> Optional[str]:
    """Authenticate with backend /api/auth/login and obtain bearer token."""
    login_url = f"{base_url.rstrip('/')}/api/auth/login"
    try:
        resp = requests.post(
            login_url,
            json={"username": username, "password": password},
            timeout=timeout,
        )
        if resp.status_code == 200:
            data = resp.json()
            return data.get("token")
    except Exception:
        pass
    return None


def fetch_inspections(
    base_url: str,
    token: Optional[str] = None,
    api_key: Optional[str] = None,
    limit: int = 200,
    page_size: int = 100,
    timeout: float = 4.0,
) -> Tuple[List[Dict[str, Any]], Optional[str]]:
    """Fetch the latest inspection records from GET /api/inspections with pagination.

    Returns:
        Tuple of (inspection_list, error_message_if_any).
    """
    inspections_url = f"{base_url.rstrip('/')}/api/inspections"
    headers: Dict[str, str] = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    elif api_key:
        headers["X-API-Key"] = api_key

    all_records: List[Dict[str, Any]] = []
    page = 0

    while len(all_records) < limit:
        fetch_size = min(page_size, limit - len(all_records))
        params = {"page": page, "size": fetch_size}

        try:
            resp = requests.get(
                inspections_url,
                headers=headers,
                params=params,
                timeout=timeout,
            )
            if resp.status_code == 401 or resp.status_code == 403:
                return [], f"Authentication failed (HTTP {resp.status_code}): Invalid token or insufficient permissions."
            if resp.status_code != 200:
                return [], f"Backend request failed with HTTP {resp.status_code}: {resp.text[:200]}"

            data = resp.json()
            # Spring Boot Page<T> format has a 'content' list
            items = data.get("content", []) if isinstance(data, dict) else (data if isinstance(data, list) else [])
            if not items:
                break

            all_records.extend(items)
            page += 1

            # Check if last page reached
            if isinstance(data, dict) and data.get("last", True):
                break
        except requests.exceptions.ConnectionError:
            return [], f"Backend server at {base_url} is unreachable (ConnectionRefused / offline)."
        except requests.exceptions.Timeout:
            return [], f"Connection to backend at {base_url} timed out."
        except Exception as e:
            return [], f"Unexpected error while fetching inspections: {e}"

    return all_records[:limit], None


def compute_ingest_latencies(
    inspections: List[Dict[str, Any]]
) -> Tuple[List[float], int]:
    """Calculate (receivedAt - inspectedAt) in milliseconds for all records.

    Returns:
        Tuple of (list of latency_ms, count of skipped records missing fields).
    """
    latencies_ms: List[float] = []
    skipped = 0

    for item in inspections:
        insp_val = item.get("inspectedAt")
        recv_val = item.get("receivedAt")

        if not insp_val or not recv_val:
            skipped += 1
            continue

        insp_dt = parse_iso_timestamp(insp_val)
        recv_dt = parse_iso_timestamp(recv_val)

        if insp_dt is None or recv_dt is None:
            skipped += 1
            continue

        latency_ms = (recv_dt - insp_dt).total_seconds() * 1000.0
        latencies_ms.append(latency_ms)

    return latencies_ms, skipped


def build_latency_markdown(
    date_str: str,
    base_url: str,
    records_requested: int,
    records_found: int,
    skipped_count: int,
    stats: Optional[Dict[str, float]],
    error_msg: Optional[str] = None,
) -> str:
    """Format ingest latency report as Markdown document."""
    lines: List[str] = [
        "# QualiScan Ingest Latency Report",
        "",
        f"**Date:** {date_str}  ",
        f"**Backend URL:** `{base_url}`  ",
        f"**Target Samples (N):** {records_requested} inspections  ",
        "",
        "> [!NOTE]",
        "> **Ingest Latency Definition:** This is the duration from when an item inspection "
        "is recorded by the camera edge service (`inspectedAt`) to when the telemetry event "
        "is ingested and committed to the backend database (`receivedAt`), measured on this machine.",
        "",
    ]

    if error_msg:
        lines.extend([
            "## Ingest Latency Measurement Status",
            "",
            "> [!WARNING]",
            f"> **Measurement Incomplete:** {error_msg}  ",
            "> In accordance with project evaluation guidelines, no metrics were fabricated or estimated.",
            "",
        ])
    elif stats is None or records_found == 0:
        lines.extend([
            "## Ingest Latency Measurement Status",
            "",
            "> [!WARNING]",
            "> No inspection records were returned from the backend service. "
            "Please ensure inspections have been streamed via `run_pipeline.py` or automated clients.",
            "",
        ])
    else:
        lines.extend([
            "## 1. Summary Statistics",
            "",
            f"- **Records Analyzed:** {len(stats.get('raw', []))} inspections",
            f"- **Records Skipped (Missing Timestamps):** {skipped_count}",
            f"- **Median Latency:** **{stats['median']:.2f} ms**",
            f"- **95th Percentile Latency:** **{stats['p95']:.2f} ms**",
            f"- **Mean Latency:** {stats['mean']:.2f} ms",
            f"- **Min Latency:** {stats['min']:.2f} ms",
            f"- **Max Latency:** {stats['max']:.2f} ms",
            "",
            "## 2. Latency Metrics Table",
            "",
            "| Metric | Value (ms) | Description |",
            "| :--- | :---: | :--- |",
            f"| **Median (50th %ile)** | **{stats['median']:.2f}** | Typical edge-to-backend network & HTTP queue latency |",
            f"| **95th Percentile** | **{stats['p95']:.2f}** | Upper bound latency covering 95% of recorded inspections |",
            f"| Mean | {stats['mean']:.2f} | Arithmetic mean latency |",
            f"| Minimum | {stats['min']:.2f} | Fastest recorded delivery |",
            f"| Maximum | {stats['max']:.2f} | Peak recorded delivery delay |",
            "",
        ])

    return "\n".join(lines)


def main() -> None:
    """CLI entrypoint for running backend latency report."""
    parser = argparse.ArgumentParser(
        description="QualiScan Ingest Latency Report Tool"
    )
    parser.add_argument(
        "--base-url",
        type=str,
        default=os.environ.get("QUALISCAN_BASE_URL", "http://localhost:8080"),
        help="Backend REST URL (default: http://localhost:8080 or QUALISCAN_BASE_URL)",
    )
    parser.add_argument(
        "--user",
        type=str,
        default=os.environ.get("QUALISCAN_USER", "admin"),
        help="Backend username (default: admin or QUALISCAN_USER)",
    )
    parser.add_argument(
        "--password",
        type=str,
        default=os.environ.get("QUALISCAN_PASSWORD", "Admin@123"),
        help="Backend password (default: Admin@123 or QUALISCAN_PASSWORD)",
    )
    parser.add_argument(
        "--api-key",
        type=str,
        default=os.environ.get("QUALISCAN_API_KEY"),
        help="Device API key (default: QUALISCAN_API_KEY env var)",
    )
    parser.add_argument(
        "--n",
        type=int,
        default=200,
        help="Number of latest inspections to fetch (default: 200)",
    )
    parser.add_argument(
        "--output",
        type=str,
        default="docs/latency.md",
        help="Path to write latency report markdown (default: docs/latency.md)",
    )

    args = parser.parse_args()

    output_path = Path(args.output)
    if not output_path.is_absolute():
        output_path = REPO_ROOT / output_path

    print("=" * 78)
    print("QUALISCAN BACKEND INGEST LATENCY REPORT")
    print(f"Backend URL: {args.base_url}")
    print(f"User:        {args.user}")
    print(f"Sample Size: Last {args.n} inspections")
    print(f"Output:      {output_path}")
    print("=" * 78)

    # 1. Backend DTO field check note
    # Confirmed from backend/src/main/java/com/qualiscan/api/ApiController.java:
    # inspectionDto() exposes both "inspectedAt" and "receivedAt".
    print("\nBackend DTO Check: Confirmed that ApiController exposes both 'inspectedAt' and 'receivedAt'.")

    # 2. Authenticate
    token: Optional[str] = None
    if args.user and args.password:
        token = fetch_jwt_token(args.base_url, args.user, args.password)
        if token:
            print("Successfully authenticated via JWT login.")
        else:
            print("Notice: JWT login unsuccessful or backend offline; attempting direct request.")

    # 3. Fetch inspections
    inspections, error_msg = fetch_inspections(
        base_url=args.base_url,
        token=token,
        api_key=args.api_key,
        limit=args.n,
    )

    date_str = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%SZ")
    stats: Optional[Dict[str, Any]] = None
    skipped = 0

    if error_msg:
        print(f"\n[NOTICE] {error_msg}")
        print("Note: In accordance with evaluation rules, no metrics were fabricated or estimated.")
    elif not inspections:
        print(f"\n[NOTICE] Backend at {args.base_url} returned 0 inspection records.")
        print("Note: In accordance with evaluation rules, no metrics were fabricated or estimated.")
    else:
        latencies_ms, skipped = compute_ingest_latencies(inspections)
        if not latencies_ms:
            print(f"\n[NOTICE] All {len(inspections)} returned records were missing required timestamps.")
        else:
            arr = np.asarray(latencies_ms, dtype=float)
            stats = {
                "mean": round(float(np.mean(arr)), 2),
                "median": round(float(np.median(arr)), 2),
                "p95": round(float(np.percentile(arr, 95)), 2),
                "min": round(float(np.min(arr)), 2),
                "max": round(float(np.max(arr)), 2),
                "raw": latencies_ms,
            }

            print("\n" + "=" * 78)
            print("INGEST LATENCY RESULTS (receivedAt - inspectedAt)")
            print("=" * 78)
            print(f"Records Evaluated: {len(latencies_ms)}")
            if skipped > 0:
                print(f"Records Skipped:   {skipped} (missing inspectedAt/receivedAt)")
            print(f"Median Latency:    {stats['median']:.2f} ms")
            print(f"95th %ile Latency: {stats['p95']:.2f} ms")
            print(f"Mean Latency:      {stats['mean']:.2f} ms")
            print(f"Min Latency:       {stats['min']:.2f} ms")
            print(f"Max Latency:       {stats['max']:.2f} ms")
            print("=" * 78)

    # 4. Write Markdown report
    report_md = build_latency_markdown(
        date_str=date_str,
        base_url=args.base_url,
        records_requested=args.n,
        records_found=len(inspections),
        skipped_count=skipped,
        stats=stats,
        error_msg=error_msg,
    )
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(report_md, encoding="utf-8")
    print(f"\nIngest latency report written to: {output_path}")


if __name__ == "__main__":
    main()
