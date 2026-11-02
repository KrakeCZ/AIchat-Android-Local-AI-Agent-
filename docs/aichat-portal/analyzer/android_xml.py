"""Android XML and Manifest Analyzer for AIchat Code Atlas.

Parses AndroidManifest.xml, layout files, menus, and XML configs to extract:
- Declared components (Activity, Service, Receiver, Provider, Application)
- Custom view references in layout files (<com.example.llama...>)
- Layout includes (<include layout="@layout/...">, <ViewStub android:layout="@layout/...">)
- Resource references (@drawable/..., @xml/..., @menu/..., @mipmap/..., @layout/...)
"""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

# Matches @drawable/name, @xml/name, @menu/name, @mipmap/name, @layout/name
RESOURCE_REF_REGEX = re.compile(
    r'@(drawable|layout|menu|xml|mipmap)/([A-Za-z0-9_]+)'
)


@dataclass
class ManifestComponent:
    kind: str  # 'activity', 'service', 'receiver', 'provider', 'application'
    raw_name: str
    resolved_fqn: str
    line: int
    text: str
    permission: Optional[str] = None
    foreground_service_type: Optional[str] = None


@dataclass
class CustomViewRef:
    class_fqn: str
    line: int
    text: str


@dataclass
class LayoutIncludeRef:
    target_layout: str
    line: int
    text: str


@dataclass
class XmlResourceRef:
    res_type: str  # 'drawable', 'layout', 'menu', 'xml', 'mipmap'
    res_name: str
    line: int
    text: str


@dataclass
class AndroidXmlAnalysis:
    file_path: str
    is_manifest: bool
    package: str
    manifest_components: List[ManifestComponent] = field(default_factory=list)
    custom_views: List[CustomViewRef] = field(default_factory=list)
    layout_includes: List[LayoutIncludeRef] = field(default_factory=list)
    resource_refs: List[XmlResourceRef] = field(default_factory=list)
    search_symbols: List[str] = field(default_factory=list)


def resolve_component_name(raw: str, default_package: str) -> str:
    raw = raw.strip()
    if not raw:
        return ""
    if raw.startswith("."):
        return f"{default_package}{raw}"
    if "." in raw:
        return raw
    # Single class name without package prefix
    if default_package:
        return f"{default_package}.{raw}"
    return raw


