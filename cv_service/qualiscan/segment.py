"""Item segmentation module for QualiScan.

Extracts isolated items from a conveyor video frame using classical thresholding,
morphological filtering, and contour geometry extraction.
"""

from dataclasses import dataclass
import math
from typing import List, Optional, Tuple
import cv2
import numpy as np


@dataclass
class Item:
    """Represents a segmented item on the conveyor line with extracted features.

    Attributes:
        contour: External contour points representing the item boundary.
        bbox: Axis-aligned bounding box tuple (x, y, w, h).
        center: Centroid coordinates (cx, cy).
        area: Contour area in square pixels.
        circularity: Isoperimetric quotient 4 * pi * area / (perimeter^2).
                     A value close to 1.0 indicates a circle.
        aspect_ratio: Long side / short side from cv2.minAreaRect.
                      Rotation-independent metric.
        mean_hsv: Average (Hue, Saturation, Value) inside the item contour.
    """
    contour: np.ndarray
    bbox: Tuple[int, int, int, int]
    center: Tuple[float, float]
    area: float
    circularity: float
    aspect_ratio: float
    mean_hsv: Tuple[float, float, float]
    solidity: float = 0.0
    edge_density: float = 0.0
    v_std: float = 0.0
    crop: Optional[np.ndarray] = None


def segment_items(
    frame: np.ndarray,
    min_area: int = 800,
    light_objects: Optional[bool] = None
) -> Tuple[List[Item], np.ndarray]:
    """Segments objects from the conveyor background and computes geometric features.

    Pipeline:
    1. Convert BGR frame to Grayscale.
    2. Gaussian blur (5x5 kernel) to suppress high-frequency sensor noise.
    3. Otsu thresholding with automatic polarity check: if more than 60% of the
       mask is white, invert the mask. The light_objects parameter serves as
       a manual override.
    4. Morphological OPEN then CLOSE with an elliptical 5x5 kernel to remove
       noise specks and bridge small interior voids.
    5. Find external contours (cv2.RETR_EXTERNAL).
    6. Filter contours smaller than min_area or whose bounding box covers > 90%
       of the frame, and calculate Item dataclass fields.

    Args:
        frame: BGR input image or video frame.
        min_area: Minimum pixel area threshold to discard dust/debris.
        light_objects: Optional manual override. True forces light objects;
                       False forces dark objects; None enables auto polarity.

    Returns:
        Tuple of (list_of_Item_objects, binary_mask).
    """
    # 1. Grayscale
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)

    # 2. Gaussian Blur (5x5)
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)

    # 3. Otsu Thresholding & Polarity Check
    if light_objects is not None:
        thresh_type = cv2.THRESH_BINARY if light_objects else cv2.THRESH_BINARY_INV
        _, thresh = cv2.threshold(blurred, 0, 255, thresh_type + cv2.THRESH_OTSU)
    else:
        # Automatic polarity check: default to binary, invert if > 60% of mask is white
        _, thresh = cv2.threshold(blurred, 0, 255, cv2.THRESH_BINARY + cv2.THRESH_OTSU)
        h_t, w_t = thresh.shape[:2]
        white_ratio = float(cv2.countNonZero(thresh)) / float(h_t * w_t)
        if white_ratio > 0.60:
            thresh = cv2.bitwise_not(thresh)

    # 4. Morphological OPEN then CLOSE with 5x5 elliptical structuring element
    kernel = cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5))
    opened = cv2.morphologyEx(thresh, cv2.MORPH_OPEN, kernel)
    mask = cv2.morphologyEx(opened, cv2.MORPH_CLOSE, kernel)

    # 5. External Contours
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    # Convert BGR to HSV once for color sampling
    hsv_frame = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
    value_frame = hsv_frame[:, :, 2]
    edges = cv2.Canny(gray, 50, 150)
    h_frame, w_frame = frame.shape[:2]
    frame_area = float(h_frame * w_frame)

    items: List[Item] = []

    for cnt in contours:
        area = float(cv2.contourArea(cnt))
        if area < min_area:
            continue

        # Axis-aligned bounding box
        x, y, w, h = cv2.boundingRect(cnt)
        bbox = (int(x), int(y), int(w), int(h))

        # Ignore any contour whose bounding box covers more than 90% of the frame
        if float(w * h) > 0.90 * frame_area:
            continue

        # Centroid from spatial moments
        moments = cv2.moments(cnt)
        if moments["m00"] != 0:
            cx = float(moments["m10"] / moments["m00"])
            cy = float(moments["m01"] / moments["m00"])
        else:
            cx = float(x + w / 2.0)
            cy = float(y + h / 2.0)
        center = (round(cx, 2), round(cy, 2))

        # Circularity: 4 * pi * Area / (Perimeter^2)
        perimeter = float(cv2.arcLength(cnt, closed=True))
        if perimeter > 0:
            circularity = (4.0 * math.pi * area) / (perimeter * perimeter)
        else:
            circularity = 0.0

        # Rotation-independent aspect ratio via minAreaRect (long_side / short_side)
        rect = cv2.minAreaRect(cnt)
        dim1, dim2 = rect[1]
        long_side = max(dim1, dim2)
        short_side = min(dim1, dim2)
        aspect_ratio = float(long_side / short_side) if short_side > 0 else 1.0

        # Mean HSV inside the contour using a per-contour mask
        contour_mask = np.zeros((h_frame, w_frame), dtype=np.uint8)
        cv2.drawContours(contour_mask, [cnt], -1, 255, -1)
        mean_vals = cv2.mean(hsv_frame, mask=contour_mask)
        mean_hsv = (round(float(mean_vals[0]), 2), round(float(mean_vals[1]), 2), round(float(mean_vals[2]), 2))
        hull_area = float(cv2.contourArea(cv2.convexHull(cnt)))
        solidity = area / hull_area if hull_area > 0 else 0.0
        edge_density = float(cv2.countNonZero(cv2.bitwise_and(edges, edges, mask=contour_mask))) / max(1, cv2.countNonZero(contour_mask))
        v_std = float(np.std(value_frame[contour_mask > 0]))
        crop = frame[y:y + h, x:x + w].copy()

        items.append(Item(
            contour=cnt,
            bbox=bbox,
            center=center,
            area=round(area, 2),
            circularity=round(circularity, 4),
            aspect_ratio=round(aspect_ratio, 4),
            mean_hsv=mean_hsv,
            solidity=round(solidity, 4),
            edge_density=round(edge_density, 4),
            v_std=round(v_std, 2),
            crop=crop
        ))

    return items, mask
