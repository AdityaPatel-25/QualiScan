"""Unit tests for QualiScan inspection checks and trigger line crossing logic."""

from pathlib import Path
import sys

# Ensure cv_service root is on sys.path for test imports
TESTS_DIR = Path(__file__).resolve().parent
CV_SERVICE_DIR = TESTS_DIR.parent
if str(CV_SERVICE_DIR) not in sys.path:
    sys.path.insert(0, str(CV_SERVICE_DIR))

import pytest

from qualiscan.checks import check_color, check_shape, check_size
from qualiscan.zone import TriggerLine


class TestSizeCheck:
    """Tests for check_size pure function."""

    def test_size_within_range(self) -> None:
        result = check_size(area=1500.0, min_area=800.0, max_area=2500.0)
        assert result.passed is True
        assert result.name == "size"
        assert result.value == 1500.0
        assert "within expected" in result.reason.lower()

    def test_size_below_minimum(self) -> None:
        result = check_size(area=500.0, min_area=800.0, max_area=2500.0)
        assert result.passed is False
        assert "below minimum" in result.reason.lower()

    def test_size_above_maximum(self) -> None:
        result = check_size(area=3000.0, min_area=800.0, max_area=2500.0)
        assert result.passed is False
        assert "exceeds maximum" in result.reason.lower()

    def test_size_at_boundaries(self) -> None:
        min_res = check_size(area=800.0, min_area=800.0, max_area=2500.0)
        max_res = check_size(area=2500.0, min_area=800.0, max_area=2500.0)
        assert min_res.passed is True
        assert max_res.passed is True


class TestColorCheck:
    """Tests for check_color pure function and hue wrap-around."""

    def test_color_standard_range_pass(self) -> None:
        # Standard green/cyan hue ~ 40-70
        result = check_color(
            mean_hsv=(55.0, 150.0, 180.0),
            h_range=(40.0, 70.0),
            s_range=(100.0, 255.0),
            v_range=(100.0, 255.0),
        )
        assert result.passed is True
        assert "within expected" in result.reason.lower()

    def test_color_standard_range_fail(self) -> None:
        # Hue out of range
        result = check_color(
            mean_hsv=(20.0, 150.0, 180.0),
            h_range=(40.0, 70.0),
            s_range=(100.0, 255.0),
            v_range=(100.0, 255.0),
        )
        assert result.passed is False
        assert "hue" in result.reason.lower()

    def test_color_saturation_and_value_fail(self) -> None:
        result = check_color(
            mean_hsv=(55.0, 30.0, 50.0),
            h_range=(40.0, 70.0),
            s_range=(100.0, 255.0),
            v_range=(100.0, 255.0),
        )
        assert result.passed is False
        assert "saturation" in result.reason.lower()
        assert "value" in result.reason.lower()

    def test_color_hue_wrap_around_red(self) -> None:
        # Red spans 170-180 and 0-10
        red_range = (170.0, 10.0)
        s_range = (100.0, 255.0)
        v_range = (100.0, 255.0)

        # Case 1: High red hue (175)
        res_high_red = check_color(
            mean_hsv=(175.0, 180.0, 200.0),
            h_range=red_range,
            s_range=s_range,
            v_range=v_range,
        )
        assert res_high_red.passed is True

        # Case 2: Low red hue (5)
        res_low_red = check_color(
            mean_hsv=(5.0, 180.0, 200.0),
            h_range=red_range,
            s_range=s_range,
            v_range=v_range,
        )
        assert res_low_red.passed is True

        # Case 3: Exactly on boundaries (0 and 180)
        assert check_color((0.0, 180.0, 200.0), red_range, s_range, v_range).passed is True
        assert check_color((180.0, 180.0, 200.0), red_range, s_range, v_range).passed is True

        # Case 4: Non-red hue (e.g. green 60) should fail
        res_green = check_color(
            mean_hsv=(60.0, 180.0, 200.0),
            h_range=red_range,
            s_range=s_range,
            v_range=v_range,
        )
        assert res_green.passed is False


