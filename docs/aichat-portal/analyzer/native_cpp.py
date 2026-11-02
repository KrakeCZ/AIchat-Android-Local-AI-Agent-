"""Native C/C++ Header and Source Analyzer for AIchat Code Atlas.

Extracts #include statements (quoted and angled), resolves include paths,
extracts C/C++ classes/structs, namespaces, and exported JNI functions
(Java_<package>_<class>_<method>) using Tree-sitter AST with regex fallback.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath
from typing import Any, Dict, List, Optional, Set, Tuple

from .symbols import Symbol

INCLUDE_REGEX = re.compile(
    r'^\s*#\s*include\s*([<"])([^>"]+)[>"]',
    re.MULTILINE,
)

JNI_EXPORT_REGEX = re.compile(
    r"\b(Java_[A-Za-z0-9_]+)\s*\("
)

CPP_CLASS_REGEX = re.compile(
    r"(?m)^\s*(?:template\s*<[^>]+>\s*)?(?:class|struct)\s+([A-Za-z_]\w*)(?:\s*final)?\s*(?::|\{|\n)"
)

# Optional Tree-sitter integration
_HAS_TREE_SITTER = False
_CPP_PARSER = None
_C_PARSER = None

try:
    import tree_sitter
    import tree_sitter_cpp
    import tree_sitter_c

    _cpp_language = tree_sitter.Language(tree_sitter_cpp.language())
    _c_language = tree_sitter.Language(tree_sitter_c.language())
    _CPP_PARSER = tree_sitter.Parser(_cpp_language)
    _C_PARSER = tree_sitter.Parser(_c_language)
    _HAS_TREE_SITTER = True
except Exception:
    _HAS_TREE_SITTER = False


@dataclass
class IncludeRef:
    name: str
    is_system: bool  # True for <...>, False for "..."
    line: int
    text: str


@dataclass
class JniExport:
    mangled_name: str
    line: int
    text: str


@dataclass
class NativeCppAnalysis:
    file_path: str
    language: str
    includes: List[IncludeRef] = field(default_factory=list)
    jni_exports: List[JniExport] = field(default_factory=list)
    symbols: List[Symbol] = field(default_factory=list)
    search_symbols: List[str] = field(default_factory=list)
    used_ast: bool = False


class NativeCppAnalyzer:
    def __init__(self):
        self.ast_parsed_count: int = 0
        self.fallback_parsed_count: int = 0

    def parse_file(self, file_path: str, source_bytes: bytes) -> NativeCppAnalysis:
        lang = "c" if file_path.endswith(".c") else ("header" if file_path.endswith((".h", ".hpp")) else "cpp")

        if _HAS_TREE_SITTER:
            try:
                res = self._parse_ast(file_path, lang, source_bytes)
                self.ast_parsed_count += 1
                return res
            except Exception:
                pass

        res = self._parse_regex(file_path, lang, source_bytes)
        self.fallback_parsed_count += 1
        return res

    def _parse_ast(self, file_path: str, lang: str, source_bytes: bytes) -> NativeCppAnalysis:
        parser = _C_PARSER if lang == "c" else _CPP_PARSER
        tree = parser.parse(source_bytes)
        text = source_bytes.decode("utf-8", errors="replace")
        lines = text.splitlines()

        includes: List[IncludeRef] = []
        jni_exports: List[JniExport] = []
        symbols: List[Symbol] = []
        search_symbols: Set[str] = set()

        seen_jni: Set[str] = set()
        seen_includes: Set[Tuple[str, int]] = set()

        def walk(node):
            ntype = node.type

            # Includes: #include <header> or #include "header"
            if ntype == "preproc_include":
                for child in node.children:
                    if child.type in ("system_lib_string", "string_literal"):
                        raw = source_bytes[child.start_byte:child.end_byte].decode("utf-8", errors="replace")
                        is_sys = child.type == "system_lib_string"
                        inc_path = raw.strip('<>"').replace("\\", "/")
                        line_no = node.start_point[0] + 1
                        line_txt = lines[line_no - 1].strip() if line_no <= len(lines) else raw
                        key = (inc_path, line_no)
                        if key not in seen_includes:
                            seen_includes.add(key)
                            includes.append(
                                IncludeRef(
                                    name=inc_path,
                                    is_system=is_sys,
                                    line=line_no,
                                    text=line_txt,
                                )
                            )

            # Classes and structs
            elif ntype in ("class_specifier", "struct_specifier"):
                for child in node.children:
                    if child.type in ("type_identifier", "identifier"):
                        cname = source_bytes[child.start_byte:child.end_byte].decode("utf-8", errors="replace")
                        line_no = child.start_point[0] + 1
                        symbols.append(
                            Symbol(
                                name=cname,
                                kind="class",
                                fqn=cname,
                                file_path=file_path,
                                line=line_no,
                                is_top_level=True,
                            )
                        )
                        search_symbols.add(cname)
                        break

            # Function declarators and JNI exports
            elif ntype in ("function_declarator", "identifier", "field_identifier"):
                txt = source_bytes[node.start_byte:node.end_byte].decode("utf-8", errors="replace")
                if txt.startswith("Java_") and "(" not in txt and " " not in txt:
                    if txt not in seen_jni:
                        seen_jni.add(txt)
                        line_no = node.start_point[0] + 1
                        line_txt = lines[line_no - 1].strip() if line_no <= len(lines) else txt
                        jni_exports.append(
                            JniExport(
                                mangled_name=txt,
                                line=line_no,
                                text=line_txt,
                            )
                        )
                        symbols.append(
                            Symbol(
                                name=txt,
                                kind="jni_method",
                                fqn=txt,
                                file_path=file_path,
                                line=line_no,
                                is_top_level=True,
                            )
                        )
                        search_symbols.add(txt)

            for child in node.children:
                walk(child)

        walk(tree.root_node)

        # Fallback check for any JNIEXPORT macros on broken AST nodes
        for idx, line in enumerate(lines, 1):
            if "Java_" in line:
                for jm in JNI_EXPORT_REGEX.finditer(line):
                    mangled = jm.group(1)
                    if mangled not in seen_jni:
                        seen_jni.add(mangled)
                        jni_exports.append(
                            JniExport(
                                mangled_name=mangled,
                                line=idx,
                                text=line.strip(),
                            )
                        )
                        symbols.append(
                            Symbol(
                                name=mangled,
                                kind="jni_method",
                                fqn=mangled,
                                file_path=file_path,
                                line=idx,
                                is_top_level=True,
                            )
                        )
                        search_symbols.add(mangled)

        return NativeCppAnalysis(
            file_path=file_path,
            language=lang,
            includes=includes,
            jni_exports=jni_exports,
            symbols=symbols,
            search_symbols=sorted(search_symbols),
            used_ast=True,
        )

    def _parse_regex(self, file_path: str, lang: str, source_bytes: bytes) -> NativeCppAnalysis:
        text = source_bytes.decode("utf-8", errors="replace")
        lines = text.splitlines()

        includes: List[IncludeRef] = []
        jni_exports: List[JniExport] = []
        symbols: List[Symbol] = []
        search_symbols: Set[str] = set()

        for idx, line in enumerate(lines, 1):
            # Includes
            m = INCLUDE_REGEX.match(line)
            if m:
                is_system = m.group(1) == "<"
                inc_name = m.group(2).replace("\\", "/")
                includes.append(
                    IncludeRef(
                        name=inc_name,
                        is_system=is_system,
                        line=idx,
                        text=line.strip(),
                    )
                )

            # JNI exported methods
            for jm in JNI_EXPORT_REGEX.finditer(line):
                mangled = jm.group(1)
                jni_exports.append(
                    JniExport(
                        mangled_name=mangled,
                        line=idx,
                        text=line.strip(),
                    )
                )
                search_symbols.add(mangled)
                symbols.append(
                    Symbol(
                        name=mangled,
                        kind="jni_method",
                        fqn=mangled,
                        file_path=file_path,
                        line=idx,
                        is_top_level=True,
                    )
                )

        # Classes and structs
        for m in CPP_CLASS_REGEX.finditer(text):
            name = m.group(1)
            line_no = text.count("\n", 0, m.start(1)) + 1
            symbols.append(
                Symbol(
                    name=name,
                    kind="class",
                    fqn=name,
                    file_path=file_path,
                    line=line_no,
                    is_top_level=True,
                )
            )
            search_symbols.add(name)

        return NativeCppAnalysis(
            file_path=file_path,
            language=lang,
            includes=includes,
            jni_exports=jni_exports,
            symbols=symbols,
            search_symbols=sorted(search_symbols),
            used_ast=False,
        )
