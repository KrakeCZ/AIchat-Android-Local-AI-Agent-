package com.example.llama.agent.skills

import android.content.Context
import android.util.Log
import com.example.llama.SettingsManager
import java.io.File

/**
 * Centrální registr dovedností (Skills Framework SOTA 2026).
 * Stará se o parsing YAML hlaviček v Markdown souborech, perzistenci na disku,
 * TODO  vytvořit databazi s možností import  export do md json  csv
 * CRUD operace pro uživatele a dynamickou injekci do kontextu LLM.
 */
class SkillRegistry private constructor(private val context: Context) {

    private val skillsDir: File = File(context.filesDir, "skills")
    private val settingsManager = SettingsManager(context)
    private val cachedSkills = mutableListOf<Skill>()

    companion object {
        private const val TAG = "SkillRegistry"
        const val SKILL_AUTO = "auto"
        const val SKILL_NONE = "none"

        @Volatile
        private var instance: SkillRegistry? = null

        fun getInstance(context: Context): SkillRegistry {
            return instance ?: synchronized(this) {
                instance ?: SkillRegistry(context.applicationContext).also {
                    it.initialize()
                    instance = it
                }
            }
        }

        // --- VÝCHOZÍ VESTAVĚNÉ DOVEDNOSTI (SOTA 2026) ---

        val DEFAULT_DEEP_RESEARCH = """
---
name: Deep Research
description: Hloubková autonomní syntéza, webové rešerše, formulace hypotéz a křížové ověřování faktů (SOTA 2026).
triggers: deep_research, deep_learning
enabled: true
---

# Deep Research & Evidence Synthesis Protocol
You are operating in DEEP RESEARCH MODE with access to real-time Web Tools. Execute every response using these rigorous guidelines:
0. Využij internet k *web_search* & *read_url_content* k hledani nejnovějších poznatků, nejmodernejších praci, studii, patentu, akademickych pracich k systémovemu datu.
1. **Deconstruct & Hypothesize**: Break down the user query into testable, core sub-questions.
2. **Autonomous Web Tools**: If you need up-to-date facts, current news, official documentation, or technical data, use the available web tools before formulating your final answer. NEVER invoke tools for greetings, introductions (e.g. "představ se", "kdo jsi"), or current date/time checks.
3. **Evidence-Based Grounding**: Ground every conclusion on verified facts. Cite sources and URLs retrieved from tools.
4. **Structured Report Delivery**: Organize the output logically:
   - **Executive Summary**: Core findings in 2-3 sentences.
   - **Deep Technical Analysis & Synthesis**: Step-by-step breakdown with empirical facts, code snippets, or benchmark numbers.
   - **Trade-offs & Edge Cases**: Limitations, potential failure modes, or opposing views.
   - **Definitive Conclusion & Source Citations**.

# Available Web Tools:
You can invoke the following tools whenever needed:
- `web_search`: Search DuckDuckGo for live facts or articles.
  Syntax:
  <tool_call>
  {"name": "web_search", "arguments": {"query": "<search query>"}}
  </tool_call>

- `read_url_content`: Read and extract clean text/markdown from a specific URL.
  Syntax:
  <tool_call>
  {"name": "read_url_content", "arguments": {"url": "<https://...>"}}
  </tool_call>

When you invoke a tool, stop and wait. The system will execute the tool and provide a <tool_response> block with the factual content so you can complete your research synthesis.
""".trimIndent()

        val DEFAULT_DEVELOPER = """
---
name: Developer
description: MultiDeveloper
triggers: multi_develop
enabled: true
---

#System_ROLE
you are mix of experts Senior/Architect/Security/DevOps/kernelDEVS.
já poskytuji jeho bezchybnou, systémově validní a profesionální implementaci.
Neočekávejte ode mě vysvětlení základů. Očekávejte kód a analýzy na úrovni, kterou byste vytvořil sám, kdybyste měl čas na manuální ověření každé jednotlivé interakce se systémem a každého okrajového případu.
Neprováděj žádné 'vylepšení', změny hodnot, kódu ani logiky, o které nejsem výslovně požádán. I když si myslíš, že by to bylo lepší, nedělej to.
Přesnost 1:1 !
Při kopírování, převodu nebo úpravě dat zachovej všechny hodnoty přesně tak, jak jsou v originále.
 Nenabízej alternativní řešení, pokud o to výslovně nepožádám.

# Available Web Tools:
You can invoke the following tools whenever needed:
- `web_search`: Search on web for live facts or articles.
  Syntax:
  <tool_call>
  {"name": "web_search", "arguments": {"query": "<search query>"}}
  </tool_call>

- `read_url_content`: Read and extract clean text/markdown from a specific URL.
  Syntax:
  <tool_call>
  {"name": "read_url_content", "arguments": {"url": "<https://...>"}}
  </tool_call>

When you invoke a tool, stop and wait. The system will execute the tool and provide a <tool_response> block with the factual content so you can complete your research synthesis.
""".trimIndent()

        val DEFAULT_CODE_AUDITOR = """
---
name: Code Assistant
description: Programátorský asistent pro analýzu, psaní a refaktorování kódu.
triggers: assist_code,
enabled: true
---

# Code Assistant
You are an expert software developer and programming assistant.
- Help write, analyze, debug, and explain code accurately.
- Provide clean, efficient, and well-structured solutions.
""".trimIndent()

        val DEFAULT_ANDROID_MASTER = """
---
name: Android Specialist
description: Expert na Android 14/15/16/17 vývoj, Kotlin, Jetpack,C++, modularní architekturu a platformní API.
Profesionální struktura : žádný koncept / šablona
Není nutné mít odpovědi co nejrychleji, důležitá je kvalita před kvantitou! odpověd, task , implementation plán, diskuze piš česky , kód univerzalně anglicky , placeholeder v kodu mužes česky.
Striktně non-root / non-ADB
Sjednocený kód: Styl psaní je konzistentní napříč celým skriptem. Všechny duplicity jsou odstraněny.
Fázový přístup: postupuje logicky sytematicky.
kód generuje jako senior DevOPS / KernelDevs systémový inženýra pro kritickou infrastrukturu.
triggers: navrhni_aplikaci_android,
enabled: true
---

# Android Specialist
You are an expert Android developer.
- Assist with modern Android application development, Kotlin, Jetpack libraries, Compose, and system integration.
""".trimIndent()
    }


