"""Trigger Line zone detection for QualiScan.

Detects when an item's centroid moves across a defined conveyor trigger line
in a specified direction (left-to-right, right-to-left, top-to-bottom, bottom-to-top).
"""

from typing import Optional, Tuple


class TriggerLine:
    """Configurable inspection trigger line for conveyor tracking."""

    def __init__(
        self,
        orientation: str = "vertical",
        position: float = 0.5,
        direction: str = "ltr"
    ) -> None:
        """Initializes trigger line properties.

        Args:
            orientation: Line orientation ('vertical' or 'horizontal').
            position: Position fraction across frame dimension (0.0 to 1.0, default 0.5).
            direction: Direction of crossing ('ltr', 'rtl', 'ttb', 'btt', default 'ltr').
        """
        self.direction = direction.lower()
        # Ensure orientation aligns with crossing direction
        if self.direction in ("ltr", "rtl"):
            self.orientation = "vertical"
        elif self.direction in ("ttb", "btt"):
            self.orientation = "horizontal"
        else:
            self.orientation = orientation.lower()

        self.position = float(position)

    def get_line_coords(self, frame_shape: Tuple[int, ...]) -> Tuple[Tuple[int, int], Tuple[int, int]]:
        """Returns the start and end pixel coordinates of the trigger line for rendering.

        Args:
            frame_shape: Frame dimensions tuple (height, width, ...).

        Returns:
            Tuple of ((x1, y1), (x2, y2)) line endpoints.
        """
        height = frame_shape[0]
        width = frame_shape[1]

        if self.orientation == "vertical":
            x = int(round(self.position * width))
            return ((x, 0), (x, height))
        else:
            y = int(round(self.position * height))
            return ((0, y), (width, y))

    def crossed(
        self,
        prev_center: Optional[Tuple[float, float]],
        curr_center: Tuple[float, float],
        frame_shape: Tuple[int, ...]
    ) -> bool:
        """Determines if a centroid crossed the line in the configured direction.

        Args:
            prev_center: Previous frame centroid (cx, cy) or None.
            curr_center: Current frame centroid (cx, cy).
            frame_shape: Frame dimensions tuple (height, width, ...).

        Returns:
            True if the centroid crossed the line in the configured direction.
        """
        if prev_center is None:
            return False

        height = frame_shape[0]
        width = frame_shape[1]

        prev_x, prev_y = prev_center
        curr_x, curr_y = curr_center

        if self.direction == "ltr":
            line_x = self.position * width
            return prev_x < line_x and curr_x >= line_x

        elif self.direction == "rtl":
            line_x = self.position * width
            return prev_x > line_x and curr_x <= line_x

        elif self.direction == "ttb":
            line_y = self.position * height
            return prev_y < line_y and curr_y >= line_y

        elif self.direction == "btt":
            line_y = self.position * height
            return prev_y > line_y and curr_y <= line_y

        return False
