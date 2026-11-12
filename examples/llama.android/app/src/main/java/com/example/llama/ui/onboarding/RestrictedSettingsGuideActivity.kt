package com.example.llama.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.setPadding

class RestrictedSettingsGuideActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Omezené nastavení (Android 13+)"

        // Programmatic UI creation to avoid layout XML dependency
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
        }
        
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val titleTv = TextView(this).apply {
            text = "⚠️ Služba je zablokována"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(getColor(android.R.color.holo_red_light))
        }

        val descTv = TextView(this).apply {
            text = "\nAndroid 13+ blokuje Accessibility služby instalované mimo Google Play (Sideloaded).\n\nMusíte ručně povolit \"Omezené nastavení\"."
            textSize = 16f
            gravity = Gravity.CENTER
        }

        val stepsTv = TextView(this).apply {
            text = "\nPOSTUP:\n\n1. Klikněte na tlačítko níže 'Otevřít Info o aplikaci'.\n\n2. V detailu aplikace klikněte na 3 tečky vpravo nahoře.\n\n3. Zvolte 'Povolit omezené nastavení' (Allow restricted settings).\n\n4. Ověřte totožnost (otisk/PIN).\n\n5. Vraťte se zpět sem a zkuste službu znovu zapnout."
            textSize = 16f
            gravity = Gravity.START
        }

        val actionBtn = Button(this).apply {
            text = "Otevřít Info o aplikaci"
            textSize = 18f
            setOnClickListener {
                openAppInfo()
            }
        }
        
         val closeBtn = Button(this).apply {
            text = "Hotovo, vrátit se"
            setOnClickListener {
                finish()
            }
        }

        layout.addView(titleTv)
        layout.addView(descTv)
        layout.addView(stepsTv)
        layout.addView(android.widget.Space(this).apply { minimumHeight = 50 })
        layout.addView(actionBtn)
        layout.addView(closeBtn)

        scrollView.addView(layout)
        setContentView(scrollView)
    }

    private fun openAppInfo() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
