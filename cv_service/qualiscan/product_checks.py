"""Product-specific defect checks using configurable thresholds."""
from typing import Tuple

import cv2
import numpy as np

from qualiscan.checks import CheckResult, check_size


def check_dent(solidity: float, min_solidity: float) -> CheckResult:
    passed = float(solidity) >= float(min_solidity)
    return CheckResult("dent", passed, round(float(solidity), 4), str(min_solidity), "Solidity meets dent threshold" if passed else "Low solidity may indicate a dent")


def check_orientation(angle: float, max_skew_deg: float) -> CheckResult:
    passed = abs(float(angle)) <= float(max_skew_deg)
    return CheckResult("orientation", passed, round(float(angle), 2), f"±{max_skew_deg} degrees", "Orientation within tolerance" if passed else "Card skew exceeds tolerance")


def check_template_match(crop: np.ndarray, reference_image: np.ndarray, max_diff_ratio: float) -> CheckResult:
    """Align crop to reference dimensions and compare differing pixel fraction."""
    if crop is None or reference_image is None or crop.size == 0 or reference_image.size == 0:
        raise ValueError("crop and reference_image must be non-empty images")
    gray_crop = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY) if crop.ndim == 3 else crop
    edges = cv2.Canny(cv2.GaussianBlur(gray_crop, (5, 5), 0), 50, 150)
    contours, _ = cv2.findContours(edges, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    if contours:
        rect = cv2.minAreaRect(max(contours, key=cv2.contourArea))
        box = cv2.boxPoints(rect).astype(np.float32)
        sums, diffs = box.sum(axis=1), np.diff(box, axis=1).ravel()
        source = np.array([box[np.argmin(sums)], box[np.argmin(diffs)], box[np.argmax(sums)], box[np.argmax(diffs)]], dtype=np.float32)
        width = max(2, int(max(rect[1])))
        height = max(2, int(min(rect[1])))
        target = np.array([[0, 0], [width - 1, 0], [width - 1, height - 1], [0, height - 1]], dtype=np.float32)
        straight = cv2.warpPerspective(crop, cv2.getPerspectiveTransform(source, target), (width, height))
    else:
        straight = crop
    resized = cv2.resize(straight, (reference_image.shape[1], reference_image.shape[0]))
    difference = cv2.absdiff(resized, reference_image)
    gray = cv2.cvtColor(difference, cv2.COLOR_BGR2GRAY) if difference.ndim == 3 else difference
    _, changed = cv2.threshold(gray, 25, 255, cv2.THRESH_BINARY)
    ratio = float(cv2.countNonZero(changed)) / changed.size
    passed = ratio <= float(max_diff_ratio)
    return CheckResult("template_match", passed, round(ratio, 4), f"≤{max_diff_ratio}", "Template difference within tolerance" if passed else "Template difference exceeds tolerance")


def check_broken(solidity: float, area: float, expected_area_range: Tuple[float, float], min_solidity: float) -> CheckResult:
    area_ok = float(expected_area_range[0]) <= float(area) <= float(expected_area_range[1])
    solidity_ok = float(solidity) >= float(min_solidity)
    passed = area_ok and solidity_ok
    return CheckResult("broken", passed, {"solidity": round(float(solidity), 4), "area": float(area)}, f"area={expected_area_range}, solidity≥{min_solidity}", "Shape and area indicate an intact biscuit" if passed else "Area or solidity may indicate a broken biscuit")


def check_burnt(mean_v: float, dark_pixel_fraction: float, max_dark_fraction: float) -> CheckResult:
    passed = float(dark_pixel_fraction) <= float(max_dark_fraction)
    return CheckResult("burnt", passed, {"mean_v": float(mean_v), "dark_pixel_fraction": float(dark_pixel_fraction)}, f"dark fraction ≤{max_dark_fraction}", "Dark pixel fraction within tolerance" if passed else "Too many dark pixels may indicate burning")


__all__ = ["CheckResult", "check_size", "check_dent", "check_orientation", "check_template_match", "check_broken", "check_burnt"]
