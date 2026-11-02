"""CMake Build Script Analyzer for AIchat Code Atlas.

Parses CMakeLists.txt files, extracting declared targets (libraries, executables),
their source files (e.g. media_engine_jni.cpp, academic_yuv_jni.cpp), include directories,
and linked targets.
"""

from __future__ import annotations

import posixpath
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

ADD_LIBRARY_BLOCK_REGEX = re.compile(
    r"add_library\s*\(\s*([\w.-]+|\$\{[^}]+\})\s+(?:STATIC|SHARED|MODULE|INTERFACE)?\s*([^)]*)\)",
    re.IGNORECASE | re.DOTALL,
)

TARGET_SOURCES_REGEX = re.compile(
    r"target_sources\s*\(\s*([\w.-]+|\$\{[^}]+\})\s+(?:PRIVATE|PUBLIC|INTERFACE)?\s*([^)]*)\)",
    re.IGNORECASE | re.DOTALL,
)

ADD_SUBDIRECTORY_REGEX = re.compile(
    r"add_subdirectory\s*\(\s*([^)\s]+)(?:\s+([^)\s]+))?\s*\)",
    re.IGNORECASE,
)

TARGET_LINK_LIBRARIES_REGEX = re.compile(
    r"target_link_libraries\s*\(\s*([\w.-]+|\$\{[^}]+\})\s+([^)]*)\)",
    re.IGNORECASE | re.DOTALL,
)

PROJECT_REGEX = re.compile(
    r'project\s*\(\s*["\']?([\w.-]+)["\']?',
    re.IGNORECASE,
)


@dataclass
class CMakeTarget:
    name: str
    kind: str  # 'SHARED', 'STATIC', etc.
    sources: List[Tuple[str, int, str]] = field(default_factory=list)  # (rel_source_path, line, snippet)
    linked_targets: List[str] = field(default_factory=list)
    line: int = 1


@dataclass
class CMakeAnalysis:
    file_path: str
    project_name: str
    targets: Dict[str, CMakeTarget] = field(default_factory=dict)
    subdirectories: List[Tuple[str, int]] = field(default_factory=list)


class CMakeAnalyzer:
    def __init__(self, root_dir: Path):
        self.root_dir = Path(root_dir).resolve()

    def parse_cmake_file(self, rel_path: str, content: str) -> CMakeAnalysis:
        parent_dir = posixpath.dirname(rel_path.replace("\\", "/"))
        lines = content.splitlines()

        proj_name = ""
        pm = PROJECT_REGEX.search(content)
        if pm:
            proj_name = pm.group(1)

        def find_line(pattern: str) -> Tuple[int, str]:
            for idx, line in enumerate(lines, 1):
                if pattern in line:
                    return idx, line.strip()
            return 1, ""

        targets: Dict[str, CMakeTarget] = {}

        # Parse add_library
        for m in ADD_LIBRARY_BLOCK_REGEX.finditer(content):
            raw_target_name = m.group(1).strip()
            # If target name is ${CMAKE_PROJECT_NAME}, resolve to proj_name
            target_name = proj_name if "${CMAKE_PROJECT_NAME}" in raw_target_name else raw_target_name
            raw_sources = m.group(2).strip()
            line_no = content.count("\n", 0, m.start()) + 1

            target = CMakeTarget(name=target_name, kind="library", line=line_no)

            for token in raw_sources.split():
                token = token.strip()
                if not token or token.startswith(("-", "$", "#")):
                    continue
                # If it looks like a source file: .cpp, .c, .cc, .h, .hpp
                if any(token.endswith(ext) for ext in (".cpp", ".c", ".cc", ".h", ".hpp", ".cu")):
                    resolved_path = posixpath.normpath(posixpath.join(parent_dir, token))
                    source_line, line_txt = find_line(token)
                    target.sources.append((resolved_path, source_line, line_txt or token))

            targets[target_name] = target

        # Parse target_sources
        for m in TARGET_SOURCES_REGEX.finditer(content):
            raw_target_name = m.group(1).strip()
            target_name = proj_name if "${CMAKE_PROJECT_NAME}" in raw_target_name else raw_target_name
            raw_sources = m.group(2).strip()

            if target_name not in targets:
                targets[target_name] = CMakeTarget(name=target_name, kind="library")
            target = targets[target_name]

            for token in raw_sources.split():
                token = token.strip()
                if not token or token.startswith(("-", "$", "#")):
                    continue
                if any(token.endswith(ext) for ext in (".cpp", ".c", ".cc", ".h", ".hpp")):
                    resolved_path = posixpath.normpath(posixpath.join(parent_dir, token))
                    source_line, line_txt = find_line(token)
                    target.sources.append((resolved_path, source_line, line_txt or token))

        # Parse target_link_libraries
        for m in TARGET_LINK_LIBRARIES_REGEX.finditer(content):
            raw_target_name = m.group(1).strip()
            target_name = proj_name if "${CMAKE_PROJECT_NAME}" in raw_target_name else raw_target_name
            raw_links = m.group(2).strip()

            if target_name in targets:
                for token in raw_links.split():
                    token = token.strip()
                    if token and not token.startswith(("-", "$", "#", "PRIVATE", "PUBLIC", "INTERFACE")):
                        targets[target_name].linked_targets.append(token)

        # Parse add_subdirectory
        subdirs: List[Tuple[str, int]] = []
        for m in ADD_SUBDIRECTORY_REGEX.finditer(content):
            sub_path = m.group(1).strip()
            if not sub_path.startswith("$"):
                line_no = content.count("\n", 0, m.start()) + 1
                subdirs.append((sub_path, line_no))

        return CMakeAnalysis(
            file_path=rel_path,
            project_name=proj_name,
            targets=targets,
            subdirectories=subdirs,
        )

