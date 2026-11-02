"""Unit tests for Gradle and CMake build system dependency analysis."""

import pytest
from pathlib import Path
from analyzer.gradle import GradleAnalyzer
from analyzer.cmake import CMakeAnalyzer
from analyzer.model import ConfidenceLevel, EdgeType, Graph, Node, NodeType


SETTINGS_GRADLE = """
pluginManagement {
    repositories { google(); mavenCentral() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "llama.android"
include(":app")
include(":lib")
include(":whisper")
"""

APP_BUILD_GRADLE = """
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.ai"
    defaultConfig {
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(project(":lib"))
    implementation(project(":whisper"))
    implementation(libs.androidx.core.ktx)
}
"""

CMAKELISTS_SAMPLE = """
cmake_minimum_required(VERSION 3.22.1)
project("media_engine")

add_library(media_engine SHARED
    media_engine_jni.cpp
    academic_yuv_jni.cpp
    videogen_bridge.cpp
    tae_decoder.cpp
)

target_link_libraries(media_engine
    android
    log
    jnigraphics
)
"""


def test_gradle_module_and_cmake_parsing(tmp_path: Path):
    analyzer = GradleAnalyzer(root_dir=tmp_path)
    modules = analyzer.parse_settings("settings.gradle.kts", SETTINGS_GRADLE)
    assert ":app" in modules
    assert ":lib" in modules
    assert ":whisper" in modules
    assert len(analyzer.settings_includes) == 3
    assert analyzer.settings_includes[0][1] == ":app"

    info = analyzer.parse_build_script("app/build.gradle.kts", APP_BUILD_GRADLE)
    assert info.name == ":app"
    assert info.namespace == "com.example.ai"

    dep_modules = [d[0] for d in info.dependencies]
    assert ":lib" in dep_modules
    assert ":whisper" in dep_modules
    assert info.cmake_file == "app/src/main/cpp/CMakeLists.txt"


def test_cmake_sources_and_links(tmp_path: Path):
    cmake_file = tmp_path / "CMakeLists.txt"
    cmake_file.write_text(CMAKELISTS_SAMPLE, encoding="utf-8")

    analyzer = CMakeAnalyzer(root_dir=tmp_path)
    analysis = analyzer.parse_cmake_file("app/src/main/cpp/CMakeLists.txt", CMAKELISTS_SAMPLE)

    assert "media_engine" in analysis.targets
    target = analysis.targets["media_engine"]
    source_names = [s[0] for s in target.sources]

    assert any("media_engine_jni.cpp" in s for s in source_names)
    assert any("academic_yuv_jni.cpp" in s for s in source_names)
    assert any("videogen_bridge.cpp" in s for s in source_names)
    assert any("tae_decoder.cpp" in s for s in source_names)

    assert "android" in target.linked_targets
    assert "log" in target.linked_targets
