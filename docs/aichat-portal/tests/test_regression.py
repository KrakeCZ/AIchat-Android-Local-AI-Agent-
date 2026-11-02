"""Integration and regression test suite against the live repository."""

import pytest
from pathlib import Path
from analyzer.resolver import ArchitectureResolver
from analyzer.validation import GraphValidator


@pytest.fixture(scope="session")
def repo_root() -> Path:
    # docs/aichat-portal/tests -> docs/aichat-portal -> docs -> repo root
    current = Path(__file__).resolve().parent
    root = current.parent.parent.parent
    return root


@pytest.fixture(scope="session")
def resolved_graph(repo_root: Path):
    resolver = ArchitectureResolver(root_dir=repo_root)
    graph = resolver.build_graph()
    return graph


def test_graph_integrity_and_no_orphans(repo_root: Path, resolved_graph):
    validator = GraphValidator(root_dir=repo_root)
    report = validator.validate(resolved_graph)

    assert len(report.orphan_edges) == 0, f"Found orphan edges: {report.orphan_edges[:10]}"
    assert len(report.invalid_evidence) == 0, f"Found invalid evidence lines: {report.invalid_evidence[:10]}"
    assert report.total_nodes >= 580, f"Expected >= 580 nodes, got {report.total_nodes}"
    assert report.total_edges >= 2000, f"Expected >= 2000 edges, got {report.total_edges}"


def test_regression_manifest_components(repo_root: Path, resolved_graph):
    validator = GraphValidator(root_dir=repo_root)
    report = validator.validate(resolved_graph)
    assert report.regression_results.get("1_manifest_components") is True, report.regression_details.get("1_manifest_components")


def test_regression_whisper_manager_to_repo(repo_root: Path, resolved_graph):
    validator = GraphValidator(root_dir=repo_root)
    report = validator.validate(resolved_graph)
    assert report.regression_results.get("2_whisper_manager_to_repo") is True, report.regression_details.get("2_whisper_manager_to_repo")


def test_regression_appdatabase_daos(repo_root: Path, resolved_graph):
    validator = GraphValidator(root_dir=repo_root)
    report = validator.validate(resolved_graph)
    assert report.regression_results.get("3_appdatabase_daos") is True, report.regression_details.get("3_appdatabase_daos")


def test_regression_gaze_filter_interface(repo_root: Path, resolved_graph):
    validator = GraphValidator(root_dir=repo_root)
    report = validator.validate(resolved_graph)
    assert report.regression_results.get("4_gaze_filter_interface") is True, report.regression_details.get("4_gaze_filter_interface")


def test_regression_map_type_converter(repo_root: Path, resolved_graph):
    validator = GraphValidator(root_dir=repo_root)
    report = validator.validate(resolved_graph)
    assert report.regression_results.get("5_map_type_converter") is True, report.regression_details.get("5_map_type_converter")


def test_regression_top_level_algorithms(repo_root: Path, resolved_graph):
    validator = GraphValidator(root_dir=repo_root)
    report = validator.validate(resolved_graph)
    assert report.regression_results.get("6_top_level_algorithms") is True, report.regression_details.get("6_top_level_algorithms")


def test_regression_wildcard_controlled(repo_root: Path, resolved_graph):
    validator = GraphValidator(root_dir=repo_root)
    report = validator.validate(resolved_graph)
    assert report.regression_results.get("7_wildcard_controlled") is True, report.regression_details.get("7_wildcard_controlled")


def test_regression_gradle_module_dependencies(repo_root: Path, resolved_graph):
    validator = GraphValidator(root_dir=repo_root)
    report = validator.validate(resolved_graph)
    assert report.regression_results.get("8_gradle_module_dependencies") is True, report.regression_details.get("8_gradle_module_dependencies")


def test_regression_cmake_target_sources(repo_root: Path, resolved_graph):
    validator = GraphValidator(root_dir=repo_root)
    report = validator.validate(resolved_graph)
    assert report.regression_results.get("9_cmake_target_sources") is True, report.regression_details.get("9_cmake_target_sources")


def test_regression_jni_bindings(repo_root: Path, resolved_graph):
    validator = GraphValidator(root_dir=repo_root)
    report = validator.validate(resolved_graph)
    assert report.regression_results.get("10_jni_bindings") is True, report.regression_details.get("10_jni_bindings")


def test_regression_native_library_loads(repo_root: Path, resolved_graph):
    validator = GraphValidator(root_dir=repo_root)
    report = validator.validate(resolved_graph)
    assert report.regression_results.get("11_native_library_loads") is True, report.regression_details.get("11_native_library_loads")


def test_regression_android_resources(repo_root: Path, resolved_graph):
    validator = GraphValidator(root_dir=repo_root)
    report = validator.validate(resolved_graph)
    assert report.regression_results.get("12_android_resources") is True, report.regression_details.get("12_android_resources")


def test_regression_clean_inventory(repo_root: Path, resolved_graph):
    validator = GraphValidator(root_dir=repo_root)
    report = validator.validate(resolved_graph)
    assert report.regression_results.get("13_clean_inventory") is True, report.regression_details.get("13_clean_inventory")


def test_integration_whisper_jni_direct_cpp_binding(resolved_graph):
    # Verify exact direct binding between WhisperJni.kt and whisper_jni.cpp
    direct_edges = [
        e for e in resolved_graph.edges
        if "WhisperJni.kt" in e.source and "whisper_jni.cpp" in e.target
    ]
    assert len(direct_edges) >= 1, "WhisperJni.kt must link directly to whisper_jni.cpp"
    edge = direct_edges[0]
    assert edge.type.value == "jni_binding"
    # Ensure evidence mentions whisperInit or other real JNI method
    snippets = " ".join(ev.snippet for ev in edge.evidence)
    assert "whisperInit" in snippets

    # Verify that NO jni_binding edge connects WhisperJni.kt to CMakeLists.txt
    cmake_jni_edges = [
        e for e in resolved_graph.edges
        if "WhisperJni.kt" in e.source and "CMakeLists.txt" in e.target and e.type.value == "jni_binding"
    ]
    assert len(cmake_jni_edges) == 0, "CMakeLists.txt must not be targeted as a jni_binding"


def test_integration_inference_engine_direct_cpp_binding(resolved_graph):
    # Verify exact direct binding between InferenceEngineImpl.kt and ai_chat.cpp
    direct_edges = [
        e for e in resolved_graph.edges
        if "InferenceEngineImpl.kt" in e.source and "ai_chat.cpp" in e.target
    ]
    assert len(direct_edges) >= 1, "InferenceEngineImpl.kt must link directly to ai_chat.cpp"
    edge = direct_edges[0]
    assert edge.type.value == "jni_binding"
