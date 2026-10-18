"""QualiScan Live Viewer and Data-Labeling Tool.

Allows real-time item segmentation visualization, interactive labeling,
and feature extraction saving to data/features.csv.
"""

import argparse
import csv
from pathlib import Path
import sys
from typing import List

# Ensure cv_service root is on sys.path for qualiscan package import
TOOLS_DIR = Path(__file__).resolve().parent
CV_SERVICE_DIR = TOOLS_DIR.parent
if str(CV_SERVICE_DIR) not in sys.path:
    sys.path.insert(0, str(CV_SERVICE_DIR))

import cv2
import numpy as np

from qualiscan.segment import Item, segment_items

# Resolve data directory relative to repository root
REPO_ROOT = CV_SERVICE_DIR.parent
if (REPO_ROOT / "data").exists():
    DATA_DIR = REPO_ROOT / "data"
else:
    DATA_DIR = Path("data").resolve()

CSV_PATH = DATA_DIR / "features.csv"
CSV_COLUMNS = ["group", "product", "quality", "area", "circularity", "aspect_ratio", "solidity", "edge_density", "v_std", "h", "s", "v"]


def prepare_csv(filepath: Path) -> None:
    """Migrate an existing dataset header before writing the new feature format."""
    filepath.parent.mkdir(parents=True, exist_ok=True)
    if filepath.exists() and filepath.stat().st_size:
        with filepath.open("r", encoding="utf-8", newline="") as stream:
            old_header = next(csv.reader(stream), [])
        if old_header != CSV_COLUMNS:
            old_path = filepath.with_name("features_old.csv")
            if old_path.exists():
                raise FileExistsError(f"Refusing to overwrite existing backup: {old_path}")
            filepath.replace(old_path)
    if not filepath.exists() or filepath.stat().st_size == 0:
        with filepath.open("w", encoding="utf-8", newline="") as stream:
            csv.writer(stream).writerow(CSV_COLUMNS)


def count_existing_csv_rows(filepath: Path) -> int:
    """Counts data rows currently saved in the CSV file."""
    if not filepath.exists():
        return 0
    try:
        with open(filepath, "r", encoding="utf-8") as f:
            reader = csv.reader(f)
            header = next(reader, None)
            if header is None:
                return 0
            return sum(1 for row in reader if row)
    except Exception:
        return 0


def append_items_to_csv(filepath: Path, items: List[Item], product: str, quality: str, group: int = 1) -> int:
    """Appends feature vectors for all detected items to data/features.csv."""
    if not items:
        return 0

    prepare_csv(filepath)
    with open(filepath, "a", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)

        for item in items:
            writer.writerow([
                group,
                product,
                quality,
                f"{item.area:.2f}",
                f"{item.circularity:.4f}",
                f"{item.aspect_ratio:.4f}",
                f"{item.solidity:.4f}",
                f"{item.edge_density:.4f}",
                f"{item.v_std:.2f}",
                f"{item.mean_hsv[0]:.2f}",
                f"{item.mean_hsv[1]:.2f}",
                f"{item.mean_hsv[2]:.2f}",
            ])
        f.flush()

    return len(items)


