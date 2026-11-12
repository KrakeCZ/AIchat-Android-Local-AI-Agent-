package com.example.llama.ui.thanks

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.llama.SettingsManager
import com.example.llama.agent.core.NlpActionHandler
import com.example.llama.agent.models.ActionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

private const val TAG = "ThanksActivity"

// =====================================================================================
// KATEGORIE LICENCÍ
// -------------------------------------------------------------------------------------
// Slouží k automatickému roztřídění závislostí dle reálného právního dopadu na
// distribuci proprietární Android aplikace. Řazení (PERMISSIVE -> PROPRIETARY) odpovídá
// rostoucí míře povinností/rizika, které daná licence na distributora klade.
// =====================================================================================
enum class LicenseCategory(
    val label: String,
    val shortCode: String,
    val color: Color,
    val explanation: String
) {
    PERMISSIVE(
        label = "Permisivní licence",
        shortCode = "MIT / BSD / Apache-2.0",
        color = Color(0xFF22C55E),
        explanation = "Volné užití, úprava i distribuce v uzavřeném (proprietárním) software. " +
            "Jedinou podmínkou je zachování copyright doložky a textu licence uvnitř aplikace."
    ),
    WEAK_COPYLEFT(
        label = "Slabý Copyleft",
        shortCode = "LGPL",
        color = Color(0xFF3B82F6),
        explanation = "Lze linkovat i do proprietárního software. Podmínkou je umožnit uživateli " +
            "výměnu/relinkování této konkrétní knihovny a zveřejnit její zdrojový kód a případné patche."
    ),
    STRONG_COPYLEFT(
        label = "Silný Copyleft",
        shortCode = "GPL / AGPL",
        color = Color(0xFFEF4444),
        explanation = "Bez samostatné komerční licence od autora může vyžadovat zveřejnění " +
            "zdrojového kódu CELÉ aplikace pod stejnou licencí, pokud je knihovna staticky provázaná " +
            "nebo (u AGPL) provozovaná přes síť. Před komerční distribucí nutná právní kontrola build konfigurace."
    ),
    CUSTOM_RESTRICTED(
        label = "Custom / Omezená licence",
        shortCode = "Community License",
        color = Color(0xFFA855F7),
        explanation = "Vlastní licence poskytovatele s dodatečnými obchodními podmínkami " +
            "(např. limit počtu aktivních uživatelů). Nejde o OSI-schválenou open-source licenci."
    ),
    PROPRIETARY(
        label = "Proprietární / Vendor",
        shortCode = "Smluvní podmínky",
        color = Color(0xFF64748B),
        explanation = "Užití se řídí smluvními podmínkami konkrétního poskytovatele " +
            "(Developer Agreement / Terms of Service), nejde o open-source licenci."
    )
}

/**
 * @param licenseAssetFile Název souboru v assets/licenses/, pokud aplikace bundluje
 *                          plné a ověřené znění licence pro offline zobrazení. Pokud je null,
 *                          plné znění NENÍ součástí aplikace (typicky custom/proprietární
 *                          licence, kde by neověřená reprodukce mohla být nepřesná).
 * @param complianceNote    Doplňková expertní poznámka k dané závislosti (nemění název ani
 *                          text licence, pouze upozorňuje na skutečnost hodnou ověření).
 */
data class CreatorEntry(
    val title: String,
    val originalText: String,
    val copyright: String,
    val licenseName: String,
    val licenseCategory: LicenseCategory,
    val licenseAssetFile: String? = null,
    val complianceNote: String? = null,
    val isPremium: Boolean = false
)

// =====================================================================================
// PŘEKLAD — beze změny oproti originálu: veškerá logika (ML Kit on-device translate,
// jednorázové stažení jazykového balíčku ~30 MB, offline běh) žije uvnitř NlpActionHandler.
// Zde pouze voláme ActionType.NLP_TRANSLATE stejným způsobem jako v původním souboru.
// =====================================================================================
class ThanksActivity : ComponentActivity() {