class AndroidXmlAnalyzer:
    def __init__(self, default_namespaces: Optional[Dict[str, str]] = None):
        # Maps module path (e.g. :app) to namespace
        self.default_namespaces = default_namespaces or {
            ":app": "com.example.llama",
            ":lib": "com.arm.aichat",
            ":whisper": "com.example.llama.whisper",
        }

    def parse_file(self, file_path: str, source_bytes: bytes, module: str = ":app") -> AndroidXmlAnalysis:
        text = source_bytes.decode("utf-8", errors="replace")
        lines = text.splitlines()
        is_manifest = file_path.endswith("AndroidManifest.xml")

        def find_line(pattern: str) -> Tuple[int, str]:
            for idx, line in enumerate(lines, 1):
                if pattern in line:
                    return idx, line.strip()
            return 1, ""

        default_pkg = self.default_namespaces.get(module, "com.example.llama")
        package = default_pkg
        components: List[ManifestComponent] = []
        custom_views: List[CustomViewRef] = []
        includes: List[LayoutIncludeRef] = []
        resource_refs: List[XmlResourceRef] = []
        search_symbols: Set[str] = set()

        # Extract all resource references (@drawable/..., @xml/..., @menu/..., @mipmap/..., @layout/...)
        for idx, line in enumerate(lines, 1):
            line_str = line.strip()
            for rm in RESOURCE_REF_REGEX.finditer(line):
                rtype = rm.group(1)
                rname = rm.group(2)
                resource_refs.append(
                    XmlResourceRef(
                        res_type=rtype,
                        res_name=rname,
                        line=idx,
                        text=line_str,
                    )
                )

        try:
            root = ET.fromstring(source_bytes)
        except Exception:
            return AndroidXmlAnalysis(
                file_path=file_path,
                is_manifest=is_manifest,
                package=package,
                resource_refs=resource_refs,
            )

        if is_manifest:
            # Check package attribute on <manifest>
            manifest_pkg = root.attrib.get("package")
            if manifest_pkg:
                package = manifest_pkg

            # Application
            app_elem = root.find("application")
            if app_elem is not None:
                app_name = app_elem.attrib.get(f"{ANDROID_NS}name") or app_elem.attrib.get("android:name")
                if app_name:
                    fqn = resolve_component_name(app_name, package)
                    line_no, line_txt = find_line(app_name)
                    components.append(
                        ManifestComponent(
                            kind="application",
                            raw_name=app_name,
                            resolved_fqn=fqn,
                            line=line_no,
                            text=line_txt,
                        )
                    )
                    search_symbols.add(app_name.rsplit(".", 1)[-1])

                # Iterate children of application: activity, service, receiver, provider, activity-alias
                for child in app_elem:
                    tag = child.tag
                    # Strip namespace if present
                    if tag.startswith("{"):
                        tag = tag.split("}", 1)[1]

                    if tag in ("activity", "service", "receiver", "provider", "activity-alias"):
                        c_name = child.attrib.get(f"{ANDROID_NS}name") or child.attrib.get("android:name")
                        if not c_name and tag == "activity-alias":
                            c_name = child.attrib.get(f"{ANDROID_NS}targetActivity") or child.attrib.get("android:targetActivity")

                        if c_name:
                            fqn = resolve_component_name(c_name, package)
                            line_no, line_txt = find_line(c_name)
                            perm = child.attrib.get(f"{ANDROID_NS}permission") or child.attrib.get("android:permission")
                            fg_type = child.attrib.get(f"{ANDROID_NS}foregroundServiceType") or child.attrib.get("android:foregroundServiceType")

                            components.append(
                                ManifestComponent(
                                    kind=tag,
                                    raw_name=c_name,
                                    resolved_fqn=fqn,
                                    line=line_no,
                                    text=line_txt,
                                    permission=perm,
                                    foreground_service_type=fg_type,
                                )
                            )
                            search_symbols.add(c_name.rsplit(".", 1)[-1])

        else:
            # Layout, menu, or navigation XML
            for elem in root.iter():
                tag = elem.tag
                if tag.startswith("{"):
                    tag = tag.split("}", 1)[1]

                # Custom views have a dot in the tag name: e.g. <com.example.llama.ui.widget.WaveformView>
                if "." in tag:
                    line_no, line_txt = find_line(tag)
                    custom_views.append(
                        CustomViewRef(
                            class_fqn=tag,
                            line=line_no,
                            text=line_txt,
                        )
                    )
                    search_symbols.add(tag.rsplit(".", 1)[-1])

                # <fragment android:name="..." class="...">
                elif tag in ("fragment", "androidx.fragment.app.FragmentContainerView"):
                    f_name = (
                        elem.attrib.get(f"{ANDROID_NS}name")
                        or elem.attrib.get("android:name")
                        or elem.attrib.get("class")
                    )
                    if f_name and "." in f_name:
                        line_no, line_txt = find_line(f_name)
                        custom_views.append(
                            CustomViewRef(
                                class_fqn=f_name,
                                line=line_no,
                                text=line_txt,
                            )
                        )
                        search_symbols.add(f_name.rsplit(".", 1)[-1])

                # <include layout="@layout/...">
                elif tag == "include":
                    lay = elem.attrib.get("layout")
                    if lay and lay.startswith("@layout/"):
                        lay_name = lay.split("/", 1)[1]
                        line_no, line_txt = find_line(lay)
                        includes.append(
                            LayoutIncludeRef(
                                target_layout=lay_name,
                                line=line_no,
                                text=line_txt,
                            )
                        )

                # <ViewStub android:layout="@layout/...">
                elif tag == "ViewStub":
                    lay = elem.attrib.get(f"{ANDROID_NS}layout") or elem.attrib.get("android:layout")
                    if lay and lay.startswith("@layout/"):
                        lay_name = lay.split("/", 1)[1]
                        line_no, line_txt = find_line(lay)
                        includes.append(
                            LayoutIncludeRef(
                                target_layout=lay_name,
                                line=line_no,
                                text=line_txt,
                            )
                        )

        return AndroidXmlAnalysis(
            file_path=file_path,
            is_manifest=is_manifest,
            package=package,
            manifest_components=components,
            custom_views=custom_views,
            layout_includes=includes,
            resource_refs=resource_refs,
            search_symbols=sorted(search_symbols),
        )
