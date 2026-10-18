"""QualiScan Live Conveyor Inspection Pipeline Demo.

Integrates item segmentation, centroid tracking, trigger-line crossing detection,
reusable quality rule inspection, snapshot archiving, and real-time visualization.
"""

import argparse
from datetime import datetime
import json
from pathlib import Path
import sys
import time
from typing import Any, Dict, Optional

# Ensure cv_service root is on sys.path
TOOLS_DIR = Path(__file__).resolve().parent
CV_SERVICE_DIR = TOOLS_DIR.parent
if str(CV_SERVICE_DIR) not in sys.path:
    sys.path.insert(0, str(CV_SERVICE_DIR))

import cv2
import numpy as np

from qualiscan.inspect_item import DEFAULT_RULES, inspect
from qualiscan.segment import segment_items
from qualiscan.tracker import CentroidTracker
from qualiscan.zone import TriggerLine
from qualiscan.identify import load_model
from qualiscan.profiles import load_profiles

# Snapshot output directory
REPO_ROOT = CV_SERVICE_DIR.parent
SNAPSHOTS_DIR = REPO_ROOT / "data" / "snapshots"


def load_rules(rules_path: Optional[str]) -> Optional[Dict[str, Any]]:
    """Loads inspection rules from a JSON file or falls back to DEFAULT_RULES."""
    if not rules_path:
        return DEFAULT_RULES

    path = Path(rules_path)
    if not path.is_file():
        print(f"Warning: Rules file '{rules_path}' not found. Using DEFAULT_RULES.")
        return DEFAULT_RULES

    try:
        with open(path, "r", encoding="utf-8") as f:
            rules = json.load(f)
            print(f"Loaded inspection rules from: {path}")
            return rules
    except Exception as e:
        print(f"Error parsing rules file '{rules_path}': {e}. Using DEFAULT_RULES.")
        return DEFAULT_RULES


