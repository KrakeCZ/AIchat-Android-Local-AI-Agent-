package com.example.llama.agent.ui.tiles

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.llama.SettingsManager

class VisionTileService : TileService() {
    private lateinit var settingsManager: SettingsManager

    override fun onCreate() {
        super.onCreate()
        settingsManager = SettingsManager(applicationContext)
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        settingsManager.isPerceptionEnabled = !settingsManager.isPerceptionEnabled
        updateTileState()
    }

    private fun updateTileState() {
        qsTile?.let { tile ->
            if (settingsManager.isPerceptionEnabled) {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Vision On"
            } else {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Vision Off"
            }
            tile.updateTile()
        }
    }
}
