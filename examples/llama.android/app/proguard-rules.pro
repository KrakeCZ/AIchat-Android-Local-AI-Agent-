# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# --- ANDROID & KOTLIN DEFAULTS ---
-keepattributes SourceFile,LineNumberTable,InnerClasses,Signature,EnclosingMethod,Exceptions

# --- AI ENGINE & NATIVE LIBS ---
# Keep all interfaces to native C++ code (Llama.cpp / JNI)
-keep class com.arm.aichat.** { *; }
-keep class com.example.llama.data.** { *; }

# Prevent R8 from stripping Native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- DATA MODELS & SERIALIZATION ---
# Critical for JSON Parsing: Keep names of AgentAction fields and Enums
-keepclassmembers enum com.example.llama.agent.models.ActionType {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep class com.example.llama.agent.models.** { *; }

# --- COROUTINES & FLOW ---
# Prevent AtomicFU/Coroutines crashes in Release
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.atomicfu.** { *; }

# --- LOGGING STRIPPING (PRODUCTION — ACTIVE) ---
# Odstraní VŠECHNY Log.* volání z bytecode — eliminuje InfoSec riziko + zrychlí exekuci.
# Bezpečné: assumenosideeffects nemá vedlejší efekty na funkčnost.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}

# --- MARKDOWN & PRISM ---
-keep class io.noties.markwon.** { *; }

# --- ML Kit GenAI / AICore (JNI/Reflection interfaces) ---
# GenAI API používá reflexi pro IPC do AICore systémové služby.
# Bez keep rules R8 odstraní třídy a inference selže s ClassNotFoundException.
-keep class com.google.mlkit.genai.** { *; }
-keep class com.google.android.gms.internal.mlkit_genai.** { *; }

# --- ML Kit NLP Core (Entity Extraction, Language ID, Smart Reply, Translate) ---
-keep class com.google.mlkit.nl.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision.** { *; }

# --- MediaPipe Tasks (Vision/Text/Audio — JNI/Native) ---
-keep class com.google.mediapipe.** { *; }
-keep class org.tensorflow.lite.** { *; }