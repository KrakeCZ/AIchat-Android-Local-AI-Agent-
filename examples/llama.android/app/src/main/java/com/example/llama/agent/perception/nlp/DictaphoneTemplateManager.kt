package com.example.llama.agent.perception.nlp

/**
 * ========================================================================
 * DICTAPHONE TEMPLATE MANAGER — NextGen 2026 Professional Edition
 * ========================================================================
 *
 * Systém šablon pro post-produkční zpracování transkriptů z diktafonu.
 * Inspirováno: Plaud Note Pro, UMEVO Note Plus, Freed AI, Laxis.
 *
 * Architektura:
 *   - Každá šablona definuje systémový prompt pro GenAI (AICore/Llama)
 *   - Fallback: Pokud GenAI není k dispozici, šablona definuje heuristický
 *     postprocessing pipeline (BERT sentiment, ML Kit entity, TF ranking)
 *   - Uživatel si může vytvořit vlastní šablony (CUSTOM prompt)
 *
 * Offline-First: Vše běží lokálně na zařízení. Žádná data neopustí telefon.
 *
 * Podporované kategorie (30+ šablon):
 *   📋 Obecné (Meeting, Brainstorm, Lecture, Interview)
 *   🩺 Zdravotnictví (SOAP, DAP, BIRP, Progress Note)
 *   ⚖️ Právní (Legal Memo, Deposition, Case Brief)
 *   💼 Obchod (BANT, Sales Call, Standup, Sprint Review)
 *   🎓 Vzdělávání (Lecture Notes, Study Guide, Q&A)
 *   🌐 Jazykové (Překlad, Korektura, Bilingvní zápis)
 *   🧠 Analytické (Sentiment, Mind Map, SWOT)
 *   ✏️ Custom (Uživatelský prompt)
 */
object DictaphoneTemplateManager {

    /**
     * Kategorie šablon pro UI picker (BottomSheet).
     */
    enum class TemplateCategory(val icon: String, val displayName: String) {
        GENERAL("📋", "Obecné"),
        MEDICAL("🩺", "Zdravotnictví"),
        LEGAL("⚖️", "Právní"),
        BUSINESS("💼", "Obchod & Sales"),
        EDUCATION("🎓", "Vzdělávání"),
        LANGUAGE("🌐", "Jazykové"),
        ANALYTICS("🧠", "Analytické"),
        CUSTOM("✏️", "Vlastní šablona")
    }

    /**
     * Definice jedné šablony.
     *
     * @param id Unikátní identifikátor (pro persistence v SharedPreferences)
     * @param name Zobrazovaný název v UI
     * @param icon Emoji ikona
     * @param category Kategorie pro filtrování
     * @param systemPrompt Systémový prompt pro GenAI engine (AICore/Llama)
     * @param markdownStructure Markdown šablona pro export (když GenAI není k dispozici)
     * @param requiresSentiment Zda šablona vyžaduje BERT sentiment analýzu
     * @param requiresTranslation Zda šablona vyžaduje překlad (ML Kit Translate)
     * @param targetLanguage Cílový jazyk překladu (BCP-47), null = žádný překlad
     * @param requiresEntities Zda šablona vyžaduje ML Kit Entity Extraction
     */
    data class DictaphoneTemplate(
        val id: String,
        val name: String,
        val icon: String,
        val category: TemplateCategory,
        val systemPrompt: String,
        val markdownStructure: String,
        val requiresSentiment: Boolean = false,
        val requiresTranslation: Boolean = false,
        val targetLanguage: String? = null,
        val requiresEntities: Boolean = true,
        val supportedEngines: List<String> = listOf("LLAMA", "AICORE", "MEDIAPIPE", "HEURISTIKA", "AUTO")
    )

    // ==================== BUILT-IN ŠABLONY ====================

