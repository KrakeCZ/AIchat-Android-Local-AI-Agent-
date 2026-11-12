package com.example.llama.agent.vision.models

import androidx.annotation.Keep

@Keep
enum class AiDomainProfile(val displayName: String, val icon: String) {
    GENERAL("Obecná detekce", "🌍"),
    AUTONOMOUS_DRIVING("Autonomní řízení / ADAS", "🚗"),
    SECURITY_SURVEILLANCE("Bezpečnost & Trasování", "🛡️"),
    MEDICAL_IMAGING("Lékařská analýza", "🩺"),
    INDUSTRIAL_DEFECT("Průmyslové defekty", "⚙️")
}
