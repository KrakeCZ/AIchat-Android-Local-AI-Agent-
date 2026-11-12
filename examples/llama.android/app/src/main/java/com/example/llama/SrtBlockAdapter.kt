package com.example.llama

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

data class SrtBlock(
    var index: Int,
    var startTime: String,
    var endTime: String,
    var text: String
)

/**
 * Adapter pro SRT bloky v SrtEditorActivity.
 * Podporuje inline editaci startTime/endTime/text a mazání bloku přes onDeleteClick.
 */
class SrtBlockAdapter(
    private val blocks: MutableList<SrtBlock>,
    private val onDeleteClick: ((Int) -> Unit)? = null
) : RecyclerView.Adapter<SrtBlockAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIndex: TextView = view.findViewById(R.id.tv_srt_index)
        val etTimeStart: EditText = view.findViewById(R.id.et_time_start)
        val etTimeEnd: EditText = view.findViewById(R.id.et_time_end)
        val etText: EditText = view.findViewById(R.id.et_srt_text)
        val btnDelete: ImageButton? = view.findViewById(R.id.btn_srt_delete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_srt_block, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val block = blocks[position]
        holder.tvIndex.text = block.index.toString()

        // Remove previous watchers to avoid infinite loops during recycling
        holder.etTimeStart.tag?.let { holder.etTimeStart.removeTextChangedListener(it as? TextWatcher) }
        holder.etTimeEnd.tag?.let { holder.etTimeEnd.removeTextChangedListener(it as? TextWatcher) }
        holder.etText.tag?.let { holder.etText.removeTextChangedListener(it as? TextWatcher) }

        holder.etTimeStart.setText(block.startTime)
        holder.etTimeEnd.setText(block.endTime)
        holder.etText.setText(block.text)

        // Listeners for live updates
        val startWatcher = createWatcher { block.startTime = it }
        holder.etTimeStart.addTextChangedListener(startWatcher)
        holder.etTimeStart.tag = startWatcher

        val endWatcher = createWatcher { block.endTime = it }
        holder.etTimeEnd.addTextChangedListener(endWatcher)
        holder.etTimeEnd.tag = endWatcher

        val textWatcher = createWatcher { block.text = it }
        holder.etText.addTextChangedListener(textWatcher)
        holder.etText.tag = textWatcher

        // Delete button — volitelný
        holder.btnDelete?.setOnClickListener {
            val adapterPos = holder.bindingAdapterPosition
            if (adapterPos != RecyclerView.NO_POSITION) {
                onDeleteClick?.invoke(adapterPos)
            }
        }
    }

    override fun getItemCount() = blocks.size

    private fun createWatcher(onTextChanged: (String) -> Unit): TextWatcher {
        return object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                onTextChanged(s?.toString() ?: "")
            }
        }
    }
}
