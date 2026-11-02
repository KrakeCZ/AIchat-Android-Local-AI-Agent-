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
    assert report.total_nodes > 800, f"Expected > 800 nodes, got {report.total_nodes}"
    assert report.total_edges > 1500, f"Expected > 1500 edges, got {report.total_edges}"


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


def test_regression_android_resources(repo_root: Path, resolved_graph):
    validator = GraphValidator(root_dir=repo_root)
    report = validator.validate(resolved_graph)
    assert report.regression_results.get("11_android_resources") is True, report.regression_details.get("11_android_resources")


def test_regression_cpp_includes(repo_root: Path, resolved_graph):
    validator = GraphValidator(root_dir=repo_root)
    report = validator.validate(resolved_graph)
    assert report.regression_results.get("12_cpp_includes") is True, report.regression_details.get("12_cpp_includes")
