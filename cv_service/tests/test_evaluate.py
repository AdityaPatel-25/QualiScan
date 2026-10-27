"""Unit tests for held-out evaluation tool metric calculations and warnings.

Tests identification metrics, confusion matrix, false-reject rate (FRR),
miss rate (MR), defect-type accuracy, and data leakage detection using
synthetic hand-made labeled fixtures with exact known expected values.
"""

from pathlib import Path
import sys
from typing import List

# Ensure cv_service root is on sys.path
TESTS_DIR = Path(__file__).resolve().parent
CV_SERVICE_DIR = TESTS_DIR.parent
if str(CV_SERVICE_DIR) not in sys.path:
    sys.path.insert(0, str(CV_SERVICE_DIR))

from tools.evaluate import (
    check_leakage,
    check_sample_counts,
    compute_defect_detection_metrics,
    compute_defect_type_accuracy,
    compute_identification_metrics,
    defect_type_matches,
)


def test_identification_metrics_and_confusion_matrix() -> None:
    """Test identification accuracy, confusion matrix, and precision/recall/F1.

    Synthetic Hand-Made Fixture:
    - 8 samples total across 'cap', 'card', 'biscuit'.
    - Known ground truth vs predictions designed with specific TP, FP, FN counts.
    """
    y_true: List[str] = [
        "cap", "cap", "cap",
        "card", "card",
        "biscuit", "biscuit", "biscuit",
    ]
    y_pred: List[str] = [
        "cap", "cap", "card",       # 2 correct cap, 1 misclassified as card
        "card", "unknown",          # 1 correct card, 1 unknown
        "biscuit", "biscuit", "cap" # 2 correct biscuit, 1 misclassified as cap
    ]

    metrics = compute_identification_metrics(y_true, y_pred)

    # 1. Total samples & overall accuracy
    # Correct: 2 caps + 1 card + 2 biscuits = 5 / 8 = 0.625
    assert metrics["total_samples"] == 8
    assert metrics["accuracy"] == 0.625

    # 2. Confusion matrix verification
    # Rows: true, Cols: predicted
    cm = metrics["confusion_matrix"]
    assert cm["biscuit"]["biscuit"] == 2
    assert cm["biscuit"]["cap"] == 1
    assert cm["biscuit"]["card"] == 0
    assert cm["biscuit"].get("unknown", 0) == 0

    assert cm["cap"]["cap"] == 2
    assert cm["cap"]["card"] == 1
    assert cm["cap"]["biscuit"] == 0
    assert cm["cap"].get("unknown", 0) == 0

    assert cm["card"]["card"] == 1
    assert cm["card"]["unknown"] == 1
    assert cm["card"]["cap"] == 0
    assert cm["card"]["biscuit"] == 0

    # 3. Per-product precision, recall, F1
    per_prod = metrics["per_product"]

    # Biscuit: TP=2, FP=0, FN=1 (predicted as cap)
    # Precision = 2/2 = 1.0, Recall = 2/3 = 0.6667
    assert per_prod["biscuit"]["support"] == 3
    assert per_prod["biscuit"]["precision"] == 1.0
    assert per_prod["biscuit"]["recall"] == 0.6667
    assert per_prod["biscuit"]["f1"] == 0.8

    # Cap: TP=2, FP=1 (from biscuit), FN=1 (to card)
    # Precision = 2/3 = 0.6667, Recall = 2/3 = 0.6667
    assert per_prod["cap"]["support"] == 3
    assert per_prod["cap"]["precision"] == 0.6667
    assert per_prod["cap"]["recall"] == 0.6667
    assert per_prod["cap"]["f1"] == 0.6667

    # Card: TP=1, FP=1 (from cap), FN=1 (to unknown)
    # Precision = 1/2 = 0.5, Recall = 1/2 = 0.5
    assert per_prod["card"]["support"] == 2
    assert per_prod["card"]["precision"] == 0.5
    assert per_prod["card"]["recall"] == 0.5
    assert per_prod["card"]["f1"] == 0.5


