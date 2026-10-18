"""Load product inspection profiles from JSON files."""
import json
from pathlib import Path
from typing import Dict


def load_profiles(directory: str | Path) -> Dict[str, dict]:
    """Load profiles keyed by product name (with filename fallback)."""
    profiles = {}
    for path in Path(directory).glob("*.json"):
        with path.open(encoding="utf-8") as stream:
            profile = json.load(stream)
        profiles[str(profile.get("product", path.stem))] = profile
    return profiles
