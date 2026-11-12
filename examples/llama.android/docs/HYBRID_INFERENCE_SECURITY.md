# 🛡️ Hybrid Inference Architecture, LAN Streaming & Hardware-Backed Security (BYOK)

> **Document Version:** 1.0.0  
> **Target Platform:** Android 14 / 15 / 16 (API 34–36, 16 KB Memory Page Alignment)  
> **Reference Devices:** Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3 for Galaxy, Knox Vault / StrongBox) & Universal ARM64 Devices  

---

## 1. Executive Summary & Vision

The core mission of **AIchat** is to provide an uncompromising, open-source assistive platform that serves all users—including individuals with severe motor or cognitive impairments reliant on **IrisMotion (eye-gaze tracking)**, hands-free voice commands, and Android Accessibility automation.

A fundamental architectural insight guided this iteration:
> **The user's relationship with their AI companion, historical context, calibrated eye profiles, and accessibility memory must remain permanent and sovereign, while the cognitive engine (the LLM model) is dynamically hot-swappable.**

Rather than locking the entire system to a single on-device model, **AIchat** implements a **Tri-Modal Hybrid Inference Architecture**:
1. **100% Offline On-Device GGUF Inference** via optimized `llama.cpp` JNI.
2. **Cloud BYOK (Bring Your Own Key) Inference** (Google AI Studio Gemini API, OpenAI-compatible providers like Groq, DeepSeek, OpenRouter).
3. **Local PC / Private LAN Inference** (connecting directly to private Ollama or `llama.cpp` server instances running on the user's home workstation).

Crucially, integrating external APIs must never compromise the strict security guarantees of an assistive app operating with `BIND_ACCESSIBILITY_SERVICE` privileges. This document details the end-to-end security architecture, hardware-backed keystore integration, offline translation pipeline, and interactive UI engineering.

---

## 2. Tri-Modal Hybrid Inference Architecture

```
                                  +--------------------------------------------------+
                                  |                 AIchat Client                    |
                                  |         (MainActivity / AgentCoordinator)        |
                                  +--------------------------------------------------+
                                                           |
                                           +-------------------------------+
                                           |      InferenceEngine (I/F)    |
                                           |     EngineProvider / Router   |
                                           +-------------------------------+
                                                           |
                      +------------------------------------+------------------------------------+
                      |                                    |                                    |
                      v                                    v                                    v
     +----------------------------------+ +----------------------------------+ +----------------------------------+
     |       MODE 1: ON-DEVICE GGUF     | |      MODE 2: CLOUD BYOK API      | |     MODE 3: LOCAL PC / LAN       |
     +----------------------------------+ +----------------------------------+ +----------------------------------+
     | • llama.cpp JNI Engine           | | • Google AI Studio (Gemini 2.5)  | | • Private Desktop / Workstation  |
     | • 16 KB Page Aligned (ARM64)     | | • Groq / OpenRouter / DeepSeek   | | • Ollama (`:11434/v1`)           |
     | • NPU / CPU Hardware Accel       | | • Direct OkHttp SSE Stream       | | • llama-server (`:8080/v1`)      |
     | • 100% Offline, Zero Network     | | • Multi-turn Tool Calling Loop   | | • Full 32B/70B GPU Inference     |
     | • Privacy Guarantee              | | • Zero RAM Footprint on Device   | | • LAN-only Zero Public Cloud     |
     +----------------------------------+ +----------------------------------+ +----------------------------------+
```

### 2.1 Mode 1: 100% On-Device GGUF Inference (llama.cpp JNI)
* **Architecture:** C++ core linked via JNI (`libllama-android.so`) compiled with Android NDK r28+ ensuring strict **16 KB ELF segment alignment** required by Android 15+.
* **Use Cases:** Total air-gapped privacy, zero data egress, confidential note processing, offline wilderness operation.
* **Supported Model Families:** Qwen 2.5 (0.5B–3B), Llama 3.2 (1B–3B), Gemma 2, SmolLM2.
* **Resource Profile:** 2.0–4.5 GB RAM consumption; thermal throttling managed via dynamic thread allocation (`sysfs` thermal monitoring).

### 2.2 Mode 2: Cloud BYOK (Bring Your Own Key)
* **Architecture:** Clean, zero-dependency streaming client utilizing direct HTTP Server-Sent Events (SSE) against REST endpoints:
  * **Google AI Studio:** `v1beta/models/{model}:streamGenerateContent?alt=sse`
  * **OpenAI-Compatible Providers:** `v1/chat/completions` (Groq, OpenRouter, DeepSeek, Cerebras).
* **Why Direct SSE instead of Google GenerativeAI SDK:**
  * Avoids legacy/deprecated client dependencies (`generativeai:0.9.0`).
  * Full control over timeouts, socket lifecycle, and backpressure.
  * Direct extraction of model reasoning tokens (`<think>` blocks from DeepSeek-R1 / Qwen-QwQ).
* **Assistive Benefit (Eliminating TOCTOU race conditions):**
  * When executing UI actions via Accessibility Services, an on-device model can take 3,000–6,000 ms to prefill a 4,000-token UI hierarchy tree. During this window, screen state can change, triggering `Time-Of-Check to Time-Of-Use (TOCTOU)` validation failures.
  * Cloud streaming generates responses in ~300–600 ms, dropping TOCTOU abort rates to near zero.
  * Offloads 4 GB of RAM, allowing real-time camera computer vision (`IrisMotion` gaze tracking @ 60 FPS) and voice streaming (`Sherpa/Whisper`) to operate without memory bus contention.

### 2.3 Mode 3: Local PC / Private LAN Inference (Ollama & llama-server)
* **Architecture:** Point the OpenAI-compatible endpoint to an internal LAN IP address (e.g., `http://192.168.1.150:11434/v1`).
* **Benefits:**
  * Runs massive 32B, 70B, or MoE models on a dedicated home desktop GPU (RTX 4090 / Mac Studio).
  * Absolutely zero data sent to external cloud providers.
  * Preserves mobile battery life and prevents device heating.

---

## 3. Hardware-Backed Security Architecture (BYOK Hardening)

Storing user-provided API keys in an Android application operating an Accessibility Service requires defense-in-depth protections against memory dumping, screen recording trojans, and unauthorized third-party apps.

```
+---------------------------------------------------------------------------------------+
|                               Android Security Perimeter                              |
+---------------------------------------------------------------------------------------+
|                                                                                       |
|  [User Input in UI]                                                                   |
|         │                                                                             |
|         ▼ (Protected by FLAG_SECURE + accessibilityDataSensitive)                      |
|  [Temporary char[] Buffer]                                                            |
|         │                                                                             |
|         ▼ (Immediate Zeroize on Save)                                                 |
|  [EncryptedSharedPreferences (AES-256-SIV Keys + AES-256-GCM Values)]                  |
|         │                                                                             |
|         ▼                                                                             |
|  [Android Keystore System] ◄───────────────────► [Hardware TEE / StrongBox Keymint]   |
|                                                   (Galaxy S24 Ultra Knox Vault)       |
|                                                                                       |
+---------------------------------------------------------------------------------------+
|                                                                                       |
|  [Key Reveal / Deletion]                                                              |
|         │                                                                             |
|         ▼                                                                             |
|  [BiometricPrompt Verification] ──► BiometricManager.Authenticators.BIOMETRIC_STRONG   |
|                                     (Fingerprint / Device PIN)                        |
+---------------------------------------------------------------------------------------+
```

### 3.1 Hardware-Backed Key Storage (StrongBox & TEE)
* **MasterKey Scheme:** The encryption key is generated directly inside the device's hardware security module (**TEE - Trusted Execution Environment** or dedicated **StrongBox Keymint** chip on devices like Galaxy S24 Ultra).
* **Storage Layer:** Implemented via AndroidX Security (`EncryptedSharedPreferences`):
  * **Key Encryption:** `AES256_SIV` (deterministic authenticated encryption for preference keys).
  * **Value Encryption:** `AES256_GCM` (authenticated symmetric encryption for sensitive payload).
* **Zero Plaintext at Rest:** Even with root extraction of `/data/data/com.example.llama.aichat/shared_prefs/`, encrypted payloads cannot be decrypted without physical access to the device's hardware-bound key.

### 3.2 RAM Footprint & Zero-Allocation UI Hardening
1. **No Memory Residency:** Once an API key is committed to `EncryptedSharedPreferences`, the `EditText` is immediately wiped (`setText("")`). The key is never cached in static companion objects.
2. **Obfuscated Display Footprint:** The UI only displays a non-reversible cryptographic preview mask (e.g., `🔒 Klíč v Keystore (AIza••••x7Q)`).
3. **Screen Capture & Spyware Prevention (`FLAG_SECURE`):**
   * Whenever the key management dialog is active, `WindowManager.LayoutParams.FLAG_SECURE` is dynamically engaged, preventing screenshots, screen casting, and recent-app snapshot leaks.
4. **Android 14+ Accessibility Defense:**
   * Marked with `View.ACCESSIBILITY_DATA_SENSITIVE_YES`. Malicious or compromised accessibility tools cannot inspect or harvest the content of the API key input field.
5. **Input Sanitization:**
   * `AUTOFILL_TYPE_NONE` prevents third-party password managers from persisting or syncing the key without user consent.
   * Long-press copy/paste menus and text selection are disabled on the input field.

### 3.3 Biometric Gatekeeper (BiometricPrompt)
* Any operation that would reveal the raw API key (the eye toggle button) or permanently delete it from the hardware Keystore is gated by `BiometricPrompt`.
* Requires `BIOMETRIC_STRONG` or `DEVICE_CREDENTIAL` authentication. No unauthenticated code path exists to inspect the raw secret.

---

## 4. On-Device Translation & Markdown Preservation Pipeline

To empower users across linguistic barriers without compromising privacy, AIchat embeds an on-device translation engine powered by Google ML Kit.

### 4.1 Structural Integrity Algorithm (`translateMarkdownPreserving`)
Standard machine translation engines frequently destroy Markdown grammar—collapsing paragraphs into run-on lines, translating code block syntax, and breaking lists. 

AIchat implements an AST-aware block pre-processor:
1. **Fenced Code Block Isolation:** Code snippets enclosed in triple backticks (````) are completely bypassed during translation to prevent syntax corruption.
2. **Header & Blockquote Recognition:** Markdown headings (`#`, `##`, `###`) and blockquotes (`>`) are isolated, translated individually, and re-anchored with exact newline spacing (`\n\n`).
3. **List Item & Task Parsing:** Ordered lists (`1. `, `2. `), unordered lists (`- `, `* `), and task lists (`- [ ]`, `- [x]`) are translated strictly at the item level, keeping numbering and bullet hierarchies intact.
4. **Table Cell Integrity:** Markdown tables are preserved row by row.

### 4.2 Independent Reasoning (<think>) Translation
Modern reasoning models (DeepSeek-R1, Qwen-QwQ, Gemini Thinking) produce step-by-step internal cognitive processes.
* The translation pipeline independently extracts the `<think>` reasoning block and the final synthesis.
* Both are translated concurrently, and the translated reasoning is rendered inside an expandable/collapsible blockquote styled in distinct italics, maintaining clear visual hierarchy.

---

## 5. UI Architecture & Markwon Lifecycle Integrity

### 5.1 Centralized Link Resolver (`createMarkwonInstance`)
Interactive chat bubbles support deep links for expanding/collapsing model reasoning (`app://toggle_thoughts/<id>`) and web search evidence (`app://toggle_sources/<id>`).

* **Identified Defect:** Calling `markwon = MarkdownFactory.create(context)` during `MainActivity.onResume()` was stripping custom `linkResolver` instances, reverting Markwon to `LinkResolverDef` and causing Android to throw `ActivityNotFoundException` when users clicked internal toggle buttons.
* **Resolution:** Centralized construction inside `MessageAdapter.createMarkwonInstance()`. All adapter re-creations and theme switches permanently retain the custom link router:
  ```kotlin
  private fun createMarkwonInstance(): Markwon {
      return MarkdownFactory.create(context) { view, link ->
          when {
              link.startsWith("app://toggle_thoughts/") -> handleToggleThoughts(link)
              link.startsWith("app://toggle_sources/") -> handleToggleSources(link)
              else -> openExternalUrl(link)
          }
      }
  }
  ```

### 5.2 User Preference Respect for Cognitive Processes
* Previous behavior unconditionally forced `lastAssistant.isThoughtsCollapsedByUser = true` upon stream termination.
* The updated pipeline respects `settingsManager.showThoughtInProgress`:
  * If the user enables persistent thoughts, the reasoning remains expanded post-generation.
  * If disabled, it neatly collapses into a compact `[▼ Rozbalit myšlenky]` toggle, freely re-expandable at any time.

---

## 6. Verification & Hardware Validation

All capabilities have been validated directly on physical hardware:
* **Test Device:** Samsung Galaxy S24 Ultra (Snapdragon 8 Gen 3, 12 GB LPDDR5X, Knox Vault).
* **OS:** Android 14 / One UI 6.1 (API 34).
* **Test Matrix:**
  * ✅ Keystore save & StrongBox key creation.
  * ✅ Biometric gatekeeping on key reveal & purge.
  * ✅ Streaming SSE from Google AI Studio (`gemini-2.5-flash`).
  * ✅ Markdown table, code block, and list preservation during offline Czech translation.
  * ✅ Zero regression on local GGUF execution.
  * ✅ Interactive thought toggle expand/collapse stability across activity pauses and resumes.

