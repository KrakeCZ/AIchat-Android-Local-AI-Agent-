
#!/usr/bin/env python3
"""AIchat Code Atlas v2: conservative, evidence-backed source graph.

Run from repository root, after build_index.py:
    python docs/aichat-portal/build_graph.py
    python docs/aichat-portal/build_graph.py --check

Only writes docs/aichat-portal/graph-index.json.
Uses Python standard library. No Android source modifications.

This is a conservative lexical analyzer, NOT a compiler or full AST parser.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
INDEX = HERE / "source-index.json"
OUTPUT = HERE / "graph-index.json"

DECL = re.compile(
    r"(?m)^\s*(?:(?:public|private|protected|internal|open|"
    r"abstract|final|data|sealed|enum|annotation|expect|actual|"
    r"value|static)\s+)*"
    r"(class|interface|object|struct|record)\s+([A-Za-z_]\w*)"
)
METHOD = re.compile(
    r"(?m)^\s*(?:(?:public|private|protected|internal|"
    r"override|suspend|inline|open|final|abstract|"
    r"external|static)\s+)*fun\s+"
    r"(?:<[^>\n]+>\s*)?([A-Za-z_]\w*)\s*\("
)
PACKAGE = re.compile(r"^\s*package\s+([\w.]+)")
IMPORT = re.compile(
    r"^\s*import\s+(?:static\s+)?([\w.*]+)"
    r"(?:\s+as\s+([A-Za-z_]\w*))?"
)
INCLUDE = re.compile(r'^\s*#\s*include\s*[<"]([^>"]+)[>"]')
QUALIFIED = re.compile(
    r"\b(?:[a-z_][A-Za-z0-9_]*\.){2,}"
    r"[A-Z][A-Za-z0-9_]*\b"
)
LOCAL_TYPE_USE = re.compile(
    r"\b([A-Z][A-Za-z0-9_]*)\s*(?=\(|\.)"
)
MEMBER_CALL = re.compile(
    r"\b([A-Z][A-Za-z0-9_]*)\.([A-Za-z_]\w*)\s*\("
)

# Preserve newlines while ignoring comments and string literals.
# Nested block comments and complex language-specific grammar remain
# outside the guarantees of this lightweight analyzer.
IGNORED = re.compile(
    r'"""[\s\S]*?"""'
    r'|/\*[\s\S]*?\*/'
    r'|//[^\n]*'
    r'|"(?:\\.|[^"\\])*"'
    r"|\'(?:\\.|[^'\\])*\'"
)

CATEGORIES = (
    ("irismotion", re.compile(r"irismotion", re.I)),
    ("gen", re.compile(r"genstudio|diffusion|wan", re.I)),
    ("audio", re.compile(r"audio|whisper|sherpa|voice|srt|subtitle|transcript", re.I)),
    ("vision", re.compile(r"vision|camera|detector|night|zoom", re.I)),
    ("native", re.compile(r"/cpp/|/lib/|jni|cmake|gradle", re.I)),
)


def category(path: str) -> str:
    for name, pattern in CATEGORIES:
        if pattern.search(path):
            return name
    return "core"


def mask(text: str) -> str:
    return IGNORED.sub(
        lambda match: re.sub(r"[^\n]", " ", match.group()),
        text,
    )


def single(values):
    unique = set(values)
    return next(iter(unique)) if len(unique) == 1 else None


def line_info(lines, number):
    return {
        "line": number,
        "text": lines[number - 1].strip()[:240],
    }


def inspect(path: str, old: dict):
    file = ROOT / path
    if not file.is_file():
        return None

    text = file.read_text(encoding="utf-8", errors="replace")
    original = text.splitlines()
    clean = mask(text)
    masked_lines = clean.splitlines()

    package = ""
    declarations = []
    imports = []
    includes = []

    for match in DECL.finditer(clean):
        declarations.append({
            "kind": match.group(1),
            "name": match.group(2),
            "line": clean.count("\n", 0, match.start(2)) + 1,
        })

    for match in METHOD.finditer(clean):
        declarations.append({
            "kind": "function",
            "name": match.group(1),
            "line": clean.count("\n", 0, match.start(1)) + 1,
        })

    for number, content in enumerate(masked_lines, 1):
        found = PACKAGE.match(content)
        if found:
            package = found.group(1)

        found = IMPORT.match(content)
        if found:
            imports.append({
                "name": found.group(1),
                "alias": found.group(2),
                **line_info(original, number),
            })

    # C/C++ includes contain strings, so inspect the original lines.
    for number, content in enumerate(original, 1):
        found = INCLUDE.match(content)
        if found:
            includes.append({
                "name": found.group(1),
                **line_info(original, number),
            })

    return {
        "path": path,
        "name": file.name,
        "category": category(path),
        "package": package,
        "symbols": declarations,
        "search_symbols": old.get("symbols", []),
        "source": original,
        "masked": masked_lines,
        "imports": imports,
        "includes": includes,
    }


def create_graph():
    if not INDEX.is_file():
        raise RuntimeError(
            "source-index.json missing. Run build_index.py first."
        )

    index = json.loads(INDEX.read_text(encoding="utf-8"))
    data = []

    for entry in index.get("files", []):
        if "path" not in entry:
            continue
        item = inspect(entry["path"], entry)
        if item:
            data.append(item)

    data.sort(key=lambda item: item["path"])

    by_path = {item["path"]: item for item in data}
    by_fqn = defaultdict(set)
    by_package = defaultdict(set)
    by_filename = defaultdict(set)

    for item in data:
        by_filename[item["name"]].add(item["path"])
        for symbol in item["symbols"]:
            if symbol["kind"] == "function":
                continue
            name = symbol["name"]
            if item["package"]:
                by_fqn[item["package"] + "." + name].add(item["path"])
                by_package[(item["package"], name)].add(item["path"])

    grouped = {}

    def add(source, target, kind, certainty, number, snippet):
        if not target or source == target or target not in by_path:
            return

        key = (source, target, kind, certainty)
        if key not in grouped:
            grouped[key] = {
                "source": source,
                "target": target,
                "type": kind,
                "confidence": certainty,
                "evidence": [],
            }

        evidence = {"line": number, "text": snippet.strip()[:240]}
        if evidence not in grouped[key]["evidence"]:
            if len(grouped[key]["evidence"]) < 5:
                grouped[key]["evidence"].append(evidence)

    for item in data:
        source = item["path"]
        package = item["package"]
        directory = str(Path(source).parent)
        known_imports = {}

        for entry in item["imports"]:
            imported = entry["name"]
            if imported.endswith(".*"):
                continue

            target = single(by_fqn.get(imported, ()))
            if target:
                add(source, target, "import", "exact",
                    entry["line"], entry["text"])
                alias = entry["alias"] or imported.rsplit(".", 1)[-1]
                known_imports[alias] = target

        for entry in item["includes"]:
            include = entry["name"].replace("\\", "/")
            exact = str(Path(directory, include))
            # Resolve ../ without filesystem dependence.
            from posixpath import normpath
            exact = normpath(exact)

            if exact in by_path:
                add(source, exact, "include", "exact",
                    entry["line"], entry["text"])
            else:
                candidates = {
                    p for p in by_path
                    if p.endswith("/" + include)
                }
                target = single(candidates)
                if target:
                    add(source, target, "include", "inferred",
                        entry["line"], entry["text"])

        for number, line in enumerate(item["masked"], 1):
            text = item["source"][number - 1]
            stripped = line.lstrip()

            if stripped.startswith(("import ", "package ")):
                continue

            # Explicit fully qualified reference.
            for match in QUALIFIED.finditer(line):
                target = single(by_fqn.get(match.group(), ()))
                if target:
                    add(source, target, "qualified", "exact",
                        number, text)

            # Simple class references in the same package.
            # Syntax is suggestive, but not semantic proof.
            for match in LOCAL_TYPE_USE.finditer(line):
                name = match.group(1)
                target = single(by_package.get((package, name), ()))
                if target:
                    add(source, target, "same_package", "inferred",
                        number, text)

            # Owner.method() candidates.
            # They are not proof of runtime dispatch.
            for match in MEMBER_CALL.finditer(line):
                owner = match.group(1)
                target = known_imports.get(owner)

                if not target:
                    target = single(
                        by_package.get((package, owner), ())
                    )

                if target:
                    add(source, target, "call_candidate", "inferred",
                        number, text)

    nodes = []
    for item in data:
        nodes.append({
            "id": item["path"],
            "name": item["name"],
            "category": item["category"],
            "package": item["package"],
            "symbols": item["symbols"][:180],
            "search_symbols": item["search_symbols"][:140],
        })

    edges = sorted(
        grouped.values(),
        key=lambda e: (
            e["source"], e["target"], e["type"], e["confidence"]
        ),
    )

    return {
        "schema_version": 2,
        "repository": index.get("repository", ""),
        "source_commit": index.get("source_commit", ""),
        "analysis": "conservative-lexical-not-ast",
        "nodes": nodes,
        "edges": edges,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    result = create_graph()
    output = json.dumps(
        result,
        ensure_ascii=False,
        indent=2,
    ) + "\n"

    if args.check:
        if not OUTPUT.exists():
            print("graph-index.json missing", file=sys.stderr)
            return 1
        if OUTPUT.read_text(encoding="utf-8") != output:
            print("graph-index.json is outdated", file=sys.stderr)
            return 1
        print("Graph index is current.")
        return 0

    OUTPUT.write_text(output, encoding="utf-8")
    print(
        f"Indexed {len(result['nodes'])} files and "
        f"{len(result['edges'])} evidence-backed relationships."
    )
    print("Source commit:", result["source_commit"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
