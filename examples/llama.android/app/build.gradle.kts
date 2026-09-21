import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.jetbrains.compose.compiler)
    alias(libs.plugins.google.ksp)
    id("kotlin-parcelize")
    // KAPT removed - prism4j-bundler is unmaintained and doesn't work with modern Gradle/Kotlin
}

android {
    namespace = "com.example.llama"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.llama.aichat"

        minSdk = 33
        targetSdk = 35

        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // NDK ABI filtry pro Dual-Pipeline (arm64-v8a = primární, x86_64 = emulátor)
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
                )
            }
        }
    }

    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/cpp/libs")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"

    }
    packaging {
        jniLibs {
            // 16 KB Page Alignment FIX: useLegacyPackaging=false
            // Legacy packaging ukládá .so jako nekomprimované ZIP entry — systém je mmap()
            // přímo z APK, což vyžaduje zarovnání uvnitř ZIP (Gradle to nezaručuje pro AAR .so).
            // false = .so se extrahuje do /data/app/ při instalaci → OS zarovnání řeší sám.
            // Nevyhoda: Instalace je pomalejší, ale .so je vždy správně zarovnána.
            useLegacyPackaging = true
            // Debug symbols zachovány pro nativní crash analysis (LLDB, Perfetto)
            keepDebugSymbols += "**/*.so"

            // Prevence kolize JNI knihoven (libc++_shared.so) mezi NDK a ML Kit / AICore / Sherpa
            pickFirsts.add("**/libc++_shared.so")
            pickFirsts.add("**/libx86.so")
            pickFirsts.add("**/libx86_64.so")
            pickFirsts.add("**/libarmeabi-v7a.so")
            pickFirsts.add("**/libarm64-v8a.so")
            // LibVLC 3.7.0 — prevence kolize VLC nativních knihoven s NDK libc++
            // pickFirsts.add("**/libvlc.so")
            // pickFirsts.add("**/libvlcjni.so")
            // pickFirsts.add("**/libvlc_pulse.so")
            pickFirsts.add("**/libanw.14.so")
            pickFirsts.add("**/libanw.18.so")
            pickFirsts.add("**/libanw.21.so")
            pickFirsts.add("**/libanw.24.so")
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    ndkVersion = "29.0.13846066 rc3"
    buildToolsVersion = "36.1.0"

    // ── Dual-Pipeline NDK Build (FFmpeg + libass) ──────────────────────────
    // CMakeLists.txt detekuje přítomnost statických .a knihoven v cpp/libs/<ABI>/
    // a sestaví libmedia_engine.so pouze pokud existují.
    // Pokud knihovny chybí: Gradle Sync proběhne bez chyby, .so se nesestavi,
    // aplikace poběží v Media3-only režimu (OrchestráTOR to detekuje přes isAvailable()).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

configurations.all {
    exclude(group = "org.jetbrains", module = "annotations-java5")
}

dependencies {
    implementation(libs.bundles.androidx)
    implementation(libs.material)

    implementation(project(":lib"))
    implementation(project(":whisper"))

    // Sherpa-ONNX ZIPFORMER OFFLINE ENGINE (Local Enterprise version 1.12.34)
    implementation(files("libs/sherpa-onnx-1.12.34.aar"))

    // Compose Dependencies (Interoperability API)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Coil for Compose
    implementation("io.coil-kt:coil-compose:2.6.0")

    // MARKWON & PRISM4J
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.tables)
    implementation(libs.markwon.ext.strikethrough)
    implementation(libs.markwon.ext.tasklist)
    implementation(libs.markwon.ext.latex)
    implementation(libs.markwon.html)
    implementation(libs.markwon.syntax.highlight)
    implementation(libs.prism4j)
    // Note: prism4j-bundler doesn't work with modern KSP/KAPT - removed
    // Syntax highlighting will show code blocks properly formatted but without colors

    // [FIX] Manual GrammarLocator uses local definitions now

    // AndroidX Preference (for PreferenceManager in MarkdownFactory)
    implementation(libs.androidx.preference)

    // Room KSP
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.runtime)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // IrisMotion — Face Landmark detection (Eye Tracking)
    implementation(libs.mediapipe.tasks.vision)

    // ML Kit Text Recognition (OCR for PerceptionEngine)
    implementation(libs.mlkit.text.recognition)

    // MediaPipe Tasks Audio removed: YAMNet runs on pure LiteRT Interpreter now to avoid 16KB JNI conflicts
    // implementation(libs.mediapipe.tasks.audio)

    // ==================== NLP Tier 2.5 — Enterprise NLP Pipeline ====================
    // Core (100% offline, vsechna zarizeni)
    implementation(libs.mlkit.entity.extraction)    // Entity Extraction (ADDRESS, DATETIME, URL, PHONE, EMAIL, IBAN)
    implementation(libs.mlkit.language.id)           // Language Identification (100+ jazyku)
    implementation(libs.mlkit.smart.reply)           // Smart Reply (EN only, ~2MB)
    implementation(libs.mlkit.translate)             // Translate (59 jazyku, ~30MB/jazyk, offline po stazeni)
    implementation(libs.mediapipe.tasks.text)        // TextClassifier (sentiment, MobileBERT z disku)
    implementation(libs.mediapipe.tasks.genai)       // MediaPipe LLM Inference (lokální GGUF/Gemma modely)

    // LiteRT On-Device Engine (nahrazuje zastaralý TFLite)
    implementation(libs.litert.core)
    implementation(libs.litert.api)
    implementation(libs.litert.gpu)
    implementation(libs.litert.gpu.api)

    // ─────────────────────────────────────────────────────────────────────────
    // Qualcomm AI Engine Direct (QNN) — NPU delegate pro Hexagon HTP
    // Cílí na Snapdragon 8 Gen 3 (S24 Ultra) Hexagon Tensor Processor.
    //
    // Cascade v AcademicYuvEngine.kt: NPU (HTP) → GPU (Adreno) → CPU (4 vlákna)
    // FLOAT32 model: QNN automaticky offloaduje podporované ops na HTP,
    //                nepodporované fallbackuje na GPU/CPU (bez pádu aplikace).
    // INT8 model:    plné NPU využití → cílová latence 8–25 ms per frame.
    //
    // Verze 2.47.0 je nejnovější k červnu 2026 (Maven Central).
    // Při upgradu LiteRT nutno ověřit kompatibilitu qnn-litert-delegate verze.
    // Dokumentace: ai.google.dev/edge/litert/android/qualcomm
    // ─────────────────────────────────────────────────────────────────────────
    implementation(libs.qnn.runtime)
    implementation(libs.qnn.litert.delegate)

    // TFLite Metadata Extractor pro automatické vyčítání label map z .tflite modelů (např. YOLO)
    // Upgrade na 0.5.0/LiteRT Metadata plánován až po stabilizaci NDK 29 pipeline.
    implementation("org.tensorflow:tensorflow-lite-metadata:0.4.4")

    // Premium GenAI (AICore / Gemini Nano — pouze kompatibilni zarizeni)
    implementation(libs.mlkit.genai.summarization)   // Summarization (1-3 bullets)
    implementation(libs.mlkit.genai.proofreading)    // Proofreading (EN/DE/FR/ES/IT/JA/KO)
    implementation(libs.mlkit.genai.rewriting)       // Rewriting (ELABORATE/SHORTEN/FRIENDLY/PROFESSIONAL)
    implementation(libs.mlkit.genai.prompt)          // Prompt API (text+image, multimodal)
    implementation(libs.mlkit.genai.image.description) // Image Description (Bitmap → text popis)
    implementation(libs.mlkit.genai.speech.recognition) // Speech Recognition (AICore on-device ASR)

    // CameraX — IrisMotion kamera pipeline  "1.4.1"-new , old_ "1.3.4"
    val camerax_version = "1.5.3"
    implementation("androidx.camera:camera-core:${camerax_version}")
    implementation("androidx.camera:camera-camera2:${camerax_version}")
    implementation("androidx.camera:camera-lifecycle:${camerax_version}")
    implementation("androidx.camera:camera-view:${camerax_version}")

    // ==================== LibVLC 3.7.0 — Multi-track prehravac ====================
    // Stabilni release 03/2026 | Maven Central | VideoLAN aktivne udrzovano
    // Podporuje: MKV, MTS, TS, MP4, AVI, FLAC, DTS, AC-3, TrueHD
    // POZOR: FFmpegKit (com.arthenica) je ARCHIVOVAN od 2025 — NEPOUZIVAT!
    // implementation("org.videolan.android:libvlc-all:3.7.0")

    // ==================== Media3 — SRT LiveEditor (ExoPlayer) ====================
    // ExoPlayer pro video přehrávání v SRT LiveEditor v2 a AudioStudio
    // (Transformer a Effect byly nahrazeny naším nativním C++ Enginem)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)
}

