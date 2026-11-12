package com.example.llama.srt.ui.editor

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.llama.R
import com.example.llama.srt.ui.models.SubtitleStyleConfig

class SubtitleStyleCarouselAdapter(
    private val styles: List<SubtitleStyleConfig>,
    private val onStyleSelected: (SubtitleStyleConfig) -> Unit
) : RecyclerView.Adapter<SubtitleStyleCarouselAdapter.StyleViewHolder>() {

    private var selectedIndex = -1
    var onStyleLongPressed: ((SubtitleStyleConfig) -> Unit)? = null

    inner class StyleViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvStylePreview: TextView = view.findViewById(R.id.tvStylePreview)
        val tvStyleName: TextView = view.findViewById(R.id.tvStyleName)
        val cardView: com.google.android.material.card.MaterialCardView = view as com.google.android.material.card.MaterialCardView
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StyleViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_subtitle_style_carousel, parent, false)
        return StyleViewHolder(view)
    }

    override fun onBindViewHolder(holder: StyleViewHolder, position: Int) {
        val config = styles[position]
        holder.tvStyleName.text = config.presetId.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }

        // Simulace vykreslení fontu
        holder.tvStylePreview.setTextColor(config.textColor)
        if (config.hasBackground) {
            holder.tvStylePreview.setBackgroundColor(config.backgroundColor)
        } else {
            holder.tvStylePreview.setBackgroundColor(Color.TRANSPARENT)
        }
        
        // Zvýraznění aktivní položky
        if (position == selectedIndex) {
            holder.cardView.setCardBackgroundColor(Color.parseColor("#332196F3")) // Modrý nádech
            holder.cardView.strokeWidth = 4
            holder.cardView.strokeColor = Color.parseColor("#2196F3")
        } else {
            holder.cardView.setCardBackgroundColor(Color.parseColor("#1A1A1A"))
            holder.cardView.strokeWidth = 0
        }

        holder.itemView.setOnClickListener {
            val prevIndex = selectedIndex
            selectedIndex = holder.absoluteAdapterPosition
            notifyItemChanged(prevIndex)
            notifyItemChanged(selectedIndex)
            onStyleSelected(config)
        }
        
        holder.itemView.setOnLongClickListener {
            onStyleLongPressed?.invoke(config)
            true
        }
    }

    override fun getItemCount() = styles.size
}