    /** Všechny vestavěné šablony. */
    fun getAllTemplates(): List<DictaphoneTemplate> {
        val strictGrounding = "\n\n[STRICT GROUNDING: Vycházej výhradně z poskytnutého textu. Zamez halucinacím a nevymýšlej si fakta. Dodržuj odborné oborové standardy (např. ISO, HL7) pro daný typ dokumentu. Pokud informace chybí, uveď 'Nezmíněno'. U překladů zachovej naprostou věrnost bez přidávání komentářů.]"
        
        return buildList {
            addAll(getGeneralTemplates())
            addAll(getMedicalTemplates())
            addAll(getLegalTemplates())
            addAll(getBusinessTemplates())
            addAll(getEducationTemplates())
            addAll(getLanguageTemplates())
            addAll(getAnalyticsTemplates())
        }.map { 
            it.copy(systemPrompt = it.systemPrompt + strictGrounding) 
        }
    }

    /** Šablony filtrované podle kategorie. */
    fun getTemplatesByCategory(category: TemplateCategory): List<DictaphoneTemplate> =
        getAllTemplates().filter { it.category == category }

    /** Najde šablonu podle ID. */
    fun getTemplateById(id: String): DictaphoneTemplate? =
        getAllTemplates().find { it.id == id }

    /** Výchozí šablona (Meeting Minutes). */
    fun getDefaultTemplate(): DictaphoneTemplate =
        getTemplateById("general_meeting") ?: getAllTemplates().first()

    // ==================== 📋 OBECNÉ ====================

    private fun getGeneralTemplates() = listOf(
        DictaphoneTemplate(
            id = "general_meeting",
            name = "Zápis z porady",
            icon = "📝",
            category = TemplateCategory.GENERAL,
            systemPrompt = """Analyzuj následující přepis schůzky a vytvoř strukturovaný zápis:
1. SHRNUTÍ: 3 klíčové body (max 2 věty každý)
2. ROZHODNUTÍ: Seznam všech rozhodnutí, která padla
3. AKČNÍ BODY: Seznam úkolů s přiřazenou osobou (pokud je zmíněna)
4. OTEVŘENÉ OTÁZKY: Co zůstalo nevyřešeno
Formátuj jako čistý Markdown. Piš v jazyce originálu.""",
            markdownStructure = """## 📌 Shrnutí
{summary}

## ✅ Rozhodnutí
{decisions}

## 📋 Akční body (To-Do)
{action_items}

## ❓ Otevřené otázky
{open_questions}

## 🏷️ Extrahované entity
{entities}"""
        ),
        DictaphoneTemplate(
            id = "general_brainstorm",
            name = "Brainstorming",
            icon = "💡",
            category = TemplateCategory.GENERAL,
            systemPrompt = """Analyzuj brainstorming session. Vytvoř:
1. HLAVNÍ TÉMA: O čem se diskutovalo
2. NÁPADY: Očíslovaný seznam všech navržených nápadů
3. PRO/PROTI: U každého nápadu uveď výhody a nevýhody
4. ZÁVĚR: Který nápad získal nejvíce podpory
Formátuj jako Markdown.""",
            markdownStructure = """## 💡 Téma brainstormingu
{topic}

## 🎯 Nápady
{ideas}

## ⚖️ Hodnocení
{evaluation}

## 🏆 Závěr
{conclusion}""",
            requiresSentiment = true
        ),
        DictaphoneTemplate(
            id = "general_interview",
            name = "Rozhovor / Interview",
            icon = "🎙️",
            category = TemplateCategory.GENERAL,
            systemPrompt = """Zpracuj přepis rozhovoru/interview:
1. PROFIL: Kdo byl dotazován (pokud je zmíněno)
2. KLÍČOVÉ ODPOVĚDI: Nejdůležitější odpovědi respondenta
3. CITACE: Doslovné citáty, které stojí za zaznamenání
4. SHRNUTÍ: Celkový dojem z rozhovoru
Zachovej formát otázka-odpověď kde je to možné.""",
            markdownStructure = """## 🎙️ Rozhovor
{profile}

## 💬 Klíčové odpovědi
{key_answers}

## 📜 Citace
{quotes}

## 📊 Shrnutí
{summary}""",
            requiresSentiment = true
        ),
        DictaphoneTemplate(
            id = "general_voice_memo",
            name = "Hlasová poznámka",
            icon = "🗣️",
            category = TemplateCategory.GENERAL,
            systemPrompt = """Zpracuj hlasovou poznámku. Uprav text do čitelné formy:
1. Odstraň nedokončené věty a výplňová slova
2. Rozděl do logických odstavců
3. Zvýrazni důležité body tučně
4. Pokud obsahuje úkoly, vypiš je jako checklist
Zachovej původní jazyk a význam.""",
            markdownStructure = """## 🗣️ Poznámka
{cleaned_text}

## ✅ Úkoly
{tasks}""",
            requiresEntities = true
        ),
        DictaphoneTemplate(
            id = "general_lecture",
            name = "Přednáška / Prezentace",
            icon = "🎓",
            category = TemplateCategory.GENERAL,
            systemPrompt = """Zpracuj přepis přednášky/prezentace:
1. NÁZEV: Téma přednášky
2. OSNOVA: Hierarchická struktura probíraných témat
3. KLÍČOVÉ KONCEPTY: Definice a vysvětlení hlavních pojmů
4. PŘÍKLADY: Uvedené příklady a případové studie
5. ZÁVĚR: Hlavní poselství přednášky""",
            markdownStructure = """## 🎓 Přednáška: {title}

### Osnova
{outline}

### Klíčové koncepty
{concepts}

### Příklady
{examples}

### Závěr
{conclusion}"""
        )
    )

