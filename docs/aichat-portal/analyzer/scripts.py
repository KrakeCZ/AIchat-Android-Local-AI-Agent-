"""Script Dependency Analyzer for AIchat Code Atlas.

Extracts intra-repository dependencies for Python, Shell, and PowerShell scripts.
Only links verifiable, static relationships without inventing false edges.
"""

from __future__ import annotations

import posixpath
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

PY_IMPORT_REGEX = re.compile(
    r"^\s*(?:from\s+([\w.]+)\s+import|import\s+([\w.]+))",
    re.MULTILINE,
)

SH_SOURCE_REGEX = re.compile(
    r'^\s*(?:source|\.)\s+["\']?([^"\'\s;&]+)["\']?',
    re.MULTILINE,
)

PS1_DOT_SOURCE_REGEX = re.compile(
    r'^\s*(?:\.\s+|&\s+)["\']?([.\\\w-]+\.ps1)["\']?',
    re.MULTILINE | re.IGNORECASE,
)


@dataclass
class ScriptDependency:
    target_path: str
    line: int
    text: str


class ScriptAnalyzer:
    def __init__(self, known_files: Set[str]):
        self.known_files = known_files

    def parse_python(self, rel_path: str, text: str) -> List[ScriptDependency]:
        parent_dir = posixpath.dirname(rel_path.replace("\\", "/"))
        lines = text.splitlines()
        deps: List[ScriptDependency] = []

        for idx, line in enumerate(lines, 1):
            m = PY_IMPORT_REGEX.match(line)
            if m:
                mod = m.group(1) or m.group(2)
                # Check if it corresponds to a local file in parent_dir
                candidate1 = posixpath.normpath(posixpath.join(parent_dir, f"{mod}.py"))
                candidate2 = posixpath.normpath(posixpath.join(parent_dir, mod.replace(".", "/"), "__init__.py"))

                if candidate1 in self.known_files:
                    deps.append(ScriptDependency(target_path=candidate1, line=idx, text=line.strip()))
                elif candidate2 in self.known_files:
                    deps.append(ScriptDependency(target_path=candidate2, line=idx, text=line.strip()))

        return deps

    def parse_shell(self, rel_path: str, text: str) -> List[ScriptDependency]:
        parent_dir = posixpath.dirname(rel_path.replace("\\", "/"))
        lines = text.splitlines()
        deps: List[ScriptDependency] = []

        for idx, line in enumerate(lines, 1):
            m = SH_SOURCE_REGEX.match(line)
            if m:
                target_name = m.group(1)
                candidate = posixpath.normpath(posixpath.join(parent_dir, target_name))
                if candidate in self.known_files:
                    deps.append(ScriptDependency(target_path=candidate, line=idx, text=line.strip()))

        return deps

    def parse_powershell(self, rel_path: str, text: str) -> List[ScriptDependency]:
        parent_dir = posixpath.dirname(rel_path.replace("\\", "/"))
        lines = text.splitlines()
        deps: List[ScriptDependency] = []

        for idx, line in enumerate(lines, 1):
            m = PS1_DOT_SOURCE_REGEX.match(line)
            if m:
                target_name = m.group(1).replace("\\", "/")
                candidate = posixpath.normpath(posixpath.join(parent_dir, target_name))
                if candidate in self.known_files:
                    deps.append(ScriptDependency(target_path=candidate, line=idx, text=line.strip()))

        return deps

