package com.example.llama.agent.ui

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.example.llama.SettingsManager

/**
 * TileService (Dlaždice v roletce rychlého nastavení) pro rychlé zapnutí/vypnutí Přepisu/Titulků.
 */
@RequiresApi(Build.VERSION_CODES.N)
class TranscriptTileService : TileService() {

    private lateinit var settingsManager: SettingsManager

    override fun onCreate() {
        super.onCreate()
        settingsManager = SettingsManager(this)
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        val currentState = settingsManager.isTranscriptEnabled
        settingsManager.isTranscriptEnabled = !currentState
        
        // Broadcast do OmniControlNotificationHelper, aby aktualizoval i ostatní UI
        val intent = Intent(OmniControlNotificationHelper.ACTION_TOGGLE_TRANSCRIPT)
        intent.setPackage(packageName)
        sendBroadcast(intent)
        
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isEnabled = settingsManager.isTranscriptEnabled
        
        tile.state = if (isEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isEnabled) "Titulky: Zapnuto" else "Titulky: Vypnuto"
        tile.updateTile()
    }
}