def main() -> None:
    """Main entry point for live exploration and labeling."""
    parser = argparse.ArgumentParser(description="QualiScan Item Exploration and Dataset Labeling Tool")
    parser.add_argument(
        "--source",
        type=str,
        default="0",
        help="Video source: camera index (e.g. '0'), video file path, or image path (default: '0')",
    )
    parser.add_argument(
        "--dark-objects",
        action="store_true",
        help="Flag indicating objects are darker than the conveyor background",
    )
    parser.add_argument(
        "--min-area",
        type=int,
        default=800,
        help="Minimum contour area threshold in pixels (default: 800)",
    )
    args = parser.parse_args()

    light_objects = False if args.dark_objects else None

    # Determine source type: camera index, image file, or video file
    is_image = False
    static_frame = None

    if args.source.isdigit():
        cap = cv2.VideoCapture(int(args.source))
        source_is_cam = True
    else:
        src_path = Path(args.source)
        # Check if source is a single image file
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
            cap = cv2.VideoCapture(args.source)
            source_is_cam = False

    if cap is not None and not cap.isOpened():
        print(f"Error: Unable to open video source '{args.source}'")
        sys.exit(1)

    # Labeling state
    prepare_csv(CSV_PATH)
    current_product = "cap"      # 'cap', 'card', 'biscuit'
    current_quality = "good"     # 'good', 'defect'
    rows_saved = count_existing_csv_rows(CSV_PATH)
    last_saved_count = 0
    show_saved_feedback = 0
    group = 1

    print("=" * 65)
    print("  QUALISCAN EXPLORATION & LABELING TOOL")
    print("=" * 65)
    print("Controls:")
    print("  [1] Product: cap")
    print("  [2] Product: card")
    print("  [3] Product: biscuit")
    print("  [g] Quality: good")
    print("  [d] Quality: defect")
    print("  [SPACE] Save feature row for all visible items to features.csv")
    print("  [n] Start a new capture group")
    print("  [q / ESC] Quit")
    print("=" * 65)

    window_frame_name = "QualiScan - Frame Inspector"
    window_mask_name = "QualiScan - Binary Mask"

    cv2.namedWindow(window_frame_name, cv2.WINDOW_AUTOSIZE)
    cv2.namedWindow(window_mask_name, cv2.WINDOW_AUTOSIZE)

    while True:
        if is_image:
            frame = static_frame.copy()
            wait_delay = 30
        else:
            ret, frame = cap.read()
            if not ret:
                if source_is_cam:
                    print("Warning: Camera feed interrupted.")
                    break
                else:
                    # Video ended - loop video back to start
                    cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
                    ret, frame = cap.read()
                    if not ret:
                        print("End of video stream reached.")
                        break
            wait_delay = 30

        # Segment items
        items, mask = segment_items(
            frame,
            min_area=args.min_area,
            light_objects=light_objects
        )

        display_frame = frame.copy()

        # Draw green bounding boxes and labels for each item
        for item in items:
            x, y, w, h = item.bbox
            cv2.rectangle(display_frame, (x, y), (x + w, y + h), (0, 255, 0), 2)

            label = f"A:{item.area:.0f} C:{item.circularity:.2f} R:{item.aspect_ratio:.2f}"
            label_y = max(18, y - 6)
            # Text shadow for visibility on any background
            cv2.putText(
                display_frame,
                label,
                (x, label_y),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.48,
                (0, 0, 0),
                2,
                cv2.LINE_AA
            )
            cv2.putText(
                display_frame,
                label,
                (x, label_y),
                cv2.FONT_HERSHEY_SIMPLEX,
                0.48,
                (0, 255, 0),
                1,
                cv2.LINE_AA
            )

        # Overlay status banner at bottom
        h_disp, w_disp = display_frame.shape[:2]
        banner_h = 44
        banner = np.zeros((banner_h, w_disp, 3), dtype=np.uint8)
        banner[:] = (20, 24, 32)

        # Status Line text
        status_line = (
            f"PRODUCT: [{current_product.upper()}] (1/2/3)  |  "
            f"QUALITY: [{current_quality.upper()}] (g/d)  |  "
            f"GROUP: {group} (n)  |  "
            f"SAVED: {rows_saved}  |  "
            f"ITEMS IN VIEW: {len(items)}"
        )

        if show_saved_feedback > 0:
            status_line += f"  *** SAVED +{last_saved_count} ROW(S) ***"
            show_saved_feedback -= 1

        help_line = "[SPACE] Save current items   [1] Cap  [2] Card  [3] Biscuit   [g] Good  [d] Defect   [q] Quit"

        cv2.putText(banner, status_line, (10, 18), cv2.FONT_HERSHEY_SIMPLEX, 0.44, (0, 255, 200), 1, cv2.LINE_AA)
        cv2.putText(banner, help_line, (10, 36), cv2.FONT_HERSHEY_SIMPLEX, 0.38, (170, 180, 190), 1, cv2.LINE_AA)

        annotated_display = np.vstack([display_frame, banner])

        cv2.imshow(window_frame_name, annotated_display)
        cv2.imshow(window_mask_name, mask)

        key = cv2.waitKey(wait_delay) & 0xFF

        if key in (ord("q"), ord("Q"), 27):  # 'q' or ESC
            break
        elif key == ord("1"):
            current_product = "cap"
        elif key == ord("2"):
            current_product = "card"
        elif key == ord("3"):
            current_product = "biscuit"
        elif key in (ord("g"), ord("G")):
            current_quality = "good"
        elif key in (ord("d"), ord("D")):
            current_quality = "defect"
        elif key in (ord("n"), ord("N")):
            group += 1
        elif key == 32:  # SPACE
            added = append_items_to_csv(CSV_PATH, items, current_product, current_quality, group)
            if added > 0:
                rows_saved += added
                last_saved_count = added
                show_saved_feedback = 15  # Display banner feedback for ~15 frames
                print(f"[SAVED] Appended {added} row(s) to {CSV_PATH} (Product: {current_product}, Quality: {current_quality})")
            else:
                print("[NOTICE] No items detected in view to save.")

    if cap is not None:
        cap.release()
    cv2.destroyAllWindows()


if __name__ == "__main__":
    main()