    /**
     * Inicializuje adresář dovedností a vytvoří výchozí Markdown soubory, pokud neexistují.
     */
    fun initialize() {
        try {
            if (!skillsDir.exists()) {
                skillsDir.mkdirs()
            }
            ensureBuiltinSkillFile("deep_research.md", DEFAULT_DEEP_RESEARCH)
            ensureBuiltinSkillFile("default_developer.md",DEFAULT_DEVELOPER)
            ensureBuiltinSkillFile("code_auditor.md", DEFAULT_CODE_AUDITOR)
            ensureBuiltinSkillFile("android_master.md", DEFAULT_ANDROID_MASTER)
            reloadSkills()
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed", e)
        }
    }

    private fun ensureBuiltinSkillFile(fileName: String, content: String) {
        val file = File(skillsDir, fileName)
        val shouldUpdate = !file.exists() ||
            (fileName == "deep_research.md" && !file.readText(Charsets.UTF_8).contains("web_search")) ||
            (fileName == "default_developer.md" && file.readText(Charsets.UTF_8).contains("Senior Developer")) ||
            (fileName == "code_auditor.md" && file.readText(Charsets.UTF_8).contains("Zero-Defect")) ||
            (fileName == "android_master.md" && file.readText(Charsets.UTF_8).contains("Infrastructure Specialist Protocol"))
        if (shouldUpdate) {
            file.writeText(content, Charsets.UTF_8)
            Log.i(TAG, "Created/Updated built-in skill file: ${file.name}")
        }
    }

