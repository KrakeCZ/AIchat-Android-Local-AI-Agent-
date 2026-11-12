"""JNI (Java Native Interface) Linker for AIchat Code Atlas.

Connects Kotlin/Java external fun declarations with C/C++ JNIEXPORT implementations
(Java_<package>_<class>_<method>), handles JNI name mangling, and links
System.loadLibrary calls to CMake targets.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Dict, List, Optional, Set, Tuple


SYSTEM_LOAD_LIBRARY_REGEX = re.compile(
    r'System\.loadLibrary\s*\(\s*["\']([^"\']+)["\']\s*\)'
)


def demangle_jni_name(mangled: str) -> Optional[Tuple[str, str, str]]:
    """Demangles a JNI function name into (package, class_name, method_name).

    Handles standard JNI name mangling where _ separates components and
    _1 represents an underscore in an identifier.
    """
    if not mangled.startswith("Java_"):
        return None

    rest = mangled[5:]  # Strip 'Java_'

    # Handle overloaded signatures (starts with __)
    if "__" in rest:
        rest = rest.split("__", 1)[0]

    # Replace _1 with an escaped token
    placeholder = "\x01"
    escaped = rest.replace("_1", placeholder)

    parts = escaped.split("_")
    if len(parts) < 2:
        return None

    # Restore underscores
    restored = [p.replace(placeholder, "_") for p in parts]

    method_name = restored[-1]
    class_name = restored[-2]
    package_parts = restored[:-2]
    package = ".".join(package_parts)

    return package, class_name, method_name


class JniLinker:
    def __init__(self):
        pass

    def extract_load_library_calls(self, text: str) -> List[Tuple[str, int, str]]:
        """Returns list of (library_name, line_no, text_snippet)."""
        res = []
        for idx, line in enumerate(text.splitlines(), 1):
            for m in SYSTEM_LOAD_LIBRARY_REGEX.finditer(line):
                lib_name = m.group(1)
                res.append((lib_name, idx, line.strip()))
        return res

    def match_jni_export(
        self, mangled: str, known_external_methods: Dict[str, Tuple[str, int, str]]
    ) -> Optional[Tuple[str, int, str]]:
        """Matches a C++ JNI export name against a dict of:

        'package.Class.method' -> (kotlin_file_path, line_no, text_snippet).
        """
        demangled = demangle_jni_name(mangled)
        if not demangled:
            return None

        pkg, cls, method = demangled
        fqn = f"{pkg}.{cls}.{method}" if pkg else f"{cls}.{method}"

        if fqn in known_external_methods:
            return known_external_methods[fqn]

        companion_fqn = f"{pkg}.{cls}.Companion.{method}" if pkg else f"{cls}.Companion.{method}"
        if companion_fqn in known_external_methods:
            return known_external_methods[companion_fqn]

        # Case-insensitive or class-only fallback
        for k, v in known_external_methods.items():
            if k.endswith(f".{cls}.{method}") or k.endswith(f".{cls}.Companion.{method}"):
                return v

        return None

