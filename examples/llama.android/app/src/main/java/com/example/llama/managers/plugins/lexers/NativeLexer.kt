package com.example.llama.managers.plugins.lexers



/**
 * Token represents a chunk of text with a specific syntax type.
 */
data class SyntaxToken(
    val type: String, // e.g., "keyword", "string", "comment"
    val start: Int,
    val end: Int
)

/**
 * Interface for custom native lexers that bypass Regex.
 */
interface NativeLexer {
    fun tokenize(text: String): List<SyntaxToken>
}

/**
 * A highly optimized, Regex-free Lexer for C-family languages.
 * Supports: C, C++, C#, Java, Kotlin, Swift, Rust, Go, TypeScript, JavaScript, Arduino.
 * Meets Tokenization Standards for Keywords, Strings, Comments, and Numbers.
 */
class CppLexer : NativeLexer {

    private val keywords = setOf(
        // C / C++ Standard
        "auto", "break", "case", "char", "const", "continue", "default", "do", "double", "else", "enum",
        "extern", "float", "for", "goto", "if", "inline", "int", "long", "register", "return", "short",
        "signed", "sizeof", "static", "struct", "switch", "typedef", "union", "unsigned", "void", "volatile",
        "while", "asm", "bool", "catch", "class", "const_cast", "delete", "dynamic_cast", "explicit",
        "export", "false", "friend", "mutable", "namespace", "new", "operator", "private", "protected",
        "public", "reinterpret_cast", "static_cast", "template", "this", "throw", "true", "try", "typeid",
        "typename", "using", "virtual", "wchar_t", "alignas", "alignof", "char16_t", "char32_t", "constexpr",
        "decltype", "noexcept", "nullptr", "static_assert", "thread_local", "override", "final",

        // Java
        "abstract", "assert", "boolean", "byte", "extends", "finally", "implements", "import", "instanceof",
        "interface", "native", "package", "strictfp", "super", "synchronized", "throws", "transient",

        // Kotlin
        "fun", "val", "var", "is", "in", "object", "when", "typealias", "companion", "init",
        "constructor", "delegate", "dynamic", "field", "file", "get", "set", "param", "property", "receiver",
        "suspend", "reified", "crossinline", "noinline", "inner", "lateinit", "data", "sealed", "open",

        // C#
        "as", "base", "checked", "decimal", "delegate", "event", "fixed", "foreach", "implicit", "internal",
        "lock", "null", "out", "params", "readonly", "ref", "sbyte", "sealed", "stackalloc", "string",
        "typeof", "unchecked", "unsafe", "ushort", "async", "await",

        // Swift
        "associatedtype", "deinit", "extension", "fileprivate", "func", "inout", "let", "module", "nil",
        "precedencegroup", "protocol", "repeat", "rethrows", "self", "subscript", "typealias", "where",
        "defer", "fallthrough", "guard",

        // Rust
        "crate", "dyn", "fn", "impl", "let", "match", "mod", "move", "mut", "pub", "ref", "trait",
        "unsafe", "use", "where", "loop", "i8", "i16", "i32", "i64", "i128", "u8", "u16", "u32", "u64", "u128",
        "f32", "f64", "isize", "usize", "str", "Self",

        // Go
        "chan", "defer", "fallthrough", "func", "go", "interface", "map", "package", "range", "select",
        "type",

        // TypeScript / JavaScript / React
        "function", "var", "let", "const", "class", "extends", "export", "import", "default", "return",
        "if", "else", "switch", "case", "break", "continue", "do", "while", "for", "in", "of",
        "try", "catch", "finally", "throw", "new", "this", "super", "instanceof", "typeof", "void",
        "delete", "async", "await", "yield", "debugger", "interface", "type", "enum", "implements",
        "declare", "namespace", "module", "public", "private", "protected", "readonly", "as", "is",
        "from", "get", "set", "null", "undefined", "true", "false", "any", "number", "boolean", "string",
        "symbol", "never", "unknown", "keyof", "readonly", "unique", "require", "global", "module",
        "React", "component", "useState", "useEffect", "useContext", "useReducer", "useCallback", "useMemo",
        "useRef", "useImperativeHandle", "useLayoutEffect", "useDebugValue", "props", "state", "render",
        "console", "window", "document", "HTMLElement", "Element", "Node", "Event",

        // Go (Extended)
        "chan", "defer", "fallthrough", "func", "go", "interface", "map", "package", "range", "select",
        "type", "var", "const", "import", "return", "break", "continue", "goto", "if", "else", "switch",
        "case", "default", "for", "struct", "len", "cap", "new", "make", "append", "copy", "close",
        "delete", "complex", "real", "imag", "panic", "recover", "print", "println", "true", "false",
        "iota", "nil", "error", "string", "int", "int8", "int16", "int32", "int64", "uint", "uint8",
        "uint16", "uint32", "uint64", "uintptr", "byte", "rune", "float32", "float64", "complex64",
        "complex128", "bool"
    )

