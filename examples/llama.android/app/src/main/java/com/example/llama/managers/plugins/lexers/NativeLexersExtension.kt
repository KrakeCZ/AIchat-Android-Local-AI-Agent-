package com.example.llama.managers.plugins.lexers

/**
 * Native Lexer for Bash / Shell.
 * Supports: Comments, Strings, Variables, Keywords, Pipes.
 */
class BashLexer : NativeLexer {
    private val keywords = setOf(
        "if", "then", "else", "elif", "fi", "case", "esac", "for", "select", "while", "until",
        "do", "done", "in", "function", "time", "coproc", "return", "exit", "export", "declare",
        "local", "readonly", "unset", "set", "shift", "trap", "kill", "echo", "printf", "read",
        "cd", "pwd", "ls", "grep", "cat", "sed", "awk", "sudo", "git", "make", "docker"
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

            // 2. Strings ("..." or '...')
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

            // 3. Variables ($var or ${var})
            if (c == '$') {
                val start = i
                i++
                if (i < len && text[i] == '{') {
                    // ${var}
                    while (i < len && text[i] != '}') i++
                    if (i < len) i++
                } else {
                    // $var
                    while (i < len && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                }
                tokens.add(SyntaxToken("variable", start, i))
                continue
            }

            // 4. Keywords
            if (c.isLetter() || c == '_') {
                val start = i
                while (i < len && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '-')) i++
                val word = text.substring(start, i)
                
                if (keywords.contains(word)) {
                    tokens.add(SyntaxToken("keyword", start, i))
                }
                continue
            }

            // 5. Flags / Options (-f, --force)
            if (c == '-') {
                val start = i
                i++
                if (i < len && (text[i].isLetterOrDigit() || text[i] == '-')) {
                     while (i < len && (text[i].isLetterOrDigit() || text[i] == '-' || text[i] == '_')) i++
                     tokens.add(SyntaxToken("operator", start, i)) // Highlight flags as operators or subtle properties
                     continue
                }
            }

            // 6. Operators / Punctuation
            if ("|&;<>()[]{}=!".contains(c)) {
                tokens.add(SyntaxToken("operator", i, i + 1))
                i++
                continue
            }

            i++
        }
        return tokens
    }
}

/**
 * Native Lexer for PowerShell.
 * Supports: Comments, variables, strings, attributes, parameters.
 */
class PowerShellLexer : NativeLexer {
    private val keywords = setOf(
        "if", "else", "elseif", "switch", "try", "catch", "finally", "trap", "data", "do", "while",
        "until", "for", "foreach", "in", "break", "continue", "return", "exit", "process", "begin",
        "end", "function", "filter", "workflow", "configuration", "param", "dynamicparam", "class",
        "define", "from", "using", "var", "throw"
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
            
            // 2. Variables ($var)
            if (c == '$') {
                 val start = i
                 i++
                 while (i < len && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == ':')) i++
                 tokens.add(SyntaxToken("variable", start, i))
                 continue
            }

