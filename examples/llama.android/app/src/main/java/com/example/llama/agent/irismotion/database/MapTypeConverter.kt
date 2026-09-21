package com.example.llama.agent.irismotion.database

import androidx.room.TypeConverter

class MapTypeConverter {

    @TypeConverter
    fun fromMap(map: Map<Int, Int>): String {
        return map.entries.joinToString(",") { "${it.key}:${it.value}" }
    }

    @TypeConverter
    fun toMap(data: String): Map<Int, Int> {
        if (data.isBlank()) return emptyMap()
        return data.split(",").mapNotNull {
            val parts = it.split(":")
            if (parts.size == 2) {
                try {
                    parts[0].toInt() to parts[1].toInt()
                } catch (e: Exception) {
                    null
                }
            } else null
        }.toMap()
    }
}
