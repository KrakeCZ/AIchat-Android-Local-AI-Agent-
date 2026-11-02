"""Kotlin and Java AST and Structural Analyzer for AIchat Code Atlas.

Extracts packages, imports (exact, aliased, wildcard), class/interface/object
declarations, inheritance, top-level functions, methods, parameters, return types,
property types, annotations, class literals (::class), and external (JNI) funs.
Uses Tree-sitter when available with a high-fidelity token fallback.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

from .symbols import Symbol

# Tree-sitter optional import
_TREE_SITTER_AVAILABLE = False
_KOTLIN_LANGUAGE = None
_JAVA_LANGUAGE = None

try:
    import tree_sitter as ts
    import tree_sitter_kotlin
    import tree_sitter_java

    _KOTLIN_LANGUAGE = ts.Language(tree_sitter_kotlin.language())
    _JAVA_LANGUAGE = ts.Language(tree_sitter_java.language())
    _TREE_SITTER_AVAILABLE = True
except Exception:
    _TREE_SITTER_AVAILABLE = False


# Regex patterns for string/comment masking and lexical parsing
IGNORED_REGEX = re.compile(
    r'"""[\s\S]*?"""'
    r'|/\*[\s\S]*?\*/'
    r'|//[^\n]*'
    r'|"(?:\\.|[^"\\])*"'
    r"|'(?:\\.|[^'\\])*'"
)

PACKAGE_REGEX = re.compile(r"^\s*package\s+([\w.]+)", re.MULTILINE)
IMPORT_REGEX = re.compile(
    r"^\s*import\s+(?:static\s+)?([\w.*]+)(?:\s+as\s+([A-Za-z_]\w*))?",
    re.MULTILINE,
)

DECL_REGEX = re.compile(
    r"(?m)^\s*(?:(?:public|private|protected|internal|open|"
    r"abstract|final|data|sealed|enum|annotation|expect|actual|"
    r"value|static)\s+)*"
    r"(class|interface|object|struct|record)\s+([A-Za-z_]\w*)"
)

METHOD_REGEX = re.compile(
    r"(?m)^\s*(?:(?:public|private|protected|internal|"
    r"override|suspend|inline|open|final|abstract|"
    r"external|static)\s+)*fun\s+"
    r"(?:<[^>\n]+>\s*)?(?:([A-Za-z_]\w*)\.)?([A-Za-z_]\w*)\s*\("
)

JAVA_METHOD_REGEX = re.compile(
    r"(?m)^\s*(?:(?:public|private|protected|static|final|native|synchronized|abstract)\s+)+"
    r"(?:<[^>\n]+>\s*)?(?:[\w.<>\[\],\s]+?)\s+([A-Za-z_]\w*)\s*\([^)]*\)\s*(?:throws\s+[\w.,\s]+)?\s*[{;]"
)

PROPERTY_REGEX = re.compile(
    r"(?m)^\s*(?:(?:public|private|protected|internal|open|final|"
    r"override|lateinit|const)\s+)*(val|var)\s+([A-Za-z_]\w*)"
)

TYPEALIAS_REGEX = re.compile(
    r"(?m)^\s*(?:(?:public|private|protected|internal)\s+)*typealias\s+([A-Za-z_]\w*)"
)

# Reference patterns
QUALIFIED_REGEX = re.compile(
    r"\b(?:[a-z_][A-Za-z0-9_]*\.){2,}[A-Z][A-Za-z0-9_]*\b"
)
CLASS_LITERAL_REGEX = re.compile(
    r"\b([A-Z][A-Za-z0-9_]*)::class(?:\.java)?\b"
)
INHERITANCE_COLON_REGEX = re.compile(
    r"(?:class|interface|object)\s+[A-Za-z_]\w*(?:<[^>\n]+>)?\s*:\s*([^\{\n]+)"
)
RETURN_TYPE_REGEX = re.compile(
    r"\)\s*:\s*([A-Za-z_][\w.<>,?\s]*)"
)
VAR_TYPE_REGEX = re.compile(
    r"(?:val|var)\s+[A-Za-z_]\w*\s*:\s*([A-Za-z_][\w.<>,?\s]*)"
)
ANNOTATION_TYPE_REGEX = re.compile(
    r"@([A-Z][A-Za-z0-9_]*)"
)
GENERIC_TYPE_REGEX = re.compile(
    r"<([A-Za-z_][\w.<>,?\s]*)>"
)
CALL_OR_CONSTRUCTOR_REGEX = re.compile(
    r"\b([A-Z][A-Za-z0-9_]*)\s*(?=\(|\.)"
)


@dataclass
class ImportRef:
    name: str
    alias: Optional[str]
    is_wildcard: bool
    line: int
    text: str


@dataclass
class TypeRef:
    name: str
    line: int
    column: int
    context: str  # 'inheritance', 'return_type', 'param_type', 'annotation', 'class_literal', 'call', 'qualified'
    text: str


@dataclass
class KotlinJavaAnalysis:
    file_path: str
    language: str
    package: str
    imports: List[ImportRef] = field(default_factory=list)
    symbols: List[Symbol] = field(default_factory=list)
    type_references: List[TypeRef] = field(default_factory=list)
    search_symbols: List[str] = field(default_factory=list)
    has_parser_error: bool = False


def mask_source(text: str) -> str:
    """Replaces comments and strings with whitespace while keeping exact line/char positions."""
    return IGNORED_REGEX.sub(
        lambda match: re.sub(r"[^\n]", " ", match.group()),
        text,
    )


class KotlinJavaAnalyzer:
    def __init__(self):
        self.use_tree_sitter = _TREE_SITTER_AVAILABLE

    def parse_file(self, file_path: str, source_bytes: bytes) -> KotlinJavaAnalysis:
        text = source_bytes.decode("utf-8", errors="replace")
        lines = text.splitlines()
        lang = "java" if file_path.endswith(".java") else "kotlin"

        if self.use_tree_sitter and ((lang == "kotlin" and _KOTLIN_LANGUAGE) or (lang == "java" and _JAVA_LANGUAGE)):
            try:
                return self._parse_tree_sitter(file_path, lang, source_bytes, lines)
            except Exception as e:
                # Log parser error and fallback to lexical extractor
                res = self._parse_lexical(file_path, lang, text, lines)
                res.has_parser_error = True
                return res

        return self._parse_lexical(file_path, lang, text, lines)

    def _parse_tree_sitter(
        self, file_path: str, lang: str, source_bytes: bytes, lines: List[str]
    ) -> KotlinJavaAnalysis:
        ts_lang = _JAVA_LANGUAGE if lang == "java" else _KOTLIN_LANGUAGE
        parser = ts.Parser(ts_lang)
        tree = parser.parse(source_bytes)
        has_error = tree.root_node.has_error

        package = ""
        imports: List[ImportRef] = []
        symbols: List[Symbol] = []
        type_refs: List[TypeRef] = []
        search_symbols: Set[str] = set()

        def get_text(node) -> str:
            return source_bytes[node.start_byte:node.end_byte].decode("utf-8", errors="replace")

        def get_line_text(line_no: int) -> str:
            if 1 <= line_no <= len(lines):
                return lines[line_no - 1].strip()
            return ""

        # First pass: find package and imports
        for child in tree.root_node.children:
            if child.type in ("package_header", "package_declaration"):
                for sub in child.children:
                    if sub.type in ("qualified_identifier", "scoped_identifier", "identifier"):
                        package = get_text(sub)
                        break

            elif child.type in ("import", "import_header", "import_declaration"):
                line_no = child.start_point[0] + 1
                line_txt = get_line_text(line_no)
                raw_text = get_text(child).strip()
                # Parse import name and alias
                is_wildcard = ".*" in raw_text or raw_text.endswith("*")
                import_name = ""
                alias = None

                for sub in child.children:
                    if sub.type in ("qualified_identifier", "scoped_identifier"):
                        import_name = get_text(sub)
                    elif sub.type in ("import_alias", "type_alias"):
                        for a in sub.children:
                            if a.type in ("identifier", "type_identifier", "simple_identifier"):
                                alias = get_text(a)

                if not alias and " as " in raw_text:
                    alias_m = re.search(r"\bas\s+([A-Za-z_]\w*)", raw_text)
                    if alias_m:
                        alias = alias_m.group(1)

                if not import_name:
                    # Fallback extraction from text
                    m = IMPORT_REGEX.search(raw_text)
                    if m:
                        import_name = m.group(1)
                        if not alias:
                            alias = m.group(2)
                        is_wildcard = import_name.endswith(".*")

                if import_name:
                    imports.append(
                        ImportRef(
                            name=import_name,
                            alias=alias,
                            is_wildcard=is_wildcard,
                            line=line_no,
                            text=line_txt,
                        )
                    )

        # Traverse AST for declarations and type references
        stack = [(tree.root_node, [])]  # (node, class_stack: List[str])
        while stack:
            node, class_stack = stack.pop()
            current_class_stack = list(class_stack)

            ntype = node.type
            line_no = node.start_point[0] + 1
            col_no = node.start_point[1] + 1
            line_txt = get_line_text(line_no)

            # Class, interface, object declarations
            if ntype in (
                "class_declaration",
                "object_declaration",
                "companion_object",
                "interface_declaration",
                "enum_declaration",
                "record_declaration",
            ):
                kind = "class"
                if "interface" in ntype:
                    kind = "interface"
                elif "object" in ntype:
                    kind = "object"
                elif "enum" in ntype:
                    kind = "enum"

                # Check modifiers
                mod_text = ""
                for child in node.children:
                    if child.type == "modifiers":
                        mod_text = get_text(child)

                name = ""
                for child in node.children:
                    if child.type in ("identifier", "type_identifier"):
                        name = get_text(child)
                        break

                if not name and ntype == "companion_object":
                    name = "Companion"

                if name:
                    if current_class_stack:
                        outer = ".".join(current_class_stack)
                        fqn = f"{package}.{outer}.{name}" if package else f"{outer}.{name}"
                    else:
                        fqn = f"{package}.{name}" if package else name

                    symbols.append(
                        Symbol(
                            name=name,
                            kind=kind,
                            fqn=fqn,
                            file_path=file_path,
                            line=line_no,
                            column=col_no,
                            package=package,
                            is_top_level=len(current_class_stack) == 0,
                            is_external=False,
                        )
                    )
                    search_symbols.add(name)
                    current_class_stack.append(name)

                # Inheritance & interface implementation
                for child in node.children:
                    if child.type in ("delegation_specifiers", "super_interfaces", "extends_clause", "implements_clause"):
                        # Extract type identifiers
                        for sub in child.children:
                            self._extract_types_from_ast(sub, source_bytes, line_txt, "inheritance", type_refs)

            # Function / Method declaration
            elif ntype in ("function_declaration", "method_declaration", "constructor_declaration"):
                name = ""
                is_external = False
                for child in node.children:
                    if child.type == "modifiers":
                        mod_str = get_text(child)
                        if "external" in mod_str or "native" in mod_str:
                            is_external = True
                    elif child.type in ("identifier", "simple_identifier"):
                        if not name:
                            name = get_text(child)

                if name:
                    if current_class_stack:
                        outer = ".".join(current_class_stack)
                        fqn = f"{package}.{outer}.{name}" if package else f"{outer}.{name}"
                    else:
                        fqn = f"{package}.{name}" if package else name

                    symbols.append(
                        Symbol(
                            name=name,
                            kind="function",
                            fqn=fqn,
                            file_path=file_path,
                            line=line_no,
                            column=col_no,
                            package=package,
                            is_top_level=len(current_class_stack) == 0,
                            is_external=is_external,
                        )
                    )
                    search_symbols.add(name)

                # Parameters and return types
                for child in node.children:
                    if child.type in ("function_value_parameters", "formal_parameters", "user_type", "type_identifier"):
                        self._extract_types_from_ast(child, source_bytes, line_txt, "signature", type_refs)

            # Property / Field declaration
            elif ntype in ("property_declaration", "field_declaration"):
                for child in node.children:
                    if child.type in ("variable_declaration", "declarator"):
                        for sub in child.children:
                            if sub.type in ("identifier", "simple_identifier"):
                                search_symbols.add(get_text(sub))
                    if child.type in ("user_type", "type_identifier", "nullable_type"):
                        self._extract_types_from_ast(child, source_bytes, line_txt, "property_type", type_refs)

            # Typealias
            elif ntype in ("type_alias", "typealias"):
                name = ""
                for child in node.children:
                    if child.type in ("identifier", "type_identifier") and not name:
                        name = get_text(child)
                if name:
                    fqn = f"{package}.{name}" if package else name
                    symbols.append(
                        Symbol(
                            name=name,
                            kind="typealias",
                            fqn=fqn,
                            file_path=file_path,
                            line=line_no,
                            column=col_no,
                            package=package,
                            is_top_level=len(current_class_stack) == 0,
                        )
                    )
                    search_symbols.add(name)

            # Class literals: MapTypeConverter::class
            elif ntype == "navigation_expression":
                raw_nav = get_text(node)
                if "::class" in raw_nav:
                    m = CLASS_LITERAL_REGEX.search(raw_nav)
                    if m:
                        type_refs.append(
                            TypeRef(
                                name=m.group(1),
                                line=line_no,
                                column=col_no,
                                context="class_literal",
                                text=line_txt,
                            )
                        )

            # Annotation invocation
            elif ntype in ("annotation", "annotation_call"):
                for child in node.children:
                    if child.type in ("user_type", "type_identifier", "constructor_invocation"):
                        self._extract_types_from_ast(child, source_bytes, line_txt, "annotation", type_refs)

            # Push children
            for child in reversed(node.children):
                stack.append((child, current_class_stack))

        # Lexical extraction of qualified names and calls to complement AST
        clean = mask_source("\n".join(lines))
        for line_idx, line in enumerate(clean.splitlines(), 1):
            line_str = lines[line_idx - 1].strip()
            # Explicit qualified references: com.example.llama.data.AiModelDao
            for m in QUALIFIED_REGEX.finditer(line):
                type_refs.append(
                    TypeRef(
                        name=m.group(),
                        line=line_idx,
                        column=m.start() + 1,
                        context="qualified",
                        text=line_str,
                    )
                )
            # Calls / constructors: Foo(), Bar.baz()
            for m in CALL_OR_CONSTRUCTOR_REGEX.finditer(line):
                type_refs.append(
                    TypeRef(
                        name=m.group(1),
                        line=line_idx,
                        column=m.start() + 1,
                        context="call",
                        text=line_str,
                    )
                )

        return KotlinJavaAnalysis(
            file_path=file_path,
            language=lang,
            package=package,
            imports=imports,
            symbols=symbols,
            type_references=type_refs,
            search_symbols=sorted(search_symbols),
            has_parser_error=has_error,
        )

    def _extract_types_from_ast(
        self, node, source_bytes: bytes, line_txt: str, context: str, out: List[TypeRef]
    ) -> None:
        stack = [node]
        while stack:
            curr = stack.pop()
            if curr.type in ("type_identifier", "identifier", "simple_identifier"):
                name = source_bytes[curr.start_byte:curr.end_byte].decode("utf-8", errors="replace")
                # Filter primitive keywords and common language tokens
                if name and name[0].isupper() and name not in ("String", "Int", "Long", "Float", "Double", "Boolean", "Byte", "Short", "Char", "Any", "Unit", "Nothing", "List", "Map", "Set", "Array"):
                    line_no = curr.start_point[0] + 1
                    col_no = curr.start_point[1] + 1
                    out.append(
                        TypeRef(
                            name=name,
                            line=line_no,
                            column=col_no,
                            context=context,
                            text=line_txt,
                        )
                    )
            for c in reversed(curr.children):
                stack.append(c)

    def _parse_lexical(
        self, file_path: str, lang: str, text: str, lines: List[str]
    ) -> KotlinJavaAnalysis:
        clean = mask_source(text)
        clean_lines = clean.splitlines()

        package = ""
        imports: List[ImportRef] = []
        symbols: List[Symbol] = []
        type_refs: List[TypeRef] = []
        search_symbols: Set[str] = set()

        for idx, line in enumerate(clean_lines, 1):
            orig = lines[idx - 1].strip()
            pm = PACKAGE_REGEX.match(line)
            if pm:
                package = pm.group(1)

            im = IMPORT_REGEX.match(line)
            if im:
                imp_name = im.group(1)
                alias = im.group(2)
                is_wc = imp_name.endswith(".*")
                imports.append(
                    ImportRef(
                        name=imp_name,
                        alias=alias,
                        is_wildcard=is_wc,
                        line=idx,
                        text=orig,
                    )
                )

        # Declarations
        for m in DECL_REGEX.finditer(clean):
            kind = m.group(1)
            name = m.group(2)
            line_no = clean.count("\n", 0, m.start(2)) + 1
            col_no = m.start(2) - clean.rfind("\n", 0, m.start(2))
            fqn = f"{package}.{name}" if package else name
            symbols.append(
                Symbol(
                    name=name,
                    kind=kind,
                    fqn=fqn,
                    file_path=file_path,
                    line=line_no,
                    column=col_no,
                    package=package,
                    is_top_level=True,
                )
            )
            search_symbols.add(name)

        # Determine enclosing class for lexical fallback
        decl_positions = []
        for s in symbols:
            if s.kind in ("class", "interface", "object", "enum"):
                decl_positions.append((s.line, s.name))
        decl_positions.sort()

        # Functions
        m_regex = JAVA_METHOD_REGEX if lang == "java" else METHOD_REGEX
        for m in m_regex.finditer(clean):
            method_name = m.group(2) if lang == "kotlin" and m.lastindex == 2 else m.group(1)
            line_no = clean.count("\n", 0, m.start(1)) + 1
            col_no = m.start(1) - clean.rfind("\n", 0, m.start(1))
            is_ext = "external " in clean[max(0, m.start() - 40):m.start()] or "native " in clean[max(0, m.start() - 40):m.start()]

            enclosing_class = ""
            for cl_line, cl_name in reversed(decl_positions):
                if cl_line <= line_no:
                    enclosing_class = cl_name
                    break

            if enclosing_class:
                fqn = f"{package}.{enclosing_class}.{method_name}" if package else f"{enclosing_class}.{method_name}"
            else:
                fqn = f"{package}.{method_name}" if package else method_name

            symbols.append(
                Symbol(
                    name=method_name,
                    kind="function",
                    fqn=fqn,
                    file_path=file_path,
                    line=line_no,
                    column=col_no,
                    package=package,
                    is_top_level=not enclosing_class,
                    is_external=is_ext,
                )
            )
            search_symbols.add(method_name)

        # Properties
        for m in PROPERTY_REGEX.finditer(clean):
            prop_name = m.group(2)
            search_symbols.add(prop_name)

        # Typealiases
        for m in TYPEALIAS_REGEX.finditer(clean):
            ta_name = m.group(1)
            line_no = clean.count("\n", 0, m.start(1)) + 1
            fqn = f"{package}.{ta_name}" if package else ta_name
            symbols.append(
                Symbol(
                    name=ta_name,
                    kind="typealias",
                    fqn=fqn,
                    file_path=file_path,
                    line=line_no,
                    package=package,
                    is_top_level=True,
                )
            )
            search_symbols.add(ta_name)

        # Type references extraction from clean lines
        for idx, line in enumerate(clean_lines, 1):
            orig = lines[idx - 1].strip()
            # Qualified
            for m in QUALIFIED_REGEX.finditer(line):
                type_refs.append(
                    TypeRef(name=m.group(), line=idx, column=m.start() + 1, context="qualified", text=orig)
                )
            # Class literals
            for m in CLASS_LITERAL_REGEX.finditer(line):
                type_refs.append(
                    TypeRef(name=m.group(1), line=idx, column=m.start() + 1, context="class_literal", text=orig)
                )
            # Return types
            for m in RETURN_TYPE_REGEX.finditer(line):
                raw_t = m.group(1)
                for t in re.findall(r"\b([A-Z][A-Za-z0-9_]*)\b", raw_t):
                    type_refs.append(TypeRef(name=t, line=idx, column=m.start() + 1, context="return_type", text=orig))
            # Variable types
            for m in VAR_TYPE_REGEX.finditer(line):
                raw_t = m.group(1)
                for t in re.findall(r"\b([A-Z][A-Za-z0-9_]*)\b", raw_t):
                    type_refs.append(TypeRef(name=t, line=idx, column=m.start() + 1, context="property_type", text=orig))
            # Inheritance
            for m in INHERITANCE_COLON_REGEX.finditer(line):
                raw_t = m.group(1)
                for t in re.findall(r"\b([A-Z][A-Za-z0-9_]*)\b", raw_t):
                    type_refs.append(TypeRef(name=t, line=idx, column=m.start() + 1, context="inheritance", text=orig))
            # Annotations
            for m in ANNOTATION_TYPE_REGEX.finditer(line):
                type_refs.append(TypeRef(name=m.group(1), line=idx, column=m.start() + 1, context="annotation", text=orig))
            # Calls / constructors
            for m in CALL_OR_CONSTRUCTOR_REGEX.finditer(line):
                type_refs.append(TypeRef(name=m.group(1), line=idx, column=m.start() + 1, context="call", text=orig))

        return KotlinJavaAnalysis(
            file_path=file_path,
            language=lang,
            package=package,
            imports=imports,
            symbols=symbols,
            type_references=type_refs,
            search_symbols=sorted(search_symbols),
            has_parser_error=False,
        )