            // 3. Strings ("..." or '...')
            if (c == '"' || c == '\'') {
                val start = i
                val quote = c
                i++
                while (i < len) {
                    if (text[i] == '`' && i + 1 < len) { // Backtick escape
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

            // 4. Attributes / Parameters ( [Attr] or -Param )
            if (c == '[') {
                val start = i
                i++
                while (i < len && text[i] != ']') i++
                if (i < len) i++
                tokens.add(SyntaxToken("class-name", start, i)) // [string], [cmdletbinding]
                continue
            }
            if (c == '-') {
                val start = i
                i++
                if (i < len && text[i].isLetter()) {
                    while (i < len && (text[i].isLetterOrDigit() || text[i] == '-')) i++
                    tokens.add(SyntaxToken("property", start, i)) // -Path, -Force (Parameters)
                    continue
                }
            }

            // 5. Keywords / Cmdlets
            if (c.isLetter() || c == '_') {
                val start = i
                while (i < len && (text[i].isLetterOrDigit() || text[i] == '_' || text[i] == '-')) i++
                val word = text.substring(start, i)
                
                if (keywords.contains(word.lowercase())) {
                    tokens.add(SyntaxToken("keyword", start, i))
                } else if (word.contains("-")) {
                    tokens.add(SyntaxToken("function", start, i)) // Verb-Noun -> Cmdlet
                }
                continue
            }

            // 6. Operators / Punctuation
             if ("|&;<>()[]{}=!,.".contains(c)) {
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
 * Native Lexer for Markup (HTML / XML / SVG).
 * Supports: Tags, Attributes, Strings, Comments, Entities.
 */
class MarkupLexer : NativeLexer {
    override fun tokenize(text: String): List<SyntaxToken> {
        val tokens = mutableListOf<SyntaxToken>()
        val len = text.length
        var i = 0

        while (i < len) {
            val c = text[i]

            // 1. Comments (<!-- ... -->)
            if (c == '<' && i + 3 < len && text[i+1] == '!' && text[i+2] == '-' && text[i+3] == '-') {
                val start = i
                i += 4
                while (i + 2 < len) {
                    if (text[i] == '-' && text[i+1] == '-' && text[i+2] == '>') {
                        i += 3
                        break
                    }
                    i++
                }
                tokens.add(SyntaxToken("comment", start, i))
                continue
            }

            // 2. Tags (<tag, </tag, <tag/>)
            if (c == '<') {
                val start = i
                i++
                if (i < len && text[i] == '/') i++ // close tag
                
                // Tag Name
                while (i < len && (text[i].isLetterOrDigit() || text[i] == '-' || text[i] == ':' || text[i] == '.' || text[i] == '_')) i++ 
                
                tokens.add(SyntaxToken("class-name", start, i)) 

                // Attributes loop
                while (i < len && text[i] != '>' && text[i] != '/') {
                     if (text[i].isWhitespace()) {
                         i++
                         continue
                     }
                     // Attribute Name
                     if (text[i].isLetter() || text[i] == '-' || text[i] == ':' || text[i] == '@' || text[i] == '*' || text[i] == '#') { // Angular/Vue chars
                         val attrStart = i
                         while (i < len && (text[i].isLetterOrDigit() || text[i] == '-' || text[i] == ':' || text[i] == '.' || text[i] == '_' || text[i] == '@' || text[i] == '*' || text[i] == '#' || text[i] == '[' || text[i] == ']' || text[i] == '(' || text[i] == ')')) i++
                         tokens.add(SyntaxToken("property", attrStart, i)) 
                         continue
                     }
                     
                     // Attribute Value String
                     if (text[i] == '"' || text[i] == '\'') {
                         val startQ = i
                         val quote = text[i]
                         i++
                         while (i < len) {
                             if (text[i] == quote) {
                                 i++
                                 break
                             }
                             i++
                         }
                         tokens.add(SyntaxToken("string", startQ, i))
                         continue
                     }
                     
                     // Equals, etc.
                     if (text[i] == '=') {
                         tokens.add(SyntaxToken("operator", i, i + 1))
                         i++
                         continue
                     }
                     
                     i++
                }

                // Tag End
                if (i < len && text[i] == '/') {
                    tokens.add(SyntaxToken("punctuation", i, i + 1))
                    i++
                }
                if (i < len && text[i] == '>') {
                    tokens.add(SyntaxToken("punctuation", i, i + 1))
                    i++
                }
                continue
            }

            // 3. Entities (&amp;)
            if (c == '&') {
                val start = i
                while (i < len && text[i] != ';') {
                    if (text[i].isWhitespace()) break // invalid entity
                    i++
                }
                if (i < len && text[i] == ';') {
                    i++
                    tokens.add(SyntaxToken("number", start, i)) // Entity -> Number color (often distinct)
                    continue
                }
            }

            i++
        }
        return tokens
    }
}

/**
 * Native Lexer for CSS / SCSS.
 * Supports: Selectors, Properties, Values, At-Rules, Strings, Comments.
 */
class CssLexer : NativeLexer {
    override fun tokenize(text: String): List<SyntaxToken> {
        val tokens = mutableListOf<SyntaxToken>()
        val len = text.length
        var i = 0
        var insideBlock = false

        while (i < len) {
            val c = text[i]

            // 1. Comments (/* ... */)
            if (c == '/' && i + 1 < len && text[i+1] == '*') {
                val start = i
                i += 2
                while (i + 1 < len) {
                    if (text[i] == '*' && text[i+1] == '/') {
                        i += 2
                        break
                    }
                    i++
                }
                tokens.add(SyntaxToken("comment", start, i))
                continue
            }

            // 2. Strings
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

            // 3. At-Rules (@media)
            if (c == '@') {
                val start = i
                i++
                while (i < len && (text[i].isLetterOrDigit() || text[i] == '-')) i++
                tokens.add(SyntaxToken("keyword", start, i))
                continue
            }

            // 4. Selectors and Properties
            if (c.isLetter() || c == '-' || c == '.' || c == '#') {
                val start = i
                while (i < len && (text[i].isLetterOrDigit() || text[i] == '-' || text[i] == '_' || text[i] == '.' || text[i] == '#')) i++
                val word = text.substring(start, i)
                
                if (insideBlock) {
                    // Inside { ... } -> Property or Value?
                    var j = i
                    while (j < len && text[j].isWhitespace()) j++
                    if (j < len && text[j] == ':') {
                        tokens.add(SyntaxToken("property", start, i))
                    } else {
                         if (word.all { it.isDigit() || it == '.' }) {
                              tokens.add(SyntaxToken("number", start, i))
                         } else {
                              tokens.add(SyntaxToken("string", start, i)) // Values often look good as strings/identifiers
                         }
                    }
                } else {
                    // Outside { ... } -> Selector
                    if (word.startsWith(".") || word.startsWith("#")) {
                        tokens.add(SyntaxToken("function", start, i)) // ID/Class selector
                    } else {
                        tokens.add(SyntaxToken("class-name", start, i)) // Element tag
                    }
                }
                continue
            }

            // 5. Punctuation
            if (c == '{') {
                insideBlock = true
                tokens.add(SyntaxToken("punctuation", i, i + 1))
                i++
                continue
            }
            if (c == '}') {
                insideBlock = false
                tokens.add(SyntaxToken("punctuation", i, i + 1))
                i++
                continue
            }
            if (":;,".contains(c)) {
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
 * Native Lexer for Go.
 */
class GoLexer : NativeLexer {
    private val keywords = setOf(
        "break", "case", "chan", "const", "continue", "default", "defer", "else", "fallthrough", 
        "for", "func", "go", "goto", "if", "import", "interface", "map", "package", "range", 
        "return", "select", "struct", "switch", "type", "var"
    )
    private val builtins = setOf(
        "append", "cap", "close", "complex", "copy", "delete", "imag", "len", "make", "new", 
        "panic", "print", "println", "real", "recover", 
        "bool", "int", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16", "uint32", 
        "uint64", "uintptr", "float32", "float64", "complex64", "complex128", "string", "byte", "rune", "error"
    )

    override fun tokenize(text: String): List<SyntaxToken> {
        val tokens = mutableListOf<SyntaxToken>()
        val len = text.length
        var i = 0

        while (i < len) {
            val c = text[i]
            if (c.isWhitespace()) { i++; continue }

            // Comments
             if (c == '/' && i + 1 < len) {
                if (text[i+1] == '/') {
                    val start = i; while (i < len && text[i] != '\n') i++; tokens.add(SyntaxToken("comment", start, i)); continue
                }
                if (text[i+1] == '*') {
                    val start = i; i+=2; while (i+1 < len && (text[i]!='*' || text[i+1]!='/')) i++; if(i+1<len) i+=2; tokens.add(SyntaxToken("comment", start, i)); continue
                }
            }

            // Strings
            if (c == '"' || c == '`') { // Go uses backticks for raw strings
                 val start = i
                 val quote = c
                 i++
                 while (i < len) {
                     if (text[i] == '\\' && i+1 < len && quote == '"') { i+=2; continue } // escape only in double quote
                     if (text[i] == quote) { i++; break }
                     i++
                 }
                 tokens.add(SyntaxToken("string", start, i))
                 continue
            }
            if (c == '\'') { // Rune
                 val start = i; i++
                 while(i < len) { if(text[i]=='\\') {i+=2; continue}; if(text[i]=='\'') {i++; break}; i++ }
                 tokens.add(SyntaxToken("string", start, i))
                 continue
            }

            // Identifiers / Keywords
            if (c.isLetter() || c == '_') {
                val start = i
                while (i < len && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                val word = text.substring(start, i)
                if (keywords.contains(word)) tokens.add(SyntaxToken("keyword", start, i))
                else if (builtins.contains(word)) tokens.add(SyntaxToken("class-name", start, i))
                else if (word == "true" || word == "false" || word == "nil") tokens.add(SyntaxToken("boolean", start, i))
                else if (i < len && text[i] == '(') tokens.add(SyntaxToken("function", start, i))
                else tokens.add(SyntaxToken("variable", start, i))
                continue
            }

            // Operator / Punctuation
            if (":=+-*/%&|^<>!.,;(){}[]".contains(c)) {
                 tokens.add(SyntaxToken(if(",;(){}[]".contains(c)) "punctuation" else "operator", i, i + 1))
                 i++
                 continue
            }
            i++
        }
        return tokens
    }
}

/**
 * Native Lexer for Rust.
 */
class RustLexer : NativeLexer {
    private val keywords = setOf(
        "abstract", "alignof", "as", "become", "box", "break", "const", "continue", "crate", "do", 
        "else", "enum", "extern", "false", "final", "fn", "for", "if", "impl", "in", "let", "loop", 
        "macro", "match", "mod", "move", "mut", "offsetof", "override", "priv", "proc", "pub", 
        "pure", "ref", "return", "Self", "self", "sizeof", "static", "struct", "super", "trait", 
        "true", "type", "typeof", "unsafe", "unsized", "use", "virtual", "where", "while", "yield"
    )

    override fun tokenize(text: String): List<SyntaxToken> {
        val tokens = mutableListOf<SyntaxToken>()
        val len = text.length
        var i = 0

        while (i < len) {
            val c = text[i]
            if (c.isWhitespace()) { i++; continue }

            // Comments
            if (c == '/' && i + 1 < len) {
                 if (text[i+1] == '/') {
                     val start = i; while (i < len && text[i] != '\n') i++; tokens.add(SyntaxToken("comment", start, i)); continue
                 }
            }

            // Lifetimes ('static)
             if (c == '\'' && i + 1 < len && text[i+1].isLetter()) {
                 val start = i
                 i++
                 while (i < len && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                 // Check if it was a char literal 'x'
                 if (i - start <= 3 && i < len && text[i] == '\'') { 
                     i = start 
                  } else {
                     tokens.add(SyntaxToken("class-name", start, i)) // Lifetime styled as class/type
                     continue
                  }
             }

            // Strings / Chars
            if (c == '"' || c == '\'') {
                val start = i
                val quote = c
                i++
                while (i < len) {
                    if (text[i] == '\\') { i+=2; continue }
                    if (text[i] == quote) { i++; break }
                    i++
                }
                tokens.add(SyntaxToken("string", start, i))
                continue
            }

            // Macros (println!)
            if (c.isLetter() || c == '_') {
                val start = i
                while (i < len && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                if (i < len && text[i] == '!') {
                    i++
                    tokens.add(SyntaxToken("function", start, i)) // Macro
                } else {
                    val word = text.substring(start, i)
                    if (keywords.contains(word)) tokens.add(SyntaxToken("keyword", start, i))
                    else if (i < len && text[i] == '(') tokens.add(SyntaxToken("function", start, i))
                    else if (word.first().isUpperCase()) tokens.add(SyntaxToken("class-name", start, i))
                    else tokens.add(SyntaxToken("variable", start, i))
                }
                continue
            }

            // Numbers
            if (c.isDigit()) {
                val start = i
                while (i < len && (text[i].isLetterOrDigit() || text[i] == '.' || text[i] == '_')) i++
                tokens.add(SyntaxToken("number", start, i))
                continue
            }

            // Punctuation
            if (":;(){}[]".contains(c)) { tokens.add(SyntaxToken("punctuation", i, i+1)); i++; continue }
            // Operators
            if ("=+-*/^&|<>!".contains(c)) { tokens.add(SyntaxToken("operator", i, i+1)); i++; continue }
            
            i++
        }
        return tokens
    }
}

/**
 * Native Lexer for C# (CSharp).
 */
class CSharpLexer : NativeLexer {
    private val keywords = setOf(
        "abstract", "as", "base", "bool", "break", "byte", "case", "catch", "char", "checked", 
        "class", "const", "continue", "decimal", "default", "delegate", "do", "double", "else", 
        "enum", "event", "explicit", "extern", "false", "finally", "fixed", "float", "for", 
        "foreach", "goto", "if", "implicit", "in", "int", "interface", "internal", "is", "lock", 
        "long", "namespace", "new", "null", "object", "operator", "out", "override", "params", 
        "private", "protected", "public", "readonly", "ref", "return", "sbyte", "sealed", "short", 
        "sizeof", "stackalloc", "static", "string", "struct", "switch", "this", "throw", "true", 
        "try", "typeof", "uint", "ulong", "unchecked", "unsafe", "ushort", "using", "virtual", 
        "void", "volatile", "while", "add", "alias", "ascending", "async", "await", "by", "descending", 
        "dynamic", "equals", "from", "get", "global", "group", "into", "join", "let", "on", "orderby", 
        "partial", "remove", "select", "set", "value", "var", "where", "yield"
    )

    override fun tokenize(text: String): List<SyntaxToken> {
        val tokens = mutableListOf<SyntaxToken>()
        val len = text.length
        var i = 0

        while (i < len) {
            val c = text[i]
            if (c.isWhitespace()) { i++; continue }

            // Comments
            if (c == '/') {
                if (i+1 < len && text[i+1] == '/') {
                     val start = i; while(i<len && text[i]!='\n') i++; tokens.add(SyntaxToken("comment", start, i)); continue
                }
                if (i+1 < len && text[i+1] == '*') {
                     val start = i; i+=2; while(i+1<len && (text[i]!='*' || text[i+1]!='/')) i++; if(i+1<len) i+=2; tokens.add(SyntaxToken("comment", start, i)); continue
                }
            }

            // Strings (@"..." or "...")
            if (c == '@' && i+1 < len && text[i+1] == '"') {
                val start = i
                i+=2
                while (i < len) {
                    if (text[i] == '"') {
                        if (i+1 < len && text[i+1] == '"') { i+=2; continue } // "" escape
                        i++; break
                    }
                    i++
                }
                tokens.add(SyntaxToken("string", start, i))
                continue
            }
            if (c == '"' || c == '\'') {
                val start = i
                val quote = c
                i++
                while (i < len) {
                    if (text[i] == '\\') { i+=2; continue }
                    if (text[i] == quote) { i++; break }
                    i++
                }
                tokens.add(SyntaxToken("string", start, i))
                continue
            }

            // Numbers
            if (c.isDigit()) {
                val start = i
                while (i < len && (text[i].isLetterOrDigit() || text[i] == '.')) i++
                tokens.add(SyntaxToken("number", start, i))
                continue
            }

            // Keywords
            if (c.isLetter() || c == '_') {
                val start = i
                while (i < len && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                val word = text.substring(start, i)
                if (keywords.contains(word)) tokens.add(SyntaxToken("keyword", start, i))
                else if (i < len && text[i] == '(') tokens.add(SyntaxToken("function", start, i))
                else if (word.first().isUpperCase()) tokens.add(SyntaxToken("class-name", start, i))
                else tokens.add(SyntaxToken("variable", start, i))
                continue
            }

            if (",;(){}[]".contains(c)) { tokens.add(SyntaxToken("punctuation", i, i+1)); i++; continue }
            if ("=+-*/%&|^<>!".contains(c)) { tokens.add(SyntaxToken("operator", i, i+1)); i++; continue }

            i++
        }
        return tokens
    }
}

/**
 * Native Lexer for Markdown (CommonMark / GFM).
 * Supports: Headers, Lists, Links, Images, Inline Code, Blockquotes, Bold/Italic hints, HR.
 * Hardened: Line-Boundaries enforced to prevent runaway parsing.
 */
class MarkdownLexer : NativeLexer {
    override fun tokenize(text: String): List<SyntaxToken> {
        val tokens = mutableListOf<SyntaxToken>()
        val len = text.length
        var i = 0

        while (i < len) {
            val c = text[i]

            // 1. Headers (# H1, ## H2...)
            if (c == '#') {
                if (i == 0 || text[i-1] == '\n') {
                    val start = i
                    var j = i
                    while (j < len && text[j] == '#') j++
                    // Must be followed by space to be a header
                    if (j < len && text[j] == ' ') {
                        // It is a header! Consume until end of line
                        while (j < len && text[j] != '\n') j++
                        tokens.add(SyntaxToken("title", start, j))
                        i = j
                        continue
                    }
                }
            }

            // 2. Blockquotes (> Quote)
            if (c == '>') {
                if (i == 0 || text[i-1] == '\n') {
                    val start = i
                    i++
                    if (i < len && text[i] == ' ') {
                        while (i < len && text[i] != '\n') i++
                        tokens.add(SyntaxToken("comment", start, i))
                        continue
                    }
                }
            }

            // 3. Horizontal Rules (---, ***, ___)
            if ((c == '-' || c == '*' || c == '_') && (i == 0 || text[i-1] == '\n')) {
                 if (i + 2 < len && text[i+1] == c && text[i+2] == c) {
                     // Check if only these chars exist on the line (ignoring whitespace)
                     var j = i + 3
                     while (j < len && text[j] == c) j++
                     while (j < len && text[j].isWhitespace() && text[j] != '\n') j++
                     if (j == len || text[j] == '\n') {
                         tokens.add(SyntaxToken("comment", i, j)) // Use comment color for HR
                         i = j
                         continue
                     }
                 }
            }

            // 4. Lists ( - item, * item, + item)
            if (c == '-' || c == '*' || c == '+') {
                 if ((i == 0 || text[i-1] == '\n') && (i + 1 < len && text[i+1] == ' ')) {
                     tokens.add(SyntaxToken("list", i, i + 1))
                     i++
                     continue
                 }
            }

            // 5. Images (![Alt](Url))
            if (c == '!' && i + 1 < len && text[i+1] == '[') {
                val start = i
                i += 2 // Skip ![
                // Parse Alt Text
                val altStart = i
                while (i < len && text[i] != '\n' && text[i] != ']') i++
                
                if (i < len && text[i] == ']') {
                    tokens.add(SyntaxToken("variable", altStart, i)) // Alt Text -> Variable
                    i++ // Skip ]
                    // Check for URL (url)
                    if (i < len && text[i] == '(') {
                        i++ // Skip (
                        val urlStart = i
                        while (i < len && text[i] != '\n' && text[i] != ')') i++
                        if (i < len && text[i] == ')') {
                            tokens.add(SyntaxToken("url", urlStart, i)) // URL -> Link/Url
                            i++ // Skip )
                        }
                    }
                    continue
                } else {
                    // Broken syntax, backtrack or continue
                    i = start + 1
                    continue
                }
            }

            // 6. Links ([Text](Url))
            if (c == '[') {
                val start = i
                i++ // Skip [
                // Find closing ]
                val textStart = i
                while (i < len && text[i] != '\n' && text[i] != ']') i++
                
                if (i < len && text[i] == ']') {
                    tokens.add(SyntaxToken("variable", textStart, i)) // Link Text -> Variable
                    i++ // Skip ]
                    // Check for URL (url)
                    if (i < len && text[i] == '(') {
                        i++ // Skip (
                        val urlStart = i
                        while (i < len && text[i] != '\n' && text[i] != ')') i++
                        if (i < len && text[i] == ')') {
                            tokens.add(SyntaxToken("url", urlStart, i)) // URL -> Link/Url
                            i++ // Skip )
                        }
                    }
                    continue
                } else {
                    i = start + 1 // Not a link, reprocess
                    continue
                }
            }

            // 7. Inline Code (`code`)
            if (c == '`') {
                val start = i
                i++
                // Line Boundary: Don't cross newline
                while (i < len && text[i] != '\n') {
                    if (text[i] == '`') {
                        i++
                        tokens.add(SyntaxToken("code", start, i))
                        break
                    }
                    i++
                }
                continue
            }

            // 8. Bold/Italic (** or __)
            if (c == '*' || c == '_') {
                if (i + 1 < len && text[i+1] == c) {
                     tokens.add(SyntaxToken("bold", i, i + 2))
                     i += 2
                     continue
                }
            }

            i++
        }
        return tokens
    }
}
