package com.example.llama.agent.perception

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

object WhisperModelManager {
    private const val TAG = "WhisperModelManager"
    
    // Defaultni cesta pro ADB upload pro zjednodušeni (Fáze I)
    private const val DEFAULT_MODEL_PATH = "/data/local/tmp/ggml-tiny.bin"

    /**
     * PRIMÁRNÍ metoda: Nejprve zkontroluje uživatelsky importovaný model z WhisperDatabase
     * (WhisperModelRepository), pak fallback na skenování souborového systému.
     *
     * Tato metoda je suspend, protože přistupuje k Room DB přes coroutines.
     */
    suspend fun getModelPathFromRepository(context: Context): String {
        val repository = com.example.llama.data.WhisperModelRepository(context)
        
        // 1. Priorita: aktivní model z databáze (uživatel ho importoval přes Settings UI)
        val activeModelPath = repository.getActiveModelPath()
        if (!activeModelPath.isNullOrEmpty()) {
            val f = File(activeModelPath)
            if (f.exists() && f.canRead() && f.length() > 10_000_000L) {
                Log.i(TAG, "✅ Whisper model z Repository: ${f.absolutePath} (${f.length() / 1_048_576} MB)")
                return activeModelPath
            } else {
                Log.w(TAG, "⚠️ Cesta z Repository nevalidní nebo soubor chybí: $activeModelPath")
            }
        }

        // 2. Fallback: skenování souborového systému (původní logika)
        Log.i(TAG, "Repository prázdné — fallback na skenování disku")
        return getModelPath(context)
    }

    /**
     * FALLBACK metoda: Skenuje standardní lokace na disku.
     * (Kód podporuje detekci ve standardní struktuře stažených souborů /sdcard/Download)
     */
    fun getModelPath(context: Context): String {
        val modelCandidates = mutableListOf<File>()

        // 1. Z pohledu vývojáře: přes ADB nahráno do /data/local/tmp/ggml-tiny.bin
        modelCandidates.add(File(DEFAULT_MODEL_PATH))

        // 2. Veřejná složka Download, kam stahuje Google Chrome (/sdcard/Download/)
        val publicDownloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        
        modelCandidates.add(File(publicDownloadDir, "whisper/ggml-tiny-q8_0.bin"))
        modelCandidates.add(File(publicDownloadDir, "whisper/ggml-base-q8_0.bin"))
        modelCandidates.add(File(publicDownloadDir, "whisper/ggml-small-q8_0.bin"))
        modelCandidates.add(File(publicDownloadDir, "ggml-tiny-q8_0.bin"))
        modelCandidates.add(File(publicDownloadDir, "ggml-base-q8_0.bin"))

        // 3. Privátní app scope složka (Android/data/.../files/Download)
        val privateDownloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        if (privateDownloadDir != null) {
            modelCandidates.add(File(privateDownloadDir, "ggml-tiny-q8_0.bin"))
        }

        // --- Iterace všech kandidátů a nalezení prvního dostupného modelu ---
        for (file in modelCandidates) {
            if (file.exists() && file.canRead() && file.length() > 10_000_000) {
                Log.i(TAG, "Nalezen validní Whisper model: ${file.absolutePath} (${file.length() / 1_048_576} MB)")
                return file.absolutePath
            }
        }

        Log.e(TAG, "Whisper model NENALEZEN!")
        Log.e(TAG, "Importujte model přes Nastavení → záložka '🎙 Whisper'.")
        return ""
    }
}
