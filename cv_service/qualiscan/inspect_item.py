"""Item inspection module for QualiScan.

Applies configurable size, color, and shape defect checks against segmented items.
"""

from pathlib import Path
from typing import Any, Dict, List, Optional

from qualiscan.checks import CheckResult, check_color, check_shape, check_size
from qualiscan.segment import Item
from qualiscan.identify import identify
from qualiscan.product_checks import check_broken, check_burnt, check_dent, check_orientation, check_template_match
import cv2
import numpy as np

# Default placeholder rules for general inspection
DEFAULT_RULES: Dict[str, Any] = {
    "size": {
        "min_area": 1000.0,           # Placeholder minimum pixel area
        "max_area": 35000.0,          # Placeholder maximum pixel area
    },
    "color": {
        "h_range": (0.0, 180.0),      # Placeholder full hue spectrum (0-180)
        "s_range": (0.0, 255.0),      # Placeholder saturation range (0-255)
        "v_range": (40.0, 255.0),     # Placeholder brightness range (reject very dark)
    },
    "shape": {
        "circ_range": (0.50, 1.05),   # Placeholder circularity tolerance
        "aspect_range": (0.80, 2.20), # Placeholder aspect ratio tolerance
    },
}


def inspect(item: Item, rules: Optional[Dict[str, Any]] = None, model: Any = None, profiles: Optional[Dict[str, dict]] = None, reference_path: Optional[str] = None) -> Dict[str, Any]:
    """Inspects a segmented item using size, color, and shape rules.

    Args:
        item: Segmented Item instance with extracted features.
        rules: Dictionary containing check rules. If None, the item is only
               measured and verdict is marked 'UNCHECKED'.

    Returns:
        Dictionary containing:
            - verdict: 'PASS', 'REJECT', or 'UNCHECKED'
            - failed_checks: list of check names that failed
            - checks: dictionary of CheckResult objects for each evaluated rule
            - measurements: area, circularity, aspect_ratio, mean_hsv
    """
    if model is not None:
        product, confidence = identify(item, model)
        if product == "unknown":
            return {"product": product, "confidence": confidence, "verdict": "UNKNOWN", "failed_checks": [], "checks": {}, "check_results": {}}
        profiles = profiles or {}
        profile = profiles.get(product)
        if profile is None:
            return {"product": product, "confidence": confidence, "verdict": "UNKNOWN", "failed_checks": [], "checks": {}, "check_results": {}}
        checks: Dict[str, CheckResult] = {}
        enabled = set(profile.get("enabled_checks", []))
        if "size" in enabled:
            checks["size"] = check_size(item.area, profile["min_area"], profile["max_area"])
        if "color" in enabled:
            checks["color"] = check_color(item.mean_hsv, profile["h_range"], profile["s_range"], profile["v_range"])
        if "shape" in enabled:
            checks["shape"] = check_shape(item.circularity, item.aspect_ratio, profile["circularity_range"], profile["aspect_ratio_range"])
        if "dent" in enabled:
            checks["dent"] = check_dent(item.solidity, profile["min_solidity"])
        if "orientation" in enabled:
            rect = cv2.minAreaRect(item.contour)
            angle = float(rect[2]) + (90.0 if rect[1][0] < rect[1][1] else 0.0)
            checks["orientation"] = check_orientation(angle, profile["max_skew_deg"])
        if "template_match" in enabled and reference_path and Path(reference_path).is_file():
            checks["template_match"] = check_template_match(item.crop, cv2.imread(reference_path), profile["max_diff_ratio"])
        if "broken" in enabled:
            checks["broken"] = check_broken(item.solidity, item.area, profile["expected_area_range"], profile["min_solidity"])
        if "burnt" in enabled:
            crop_gray = cv2.cvtColor(item.crop, cv2.COLOR_BGR2GRAY)
            dark_fraction = float(np.count_nonzero(crop_gray < profile["dark_value_threshold"])) / crop_gray.size
            checks["burnt"] = check_burnt(item.mean_hsv[2], dark_fraction, profile["max_dark_fraction"])
        failed = [name for name, result in checks.items() if not result.passed]
        return {"product": product, "confidence": confidence, "verdict": "REJECT" if failed else "PASS", "failed_checks": failed, "checks": checks, "check_results": checks}

    if rules is None:
        return {
            "verdict": "UNCHECKED",
            "failed_checks": [],
            "checks": {},
            "area": item.area,
            "circularity": item.circularity,
            "aspect_ratio": item.aspect_ratio,
            "mean_hsv": item.mean_hsv,
        }

    checks: Dict[str, CheckResult] = {}

    # 1. Size Check
    size_cfg = rules.get("size", {})
    min_area = size_cfg.get("min_area", rules.get("min_area", 0.0))
    max_area = size_cfg.get("max_area", rules.get("max_area", float("inf")))
    checks["size"] = check_size(item.area, min_area, max_area)

    # 2. Color Check
    color_cfg = rules.get("color", {})
    h_range = color_cfg.get("h_range", rules.get("h_range", (0.0, 180.0)))
    s_range = color_cfg.get("s_range", rules.get("s_range", (0.0, 255.0)))
    v_range = color_cfg.get("v_range", rules.get("v_range", (0.0, 255.0)))
    checks["color"] = check_color(item.mean_hsv, h_range, s_range, v_range)

    # 3. Shape Check
    shape_cfg = rules.get("shape", {})
    circ_range = shape_cfg.get("circ_range", rules.get("circ_range", (0.0, 1.0)))
    aspect_range = shape_cfg.get("aspect_range", rules.get("aspect_range", (0.0, 10.0)))
    checks["shape"] = check_shape(
        item.circularity,
        item.aspect_ratio,
        circ_range,
        aspect_range
    )

    failed_checks: List[str] = [
        name for name, result in checks.items() if not result.passed
    ]
    verdict = "PASS" if len(failed_checks) == 0 else "REJECT"

    return {
        "verdict": verdict,
        "failed_checks": failed_checks,
        "checks": checks,
        "area": item.area,
        "circularity": item.circularity,
        "aspect_ratio": item.aspect_ratio,
        "mean_hsv": item.mean_hsv,
    }
