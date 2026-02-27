"""
download_data.py
================
Downloads the Road Damage Dataset (Potholes, Cracks, and Manholes) from Kaggle
and restructures it into a YOLOv8-compatible directory layout.

This dataset is FLAT (no pre-existing train/valid/test splits), so the script
automatically splits the data into train (80%), valid (10%), test (10%).

Prerequisites:
    - Place your `kaggle.json` API credentials file in this directory (ml_pipeline/).
    - Install dependencies: pip install -r requirements.txt

Usage:
    python download_data.py
"""

import os
import sys
import shutil
import zipfile
import glob
import random
import yaml

# ---------------------------------------------------------------------------
# 1. Configure Kaggle authentication
# ---------------------------------------------------------------------------
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
os.environ["KAGGLE_CONFIG_DIR"] = SCRIPT_DIR

# Verify kaggle.json exists before importing the API
kaggle_json_path = os.path.join(SCRIPT_DIR, "kaggle.json")
if not os.path.exists(kaggle_json_path):
    print(f"ERROR: kaggle.json not found in {SCRIPT_DIR}")
    print("Please place your Kaggle API credentials file (kaggle.json) in the ml_pipeline/ directory.")
    sys.exit(1)

# Ensure private permissions on kaggle.json (required by the API on some systems)
try:
    os.chmod(kaggle_json_path, 0o600)
except OSError:
    pass  # Windows may not support chmod; that's fine

from kaggle.api.kaggle_api_extended import KaggleApi

# ---------------------------------------------------------------------------
# 2. Constants
# ---------------------------------------------------------------------------
DATASET_SLUG = "lorenzoarcioni/road-damage-dataset-potholes-cracks-and-manholes"
DOWNLOAD_DIR = os.path.join(SCRIPT_DIR, "raw_download")
DATASET_DIR = os.path.join(SCRIPT_DIR, "dataset")

# Split ratios
TRAIN_RATIO = 0.80
VALID_RATIO = 0.10
TEST_RATIO = 0.10

# Reproducibility
RANDOM_SEED = 42

# Known classes for this dataset
CLASS_NAMES = ["Pothole", "Crack", "Manhole"]


# ---------------------------------------------------------------------------
# 3. Download dataset
# ---------------------------------------------------------------------------
def download_dataset():
    """Authenticate with Kaggle and download the dataset."""
    print("[1/5] Authenticating with Kaggle API...")
    api = KaggleApi()
    api.authenticate()

    print(f"[2/5] Downloading dataset: {DATASET_SLUG}")
    os.makedirs(DOWNLOAD_DIR, exist_ok=True)
    api.dataset_download_files(DATASET_SLUG, path=DOWNLOAD_DIR, unzip=False)

    # Find the downloaded zip file
    zip_files = glob.glob(os.path.join(DOWNLOAD_DIR, "*.zip"))
    if not zip_files:
        print("ERROR: No ZIP file found after download.")
        sys.exit(1)

    zip_path = zip_files[0]
    print(f"[3/5] Extracting {os.path.basename(zip_path)}...")
    with zipfile.ZipFile(zip_path, "r") as zf:
        zf.extractall(DOWNLOAD_DIR)

    print("    Extraction complete.")
    return DOWNLOAD_DIR


# ---------------------------------------------------------------------------
# 4. Find images and labels directories (recursive search)
# ---------------------------------------------------------------------------
def find_directory(root, target_name):
    """
    Recursively search for a directory with the given name under root.
    Returns the first match found (full path), or None.
    """
    for dirpath, dirnames, _ in os.walk(root):
        for d in dirnames:
            if d.lower() == target_name.lower():
                return os.path.join(dirpath, d)
    return None


def find_file(root, target_name):
    """
    Recursively search for a file with the given name under root.
    Returns the first match found (full path), or None.
    """
    for dirpath, _, filenames in os.walk(root):
        for f in filenames:
            if f.lower() == target_name.lower():
                return os.path.join(dirpath, f)
    return None


