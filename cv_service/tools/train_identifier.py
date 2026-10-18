"""Train and evaluate the classical QualiScan product identifier."""
import csv
from pathlib import Path
import sys

TOOLS_DIR = Path(__file__).resolve().parent
CV_SERVICE_DIR = TOOLS_DIR.parent
REPO_ROOT = CV_SERVICE_DIR.parent
sys.path.insert(0, str(CV_SERVICE_DIR))

import joblib
import numpy as np
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix
from sklearn.model_selection import GroupShuffleSplit
from sklearn.tree import DecisionTreeClassifier, export_text
from sklearn.ensemble import RandomForestClassifier

FEATURES = ["circularity", "aspect_ratio", "solidity", "edge_density", "v_std", "h", "s", "v"]
DATA_PATH = REPO_ROOT / "data" / "features.csv"


def main() -> None:
    rows = list(csv.DictReader(DATA_PATH.open(encoding="utf-8", newline=""))) if DATA_PATH.exists() else []
    if rows and "group" in rows[0] and "product" in rows[0]:
        products = {row["product"] for row in rows}
        for product in sorted(products):
            subset = [row for row in rows if row["product"] == product]
            groups = {row["group"] for row in subset}
            if len(groups) < 3 or len(subset) < 40:
                print(f"WARNING: {product} has {len(groups)} groups and {len(subset)} rows; target is at least 3 groups and 40 rows.")
        if len(products) < 2:
            print("WARNING: only one product exists; a product identifier needs multiple product classes.")
        X = np.asarray([[float(row[col]) for col in FEATURES] for row in rows])
        y = np.asarray([row["product"] for row in rows])
        groups = np.asarray([row["group"] for row in rows])
        splitter = GroupShuffleSplit(n_splits=1, test_size=0.3, random_state=42)
        train_idx, test_idx = next(splitter.split(X, y, groups))
    else:
        print("WARNING: real grouped feature data is unavailable; training output below uses SYNTHETIC rows.")
        rng = np.random.default_rng(42)
        names = ["cap", "card", "biscuit"]
        X = np.vstack([rng.normal(loc=i * 3.0, scale=0.2, size=(60, len(FEATURES))) for i in range(3)])
        y = np.repeat(names, 60)
        groups = np.tile(np.repeat(np.arange(6), 10), 3)
        train_idx, test_idx = next(GroupShuffleSplit(n_splits=1, test_size=0.3, random_state=42).split(X, y, groups))

    models = [("DecisionTree", DecisionTreeClassifier(max_depth=4, random_state=42)), ("RandomForest", RandomForestClassifier(n_estimators=50, random_state=42))]
    scores = []
    for name, model in models:
        model.fit(X[train_idx], y[train_idx])
        accuracy = accuracy_score(y[test_idx], model.predict(X[test_idx]))
        scores.append((accuracy, name, model))
        print(f"{name} test accuracy: {accuracy:.4f}")
    _, name, best = max(scores, key=lambda row: row[0])
    predictions = best.predict(X[test_idx])
    print(f"Best model: {name}")
    print("Confusion matrix:")
    print(confusion_matrix(y[test_idx], predictions, labels=best.classes_))
    print("Classification report:")
    print(classification_report(y[test_idx], predictions, zero_division=0))
    model_path = CV_SERVICE_DIR / "models" / "identifier.joblib"
    model_path.parent.mkdir(parents=True, exist_ok=True)
    joblib.dump(best, model_path)
    tree = next(model for _, model_name, model in scores if model_name == "DecisionTree")
    rules_path = REPO_ROOT / "docs" / "identifier_rules.txt"
    rules_path.parent.mkdir(parents=True, exist_ok=True)
    rules_path.write_text(export_text(tree, feature_names=FEATURES), encoding="utf-8")
    print(f"Saved model: {model_path}")
    print(f"Saved tree rules: {rules_path}")


if __name__ == "__main__":
    main()
