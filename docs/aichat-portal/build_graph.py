#!/usr/bin/env python3
"""AIchat Code Atlas v3: Multi-modal AST and Deterministic Dependency Graph Generator.

CLI orchestrator for static architecture analysis, regression validation,
and automated coverage reporting.

Usage (from repository root or portal directory):
    python docs/aichat-portal/build_graph.py
    python docs/aichat-portal/build_graph.py --check
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

# Ensure portal root is on sys.path
PORTAL_DIR = Path(__file__).resolve().parent
REPO_ROOT = PORTAL_DIR.parent.parent

if str(PORTAL_DIR) not in sys.path:
    sys.path.insert(0, str(PORTAL_DIR))

from analyzer.model import Graph
from analyzer.reporting import generate_coverage_report
from analyzer.resolver import ArchitectureResolver
from analyzer.validation import GraphValidator


OUTPUT_PATH = PORTAL_DIR / "graph-index.json"
REPORT_PATH = PORTAL_DIR / "GRAPH_COVERAGE_REPORT.md"


def main() -> int:
    parser = argparse.ArgumentParser(description="Generate AIchat Code Atlas v3 graph index.")
    parser.add_argument(
        "--check",
        action="store_true",
        help="Check whether graph-index.json and validation are up to date.",
    )
    parser.add_argument(
        "--skip-report",
        action="store_true",
        help="Skip generating GRAPH_COVERAGE_REPORT.md.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=OUTPUT_PATH,
        help="Path to output graph JSON file.",
    )
    args = parser.parse_args()

    print(f"--> Initializing ArchitectureResolver (Repository: {REPO_ROOT})")
    resolver = ArchitectureResolver(root_dir=REPO_ROOT)

    print("--> Resolving ASTs, symbols, JNI bridges, Manifest components, and build links...")
    graph = resolver.build_graph()

    print("--> Validating graph integrity and running regression test suites...")
    validator = GraphValidator(root_dir=REPO_ROOT)
    validation_report = validator.validate(graph)

    # Retrieve AST parser metrics
    parser_stats = resolver.get_parser_stats()

    # Generate Markdown coverage report
    if not args.skip_report:
        generate_coverage_report(
            graph,
            validation_report,
            output_path=REPORT_PATH,
            parser_stats=parser_stats,
        )
        print(f"--> Coverage report written to: {REPORT_PATH}")

    # Prepare JSON data
    graph_dict = graph.to_dict()
    data_str = json.dumps(graph_dict, ensure_ascii=False, indent=2) + "\n"

    total_nodes = len(graph.nodes)
    total_edges = len(graph.edges)
    isolated_nodes = validation_report.isolated_nodes
    connected_nodes = total_nodes - isolated_nodes

    kt_s = parser_stats.get("kotlin_java", {})
    cpp_s = parser_stats.get("native_cpp", {})
    tot_ast = kt_s.get("ast_parsed_count", 0) + cpp_s.get("ast_parsed_count", 0)
    tot_fb = kt_s.get("fallback_parsed_count", 0) + cpp_s.get("fallback_parsed_count", 0)
    tot_files = tot_ast + tot_fb
    ast_pct = (tot_ast / tot_files * 100.0) if tot_files > 0 else 0.0

    print("\n================== GRAPH GENERATION SUMMARY ==================")
    print(f"Total Nodes (Indexed Files):  {total_nodes}")
    print(f"Total Edges (Dependencies):   {total_edges}")
    print(f"Connected Nodes:              {connected_nodes} ({(connected_nodes / total_nodes * 100):.1f}%)")
    print(f"Isolated Nodes:               {isolated_nodes} ({(isolated_nodes / total_nodes * 100):.1f}%)")
    print(f"Orphan (Dangling) Edges:      {len(validation_report.orphan_edges)}")
    print(f"Invalid Evidence Lines:       {len(validation_report.invalid_evidence)}")
    print("--------------------------------------------------------------")
    print(f"Tree-sitter AST Utilization:  {tot_ast}/{tot_files} files ({ast_pct:.1f}%) [Kotlin/Java: {kt_s.get('ast_parsed_count', 0)}, C/C++: {cpp_s.get('ast_parsed_count', 0)}, Fallback: {tot_fb}]")
    print("--------------------------------------------------------------")
    print(f"Regression Test Suites ({len(validation_report.regression_results)}/{len(validation_report.regression_results)}):")
    all_passed = True
    for key, passed in sorted(validation_report.regression_results.items()):
        status = "PASSED" if passed else "FAILED"
        if not passed:
            all_passed = False
        print(f"  [{status}] {key}: {validation_report.regression_details.get(key, '')}")
    print("==============================================================\n")

    if args.check:
        if not args.output.exists():
            print(f"Error: {args.output} does not exist.", file=sys.stderr)
            return 1
        existing_str = args.output.read_text(encoding="utf-8")
        if existing_str != data_str:
            print(f"Error: {args.output} is out of date.", file=sys.stderr)
            return 1
        if not validation_report.is_valid or not all_passed:
            print("Error: Graph validation failed.", file=sys.stderr)
            return 1
        print(f"Verification successful: {args.output} is up to date and all checks passed.")
        return 0

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(data_str, encoding="utf-8")
    print(f"--> Successfully wrote graph index to: {args.output}")

    if not validation_report.is_valid or not all_passed:
        print("Warning: Some validation checks or regressions failed.", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