class TestShapeCheck:
    """Tests for check_shape pure function."""

    def test_shape_within_range(self) -> None:
        result = check_shape(
            circularity=0.92,
            aspect_ratio=1.05,
            circ_range=(0.80, 1.05),
            aspect_range=(0.95, 1.20),
        )
        assert result.passed is True
        assert "within expected" in result.reason.lower()

    def test_shape_circularity_fail(self) -> None:
        result = check_shape(
            circularity=0.65,
            aspect_ratio=1.05,
            circ_range=(0.80, 1.05),
            aspect_range=(0.95, 1.20),
        )
        assert result.passed is False
        assert "circularity" in result.reason.lower()

    def test_shape_aspect_ratio_fail(self) -> None:
        result = check_shape(
            circularity=0.92,
            aspect_ratio=1.85,
            circ_range=(0.80, 1.05),
            aspect_range=(0.95, 1.20),
        )
        assert result.passed is False
        assert "aspect ratio" in result.reason.lower()


class TestTriggerLineCrossing:
    """Tests for TriggerLine.crossed in all four configured directions."""

    FRAME_SHAPE = (480, 640, 3)  # Height=480, Width=640

    def test_crossed_ltr(self) -> None:
        # Vertical line at x = 0.5 * 640 = 320
        line = TriggerLine(position=0.5, direction="ltr")

        # Crosses left-to-right (from 300 to 330)
        assert line.crossed(prev_center=(300.0, 240.0), curr_center=(330.0, 240.0), frame_shape=self.FRAME_SHAPE) is True

        # Reverse direction right-to-left should NOT trigger ltr
        assert line.crossed(prev_center=(330.0, 240.0), curr_center=(300.0, 240.0), frame_shape=self.FRAME_SHAPE) is False

        # Moving before line without crossing
        assert line.crossed(prev_center=(100.0, 240.0), curr_center=(200.0, 240.0), frame_shape=self.FRAME_SHAPE) is False

        # None prev_center cannot cross
        assert line.crossed(prev_center=None, curr_center=(330.0, 240.0), frame_shape=self.FRAME_SHAPE) is False

    def test_crossed_rtl(self) -> None:
        # Vertical line at x = 0.5 * 640 = 320
        line = TriggerLine(position=0.5, direction="rtl")

        # Crosses right-to-left (from 340 to 310)
        assert line.crossed(prev_center=(340.0, 240.0), curr_center=(310.0, 240.0), frame_shape=self.FRAME_SHAPE) is True

        # Left-to-right does not trigger rtl
        assert line.crossed(prev_center=(310.0, 240.0), curr_center=(340.0, 240.0), frame_shape=self.FRAME_SHAPE) is False

    def test_crossed_ttb(self) -> None:
        # Horizontal line at y = 0.5 * 480 = 240
        line = TriggerLine(position=0.5, direction="ttb")

        # Crosses top-to-bottom (from 220 to 260)
        assert line.crossed(prev_center=(320.0, 220.0), curr_center=(320.0, 260.0), frame_shape=self.FRAME_SHAPE) is True

        # Bottom-to-top does not trigger ttb
        assert line.crossed(prev_center=(320.0, 260.0), curr_center=(320.0, 220.0), frame_shape=self.FRAME_SHAPE) is False

    def test_crossed_btt(self) -> None:
        # Horizontal line at y = 0.5 * 480 = 240
        line = TriggerLine(position=0.5, direction="btt")

        # Crosses bottom-to-top (from 260 to 220)
        assert line.crossed(prev_center=(320.0, 260.0), curr_center=(320.0, 220.0), frame_shape=self.FRAME_SHAPE) is True

        # Top-to-bottom does not trigger btt
        assert line.crossed(prev_center=(320.0, 220.0), curr_center=(320.0, 260.0), frame_shape=self.FRAME_SHAPE) is False