    // ==================== 🩺 ZDRAVOTNICTVÍ ====================

    private fun getMedicalTemplates() = listOf(
        DictaphoneTemplate(
            id = "medical_soap",
            name = "SOAP Poznámka",
            icon = "🩺",
            category = TemplateCategory.MEDICAL,
            systemPrompt = """Zpracuj klinický záznam do SOAP formátu:
S (Subjektivní): Co pacient hlásí — příznaky, stížnosti, anamnéza
O (Objektivní): Měřitelná data — vitální znaky, fyzikální nález, laboratorní výsledky
A (Assessment): Diagnóza nebo diferenciální diagnóza
P (Plán): Navržená léčba, medikace, follow-up
DŮLEŽITÉ: Používej lékařskou terminologii. Zachovej faktickou přesnost.""",
            markdownStructure = """## 🩺 SOAP Note

### S — Subjektivní
{subjective}

### O — Objektivní
{objective}

### A — Assessment
{assessment}

### P — Plán
{plan}""",
            requiresEntities = true,
            supportedEngines = listOf("LLAMA", "AICORE", "AUTO")
        ),
        DictaphoneTemplate(
            id = "medical_dap",
            name = "DAP Poznámka (Terapie)",
            icon = "🧠",
            category = TemplateCategory.MEDICAL,
            systemPrompt = """Zpracuj terapeutický záznam do DAP formátu:
D (Data): Objektivní pozorování z terapeutického sezení
A (Assessment): Terapeutovo hodnocení klientova stavu a pokroku
P (Plán): Cíle pro další sezení, domácí úkoly, doporučení
Zachovej důvěrnost. Nepoužívej jména.""",
            markdownStructure = """## 🧠 DAP Note (Terapie)

### D — Data
{data}

### A — Assessment
{assessment}

### P — Plán
{plan}""",
            requiresSentiment = true
        ),
        DictaphoneTemplate(
            id = "medical_progress",
            name = "Progress Note",
            icon = "📈",
            category = TemplateCategory.MEDICAL,
            systemPrompt = """Vytvoř progress note z klinického záznamu:
1. DATUM A ČAS návštěvy
2. DŮVOD NÁVŠTĚVY
3. AKTUÁLNÍ STAV pacienta
4. ZMĚNY od poslední návštěvy
5. MEDIKACE (pokud zmíněna)
6. PLÁN DALŠÍHO POSTUPU""",
            markdownStructure = """## 📈 Progress Note

| Položka | Detail |
|---------|--------|
| Datum | {date} |
| Důvod | {reason} |

### Aktuální stav
{current_state}

### Změny
{changes}

### Medikace
{medication}

### Plán
{plan}""",
            requiresEntities = true
        )
    )

    // ==================== ⚖️ PRÁVNÍ ====================

