"""Tests for BackendClient and inspection reporting in QualiScan."""

from datetime import datetime, timezone
from email.parser import BytesParser
from email.policy import default
from http.server import BaseHTTPRequestHandler, HTTPServer
import json
import logging
import os
from pathlib import Path
import threading
import time
from typing import Any, Dict, List, Optional
import urllib.parse

import pytest

from qualiscan.client import (
    BackendClient,
    format_inspection_payload,
    format_utc_timestamp,
    normalize_payload,
)


class MockBackendHandler(BaseHTTPRequestHandler):
    """Custom HTTP handler that records requests and returns configurable responses."""

    # Shared mock server state
    received_requests: List[Dict[str, Any]] = []
    inspections_status_queue: List[int] = []
    default_inspection_status: int = 201
    profiles_data: Any = [
        {
            "productType": "cap",
            "rulesJson": json.dumps({"min_area": 800, "max_area": 35000, "enabled_checks": ["size"]}),
        },
        {
            "productType": "card",
            "rulesJson": json.dumps({"min_area": 1200, "max_area": 40000, "enabled_checks": ["shape"]}),
        },
    ]

    def log_message(self, format: str, *args: Any) -> None:
        """Silence standard HTTP server console output during test runs."""
        pass

    def do_GET(self) -> None:
        parsed_url = urllib.parse.urlparse(self.path)
        req_entry = {
            "method": "GET",
            "path": parsed_url.path,
            "headers": dict(self.headers),
        }
        MockBackendHandler.received_requests.append(req_entry)

        if parsed_url.path == "/api/profiles":
            payload = json.dumps(MockBackendHandler.profiles_data).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        else:
            self.send_response(404)
            self.end_headers()

    def do_POST(self) -> None:
        parsed_url = urllib.parse.urlparse(self.path)
        content_type = self.headers.get("Content-Type", "")
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length)

        # Parse multipart form data
        parts: Dict[str, Any] = {}
        if "multipart/form-data" in content_type:
            msg = BytesParser(policy=default).parsebytes(
                f"Content-Type: {content_type}\r\n\r\n".encode("latin1") + body
            )
            for part in msg.iter_parts():
                name = part.get_param("name", header="content-disposition")
                parts[name] = {
                    "filename": part.get_filename(),
                    "content_type": part.get_content_type(),
                    "payload": part.get_payload(decode=True),
                }

        req_entry = {
            "method": "POST",
            "path": parsed_url.path,
            "headers": dict(self.headers),
            "parts": parts,
            "raw_body": body,
        }
        MockBackendHandler.received_requests.append(req_entry)

        if parsed_url.path == "/api/inspections":
            if MockBackendHandler.inspections_status_queue:
                status_code = MockBackendHandler.inspections_status_queue.pop(0)
            else:
                status_code = MockBackendHandler.default_inspection_status

            if status_code in (200, 201):
                resp_bytes = b'{"id": 42}'
                self.send_response(status_code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(resp_bytes)))
                self.end_headers()
                self.wfile.write(resp_bytes)
            elif status_code == 400:
                resp_bytes = b'{"error": "Bad Request - Invalid field"}'
                self.send_response(400)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(resp_bytes)))
                self.end_headers()
                self.wfile.write(resp_bytes)
            else:  # 500 or other
                resp_bytes = b'{"error": "Internal Server Error"}'
                self.send_response(status_code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(resp_bytes)))
                self.end_headers()
                self.wfile.write(resp_bytes)
        else:
            self.send_response(404)
            self.end_headers()


@pytest.fixture
def fake_backend():
    """Start a tiny background HTTP server for the duration of a test."""
    MockBackendHandler.received_requests.clear()
    MockBackendHandler.inspections_status_queue.clear()
    MockBackendHandler.default_inspection_status = 201

    server = HTTPServer(("127.0.0.1", 0), MockBackendHandler)
    port = server.server_address[1]
    server_thread = threading.Thread(target=server.serve_forever, daemon=True)
    server_thread.start()

    base_url = f"http://127.0.0.1:{port}"
    try:
        yield base_url
    finally:
        server.shutdown()
        server.server_close()


