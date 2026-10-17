"""Reusable quality inspection checks for QualiScan.

Provides pure functions to validate physical dimensions, HSV color profiles,
and geometric shape metrics against configurable tolerances.
"""

from dataclasses import dataclass
from typing import Any, List, Tuple, Union


@dataclass
class CheckResult:
    """Outcome of an individual quality rule evaluation.

    Attributes:
        name: Identifier of the inspection rule (e.g. 'size', 'color', 'shape').
        passed: True if item meets the specified tolerance.
        value: Observed measurement from the item.
        expected: Human-readable specification of expected bounds (e.g. '800-2500').
        reason: Explanatory text detailing pass or failure reason.
    """
    name: str
    passed: bool
    value: Any
    expected: str
    reason: str


def check_size(area: float, min_area: float, max_area: float) -> CheckResult:
    """Checks whether contour area falls within minimum and maximum limits.

    Args:
        area: Measured contour pixel area.
        min_area: Lower area threshold.
        max_area: Upper area threshold.

    Returns:
        CheckResult detailing area compliance.
    """
    passed = float(min_area) <= float(area) <= float(max_area)
    expected_str = f"{min_area}-{max_area}"
    observed_val = round(float(area), 2)

    if passed:
        reason = "Area within expected bounds"
    elif float(area) < float(min_area):
        reason = f"Area {observed_val} is below minimum threshold ({min_area})"
    else:
        reason = f"Area {observed_val} exceeds maximum threshold ({max_area})"

    return CheckResult(
        name="size",
        passed=passed,
        value=observed_val,
        expected=expected_str,
        reason=reason,
    )


def _hue_in_range(h: float, h_range: Union[Tuple[float, float], List[Tuple[float, float]]]) -> bool:
    """Checks if hue falls inside range, handling wrap-around across 0 (e.g. red 170-10)."""
    if isinstance(h_range, list) and len(h_range) > 0 and isinstance(h_range[0], (tuple, list)):
        return any(_hue_in_range(h, r) for r in h_range)

    h_min, h_max = float(h_range[0]), float(h_range[1])
    if h_min > h_max:
        # Hue wrap-around (e.g. 170 to 10 covering red boundary)
        return h >= h_min or h <= h_max
    return h_min <= h <= h_max


def check_color(
    mean_hsv: Tuple[float, float, float],
    h_range: Union[Tuple[float, float], List[Tuple[float, float]]],
    s_range: Tuple[float, float],
    v_range: Tuple[float, float]
) -> CheckResult:
    """Checks if mean HSV color values fall within specified ranges.

    Supports hue wrap-around where h_min > h_max (e.g., 170 to 10 for red hues).

    Args:
        mean_hsv: Tuple of (H, S, V) average values.
        h_range: (min_hue, max_hue) range, or list of sub-ranges.
        s_range: (min_saturation, max_saturation) range.
        v_range: (min_value, max_value) range.

    Returns:
        CheckResult detailing color compliance.
    """
    h, s, v = float(mean_hsv[0]), float(mean_hsv[1]), float(mean_hsv[2])

    h_pass = _hue_in_range(h, h_range)
    s_pass = float(s_range[0]) <= s <= float(s_range[1])
    v_pass = float(v_range[0]) <= v <= float(v_range[1])

    passed = h_pass and s_pass and v_pass
    expected_str = f"H:{h_range}, S:{s_range[0]}-{s_range[1]}, V:{v_range[0]}-{v_range[1]}"
    observed_val = (round(h, 2), round(s, 2), round(v, 2))

    if passed:
        reason = "HSV color values within expected ranges"
    else:
        failures = []
        if not h_pass:
            failures.append(f"Hue {h:.1f} not in {h_range}")
        if not s_pass:
            failures.append(f"Saturation {s:.1f} not in [{s_range[0]}, {s_range[1]}]")
        if not v_pass:
            failures.append(f"Value {v:.1f} not in [{v_range[0]}, {v_range[1]}]")
        reason = "; ".join(failures)

    return CheckResult(
        name="color",
        passed=passed,
        value=observed_val,
        expected=expected_str,
        reason=reason,
    )


def check_shape(
    circularity: float,
    aspect_ratio: float,
    circ_range: Tuple[float, float],
    aspect_range: Tuple[float, float]
) -> CheckResult:
    """Checks if circularity and aspect ratio fall within tolerance bounds.

    Args:
        circularity: Contour circularity isoperimetric metric (4*pi*area / perimeter^2).
        aspect_ratio: Aspect ratio (bounding box long side / short side).
        circ_range: (min_circ, max_circ) bounds.
        aspect_range: (min_aspect_ratio, max_aspect_ratio) bounds.

    Returns:
        CheckResult detailing shape compliance.
    """
    c = float(circularity)
    r = float(aspect_ratio)

    circ_pass = float(circ_range[0]) <= c <= float(circ_range[1])
    aspect_pass = float(aspect_range[0]) <= r <= float(aspect_range[1])

    passed = circ_pass and aspect_pass
    expected_str = f"Circ:{circ_range[0]}-{circ_range[1]}, Aspect:{aspect_range[0]}-{aspect_range[1]}"
    observed_val = {"circularity": round(c, 4), "aspect_ratio": round(r, 4)}

    if passed:
        reason = "Shape metrics within expected tolerances"
    else:
        failures = []
        if not circ_pass:
            failures.append(f"Circularity {c:.3f} outside [{circ_range[0]}, {circ_range[1]}]")
        if not aspect_pass:
            failures.append(f"Aspect ratio {r:.3f} outside [{aspect_range[0]}, {aspect_range[1]}]")
        reason = "; ".join(failures)

    return CheckResult(
        name="shape",
        passed=passed,
        value=observed_val,
        expected=expected_str,
        reason=reason,
    )
