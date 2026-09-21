Add-Type -AssemblyName System.IO.Compression.FileSystem
$jarPath = Join-Path $PSScriptRoot "sherpa_tmp\extracted\classes.jar"
$zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
foreach ($entry in $zip.Entries) {
    if ($entry.FullName -match 'Tts|Matcha|ZipVoice|Pocket|Supertonic|Kitten') {
        Write-Output $entry.FullName
    }
}
$zip.Dispose()