# ---------------------------------------------------------------------------
# 5. Restructure into YOLOv8 layout with train/valid/test split
# ---------------------------------------------------------------------------
def restructure_dataset(download_dir):
    """
    Find images and YOLO labels in the extracted data, split into
    train/valid/test, and build the YOLOv8-compatible directory layout.
    """
    print("[4/5] Locating images and labels in extracted data...")

    # --- Locate the images directory ---
    images_dir = find_directory(download_dir, "images")
    if not images_dir:
        print("ERROR: Could not find an 'images' directory in the extracted data.")
        sys.exit(1)
    print(f"    Found images: {images_dir}")

    # --- Locate the YOLO labels directory ---
    # Try 'labels-YOLO' first (this dataset's specific name), then 'labels'
    labels_dir = find_directory(download_dir, "labels-YOLO")
    if not labels_dir:
        labels_dir = find_directory(download_dir, "labels")
    if not labels_dir:
        print("ERROR: Could not find a 'labels-YOLO' or 'labels' directory in the extracted data.")
        sys.exit(1)
    print(f"    Found labels: {labels_dir}")

    # --- Collect paired image-label files ---
    image_exts = (".jpg", ".jpeg", ".png", ".bmp", ".webp")
    image_files = [
        f for f in os.listdir(images_dir)
        if f.lower().endswith(image_exts)
    ]
    image_files.sort()  # Deterministic ordering

    # Build pairs: only include images that have a matching label file
    pairs = []
    skipped = 0
    for img_file in image_files:
        stem = os.path.splitext(img_file)[0]
        label_file = stem + ".txt"
        label_path = os.path.join(labels_dir, label_file)

        if os.path.exists(label_path):
            pairs.append((img_file, label_file))
        else:
            skipped += 1

    print(f"    Paired samples: {len(pairs)} (skipped {skipped} images without labels)")

    if not pairs:
        print("ERROR: No image-label pairs found.")
        sys.exit(1)

    # --- Shuffle and split ---
    random.seed(RANDOM_SEED)
    random.shuffle(pairs)

    n = len(pairs)
    n_train = int(n * TRAIN_RATIO)
    n_valid = int(n * VALID_RATIO)
    # Remainder goes to test
    train_pairs = pairs[:n_train]
    valid_pairs = pairs[n_train:n_train + n_valid]
    test_pairs = pairs[n_train + n_valid:]

    print(f"    Split: train={len(train_pairs)}, valid={len(valid_pairs)}, test={len(test_pairs)}")

    # --- Create YOLOv8 directory structure ---
    print("[5/5] Building YOLOv8-compatible directory structure...")

    if os.path.exists(DATASET_DIR):
        shutil.rmtree(DATASET_DIR)

    splits = {
        "train": train_pairs,
        "valid": valid_pairs,
        "test": test_pairs,
    }

    for split_name, split_pairs in splits.items():
        img_dest = os.path.join(DATASET_DIR, split_name, "images")
        lbl_dest = os.path.join(DATASET_DIR, split_name, "labels")
        os.makedirs(img_dest, exist_ok=True)
        os.makedirs(lbl_dest, exist_ok=True)

        for img_file, lbl_file in split_pairs:
            shutil.copy2(
                os.path.join(images_dir, img_file),
                os.path.join(img_dest, img_file),
            )
            shutil.copy2(
                os.path.join(labels_dir, lbl_file),
                os.path.join(lbl_dest, lbl_file),
            )

        print(f"    {split_name}/  ->  {len(split_pairs)} images + labels")

    # --- Check for existing data.yaml in the download ---
    class_names = CLASS_NAMES
    existing_yaml = find_file(download_dir, "data.yaml")
    if existing_yaml:
        try:
            with open(existing_yaml, "r") as f:
                data = yaml.safe_load(f)
            if "names" in data:
                names = data["names"]
                if isinstance(names, dict):
                    class_names = list(names.values())
                elif isinstance(names, list):
                    class_names = names
                print(f"    Using class names from existing data.yaml: {class_names}")
        except Exception:
            pass  # Fall back to defaults

    # --- Generate data.yaml ---
    generate_data_yaml(class_names)

    print(f"\n    Dataset ready at: {DATASET_DIR}")


def generate_data_yaml(class_names):
    """Generate the data.yaml configuration file for YOLOv8."""
    data_yaml = {
        "path": os.path.abspath(DATASET_DIR),
        "train": "train/images",
        "val": "valid/images",
        "test": "test/images",
        "nc": len(class_names),
        "names": class_names,
    }

    yaml_path = os.path.join(DATASET_DIR, "data.yaml")
    with open(yaml_path, "w") as f:
        yaml.dump(data_yaml, f, default_flow_style=False, sort_keys=False)

    print(f"    Generated {yaml_path}")
    print(f"    Classes ({len(class_names)}): {class_names}")


# ---------------------------------------------------------------------------
# 6. Main
# ---------------------------------------------------------------------------
if __name__ == "__main__":
    print("=" * 60)
    print("  RoadFlow-AI: Dataset Download Pipeline")
    print("=" * 60)

    download_dir = download_dataset()
    restructure_dataset(download_dir)

    # Clean up raw download
    print("\nCleaning up raw download files...")
    shutil.rmtree(DOWNLOAD_DIR, ignore_errors=True)

    print("\nDone! You can now run: python train.py")
