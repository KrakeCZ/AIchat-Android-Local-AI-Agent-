"""File Inventory and Scope Classifier for AIchat Code Atlas.

Discovers and classifies files across the repository, distinguishing
first-party application source, Android resources, native code,
build definitions, and vendored/upstream dependencies.
"""

from __future__ import annotations

import os
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple


EXTENSIONS: Set[str] = {
    ".kt", ".java", ".cpp", ".cc", ".c", ".h", ".hpp",
    ".kts", ".py", ".sh", ".ps1", ".xml"
}

CATEGORIES: Tuple[Tuple[str, re.Pattern[str]], ...] = (
    ("irismotion", re.compile(r"irismotion", re.IGNORECASE)),
    ("gen", re.compile(r"genstudio|diffusion|wan", re.IGNORECASE)),
    ("audio", re.compile(r"audio|whisper|sherpa|voice|srt|subtitle|transcript", re.IGNORECASE)),
    ("vision", re.compile(r"vision|camera|detector|night|zoom", re.IGNORECASE)),
    ("native", re.compile(r"/cpp/|/lib/|jni|cmake", re.IGNORECASE)),
    ("build", re.compile(r"gradle|build\.gradle|settings\.gradle", re.IGNORECASE)),
)

VENDORED_OR_BUILD_PATHS: Tuple[str, ...] = (
    "/cpp/include/",
    "/cpp/libs/",
    "/cpp/whisper.cpp/",
    "/cpp/llama.cpp/",
    "/cpp/ggml/",
    "/build/",
    "/.gradle/",
    "/.idea/",
)


@dataclass
class FileEntry:
    path: str
    name: str
    extension: str
    language: str
    module: str
    category: str
    size_bytes: int
    is_test: bool
    is_resource: bool
    is_manifest: bool
    is_build_script: bool


def determine_category(path: str) -> str:
    norm = path.replace("\\", "/")
    for name, pattern in CATEGORIES:
        if pattern.search(norm):
            return name
    return "core"


def determine_language(path: str) -> str:
    norm = path.replace("\\", "/")
    basename = Path(norm).name.lower()
    if basename == "cmakelists.txt" or basename.endswith(".cmake"):
        return "cmake"

    ext = Path(norm).suffix.lower()
    if ext == ".kt":
        return "kotlin"
    if ext == ".kts":
        return "gradle_kts"
    if ext == ".java":
        return "java"
    if ext in (".cpp", ".cc"):
        return "cpp"
    if ext == ".c":
        return "c"
    if ext in (".h", ".hpp"):
        return "header"
    if ext == ".xml":
        return "xml"
    if ext == ".py":
        return "python"
    if ext == ".sh":
        return "shell"
    if ext == ".ps1":
        return "powershell"
    return "unknown"


def determine_module(path: str) -> str:
    norm = path.replace("\\", "/")
    if "/app/" in norm or norm.startswith("examples/llama.android/app/"):
        return ":app"
    if "/lib/" in norm or norm.startswith("examples/llama.android/lib/"):
        return ":lib"
    if "/whisper/" in norm or norm.startswith("examples/llama.android/whisper/"):
        return ":whisper"
    return ":root"


def is_included_path(path: str, prefix: str = "examples/llama.android/") -> bool:
    norm = path.replace("\\", "/")
    if prefix and not norm.startswith(prefix):
        return False

    for excluded in VENDORED_OR_BUILD_PATHS:
        if excluded in norm:
            return False

    basename = Path(norm).name
    ext = Path(norm).suffix.lower()
    return ext in EXTENSIONS or basename == "CMakeLists.txt" or basename.endswith(".cmake")


class Inventory:
    def __init__(self, root_dir: Path, prefix: str = "examples/llama.android/"):
        self.root_dir = Path(root_dir).resolve()
        self.prefix = prefix
        self.files: Dict[str, FileEntry] = {}
        self.source_commit: str = ""

    def scan(self) -> List[FileEntry]:
        self.files.clear()
        paths: List[str] = []

        # Try git ls-files first
        try:
            raw = subprocess.check_output(
                ["git", "ls-files", "-z"],
                cwd=self.root_dir,
                stderr=subprocess.DEVNULL,
            )
            for part in raw.split(b"\0"):
                if part:
                    paths.append(part.decode("utf-8", errors="replace"))

            commit = subprocess.check_output(
                ["git", "rev-parse", "HEAD"],
                cwd=self.root_dir,
                stderr=subprocess.DEVNULL,
            ).decode().strip()
            self.source_commit = commit
        except Exception:
            # Filesystem fallback
            self.source_commit = "local"
            for root, _, filenames in os.walk(self.root_dir):
                for filename in filenames:
                    abs_p = Path(root) / filename
                    rel_p = abs_p.relative_to(self.root_dir).as_posix()
                    paths.append(rel_p)

        for p in sorted(paths):
            if not is_included_path(p, self.prefix):
                continue

            abs_path = self.root_dir / p
            if not abs_path.is_file():
                continue

            size = abs_path.stat().st_size
            basename = abs_path.name
            ext = abs_path.suffix.lower()
            lang = determine_language(p)
            mod = determine_module(p)
            cat = determine_category(p)
            is_test = "/test/" in p or "/androidTest/" in p
            is_res = "/res/" in p
            is_manifest = basename == "AndroidManifest.xml"
            is_build = ext == ".kts" or basename == "CMakeLists.txt" or basename.endswith(".cmake")

            entry = FileEntry(
                path=p,
                name=basename,
                extension=ext,
                language=lang,
                module=mod,
                category=cat,
                size_bytes=size,
                is_test=is_test,
                is_resource=is_res,
                is_manifest=is_manifest,
                is_build_script=is_build,
            )
            self.files[p] = entry

        return list(self.files.values())
