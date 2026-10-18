"""Product identification tests with controlled synthetic data."""
from pathlib import Path
import sys

TESTS_DIR = Path(__file__).resolve().parent
CV_SERVICE_DIR = TESTS_DIR.parent
sys.path.insert(0, str(CV_SERVICE_DIR))

import numpy as np
from sklearn.tree import DecisionTreeClassifier

from qualiscan.identify import identify
from qualiscan.segment import Item


def _item(features: list[float]) -> Item:
    return Item(np.empty((0, 1, 2), dtype=np.int32), (0, 0, 4, 4), (2, 2), 10, *features[:2], (features[5], features[6], features[7]), features[2], features[3], features[4], np.zeros((4, 4, 3), dtype=np.uint8))


def test_identifies_right_product() -> None:
    rng = np.random.default_rng(7)
    cap_center = np.array([0, 1, 1, 0, 2, 30, 100, 200], dtype=float)
    card_center = np.array([0.7, 2, 0.8, 0.2, 20, 110, 40, 100], dtype=float)
    X = np.vstack([cap_center + rng.normal(0, 0.01, (20, 8)), card_center + rng.normal(0, 0.01, (20, 8))])
    model = DecisionTreeClassifier(max_depth=2).fit(X, ["cap"] * 20 + ["card"] * 20)
    product, confidence = identify(_item([0, 1, 1, 0, 2, 30, 100, 200]), model)
    assert product == "cap"
    assert confidence >= 0.6


def test_returns_unknown_below_threshold() -> None:
    class UncertainModel:
        classes_ = np.array(["cap", "card"])

        def predict_proba(self, _X: np.ndarray) -> np.ndarray:
            return np.array([[0.55, 0.45]])

    product, confidence = identify(_item([0, 1, 1, 0, 2, 30, 100, 200]), UncertainModel())
    assert product == "unknown"
    assert confidence == 0.55