    private fun getLegalTemplates() = listOf(
        DictaphoneTemplate(
            id = "legal_memo",
            name = "Právní Memorandum",
            icon = "⚖️",
            category = TemplateCategory.LEGAL,
            systemPrompt = """Zpracuj přepis do právního memoranda:
1. VĚCI: Identifikace stran a předmětu
2. OTÁZKY: Právní otázky k řešení
3. KRÁTKÁ ODPOVĚĎ: Stručná odpověď na každou otázku
4. FAKTA: Relevantní skutkový stav
5. ANALÝZA: Právní rozbor
6. ZÁVĚR: Doporučení dalšího postupu
Používej formální právní jazyk.""",
            markdownStructure = """## ⚖️ Právní Memorandum

### Strany a předmět
{parties}

### Právní otázky
{questions}

### Skutkový stav
{facts}

### Analýza
{analysis}

### Závěr a doporučení
{conclusion}""",
            requiresEntities = true,
            supportedEngines = listOf("LLAMA", "AICORE", "AUTO")
        ),
        DictaphoneTemplate(
            id = "legal_deposition",
            name = "Záznam z výslechu",
            icon = "📋",
            category = TemplateCategory.LEGAL,
            systemPrompt = """Zpracuj záznam z výslechu/svědecké výpovědi:
1. IDENTIFIKACE svědka
2. KLÍČOVÉ VÝPOVĚDI s přesnými citacemi
3. ROZPORY v tvrzeních (pokud existují)
4. DŮKAZNÍ MATERIÁL zmíněný ve výpovědi
5. SHRNUTÍ výpovědi
Zachovej přesnost citací.""",
            markdownStructure = """## 📋 Deposition Summary

### Svědek
{witness}

### Klíčové výpovědi
{testimonies}

### Rozpory
{contradictions}

### Důkazy
{evidence}""",
            requiresEntities = true
        )
    )

    // ==================== 💼 OBCHOD ====================

    private fun getBusinessTemplates() = listOf(
        DictaphoneTemplate(
            id = "business_bant",
            name = "BANT Kvalifikace",
            icon = "💰",
            category = TemplateCategory.BUSINESS,
            systemPrompt = """Analyzuj sales call podle BANT frameworku:
B (Budget): Jaký je rozpočet klienta? Zmínil cenu/limit?
A (Authority): Kdo rozhoduje? Má volající pravomoc?
N (Need): Jaký problém řeší? Co potřebuje?
T (Timeline): Kdy potřebuje řešení? Jaký je časový rámec?
SCORE: Na konci uveď BANT skóre 0-100% (kolik kritérií je splněno).""",
            markdownStructure = """## 💰 BANT Kvalifikace

| Kritérium | Status | Detail |
|-----------|--------|--------|
| 💵 Budget | {b_status} | {budget} |
| 👤 Authority | {a_status} | {authority} |
| 🎯 Need | {n_status} | {need} |
| ⏰ Timeline | {t_status} | {timeline} |

### BANT Skóre: {score}%

### Další kroky
{next_steps}""",
            requiresEntities = true,
            requiresSentiment = true
        ),
        DictaphoneTemplate(
            id = "business_standup",
            name = "Daily Standup",
            icon = "🏃",
            category = TemplateCategory.BUSINESS,
            systemPrompt = """Zpracuj daily standup meeting:
Pro každého člena týmu (pokud jsou rozlišitelní):
1. ✅ VČERA: Co dokončil
2. 📋 DNES: Na čem bude pracovat
3. 🚫 BLOCKER: Co mu brání v práci
Na konci uveď celkový přehled blockerů týmu.""",
            markdownStructure = """## 🏃 Daily Standup

{team_updates}

### 🚫 Celkové blockery
{blockers}"""
        ),
        DictaphoneTemplate(
            id = "business_sprint_review",
            name = "Sprint Review",
            icon = "🔄",
            category = TemplateCategory.BUSINESS,
            systemPrompt = """Zpracuj sprint review/retrospektivu:
1. DOKONČENÉ: Co bylo ve sprintu dokončeno
2. NEDOKONČENÉ: Co se nestihlo a proč
3. DEMO: Jaké funkce byly předvedeny
4. FEEDBACK: Zpětná vazba od stakeholderů
5. METRIKY: Velocity, burn-down (pokud zmíněno)
6. AKCE PRO DALŠÍ SPRINT: Konkrétní kroky""",
            markdownStructure = """## 🔄 Sprint Review

### ✅ Dokončené
{completed}

### ⏳ Nedokončené
{incomplete}

### 💬 Feedback
{feedback}

### 🎯 Akce pro další sprint
{actions}"""
        ),
        DictaphoneTemplate(
            id = "business_sales_call",
            name = "Sales Call Report",
            icon = "📞",
            category = TemplateCategory.BUSINESS,
            systemPrompt = """Zpracuj záznam obchodního hovoru:
1. KLIENT: Jméno firmy/osoby
2. PRODUKT/SLUŽBA: O čem se jednalo
3. POTŘEBY KLIENTA: Identifikované potřeby
4. NÁMITKY: Jaké námitky klient vznesl
5. NABÍDKA: Co bylo nabídnuto (cena, podmínky)
6. VÝSLEDEK: Dohodnutý další krok
7. SENTIMENT: Celkový dojem z hovoru""",
            markdownStructure = """## 📞 Sales Call Report

| Info | Detail |
|------|--------|
| Klient | {client} |
| Produkt | {product} |

### Potřeby
{needs}

### Námitky
{objections}

### Výsledek & Další kroky
{outcome}""",
            requiresSentiment = true,
            requiresEntities = true
        )
    )

