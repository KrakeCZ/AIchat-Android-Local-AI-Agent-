# AIchat Code Atlas — Dependency Architecture Coverage Report

**Generated:** 2026-11-02 20:06:25Z  
**Analyzer Version:** 3.1.0 (Clean Room AST Tree-sitter + Semantic Multi-Pass)  
**Schema Version:** 3.0  
**Overall Validation Status:** **ALL CHECKS PASSED**

---

## 1. Executive Summary: Clean Room Inventory & Baseline Comparison

| Metric | Historical Baseline (2026-11-02) | New Clean Analyzer V3 | Delta / Improvement |
|---|---:|---:|---:|
| **Indexed Primary Nodes** | 904 | 590 | **Clean scope: 590 genuine sources** |
| **Excluded Build Artifacts (.cxx)** | 0 (Unfiltered: 350 artifacts) | **350 excluded** | Separated generated build artifacts |
| **Total Relationships (Edges)** | 1,207 | 2,340 | **+1133 edges (+93.9%)** |
| **Connected Nodes** | 442 | 552 | **552 connected (93.6%)** |
| **Isolated Nodes** | 462 (51.1%) | 38 (6.4%) | **Down to 38 (6.4%)** |
| **Orphan Edges (Dangling)** | Unknown | 0 | 0 (Strictly Validated) |
| **Invalid Evidence Lines** | Unknown | 0 | 0 (Strictly Validated: bounds & existence) |

### Key Architectural Fixes & Breakthroughs
1. **Clean Room Inventory Separation:** Excluded 350 CMake temporary build artifacts in `whisper/.cxx/` (including 308 generated `.cpp` and 34 `.cmake` files) that previously polluted the inventory and falsely inflated isolated node counts. Primary first-party code is now 590 clean source files.
2. **True Tree-sitter C/C++ AST Parsing:** Replaced naive regex parsing with a full Tree-sitter AST parser (`tree_sitter_cpp` and `tree_sitter_c`), accurately extracting `#include` paths (quoted vs angled), classes, structs, and `Java_...` JNI function exports.
3. **Class-Qualified Kotlin/Java JNI FQN:** Corrected symbol FQN generation in AST traversal to retain the owning class (`package.Class.method` instead of `package.method`).
4. **Separation of JNI Method Bindings & Library Loads:** Mapped Kotlin `external fun` declarations directly to C++ `Java_...` implementations with exact line evidence on both sides, while categorizing `System.loadLibrary` calls distinctly as `native_library_load`.
5. **Strict Evidence & Line Bounds Verification:** The validator now checks file existence on disk, positive line numbers, file length boundaries, and snippet presence.
6. **Zero Manifest & Resource Isolation:** `AndroidManifest.xml` files across all modules are linked to actual activities and foreground services, and XML layout files are connected via ViewBinding.

---

## 2. Regression Test Results (13 Mandatory AIchat Suites)

| Regression Check | Status | Details |
|---|---|---|
| `10_jni_bindings` | **PASSED** | Verified 5 C++ JNI bridges (Whisper: True, AIchat: True, YUV: True, MediaEngine: True, VideoGen: True). Dual-sided evidence: True, Total 1:1 paired native methods: 49 (0 CMake false bindings) |
| `11_native_library_loads` | **PASSED** | Found 5 native library load edges (System.loadLibrary) |
| `12_android_resources` | **PASSED** | Found 93 layout/resource/ViewBinding edges |
| `13_clean_inventory` | **PASSED** | Clean Room validation: 0 .cxx build artifacts in graph (expected 0) |
| `1_manifest_components` | **PASSED** | Found 2 component edges from AndroidManifest.xml |
| `2_whisper_manager_to_repo` | **PASSED** | Found 1 edges from WhisperModelManager to WhisperModelRepository |
| `3_appdatabase_daos` | **PASSED** | Connected Room DAOs: 6/6 ({'AiModelDao': True, 'BertModelDao': True, 'NlpModelDao': True, 'WhisperModelDao': True, 'GenModelDao': True, 'RemoteProfileDao': True}) |
| `4_gaze_filter_interface` | **PASSED** | Found 7 edges connecting GazeFilter interface |
| `5_map_type_converter` | **PASSED** | Found 1 edges connecting MapTypeConverter |
| `6_top_level_algorithms` | **PASSED** | UI navigation/manifest connections: {'RestrictedSettingsGuideActivity': True, 'SkyWatcherScreenUi': True} |
| `7_wildcard_controlled` | **PASSED** | Found 70 targeted wildcard edges |
| `8_gradle_module_dependencies` | **PASSED** | Found 2 module dependency edges (:lib, :whisper) |
| `9_cmake_target_sources` | **PASSED** | Found 4 C++ source links from app CMakeLists.txt |
---