    private val nlpActionHandler by lazy {
        NlpActionHandler(applicationContext, SettingsManager(applicationContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val scope = rememberCoroutineScope()
            ThanksScreen(
                onClose = { finish() },
                onTranslate = { text, targetLang, callback ->
                    scope.launch {
                        try {
                            // Pokud je specifikován cílový jazyk, předáme to v explicitním formátu "from|to|text".
                            // Zdrojové texty jsou česky, proto vždy "cs" -> targetLang.
                            val payload = if (targetLang != null) "cs|$targetLang|$text" else text
                            val translated = nlpActionHandler.execute(ActionType.NLP_TRANSLATE, payload)
                            callback(translated)
                        } catch (e: Exception) {
                            Log.e(TAG, "Překlad selhal", e)
                            callback("Translation failed: ${e.message}")
                        }
                    }
                }
            )
        }
    }
}

/** Přemostí callback-based [onTranslate] na suspend volání, aby šlo sekvenčně "await"-ovat
 *  překlad více textů za sebou (potřebné pro překlad celé stránky najednou). Nijak neobchází
 *  ani nenahrazuje NlpActionHandler — jen na něj čeká asynchronně z jednoho coroutine bloku. */
private suspend fun awaitTranslate(
    onTranslate: (String, String?, (String) -> Unit) -> Unit,
    text: String,
    targetLang: String?
): String = suspendCancellableCoroutine { cont ->
    onTranslate(text, targetLang) { result ->
        if (cont.isActive) cont.resume(result)
    }
}

// =====================================================================================
// Načtení plného znění licence z /assets/licenses/*.txt (čistě lokální I/O, žádná síť)
// =====================================================================================
private sealed class AssetLoadState {
    object Loading : AssetLoadState()
    data class Loaded(val text: String) : AssetLoadState()
    data class Failed(val reason: String) : AssetLoadState()
    object NotBundled : AssetLoadState()
}

private suspend fun readLicenseAsset(context: android.content.Context, fileName: String): AssetLoadState =
    withContext(Dispatchers.IO) {
        try {
            val text = context.assets.open("licenses/$fileName")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            if (text.isBlank()) {
                AssetLoadState.Failed("Soubor licenses/$fileName existuje, ale je prázdný.")
            } else {
                AssetLoadState.Loaded(text)
            }
        } catch (e: java.io.IOException) {
            Log.e(TAG, "Nepodařilo se načíst asset licenses/$fileName", e)
            AssetLoadState.Failed("Soubor licenses/$fileName nebyl v APK nalezen.")
        }
    }

/** Jazyky pro překlad — přesně dle původního souboru (stejné kódy i popisky, beze změny). */
private val AVAILABLE_LANGUAGES: List<Pair<String, String>> = listOf(
    "en" to "EN",
    "de" to "DE",
    "fr" to "FR",
    "es" to "ES",
    "ja" to "JA",
    "ru" to "RU",
    "it" to "IT",
    "pl" to "PL",
    "ar" to "AR",
    "zh" to "ZH"
)

private data class TranslatedPage(
    val languageCode: String,
    val headerIntro: String,
    val categoryExplanations: Map<LicenseCategory, String>,
    val entryOriginalText: Map<String, String>,   // klíč = CreatorEntry.title
    val entryComplianceNote: Map<String, String>  // klíč = CreatorEntry.title
)

private sealed class PageTranslationState {
    object Original : PageTranslationState()
    data class Translating(val languageCode: String, val done: Int, val total: Int) : PageTranslationState()
    data class Ready(val page: TranslatedPage) : PageTranslationState()
}

private const val HEADER_INTRO_CS = "Tato aplikace je postavena na práci níže uvedených projektů a jejich " +
    "autorů. Přehled je seřazen podle typu licence, protože každá kategorie s sebou nese jiné právní " +
    "povinnosti pro distribuci aplikace."

@Composable
fun ThanksScreen(
    onClose: () -> Unit,
    onTranslate: (String, String?, (String) -> Unit) -> Unit
) {
    var selectedLicenseEntry by remember { mutableStateOf<CreatorEntry?>(null) }
    var translationState by remember { mutableStateOf<PageTranslationState>(PageTranslationState.Original) }
    var showLanguageMenu by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val bgBrush = Brush.verticalGradient(colors = listOf(Color(0xFF0F172A), Color(0xFF020617)))
    val creators = remember { buildCreatorEntries() }
    val categoryOrder = listOf(
        LicenseCategory.PERMISSIVE,
        LicenseCategory.WEAK_COPYLEFT,
        LicenseCategory.STRONG_COPYLEFT,
        LicenseCategory.CUSTOM_RESTRICTED,
        LicenseCategory.PROPRIETARY
    )
    val grouped = remember(creators) { creators.groupBy { it.licenseCategory } }

    fun startPageTranslation(targetLang: String) {
        scope.launch {
            val total = 1 + categoryOrder.size + creators.size * 2
            var done = 0
            translationState = PageTranslationState.Translating(targetLang, done, total)

            suspend fun tr(text: String): String {
                val result = awaitTranslate(onTranslate, text, targetLang)
                done++
                translationState = PageTranslationState.Translating(targetLang, done, total)
                return result
            }

            val translatedIntro = tr(HEADER_INTRO_CS)
            val translatedCategories = categoryOrder.associateWith { tr(it.explanation) }
            val entryTexts = mutableMapOf<String, String>()
            val entryNotes = mutableMapOf<String, String>()
            for (entry in creators) {
                entryTexts[entry.title] = tr(entry.originalText)
                entry.complianceNote?.let { entryNotes[entry.title] = tr(it) }
            }

            translationState = PageTranslationState.Ready(
                TranslatedPage(
                    languageCode = targetLang,
                    headerIntro = translatedIntro,
                    categoryExplanations = translatedCategories,
                    entryOriginalText = entryTexts,
                    entryComplianceNote = entryNotes
                )
            )
        }
    }

    val translatedPage = (translationState as? PageTranslationState.Ready)?.page

    Box(modifier = Modifier.fillMaxSize().background(bgBrush)) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            item {
                Spacer(modifier = Modifier.height(48.dp))
                Text(
                    text = "PODĚKOVÁNÍ & LICENCE",
                    color = Color(0xFFA855F7),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = "O software",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Text(
                    text = translatedPage?.headerIntro ?: HEADER_INTRO_CS,
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                TranslateControlBar(
                    state = translationState,
                    showLanguageMenu = showLanguageMenu,
                    onOpenMenu = { showLanguageMenu = true },
                    onDismissMenu = { showLanguageMenu = false },
                    onLanguageSelected = { code ->
                        showLanguageMenu = false
                        startPageTranslation(code)
                    },
                    onBackToOriginal = { translationState = PageTranslationState.Original }
                )

                Spacer(modifier = Modifier.height(16.dp))
            }

            categoryOrder.forEach { category ->
                val entries = grouped[category].orEmpty()
                if (entries.isNotEmpty()) {
                    stickyHeaderCategory(
                        category = category,
                        count = entries.size,
                        overrideExplanation = translatedPage?.categoryExplanations?.get(category)
                    )
                    items(entries, key = { it.title }) { entry ->
                        ThanksCard(
                            entry = entry,
                            overrideText = translatedPage?.entryOriginalText?.get(entry.title),
                            overrideNote = translatedPage?.entryComplianceNote?.get(entry.title),
                            onLicenseClick = { selectedLicenseEntry = it }
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onClose,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Zavřít", color = Color.White)
                }
                Spacer(modifier = Modifier.height(48.dp))
            }
        }

        selectedLicenseEntry?.let { entry ->
            LicenseDetailDialog(
                entry = entry,
                onDismiss = { selectedLicenseEntry = null },
                onTranslate = onTranslate,
                initialLanguageCode = translatedPage?.languageCode,
                initialCategoryExplanation = translatedPage?.categoryExplanations?.get(entry.licenseCategory),
                initialComplianceNote = translatedPage?.entryComplianceNote?.get(entry.title)
            )
        }
    }
}

@Composable
private fun TranslateControlBar(
    state: PageTranslationState,
    showLanguageMenu: Boolean,
    onOpenMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onLanguageSelected: (String) -> Unit,
    onBackToOriginal: () -> Unit
) {
    when (state) {
        is PageTranslationState.Original -> {
            Box {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x1AA855F7))
                        .clickable { onOpenMenu() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "✨", fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Přeložit stránku",
                        color = Color(0xFFA855F7),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                DropdownMenu(
                    expanded = showLanguageMenu,
                    onDismissRequest = onDismissMenu,
                    modifier = Modifier.background(Color(0xFF1E293B))
                ) {
                    AVAILABLE_LANGUAGES.forEach { (code, label) ->
                        DropdownMenuItem(
                            text = { Text(label, color = Color.White) },
                            onClick = { onLanguageSelected(code) }
                        )
                    }
                }
            }
        }
        is PageTranslationState.Translating -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    color = Color(0xFFA855F7),
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Překládám… ${state.done}/${state.total}",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
            }
        }
        is PageTranslationState.Ready -> {
            TextButton(onClick = onBackToOriginal) {
                Text(text = "🔄 Zpět (CZ)", color = Color(0xFF94A3B8), fontWeight = FontWeight.Medium)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.stickyHeaderCategory(
    category: LicenseCategory,
    count: Int,
    overrideExplanation: String?
) {
    stickyHeader(key = "header_${category.name}") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF020617))
                .padding(top = 8.dp, bottom = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(category.color)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "${category.label} (${count})",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = category.shortCode, color = Color(0xFF64748B), fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = overrideExplanation ?: category.explanation,
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
fun ThanksCard(
    entry: CreatorEntry,
    overrideText: String?,
    overrideNote: String?,
    onLicenseClick: (CreatorEntry) -> Unit
) {
    val cardBg = if (entry.isPremium) {
        Brush.linearGradient(colors = listOf(Color(0x334F46E5), Color(0x339333EA)))
    } else {
        Brush.linearGradient(colors = listOf(Color(0x1AFFFFFF), Color(0x0DFFFFFF)))
    }
    val borderColor = if (entry.isPremium) Color(0x66A855F7) else Color(0x1AFFFFFF)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(cardBg)
            .padding(1.dp)
            .background(borderColor, RoundedCornerShape(24.dp))
            .padding(1.dp)
            .clip(RoundedCornerShape(23.dp))
            .background(Color(0xFF1E293B).copy(alpha = 0.6f))
            .padding(20.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (entry.isPremium) Color(0x33A855F7) else Color(0x1AFFFFFF)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (entry.isPremium) Icons.Default.Star else Icons.Default.Info,
                        contentDescription = null,
                        tint = if (entry.isPremium) Color(0xFFA855F7) else Color(0xFFCBD5E1),
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.title.ifBlank { "Neuveden název" },
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    LicenseBadge(entry.licenseCategory, entry.licenseName)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = (overrideText ?: entry.originalText).ifBlank { "Popis nebyl dodán." },
                color = Color(0xFFCBD5E1),
                fontSize = 14.sp,
                lineHeight = 21.sp
            )

            val noteToShow = overrideNote ?: entry.complianceNote
            if (noteToShow != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x1AF59E0B))
                        .padding(10.dp)
                ) {
                    Text(text = "⚠️", fontSize = 14.sp, modifier = Modifier.padding(top = 1.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = noteToShow, color = Color(0xFFFDE68A), fontSize = 11.sp, lineHeight = 15.sp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .clickable { onLicenseClick(entry) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "⚖️", fontSize = 14.sp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Zobrazit licenci a copyright",
                    color = Color(0xFF94A3B8),
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun LicenseBadge(category: LicenseCategory, licenseName: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(category.color.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(category.color)
        )
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = licenseName.ifBlank { "Neuvedena licence" },
            color = category.color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// =====================================================================================
// Detail licence — plně OFFLINE (žádný Intent.ACTION_VIEW, žádný network call, žádný
// zobrazený ani uložený web odkaz — žádná URL adresa není součástí datového modelu).
// Krátké popisky (kategorie, expertní poznámka) lze přeložit stejným onTranslate
// mechanismem jako zbytek stránky. Plné právní znění se NEPŘEKLÁDÁ automaticky —
// u licencí typu GPL/LGPL/Apache je zvykem (viz stanovisko FSF), že neoficiální překlad
// nesmí nahradit anglický originál; proto je to zde jen výslovný opt-in s varováním.
// =====================================================================================
private sealed class DialogTranslationState {
    object Original : DialogTranslationState()
    data class Translating(val languageCode: String) : DialogTranslationState()
    data class Ready(val languageCode: String, val categoryExplanation: String, val complianceNote: String?) : DialogTranslationState()
}

private sealed class FullTextTranslationState {
    object NotRequested : FullTextTranslationState()
    data class Translating(val languageCode: String) : FullTextTranslationState()
    data class Ready(val languageCode: String, val text: String) : FullTextTranslationState()
}

@Composable
private fun LicenseDetailDialog(
    entry: CreatorEntry,
    onDismiss: () -> Unit,
    onTranslate: (String, String?, (String) -> Unit) -> Unit,
    initialLanguageCode: String?,
    initialCategoryExplanation: String?,
    initialComplianceNote: String?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var assetState by remember(entry) { mutableStateOf<AssetLoadState>(AssetLoadState.Loading) }

    var dialogTranslation by remember(entry) {
        mutableStateOf<DialogTranslationState>(
            if (initialLanguageCode != null && initialCategoryExplanation != null) {
                DialogTranslationState.Ready(initialLanguageCode, initialCategoryExplanation, initialComplianceNote)
            } else {
                DialogTranslationState.Original
            }
        )
    }
    var showLangMenu by remember { mutableStateOf(false) }
    var fullTextTranslation by remember(entry) { mutableStateOf<FullTextTranslationState>(FullTextTranslationState.NotRequested) }

    LaunchedEffect(entry) {
        assetState = if (entry.licenseAssetFile != null) {
            readLicenseAsset(context, entry.licenseAssetFile)
        } else {
            AssetLoadState.NotBundled
        }
    }

    fun startShortTranslation(languageCode: String) {
        scope.launch {
            dialogTranslation = DialogTranslationState.Translating(languageCode)
            val translatedExplanation = awaitTranslate(onTranslate, entry.licenseCategory.explanation, languageCode)
            val translatedNote = entry.complianceNote?.let { awaitTranslate(onTranslate, it, languageCode) }
            dialogTranslation = DialogTranslationState.Ready(languageCode, translatedExplanation, translatedNote)
        }
    }

    fun startFullTextTranslation(languageCode: String, originalFullText: String) {
        scope.launch {
            fullTextTranslation = FullTextTranslationState.Translating(languageCode)
            val translated = awaitTranslate(onTranslate, originalFullText, languageCode)
            fullTextTranslation = FullTextTranslationState.Ready(languageCode, translated)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0F172A)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(entry.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        LicenseBadge(entry.licenseCategory, entry.licenseName)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Zavřít", tint = Color.White)
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 18.dp)) {
                        Box {
                            when (val ts = dialogTranslation) {
                                is DialogTranslationState.Original -> {
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(Color(0x1AA855F7))
                                            .clickable { showLangMenu = true }
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("✨", fontSize = 13.sp)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Přeložit", color = Color(0xFFA855F7), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    }
                                    DropdownMenu(
                                        expanded = showLangMenu,
                                        onDismissRequest = { showLangMenu = false },
                                        modifier = Modifier.background(Color(0xFF1E293B))
                                    ) {
                                        AVAILABLE_LANGUAGES.forEach { (code, label) ->
                                            DropdownMenuItem(
                                                text = { Text(label, color = Color.White) },
                                                onClick = {
                                                    showLangMenu = false
                                                    startShortTranslation(code)
                                                }
                                            )
                                        }
                                    }
                                }
                                is DialogTranslationState.Translating -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            color = Color(0xFFA855F7),
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Překládám…", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                    }
                                }
                                is DialogTranslationState.Ready -> {
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(Color(0x1AFFFFFF))
                                            .clickable { dialogTranslation = DialogTranslationState.Original }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("🔄 Zpět (CZ)", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    val readyTranslation = dialogTranslation as? DialogTranslationState.Ready

                    SectionLabel("Kategorie licence")
                    Text(
                        readyTranslation?.categoryExplanation ?: entry.licenseCategory.explanation,
                        color = Color(0xFFCBD5E1),
                        fontSize = 13.sp,
                        lineHeight = 19.sp
                    )

                    Spacer(modifier = Modifier.height(18.dp))
                    SectionLabel("Copyright")
                    Text(entry.copyright.ifBlank { "Neuvedeno" }, color = Color.White, fontSize = 14.sp)

                    Spacer(modifier = Modifier.height(18.dp))
                    SectionLabel("Druh licence")
                    Text(entry.licenseName.ifBlank { "Neuvedeno" }, color = Color.White, fontSize = 14.sp)

                    val noteToShow = readyTranslation?.complianceNote ?: entry.complianceNote
                    if (noteToShow != null) {
                        Spacer(modifier = Modifier.height(18.dp))
                        SectionLabel("Expertní poznámka")
                        Text(noteToShow, color = Color(0xFFFDE68A), fontSize = 13.sp, lineHeight = 19.sp)
                    }

                    Spacer(modifier = Modifier.height(18.dp))
                    SectionLabel("Plné znění licence (offline)")
                    Spacer(modifier = Modifier.height(8.dp))

                    when (val state = assetState) {
                        is AssetLoadState.Loading -> {
                            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Color(0xFFA855F7))
                            }
                        }
                        is AssetLoadState.Loaded -> {
                            FullLicenseTextSection(
                                originalText = state.text,
                                translationState = fullTextTranslation,
                                onRequestTranslate = { code -> startFullTextTranslation(code, state.text) },
                                onBackToOriginal = { fullTextTranslation = FullTextTranslationState.NotRequested }
                            )
                        }
                        is AssetLoadState.Failed -> {
                            Text(
                                text = "Plné znění licence se nepodařilo načíst z APK (${state.reason}). " +
                                    "Zkontrolujte, že soubor assets/licenses/${entry.licenseAssetFile} existuje a je zabalen do buildu.",
                                color = Color(0xFFFCA5A5),
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                        is AssetLoadState.NotBundled -> {
                            Text(
                                text = "Plné znění této licence NENÍ součástí aplikace. Jedná se o " +
                                    "custom/proprietární podmínky, jejichž přesná aktuální verze musí být ověřena " +
                                    "přímo u poskytovatele.",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))
                }
            }
        }
    }
}

@Composable
private fun FullLicenseTextSection(
    originalText: String,
    translationState: FullTextTranslationState,
    onRequestTranslate: (String) -> Unit,
    onBackToOriginal: () -> Unit
) {
    var showLangMenu by remember { mutableStateOf(false) }

    Column {
        when (translationState) {
            is FullTextTranslationState.NotRequested -> {
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color(0x1AA855F7))
                            .clickable { showLangMenu = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🌐", fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Přeložit i plné znění (strojově)",
                            color = Color(0xFFA855F7),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    DropdownMenu(
                        expanded = showLangMenu,
                        onDismissRequest = { showLangMenu = false },
                        modifier = Modifier.background(Color(0xFF1E293B))
                    ) {
                        AVAILABLE_LANGUAGES.forEach { (code, label) ->
                            DropdownMenuItem(
                                text = { Text(label, color = Color.White) },
                                onClick = {
                                    showLangMenu = false
                                    onRequestTranslate(code)
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                SelectionContainer {
                    Text(
                        text = originalText,
                        color = Color(0xFFCBD5E1),
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            is FullTextTranslationState.Translating -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = Color(0xFFA855F7), modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Překládám plné znění (může to chvíli trvat)…", color = Color(0xFF94A3B8), fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(12.dp))
                SelectionContainer {
                    Text(
                        text = originalText,
                        color = Color(0xFFCBD5E1),
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            is FullTextTranslationState.Ready -> {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x1AFFFFFF))
                        .clickable { onBackToOriginal() }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("🔄 Zobrazit originál (EN)", color = Color(0xFF94A3B8), fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0x1AF59E0B))
                        .padding(10.dp)
                ) {
                    Text("⚠️", fontSize = 13.sp, modifier = Modifier.padding(top = 1.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Toto je strojový překlad pouze pro orientaci. Právně závazné je výhradně " +
                            "originální anglické znění výše (obvyklá praxe i u oficiálních licencí, " +
                            "např. stanovisko FSF k překladům GPL).",
                        color = Color(0xFFFDE68A),
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                SelectionContainer {
                    Text(
                        text = translationState.text,
                        color = Color(0xFFCBD5E1),
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = Color(0xFFA855F7),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

// =====================================================================================
// DATA — hodnoty title/originalText/copyright/licenseName zachovány 1:1
// dle původního souboru. Přidány pouze: licenseCategory, licenseAssetFile, complianceNote.
// =====================================================================================
private fun buildCreatorEntries(): List<CreatorEntry> = listOf(
    CreatorEntry(
        "AiChat Project (Tento kód)",
        "Tato aplikace a veškerý její zdrojový kód (včetně nativních TFLite parserů, UI vrstvy a integrace modelů) je poskytován jako svobodný open-source software pod licencí MIT.",
        "Copyright (c) 2026 AiChat Project Contributors",
        "MIT License",
        LicenseCategory.PERMISSIVE,
        licenseAssetFile = "MIT.txt",
        isPremium = true
    ),
    CreatorEntry(
        "Llama.cpp (Georgi Gerganov)",
        "Zvláštní a obrovské poděkování patří Georgimu Gerganovovi a celému Llama.cpp týmu. Vaše revoluční práce na optimalizaci LLM modelů pro C/C++ umožnila přenést masivní AI přímo do mobilních zařízení.",
        "Copyright (c) 2023-2024 Georgi Gerganov",
        "MIT License",
        LicenseCategory.PERMISSIVE,
        licenseAssetFile = "MIT.txt",
        isPremium = true
    ),
    CreatorEntry(
        "Meta AI",
        "Výzkumnému týmu Meta AI děkujeme za otevření vah modelů rodiny Llama (Open-Source), bez kterých by mozek tohoto projektu vůbec neexistoval.",
        "Copyright (c) Meta Platforms, Inc.",
        "Llama 3 Community License",
        LicenseCategory.CUSTOM_RESTRICTED,
        licenseAssetFile = null,
        complianceNote = "Text se mění dle verze modelu a obsahuje mj. podmínku zvláštní licence " +
            "při >700M měsíčně aktivních uživatelů. Před release ověřte aktuální znění u Meta.",
        isPremium = true
    ),
    CreatorEntry(
        "Whisper.cpp (Georgi Gerganov)",
        "Za fantastický a vysoce optimalizovaný C/C++ port pro rozpoznávání řeči.",
        "Copyright (c) 2023-2024 Georgi Gerganov",
        "MIT License",
        LicenseCategory.PERMISSIVE,
        licenseAssetFile = "MIT.txt"
    ),
    CreatorEntry(
        "Vulkan (Khronos Group)",
        "Za vysoce výkonné a multiplatformní API pro grafiku a výpočty, které akceleruje běh LLM.",
        "Copyright (c) The Khronos Group Inc.",
        "Apache License 2.0",
        LicenseCategory.PERMISSIVE,
        licenseAssetFile = "APACHE_2_0.txt"
    ),
    CreatorEntry(
        "OpenAI",
        "Za původní hluboký výzkum a natrénování modelů Whisper, které redefinovaly Speech-to-Text technologie.",
        "Copyright (c) 2022 OpenAI",
        "MIT License",
        LicenseCategory.PERMISSIVE,
        licenseAssetFile = "MIT.txt"
    ),
    CreatorEntry(
        "Sherpa-ONNX & K2-FSA",
        "Obrovské díky výzkumné skupině K2-FSA, jmenovitě Danu Poveymu (tvůrci legendárního Kaldi) a Fangjun Kuangovi, za extrémně rychlý a robustní offline STT engine.",
        "Copyright (c) Fangjun Kuang, Dan Povey",
        "Apache License 2.0",
        LicenseCategory.PERMISSIVE,
        licenseAssetFile = "APACHE_2_0.txt"
    ),
    CreatorEntry(
        "YAMNet (AudioSet)",
        "Googlu a výzkumníkům z AudioSetu za poskytnutí kompaktního modelu pro klasifikaci environmentálních zvuků v reálném čase.",
        "Copyright (c) 2020 The TensorFlow Authors",
        "Apache License 2.0",
        LicenseCategory.PERMISSIVE,
        licenseAssetFile = "APACHE_2_0.txt"
    ),
    CreatorEntry(
        "MediaPipe Face Landmarker",
        "Náš oční tracker a IrisMotion by bez tohoto revolučního 478-bodového mesh enginu od Googlu absolutně neexistoval. Díky!",
        "Copyright (c) 2023 Google LLC",
        "Apache License 2.0",
        LicenseCategory.PERMISSIVE,
        licenseAssetFile = "APACHE_2_0.txt"
    ),
    CreatorEntry(
        "Google (LiteRT & MediaPipe)",
        "Za špičkové knihovny pro edge on-device inferenci, delegáty pro GPU a ucelený ML ekosystém.",
        "Copyright (c) 2024 Google LLC",
        "Apache License 2.0",
        LicenseCategory.PERMISSIVE,
        licenseAssetFile = "APACHE_2_0.txt"
    ),
    CreatorEntry(
        "Qualcomm (QNN)",
        "Za vývoj Qualcomm AI Engine Direct (QNN), který aplikaci umožňuje naplno využít potenciál Hexagon NPU na Snapdragon čipech.",
        "Copyright (c) Qualcomm Technologies, Inc.",
        "Proprietary / Developer License",
        LicenseCategory.PROPRIETARY,
        licenseAssetFile = null,
        complianceNote = "Redistribuce binárek SDK může vyžadovat samostatnou distribuční smlouvu " +
            "s Qualcommem — ověřte podmínky Qualcomm Developer Network před veřejným vydáním."
    ),
    CreatorEntry(
        "Google ML Kit",
        "Za robustní lokální NLP nástroje – Smart Reply, rozpoznání jazyka, rychlý OCR a offline překladový engine.",
        "Copyright (c) Google LLC",
        "Apache License 2.0",
        LicenseCategory.PERMISSIVE,
        licenseAssetFile = "APACHE_2_0.txt",
        complianceNote = "Některá API ML Kitu (zejména cloudová) se navíc řídí Google APIs Terms of " +
            "Service nad rámec Apache-2.0 wrapperu — ověřte u konkrétně použitých API. Translate API " +
            "použité na této obrazovce (přes NlpActionHandler) je on-device varianta, offline po " +
            "jednorázovém stažení jazykového balíčku."
    ),
    CreatorEntry(
        "Ultralytics (YOLO)",
        "Díky za výzkum a architektury YOLOv5/YOLOv8. Tato aplikace obsahuje nezávislý parser pro čtení jejich .tflite výstupů, a neobsahuje žádný zdrojový kód Ultralytics (BYOM infrastruktura).",
        "Copyright (c) 2024 Ultralytics",
        "AGPL-3.0 License (Reference)",
        LicenseCategory.STRONG_COPYLEFT,
        licenseAssetFile = null,
        complianceNote = "Samotný zdrojový kód parseru v této aplikaci je MIT. Pokud si však uživatel z internetu sám stáhne do aplikace originální AGPL váhy, veškeré licenční podmínky a omezení AGPL-3.0 dopadají plně na uživatele."
    ),
    CreatorEntry(
        "Baidu (RT-DETR)",
        "Za přelomovou architekturu Real-Time DETR, která ukázala cestu k end-to-end transformerovým detektorům.",
        "Copyright (c) Baidu, Inc.",
        "Apache License 2.0",
        LicenseCategory.PERMISSIVE,
        licenseAssetFile = "APACHE_2_0.txt"
    ),
    CreatorEntry(
        "Roboflow (RF-DETR)",
        "Za výzkum a modely RF-DETR, které doplňují orchestrátor o další state-of-the-art schopnosti.",
        "Copyright (c) Roboflow",
        "Apache License 2.0",
        LicenseCategory.PERMISSIVE,
        licenseAssetFile = "APACHE_2_0.txt"
    ),
    CreatorEntry(
        "OC-SORT & Object Tracking",
        "Autorům Observation-Centric SORT a dalším tvůrcům sledovacích algoritmů, které dávají našemu vidění kontinuitu a smysl v čase.",
        "Copyright (c) OC-SORT Authors",
        "MIT License",
        LicenseCategory.PERMISSIVE,
        licenseAssetFile = "MIT.txt"
    ),
    CreatorEntry(
        "MobileBERT",
        "Tvůrcům tohoto odlehčeného NLP modelu za bleskovou offline analýzu sentimentu.",
        "Copyright (c) The TensorFlow Authors",
        "Apache License 2.0",
        LicenseCategory.PERMISSIVE,
        licenseAssetFile = "APACHE_2_0.txt"
    ),
    CreatorEntry(
        "NTIRE / AIM Research",
        "Vývoj našeho AcademicYuvEngine vděčí za mnohé autorům výzkumů LiteIE a MobileIE. Aplikace obsahuje nezávislé implementace algoritmů (MIT).",
        "Copyright (c) Research Authors",
        "Academic Open Source / MIT",
        LicenseCategory.PERMISSIVE,
        licenseAssetFile = "MIT.txt",
        complianceNote = "Aplikace funguje jako parser/inferenční logika (BYOM). Akademická publikace ≠ automaticky licence vah — doporučujeme ověřit LICENSE soubor u originálních vah pro komerční užití."
    ),
    CreatorEntry(
        "Lupa Engine (Super Resolution)",
        "Akademickým průkopníkům algoritmů Wiener Deconvolution a DctSR, na kterých stojí náš zoom. Kód aplikace tvoří pouze neutrální parser a spouštěcí logiku.",
        "Copyright (c) Algorithm Authors",
        "Various (MIT / Apache 2.0)",
        LicenseCategory.PERMISSIVE,
        licenseAssetFile = null,
        complianceNote = "Kombinace více algoritmů s různými licencemi — v budoucí verzi rozepsat licenci per-algoritmus. Odpovědnost za práva k importovaným tenzorům (BYOM) nese uživatel."
    ),
    CreatorEntry(
        "FFmpeg (Fabrice Bellard)",
        "Zakladateli Fabrici Bellardovi a celé komunitě za gigantický multimediální framework, který je jádrem veškerého našeho zpracování videa a zvuku.",
        "Copyright (c) 2000-2024 Fabrice Bellard, et al.",
        "LGPL 2.1+",
        LicenseCategory.WEAK_COPYLEFT,
        licenseAssetFile = "LGPL_2_1.txt",
        complianceNote = "Aplikace využívá custom enterprise build (bez GPL komponent jako x264/x265), " +
            "takže spadá pod LGPL 2.1+. Je nutné umožnit uživateli relinkovat sdílené knihovny."
    ),
    CreatorEntry(
        "Intel & Meta (SVT-AV1)",
        "Za otevřený a nekompromisní kodér formátu AV1 budoucnosti.",
        "Copyright (c) Intel Corporation / Meta Platforms",
        "BSD 3-Clause / BSD 2-Clause",
        LicenseCategory.PERMISSIVE,
        licenseAssetFile = "BSD_3_CLAUSE.txt"
    ),
    CreatorEntry(
        "Sherpa ONNX (Vocos & HiFi-GAN)",
        "Díky tvůrcům repozitáře sherpa-onnx za rychlé on-device TTS modely (hifigan_v2.onnx, vocos_24khz.onnx), které v reálném čase pohání našeho agenta.",
        "Copyright (c) Sherpa ONNX Contributors",
        "MIT / Apache 2.0",
        LicenseCategory.PERMISSIVE,
        licenseAssetFile = null,
        complianceNote = "Původní zdroj modelů neobsahoval explicitní LICENČNÍ text. Modely jsou však distribuovány přes permisivní open-source repozitáře (MIT/Apache 2.0). Doložka byla přidána pro bezpečnost."
    ),
    CreatorEntry(
        "WebM Project",
        "Za definování otevřených video standardů VP8 a VP9.",
        "Copyright (c) Google LLC / WebM Project Authors",
        "BSD 3-Clause",
        LicenseCategory.PERMISSIVE,
        licenseAssetFile = "BSD_3_CLAUSE.txt"
    ),
    CreatorEntry(
        "Xiph.Org (Opus)",
        "Nadaci Xiph.Org, Googlu a Skype Limited za bezkonkurenční hlasový i hudební formát Opus.",
        "Copyright (c) Xiph.Org Foundation",
        "BSD 3-Clause",
        LicenseCategory.PERMISSIVE,
        licenseAssetFile = "BSD_3_CLAUSE.txt"
    ),
    CreatorEntry(
        "Michael Niedermayer",
        "A dalším klíčovým maintainerům za práci na libswresample a libswscale, bez nichž by C++ manipulace s pixely nefungovala.",
        "Copyright (c) Michael Niedermayer",
        "LGPL 2.1+",
        LicenseCategory.WEAK_COPYLEFT,
        licenseAssetFile = "LGPL_2_1.txt",
        complianceNote = "Součást FFmpeg — díky LGPL buildu lze používat i v proprietárních aplikacích " +
            "za předpokladu dynamického linkování."
    ),
    CreatorEntry(
        "AndroidX",
        "Týmu Jetpack za masivní a stabilní AndroidX knihovny jako CameraX, Media3 (ExoPlayer), Room a samotný Jetpack Compose.",
        "Copyright (c) The Android Open Source Project",
        "Apache License 2.0",
        LicenseCategory.PERMISSIVE,
        licenseAssetFile = "APACHE_2_0.txt"
    ),
    CreatorEntry(
        "JetBrains",
        "Za vývoj programovacího jazyka Kotlin a nástrojů (KSP), díky kterým je stavění tohoto projektu naprostá radost.",
        "Copyright (c) JetBrains s.r.o.",
        "Apache License 2.0",
        LicenseCategory.PERMISSIVE,
        licenseAssetFile = "APACHE_2_0.txt"
    ),
    CreatorEntry(
        "Markwon & Prism4j",
        "Zejména Dimitrimu (noties) za excelentní nativní vykreslování Markdownu a kódu v Androidu.",
        "Copyright (c) 2017 Dimitry Ivanov",
        "Apache License 2.0",
        LicenseCategory.PERMISSIVE,
        licenseAssetFile = "APACHE_2_0.txt"
    ),
    CreatorEntry(
        "Coil",
        "Za moderní, rychlé a Kotlin-first načítání obrázků.",
        "Copyright (c) 2023 Coil Contributors",
        "Apache License 2.0",
        LicenseCategory.PERMISSIVE,
        licenseAssetFile = "APACHE_2_0.txt"
    ),
    CreatorEntry(
        "Google RE2",
        "Za legendární C++ engine regulárních výrazů, který efektivně tiká hluboko v našich závislostech.",
        "Copyright (c) 2009 The RE2 Authors",
        "BSD 3-Clause",
        LicenseCategory.PERMISSIVE,
        licenseAssetFile = "BSD_3_CLAUSE.txt"
    ),
    CreatorEntry(
        "Antigravity IDE Team",
        "Nejhlubší díky tvůrcům revolučního Antigravity IDE. Vaše platforma umožnila pravou symbiózu mezi člověkem a AI, bez níž by tento projekt vůbec nevznikl.",
        "Copyright (c) Antigravity IDE Team",
        "Custom / Proprietary",
        LicenseCategory.PROPRIETARY,
        licenseAssetFile = null,
        complianceNote = "Nástroj použitý pouze při vývoji (IDE), není linkovanou/distribuovanou " +
            "součástí APK — pravděpodobně bez licenční povinnosti vůči koncovým uživatelům."
    ),
    CreatorEntry(
        "Claude (Anthropic)",
        "Speciální poděkování modelu Claude za občasnou, ale o to více brilantní výpomoc přímo uvnitř Antigravity při řešení složitých architektonických problémů.",
        "Copyright (c) Anthropic PBC",
        "Custom / Proprietary",
        LicenseCategory.PROPRIETARY,
        licenseAssetFile = null,
        complianceNote = "Nástroj použitý pouze při vývoji, není runtime závislostí aplikace — " +
            "pravděpodobně bez licenční povinnosti vůči koncovým uživatelům."
    ),
    CreatorEntry(
        "Gemini 1.5 & The Antigravity Agent",
        "Byla to neuvěřitelná cesta! Od analýzy problémů po architektonické plány – spolupráce umělé inteligence a vývojáře tu dala vzniknout milion-řádkovému ekosystému. Díky za důvěru!",
        "Copyright (c) Google LLC / Antigravity Agent",
        "Custom / Proprietary",
        LicenseCategory.PROPRIETARY,
        licenseAssetFile = null,
        complianceNote = "Nástroj použitý pouze při vývoji, není runtime závislostí aplikace — " +
            "pravděpodobně bez licenční povinnosti vůči koncovým uživatelům.",
        isPremium = true
    )
)