def test_multipart_request_contract(fake_backend, tmp_path):
    """Test multipart POST contains X-API-Key, exact JSON fields, and snapshot."""
    snap_file = tmp_path / "test_snapshot.jpg"
    fake_jpeg_bytes = b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00\xff\xd9"
    snap_file.write_bytes(fake_jpeg_bytes)

    outbox_file = tmp_path / "outbox.jsonl"
    api_key = "device-key-test-abc-123"

    client = BackendClient(
        base_url=fake_backend,
        api_key=api_key,
        outbox_path=outbox_file,
        timeout=3.0,
    )
    try:
        payload = format_inspection_payload(
            track_id="42",
            product_type="cap",
            confidence=0.985,
            verdict="PASS",
            failed_checks=[],
            area=1250.5,
            circularity=0.88,
            aspect_ratio=1.05,
            inspected_at="2026-10-25T10:15:30Z",
        )

        resp = client.post_inspection(payload, snapshot_path=str(snap_file))
        assert resp.status_code == 201
        assert client.online is True
        assert client.sent_count == 1

        # Check recorded request on fake backend
        assert len(MockBackendHandler.received_requests) == 1
        req = MockBackendHandler.received_requests[0]
        assert req["method"] == "POST"
        assert req["path"] == "/api/inspections"
        assert req["headers"].get("X-API-Key") == api_key

        # Verify multipart parts
        parts = req["parts"]
        assert "result" in parts
        assert "snapshot" in parts

        # Verify result part content and JSON fields
        result_part = parts["result"]
        assert result_part["content_type"] == "application/json"
        parsed_json = json.loads(result_part["payload"].decode("utf-8"))

        expected_fields = {
            "trackId",
            "productType",
            "confidence",
            "verdict",
            "failedChecks",
            "area",
            "circularity",
            "aspectRatio",
            "inspectedAt",
        }
        assert set(parsed_json.keys()) == expected_fields
        assert parsed_json["trackId"] == "42"
        assert parsed_json["productType"] == "cap"
        assert parsed_json["confidence"] == 0.985
        assert parsed_json["verdict"] == "PASS"
        assert parsed_json["failedChecks"] == []
        assert parsed_json["area"] == 1250.5
        assert parsed_json["circularity"] == 0.88
        assert parsed_json["aspectRatio"] == 1.05
        assert parsed_json["inspectedAt"] == "2026-10-25T10:15:30Z"

        # Verify snapshot part content
        snapshot_part = parts["snapshot"]
        assert snapshot_part["content_type"] == "image/jpeg"
        assert snapshot_part["payload"] == fake_jpeg_bytes
    finally:
        client.close()


def test_500_response_leads_to_retries_and_outbox(fake_backend, tmp_path):
    """Test 500 response triggers exponential retries and saves entry to outbox."""
    MockBackendHandler.default_inspection_status = 500
    outbox_file = tmp_path / "outbox.jsonl"

    client = BackendClient(
        base_url=fake_backend,
        api_key="device-test-key",
        outbox_path=outbox_file,
        max_retries=3,
        retry_delay=0.03,  # Fast retries for testing
    )
    try:
        payload = format_inspection_payload(
            track_id="101",
            product_type="card",
            confidence=0.92,
            verdict="REJECT",
            failed_checks=["dent"],
            area=850.0,
            circularity=0.75,
            aspect_ratio=1.4,
        )

        client.enqueue(payload, snapshot_path=None)

        # Wait for worker to exhaust 3 retries (1 initial + 3 retries = 4 attempts)
        deadline = time.time() + 2.0
        while time.time() < deadline:
            if len(MockBackendHandler.received_requests) >= 4 and outbox_file.is_file():
                break
            time.sleep(0.05)

        # 1 initial + 3 retries = 4 total attempts
        assert len(MockBackendHandler.received_requests) == 4
        assert outbox_file.is_file()

        lines = [line.strip() for line in outbox_file.read_text(encoding="utf-8").splitlines() if line.strip()]
        assert len(lines) == 1

        stored = json.loads(lines[0])
        assert stored["result"]["trackId"] == "101"
        assert stored["result"]["verdict"] == "REJECT"
        assert client.online is False
        assert client.queued == 1
        assert client.failed_count == 1
    finally:
        client.close()