    // ==================== 🎓 VZDĚLÁVÁNÍ ====================

    private fun getEducationTemplates() = listOf(
        DictaphoneTemplate(
            id = "edu_study_guide",
            name = "Studijní průvodce",
            icon = "📚",
            category = TemplateCategory.EDUCATION,
            systemPrompt = """Vytvoř studijní průvodce z přednášky:
1. HLAVNÍ TÉMATA s hierarchií
2. DEFINICE klíčových pojmů (formát: POJEM — definice)
3. VZORCE/PRAVIDLA (pokud zmíněny)
4. KONTROLNÍ OTÁZKY pro self-test (5-10 otázek)
5. SHRNUTÍ na jednu stranu A4""",
            markdownStructure = """## 📚 Studijní průvodce

### Témata
{topics}

### Definice
{definitions}

### Kontrolní otázky
{questions}

### Shrnutí
{summary}"""
        ),
        DictaphoneTemplate(
            id = "edu_qa_extract",
            name = "Q&A Extrakce",
            icon = "❓",
            category = TemplateCategory.EDUCATION,
            systemPrompt = """Extrahuj všechny otázky a odpovědi z přepisu:
Formát:
Q1: [Otázka]
A1: [Odpověď]
Na konci uveď seznam nezodpovězených otázek.""",
            markdownStructure = """## ❓ Q&A

{qa_pairs}

### Nezodpovězené otázky
{unanswered}"""
        )
    )

    // ==================== 🌐 JAZYKOVÉ ====================

    private fun getLanguageTemplates() = listOf(
        DictaphoneTemplate(
            id = "lang_translate_multi",
            name = "Překlad do cizího jazyka",
            icon = "🌍",
            category = TemplateCategory.LANGUAGE,
            systemPrompt = "Přelož následující text do cílového jazyka. Zachovej formátování a strukturu originálu.",
            markdownStructure = """## 🌍 Překlad

### Originál
{original}

### Výsledek překladu
{translated}""",
            requiresTranslation = true,
            targetLanguage = "MULTI",
            requiresEntities = false,
            supportedEngines = listOf("LLAMA", "AICORE", "AUTO")
        ),
        DictaphoneTemplate(
            id = "lang_proofread",
            name = "Korektura & Grammar",
            icon = "✍️",
            category = TemplateCategory.LANGUAGE,
            systemPrompt = """Proveď jazykovou korekturu textu:
1. Oprav gramatické chyby
2. Oprav překlepy a interpunkci
3. Vylepši stylistiku (ale zachovej význam)
4. Na konci uveď seznam provedených oprav
Neměň odborné termíny a vlastní jména.""",
            markdownStructure = """## ✍️ Korektura

### Opravený text
{corrected}

### Seznam oprav
{corrections}""",
            requiresEntities = false
        ),
        DictaphoneTemplate(
            id = "lang_bilingual",
            name = "Bilingvní zápis (CZ + Výběr)",
            icon = "🌍",
            category = TemplateCategory.LANGUAGE,
            systemPrompt = """Vytvoř bilingvní zápis: Každý odstavec nejdřív v originálním jazyce, pak překlad do zvoleného jazyka.""",
            markdownStructure = """## 🌍 Bilingvní zápis

{bilingual_content}""",
            requiresTranslation = true,
            targetLanguage = "MULTI",
            supportedEngines = listOf("LLAMA", "AICORE", "AUTO")
        )
    )

