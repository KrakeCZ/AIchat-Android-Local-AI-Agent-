"""Gradle Build Script and Module Dependency Analyzer for AIchat Code Atlas.

Extracts module declarations from settings.gradle.kts, inter-module project
dependencies (e.g. implementation(project(":lib"))), and links between Gradle
modules and their underlying CMake build scripts.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

MODULE_INCLUDE_REGEX = re.compile(
    r'include\s*\(\s*["\'](:[\w.-]+)["\']\s*\)'
)

PROJECT_DEP_REGEX = re.compile(
    r'(?:implementation|api|compileOnly|ksp|testImplementation)\s*\(\s*project\s*\(\s*["\'](:[\w.-]+)["\']\s*\)\s*\)'
)

CMAKE_PATH_REGEX = re.compile(
    r'(?:path\s*=\s*file\s*\(\s*["\']([^"\']+)["\']\s*\)|path\s*\(\s*["\']([^"\']+)["\']\s*\))'
)

NAMESPACE_REGEX = re.compile(
    r'namespace\s*=\s*["\']([\w.]+)["\']'
)


@dataclass
class GradleModuleInfo:
    name: str  # e.g. ':app'
    build_file: str
    namespace: str = ""
    dependencies: List[Tuple[str, int, str]] = field(default_factory=list)  # (target_module, line, text)
    cmake_file: Optional[str] = None
    cmake_line: int = 0
    cmake_text: str = ""


class GradleAnalyzer:
    def __init__(self, root_dir: Path):
        self.root_dir = Path(root_dir).resolve()
        self.modules: Dict[str, GradleModuleInfo] = {}
        # (settings_path, target_module, line, text)
        self.settings_includes: List[Tuple[str, str, int, str]] = []

    def parse_settings(self, settings_rel_path: str, content: str) -> List[str]:
        modules = []
        for idx, line in enumerate(content.splitlines(), 1):
            for m in MODULE_INCLUDE_REGEX.finditer(line):
                mod_name = m.group(1)
                modules.append(mod_name)
                self.settings_includes.append((settings_rel_path, mod_name, idx, line.strip()))
        return modules

    def parse_build_script(self, build_rel_path: str, content: str) -> GradleModuleInfo:
        lines = content.splitlines()
        parent_dir = Path(build_rel_path).parent

        # Determine module name from directory: app -> :app
        mod_name = f":{parent_dir.name}" if parent_dir.name in ("app", "lib", "whisper") else ":root"

        namespace = ""
        deps: List[Tuple[str, int, str]] = []
        cmake_file = None
        cmake_line = 0
        cmake_text = ""

        for idx, line in enumerate(lines, 1):
            nm = NAMESPACE_REGEX.search(line)
            if nm:
                namespace = nm.group(1)

            for dm in PROJECT_DEP_REGEX.finditer(line):
                target_mod = dm.group(1)
                deps.append((target_mod, idx, line.strip()))

            cm = CMAKE_PATH_REGEX.search(line)
            if cm:
                raw_cmake = cm.group(1) or cm.group(2)
                cmake_file = (parent_dir / raw_cmake).as_posix()
                cmake_line = idx
                cmake_text = line.strip()

        info = GradleModuleInfo(
            name=mod_name,
            build_file=build_rel_path,
            namespace=namespace,
            dependencies=deps,
            cmake_file=cmake_file,
            cmake_line=cmake_line,
            cmake_text=cmake_text,
        )
        self.modules[mod_name] = info
        return info