def test_recover_and_flush_outbox(fake_backend, tmp_path):
    """Test that when the backend recovers, flush_outbox() sends items and clears outbox."""
    outbox_file = tmp_path / "outbox.jsonl"

    # Seed an outbox entry simulating a previously buffered item
    payload = format_inspection_payload(
        track_id="202",
        product_type="biscuit",
        confidence=0.96,
        verdict="PASS",
        failed_checks=[],
        area=1500.0,
        circularity=0.91,
        aspect_ratio=1.02,
    )
    entry = {"result": payload, "snapshot_path": None}
    outbox_file.write_text(json.dumps(entry) + "\n", encoding="utf-8")

    MockBackendHandler.default_inspection_status = 201

    client = BackendClient(
        base_url=fake_backend,
        api_key="device-test-key",
        outbox_path=outbox_file,
    )
    try:
        # Client startup automatically triggers flush_outbox()
        deadline = time.time() + 2.0
        while time.time() < deadline:
            if client.sent_count >= 1:
                break
            time.sleep(0.05)

        # Also trigger explicitly to test public flush_outbox() method
        client.flush_outbox()

        assert client.online is True
        assert client.sent_count == 1
        assert client.queued == 0

        # Outbox file should be empty or deleted
        if outbox_file.is_file():
            assert outbox_file.read_text(encoding="utf-8").strip() == ""
    finally:
        client.close()


def test_400_response_dropped_not_stored(fake_backend, tmp_path):
    """Test 400 Bad Request is dropped immediately without retrying or saving to outbox."""
    MockBackendHandler.default_inspection_status = 400
    outbox_file = tmp_path / "outbox.jsonl"

    client = BackendClient(
        base_url=fake_backend,
        api_key="device-test-key",
        outbox_path=outbox_file,
        max_retries=3,
        retry_delay=0.03,
    )
    try:
        payload = format_inspection_payload(
            track_id="303",
            product_type="unknown",
            confidence=0.1,
            verdict="UNKNOWN",
        )

        client.enqueue(payload, snapshot_path=None)

        # Allow worker thread time to process
        deadline = time.time() + 1.0
        while time.time() < deadline:
            if len(MockBackendHandler.received_requests) >= 1:
                break
            time.sleep(0.05)

        # Wait a small buffer to confirm no retries occur
        time.sleep(0.2)

        # Exactly 1 request sent (no retries for 4xx)
        assert len(MockBackendHandler.received_requests) == 1
        assert client.failed_count == 1

        # Outbox should not contain the dropped item
        assert not outbox_file.is_file() or outbox_file.read_text(encoding="utf-8").strip() == ""
        assert client.queued == 0
    finally:
        client.close()


def test_api_key_never_appears_in_log_output(fake_backend, caplog, tmp_path):
    """Test device API key is never printed or logged."""
    secret_key = "SUPER-SECRET-DEVICE-TOKEN-XYZZY-998877"
    MockBackendHandler.default_inspection_status = 400
    outbox_file = tmp_path / "outbox.jsonl"

    caplog.set_level(logging.DEBUG)

    client = BackendClient(
        base_url=fake_backend,
        api_key=secret_key,
        outbox_path=outbox_file,
    )
    try:
        payload = format_inspection_payload(
            track_id="404",
            product_type="cap",
            confidence=0.5,
            verdict="REJECT",
        )
        # Trigger logging via 400 rejection
        client.post_inspection(payload, snapshot_path=None)

        # Also trigger an error path
        dead_client = BackendClient(
            base_url="http://127.0.0.1:59999",  # Non-existent server
            api_key=secret_key,
            outbox_path=outbox_file,
            timeout=0.2,
        )
        try:
            dead_client.fetch_profiles()
        except RuntimeError:
            pass
        finally:
            dead_client.close()

        # Verify key does not appear in any captured log
        all_logs = caplog.text
        assert secret_key not in all_logs
        assert secret_key not in repr(client)
        assert secret_key not in str(client)
    finally:
        client.close()


