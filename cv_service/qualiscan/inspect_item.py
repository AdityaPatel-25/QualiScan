"""Item inspection module for QualiScan.

Applies configurable size, color, and shape defect checks against segmented items.
"""

from typing import Any, Dict, List, Optional

from qualiscan.checks import CheckResult, check_color, check_shape, check_size
from qualiscan.segment import Item

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


def inspect(item: Item, rules: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
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
