"""Negative tests and ambiguity resolution tests for static graph analysis."""

import pytest
from pathlib import Path
from analyzer.kotlin_java import KotlinJavaAnalyzer
from analyzer.model import ConfidenceLevel, EdgeType, Graph, Node, NodeType
from analyzer.symbols import Symbol, SymbolTable


COMMENTED_AND_STRING_SAMPLE = b"""
package com.example.ai

// import com.example.ai.FakeUnusedClass
/* 
   val phantom = PhantomDependency()
*/

class CleanClass {
    val message = "This string mentions GazeFilter and MainActivity, but they should not be AST references!"
    val jsonKey = "AppDatabase"
}
"""


def test_comments_and_strings_ignored_by_ast():
    analyzer = KotlinJavaAnalyzer()
    extracted = analyzer.parse_file("app/CleanClass.kt", COMMENTED_AND_STRING_SAMPLE)

    # The commented import should not be in imports
    import_names = [imp.name for imp in extracted.imports]
    assert "com.example.ai.FakeUnusedClass" not in import_names

    # "PhantomDependency" inside block comment should not be in type_references
    ref_names = [r.name for r in extracted.type_references]
    assert "PhantomDependency" not in ref_names


def test_graph_rejects_self_loop():
    graph = Graph()
    node = Node(id="app/Main.kt", type=NodeType.FILE, path="app/Main.kt")
    graph.add_node(node)

    edge = graph.add_edge(
        source="app/Main.kt",
        target="app/Main.kt",
        edge_type=EdgeType.TYPE_REFERENCE,
        confidence=ConfidenceLevel.RESOLVED_STATIC,
        analyzer="test",
    )
    assert edge is None
    assert len(graph.edges) == 0


def test_graph_rejects_orphan_endpoints():
    graph = Graph()
    node = Node(id="app/Main.kt", type=NodeType.FILE, path="app/Main.kt")
    graph.add_node(node)

    # Target does not exist
    edge1 = graph.add_edge(
        source="app/Main.kt",
        target="app/NonExistent.kt",
        edge_type=EdgeType.TYPE_REFERENCE,
        confidence=ConfidenceLevel.RESOLVED_STATIC,
        analyzer="test",
    )
    assert edge1 is None

    # Source does not exist
    edge2 = graph.add_edge(
        source="app/Ghost.kt",
        target="app/Main.kt",
        edge_type=EdgeType.TYPE_REFERENCE,
        confidence=ConfidenceLevel.RESOLVED_STATIC,
        analyzer="test",
    )
    assert edge2 is None
    assert len(graph.edges) == 0


def test_ambiguous_short_symbol_lookup():
    symbol_table = SymbolTable()

    # Two classes named Config in different packages
    s1 = Symbol(name="Config", fqn="com.example.ai.core.Config", file_path="app/core/Config.kt", line=5, kind="class")
    s2 = Symbol(name="Config", fqn="com.example.ai.net.Config", file_path="app/net/Config.kt", line=10, kind="class")
    symbol_table.register(s1)
    symbol_table.register(s2)

    # FQN lookup resolves uniquely
    assert symbol_table.lookup_fqn("com.example.ai.core.Config")[0].file_path == "app/core/Config.kt"
    assert symbol_table.lookup_fqn("com.example.ai.net.Config")[0].file_path == "app/net/Config.kt"

    # Same-package lookup resolves uniquely
    same_core = symbol_table.lookup_same_package("com.example.ai.core", "Config")
    assert len(same_core) == 1
    assert same_core[0].file_path == "app/core/Config.kt"

    same_net = symbol_table.lookup_same_package("com.example.ai.net", "Config")
    assert len(same_net) == 1
    assert same_net[0].file_path == "app/net/Config.kt"
