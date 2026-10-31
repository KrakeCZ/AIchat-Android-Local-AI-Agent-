package com.example.llama.agent.skills

/**
 * Datová reprezentace dovednosti (Agent Skill).
 * Každý skill odpovídá Markdown souboru s YAML frontmatter hlavičkou.
 */
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val triggers: List<String> = emptyList(),
    val instructions: String,
    val isBuiltIn: Boolean = false,
    var isEnabled: Boolean = true,
    val fileName: String = ""
)
