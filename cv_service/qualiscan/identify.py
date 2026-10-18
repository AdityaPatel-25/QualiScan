"""Classical machine-learning product identification."""
from pathlib import Path
from typing import Any, Tuple

import joblib
import numpy as np

FEATURE_NAMES = ("circularity", "aspect_ratio", "solidity", "edge_density", "v_std", "h", "s", "v")


def feature_vector(item: Any) -> np.ndarray:
    """Build a scale-tolerant feature vector; raw area is deliberately excluded."""
    h, s, v = item.mean_hsv
    return np.asarray([[item.circularity, item.aspect_ratio, item.solidity, item.edge_density, item.v_std, h, s, v]], dtype=float)


def load_model(path: str | Path) -> Any:
    """Load a fitted scikit-learn classifier from disk."""
    return joblib.load(path)


def identify(item: Any, model: Any) -> Tuple[str, float]:
    """Return the most likely product and its probability, or unknown below 0.6."""
    probabilities = model.predict_proba(feature_vector(item))[0]
    index = int(np.argmax(probabilities))
    confidence = float(probabilities[index])
    product = str(model.classes_[index]) if confidence >= 0.6 else "unknown"
    return product, confidence