    /**
     * Znovu načte a naparsuje všechny dovednosti z disku.
     */
    @Synchronized
    fun reloadSkills(): List<Skill> {
        cachedSkills.clear()
        val files = skillsDir.listFiles { f -> f.isFile && f.name.endsWith(".md") } ?: emptyArray()

        for (file in files) {
            try {
                val text = file.readText(Charsets.UTF_8)
                val isBuiltIn = file.name in listOf("deep_research.md", "default_developer.md", "code_auditor.md", "android_master.md")
                val defaultId = file.nameWithoutExtension.lowercase().replace(" ", "_")
                val skill = parseSkillMarkdown(
                    content = text,
                    defaultId = defaultId,
                    isBuiltIn = isBuiltIn,
                    fileName = file.name
                )
                cachedSkills.add(skill)
            } catch (e: Exception) {
                Log.e(TAG, "Failed parsing skill from file: ${file.name}", e)
            }
        }
        cachedSkills.sortBy { if (it.isBuiltIn) 0 else 1 }
        Log.i(TAG, "Loaded ${cachedSkills.size} skills from disk.")
        return cachedSkills.toList()
    }

    /**
     * Vrátí seznam všech dostupných dovedností.
     */
    @Synchronized
    fun getAllSkills(): List<Skill> {
        if (cachedSkills.isEmpty()) {
            reloadSkills()
        }
        return cachedSkills.toList()
    }

    /**
     * Vrátí konkrétní dovednost podle ID.
     */
    @Synchronized
    fun getSkillById(id: String): Skill? {
        return getAllSkills().firstOrNull { it.id.equals(id, ignoreCase = true) }
    }

    /**
     * Uloží nebo vytvoří novou dovednost jako Markdown soubor na disk.
     */
    @Synchronized
    fun saveSkill(skill: Skill): Boolean {
        return try {
            val fileName = if (skill.fileName.isNotBlank()) {
                skill.fileName
            } else {
                val safeSlug = skill.id.ifBlank { skill.name }
                    .lowercase()
                    .replace(Regex("[^a-z0-9_]"), "_")
                "$safeSlug.md"
            }
            val file = File(skillsDir, fileName)
            val markdown = formatSkillMarkdown(skill.copy(fileName = fileName))
            file.writeText(markdown, Charsets.UTF_8)
            reloadSkills()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save skill: ${skill.name}", e)
            false
        }
    }

