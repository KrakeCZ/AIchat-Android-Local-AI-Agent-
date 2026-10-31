package com.example.llama.managers.plugins

/**
 * Enum of top 15 Prism4j supported languages with metadata for UI and settings.
 * Manual implementation (no bundler) for KSP-compatible offline syntax highlighting.
 * 
 * @property key Unique identifier used in SharedPreferences (e.g., "prism_lang_kotlin")
 * @property displayName Human-readable name for UI
 * @property aliases Alternative language identifiers (e.g., "js" for "javascript")
 */
enum class PrismLanguage(
    val key: String,
    val displayName: String,
    val aliases: List<String> = emptyList()
) {
    // Core web languages (most commonly used)
    MARKUP("markup", "HTML/XML/SVG", listOf("html", "xml", "svg", "mathml")),
    CSS("css", "CSS", emptyList()),
    CLIKE("clike", "C-like", emptyList()),
    JAVASCRIPT("javascript", "JavaScript", listOf("js", "jsx")),
    
    // JVM languages
    JAVA("java", "Java", emptyList()),
    KOTLIN("kotlin", "Kotlin", listOf("kt", "kts")),
    
    // Native languages
    C("c", "C", emptyList()),
    CPP("cpp", "C++", listOf("c++")),
    
    // Scripting languages
    PYTHON("python", "Python", listOf("py")),
    BASH("bash", "Bash/Shell", listOf("sh", "shell")),
    POWERSHELL("powershell", "PowerShell", listOf("ps1", "pwsh", "ps")),
    
    // Data formats
    JSON("json", "JSON", emptyList()),
    YAML("yaml", "YAML", listOf("yml")),
    SQL("sql", "SQL", emptyList()),
    
    // Markup & Documentation
    MARKDOWN("markdown", "Markdown", listOf("md")),
    
    // Other popular languages
    GO("go", "Go", listOf("golang")),
    
    // New additions
    RUST("rust", "Rust", listOf("rs")),
    TYPESCRIPT("typescript", "TypeScript", listOf("ts", "tsx", "react")),
    NASM("nasm", "Assembly (NASM)", listOf("asm", "nasm64", "low_level", "low-level")),
    CSHARP("csharp", "C#", listOf("cs", "dotnet", "c#")),
    SWIFT("swift", "Swift", emptyList());
    
    companion object {
        // Helper functions removed as they were unused.
        // The enum entries are accessed directly by SettingsActivity.
    }
}
