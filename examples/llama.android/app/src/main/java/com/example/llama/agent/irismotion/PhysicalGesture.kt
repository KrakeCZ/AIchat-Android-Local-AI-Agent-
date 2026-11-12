package com.example.llama.agent.irismotion

enum class PhysicalGesture(val id: Int, val localizedName: String, val defaultActionId: Int) {
    LEFT_BLINK(1001, "Leve Mrknuti", 0),
    RIGHT_BLINK(1002, "Prave Mrknuti", 1),
    BOTH_BLINK(1003, "Obe Mrknuti", 2),
    SMILE(1004, "Usmev", -99),
    DWELL_CLICK(1005, "Dwell Click (Pohled)", 0),
    DOUBLE_CLICK(1006, "Dvojite mrknuti", 0),
    FROWN(1007, "Zamraceni", -1),
    RAISE_EYEBROWS(1008, "Zvednute Oboci", -2);

    companion object {
        fun fromId(id: Int) = entries.find { it.id == id }
    }
}
