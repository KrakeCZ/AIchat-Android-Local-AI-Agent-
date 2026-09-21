# SCRIPT PRO STAZENI AI MODELU (Windows)
# Pro splneni reproducible-build pravidel se modely nestahuji automaticky
# pres Gradle, ale manualne pres tento skript.

$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path | Split-Path -Parent
$AssetsDir = Join-Path $ProjectRoot "app\src\main\assets"

if (!(Test-Path -Path $AssetsDir)) {
    New-Item -ItemType Directory -Path $AssetsDir | Out-Null
}

function Download-Model {
    param (
        [string]$Url,
        [string]$Filename
    )
    
    $Filepath = Join-Path $AssetsDir $Filename

    if (Test-Path -Path $Filepath) {
        Write-Host "[INFO] $Filename jiz existuje, preskoceno." -ForegroundColor Cyan
    } else {
        Write-Host "[DOWNLOADING] Stahuji $Filename..." -ForegroundColor Yellow
        Invoke-WebRequest -Uri $Url -OutFile $Filepath
        Write-Host "[OK] $Filename stazen." -ForegroundColor Green
    }
}

Write-Host "=== Zacina stahovani modelu do $AssetsDir ===" -ForegroundColor Magenta

# 1. IrisMotion - Face Landmarker (Float16)
Download-Model -Url "https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task" -Filename "face_landmarker.task"

# 2. PerceptionEngine - Object Detector (EfficientDet-Lite0, INT8)
Download-Model -Url "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite" -Filename "efficientdet_lite0.tflite"

# 3. AudioPerceptionEngine - YAMNet (Float32)
Download-Model -Url "https://storage.googleapis.com/mediapipe-models/audio_classifier/yamnet/float32/1/yamnet.tflite" -Filename "yamnet.tflite"

# 4. PerceptionEngine - Pose Landmarker Lite (Float16)
Download-Model -Url "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task" -Filename "pose_landmarker_lite.task"

Write-Host "==============================================" -ForegroundColor Magenta
Write-Host "[SUCCESS] Vsechny modely byly uspesne stazeny!" -ForegroundColor Green
Write-Host "Nyni muzete spustit Gradle synchronizaci/build."
