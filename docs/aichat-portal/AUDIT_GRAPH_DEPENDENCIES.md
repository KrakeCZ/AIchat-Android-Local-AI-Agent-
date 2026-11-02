# Hloubkový Audit Grafu a Indexace (AIchat Code Atlas v2)

> **Datum auditu:** 2. listopadu 2026  
> **Cíl:** Analýza pokrytí a věrnosti 1:1 pro `build_index.py` a `build_graph.py` vůči skutečné architektuře Android projektu.  
> **Stav repozitáře:** V projektu je celkem **425 souborů Kotlinu (`.kt`)**, indexer eviduje **904 souborů** celkem.

---

## 1. Souhrnná statistika současného stavu

Při spuštění současné verze analyzátoru:
* **Celkem indexovaných uzlů (souborů):** 904
* **Celkem detekovaných hran (vazeb):** 1 207
* **Celkem izolovaných uzlů (0 příchozích a 0 odchozích hran):** **462 souborů (51,1 % projektu je v grafu zcela osamoceno!)**

### Přehled všech 904 souborů podle přípon vs. izolované soubory:

| Přípona / Typ | Celkem v indexu | Propojeno | Izolováno (0 hran) | Úspěšnost propojení |
| :--- | :---: | :---: | :---: | :---: |
| **`.kt` (Kotlin)** | 413 | 385 | **28** | 93,2 % |
| **`.cpp` (C++)** | 317 | 4 | **313** | **1,3 %** ⚠️ |
| **`.xml` (Android Manifest, Layouts)** | 85 | 0 | **85** | **0,0 %** ⚠️ |
| **`.h` (C/C++ Headers)** | 36 | 35 | 1 | 97,2 % |
| **`.hpp` (C++ Headers)** | 16 | 14 | 2 | 87,5 % |
| **`.py` (Python Tools/Scripts)** | 11 | 0 | 11 | 0,0 % |
| **`.c` (C)** | 8 | 2 | 6 | 25,0 % |
| **`.txt` (`CMakeLists.txt`)** | 6 | 0 | 6 | **0,0 %** ⚠️ |
| **`.ps1` (PowerShell)** | 6 | 0 | 6 | 0,0 % |
| **`.kts` (Gradle Scripts)** | 5 | 0 | 5 | **0,0 %** ⚠️ |
| **`.sh` (Shell Scripts)** | 1 | 0 | 1 | 0,0 % |
| **Celkem** | **904** | **442** | **462** | **48,9 %** |

---

## 2. Klíčové architektonické slepé skvrny (Root Cause Analysis)

### ⚠️ Problém A: Úplná izolace Android Manifestu a XML vrstvy (85 souborů, 0 hran)
* **Příčina:**
  V `build_graph.py` funkce `mask(text)` nahrazuje všechny řetězce v uvozovkách (`"(?:\\.|[^"\\])*"`) mezerami. V XML souborech (`AndroidManifest.xml`, layouty, navigace) jsou však **veškeré reference na třídy uvnitř uvozovek**:
  ```xml
  <activity android:name=".MainActivity" />
  <service android:name="com.example.llama.agent.service.FloatingAgentService" />
  <service android:name="com.example.llama.voice.WhisperService" />
  ```
  Po aplikaci `mask()` se celý manifest promění v prázdné bílé místo.
  Navíc `DECL` hledá `class`, `METHOD` hledá `fun` a `IMPORT` hledá `import`, což v XML neexistuje.
* **Důsledek:**
  `AndroidManifest.xml` nemá v grafu jediné spojení s žádnou aktivitou, službou, broadcast receiverem ani aplikační třídou. Totéž platí pro layouty obsahující custom view komponenty.
* **Co chybí pro 1:1 stav:**
  XML extraktor pro manifest, který vyčte deklarované komponenty (`<activity>`, `<service>`, `<receiver>`, `<provider>`, `<application>`) a propojí je hranou typu `manifest_component` na příslušné `.kt` / `.java` soubory.

