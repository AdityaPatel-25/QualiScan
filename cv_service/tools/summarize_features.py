"""QualiScan Feature Summary Tool.

Reads data/features.csv and computes summary statistics (count, min, mean, max)
for extracted features grouped by product and quality label.
"""

import csv
from pathlib import Path
import sys
from typing import Dict, List, Tuple

# Locate CSV relative to cv_service or repository root
TOOLS_DIR = Path(__file__).resolve().parent
REPO_ROOT = TOOLS_DIR.parent.parent
DEFAULT_CSV = REPO_ROOT / "data" / "features.csv"


def summarize(csv_path: Path) -> None:
    """Reads features.csv and prints a formatted statistics table."""
    if not csv_path.exists():
        print(f"Error: Features file not found at '{csv_path}'")
        sys.exit(1)

    grouped_data: Dict[Tuple[str, str], Dict[str, List[float]]] = {}
    feature_keys = ["area", "circularity", "aspect_ratio", "h", "s", "v"]

    with open(csv_path, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        if not reader.fieldnames:
            print(f"Notice: CSV at '{csv_path}' is empty.")
            return

        for row in reader:
            product = row.get("product", "unknown").strip()
            quality = row.get("quality", "unknown").strip()
            key = (product, quality)

            if key not in grouped_data:
                grouped_data[key] = {k: [] for k in feature_keys}

            for k in feature_keys:
                val_str = row.get(k, "")
                if val_str:
                    try:
                        grouped_data[key][k].append(float(val_str))
                    except ValueError:
                        pass

    if not grouped_data:
        print(f"Notice: No data rows found in '{csv_path}'.")
        return

    print("=" * 86)
    print("  QUALISCAN FEATURE METRICS SUMMARY TABLE")
    print(f"  Source: {csv_path}")
    print("=" * 86)
    print(f"{'PRODUCT':<12} {'QUALITY':<10} {'COUNT':<7} {'METRIC':<15} {'MIN':<12} {'MEAN':<12} {'MAX':<12}")
    print("-" * 86)

    for (product, quality), metrics in sorted(grouped_data.items()):
        count = len(metrics["area"])
        for idx, k in enumerate(feature_keys):
            vals = metrics[k]
            if vals:
                v_min = min(vals)
                v_mean = sum(vals) / len(vals)
                v_max = max(vals)
                min_str = f"{v_min:.4f}" if k in ("circularity", "aspect_ratio") else f"{v_min:.2f}"
                mean_str = f"{v_mean:.4f}" if k in ("circularity", "aspect_ratio") else f"{v_mean:.2f}"
                max_str = f"{v_max:.4f}" if k in ("circularity", "aspect_ratio") else f"{v_max:.2f}"
            else:
                min_str = mean_str = max_str = "N/A"

            prod_display = product if idx == 0 else ""
            qual_display = quality if idx == 0 else ""
            count_display = str(count) if idx == 0 else ""

            print(
                f"{prod_display:<12} {qual_display:<10} {count_display:<7} {k:<15} "
                f"{min_str:<12} {mean_str:<12} {max_str:<12}"
            )
        print("-" * 86)


def main() -> None:
    """CLI entry point for feature summarization."""
    csv_file = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_CSV
    summarize(csv_file)


if __name__ == "__main__":
    main()