    override fun tokenize(text: String): List<SyntaxToken> {
        val tokens = mutableListOf<SyntaxToken>()
        val len = text.length
        var i = 0

        while (i < len) {
            // 2. Comments
            if (text[i] == '/' && i + 1 < len) {
                if (text[i + 1] == '/') {
                    // Single line comment
                    val start = i
                    i += 2
                    while (i < len && text[i] != '\n') i++
                    tokens.add(SyntaxToken("comment", start, i))
                    continue
                } else if (text[i + 1] == '*') {
                    // Block comment
                    val start = i
                    i += 2
                    while (i + 1 < len && (text[i] != '*' || text[i + 1] != '/')) {
                        i++
                    }
                    if (i + 1 < len) i += 2 // consume */
                    tokens.add(SyntaxToken("comment", start, i))
                    continue
                }
            }

            // 3. Macros (#include, etc) and Attributes (@Annotation, #[Attribute])
            if (text[i] == '#') {
                val start = i
                i++
                // Consume until whitespace or newline (Standard C Preprocessor)
                while (i < len && !text[i].isWhitespace()) i++
                tokens.add(SyntaxToken("macro", start, i))
                continue
            }
            if (text[i] == '@') {
                val start = i
                i++
                while (i < len && text[i].isLetterOrDigit()) i++
                tokens.add(SyntaxToken("class-name", start, i)) // Java/TS Annotation
                continue
            }

            // 4. Strings
            if (text[i] == '"' || text[i] == '\'') {
                val quote = text[i]
                val start = i
                i++
                while (i < len) {
                    if (text[i] == '\\' && i + 1 < len) {
                        i += 2 // Skip escaped char
                        continue
                    }
                    if (text[i] == quote) {
                        i++ // Consume closing quote
                        break
                    }
                    i++
                }
                tokens.add(SyntaxToken("string", start, i))
                continue
            }

            // 5. Numbers
            if (text[i].isDigit()) {
                val start = i
                i++
                // Hex
                if (text[i-1] == '0' && i < len && (text[i] == 'x' || text[i] == 'X')) {
                    i++
                    while (i < len && isHexDigit(text[i])) i++
                } else {
                    // Decimal / Float
                    while (i < len && (text[i].isDigit() || text[i] == '.' || text[i] == 'f' || text[i] == 'e')) i++
                }
                tokens.add(SyntaxToken("number", start, i))
                continue
            }

            // 6. Words (Keywords, Identifiers, Functions)
            if (text[i].isLetter() || text[i] == '_') {
                val start = i
                i++
                while (i < len && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                val word = text.substring(start, i)

                // Exact match for keywords
                if (keywords.contains(word)) {
                     tokens.add(SyntaxToken("keyword", start, i))
                } else if (i < len && text[i] == '(') {
                     tokens.add(SyntaxToken("function", start, i))
                } else {
                    // Start with UpperCase -> likely Class/Struct/Type
                    if (word.isNotEmpty() && word[0].isUpperCase()) {
                         tokens.add(SyntaxToken("class-name", start, i))
                    } else {
                         // Plain variable
                    }
                }
                continue
            }

            // 7. Operators / Punctuation
            if (isOperator(text[i])) {
                tokens.add(SyntaxToken("operator", i, i + 1))
                i++
                continue
            }
            if (isPunctuation(text[i])) {
                tokens.add(SyntaxToken("punctuation", i, i + 1))
                i++
                continue
            }

            // Fallback
            i++
        }

        return tokens
    }

    private fun isHexDigit(c: Char): Boolean {
        return c.isDigit() || (c in 'a'..'f') || (c in 'A'..'F')
    }

    private fun isOperator(c: Char): Boolean {
        return "+-*/%=&|<>!^~?".contains(c)
    }

    private fun isPunctuation(c: Char): Boolean {
        return "(){}[],.;:".contains(c)
    }
}

class JsonLexer : NativeLexer {
    override fun tokenize(text: String): List<SyntaxToken> {
         val tokens = mutableListOf<SyntaxToken>()
        val len = text.length
        var i = 0

        while (i < len) {
            val c = text[i]

            if (c.isWhitespace()) {
                i++
                continue
            }

            // Strings (Keys or Values)
            if (c == '"') {
                val start = i
                i++
                while (i < len) {
                    if (text[i] == '\\' && i + 1 < len) {
                        i += 2
                        continue
                    }
                    if (text[i] == '"') {
                        i++
                        break
                    }
                    i++
                }

                // Check if it's a key (followed by :)
                var j = i
                while (j < len && text[j].isWhitespace()) j++

                if (j < len && text[j] == ':') {
                     tokens.add(SyntaxToken("property", start, i)) // Key
                } else {
                     tokens.add(SyntaxToken("string", start, i)) // Value
                }
                continue
            }

            // Numbers
            if (c.isDigit() || c == '-') {
                val start = i
                i++
                 while (i < len && (text[i].isDigit() || text[i] == '.' || text[i] == 'e' || text[i] == 'E' || text[i] == '+' || text[i] == '-')) i++
                tokens.add(SyntaxToken("number", start, i))
                continue
            }

            // Booleans / Null
            if (c.isLetter()) {
                 val start = i
                 while (i < len && text[i].isLetter()) i++
                 val word = text.substring(start, i)
                 if (word == "true" || word == "false" || word == "null") {
                     tokens.add(SyntaxToken("boolean", start, i))
                 }
                 continue
            }

            // Punctuation
             if ("{}[]:,".contains(c)) {
                tokens.add(SyntaxToken("punctuation", i, i + 1))
                i++
                continue
            }

            i++
        }
        return tokens
    }
    }

/**
 * Native Lexer for Assembly (NASM/Intel/AT&T) and Mixed C/Low-Level.
 */
class AsmLexer : NativeLexer {
    private val instructions = setOf(
        "mov", "add", "sub", "mul", "div", "inc", "dec", "cmp", "jmp", "je", "jne", "jg", "jl",
        "push", "pop", "call", "ret", "nop", "xor", "or", "and", "shl", "shr", "lea", "int",
        "section", "global", "extern", "db", "dw", "dd", "dq", "resb", "resw", "resd", "resq",
        "test", "syscall", "enter", "leave", "cli", "sti", "in", "out", "hlt"
    )

