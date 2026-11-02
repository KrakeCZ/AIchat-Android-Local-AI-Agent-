"""Symbol Model and Global Symbol Table for AIchat Code Atlas.

Provides deterministic registry and lookup for types, top-level functions,
constants, interfaces, DAOs, JNI methods, and resource identifiers.
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from typing import Dict, List, Optional, Set, Tuple


@dataclass
class Symbol:
    name: str
    kind: str
    fqn: str
    file_path: str
    line: int
    column: int = 0
    package: str = ""
    is_top_level: bool = False
    is_external: bool = False

    def __post_init__(self):
        if not self.package and "." in self.fqn:
            self.package = self.fqn.rsplit(".", 1)[0]

    def to_dict(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "kind": self.kind,
            "fqn": self.fqn,
            "line": self.line,
            "is_top_level": self.is_top_level,
            "is_external": self.is_external,
        }


class SymbolTable:
    def __init__(self):
        self.by_fqn: Dict[str, List[Symbol]] = defaultdict(list)
        self.by_package_name: Dict[Tuple[str, str], List[Symbol]] = defaultdict(list)
        self.by_name: Dict[str, List[Symbol]] = defaultdict(list)
        self.by_file: Dict[str, List[Symbol]] = defaultdict(list)
        self.top_levels: Dict[Tuple[str, str], List[Symbol]] = defaultdict(list)

    def register(self, symbol: Symbol) -> None:
        self.by_name[symbol.name].append(symbol)
        self.by_file[symbol.file_path].append(symbol)

        if symbol.fqn:
            self.by_fqn[symbol.fqn].append(symbol)

        if symbol.package:
            self.by_package_name[(symbol.package, symbol.name)].append(symbol)
            if symbol.is_top_level:
                self.top_levels[(symbol.package, symbol.name)].append(symbol)

    def lookup_fqn(self, fqn: str) -> List[Symbol]:
        return self.by_fqn.get(fqn, [])

    def lookup_same_package(self, package: str, name: str) -> List[Symbol]:
        return self.by_package_name.get((package, name), [])

    def lookup_by_name(self, name: str) -> List[Symbol]:
        return self.by_name.get(name, [])

    def lookup_top_level(self, package: str, name: str) -> List[Symbol]:
        return self.top_levels.get((package, name), [])

    def get_file_symbols(self, file_path: str) -> List[Symbol]:
        return self.by_file.get(file_path, [])