## 3. Parser Architecture & Tree-sitter AST Utilization

The analyzer uses native Tree-sitter AST parsing across both Kotlin/Java and C/C++ source trees with 0 regex fallback:

| Source Subsystem | Total Files Parsed | Tree-sitter AST Parsed | Fallback Regex Parsed | AST Utilization Rate |
|---|---:|---:|---:|---:|
| **Kotlin / Java** | 413 | 413 | 0 | **100.0%** |
| **C / C++ Native & Headers** | 61 | 61 | 0 | **100.0%** |
| **Total Code AST Handled** | 474 | 474 | 0 | **100.0%** |

- **Deterministic JNI Pairing:** 49 Kotlin `external fun` declarations mapped 1:1 to C++ `JNIEXPORT` symbols across all 5 native engines (`WhisperJni`, `InferenceEngineImpl`, `AcademicYuvEngine`, `NativeVideoEngine`, `VideoGenEngine`).
- **Dual-Sided Structured Evidence:** Every JNI method generates two independent `EdgeEvidence` records (Kotlin source file + line and C++ source file + line), verified for file existence, positive line index, and file boundary.
- **Ambiguity-Protected Symbol Resolution:** Short identifiers (e.g. `Companion`, sealed class `Error`) resolve to external files only when a single unambiguous target exists in the package, eliminating false cross-file links.

---

## 4. Relationships by Edge Type

| Edge Type | Count | Share |
|---|---:|---:|
| `type_reference` | 1,175 | 50.2% |
| `import` | 510 | 21.8% |
| `same_package` | 245 | 10.5% |
| `inheritance` | 95 | 4.1% |
| `resource_reference` | 93 | 4.0% |
| `include` | 76 | 3.2% |
| `import_wildcard` | 70 | 3.0% |
| `manifest_component` | 32 | 1.4% |
| `cmake_source` | 13 | 0.6% |
| `script_source` | 12 | 0.5% |
| `gradle_dependency` | 8 | 0.3% |
| `native_library_load` | 5 | 0.2% |
| `jni_binding` | 5 | 0.2% |
| `import_alias` | 1 | 0.0% |

---

## 5. Confidence Distribution

| Confidence Level | Count | Share | Description |
|---|---:|---:|---|
| `exact_syntax` | 851 | 36.4% | Exact AST node, import statement, or JNI signature match |
| `resolved_static` | 1,489 | 63.6% | Statically verified reference (FQN, same-package, Room DAO, CMake target) |
| `candidate` | 0 | 0.0% | Heuristic or wildcard candidate scoped to package/type |

---

## 6. Coverage by Project Category

| Category | Total Nodes | Connected | Isolated | Connectivity % |
|---|---:|---:|---:|---:|
| `core` | 257 | 243 | 14 | 94.6% |
| `audio` | 139 | 125 | 14 | 89.9% |
| `vision` | 95 | 89 | 6 | 93.7% |
| `irismotion` | 44 | 44 | 0 | 100.0% |
| `gen` | 28 | 28 | 0 | 100.0% |
| `native` | 23 | 20 | 3 | 87.0% |
| `build` | 4 | 3 | 1 | 75.0% |

---

## 7. Verified Connection Highlights & Evidence Samples