def test_enqueue_returns_under_50ms_when_server_down(tmp_path):
    """Test enqueue() is non-blocking and executes in < 50ms even when server is offline."""
    outbox_file = tmp_path / "outbox.jsonl"

    # Dead server URL
    client = BackendClient(
        base_url="http://127.0.0.1:59999",
        api_key="some-key",
        outbox_path=outbox_file,
        timeout=0.2,
        max_retries=1,
        retry_delay=0.01,
    )
    try:
        payload = format_inspection_payload(
            track_id="505",
            product_type="cap",
            confidence=0.9,
            verdict="PASS",
        )

        start = time.perf_counter()
        client.enqueue(payload, snapshot_path=None)
        elapsed_ms = (time.perf_counter() - start) * 1000.0

        assert elapsed_ms < 50.0, f"enqueue() took too long: {elapsed_ms:.2f} ms"
    finally:
        client.close(timeout=1.0)


def test_inspected_at_is_utc_and_ends_with_z():
    """Test inspectedAt is an ISO-8601 UTC timestamp ending with 'Z'."""
    ts = format_utc_timestamp()
    assert ts.endswith("Z"), f"Expected timestamp ending with 'Z', got: {ts}"
    assert "+00:00" not in ts

    # Verify parseable as valid ISO-8601
    dt = datetime.fromisoformat(ts.replace("Z", "+00:00"))
    assert dt.tzinfo == timezone.utc

    # Test with specific datetime
    sample_dt = datetime(2026, 10, 24, 15, 30, 45, tzinfo=timezone.utc)
    ts_custom = format_utc_timestamp(sample_dt)
    assert ts_custom == "2026-10-24T15:30:45Z"

    # Test payload generation
    payload = format_inspection_payload(
        track_id="777",
        product_type="card",
        confidence=0.95,
        verdict="PASS",
    )
    assert payload["inspectedAt"].endswith("Z")
    parsed_payload_dt = datetime.fromisoformat(payload["inspectedAt"].replace("Z", "+00:00"))
    assert parsed_payload_dt.tzinfo == timezone.utc


def test_fetch_profiles_success_and_parsing(fake_backend, tmp_path):
    """Test fetch_profiles() returns parsed rules per product type."""
    client = BackendClient(
        base_url=fake_backend,
        api_key="test-api-key",
        outbox_path=tmp_path / "outbox.jsonl",
    )
    try:
        profiles = client.fetch_profiles()
        assert "cap" in profiles
        assert "card" in profiles
        assert profiles["cap"]["min_area"] == 800
        assert profiles["card"]["max_area"] == 40000
        assert client.online is True
    finally:
        client.close()


def test_api_key_environment_variable_precedence(tmp_path):
    """Test QUALISCAN_API_KEY environment variable wins over constructor argument."""
    outbox_file = tmp_path / "outbox.jsonl"
    os.environ["QUALISCAN_API_KEY"] = "env-secret-key"
    try:
        client = BackendClient(
            base_url="http://localhost:8080",
            api_key="arg-secret-key",
            outbox_path=outbox_file,
        )
        assert client.api_key == "env-secret-key"
        client.close()
    finally:
        del os.environ["QUALISCAN_API_KEY"]

    # When env var is not set, constructor arg is used
    client2 = BackendClient(
        base_url="http://localhost:8080",
        api_key="arg-secret-key",
        outbox_path=outbox_file,
    )
    assert client2.api_key == "arg-secret-key"
    client2.close()
