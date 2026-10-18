"""Pass/fail cases for the product-specific defect checks."""
from pathlib import Path
import sys

TESTS_DIR = Path(__file__).resolve().parent
CV_SERVICE_DIR = TESTS_DIR.parent
sys.path.insert(0, str(CV_SERVICE_DIR))

import numpy as np

from qualiscan.product_checks import check_broken, check_burnt, check_dent, check_orientation, check_template_match


def test_check_dent_pass_fail() -> None:
    assert check_dent(0.95, 0.8).passed
    assert not check_dent(0.5, 0.8).passed


def test_check_orientation_pass_fail() -> None:
    assert check_orientation(2, 5).passed
    assert not check_orientation(8, 5).passed


def test_check_template_match_pass_fail() -> None:
    reference = np.full((20, 30, 3), 120, dtype=np.uint8)
    same = check_template_match(reference.copy(), reference, 0.01)
    changed = check_template_match(np.zeros_like(reference), reference, 0.01)
    assert same.passed
    assert not changed.passed


def test_check_broken_pass_fail() -> None:
    assert check_broken(0.9, 100, (80, 120), 0.8).passed
    assert not check_broken(0.5, 100, (80, 120), 0.8).passed


def test_check_burnt_pass_fail() -> None:
    assert check_burnt(180, 0.05, 0.1).passed
    assert not check_burnt(50, 0.3, 0.1).passed
