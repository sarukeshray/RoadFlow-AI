"""
rhi_calculator.py
=================
Road Health Index (RHI) Calculator

Takes YOLOv8 bounding-box detection outputs and computes a Road Health Index
score on a 0–100 scale.

Scoring Rules:
    - Base score: 100
    - Pothole detected:  −15 points each
    - Crack detected:    −5  points each
    - Minimum score:     0

Usage as a module:
    from rhi_calculator import calculate_rhi, process_yolo_results

Usage standalone (runs demo):
    python rhi_calculator.py
"""

from dataclasses import dataclass, field
from typing import List, Dict, Any, Optional


# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
BASE_SCORE = 100

# Penalty per detection (by class name keyword)
PENALTY_MAP = {
    "pothole":  15,
    "crack":     5,   # covers longitudinal_crack, transverse_crack, alligator_crack
}

# Default penalty for classes that don't match any keyword above
DEFAULT_PENALTY = 2

# Grade thresholds
GRADE_THRESHOLDS = [
    (75, "Good"),       # 75-100
    (50, "Fair"),       # 50-74
    (25, "Poor"),       # 25-49
    (0,  "Critical"),   # 0-24
]


# ---------------------------------------------------------------------------
# Data classes
# ---------------------------------------------------------------------------
@dataclass
class Detection:
    """Represents a single YOLO detection."""
    class_id: int
    class_name: str
    confidence: float
    bbox: List[float]  # [x1, y1, x2, y2]


@dataclass
class RHIResult:
    """Result of an RHI calculation."""
    rhi_score: int
    grade: str
    total_detections: int
    detections_summary: Dict[str, int]
    penalties_applied: Dict[str, int]
    detections: List[Detection] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        """Convert to a plain dictionary (useful for JSON serialization)."""
        return {
            "rhi_score": self.rhi_score,
            "grade": self.grade,
            "total_detections": self.total_detections,
            "detections_summary": self.detections_summary,
            "penalties_applied": self.penalties_applied,
        }


# ---------------------------------------------------------------------------
# Core functions
# ---------------------------------------------------------------------------
def get_penalty(class_name: str) -> int:
    """
    Determine the penalty for a given class name.
    Matches by keyword (case-insensitive substring match).
    """
    name_lower = class_name.lower()
    for keyword, penalty in PENALTY_MAP.items():
        if keyword in name_lower:
            return penalty
    return DEFAULT_PENALTY


def get_grade(score: int) -> str:
    """Map an RHI score to a grade label."""
    for threshold, grade in GRADE_THRESHOLDS:
        if score >= threshold:
            return grade
    return "Critical"


def calculate_rhi(detections: List[Detection]) -> RHIResult:
    """
    Calculate the Road Health Index from a list of detections.

    Args:
        detections: List of Detection objects from YOLO output.

    Returns:
        RHIResult with the computed score, grade, and breakdown.
    """
    score = BASE_SCORE
    summary: Dict[str, int] = {}
    penalties: Dict[str, int] = {}

    for det in detections:
        class_name = det.class_name

        # Count detections per class
        summary[class_name] = summary.get(class_name, 0) + 1

        # Apply penalty
        penalty = get_penalty(class_name)
        score -= penalty
        penalties[class_name] = penalties.get(class_name, 0) + penalty

    # Clamp score to [0, 100]
    score = max(0, min(100, score))

    return RHIResult(
        rhi_score=score,
        grade=get_grade(score),
        total_detections=len(detections),
        detections_summary=summary,
        penalties_applied=penalties,
        detections=detections,
    )


def process_yolo_results(results, confidence_threshold: float = 0.25) -> RHIResult:
    """
    Parse raw Ultralytics YOLO results and compute RHI.

    Args:
        results: The output from model.predict() or model() — a list of
                 ultralytics.engine.results.Results objects.
        confidence_threshold: Minimum confidence to consider a detection.

    Returns:
        RHIResult with the computed score and breakdown.
    """
    detections: List[Detection] = []

    for result in results:
        boxes = result.boxes
        if boxes is None:
            continue

        class_names = result.names  # {0: 'pothole', 1: 'crack', ...}

        for i in range(len(boxes)):
            conf = float(boxes.conf[i])
            if conf < confidence_threshold:
                continue

            cls_id = int(boxes.cls[i])
            cls_name = class_names.get(cls_id, f"class_{cls_id}")
            bbox = boxes.xyxy[i].tolist()

            detections.append(Detection(
                class_id=cls_id,
                class_name=cls_name,
                confidence=conf,
                bbox=bbox,
            ))

    return calculate_rhi(detections)


# ---------------------------------------------------------------------------
# Demo / Self-test
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    print("=" * 60)
    print("  RoadFlow-AI: RHI Calculator Demo")
    print("=" * 60)

    # --- Test 1: No detections (perfect road) ---
    result = calculate_rhi([])
    assert result.rhi_score == 100, f"Expected 100, got {result.rhi_score}"
    assert result.grade == "Good"
    print(f"\n[Test 1] No damage:       RHI={result.rhi_score}, Grade={result.grade}  [PASS]")

    # --- Test 2: 2 potholes (100 - 2*15 = 70) ---
    dets = [
        Detection(0, "pothole", 0.9, [10, 10, 50, 50]),
        Detection(0, "pothole", 0.85, [100, 100, 150, 150]),
    ]
    result = calculate_rhi(dets)
    assert result.rhi_score == 70, f"Expected 70, got {result.rhi_score}"
    assert result.grade == "Fair"
    print(f"[Test 2] 2 potholes:      RHI={result.rhi_score}, Grade={result.grade}  [PASS]")

    # --- Test 3: 3 cracks (100 - 3*5 = 85) ---
    dets = [
        Detection(1, "longitudinal_crack", 0.8, [10, 10, 50, 50]),
        Detection(2, "transverse_crack", 0.75, [60, 60, 100, 100]),
        Detection(3, "alligator_crack", 0.7, [120, 120, 170, 170]),
    ]
    result = calculate_rhi(dets)
    assert result.rhi_score == 85, f"Expected 85, got {result.rhi_score}"
    assert result.grade == "Good"
    print(f"[Test 3] 3 cracks:        RHI={result.rhi_score}, Grade={result.grade}  [PASS]")

    # --- Test 4: Mixed damage (100 - 15 - 5 - 5 = 75) ---
    dets = [
        Detection(0, "pothole", 0.9, [10, 10, 50, 50]),
        Detection(1, "longitudinal_crack", 0.8, [60, 60, 100, 100]),
        Detection(2, "transverse_crack", 0.75, [120, 120, 170, 170]),
    ]
    result = calculate_rhi(dets)
    assert result.rhi_score == 75, f"Expected 75, got {result.rhi_score}"
    assert result.grade == "Good"
    print(f"[Test 4] Mixed damage:    RHI={result.rhi_score}, Grade={result.grade}  [PASS]")

    # --- Test 5: Heavily damaged road (should clamp to 0) ---
    dets = [Detection(0, "pothole", 0.9, [i*10, 0, i*10+10, 10]) for i in range(10)]
    result = calculate_rhi(dets)
    assert result.rhi_score == 0, f"Expected 0, got {result.rhi_score}"
    assert result.grade == "Critical"
    print(f"[Test 5] 10 potholes:     RHI={result.rhi_score}, Grade={result.grade}  [PASS]")

    # --- Test 6: Summary output ---
    print(f"\n  Full result dict: {result.to_dict()}")
    print("\n  All tests passed!")
