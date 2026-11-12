package com.example.llama.srt.ui.sheets

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import com.example.llama.R
import com.example.llama.srt.ui.models.SubtitleAnimationType
import com.example.llama.srt.ui.models.SubtitlePresetId
import com.example.llama.srt.ui.models.SubtitleStyleConfig
import com.example.llama.srt.ui.models.SubtitleStyleRegistry
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * StylePickerBottomSheet — čistá XML/Views implementace.
 *
 * Přepsáno z Compose (originální STUDY verze) protože projekt má `compose = false`.
 * Funkcionalita zachována 1:1: výběr presetu, velikost textu, barva, obrys,
 * pozadí, animace, CAPS.
 */
class StylePickerBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "StylePickerBottomSheet"
        private const val ARG_CONFIG = "arg_config"

        fun newInstance(config: SubtitleStyleConfig): StylePickerBottomSheet {
            return StylePickerBottomSheet().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_CONFIG, config)
                }
            }
        }
    }

    var onStyleSelected: ((SubtitleStyleConfig) -> Unit)? = null

    private var currentConfig: SubtitleStyleConfig = SubtitleStyleConfig()

    // Barevná paleta pro výběr barvy textu
    private val textColors = listOf(
        Color.WHITE,
        Color.YELLOW,
        Color.parseColor("#FFEA00"),
        Color.parseColor("#FF5722"),
        Color.parseColor("#4CAF50"),
        Color.parseColor("#2196F3"),
        Color.parseColor("#E91E63"),
        Color.parseColor("#9C27B0"),
    )
    private val textColorNames = listOf(
        "Bílá", "Žlutá", "Neon", "Oranžová", "Zelená", "Modrá", "Růžová", "Fialová"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        currentConfig = arguments?.getParcelable(ARG_CONFIG) ?: SubtitleStyleConfig()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bottom_sheet_style_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val chipGroupPreset = view.findViewById<ChipGroup>(R.id.chipGroupPreset)
        val seekbarTextSize = view.findViewById<SeekBar>(R.id.seekbarTextSize)
        val tvTextSizeValue = view.findViewById<TextView>(R.id.tvTextSizeValue)
        val chipGroupTextColor = view.findViewById<ChipGroup>(R.id.chipGroupTextColor)
        val switchStroke = view.findViewById<SwitchMaterial>(R.id.switchStroke)
        val switchBackground = view.findViewById<SwitchMaterial>(R.id.switchBackground)
        val spinnerAnimation = view.findViewById<Spinner>(R.id.spinnerAnimation)
        val switchAllCaps = view.findViewById<SwitchMaterial>(R.id.switchAllCaps)
        val btnApply = view.findViewById<MaterialButton>(R.id.btnApplyStyle)

        // ─── Presety (ChipGroup) ─────────────────────────────────────────────
        val allPresets = SubtitleStyleRegistry.all()
        allPresets.forEachIndexed { index, preset ->
            val chip = Chip(requireContext()).apply {
                text = preset.fontDisplayName
                isCheckable = true
                isChecked = preset.presetId == currentConfig.presetId
                setChipBackgroundColorResource(android.R.color.darker_gray)
                setTextColor(Color.WHITE)
                id = View.generateViewId()
                tag = index
            }
            chipGroupPreset.addView(chip)
        }
        chipGroupPreset.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val checkedChip = group.findViewById<Chip>(checkedIds[0])
            val idx = checkedChip?.tag as? Int ?: return@setOnCheckedStateChangeListener
            if (idx in allPresets.indices) {
                currentConfig = allPresets[idx]
                // Aktualizovat UI podle nového presetu
                seekbarTextSize.progress = currentConfig.textSizeSp.toInt()
                tvTextSizeValue.text = "${currentConfig.textSizeSp.toInt()}sp"
                switchStroke.isChecked = currentConfig.strokeWidthPx > 0f
                switchBackground.isChecked = currentConfig.hasBackground
                switchAllCaps.isChecked = currentConfig.isAllCaps
            }
        }

        // ─── Velikost textu ──────────────────────────────────────────────────
        seekbarTextSize.progress = currentConfig.textSizeSp.toInt()
        tvTextSizeValue.text = "${currentConfig.textSizeSp.toInt()}sp"
        seekbarTextSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                tvTextSizeValue.text = "${progress}sp"
                if (fromUser) {
                    currentConfig = currentConfig.copy(textSizeSp = progress.toFloat())
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // ─── Barvy textu (ChipGroup) ─────────────────────────────────────────
        textColors.forEachIndexed { index, color ->
            val chip = Chip(requireContext()).apply {
                text = textColorNames[index]
                isCheckable = true
                isChecked = color == currentConfig.textColor
                chipBackgroundColor = android.content.res.ColorStateList.valueOf(color)
                setTextColor(if (color == Color.WHITE || color == Color.YELLOW) Color.BLACK else Color.WHITE)
                id = View.generateViewId()
                tag = index
            }
            chipGroupTextColor.addView(chip)
        }
        chipGroupTextColor.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val checkedChip = group.findViewById<Chip>(checkedIds[0])
            val idx = checkedChip?.tag as? Int ?: return@setOnCheckedStateChangeListener
            if (idx in textColors.indices) {
                currentConfig = currentConfig.copy(textColor = textColors[idx])
            }
        }

        // ─── Obrys ──────────────────────────────────────────────────────────
        switchStroke.isChecked = currentConfig.strokeWidthPx > 0f
        switchStroke.setOnCheckedChangeListener { _, isChecked ->
            currentConfig = currentConfig.copy(
                strokeWidthPx = if (isChecked) 4f else 0f,
                strokeColor = Color.BLACK
            )
        }

        // ─── Pozadí ─────────────────────────────────────────────────────────
        switchBackground.isChecked = currentConfig.hasBackground
        switchBackground.setOnCheckedChangeListener { _, isChecked ->
            currentConfig = currentConfig.copy(hasBackground = isChecked)
        }

        // ─── Animace (Spinner) ──────────────────────────────────────────────
        val animNames = SubtitleAnimationType.entries.map { it.name.replace("_", " ") }
        val animAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, animNames)
        animAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerAnimation.adapter = animAdapter
        spinnerAnimation.setSelection(currentConfig.animationType.ordinal)
        spinnerAnimation.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                currentConfig = currentConfig.copy(animationType = SubtitleAnimationType.entries[pos])
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // ─── CAPS ───────────────────────────────────────────────────────────
        switchAllCaps.isChecked = currentConfig.isAllCaps
        switchAllCaps.setOnCheckedChangeListener { _, isChecked ->
            currentConfig = currentConfig.copy(isAllCaps = isChecked)
        }

        // ─── Potvrzení ──────────────────────────────────────────────────────
        btnApply.setOnClickListener {
            onStyleSelected?.invoke(currentConfig)
            dismiss()
        }
    }
}
