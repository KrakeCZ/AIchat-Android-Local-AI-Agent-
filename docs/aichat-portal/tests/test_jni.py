"""Unit tests for JNI demangling and C++ <-> Kotlin/Java method matching."""

import pytest
from pathlib import Path
from analyzer.jni import JniLinker, demangle_jni_name
from analyzer.model import ConfidenceLevel, EdgeType, Graph, Node, NodeType


def test_demangle_jni_name_basic():
    mangled = "Java_com_example_ai_NativeBridge_initModel"
    result = demangle_jni_name(mangled)
    assert result is not None
    pkg, cls, method = result
    assert pkg == "com.example.ai"
    assert cls == "NativeBridge"
    assert method == "initModel"


def test_demangle_jni_name_with_escaped_underscore():
    # JNI spec: _1 stands for '_' in class or method name
    mangled = "Java_com_example_ai_Native_1Bridge_run_1inference"
    result = demangle_jni_name(mangled)
    assert result is not None
    pkg, cls, method = result
    assert pkg == "com.example.ai"
    assert cls == "Native_Bridge"
    assert method == "run_inference"


def test_demangle_jni_name_with_overload_signature():
    # JNI spec: __ begins overloaded signature arguments
    mangled = "Java_com_example_ai_NativeBridge_processData__ILjava_lang_String_2"
    result = demangle_jni_name(mangled)
    assert result is not None
    pkg, cls, method = result
    assert pkg == "com.example.ai"
    assert cls == "NativeBridge"
    assert method == "processData"


def test_jni_linker_matching():
    linker = JniLinker()

    known_external = {
        "com.example.ai.NativeBridge.initModel": (
            "app/src/main/java/com/example/ai/NativeBridge.kt",
            42,
            "external fun initModel(): Boolean",
        )
    }

    matched = linker.match_jni_export("Java_com_example_ai_NativeBridge_initModel", known_external)
    assert matched is not None
    file_path, line, snippet = matched
    assert file_path == "app/src/main/java/com/example/ai/NativeBridge.kt"
    assert line == 42
    assert "initModel" in snippet


def test_jni_real_whisper_methods_matching():
    linker = JniLinker()

    known_external = {
        "com.example.llama.agent.perception.WhisperJni.whisperInit": (
            "examples/llama.android/lib/src/main/java/com/example/llama/agent/perception/WhisperJni.kt",
            85,
            "private external fun whisperInit(modelPath: String, computeBackend: String): Long",
        ),
        "com.example.llama.agent.perception.WhisperJni.whisperTranscribe": (
            "examples/llama.android/lib/src/main/java/com/example/llama/agent/perception/WhisperJni.kt",
            86,
            "private external fun whisperTranscribe(ctxPtr: Long, audioData: FloatArray, language: String, nThreads: Int): String",
        ),
        "com.example.llama.agent.perception.WhisperJni.whisperDetectLanguage": (
            "examples/llama.android/lib/src/main/java/com/example/llama/agent/perception/WhisperJni.kt",
            87,
            "private external fun whisperDetectLanguage(ctxPtr: Long, audioData: FloatArray, nThreads: Int): String",
        ),
        "com.example.llama.agent.perception.WhisperJni.whisperFree": (
            "examples/llama.android/lib/src/main/java/com/example/llama/agent/perception/WhisperJni.kt",
            88,
            "private external fun whisperFree(ctxPtr: Long)",
        ),
    }

    # All 4 methods in whisper_jni.cpp must match
    for method_name in ("whisperInit", "whisperTranscribe", "whisperDetectLanguage", "whisperFree"):
        mangled = f"Java_com_example_llama_agent_perception_WhisperJni_{method_name}"
        matched = linker.match_jni_export(mangled, known_external)
        assert matched is not None, f"Failed to match {mangled}"
        file_path, line, snippet = matched
        assert "WhisperJni.kt" in file_path
        assert line >= 85
        assert method_name in snippet


def test_jni_load_library_extraction():
    linker = JniLinker()
    code = """
    companion object {
        init {
            System.loadLibrary("media_engine")
        }
    }
    """
    calls = linker.extract_load_library_calls(code)
    assert len(calls) == 1
    lib_name, line, snippet = calls[0]
    assert lib_name == "media_engine"
    assert 'System.loadLibrary("media_engine")' in snippet
