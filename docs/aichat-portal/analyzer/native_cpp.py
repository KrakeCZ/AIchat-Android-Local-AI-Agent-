"""Native C/C++ Header and Source Analyzer for AIchat Code Atlas.

Extracts #include statements (quoted and angled), resolves include paths,
extracts C/C++ classes/structs, namespaces, and exported JNI functions
(Java_<package>_<class>_<method>).
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


class NativeCppAnalyzer:
    def __init__(self):
        pass

    def parse_file(self, file_path: str, source_bytes: bytes) -> NativeCppAnalysis:
        text = source_bytes.decode("utf-8", errors="replace")
        lines = text.splitlines()
        lang = "c" if file_path.endswith(".c") else ("header" if file_path.endswith((".h", ".hpp")) else "cpp")

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
        )