// ==================== Zamezení Duplicate Classes pro LiteRT ====================
// Následující blok zajistí, že MediaPipe a další knihovny, které tranzitivně
// závisí na zastaralém org.tensorflow:tensorflow-lite, použijí místo něj
// náš com.google.ai.edge.litert bez konfliktů Duplicate Classes.
configurations.all {
    exclude(group = "org.tensorflow", module = "tensorflow-lite-api")
    exclude(group = "org.tensorflow", module = "tensorflow-lite")
    exclude(group = "org.tensorflow", module = "tensorflow-lite-gpu-api")
    exclude(group = "org.tensorflow", module = "tensorflow-lite-gpu")
}


// ==================== Model Assets Validation ====================
// Pro splnění pravidel "Reproducible Build" a open-source standardů (MIT)
// už Gradle sám nestahuje žádné modely během synchronizace. 
// Vývojář si musí modely stáhnout lokálně zavoláním skriptu:
// - Windows: .\scripts\download_models.ps1
// - Linux/Mac: ./scripts/download_models.sh

tasks.register("checkModels") {
    group = "verification"
    description = "Ověří, zda jsou všechny povinné AI modely stažené v assets/."
    
    val assetsDir = File(projectDir, "src/main/assets")
    val requiredModels = listOf(
        "face_landmarker.task",
        "efficientdet_lite0.tflite",
        "yamnet.tflite",
        "pose_landmarker_lite.task"
    )
    
    doFirst {
        var missingModels = false
        logger.lifecycle("==================================================")
        logger.lifecycle("🤖 KONTROLA LOKÁLNÍCH AI MODELŮ (Open Source Mode)")
        logger.lifecycle("==================================================")
        
        requiredModels.forEach { modelName ->
            if (!File(assetsDir, modelName).exists()) {
                logger.error("❌ CHYBÍ: assets/$modelName")
                missingModels = true
            } else {
                logger.lifecycle("✅ NALEZENO: assets/$modelName")
            }
        }
        
        if (missingModels) {
            logger.error("==================================================")
            throw GradleException(
                "Některé AI modely chybí v adresáři src/main/assets/.\n" +
                "Pro úspěšný build spusťte skript pro stažení závislostí:\n" +
                "  Windows: .\\scripts\\download_models.ps1\n" +
                "  Linux/Mac: ./scripts/download_models.sh"
            )
        } else {
            logger.lifecycle("==================================================")
            logger.lifecycle("✅ Všechny vyžadované modely jsou připraveny k buildu.")
            logger.lifecycle("==================================================")
        }
    }
}

// Přiřazení checkModels před preBuild, aby build okamžitě spadl, pokud modely chybí.
afterEvaluate {
    tasks.named("preBuild") {
        dependsOn("checkModels")
    }
}

