package com.example.llama.agent.irismotion.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "remote_profiles")
data class RemoteProfileEntity(
    @PrimaryKey
    val id: String,
    
    val name: String,
    
    /** "MOUSE", "GAMEPAD", "CUSTOM" */
    val type: String,
    
    /** 
     * Mapování vstupního gesta na výstupní akci (např. BT HID kód).
     * Bude konvertováno na String přes MapTypeConverter.
     */
    val gestureMapping: Map<Int, Int>
)
