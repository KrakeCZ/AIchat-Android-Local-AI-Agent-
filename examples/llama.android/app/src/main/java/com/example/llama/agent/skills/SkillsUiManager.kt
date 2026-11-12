package com.example.llama.agent.skills

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.llama.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * UI manažer pro správu a editaci dovedností (Skills Framework).
 * Zajišťuje dialog správy modulů, editor Markdownu a rychlý výběr z lišty chatu.
 */
object SkillsUiManager {

    /**
     * Zobrazí hlavní dialog pro správu všech dovedností (Skills.md).
     */
    fun showSkillsManager(
        context: Context,
        onSkillsChanged: (() -> Unit)? = null
    ) {
        val registry = SkillRegistry.getInstance(context)
        val inflater = LayoutInflater.from(context)
        val dialogView = inflater.inflate(R.layout.dialog_skills_manager, null)

        val spinnerActive = dialogView.findViewById<Spinner>(R.id.spinner_active_skill)
        val containerList = dialogView.findViewById<LinearLayout>(R.id.container_skills_list)
        val btnCreate = dialogView.findViewById<MaterialButton>(R.id.btn_create_skill)
        val btnClose = dialogView.findViewById<MaterialButton>(R.id.btn_close_skills)

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        fun refreshList() {
            containerList.removeAllViews()
            val skills = registry.getAllSkills()

            // Nastavení spinneru aktivního režimu
            val spinnerOptions = mutableListOf("⚡ Auto (dle promptu)", "🚫 Vypnuto (Bez skillů)")
            val skillIds = mutableListOf(SkillRegistry.SKILL_AUTO, SkillRegistry.SKILL_NONE)

            skills.forEach { skill ->
                spinnerOptions.add("🧠 ${skill.name}")
                skillIds.add(skill.id)
            }

            val spinnerAdapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, spinnerOptions)
            spinnerActive.adapter = spinnerAdapter

            val currentActiveId = registry.activeSkillId
            val activeIdx = skillIds.indexOf(currentActiveId).coerceAtLeast(0)
            spinnerActive.setSelection(activeIdx, false)

            spinnerActive.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selectedId = skillIds.getOrElse(position) { SkillRegistry.SKILL_AUTO }
                    registry.activeSkillId = selectedId
                    onSkillsChanged?.invoke()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            // Vyrenderování jednotlivých karet skillů
            skills.forEach { skill ->
                val itemView = inflater.inflate(R.layout.item_skill, containerList, false)
                val tvName = itemView.findViewById<TextView>(R.id.tv_skill_name)
                val tvBadge = itemView.findViewById<TextView>(R.id.tv_skill_badge)
                val tvDesc = itemView.findViewById<TextView>(R.id.tv_skill_desc)
                val tvTriggers = itemView.findViewById<TextView>(R.id.tv_skill_triggers)
                val switchEnabled = itemView.findViewById<SwitchMaterial>(R.id.switch_skill_enabled)
                val btnEdit = itemView.findViewById<MaterialButton>(R.id.btn_skill_edit)
                val btnDelete = itemView.findViewById<MaterialButton>(R.id.btn_skill_delete)

                tvName.text = skill.name
                tvDesc.text = skill.description
                tvTriggers.text = if (skill.triggers.isNotEmpty()) {
                    "Triggers: ${skill.triggers.joinToString(", ")}"
                } else {
                    "Triggers: (žádné explicitní)"
                }

                if (skill.isBuiltIn) {
                    tvBadge.text = "Vestavěný"
                    tvBadge.setTextColor(android.graphics.Color.parseColor("#00E676"))
                    tvBadge.setBackgroundColor(android.graphics.Color.parseColor("#2200E676"))
                    btnDelete.visibility = View.GONE
                } else {
                    tvBadge.text = "Vlastní .md"
                    tvBadge.setTextColor(android.graphics.Color.parseColor("#29B6F6"))
                    tvBadge.setBackgroundColor(android.graphics.Color.parseColor("#2229B6F6"))
                    btnDelete.visibility = View.VISIBLE
                }

                switchEnabled.isChecked = skill.isEnabled
                switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                    registry.setSkillEnabled(skill.id, isChecked)
                    onSkillsChanged?.invoke()
                }

                btnEdit.setOnClickListener {
                    showSkillEditor(context, skill) {
                        refreshList()
                        onSkillsChanged?.invoke()
                    }
                }

                btnDelete.setOnClickListener {
                    MaterialAlertDialogBuilder(context)
                        .setTitle("Smazat dovednost?")
                        .setMessage("Opravdu chcete smazat dovednost \"${skill.name}\" z disku?")
                        .setPositiveButton("Smazat") { _, _ ->
                            registry.deleteSkill(skill.id)
                            refreshList()
                            onSkillsChanged?.invoke()
                        }
                        .setNegativeButton("Zrušit", null)
                        .show()
                }

                containerList.addView(itemView)
            }
        }

        btnCreate.setOnClickListener {
            showSkillEditor(context, null) {
                refreshList()
                onSkillsChanged?.invoke()
            }
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        refreshList()
        dialog.show()
    }

    /**
     * Zobrazí editor konkrétní dovednosti (editace existující nebo vytvoření nové).
     */
    fun showSkillEditor(
        context: Context,
        existingSkill: Skill?,
        onSaved: () -> Unit
    ) {
        val registry = SkillRegistry.getInstance(context)
        val inflater = LayoutInflater.from(context)
        val editorView = inflater.inflate(R.layout.dialog_skill_editor, null)

        val tvTitle = editorView.findViewById<TextView>(R.id.tv_editor_title)
        val etName = editorView.findViewById<EditText>(R.id.et_skill_name)
        val etDesc = editorView.findViewById<EditText>(R.id.et_skill_desc)
        val etTriggers = editorView.findViewById<EditText>(R.id.et_skill_triggers)
        val etInstructions = editorView.findViewById<EditText>(R.id.et_skill_instructions)
        val btnCancel = editorView.findViewById<MaterialButton>(R.id.btn_cancel_edit)
        val btnSave = editorView.findViewById<MaterialButton>(R.id.btn_save_skill)

        if (existingSkill != null) {
            tvTitle.text = if (existingSkill.isBuiltIn) "📄 Prohlížeč dovednosti: ${existingSkill.name}" else "✏️ Upravit dovednost: ${existingSkill.name}"
            etName.setText(existingSkill.name)
            etDesc.setText(existingSkill.description)
            etTriggers.setText(existingSkill.triggers.joinToString(", "))
            etInstructions.setText(existingSkill.instructions)

            if (existingSkill.isBuiltIn) {
                // U vestavěných skillů nedovolujeme změnit ID/soubor, ale instrukce a triggery ano
                etName.isEnabled = false
            }
        } else {
            tvTitle.text = "✨ Vytvořit novou dovednost (Skill.md)"
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(editorView)
            .setCancelable(true)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val name = etName.text.toString().trim()
            val desc = etDesc.text.toString().trim()
            val triggersStr = etTriggers.text.toString()
            val instructions = etInstructions.text.toString().trim()

            if (name.isBlank()) {
                Toast.makeText(context, "Zadejte prosím název dovednosti.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (instructions.isBlank()) {
                Toast.makeText(context, "Zadejte prosím instrukce pro model.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val triggers = triggersStr.split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }

            val skillId = existingSkill?.id ?: name.lowercase().replace(Regex("[^a-z0-9_]"), "_")
            val isBuiltIn = existingSkill?.isBuiltIn ?: false
            val fileName = existingSkill?.fileName ?: "$skillId.md"
            val isEnabled = existingSkill?.isEnabled ?: true

            val newOrUpdated = Skill(
                id = skillId,
                name = name,
                description = desc,
                triggers = triggers,
                instructions = instructions,
                isBuiltIn = isBuiltIn,
                isEnabled = isEnabled,
                fileName = fileName
            )

            val success = registry.saveSkill(newOrUpdated)
            if (success) {
                Toast.makeText(context, "Dovednost \"$name\" byla uložena.", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                onSaved.invoke()
            } else {
                Toast.makeText(context, "Chyba při ukládání souboru.", Toast.LENGTH_LONG).show()
            }
        }

        dialog.show()
    }

    /**
     * Rychlý výběr aktivního skillu z lišty nad klávesnicí.
     */
    fun showQuickSkillSelector(
        context: Context,
        onSelected: (String) -> Unit
    ) {
        val registry = SkillRegistry.getInstance(context)
        val skills = registry.getAllSkills().filter { it.isEnabled }

        val items = mutableListOf<String>()
        val ids = mutableListOf<String>()

        items.add("⚡ Auto (dle dotazu)")
        ids.add(SkillRegistry.SKILL_AUTO)

        skills.forEach { skill ->
            val icon = when (skill.id) {
                "deep_research" -> "🔬"
                "code_auditor" -> "🛡️"
                "android_master" -> "🤖"
                else -> "🧠"
            }
            items.add("$icon ${skill.name}")
            ids.add(skill.id)
        }

        items.add("🚫 Vypnuto (Bez skillů)")
        ids.add(SkillRegistry.SKILL_NONE)

        items.add("⚙️ Spravovat dovednosti (Skills.md)...")
        ids.add("__MANAGE__")

        val currentActive = registry.activeSkillId
        val checkedItem = ids.indexOf(currentActive).coerceAtLeast(0)

        MaterialAlertDialogBuilder(context)
            .setTitle("🧠 Zvolte aktivní dovednost (Skill)")
            .setSingleChoiceItems(items.toTypedArray(), checkedItem) { dialog, which ->
                val selectedId = ids[which]
                dialog.dismiss()
                if (selectedId == "__MANAGE__") {
                    showSkillsManager(context) {
                        onSelected(registry.activeSkillId)
                    }
                } else {
                    registry.activeSkillId = selectedId
                    onSelected(selectedId)
                }
            }
            .setNegativeButton("Zrušit", null)
            .show()
    }
}
