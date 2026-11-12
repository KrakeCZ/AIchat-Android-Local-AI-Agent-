<#
.SYNOPSIS
    Deep Clean Script pro Android Project [Critical Infrastructure Edition]
    
.DESCRIPTION
    Provádí bezpečné a hloubkové čištění build artefaktů (Gradle, CXX).
    Implementuje:
    - Strict Mode & Error Handling
    - Gradle Daemon Stop (prevence File Lock)
    - Validaci CWD (Current Working Directory)
    - Diagnostiku prostředí (JAVA_HOME)
    
.NOTES
    Author: Antigravity (DeepWriteShell Mode)
    Target: Windows PowerShell / PowerShell Core
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# --- Barvičky a Utility ---
function Log-Info ($Message) { Write-Host "[INFO] $Message" -ForegroundColor Cyan }
function Log-Success ($Message) { Write-Host "[OK] $Message" -ForegroundColor Green }
function Log-Warn ($Message) { Write-Host "[WARN] $Message" -ForegroundColor Yellow }
function Log-Error ($Message) { Write-Host "[ERROR] $Message" -ForegroundColor Red }

# --- 1. Validace Kontextu (Context Integrity) ---
Log-Info "Zahajuji Deep Clean proceduru..."
Log-Info "CWD: $(Get-Location)"

if (-not ((Test-Path "build.gradle.kts") -or (Test-Path "build.gradle"))) {
    Log-Error "Kritická chyba: Skript neběží v kořenu Android projektu!"
    Log-Error "Nenalezen soubor build.gradle(.kts). Ukončuji."
    exit 1
}

# --- 2. Diagnostika Prostředí ---
if ($env:JAVA_HOME) {
    Log-Info "JAVA_HOME detekován: $env:JAVA_HOME"
}
else {
    Log-Warn "JAVA_HOME není nastaven! Gradle může selhat, pokud nenajde JDK."
}

# --- 3. Gradle Management (Safe Stop) ---
if (Test-Path ".\gradlew.bat") {
    Log-Info "Zastavuji běžící Gradle Daemony pro uvolnění zámků souborů..."
    try {
        Start-Process -FilePath ".\gradlew.bat" -ArgumentList "--stop" -NoNewWindow -Wait
        Log-Success "Gradle Daemony zastaveny (nebo neběžely)."
    }
    catch {
        Log-Warn "Nepodařilo se gracefuly zastavit Gradle. Pokračuji, ale může dojít k 'File Locked' chybám."
        Log-Warn "Detail: $_"
    }
}
else {
    Log-Warn "Gradle wrapper (gradlew.bat) nenalezen. Přeskakuji 'gradle --stop'."
}

# --- 4. Funkce Bezpečného Mazání (Robust Deletion) ---
function Remove-SafeItem {
    param (
        [string]$Path,
        [string]$Description
    )

    if (Test-Path $Path) {
        Write-Host -NoNewline "[CLEAN] Mazání $Description ($Path)... "
        try {
            Remove-Item -Path $Path -Recurse -Force -ErrorAction Stop
            Write-Host "OK" -ForegroundColor Green
        }
        catch {
            Write-Host "FAILED" -ForegroundColor Red
            Log-Error "Nelze smazat '$Path'. Soubor je pravděpodobně používán jiným procesem."
            Log-Error "Tip: Zavřete Android Studio nebo zabijte procesy 'java.exe'."
            Log-Warn "Chyba: $_"
        }
    }
    else {
        # Log-Info "$Description již neexistuje ($Path)." # Silent success
    }
}

# --- 5. Exekuce Mazání ---
# C++ Build Artefakty (nejčastější zdroj problémů s linkerem)
Remove-SafeItem -Path "lib\.cxx" -Description "Lib C++ Cache"
Remove-SafeItem -Path "app\.cxx" -Description "App C++ Cache"

# Gradle a Build Artefakty
Remove-SafeItem -Path ".gradle" -Description "Project Gradle Cache"
Remove-SafeItem -Path "app\build" -Description "App Build Output"
Remove-SafeItem -Path "lib\build" -Description "Lib Build Output"
Remove-SafeItem -Path "build" -Description "Root Build Output"

Log-Success "Deep Clean dokončen."
Log-Info "Doporučený další krok: Restart Android Studia -> File -> Sync Project with Gradle Files."
Log-Info "Pokud problém přetrvává, zkontrolujte nastavení JDK ve VS Code/Android Studiu."