def main() -> None:
    """Executes the live conveyor inspection pipeline."""
    parser = argparse.ArgumentParser(description="QualiScan Real-Time Conveyor Pipeline Demo")
    parser.add_argument(
        "--source",
        type=str,
        default="0",
        help="Video source: camera index (default: '0'), or path to video file/image",
    )
    parser.add_argument(
        "--dark-objects",
        action="store_true",
        help="Flag indicating objects are darker than the conveyor background (manual override)",
    )
    parser.add_argument(
        "--min-area",
        type=int,
        default=800,
        help="Minimum contour area threshold in pixels (default: 800)",
    )
    parser.add_argument(
        "--line-pos",
        type=float,
        default=0.5,
        help="Trigger line position fraction across the frame (default: 0.5)",
    )
    parser.add_argument(
        "--direction",
        type=str,
        default="ltr",
        choices=["ltr", "rtl", "ttb", "btt"],
        help="Crossing direction to trigger inspection: ltr, rtl, ttb, btt (default: 'ltr')",
    )
    parser.add_argument(
        "--rules",
        type=str,
        default=None,
        help="Optional path to a custom JSON rules file",
    )
    parser.add_argument("--model", type=str, default="models/identifier.joblib", help="Identifier model path")
    parser.add_argument("--profiles", type=str, default="profiles/", help="Product profile directory")
    parser.add_argument(
        "--no-display",
        action="store_true",
        help="Run without displaying OpenCV GUI windows (useful for headless testing)",
    )
    parser.add_argument(
        "--loop",
        action="store_true",
        help="Loop video continuously until 'q' or ESC is pressed",
    )

    args = parser.parse_args()

    # Manual override for polarity
    light_objects = False if args.dark_objects else None

    # Load inspection rules
    rules = load_rules(args.rules)
    model_path = Path(args.model)
    if not model_path.is_absolute():
        model_path = CV_SERVICE_DIR / model_path
    model = load_model(model_path) if model_path.is_file() else None
    if model is None:
        print(f"Warning: identifier model '{model_path}' not found; using legacy rules inspection.")
    profiles_path = Path(args.profiles)
    if not profiles_path.is_absolute():
        profiles_path = CV_SERVICE_DIR / profiles_path
    profiles = load_profiles(profiles_path)
    reference_path = str(profiles_path / "card_reference.png")

    # Initialize tracker and trigger line
    tracker = CentroidTracker(max_distance=80.0, max_disappeared=15)
    trigger_line = TriggerLine(position=args.line_pos, direction=args.direction)

    # Ensure snapshots directory exists
    SNAPSHOTS_DIR.mkdir(parents=True, exist_ok=True)

    # Open video source
    is_image = False
    static_frame = None

    if args.source.isdigit():
        cap = cv2.VideoCapture(int(args.source))
        source_is_cam = True
    else:
        src_path = Path(args.source)
        # Attempt resolution across current directory, cv_service, repo root, and data folder
        if not src_path.exists():
            if (CV_SERVICE_DIR / args.source).exists():
                src_path = CV_SERVICE_DIR / args.source
            elif (REPO_ROOT / args.source).exists():
                src_path = REPO_ROOT / args.source
            elif (REPO_ROOT / "data" / Path(args.source).name).exists():
                src_path = REPO_ROOT / "data" / Path(args.source).name

        image_exts = {".png", ".jpg", ".jpeg", ".bmp", ".tif", ".tiff", ".webp"}
        if src_path.is_file() and src_path.suffix.lower() in image_exts:
            static_frame = cv2.imread(str(src_path))
            if static_frame is None:
                print(f"Error: Unable to read image file at '{args.source}'")
                sys.exit(1)
            is_image = True
            cap = None
            source_is_cam = False
        else:
            cap = cv2.VideoCapture(str(src_path))
            source_is_cam = False

    if cap is not None and not cap.isOpened():
        print(f"Error: Unable to open video source '{args.source}'")
        sys.exit(1)

    pass_count = 0
    reject_count = 0
    track_verdicts: Dict[int, str] = {}
    track_products: Dict[int, tuple] = {}
    product_counts: Dict[str, Dict[str, int]] = {}

    prev_time = time.time()
    fps = 0.0

    window_name = "QualiScan - Live Pipeline Inspector"
    if not args.no_display:
        try:
            cv2.namedWindow(window_name, cv2.WINDOW_AUTOSIZE)
        except Exception as e:
            print(f"Notice: GUI window unavailable ({e}). Continuing in headless mode.")
            args.no_display = True

    while True:
        if is_image:
            frame = static_frame.copy()
            time.sleep(0.03)
        else:
            ret, frame = cap.read()
            if not ret or frame is None or frame.size == 0:
                if not source_is_cam and args.loop:
                    cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
                    ret, frame = cap.read()
                if not ret or frame is None or frame.size == 0:
                    if source_is_cam:
                        print("Camera stream disconnected or ended.")
                    else:
                        print("End of video stream reached.")
                    break

        # Calculate FPS
        curr_time = time.time()
        dt = curr_time - prev_time
        prev_time = curr_time
        if dt > 0:
            fps = 0.9 * fps + 0.1 * (1.0 / dt) if fps > 0 else (1.0 / dt)

        # 1. Segment items from conveyor background
        items, _ = segment_items(
            frame,
            min_area=args.min_area,
            light_objects=light_objects
        )

        # 2. Update centroid tracker
        tracker.update(items)

        # 3. Check line crossing and inspect
        for track in tracker.tracks.values():
            if track.disappeared > 0:
                continue

            if not track.inspected and trigger_line.crossed(
                track.prev_center,
                track.item.center,
                frame.shape
            ):
                result = inspect(track.item, rules, model=model, profiles=profiles, reference_path=reference_path)
                track.inspected = True
                verdict = result["verdict"]
                track_verdicts[track.id] = verdict
                product = result.get("product", "unknown")
                confidence = float(result.get("confidence", 0.0))
                track_products[track.id] = (product, confidence)
                if verdict in ("PASS", "REJECT"):
                    product_counts.setdefault(product, {"PASS": 0, "REJECT": 0})[verdict] += 1

                if verdict == "PASS":
                    pass_count += 1
                elif verdict == "REJECT":
                    reject_count += 1

                # Timestamp for logging and snapshot
                now = datetime.now()
                iso_ts = now.isoformat()
                file_ts = now.strftime("%Y%m%d_%H%M%S_%f")

                # Console JSON output (one line per inspected item)
                json_log = {
                    "track_id": track.id,
                    "timestamp": iso_ts,
                    "verdict": verdict,
                    "product": product,
                    "confidence": round(confidence, 4),
                    "failed_checks": result["failed_checks"],
                    "area": round(float(track.item.area), 2),
                    "circularity": round(float(track.item.circularity), 4),
                    "aspect_ratio": round(float(track.item.aspect_ratio), 4),
                    "mean_hsv": [round(float(v), 2) for v in track.item.mean_hsv],
                }
                print(json.dumps(json_log), flush=True)

                # Save cropped snapshot
                x, y, w, h = track.item.bbox
                h_f, w_f = frame.shape[:2]
                x1 = max(0, x)
                y1 = max(0, y)
                x2 = min(w_f, x + w)
                y2 = min(h_f, y + h)

                cropped_snapshot = frame[y1:y2, x1:x2]
                if cropped_snapshot.size > 0:
                    snapshot_filename = f"{file_ts}_track{track.id}_{verdict}.jpg"
                    snapshot_path = SNAPSHOTS_DIR / snapshot_filename
                    cv2.imwrite(str(snapshot_path), cropped_snapshot)

        # 4. Annotate display frame
        if not args.no_display:
            display_frame = frame.copy()
            h_f, w_f = display_frame.shape[:2]

            # Draw trigger line
            (lx1, ly1), (lx2, ly2) = trigger_line.get_line_coords(display_frame.shape)
            cv2.line(display_frame, (lx1, ly1), (lx2, ly2), (0, 220, 255), 2)

            line_tag = f"TRIGGER [{args.direction.upper()}]"
            tag_x = lx1 + 5 if trigger_line.orientation == "vertical" else 10
            tag_y = 25 if trigger_line.orientation == "vertical" else ly1 - 6
            cv2.putText(
                display_frame,
                line_tag,
                (tag_x, tag_y),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.45,
                (0, 220, 255),
                1,
                cv2.LINE_AA
            )

            # Draw tracked items
            for track in tracker.tracks.values():
                if track.disappeared > 0:
                    continue

                x, y, w, h = track.item.bbox
                verdict = track_verdicts.get(track.id, None)

                # Color coding: Blue = not inspected, Green = PASS, Red = REJECT
                if verdict == "UNKNOWN":
                    color = (150, 150, 150)
                    status_text = "UNKNOWN"
                elif not track.inspected or verdict is None:
                    color = (255, 160, 0)  # Blue/Orange in BGR
                    status_text = "UNINSPECTED"
                elif verdict == "PASS":
                    color = (0, 255, 0)    # Green
                    status_text = "PASS"
                else:
                    color = (0, 0, 255)    # Red
                    status_text = "REJECT"

                cv2.rectangle(display_frame, (x, y), (x + w, y + h), color, 2)
                product, confidence = track_products.get(track.id, ("?", 0.0))
                label = f"{product} {confidence:.2f} | ID:{track.id} [{status_text}]"
                label_y = max(20, y - 6)
                cv2.putText(
                    display_frame,
                    label,
                    (x, label_y),
                    cv2.FONT_HERSHEY_SIMPLEX,
                    0.48,
                    color,
                    2,
                    cv2.LINE_AA
                )

            # Top HUD banner for counts and FPS
            hud_h = 58
            hud = np.zeros((hud_h, w_f, 3), dtype=np.uint8)
            hud[:] = (20, 24, 30)

            hud_text = (
                f"PASS: {pass_count}  |  "
                f"REJECT: {reject_count}  |  "
                f"TOTAL: {pass_count + reject_count}  |  "
                f"FPS: {fps:.1f}"
            )
            cv2.putText(
                hud,
                hud_text,
                (12, 23),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.55,
                (0, 255, 200),
                1,
                cv2.LINE_AA
            )
            per_product = "  ".join(f"{product}: P{counts['PASS']}/R{counts['REJECT']}" for product, counts in sorted(product_counts.items()))
            cv2.putText(hud, per_product[:180], (12, 48), cv2.FONT_HERSHEY_SIMPLEX, 0.43, (180, 220, 255), 1, cv2.LINE_AA)

            annotated_screen = np.vstack([hud, display_frame])
            cv2.imshow(window_name, annotated_screen)

            key = cv2.waitKey(1) & 0xFF
            if key in (ord("q"), ord("Q"), 27):
                break

    if cap is not None:
        cap.release()
    if not args.no_display:
        cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
