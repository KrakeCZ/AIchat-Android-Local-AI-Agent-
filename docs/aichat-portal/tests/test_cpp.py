"""Unit tests for C/C++ native source analysis."""

import pytest
from pathlib import Path
from analyzer.native_cpp import NativeCppAnalyzer
from analyzer.model import ConfidenceLevel, EdgeType, Graph, Node, NodeType


CPP_SAMPLE = b"""#include "media_engine.h"
#include <jni.h>
#include <android/log.h>
#include "../common/utils.h"

class EngineCore {
public:
    void init();
};

extern "C" JNIEXPORT jint JNICALL
Java_com_example_ai_media_MediaEngine_nativeInit(JNIEnv *env, jobject thiz) {
    return 0;
}
"""


def test_cpp_analyzer_includes_and_jni():
    analyzer = NativeCppAnalyzer()
    extracted = analyzer.parse_file("app/src/main/cpp/media_engine_jni.cpp", CPP_SAMPLE)

    local_includes = [inc.name for inc in extracted.includes if not inc.is_system]
    system_includes = [inc.name for inc in extracted.includes if inc.is_system]

    assert "media_engine.h" in local_includes
    assert "../common/utils.h" in local_includes
    assert "jni.h" in system_includes
    assert "android/log.h" in system_includes

    # Check JNI export
    assert len(extracted.jni_exports) >= 1
    export = extracted.jni_exports[0]
    assert export.mangled_name == "Java_com_example_ai_media_MediaEngine_nativeInit"
    assert export.line >= 10

    # Check class symbol
    symbol_names = [s.name for s in extracted.symbols]
    assert "EngineCore" in symbol_names
