# 🦙 Android Local AI Agent 
OPEN-SOURCE/ MIT license ..

early-access -> really early ... 


An advanced, fully on-device AI Agent for Android. This application acts as a smart accessibility layer, understanding your screen context, listening to voice commands, and executing autonomous actions—all processed locally without sending your private data to the cloud.


[![Platform: Android](https://img.shields.io/badge/Platform-Android-green.svg)](https://android.com)
[![Offline First](https://img.shields.io/badge/Offline-100%25-blue.svg)](#privacy)
[![F-Droid Compatible](https://img.shields.io/badge/F--Droid-Ready-blue.svg)](https://f-droid.org)

Android local AI Agent is a comprehensive, open-source assistive platform for Android that runs entirely locally without the need for cloud connectivity [1]. The application combines the power of local LLMs (Large Language Models), Computer Vision, audio processing, and advanced eye tracking (IrisMotion) to enable fully autonomous and hands-free device control [2, 3].
​The project is designed as an "Assistive Technology" and places absolute emphasis on user data security. It contains no trackers and is an ideal candidate for distribution via F-Droid [1, 4].

## ✨ Main Function (Core Features)

### 🧠 Here is the translation of your README into English, keeping the formatting clean and suited for GitHub/F-Droid.
An advanced, fully on-device AI Agent for Android. This application acts as a smart accessibility layer, understanding your screen context, listening to voice commands, and executing autonomous actions—all processed locally without sending your private data to the cloud.
Platform: Android

Offline First

F-Droid Compatible
**Android AI Agent** is a comprehensive, open-source assistive platform for Android that runs entirely locally without the need for cloud connectivity [1]. The application combines the power of local LLMs (Large Language Models), Computer Vision, audio processing, and advanced eye tracking (IrisMotion) to enable fully autonomous and hands-free device control [2, 3].
The project is designed as an "Assistive Technology" and places absolute emphasis on user data security. It contains no trackers and is an ideal candidate for distribution via F-Droid [1, 4].
## ✨ Core Features

### 🧠 Local AI and LLM Inference
 * **Llama.cpp Integration:** Support for GGUF models running locally using the llama.cpp JNI layer [5]. Models run in isolated processes with hardware acceleration support and multi-thread optimization [6].
 * **Cascading Fallback:** Support for MediaPipe LLM (LiteRT bundle) and Google AICore (Gemini Nano) models as fallbacks for devices with varying hardware capabilities [7, 8].
 * **Multimodality:** The agent can process visual inputs (screen captures or live camera feed) using visual models [5, 9].
### 👁️ IrisMotion (Hands-Free Eye Control)
 * **Precise Gaze Tracking:** Utilizes MediaPipe Face Mesh to calculate an accurate gaze vector using 3D inverse kinematics and neural Blendshapes [10, 11].
 * **6DoF Support:** Supports cursor movement not only through rotation but also head translation (Yaw, Pitch, Roll, X, Y, Z translation) [12].
 * **Dwell & Blink Clicking:** Autonomous clicking via stabilized staring (Dwell) or intentional blink detection (Blink Phase Detector) [13, 14].
 * **Enterprise Filters:** Stabilization using Kalman filter (Zero-Lag), Alpha-Beta, and OneEuro filters to eliminate microsaccades [15-17].
### 🦾 Autonomous Control and Automation
 * **Screen Parsing:** The application scans the UI tree using the Accessibility API, extracts context (texts, buttons), and passes it to the AI [18].
 * **Action Executor:** Based on the LLM's response, the agent performs gestures—ranging from semantic clicking and typing text to swipe gestures and smooth scrolling [19-21].
 * **Macro Engine & Smart Clicks:** Support for complex scripts and autonomous element discovery (e.g., "like this") at runtime [22, 23].
### 🗣️ Audio Studio and Transcription (STT & TTS)
 * **Dual-Engine Transcription:** Integrates highly accurate Whisper.cpp models and fast Sherpa-ONNX (Streaming and Batch models) [24].
 * **System Audio:** Ability to capture internal device audio (Android 14+) via MediaProjection without needing a microphone [25, 26].
 * **SRT Editor / Voice Recorder:** A fully-featured tool for recording, transcribing into subtitles, and editing SRT/VTT blocks from media [27, 28].
 * **Speech Synthesis:** Option to clone voices or use fluent offline TTS models (e.g., VITS, Matcha, ZipVoice) via Sherpa integration [29].
### 🌍 Environmental Perception (Perception Matrix)
 * **Image Processing:** Local Object Detection (EfficientDet-Lite0) and ML Kit OCR analyze what the rear camera sees or the screen content [30, 31].
 * **Acoustic Analysis (YAMNet):** Continuous ambient listening (AudioSet with 521 classes) detecting dangerous situations like alarm sirens, screaming, or barking, and alerting the user [32, 33].
## 🛡️ Privacy and Security
The application operates at a sensitive system level (Accessibility Service) but applies a radical principle of data isolation:
 * **No Cloud:** All inference (LLM, NLP, OCR, Object Detection) occurs exclusively on the device's CPU/NPU [1].
 * **SafetyGuard (Sensitive Data Protection):** An integrated blacklist automatically blocks the agent and screen perception in banking apps, password fields, or sensitive UI dialogs [4].
 * **Encrypted Storage:** Intermediate AI operation results and conversation histories are stored encrypted (AES-256-GCM/SIV) in a secure Keystore [34].
## 🛠️ Architecture
The project is built on a modern Android stack:
 * **Language:** Kotlin (100% UI and logic), C++ (JNI integration into llama.cpp and Sherpa/Whisper) [35].
 * **Processing:** Coroutines and Flow (Event-Driven architecture via PerceptionEventBus) [36].
 * **UI:** Support for Markdown parsed by custom, fast native lexers (without Regex) for dozens of languages [37].
 * **Compatibility:** Targeting SDK 35 (Android 15+ ready) with support for the max-page-size=16384 requirement [38, 39].
## 📦 Installation
The project is optimized for F-Droid. It contains no proprietary Google trackers (exclusively on-device ML Kit / LiteRT dependencies).
 1. Download the latest APK from Releases.
 2. For voice features, you will be prompted to download the required models (Whisper/Sherpa) in Settings [40, 41].
 3. For full autonomy, enable the application in **Android Settings -> Accessibility** [42].
## 📜 Permissions
For the assistant to function correctly, it may require (depending on which modules you enable):
 * ACCESSIBILITY_SERVICE: Critical for screen reading, OS navigation, and hands-free touch simulation [1, 18].
 * RECORD_AUDIO: For wake-word detection, STT commands, and YAMNet environmental analysis [32, 43].
 * CAMERA: For the IrisMotion feature (Eye tracking via the front camera) and object detection [43].
 * SYSTEM_ALERT_WINDOW / TYPE_ACCESSIBILITY_OVERLAY: To display a floating cursor (IrisMotion), notification bubbles, or smart subtitles across the system [44, 45].
## 🤝 Contributing
Contributions are welcome! Please check out our Issues and submit a Pull Request. We primarily focus on memory stability (C++ pointer leaks) and reducing thermal load (Thermal throttling optimization) when running heavy AI models [46, 47].
## ⚖️ License
This project is distributed under the [MIT / GPLv3] license *(fill in your preferred license for F-Droid)*. Llama.cpp, Sherpa, and other integrations are subject to their own open-source licenses.
This agent operates primarily offline. 
* **SafetyGuard**: Prevents the agent from interacting with sensitive apps (banking, password managers).
* **Secure Storage**: NLP context cache is encrypted using AES-256-GCM via Android Keystore.
* No root access is required. All actions utilize standard Android Accessibility and Intent APIs.

## 🚀 Installation
 Download full project repo and copilate /edit / try "code_vibe" custom function 

### Option 1: Direct APK Download
1. Go to the [Releases](../../releases) page.
2. Download the latest `app-release.apk`.
3. Install the APK on your Android device (Android 13+ recommended).

### Option 2: Custom F-Droid Repository
*(Add your F-Droid repo link here if you create one)*
Add this repository to your F-Droid client:
`https://github.com/YOUR_NAME/YOUR_REPO/releases/download/fdroid/repo`

## ⚙️ Setup & Permissions

To function correctly, the AI Agent requires the following permissions:
1. **Accessibility Service**: Go to `Settings -> Accessibility -> AI Agent` and enable it. This is required to read screen context and perform clicks.
2. **Display Over Other Apps**: Required for the floating Agent button and visual bounding-box overlays.
3. **Microphone**: For voice commands and dictation features.
4. **Virtual Keyboard**: Enable `HyperKeyboardService` in Android language settings to allow the agent to type text autonomously.

## 🛠️ Building from Source

1. Clone the repository:
   ```bash
   git clone [https://github.com/YOUR_NAME/YOUR_REPO.git](https://github.com/YOUR_NAME/YOUR_REPO.git)