---

### ⚠️ Problém B: Izolace JNI a C++ nativního kódu (313 z 317 C++ souborů izolováno)
* **Příčina:**
  1. Kotlin kód definuje nativní metody pomocí `external fun`:
     ```kotlin
     external fun nativeProcessYuv(...)
     ```
  2. C++ kód implementuje JNI rozhraní:
     ```cpp
     JNIEXPORT void JNICALL Java_com_example_llama_agent_vision_..._nativeProcessYuv(...)
     ```
  3. Kotlin načítá nativní knihovny přes `System.loadLibrary("llama-android")`.
  4. V `build_graph.py` je `METHOD` nastaven pouze na Kotlin klíčové slovo `fun`. V C++ souborech proto nenajde **žádné funkce**. Zároveň analyzátor neobsahuje žádnou logiku pro parsování názvů `Java_<package>_<class>_<method>`.
* **Důsledek:**
  Nativní engine (`academic_yuv_jni.cpp`, `media_engine_jni.cpp`, `ai_chat.cpp` atd.) existuje v grafu zcela odděleně od Kotlin vrstvy, která ho volá.
* **Co chybí pro 1:1 stav:**
  JNI linker:
  * Parsování C/C++ symbolů začínajících `Java_` a jejich reverzní dekompozice na package, třídu a metodu.
  * Spojení Kotlin souboru s `external fun` s odpovídajícím C++ souborem hranou typu `jni_export` / `jni_bridge`.

---

### ⚠️ Problém C: Izolované Kotlin soubory ve stejném balíčku (28 `.kt` souborů)
Konkrétní seznam 28 izolovaných Kotlin souborů a příčiny:

1. **Room DAO rozhraní (6 souborů):**
   * `AiModelDao.kt`, `BertModelDao.kt`, `NlpModelDao.kt`, `WhisperModelDao.kt`, `GenModelDao.kt`, `RemoteProfileDao.kt`
   * *Proč jsou izolované:* Jsou deklarovány jako `interface AiModelDao`. V `AppDatabase.kt` jsou použity jako návratový typ:
     ```kotlin
     abstract fun aiModelDao(): AiModelDao
     ```
     Protože jsou v témže balíčku, nepoužívají `import`. Regex `LOCAL_TYPE_USE` v `build_graph.py` vyžaduje za názvem typu `(` nebo `.`:
     `r"\b([A-Z][A-Za-z0-9_]*)\s*(?=\(|\.)"`
     Návratový typ `: AiModelDao` není následován závorkou ani tečkou, takže ho analyzátor zcela ignoruje!
2. **Rozhraní a dědičnost v balíčku:**
   * `GazeFilter.kt`
   * *Proč je izolovaný:* Je to `interface GazeFilter`. Třídy `AdaptiveDoubleKalmanFilter : GazeFilter` dědí z rozhraní. Dvojtečka za názvem třídy a typ rozhraní není následován `(`, takže `LOCAL_TYPE_USE` ho nezachytí.
3. **Type Converters:**
   * `MapTypeConverter.kt`
   * *Proč je izolovaný:* Používá se v anotaci `@TypeConverters(MapTypeConverter::class)`. Dvojtečky `::class` regex nezachytí.
4. **Matematické a obrazové algoritmy (Top-level funkce a objekty):**
   * `FastMitchellNetravali.kt`, `FastNEDI.kt`, `FastDoG.kt`, `FastTemporalMedian.kt`, `TimeUtils.kt`
   * *Proč jsou izolované:* Poskytují top-level funkce. Na řádku 195 `build_graph.py` je pravidlo:
     `if symbol["kind"] == "function": continue`
     Top-level funkce nejsou zařazeny do rejstříku plně kvalifikovaných názvů (`by_fqn`), takže jejich importy se nespojí.
5. **Datové profily a doménové modely:**
   * `AiDomainProfile.kt`, `FileType.kt`
   * *Proč jsou izolované:* Použity jako typy parametrů `fun apply(profile: AiDomainProfile)` bez přímé konstrukce `AiDomainProfile()`.