    /**
     * Smaže uživatelskou dovednost z disku (vestavěné dovednosti nelze smazat, pouze vypnout).
     */
    @Synchronized
    fun deleteSkill(id: String): Boolean {
        val skill = getSkillById(id) ?: return false
        if (skill.isBuiltIn) {
            Log.w(TAG, "Cannot delete built-in skill: $id")
            return false
        }
        return try {
            val file = File(skillsDir, skill.fileName)
            if (file.exists() && file.delete()) {
                if (activeSkillId == id) {
                    activeSkillId = SKILL_AUTO
                }
                reloadSkills()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed deleting skill: $id", e)
            false
        }
    }

    /**
     * Přepne stav zapnuto/vypnuto pro konkrétní dovednost.
     */
    @Synchronized
    fun setSkillEnabled(id: String, enabled: Boolean) {
        val skill = getSkillById(id) ?: return
        val updated = skill.copy(isEnabled = enabled)
        saveSkill(updated)
    }

    // --- AKTIVNÍ PROFIL DOVEDNOSTI PRO KONVERZACI ---

    var activeSkillId: String
        get() = settingsManager.prefs.getString("active_skill_id", SKILL_AUTO) ?: SKILL_AUTO
        set(value) = settingsManager.prefs.edit().putString("active_skill_id", value).apply()

    /**
     * Sestaví instrukce pro injekci do systémového promptu LLM na základě uživatelského vstupu a nastavení.
     */
    fun getPromptInjection(userPrompt: String = ""): String {
        val activeId = activeSkillId
        if (activeId == SKILL_NONE) {
            return ""
        }

        // 1. Explicitně zvolená konkrétní dovednost
        if (activeId != SKILL_AUTO) {
            val skill = getSkillById(activeId)
            if (skill != null && skill.isEnabled) {
                return buildString {
                    append("<!-- ACTIVE_SKILL: ${skill.name} -->\n")
                    append("<active_skill id=\"${skill.id}\" name=\"${skill.name}\">\n")
                    append(skill.instructions.trim())
                    append("\n</active_skill>")
                }
            }
        }

        // 2. Režim AUTO: Dynamické ověření triggerů a klíčových slov v promptu uživatele
        if (activeId == SKILL_AUTO && userPrompt.isNotBlank()) {
            val cleanPrompt = userPrompt.lowercase()
            val matchedSkill = getAllSkills().filter { it.isEnabled }.firstOrNull { skill ->
                skill.triggers.any { trig ->
                    trig.isNotBlank() && cleanPrompt.contains(trig.lowercase())
                }
            }
            if (matchedSkill != null) {
                return buildString {
                    append("<!-- ACTIVE_SKILL: ${matchedSkill.name} -->\n")
                    append("<active_skill id=\"${matchedSkill.id}\" name=\"${matchedSkill.name}\">\n")
                    append(matchedSkill.instructions.trim())
                    append("\n</active_skill>")
                }
            }
        }

        return ""
    }

    /**
     * Parsovací jádro: Zpracuje Markdown s YAML frontmatter.
     */
    fun parseSkillMarkdown(
        content: String,
        defaultId: String,
        isBuiltIn: Boolean = false,
        fileName: String = ""
    ): Skill {
        var name = defaultId.replace("_", " ").replaceFirstChar { it.uppercase() }
        var description = ""
        val triggers = mutableListOf<String>()
        var isEnabled = true
        var instructions = content

        val frontmatterRegex = Regex("""^---\s*\r?\n([\s\S]*?)\r?\n---\s*\r?\n?([\s\S]*)""")
        val match = frontmatterRegex.find(content.trim())

        if (match != null) {
            val yamlBlock = match.groupValues[1]
            instructions = match.groupValues[2].trim()

            yamlBlock.lines().forEach { line ->
                val trimmed = line.trim()
                val colonIdx = trimmed.indexOf(':')
                if (colonIdx > 0) {
                    val key = trimmed.substring(0, colonIdx).trim().lowercase()
                    val value = trimmed.substring(colonIdx + 1).trim().removeSurrounding("\"").removeSurrounding("'")
                    when (key) {
                        "name" -> if (value.isNotBlank()) name = value
                        "description" -> if (value.isNotBlank()) description = value
                        "enabled" -> isEnabled = value.toBooleanStrictOrNull() ?: true
                        "triggers" -> {
                            val items = value.removePrefix("[").removeSuffix("]")
                                .split(",")
                                .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
                                .filter { it.isNotBlank() }
                            triggers.addAll(items)
                        }
                    }
                }
            }
        } else {
            // Fallback pokud chybí YAML hlavička: pokus se vytáhnout první H1 nadpis a první odstavec
            val lines = content.lines().map { it.trim() }.filter { it.isNotBlank() }
            val firstH1 = lines.firstOrNull { it.startsWith("# ") }
            if (firstH1 != null) {
                name = firstH1.removePrefix("# ").trim()
            }
            val firstPara = lines.firstOrNull { !it.startsWith("#") }
            if (firstPara != null) {
                description = firstPara
            }
        }

        return Skill(
            id = defaultId,
            name = name,
            description = description,
            triggers = triggers.distinct(),
            instructions = instructions,
            isBuiltIn = isBuiltIn,
            isEnabled = isEnabled,
            fileName = fileName
        )
    }

    /**
     * Formátuje Skill objekt do validního Markdownu s YAML frontmatter.
     */
    fun formatSkillMarkdown(skill: Skill): String {
        val sb = StringBuilder()
        sb.append("---\n")
        sb.append("name: \"${skill.name.replace("\"", "\\\"")}\"\n")
        sb.append("description: \"${skill.description.replace("\"", "\\\"")}\"\n")
        sb.append("triggers: [${skill.triggers.joinToString(", ") { "\"${it.trim()}\"" }}]\n")
        sb.append("enabled: ${skill.isEnabled}\n")
        sb.append("---\n\n")
        sb.append(skill.instructions.trim())
        sb.append("\n")
        return sb.toString()
    }
}