def test_defect_detection_false_reject_and_miss_rate() -> None:
    """Test defect detection metrics: Precision, Recall, False-Reject Rate (FRR), Miss Rate (MR).

    Synthetic Hand-Made Fixture:
    - 4 Good items: 3 correctly PASS (TN), 1 incorrectly REJECT (FP / False Reject)
    - 6 Defect items: 4 correctly REJECT (TP), 2 incorrectly PASS (FN / Miss)
    """
    expected_qualities: List[str] = [
        "good", "good", "good", "good",
        "defect", "defect", "defect", "defect", "defect", "defect",
    ]
    predicted_verdicts: List[str] = [
        "PASS", "PASS", "PASS", "REJECT",   # 3 TN, 1 FP (False Reject)
        "REJECT", "REJECT", "REJECT", "REJECT", "PASS", "PASS",  # 4 TP, 2 FN (Miss)
    ]

    m = compute_defect_detection_metrics(expected_qualities, predicted_verdicts)

    assert m["total_good"] == 4
    assert m["total_defect"] == 6
    assert m["tp"] == 4
    assert m["fp"] == 1
    assert m["tn"] == 3
    assert m["fn"] == 2

    # Precision for REJECT: TP / (TP + FP) = 4 / (4 + 1) = 0.8
    assert m["precision_reject"] == 0.8

    # Recall for REJECT: TP / (TP + FN) = 4 / (4 + 2) = 4/6 = 0.6667
    assert m["recall_reject"] == 0.6667

    # False-Reject Rate (FRR): good items rejected / all good items = 1 / 4 = 0.25 (25%)
    assert m["false_reject_rate"] == 0.25

    # Miss Rate (MR): defective items passed / all defective items = 2 / 6 = 0.3333 (33.33%)
    assert m["miss_rate"] == 0.3333


def test_defect_type_accuracy_matching() -> None:
    """Test defect-type accuracy computation and alias matching for correctly rejected items.

    Synthetic Hand-Made Fixture:
    - 5 defective items correctly rejected (REJECT):
      1. Expected 'dent', failed ['dent', 'size'] -> Match
      2. Expected 'broken_or_chipped', failed ['broken'] -> Match (alias)
      3. Expected 'burnt', failed ['burnt'] -> Match
      4. Expected 'undersized', failed ['size'] -> Match (alias)
      5. Expected 'surface_crack', failed ['color'] -> Mismatch
    - Matched: 4 / 5 = 0.8 (80%)
    """
    expected_qualities = ["defect"] * 5
    expected_defects = [
        "dent",
        "broken_or_chipped",
        "burnt",
        "undersized",
        "surface_crack",
    ]
    predicted_verdicts = ["REJECT"] * 5
    reported_failed_checks = [
        ["dent", "size"],
        ["broken"],
        ["burnt"],
        ["size"],
        ["color"],
    ]

    res = compute_defect_type_accuracy(
        expected_qualities,
        expected_defects,
        predicted_verdicts,
        reported_failed_checks,
    )

    assert res["eligible_count"] == 5
    assert res["matched_count"] == 4
    assert res["defect_type_accuracy"] == 0.8

    # Also test individual alias matching helper
    assert defect_type_matches("dent", ["dent"]) is True
    assert defect_type_matches("broken_or_chipped", ["broken"]) is True
    assert defect_type_matches("undersized", ["size"]) is True
    assert defect_type_matches("surface_scratch", ["template_match"]) is True
    assert defect_type_matches("dent", ["color"]) is False


def test_data_leakage_detection(tmp_path: Path) -> None:
    """Test detection of evaluation samples accidentally present in features.csv.

    Synthetic Hand-Made Test:
    - Creates a temporary features.csv with known filenames.
    - Asserts that matching eval filenames are flagged as leaked.
    """
    fake_features = tmp_path / "features.csv"
    fake_features.write_text(
        "group,product,quality,file,area,circularity\n"
        "1,cap,good,cap_eval_01.png,9500,0.91\n"
        "1,card,defect,card_train_02.png,15000,0.65\n",
        encoding="utf-8",
    )

    eval_files = [
        "cap_eval_01.png",       # Present in features.csv -> Leak!
        "cap_eval_clean_99.png", # Not present -> Clean
    ]

    leaks = check_leakage(eval_files, fake_features)
    assert leaks == ["cap_eval_01.png"]

    # When no leakage occurs
    clean_eval = ["pure_held_out_01.png", "pure_held_out_02.png"]
    no_leaks = check_leakage(clean_eval, fake_features)
    assert no_leaks == []


def test_low_sample_count_warnings() -> None:
    """Test low sample size warnings when any product has fewer than 10 samples."""
    product_counts = {
        "biscuit": 12,  # >= 10: OK
        "cap": 4,       # < 10: Warning!
        "card": 9,      # < 10: Warning!
    }

    warnings = check_sample_counts(product_counts, min_samples=10)
    assert len(warnings) == 2
    assert any("cap" in w and "4 sample(s)" in w for w in warnings)
    assert any("card" in w and "9 sample(s)" in w for w in warnings)

    # When all products meet threshold
    ok_counts = {"biscuit": 20, "cap": 15, "card": 10}
    assert check_sample_counts(ok_counts, min_samples=10) == []
