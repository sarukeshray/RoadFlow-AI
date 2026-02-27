"""
train.py
========
Trains a YOLOv8n (Nano) model on the road damage dataset for mobile deployment.

Prerequisites:
    - Run `python download_data.py` first to prepare the dataset.
    - GPU recommended for reasonable training speed.

Usage:
    python train.py
"""

import os
import sys
from ultralytics import YOLO

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DATA_YAML = os.path.join(SCRIPT_DIR, "dataset", "data.yaml")

# Hyperparameters
MODEL_VARIANT = "yolov8n.pt"   # Nano variant for mobile efficiency
EPOCHS = 50
IMAGE_SIZE = 640
BATCH_SIZE = 16
OPTIMIZER = "auto"              # Let Ultralytics pick the best optimizer
PATIENCE = 10                   # Early stopping patience
WORKERS = 8                     # DataLoader workers
PROJECT = os.path.join(SCRIPT_DIR, "runs", "detect")
NAME = "train"


# ---------------------------------------------------------------------------
# Training
# ---------------------------------------------------------------------------
def main():
    print("=" * 60)
    print("  RoadFlow-AI: YOLOv8n Model Training")
    print("=" * 60)

    # Verify dataset exists
    if not os.path.exists(DATA_YAML):
        print(f"ERROR: Dataset config not found at {DATA_YAML}")
        print("Please run `python download_data.py` first.")
        sys.exit(1)

    print(f"\nModel:      {MODEL_VARIANT}")
    print(f"Dataset:    {DATA_YAML}")
    print(f"Epochs:     {EPOCHS}")
    print(f"Image Size: {IMAGE_SIZE}")
    print(f"Batch Size: {BATCH_SIZE}")
    print(f"Optimizer:  {OPTIMIZER}")
    print(f"Patience:   {PATIENCE}")
    print()

    # Load pretrained YOLOv8 Nano model
    model = YOLO(MODEL_VARIANT)

    # Train the model
    results = model.train(
        data=DATA_YAML,
        epochs=EPOCHS,
        imgsz=IMAGE_SIZE,
        batch=BATCH_SIZE,
        optimizer=OPTIMIZER,
        patience=PATIENCE,
        workers=WORKERS,
        project=PROJECT,
        name=NAME,
        exist_ok=True,
        verbose=True,
        # Augmentation (sensible defaults for road damage)
        hsv_h=0.015,       # Hue augmentation
        hsv_s=0.7,         # Saturation augmentation
        hsv_v=0.4,         # Value augmentation
        degrees=0.0,       # No rotation (roads are mostly horizontal)
        translate=0.1,     # Translation augmentation
        scale=0.5,         # Scale augmentation
        fliplr=0.5,        # Horizontal flip
        flipud=0.0,        # No vertical flip (unnatural for road images)
        mosaic=1.0,        # Mosaic augmentation
        device=0
    )

    # Print results summary
    best_model_path = os.path.join(PROJECT, NAME, "weights", "best.pt")
    print("\n" + "=" * 60)
    print("  Training Complete!")
    print("=" * 60)
    print(f"  Best model saved to: {best_model_path}")
    print(f"\n  Next step: python export.py")

    return results


if __name__ == "__main__":
    main()