    private val registers = setOf(
        "eax", "ebx", "ecx", "edx", "esi", "edi", "esp", "ebp",
        "rax", "rbx", "rcx", "rdx", "rsi", "rdi", "rsp", "rbp",
        "ax", "bx", "cx", "dx", "al", "ah", "bl", "bh", "cl", "ch", "dl", "dh",
        "r8", "r9", "r10", "r11", "r12", "r13", "r14", "r15",
        "eip", "rip", "cs", "ds", "es", "fs", "gs", "ss",
        "xmm0", "xmm1", "xmm2", "xmm3", "cr0", "cr3"
    )

    // C-Style Keywords and Standard Types for Mixed "Low-Level" blocks
    private val cKeywords = setOf(
        "void", "int", "char", "long", "short", "unsigned", "signed", "struct", "union", "enum",
        "return", "if", "else", "while", "for", "do", "switch", "case", "break", "continue",
        "const", "static", "volatile", "extern", "typedef", "sizeof", "NULL", "true", "false",
        // Std Types
        "size_t", "uint8_t", "int8_t", "uint16_t", "int16_t", "uint32_t", "int32_t", "uint64_t", "int64_t",
        "uintptr_t", "intptr_t", "bool", "wchar_t", "FILE",
        // Common Libc
        "printf", "malloc", "free", "memset", "memcpy", "main"
    )