    // ==================== 🧠 ANALYTICKÉ ====================

    private fun getAnalyticsTemplates() = listOf(
        DictaphoneTemplate(
            id = "analytics_sentiment",
            name = "Sentiment & Nálada",
            icon = "😊",
            category = TemplateCategory.ANALYTICS,
            systemPrompt = """Analyzuj emocionální tón konverzace:
1. CELKOVÝ SENTIMENT: Pozitivní / Neutrální / Negativní (s procentem)
2. PRŮBĚH: Jak se nálada měnila v průběhu konverzace
3. KLÍČOVÉ MOMENTY: Kdy došlo ke změně nálady a proč
4. DOPORUČENÍ: Jak zlepšit komunikaci na základě sentimentu""",
            markdownStructure = """## 😊 Sentiment analýza

### Celkový tón: {overall_sentiment}

### Průběh nálady
{sentiment_timeline}

### Klíčové momenty
{key_moments}

### Doporučení
{recommendations}""",
            requiresSentiment = true,
            requiresEntities = false
        ),
        DictaphoneTemplate(
            id = "analytics_mindmap",
            name = "Myšlenková mapa",
            icon = "🗺️",
            category = TemplateCategory.ANALYTICS,
            systemPrompt = """Vytvoř myšlenkovou mapu z textu ve formátu Markdown:
- Centrální téma jako # nadpis
- Hlavní větve jako ## podnadpisy
- Pod-body jako odrážky (-)
- Klíčová slova tučně (**slovo**)
Max 3 úrovně zanoření. Stručné, heslovité.""",
            markdownStructure = """## 🗺️ Myšlenková mapa

{mindmap_content}""",
            requiresEntities = false
        ),
        DictaphoneTemplate(
            id = "analytics_swot",
            name = "SWOT Analýza",
            icon = "📊",
            category = TemplateCategory.ANALYTICS,
            systemPrompt = """Vytvoř SWOT analýzu z diskuse:
S (Strengths): Identifikované silné stránky
W (Weaknesses): Identifikované slabé stránky
O (Opportunities): Zmíněné příležitosti
T (Threats): Zmíněné hrozby a rizika
Na konci navrhni strategické doporučení.""",
            markdownStructure = """## 📊 SWOT Analýza

| | Pozitivní | Negativní |
|---|---|---|
| **Interní** | 💪 Strengths | ⚠️ Weaknesses |
| **Externí** | 🌟 Opportunities | 🔥 Threats |

### 💪 Strengths
{strengths}

### ⚠️ Weaknesses
{weaknesses}

### 🌟 Opportunities
{opportunities}

### 🔥 Threats
{threats}

### 🎯 Strategie
{strategy}""",
            requiresSentiment = true
        ),
        DictaphoneTemplate(
            id = "analytics_action_extract",
            name = "Čistá extrakce úkolů",
            icon = "✅",
            category = TemplateCategory.ANALYTICS,
            systemPrompt = """Extrahuj POUZE akční body a úkoly z textu:
Formát:
- [ ] ÚKOL (Zodpovědný: XY, Termín: DD.MM.)
Seřaď podle priority (urgentní první).
Ignoruj vše ostatní — žádné shrnutí, žádný kontext.""",
            markdownStructure = """## ✅ Akční body

### 🔴 Urgentní
{urgent}

### 🟡 Normální
{normal}

### 🟢 Nízká priorita
{low}""",
            requiresEntities = true
        )
    )

    // ==================== CUSTOM ŠABLONA ====================

    /**
     * Vytvoří custom šablonu z uživatelského promptu.
     */
    fun createCustomTemplate(
        id: String,
        name: String,
        userPrompt: String,
        targetLang: String? = null
    ): DictaphoneTemplate = DictaphoneTemplate(
        id = "custom_$id",
        name = name,
        icon = "✏️",
        category = TemplateCategory.CUSTOM,
        systemPrompt = userPrompt,
        markdownStructure = """## ✏️ $name

{ai_output}

---
## 💬 Původní přepis
{original}""",
        requiresTranslation = targetLang != null,
        targetLanguage = targetLang
    )
}