### Direct JNI Method Bindings (Kotlin external fun <-> C++ JNIEXPORT)
- `examples/llama.android/app/src/main/java/com/example/llama/agent/ui/studio/genstudio/engine/VideoGenEngine.kt` <-> `examples/llama.android/app/src/main/cpp/videogen_bridge.cpp` (`jni_binding`: [Kotlin external fun] private external fun nativeDetectHardware(): String)
- `examples/llama.android/app/src/main/java/com/example/llama/agent/vision/nightmode/AcademicYuvEngine.kt` <-> `examples/llama.android/app/src/main/cpp/academic_yuv_jni.cpp` (`jni_binding`: [Kotlin external fun] private external fun nativeYuvPlaneToFloatBuffer()
- `examples/llama.android/app/src/main/java/com/example/llama/media/NativeVideoEngine.kt` <-> `examples/llama.android/app/src/main/cpp/media_engine_jni.cpp` (`jni_binding`: [Kotlin external fun] private external fun nativeCancel())
- `examples/llama.android/lib/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt` <-> `examples/llama.android/lib/src/main/cpp/ai_chat.cpp` (`jni_binding`: [Kotlin external fun] private external fun init(nativeLibDir: String))
- `examples/llama.android/lib/src/main/java/com/example/llama/agent/perception/WhisperJni.kt` <-> `examples/llama.android/whisper/src/main/cpp/whisper_jni.cpp` (`jni_binding`: [Kotlin external fun] private external fun whisperInit(modelPath: String, computeBackend: String): Long)

### Native Library Loads (System.loadLibrary -> CMake Target)
- `examples/llama.android/app/src/main/java/com/example/llama/agent/ui/studio/genstudio/engine/VideoGenEngine.kt` -> `examples/llama.android/app/src/main/cpp/CMakeLists.txt` (`native_library_load`: System.loadLibrary("media_engine"))
- `examples/llama.android/app/src/main/java/com/example/llama/agent/vision/nightmode/AcademicYuvEngine.kt` -> `examples/llama.android/app/src/main/cpp/CMakeLists.txt` (`native_library_load`: System.loadLibrary("media_engine"))
- `examples/llama.android/app/src/main/java/com/example/llama/media/NativeVideoEngine.kt` -> `examples/llama.android/app/src/main/cpp/CMakeLists.txt` (`native_library_load`: System.loadLibrary("media_engine"))
- `examples/llama.android/lib/src/main/java/com/arm/aichat/internal/InferenceEngineImpl.kt` -> `examples/llama.android/lib/src/main/cpp/CMakeLists.txt` (`native_library_load`: System.loadLibrary("ai-chat"))
- `examples/llama.android/lib/src/main/java/com/example/llama/agent/perception/WhisperJni.kt` -> `examples/llama.android/whisper/src/main/cpp/CMakeLists.txt` (`native_library_load`: System.loadLibrary("whisper-jni"))

### AndroidManifest.xml Components & Services
- `examples/llama.android/app/src/main/AndroidManifest.xml` -> `examples/llama.android/app/src/main/java/com/example/llama/AudioStudioActivity.kt` (`manifest_component`, confidence `exact_syntax`)
- `examples/llama.android/app/src/main/AndroidManifest.xml` -> `examples/llama.android/app/src/main/java/com/example/llama/MainActivity.kt` (`manifest_component`, confidence `exact_syntax`)
- `examples/llama.android/app/src/main/AndroidManifest.xml` -> `examples/llama.android/app/src/main/java/com/example/llama/SettingsActivity.kt` (`manifest_component`, confidence `exact_syntax`)
- `examples/llama.android/app/src/main/AndroidManifest.xml` -> `examples/llama.android/app/src/main/java/com/example/llama/SrtEditorActivity.kt` (`manifest_component`, confidence `exact_syntax`)
- `examples/llama.android/app/src/main/AndroidManifest.xml` -> `examples/llama.android/app/src/main/java/com/example/llama/agent/irismotion/CalibrationActivity.kt` (`manifest_component`, confidence `exact_syntax`)
- `examples/llama.android/app/src/main/AndroidManifest.xml` -> `examples/llama.android/app/src/main/java/com/example/llama/agent/irismotion/bluetooth/BluetoothHidForegroundService.kt` (`manifest_component`, confidence `exact_syntax`)
- `examples/llama.android/app/src/main/AndroidManifest.xml` -> `examples/llama.android/app/src/main/java/com/example/llama/agent/irismotion/ui/RemoteProfilesActivity.kt` (`manifest_component`, confidence `exact_syntax`)
- `examples/llama.android/app/src/main/AndroidManifest.xml` -> `examples/llama.android/app/src/main/java/com/example/llama/agent/perception/MediaProjectionPermissionActivity.kt` (`manifest_component`, confidence `exact_syntax`)

### CMake Build Links & Native Sources
- `examples/llama.android/app/build.gradle.kts` -> `examples/llama.android/app/src/main/cpp/CMakeLists.txt` (`cmake_source`)
- `examples/llama.android/app/src/main/cpp/CMakeLists.txt` -> `examples/llama.android/app/src/main/cpp/academic_yuv_jni.cpp` (`cmake_source`)
- `examples/llama.android/app/src/main/cpp/CMakeLists.txt` -> `examples/llama.android/app/src/main/cpp/media_engine_jni.cpp` (`cmake_source`)
- `examples/llama.android/app/src/main/cpp/CMakeLists.txt` -> `examples/llama.android/app/src/main/cpp/tae_decoder.cpp` (`cmake_source`)
- `examples/llama.android/app/src/main/cpp/CMakeLists.txt` -> `examples/llama.android/app/src/main/cpp/videogen_bridge.cpp` (`cmake_source`)
- `examples/llama.android/lib/build.gradle.kts` -> `examples/llama.android/lib/src/main/cpp/CMakeLists.txt` (`cmake_source`)
- `examples/llama.android/lib/src/main/cpp/CMakeLists.txt` -> `examples/llama.android/lib/src/main/cpp/ai_chat.cpp` (`cmake_source`)
- `examples/llama.android/whisper/build.gradle.kts` -> `examples/llama.android/whisper/src/main/cpp/CMakeLists.txt` (`cmake_source`)

### Android Resources & ViewBinding
- `examples/llama.android/app/src/main/AndroidManifest.xml` -> `examples/llama.android/app/src/main/res/drawable/ic_mic_24.xml` (`resource_reference`)
- `examples/llama.android/app/src/main/AndroidManifest.xml` -> `examples/llama.android/app/src/main/res/drawable/ic_mouse.xml` (`resource_reference`)
- `examples/llama.android/app/src/main/AndroidManifest.xml` -> `examples/llama.android/app/src/main/res/mipmap-anydpi/ic_launcher.xml` (`resource_reference`)
- `examples/llama.android/app/src/main/AndroidManifest.xml` -> `examples/llama.android/app/src/main/res/mipmap-anydpi/ic_launcher_round.xml` (`resource_reference`)
- `examples/llama.android/app/src/main/AndroidManifest.xml` -> `examples/llama.android/app/src/main/res/xml/accessibility_service_config.xml` (`resource_reference`)
- `examples/llama.android/app/src/main/AndroidManifest.xml` -> `examples/llama.android/app/src/main/res/xml/backup_rules.xml` (`resource_reference`)
- `examples/llama.android/app/src/main/AndroidManifest.xml` -> `examples/llama.android/app/src/main/res/xml/data_extraction_rules.xml` (`resource_reference`)
- `examples/llama.android/app/src/main/AndroidManifest.xml` -> `examples/llama.android/app/src/main/res/xml/file_paths.xml` (`resource_reference`)

---

## 7. Known Architectural Scope & Clean Room Notes

1. **Excluded Generated Build Artifacts:** The 350 files in `whisper/.cxx/` are generated CMake build artifacts and are inventoried separately, leaving exactly 590 first-party application and library source files in the primary dependency graph.
2. **Third-Party External Libraries:** External AndroidX, Coroutines, and Google libraries are referenced as external types but not expanded into standalone nodes unless explicitly declared in Gradle build scripts.
3. **Deterministic AST Pipeline:** Kotlin and C/C++ parsers use Tree-sitter grammars with safe regex fallbacks, ensuring zero crashes on incomplete or dialect-specific syntax.
