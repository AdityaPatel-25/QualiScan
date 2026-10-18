"""Capture or load a good card, rectify it, and save the template image."""
import argparse
from pathlib import Path
import sys

TOOLS_DIR = Path(__file__).resolve().parent
CV_SERVICE_DIR = TOOLS_DIR.parent
sys.path.insert(0, str(CV_SERVICE_DIR))

import cv2
import numpy as np


def rectify(image: np.ndarray) -> np.ndarray:
    """Find the largest card-like contour and warp it to a top-down view."""
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    edges = cv2.Canny(cv2.GaussianBlur(gray, (5, 5), 0), 50, 150)
    contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if not contours:
        raise ValueError("No card contour found")
    contour = max(contours, key=cv2.contourArea)
    rect = cv2.minAreaRect(contour)
    box = cv2.boxPoints(rect).astype("float32")
    sums, diffs = box.sum(axis=1), np.diff(box, axis=1).ravel()
    points = np.array([box[np.argmin(sums)], box[np.argmin(diffs)], box[np.argmax(sums)], box[np.argmax(diffs)]], dtype="float32")
    width = max(2, int(rect[1][0] if rect[1][0] >= rect[1][1] else rect[1][1]))
    height = max(2, int(min(rect[1])))
    target = np.array([[0, 0], [width - 1, 0], [width - 1, height - 1], [0, height - 1]], dtype="float32")
    transform = cv2.getPerspectiveTransform(points, target)
    return cv2.warpPerspective(image, transform, (width, height))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", nargs="?", help="Good card image path; omit to capture from webcam")
    parser.add_argument("--camera", type=int, default=0, help="Webcam index (default: 0)")
    args = parser.parse_args()
    if args.image:
        image = cv2.imread(args.image)
        if image is None:
            raise SystemExit(f"Could not read image: {args.image}")
    else:
        camera = cv2.VideoCapture(args.camera)
        try:
            while True:
                ok, frame = camera.read()
                if not ok:
                    raise SystemExit("Could not read from webcam")
                cv2.imshow("Card reference - press SPACE to capture, Q to quit", frame)
                key = cv2.waitKey(1) & 0xFF
                if key == 32:
                    image = frame
                    break
                if key in (ord("q"), 27):
                    return
        finally:
            camera.release()
            cv2.destroyAllWindows()
    result = rectify(image)
    output = CV_SERVICE_DIR / "profiles" / "card_reference.png"
    output.parent.mkdir(parents=True, exist_ok=True)
    if not cv2.imwrite(str(output), result):
        raise SystemExit(f"Could not save reference: {output}")
    print(f"Saved rectified card reference: {output}")


if __name__ == "__main__":
    main()
