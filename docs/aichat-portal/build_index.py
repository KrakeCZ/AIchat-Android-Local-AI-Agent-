#!/usr/bin/env python3
"""
AIchat source index generator.

Uses the unified File Inventory from analyzer.inventory to ensure 1:1 parity
with the Code Atlas graph index.

Usage (from repository root):
    python docs/aichat-portal/build_index.py
    python docs/aichat-portal/build_index.py --check

No application source files are modified.
"""

import argparse
import json
import re
import sys
from pathlib import Path

PORTAL = Path(__file__).resolve().parent
ROOT = PORTAL.parent.parent
OUTPUT = PORTAL / "source-index.json"

if str(PORTAL) not in sys.path:
    sys.path.insert(0, str(PORTAL))

from analyzer.inventory import Inventory

PREFIX = "examples/llama.android/"

CLASS_RX = re.compile(
    r"\b(?:data\s+class|sealed\s+class|enum\s+class|"
    r"class|interface|object|struct)\s+([A-Za-z_]\w*)"
)
FUNCTION_RX = re.compile(
    r"\bfun\s+(?:<[^>]+>\s*)?([A-Za-z_]\w*)\s*\("
)
VARIABLE_RX = re.compile(
    r"\b(?:val|var)\s+([A-Za-z_]\w*)"
)
IMPORT_RX = re.compile(
    r"^\s*(?:import\s+([\w.*]+)|#include\s*[<\"]([^>\"]+))",
    re.MULTILINE
)


def unique_matches(regex, text, limit=80):
    results = []
    seen = set()

    for match in regex.finditer(text):
        value = match.group(1)
        if value and value not in seen:
            results.append(value)
            seen.add(value)

        if len(results) >= limit:
            break

    return results


def extract_imports(text):
    results = []

    for match in IMPORT_RX.finditer(text):
        value = match.group(1) or match.group(2)
        if value and value not in results:
            results.append(value)

        if len(results) >= 60:
            break

    return results


def read_source(path):
    file = ROOT / path

    if not file.is_file():
        return None

    size = file.stat().st_size

    # Huge source files remain indexed, but skip expensive symbol parsing.
    if size > 2_000_000:
        return {
            "path": path,
            "bytes": size,
            "symbols": [],
            "imports": [],
            "summary": "Large source file — inspect on GitHub.",
        }

    source = file.read_text(encoding="utf-8", errors="replace")

    symbols = []
    seen = set()

    for pattern in (CLASS_RX, FUNCTION_RX, VARIABLE_RX):
        for name in unique_matches(pattern, source):
            if name not in seen:
                symbols.append(name)
                seen.add(name)

    return {
        "path": path,
        "bytes": size,
        "lines": source.count("\n") + (not source.endswith("\n")),
        "symbols": symbols[:120],
        "imports": extract_imports(source),
    }


def generate():
    inv = Inventory(ROOT, prefix=PREFIX)
    entries = inv.scan()
    files = []

    for entry in entries:
        item = read_source(entry.path)
        if item:
            files.append(item)

    files.sort(key=lambda x: x["path"])
    commit = inv.source_commit

    return json.dumps(
        {
            "schema_version": 1,
            "repository": "KrakeCZ/AIchat-Android-Local-AI-Agent-",
            "source_commit": commit,
            "files": files,
        },
        ensure_ascii=False,
        indent=2,
    ) + "\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--check",
        action="store_true",
        help="Check whether source-index.json is up to date.",
    )
    args = parser.parse_args()

    data = generate()

    if args.check:
        if not OUTPUT.exists() or OUTPUT.read_text(
            encoding="utf-8"
        ) != data:
            print("Source index is out of date.", file=sys.stderr)
            return 1

        print("Source index is up to date.")
        return 0

    OUTPUT.write_text(data, encoding="utf-8")
    content = json.loads(data)
    print(f"Indexed {len(content['files'])} source files.")
    print(f"Output: {OUTPUT}")
    print(f"Commit: {content['source_commit']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
