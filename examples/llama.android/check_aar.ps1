Add-Type -AssemblyName System.IO.Compression.FileSystem
$aarPath = "c:\SS24_V3_OK_preAgent_\examples\llama.android\app\libs\sherpa-onnx-1.12.34.aar"
$z = [System.IO.Compression.ZipFile]::OpenRead($aarPath)
# First list all jar files
Write-Host "=== JAR files in AAR ==="
$z.Entries | Where-Object { $_.FullName -like '*.jar' } | ForEach-Object { Write-Host $_.FullName }

# Check classes.jar for Offline
Write-Host "`n=== Checking for Offline classes ==="
$jarEntry = $z.Entries | Where-Object { $_.FullName -eq 'classes.jar' } | Select-Object -First 1
if ($jarEntry) {
    $tempPath = [System.IO.Path]::GetTempFileName() + ".jar"
    $stream = $jarEntry.Open()
    $fs = [System.IO.File]::Create($tempPath)
    $stream.CopyTo($fs)
    $fs.Close()
    $stream.Close()
    
    $jar = [System.IO.Compression.ZipFile]::OpenRead($tempPath)
    $jar.Entries | Where-Object { $_.FullName -like '*Offline*' -and $_.FullName -like '*.class' } | ForEach-Object { Write-Host $_.FullName }
    
    Write-Host "`n=== All sherpa.onnx classes ==="
    $jar.Entries | Where-Object { $_.FullName -like '*sherpa*' -and $_.FullName -like '*.class' -and $_.FullName -notlike '*$*' } | ForEach-Object { Write-Host $_.FullName }
    $jar.Dispose()
    Remove-Item $tempPath -ErrorAction SilentlyContinue
} else {
    Write-Host "classes.jar not found in AAR!"
}
$z.Dispose()