    override fun tokenize(text: String): List<SyntaxToken> {
        val tokens = mutableListOf<SyntaxToken>()
        val len = text.length
        var i = 0

        while (i < len) {
            val c = text[i]

            // 1. Comments (; or // or /*)
            if (c == ';') {
                val start = i
                while (i < len && text[i] != '\n') i++
                tokens.add(SyntaxToken("comment", start, i))
                continue
            }
            if (c == '/') {
                if (i + 1 < len && text[i + 1] == '/') {
                    val start = i
                    i += 2
                    while (i < len && text[i] != '\n') i++
                    tokens.add(SyntaxToken("comment", start, i))
                    continue
                } else if (i + 1 < len && text[i + 1] == '*') {
                    val start = i
                    i += 2
                    while (i + 1 < len && (text[i] != '*' || text[i+1] != '/')) i++
                    if (i + 1 < len) i += 2
                    tokens.add(SyntaxToken("comment", start, i))
                    continue
                }
            }

            // 2. Preprocessor (#define, #include)
            if (c == '#') {
                val start = i
                i++
                while (i < len && !text[i].isWhitespace()) i++
                tokens.add(SyntaxToken("macro", start, i))
                continue
            }

            // 3. Hex/Binary Numbers
            if (c.isDigit()) {
                val start = i
                while (i < len && (text[i].isLetterOrDigit())) i++ // simplistic hex
                tokens.add(SyntaxToken("number", start, i))
                continue
            }

            // 4. Labels and Words
            if (c.isLetter() || c == '.' || c == '_') {
                val start = i
                i++
                while (i < len && (text[i].isLetterOrDigit() || text[i] == '.' || text[i] == '_' || text[i] == '@')) i++

                // Check if Label (ends with :)
                if (i < len && text[i] == ':') {
                    i++ // consume :
                    tokens.add(SyntaxToken("label", start, i))
                    continue
                }

                val word = text.substring(start, i).lowercase()
                if (instructions.contains(word)) {
                    tokens.add(SyntaxToken("instruction", start, i))
                } else if (registers.contains(word)) {
                    tokens.add(SyntaxToken("register", start, i))
                } else if (cKeywords.contains(word)) {
                     tokens.add(SyntaxToken("keyword", start, i))
                } else {
                    tokens.add(SyntaxToken("variable", start, i)) // Everything else is variable/identifier
                }
                continue
            }

            // 5. Strings
            if (c == '"' || c == '\'') {
                val start = i
                i++
                while (i < len && text[i] != c) i++
                if (i < len) i++ // consume quote
                tokens.add(SyntaxToken("string", start, i))
                continue
            }

            // 6. Punctuation
             if (",[]()+*-=<>!&|".contains(c)) {
                 tokens.add(SyntaxToken("punctuation", i, i + 1))
                 i++
                 continue
             }

            i++
        }
        return tokens
    }
}

/**
 * Native Lexer for SQL.
 */
/**
 * Native Lexer for SQL (ANSI Standard + T-SQL/PL-SQL Extras).
 * Supports: CTEs, Window Functions, Constraints, Data Types, and System Functions.
 */
class SqlLexer : NativeLexer {
    private val keywords = setOf(
        // DML
        "select", "from", "where", "insert", "into", "update", "delete", "merge", "call", "explain", "lock", "table",
        // DDL
        "create", "alter", "drop", "truncate", "rename", "index", "view", "procedure", "function", "trigger",
        "database", "schema", "sequence",
        // Joins & Sets
        "join", "inner", "outer", "left", "right", "full", "cross", "natural", "on", "using", "union",
        "intersect", "except", "minus", "distinct", "all",
        // Control Flow
        "case", "when", "then", "else", "end", "if", "begin", "declare", "exec", "execute", "return",
        "while", "loop", "leave", "continue", "commit", "rollback", "savepoint", "transaction",
        // Constraints
        "constraint", "primary", "foreign", "key", "references", "check", "unique", "default", "null", "not",
        "auto_increment", "identity",
        // Clauses
        "group", "by", "order", "having", "limit", "offset", "top", "fetch", "partition", "over", "window",
        "with", "recursive", "as", "values", "set"
    )

    private val operators = setOf(
        "and", "or", "xor", "not", "is", "in", "like", "ilike", "glob", "match", "regexp", "between",
        "exists", "any", "all", "some"
    )

    private val types = setOf(
        // Numeric
        "int", "integer", "tinyint", "smallint", "mediumint", "bigint", "decimal", "numeric", "float",
        "double", "real", "bit", "boolean", "bool", "serial", "money",
        // String
        "char", "varchar", "tinytext", "text", "mediumtext", "longtext", "nchar", "nvarchar", "binary",
        "varbinary", "blob", "tinyblob", "mediumblob", "longblob", "enum", "set",
        // Date/Time
        "date", "datetime", "timestamp", "time", "year", "interval",
        // Other
        "json", "jsonb", "xml", "uuid", "point", "polygon", "geometry", "box", "circle", "line",
        "lseg", "path", "macaddr", "cidr", "inet", "tsvector", "tsquery"
    )

    private val builtins = setOf(
        // Aggregates
        "count", "sum", "avg", "min", "max", "group_concat", "stddev", "variance",
        // String
        "concat", "substring", "substr", "length", "lower", "upper", "trim", "ltrim", "rtrim", "replace",
        "left", "right", "char_length", "position", "overlay",
        // Date
        "now", "current_date", "current_time", "current_timestamp", "date_add", "date_sub", "datediff",
        "extract", "to_char", "to_date", "coalesce", "nullif", "cast", "convert"
    )

