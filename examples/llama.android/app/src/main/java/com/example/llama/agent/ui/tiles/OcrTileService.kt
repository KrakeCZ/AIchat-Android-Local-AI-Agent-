package com.example.llama.agent.ui.tiles

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.example.llama.SettingsManager

class OcrTileService : TileService() {
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
        settingsManager.isOcrEnabled = !settingsManager.isOcrEnabled
        updateTileState()
    }

    private fun updateTileState() {
        qsTile?.let { tile ->
            if (settingsManager.isOcrEnabled) {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "OCR On"
            } else {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "OCR Off"
            }
            tile.updateTile()
        }
    }
}
