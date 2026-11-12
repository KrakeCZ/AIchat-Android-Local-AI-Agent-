package com.example.llama.agent.ui.tiles

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.example.llama.SettingsManager

@RequiresApi(Build.VERSION_CODES.N)
class RemoteMouseTileService : TileService() {
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
        // Přepnutí stavu
        val currentState = settingsManager.isRemoteModeEnabled
        settingsManager.isRemoteModeEnabled = !currentState
        
        // Okamžitý update UI dlaždice
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val isEnabled = settingsManager.isRemoteModeEnabled

        tile.state = if (isEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (isEnabled) "PC Myš (Oči)" else "Oční Myš (Vyp)"
        
        tile.updateTile()
    }
}
