"""Centroid Tracker for QualiScan.

Tracks segmented conveyor items across consecutive frames using Euclidean
distance matching between item centroids.
"""

from dataclasses import dataclass
import math
from typing import Dict, List, Optional, Tuple

from qualiscan.segment import Item


@dataclass
class Track:
    """Represents an active item trajectory across video frames.

    Attributes:
        id: Unique numerical tracking identifier.
        item: Most recently detected Item object.
        prev_center: Previous frame centroid (cx, cy), or None if track is new.
        inspected: True if item has crossed the inspection line and been evaluated.
        disappeared: Consecutive frames where this track was not detected.
    """
    id: int
    item: Item
    prev_center: Optional[Tuple[float, float]] = None
    inspected: bool = False
    disappeared: int = 0

    @property
    def current(self) -> Item:
        """Alias for current Item object."""
        return self.item

    @property
    def previous_center(self) -> Optional[Tuple[float, float]]:
        """Alias for previous centroid coordinates."""
        return self.prev_center


class CentroidTracker:
    """Lightweight nearest-centroid object tracker without external dependencies."""

    def __init__(self, max_distance: float = 80.0, max_disappeared: int = 15) -> None:
        """Initializes tracker parameters.

        Args:
            max_distance: Maximum pixel distance to associate an item with an existing track.
            max_disappeared: Maximum consecutive frames to keep track alive after missing.
        """
        self.max_distance = float(max_distance)
        self.max_disappeared = int(max_disappeared)
        self.next_id: int = 1
        self.tracks: Dict[int, Track] = {}

    @staticmethod
    def _euclidean_dist(p1: Tuple[float, float], p2: Tuple[float, float]) -> float:
        """Calculates Euclidean distance between two 2D points."""
        return math.hypot(p1[0] - p2[0], p1[1] - p2[1])

    def _register(self, item: Item) -> Track:
        """Registers a new track with an auto-incrementing ID."""
        track = Track(
            id=self.next_id,
            item=item,
            prev_center=None,
            inspected=False,
            disappeared=0,
        )
        self.tracks[self.next_id] = track
        self.next_id += 1
        return track

    def update(self, items: List[Item]) -> Dict[int, Item]:
        """Updates tracks with items detected in the current frame.

        Args:
            items: List of Item objects detected in the current frame.

        Returns:
            Dictionary mapping active track IDs to their current Item.
        """
        # If no items were detected in this frame
        if len(items) == 0:
            expired_ids = []
            for track_id, track in self.tracks.items():
                track.disappeared += 1
                if track.disappeared > self.max_disappeared:
                    expired_ids.append(track_id)
            for track_id in expired_ids:
                del self.tracks[track_id]
            return {t_id: t.item for t_id, t in self.tracks.items() if t.disappeared == 0}

        # If no active tracks exist, register all current items
        if len(self.tracks) == 0:
            for item in items:
                self._register(item)
            return {t_id: t.item for t_id, t in self.tracks.items()}

        # Existing tracks and candidates
        track_ids = list(self.tracks.keys())
        track_centers = [self.tracks[tid].item.center for tid in track_ids]
        item_centers = [item.center for item in items]

        # Compute pairwise distances between all tracks and items
        pair_distances = []
        for t_idx, t_center in enumerate(track_centers):
            for i_idx, i_center in enumerate(item_centers):
                dist = self._euclidean_dist(t_center, i_center)
                pair_distances.append((dist, t_idx, i_idx))

        # Sort pairs in ascending order of distance (greedy nearest match)
        pair_distances.sort(key=lambda x: x[0])

        used_track_indices = set()
        used_item_indices = set()

        for dist, t_idx, i_idx in pair_distances:
            if t_idx in used_track_indices or i_idx in used_item_indices:
                continue
            if dist > self.max_distance:
                # No closer associations below threshold
                break

            tid = track_ids[t_idx]
            track = self.tracks[tid]
            track.prev_center = track.item.center
            track.item = items[i_idx]
            track.disappeared = 0

            used_track_indices.add(t_idx)
            used_item_indices.add(i_idx)

        # Mark unmatched existing tracks as disappeared
        for t_idx, tid in enumerate(track_ids):
            if t_idx not in used_track_indices:
                self.tracks[tid].disappeared += 1

        # Remove tracks that exceeded max_disappeared
        expired_ids = [
            tid for tid, track in self.tracks.items()
            if track.disappeared > self.max_disappeared
        ]
        for tid in expired_ids:
            del self.tracks[tid]

        # Register remaining unmatched items as new tracks
        for i_idx, item in enumerate(items):
            if i_idx not in used_item_indices:
                self._register(item)

        return {t_id: t.item for t_id, t in self.tracks.items() if t.disappeared == 0}
