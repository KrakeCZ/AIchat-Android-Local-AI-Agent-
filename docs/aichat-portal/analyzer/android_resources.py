"""Android Resources and ViewBinding Linker for AIchat Code Atlas.

Indexes files in res/ (layouts, drawables, menus, xml configs), maps R.layout.*,
R.drawable.* references in code, and maps ViewBinding classes (e.g. ActivityMainBinding)
to layout XML files.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

# Matches R.layout.name, R.drawable.name, R.menu.name, R.xml.name
R_RESOURCE_REGEX = re.compile(
    r"\bR\.(layout|drawable|menu|xml)\.([A-Za-z0-9_]+)\b"
)

# Matches ViewBinding classes: e.g. ActivityMainBinding, FragmentOmniBinding
VIEW_BINDING_REGEX = re.compile(
    r"\b([A-Z][A-Za-z0-9_]*Binding)\b"
)


def camel_to_snake(name: str) -> str:
    s1 = re.sub(r"(.)([A-Z][a-z]+)", r"\1_\2", name)
    return re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", s1).lower()


def binding_to_layout_name(binding_class: str) -> Optional[str]:
    if not binding_class.endswith("Binding") or binding_class == "ViewBinding":
        return None
    stem = binding_class[:-7]  # strip 'Binding'
    return camel_to_snake(stem)


class AndroidResourceRegistry:
    def __init__(self):
        # Maps (res_type, res_name) -> list of relative file paths
        self.resources: Dict[Tuple[str, str], List[str]] = {}
        # Maps layout name -> layout file path
        self.layouts_by_name: Dict[str, str] = {}

    def register_resource_file(self, rel_path: str) -> None:
        p = Path(rel_path.replace("\\", "/"))
        parts = p.parts
        if "res" not in parts:
            return

        res_idx = parts.index("res")
        if res_idx + 1 >= len(parts):
            return

        folder = parts[res_idx + 1]
        res_type = folder.split("-", 1)[0]  # strip qualifiers like layout-land
        res_name = p.stem.lower()

        key = (res_type, res_name)
        if key not in self.resources:
            self.resources[key] = []
        self.resources[key].append(rel_path)

        if res_type == "layout":
            self.layouts_by_name[res_name] = rel_path

    def find_resource(self, res_type: str, res_name: str) -> List[str]:
        return self.resources.get((res_type, res_name.lower()), [])

    def find_layout_by_binding(self, binding_class: str) -> Optional[str]:
        lay_name = binding_to_layout_name(binding_class)
        if lay_name and lay_name in self.layouts_by_name:
            return self.layouts_by_name[lay_name]
        return None

