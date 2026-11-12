# Definice zdrojové a cílové cesty
$SourcePath = "C:\SS24_V3_OK_preAgent_\examples\llama.android\app\src\main\java\com\example\llama\agent\vision"
$DestinationPath = "C:\00_soubory_PROJEKT"

# Kontrola existence zdrojového adresáře
if (-not (Test-Path -Path $SourcePath)) {
    Write-Error "Zdrojový adresář nebyl nalezen: $SourcePath"
    return
}

# Vytvoření cílového adresáře, pokud neexistuje
if (-not (Test-Path -Path $DestinationPath)) {
    New-Item -Path $DestinationPath -ItemType Directory | Out-Null
    Write-Host "Cílový adresář byl vytvořen: $DestinationPath" -ForegroundColor Green
}

# Vyhledání a zkopírování všech .kt souborů
try {
    $Files = Get-ChildItem -Path $SourcePath -Filter *.kt -Recurse -File
    
    if ($Files.Count -eq 0) {
        Write-Warning "Ve zdrojové složce nebyly nalezeny žádné soubory s příponou .kt"
    }
    else {
        foreach ($File in $Files) {
            $DestinationFile = Join-Path -Path $DestinationPath -ChildPath $File.Name
            
            # Kopírování s kontrolou, zda soubor již v cíli neexistuje (prevence kolizí)
            if (-not (Test-Path -Path $DestinationFile)) {
                Copy-Item -Path $File.FullName -Destination $DestinationFile -Force
                Write-Host "Zkopírován soubor: $($File.Name)" -ForegroundColor Cyan
            }
            else {
                Write-Warning "Soubor již existuje, přeskakuji: $($File.Name)"
            }
        }
        Write-Host "Operace byla úspěšně dokončena." -ForegroundColor Green
    }
}
catch {
    Write-Error "Došlo k neočekávané chybě během kopírování: $($_.Exception.Message)"
}