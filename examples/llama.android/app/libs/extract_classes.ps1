Add-Type -AssemblyName System.IO.Compression.FileSystem
$jarPath = Join-Path $PSScriptRoot "sherpa_tmp\extracted\classes.jar"
$destDir = Join-Path $PSScriptRoot "sherpa_tmp\jar_extracted"
if (-not (Test-Path $destDir)) { New-Item -Path $destDir -ItemType Directory -Force | Out-Null }
$zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
foreach ($entry in $zip.Entries) {
    if ($entry.FullName -match 'OfflineTtsZipVoice|OfflineTtsPocket|OfflineTtsSupertonic|OfflineTtsKitten|OfflineTtsMatcha|OfflineTtsModelConfig\.class') {
        $targetPath = Join-Path $destDir $entry.Name
        $stream = $entry.Open()
        $fileStream = [System.IO.File]::Create($targetPath)
        $stream.CopyTo($fileStream)
        $fileStream.Dispose()
        $stream.Dispose()
        Write-Output "Extracted: $($entry.FullName)"
    }
}
$zip.Dispose()