    override fun tokenize(text: String): List<SyntaxToken> {
        val tokens = mutableListOf<SyntaxToken>()
        val len = text.length
        var i = 0

        while (i < len) {
            val c = text[i]

            // 1. Comments (-- or /*)
            if (c == '-') {
                if (i + 1 < len && text[i + 1] == '-') {
                    val start = i
                    i += 2
                    while (i < len && text[i] != '\n') i++
                    tokens.add(SyntaxToken("comment", start, i))
                    continue
                }
            }
            if (c == '/') {
                if (i + 1 < len && text[i + 1] == '*') {
                     val start = i
                     i += 2
                     while (i + 1 < len && (text[i] != '*' || text[i+1] != '/')) i++
                     if (i + 1 < len) i += 2
                     tokens.add(SyntaxToken("comment", start, i))
                     continue
                }
            }

            // 2. Strings ('...')
            if (c == '\'') {
                val start = i
                i++
                while (i < len) {
                    if (text[i] == '\'' && i + 1 < len && text[i+1] == '\'') {
                        i += 2 // SQL escape ''
                        continue
                    }
                    if (text[i] == '\'') {
                         i++
                         break
                    }
                    i++
                }
                tokens.add(SyntaxToken("string", start, i))
                continue
            }
            // Quoted Identifiers ("..." or `...` or [...])
            if (c == '"' || c == '`' || c == '[') {
                val endChar = if (c == '[') ']' else c
                val start = i
                i++
                while (i < len && text[i] != endChar) i++
                if (i < len) i++
                tokens.add(SyntaxToken("variable", start, i)) // Table/Column names
                continue
            }

             // 3. Parameters/Variables (@var, :var, $var)
            if (c == '@' || c == ':' || c == '$') {
                val start = i
                i++
                while (i < len && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                tokens.add(SyntaxToken("variable", start, i))
                continue
            }

            // 4. Words
            if (c.isLetter() || c == '_') {
                val start = i
                i++
                while (i < len && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                val word = text.substring(start, i).lowercase()

                if (keywords.contains(word)) {
                    tokens.add(SyntaxToken("keyword", start, i))
                } else if (operators.contains(word)) {
                    tokens.add(SyntaxToken("operator", start, i)) // AND, OR, LIKE
                } else if (types.contains(word)) {
                    tokens.add(SyntaxToken("class-name", start, i)) // Data Types
                } else if (builtins.contains(word)) {
                    tokens.add(SyntaxToken("function", start, i))
                } else if (i < len && text[i] == '(') {
                    tokens.add(SyntaxToken("function", start, i)) // Unknown function
                }
                // identifiers are plain
                continue
            }

            // 5. Numbers
            if (c.isDigit()) {
                 val start = i
                 while (i < len && (text[i].isDigit() || text[i] == '.')) i++
                 tokens.add(SyntaxToken("number", start, i))
                 continue
            }

            // 6. Operators/Punctuation
            if ("=<>!+-*/%&|^~".contains(c)) {
                 tokens.add(SyntaxToken("operator", i, i + 1))
                 i++
                 continue
             }
            if ("(),.;:".contains(c)) {
                 tokens.add(SyntaxToken("punctuation", i, i + 1))
                 i++
                 continue
             }

            i++
        }
        return tokens
    }
}

/**
 * Native Lexer for Python 3.x (Professional Standard).
 * Supports: Triple Strings, Decorators, F-Strings, Binary/Hex Numbers.
 */
class PythonLexer : NativeLexer {
    private val keywords = setOf(
        "False", "None", "True", "and", "as", "assert", "async", "await", "break", "class",
        "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global",
        "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return",
        "try", "while", "with", "yield", "match", "case", "type", "print", "exec", "super", "self"
    )

    private val specialMethods = setOf(
        "__init__", "__str__", "__repr__", "__eq__", "__ne__", "__lt__", "__gt__", "__le__", "__ge__",
        "__name__", "__main__"
    )

    override fun tokenize(text: String): List<SyntaxToken> {
        val tokens = mutableListOf<SyntaxToken>()
        val len = text.length
        var i = 0

        while (i < len) {
            val c = text[i]

            // 1. Comments (#)
            if (c == '#') {
                val start = i
                while (i < len && text[i] != '\n') i++
                tokens.add(SyntaxToken("comment", start, i))
                continue
            }

            // 2. Strings (Triple and Single)
            if (c == '\"' || c == '\'') {
                val start = i
                val quote = c

                // Check for Triple Quote
                if (i + 2 < len && text[i+1] == quote && text[i+2] == quote) {
                    // Triple Quote
                    i += 3
                    while (i < len) {
                        if (text[i] == quote && i + 2 < len && text[i+1] == quote && text[i+2] == quote) {
                            i += 3
                            break
                        }
                        if (text[i] == '\\' && i + 1 < len) {
                            i += 2
                            continue
                        }
                        i++
                    }
                } else {
                    // Single Quote
                    i++
                    while (i < len) {
                        if (text[i] == '\\' && i + 1 < len) {
                            i += 2
                            continue
                        }
                        if (text[i] == quote) {
                            i++
                            break
                        }
                        i++
                    }
                }
                tokens.add(SyntaxToken("string", start, i))
                continue
            }

            // 3. Decorators (@)
            if (c == '@') {
                val start = i
                i++
                while (i < len && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '.')) i++
                tokens.add(SyntaxToken("class-name", start, i)) // Decorators styled as classes/metadata
                continue
            }

            // 4. Numbers (Hex 0x, Binary 0b, Octal 0o, Float, Complex)
            if (c.isDigit() || (c == '.' && i+1 < len && text[i+1].isDigit())) {
                val start = i
                if (c == '0' && i + 1 < len) {
                     val next = text[i+1]
                     if (next == 'x' || next == 'X' || next == 'b' || next == 'B' || next == 'o' || next == 'O') {
                         i += 2
                         while (i < len && (text[i].isLetterOrDigit())) i++ // consume hex/bin/oct chars
                         tokens.add(SyntaxToken("number", start, i))
                         continue
                     }
                }
                
                // Normal number / float
                while (i < len && (text[i].isDigit() || text[i] == '.' || text[i] == 'e' || text[i] == 'E' || text[i] == 'j' || text[i] == 'J')) i++
                tokens.add(SyntaxToken("number", start, i))
                continue
            }

            // 5. Words
            if (c.isLetter() || c == '_') {
                val start = i
                i++
                while (i < len && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                val word = text.substring(start, i)

                if (keywords.contains(word)) {
                    tokens.add(SyntaxToken("keyword", start, i))
                } else if (specialMethods.contains(word)) {
                     tokens.add(SyntaxToken("function", start, i))
                } else if (i < len && text[i] == '(') {
                     tokens.add(SyntaxToken("function", start, i))
                } else if (word == "self" || word == "cls") {
                     tokens.add(SyntaxToken("variable", start, i)) // Highlight self/cls distinctively if possible (mapped to variable)
                } else if (word.isNotEmpty() && word[0].isUpperCase()) {
                    tokens.add(SyntaxToken("class-name", start, i))
                } else {
                    // plain identifier
                }
                continue
            }

            // 6. Operators / Punctuation
             if ("=+-*/%&|^~<>!".contains(c)) {
                 tokens.add(SyntaxToken("operator", i, i + 1))
                 i++
                 continue
             }
             if ("()[]{},:;.".contains(c)) {
                 tokens.add(SyntaxToken("punctuation", i, i + 1))
                 i++
                 continue
             }

            i++
        }
        return tokens
    }
}

/**
 * Native Lexer for JavaScript / TypeScript (Modern ES6+).
 * Supports: Template Literals, Regex Literals (Heuristic), Arrow Functions, TypeScript Types.
 */
class JavascriptLexer : NativeLexer {
    private val keywords = setOf(
        "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete",
        "do", "else", "export", "extends", "false", "finally", "for", "function", "if", "import",
        "in", "instanceof", "new", "null", "return", "super", "switch", "this", "throw", "true",
        "try", "typeof", "var", "void", "while", "with", "yield",
        // ES6+ / TS
        "let", "static", "enum", "await", "async", "implements", "package", "protected",
        "interface", "private", "public", "as", "any", "number", "string", "boolean", "symbol",
        "unknown", "never", "type", "declare", "namespace", "module", "require", "from", "of",
        "constructor", "get", "set", "readonly", "keyof", "is", "infer", "unique", "override", "global"
    )

    private val builtins = setOf(
        "console", "window", "document", "global", "process", "module", "exports", "Array",
        "String", "Object", "Number", "Boolean", "Date", "Math", "JSON", "Promise", "Map", "Set",
        "WeakMap", "WeakSet", "Proxy", "Reflect", "Inttl", "Buffer", "Error", "RegExp", "Symbol"
    )

    override fun tokenize(text: String): List<SyntaxToken> {
        val tokens = mutableListOf<SyntaxToken>()
        val len = text.length
        var i = 0
        var lastTokenType = "punctuation" 
        var lastTokenText = ";" // Assume safe start

        while (i < len) {
            val c = text[i]

            // 1. Whitespace
            if (c.isWhitespace()) {
                i++
                continue
            }

            // 2. Comments
            if (c == '/') {
                if (i + 1 < len) {
                    val next = text[i+1]
                    if (next == '/') {
                        val start = i
                        i += 2
                        while (i < len && text[i] != '\n') i++
                        tokens.add(SyntaxToken("comment", start, i))
                        continue
                    } else if (next == '*') {
                        val start = i
                        i += 2
                        while (i + 1 < len && (text[i] != '*' || text[i+1] != '/')) i++
                        if (i + 1 < len) i += 2
                        tokens.add(SyntaxToken("comment", start, i))
                        continue
                    }
                }
            
                // Regex vs Division
                if (isRegexPossible(lastTokenType, lastTokenText)) {
                     val start = i
                     var j = i + 1
                     var escaped = false
                     var isRegex = false
                     
                     while (j < len) {
                         if (text[j] == '\\') {
                             escaped = !escaped
                             j++
                             continue
                         }
                         if (text[j] == '/' && !escaped) {
                             isRegex = true
                             j++
                             while (j < len && text[j].isLetter()) j++ // Flags
                             break
                         }
                         if (text[j] == '\n') {
                             break 
                         }
                         escaped = false
                         j++
                     }
                     
                     if (isRegex) {
                         tokens.add(SyntaxToken("regex", start, j))
                         lastTokenType = "regex"
                         lastTokenText = text.substring(start, j)
                         i = j
                         continue
                     }
                }
                
                // Division
                tokens.add(SyntaxToken("operator", i, i + 1))
                lastTokenType = "operator"
                lastTokenText = "/"
                i++
                continue
            }

            // 3. Template Literals (Backticks)
            if (c == '`') {
                val start = i
                i++
                while (i < len) {
                     if (text[i] == '\\' && i + 1 < len) {
                         i += 2
                         continue
                     }
                     if (text[i] == '`') {
                         i++
                         break
                     }
                     i++
                }
                tokens.add(SyntaxToken("string", start, i))
                lastTokenType = "string"
                lastTokenText = "`...`"
                continue
            }

            // 4. Strings
            if (c == '\'' || c == '"') {
                val start = i
                val quote = c
                i++
                while (i < len) {
                    if (text[i] == '\\' && i + 1 < len) {
                        i += 2
                        continue
                    }
                    if (text[i] == quote) {
                        i++
                        break
                    }
                    i++
                }
                tokens.add(SyntaxToken("string", start, i))
                lastTokenType = "string"
                lastTokenText = "str"
                continue
            }

            // 5. Numbers
            if (c.isDigit() || (c == '.' && i+1 < len && text[i+1].isDigit())) {
                val start = i
                while (i < len && (text[i].isLetterOrDigit() || text[i] == '.' || text[i] == '_')) i++ // Support 1_000
                tokens.add(SyntaxToken("number", start, i))
                lastTokenType = "number"
                lastTokenText = "num"
                continue
            }

            // 6. Keywords / Identifiers
            if (c.isLetter() || c == '_' || c == '$' || c == '@') {
                val start = i
                i++
                while (i < len && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '$')) i++ // Stopped allowing dots
                val word = text.substring(start, i)
                
                if (word.startsWith("@")) {
                    tokens.add(SyntaxToken("function", start, i)) // Decorator -> Function color
                    lastTokenType = "identifier"
                } else if (keywords.contains(word)) {
                    tokens.add(SyntaxToken("keyword", start, i))
                    lastTokenType = "keyword"
                } else if (builtins.contains(word)) {
                     tokens.add(SyntaxToken("class-name", start, i)) 
                     lastTokenType = "identifier"
                } else if (word == "true" || word == "false") {
                     tokens.add(SyntaxToken("boolean", start, i))
                     lastTokenType = "boolean"
                } else {
                     if (i < len && text[i] == '(') {
                         tokens.add(SyntaxToken("function", start, i))
                     } else if (i < len && text[i] == ':') {
                         tokens.add(SyntaxToken("property", start, i)) 
                     } else if (lastTokenType == "punctuation" && lastTokenText == ".") {
                         tokens.add(SyntaxToken("property", start, i)) // obj.prop -> prop is property
                     } else if (word.isNotEmpty() && word[0].isUpperCase()) {
                         tokens.add(SyntaxToken("class-name", start, i))
                     } else {
                         tokens.add(SyntaxToken("variable", start, i)) 
                     }
                     lastTokenType = "identifier"
                }
                lastTokenText = word
                continue
            }

            // 7. Operators / Punctuation
             if (",;(){}[]:.".contains(c)) {
                 tokens.add(SyntaxToken("punctuation", i, i + 1))
                 lastTokenType = "punctuation"
                 lastTokenText = c.toString()
                 i++
                 continue
             }
             if ("=+-*%&|^~<>!?".contains(c)) {
                 tokens.add(SyntaxToken("operator", i, i + 1))
                 lastTokenType = "operator"
                 lastTokenText = c.toString()
                 i++
                 continue
             }

            i++
        }
        return tokens
    }

    private fun isRegexPossible(lastType: String, lastText: String): Boolean {
        if (lastType == "identifier" || lastType == "number" || lastType == "string" || lastType == "boolean") return false // Division
        if (lastType == "punctuation") {
            if (lastText == ")" || lastText == "]") return false // (a+b)/c or arr[0]/c -> Division
            return true // { } ; , ( [ etc. -> Regex
        }
        return true // Keyword, Operator, Regex -> Regex
    }
}

/**
 * Native Lexer for YAML (Professional Standard).
 * Supports: Keys, Scalars, Comments, List Indicators, Document Separators.
 */
class YamlLexer : NativeLexer {
    override fun tokenize(text: String): List<SyntaxToken> {
        val tokens = mutableListOf<SyntaxToken>()
        val len = text.length
        var i = 0

        while (i < len) {
            val c = text[i]

            // 1. Comments (#)
            if (c == '#') {
                val start = i
                while (i < len && text[i] != '\n') i++
                tokens.add(SyntaxToken("comment", start, i))
                continue
            }

            // 2. Document Separator (---)
            if (c == '-' && i + 2 < len && text[i+1] == '-' && text[i+2] == '-') {
                tokens.add(SyntaxToken("punctuation", i, i + 3))
                i += 3
                continue
            }

            // 3. List Indicator (-)
            if (c == '-' && (i + 1 == len || text[i+1].isWhitespace())) {
                tokens.add(SyntaxToken("punctuation", i, i + 1))
                i++
                continue
            }

            // 4. Strings ("..." or '...')
            if (c == '"' || c == '\'') {
                val start = i
                val quote = c
                i++
                while (i < len) {
                    if (text[i] == '\\' && i + 1 < len) {
                        i += 2
                        continue
                    }
                    if (text[i] == quote) {
                        i++
                        break
                    }
                    i++
                }
                tokens.add(SyntaxToken("string", start, i))
                continue
            }

            // 5. Keys (word followed by :)
            if (c.isLetter() || c == '_' || c.isDigit()) {
                val start = i
                // Read until we hit whitespace or :
                while (i < len && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '-' || text[i] == '.')) i++
                val end = i
                
                // Check if followed by colon
                var isKey = false
                var j = i
                while (j < len && text[j] == ' ') j++ // Skip spaces
                if (j < len && text[j] == ':') {
                    isKey = true
                }

                if (isKey) {
                    tokens.add(SyntaxToken("property", start, end)) // YAML Key -> Property
                } else {
                    // Scalar / Value
                     // Try to match keywords
                    val word = text.substring(start, end)
                    if (word == "true" || word == "false" || word == "yes" || word == "no" || word == "null") {
                        tokens.add(SyntaxToken("boolean", start, end))
                    } else if (word.all { it.isDigit() || it == '.' }) {
                         tokens.add(SyntaxToken("number", start, end))
                    } else {
                         tokens.add(SyntaxToken("string", start, end)) // Unquoted string scalar
                    }
                }
                continue
            }

            // 6. Punctuation
            if (":,[]{}".contains(c)) {
                tokens.add(SyntaxToken("punctuation", i, i + 1))
                i++
                continue
            }

             // 7. Decorators / Tags (!)
            if (c == '!') {
                 val start = i
                 while (i < len && !text[i].isWhitespace()) i++
                 tokens.add(SyntaxToken("important", start, i)) // Tags
                 continue
            }

             // 8. Properties / Anchors (&, *)
             if (c == '&' || c == '*') {
                 val start = i
                 i++
                 while (i < len && !text[i].isWhitespace()) i++
                 tokens.add(SyntaxToken("variable", start, i)) // Anchors
                 continue
             }

            // Whitespace / Fallback
            i++
        }
        return tokens
    }
}
