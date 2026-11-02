"""Unified Graph and Entity Model for AIchat Code Atlas (Schema v3).

Deterministic representation of files, symbols, Gradle modules, CMake targets,
resources, and their evidence-backed relationships.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional, Set, Tuple


class ConfidenceLevel(str, Enum):
    EXACT_SYNTAX = "exact_syntax"
    RESOLVED_STATIC = "resolved_static"
    CANDIDATE = "candidate"
    UNRESOLVED = "unresolved"

    @classmethod
    def from_str(cls, value: str) -> "ConfidenceLevel":
        try:
            return cls(value)
        except ValueError:
            # Map legacy confidence
            if value in ("exact", "direct"):
                return cls.RESOLVED_STATIC
            if value in ("inferred", "likely"):
                return cls.CANDIDATE
            return cls.CANDIDATE


class NodeType(str, Enum):
    FILE = "file"
    SYMBOL = "symbol"
    GRADLE_MODULE = "gradle_module"
    CMAKE_TARGET = "cmake_target"
    NATIVE_LIBRARY = "native_library"
    RESOURCE = "resource"
    EXTERNAL_DEPENDENCY = "external_dependency"


class EdgeType(str, Enum):
    IMPORT = "import"
    IMPORT_ALIAS = "import_alias"
    IMPORT_WILDCARD = "import_wildcard"
    TYPE_REFERENCE = "type_reference"
    INHERITANCE = "inheritance"
    INTERFACE_IMPLEMENTATION = "interface_implementation"
    CALL_CANDIDATE = "call_candidate"
    SAME_PACKAGE = "same_package"
    JNI_BINDING = "jni_binding"
    NATIVE_LIBRARY_LOAD = "native_library_load"
    MANIFEST_COMPONENT = "manifest_component"
    MANIFEST_PERMISSION = "manifest_permission"
    RESOURCE_REFERENCE = "resource_reference"
    VIEW_BINDING = "view_binding"
    GRADLE_DEPENDENCY = "gradle_dependency"
    CMAKE_SOURCE = "cmake_source"
    CMAKE_LINK = "cmake_link"
    INCLUDE = "include"
    SCRIPT_SOURCE = "script_source"


@dataclass
class EdgeEvidence:
    path: str
    line: int
    column: int = 0
    snippet: str = ""

    def to_dict(self) -> Dict[str, Any]:
        res: Dict[str, Any] = {
            "path": self.path,
            "line": self.line,
            "text": self.snippet[:240],
            "snippet": self.snippet[:240],
        }
        if self.column > 0:
            res["column"] = self.column
        return res

    @classmethod
    def from_dict(cls, data: Dict[str, Any]) -> "EdgeEvidence":
        return cls(
            path=data.get("path", ""),
            line=int(data.get("line", 1)),
            column=int(data.get("column", 0)),
            snippet=str(data.get("snippet", "")),
        )


@dataclass
class Node:
    id: str
    type: NodeType = NodeType.FILE
    name: str = ""
    category: str = "core"
    path: str = ""
    package: str = ""
    symbols: List[Dict[str, Any]] = field(default_factory=list)
    search_symbols: List[str] = field(default_factory=list)
    properties: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "type": self.type.value if isinstance(self.type, NodeType) else str(self.type),
            "name": self.name,
            "category": self.category,
            "path": self.path,
            "package": self.package,
            "symbols": self.symbols[:180],
            "search_symbols": self.search_symbols[:140],
            "properties": self.properties,
        }


@dataclass
class Edge:
    source: str
    target: str
    type: EdgeType = EdgeType.TYPE_REFERENCE
    confidence: ConfidenceLevel = ConfidenceLevel.RESOLVED_STATIC
    analyzer: str = "general"
    evidence: List[EdgeEvidence] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        # Legacy compatibility: confidence maps to "exact" vs "inferred"
        legacy_conf = "exact" if self.confidence in (ConfidenceLevel.EXACT_SYNTAX, ConfidenceLevel.RESOLVED_STATIC) else "inferred"
        return {
            "source": self.source,
            "target": self.target,
            "type": self.type.value if isinstance(self.type, EdgeType) else str(self.type),
            "confidence": legacy_conf,
            "confidence_v3": self.confidence.value if isinstance(self.confidence, ConfidenceLevel) else str(self.confidence),
            "analyzer": self.analyzer,
            "evidence": [e.to_dict() for e in self.evidence[:5]],
        }


class Graph:
    def __init__(self, repository: str = "", source_commit: str = ""):
        self.repository = repository
        self.source_commit = source_commit
        self.nodes: Dict[str, Node] = {}
        # Key: (source, target, type, confidence) -> Edge
        self._edges: Dict[Tuple[str, str, str, str], Edge] = {}
        self.metadata: Dict[str, Any] = {
            "schema_version": 3,
            "analysis": "multimodal-ast-tree-sitter-manifest-jni-cmake-gradle",
        }

    def add_node(self, node: Node) -> Node:
        if node.id in self.nodes:
            existing = self.nodes[node.id]
            # Merge symbols and properties if needed
            if not existing.symbols and node.symbols:
                existing.symbols = node.symbols
            if not existing.search_symbols and node.search_symbols:
                existing.search_symbols = node.search_symbols
            existing.properties.update(node.properties)
            return existing
        self.nodes[node.id] = node
        return node

    def add_edge(
        self,
        source: str,
        target: str,
        edge_type: EdgeType,
        confidence: ConfidenceLevel,
        analyzer: str,
        evidence: Optional[EdgeEvidence] = None,
    ) -> Optional[Edge]:
        if not source or not target or source == target:
            return None
        if source not in self.nodes or target not in self.nodes:
            return None

        t_val = edge_type.value if isinstance(edge_type, EdgeType) else str(edge_type)
        c_val = confidence.value if isinstance(confidence, ConfidenceLevel) else str(confidence)
        key = (source, target, t_val, c_val)

        if key not in self._edges:
            edge = Edge(
                source=source,
                target=target,
                type=edge_type,
                confidence=confidence,
                analyzer=analyzer,
                evidence=[],
            )
            self._edges[key] = edge
        else:
            edge = self._edges[key]

        if evidence is not None:
            # Avoid duplicate evidence
            if not any(
                e.path == evidence.path and e.line == evidence.line
                for e in edge.evidence
            ):
                if len(edge.evidence) < 5:
                    edge.evidence.append(evidence)

        return edge

    @property
    def edges(self) -> List[Edge]:
        return sorted(
            self._edges.values(),
            key=lambda e: (
                e.source,
                e.target,
                e.type.value if isinstance(e.type, EdgeType) else str(e.type),
                e.confidence.value if isinstance(e.confidence, ConfidenceLevel) else str(e.confidence),
            ),
        )

    def to_dict(self) -> Dict[str, Any]:
        sorted_nodes = sorted(self.nodes.values(), key=lambda n: n.id)
        return {
            "schema_version": 3,
            "repository": self.repository,
            "source_commit": self.source_commit,
            "analysis": self.metadata.get("analysis", "multimodal-ast"),
            "nodes": [n.to_dict() for n in sorted_nodes],
            "edges": [e.to_dict() for e in self.edges],
            "metadata": self.metadata,
        }