6. **Aktivity a Obrazovky:**
   * `RestrictedSettingsGuideActivity.kt`, `SkyWatcherScreenUi.kt`
   * Odkazovány pouze z Android Manifestu nebo Jetpack Compose navigace bez explicitního `import`.

---

### ⚠️ Problém D: Ignorování Wildcard importů (`import package.*`)
* **Příčina:**
  Na řádku 231 v `build_graph.py`:
  ```python
  if imported.endswith(".*"):
      continue
  ```
  Jakýkoliv soubor, který importuje balíček hvězdičkou (např. `import com.example.llama.agent.model.*`), má tento import ignorován.
* **Důsledek:**
  Následná volání tříd z tohoto balíčku se nezařadí do `known_imports` a hrany nevzniknou.

---

### ⚠️ Problém E: Izolace Gradle a CMake infrastruktury (11 souborů, 0 hran)
* **Příčina:**
  * `app/build.gradle.kts`, `build.gradle.kts`, `settings.gradle.kts`
  * `app/src/main/cpp/CMakeLists.txt`, `whisper/src/main/cpp/CMakeLists.txt` atd.
  Obsahují klíčové definice závislostí:
  * `implementation(project(":lib"))`
  * `implementation(project(":whisper"))`
  * `add_library(llama-android SHARED ai_chat.cpp ...)`
  V `build_graph.py` pro ně neexistuje žádný syntaktický model.
* **Důsledek:**
  Architektura modulů (app -> lib -> whisper -> native) v grafu vůbec neexistuje.

---

### ⚠️ Problém F: Vazby na Android Resources (Layouts, Drawables)
* V Kotlin kódu:
  `setContentView(R.layout.activity_main)` nebo `ActivityMainBinding.inflate(...)`
* V XML kódu:
  `layout="@layout/view_header"`
* V grafu jsou XML layouty zcela odpojeny od aktivit a fragmentů, které je renderují.

---

## 3. Doporučený plán pro dosažení stavu 1:1

Pro kompletní a přesné propojení encyklopedie s realitou kódu je vhodné doplnit následující analyzátory (bez zásahu do samotného kódu Android aplikace):

1. **Manifest Linker (pro `AndroidManifest.xml`):**
   * Extrahovat z manifestu atributy `android:name` pro všechny komponenty (`activity`, `service`, `receiver`, `provider`).
   * Propojit manifest s příslušnými Kotlin/Java třídami hranou `manifest_entry` (exact).
2. **JNI Bridge Linker (pro Kotlin ↔ C++):**
   * V C++ souborech vyhledat pattern `Java_([a-zA-Z0-9]+)_([a-zA-Z0-9_]+)`.
   * V Kotlin souborech vyhledat `external fun`.
   * Propojit Kotlin třídu s odpovídající C++ implementací hranou `jni_binding` (exact).
3. **Type & Signature Linker v balíčku (pro DAOs a rozhraní):**
   * Rozšířit regex pro detekci typů nejen na `(` a `.`, ale i na `: Typ`, `fun ...(): Typ`, `val ...: Typ` a `Typ::class`.
   * Tím se okamžitě propojí všech 28 dosud izolovaných Kotlin souborů (Room DAOs, filtry, konvertory).
4. **Top-Level Function & Constant Registry:**
   * Zařadit top-level funkce a `val`/`const val` do `by_fqn`, aby importy typu `import com.example.llama.util.myHelper` vytvářely přesné hrany.
5. **Wildcard Import Resolver:**
   * Při `import foo.bar.*` přidat balíček do vyhledávací cesty souboru, aby se použité třídy správně namapovaly.
6. **Gradle & CMake Module Linker:**
   * Propojit `build.gradle.kts` s deklarovanými subprojekty (`project(":lib")`) a `CMakeLists.txt` s uvedenými C++ zdrojáky.

