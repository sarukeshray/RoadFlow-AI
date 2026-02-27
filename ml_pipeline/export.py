"""
export.py
=========
Exports the trained YOLOv8 model to TensorFlow Lite (.tflite) format
for Android deployment.

Prerequisites:
    - Run `python train.py` first to produce the trained weights.
    - Required packages: ultralytics, tensorflow, onnx, onnx2tf, sng4onnx, onnxsim

Usage:
    python export.py
    python export.py --weights path/to/best.pt
    python export.py --imgsz 320
"""

import os
import sys
import argparse
from ultralytics import YOLO

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_WEIGHTS = os.path.join(SCRIPT_DIR, "runs", "detect", "train", "weights", "best.pt")
DEFAULT_IMGSZ = 640


# ---------------------------------------------------------------------------
# Export
# ---------------------------------------------------------------------------
def export_to_tflite(weights_path: str, imgsz: int = DEFAULT_IMGSZ) -> str:
    """
    Export a YOLOv8 model to TensorFlow Lite format.

    Args:
        weights_path: Path to the trained .pt model file.
        imgsz: Input image size for the exported model.

    Returns:
        Path to the exported .tflite file.
    """
    print("=" * 60)
    print("  RoadFlow-AI: TFLite Export for Android")
    print("=" * 60)

    if not os.path.exists(weights_path):
        print(f"\nERROR: Model weights not found at:\n  {weights_path}")
        print("\nPlease train the model first with: python train.py")
        sys.exit(1)

    print(f"\n  Weights: {weights_path}")
    print(f"  Image Size: {imgsz}")
    print(f"  Format: TensorFlow Lite (.tflite)")
    print()

    # Load the trained model
    model = YOLO(weights_path)

    # Export to TFLite
    # Ultralytics handles the ONNX -> TF SavedModel -> TFLite conversion pipeline
    export_path = model.export(
        format="tflite",
        imgsz=imgsz,
    )

    print("\n" + "=" * 60)
    print("  Export Complete!")
    print("=" * 60)
    print(f"  TFLite model saved to: {export_path}")
    print(f"\n  Copy this .tflite file into your Android project's")
    print(f"  assets/ directory for deployment.")

    return export_path


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------
def parse_args():
    parser = argparse.ArgumentParser(
        description="Export YOLOv8 model to TFLite for Android deployment"
    )
    parser.add_argument(
        "--weights",
        type=str,
        default=DEFAULT_WEIGHTS,
        help=f"Path to trained model weights (default: {DEFAULT_WEIGHTS})"
    )
    parser.add_argument(
        "--imgsz",
        type=int,
        default=DEFAULT_IMGSZ,
        help=f"Input image size (default: {DEFAULT_IMGSZ})"
    )
    return parser.parse_args()


if __name__ == "__main__":
    args = parse_args()
    export_to_tflite(args.weights, args.imgsz)
