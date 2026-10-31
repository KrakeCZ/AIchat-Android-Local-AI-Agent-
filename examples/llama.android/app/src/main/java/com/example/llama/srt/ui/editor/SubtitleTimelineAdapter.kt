package com.example.llama.srt.ui.editor

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.llama.srt.SrtBlock
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class SubtitleTimelineAdapter(
    private val onBlockSelected: (Int) -> Unit,
    private val onBlockTextChanged: (Int, String) -> Unit,
    private val onBlockShifted: (Int, Long) -> Unit
) : ListAdapter<SrtBlock, SubtitleTimelineAdapter.SrtViewHolder>(DiffCallback) {

    private var selectedIndex = -1

    fun setSelectedIndex(index: Int) {
        if (selectedIndex == index) return
        val oldIndex = selectedIndex
        selectedIndex = index
        if (oldIndex != -1) notifyItemChanged(oldIndex)
        if (selectedIndex != -1) notifyItemChanged(selectedIndex)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SrtViewHolder {
        val context = parent.context
        
        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(16, 8, 16, 8)
            }
            setPadding(16, 16, 16, 16)
        }

        val headerLayout = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val tvIndex = TextView(context).apply {
            setTextColor(android.graphics.Color.parseColor("#888888"))
            textSize = 12f
            layoutParams = android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnMinus = com.google.android.material.button.MaterialButton(context).apply {
            text = "-"
            textSize = 12f
            insetTop = 0; insetBottom = 0
            layoutParams = android.widget.LinearLayout.LayoutParams(100, 100).apply { marginEnd = 8 }
            setPadding(0,0,0,0)
            cornerRadius = 8
            setBackgroundColor(android.graphics.Color.parseColor("#333333"))
        }

        val tvTime = TextView(context).apply {
            setTextColor(android.graphics.Color.parseColor("#2196F3"))
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val btnPlus = com.google.android.material.button.MaterialButton(context).apply {
            text = "+"
            textSize = 12f
            insetTop = 0; insetBottom = 0
            layoutParams = android.widget.LinearLayout.LayoutParams(100, 100).apply { marginStart = 8 }
            setPadding(0,0,0,0)
            cornerRadius = 8
            setBackgroundColor(android.graphics.Color.parseColor("#333333"))
        }

        headerLayout.addView(tvIndex)
        headerLayout.addView(btnMinus)
        headerLayout.addView(tvTime)
        headerLayout.addView(btnPlus)

        val inputLayout = TextInputLayout(context).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 8
            }
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxStrokeColor(android.graphics.Color.parseColor("#2196F3"))
            hint = "Text titulku"
        }

        val editText = TextInputEditText(inputLayout.context).apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            maxLines = 4
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        inputLayout.addView(editText)

        container.addView(headerLayout)
        container.addView(inputLayout)

        return SrtViewHolder(container, tvIndex, tvTime, editText, btnMinus, btnPlus)
    }

    override fun onBindViewHolder(holder: SrtViewHolder, position: Int) {
        holder.bind(getItem(position), position)
    }

    inner class SrtViewHolder(
        private val container: View,
        private val tvIndex: TextView,
        private val tvTime: TextView,
        private val editText: TextInputEditText,
        private val btnMinus: View,
        private val btnPlus: View
    ) : RecyclerView.ViewHolder(container) {

        private var currentPos = -1
        private var isBinding = false

        init {
            editText.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (isBinding) return
                    if (currentPos != -1 && editText.hasFocus()) {
                        onBlockTextChanged(currentPos, s?.toString() ?: "")
                    }
                }
            })
            
            editText.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && currentPos != -1) {
                    onBlockSelected(currentPos)
                }
            }
            
            btnMinus.setOnClickListener {
                if (currentPos != -1) onBlockShifted(currentPos, -25L)
            }
            btnPlus.setOnClickListener {
                if (currentPos != -1) onBlockShifted(currentPos, 25L)
            }
        }

        fun bind(block: SrtBlock, position: Int) {
            currentPos = position
            isBinding = true
            
            tvIndex.text = "Blok #${position + 1}"
            tvTime.text = "${formatTime(block.startTimeMs)} → ${formatTime(block.endTimeMs)}"
            
            if (editText.text.toString() != block.text) {
                editText.setText(block.text)
            }
            
            if (position == selectedIndex) {
                container.setBackgroundColor(android.graphics.Color.parseColor("#1A2196F3"))
            } else {
                container.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
            
            isBinding = false
        }
        
        private fun formatTime(ms: Long): String {
            val clamped = ms.coerceAtLeast(0L)
            val h = clamped / 3600000
            val m = (clamped % 3600000) / 60000
            val s = (clamped % 60000) / 1000
            val millis = clamped % 1000
            return "%02d:%02d:%02d,%03d".format(h, m, s, millis)
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<SrtBlock>() {
        override fun areItemsTheSame(oldItem: SrtBlock, newItem: SrtBlock) = oldItem.startTimeMs == newItem.startTimeMs && oldItem.endTimeMs == newItem.endTimeMs
        override fun areContentsTheSame(oldItem: SrtBlock, newItem: SrtBlock) = oldItem.text == newItem.text
    }
}
