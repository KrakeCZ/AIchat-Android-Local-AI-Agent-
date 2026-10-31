#!/usr/bin/env bash
# SCRIPT PRO STAZENI AI MODELU (Linux/macOS)
# Pro splneni reproducible-build pravidel se modely nestahuji automaticky
# pres Gradle, ale manualne pres tento skript.

set -e

PROJECT_ROOT=$(cd "$(dirname "$0")/.." && pwd)
ASSETS_DIR="$PROJECT_ROOT/app/src/main/assets"

mkdir -p "$ASSETS_DIR"

download_file() {
    local url=$1
    local filename=$2
    local filepath="$ASSETS_DIR/$filename"

    if [ -f "$filepath" ]; then
        echo "[INFO] $filename jiz existuje, preskoceno."
    else
        echo "[DOWNLOADING] Stahuji $filename..."
        curl -L -o "$filepath" "$url"
        echo "[OK] $filename stazen."
    fi
}

echo "=== Zacina stahovani modelu do $ASSETS_DIR ==="

# 1. IrisMotion - Face Landmarker (Float16)
download_file "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task" "face_landmarker.task"

# 2. PerceptionEngine - Object Detector (EfficientDet-Lite0, INT8)
download_file "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite" "efficientdet_lite0.tflite"

# 3. AudioPerceptionEngine - YAMNet (Float32)
download_file "https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite" "yamnet.tflite"

# 4. PerceptionEngine - Pose Landmarker Lite (Float16)
download_file "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task" "pose_landmarker_lite.task"

echo "=============================================="
echo "[SUCCESS] Vsechny modely byly uspesne stazeny!"
echo "Nyni muzete spustit Gradle synchronizaci/build."
