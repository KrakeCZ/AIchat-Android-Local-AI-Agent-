package com.example.llama

import android.media.MediaPlayer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RecyclerView Adapter pro seznam nahrávek diktafonu.
 *
 * Každá položka zobrazuje:
 * - Název souboru + velikost
 * - Datum/čas vytvoření
 * - Tlačítko přehrát/zastavit (toggle)
 * - Tlačítko přepis / otevřít SRT editor (tužka)
 * - Tlačítko smazat
 *
 * D2: Tužka rozlišuje stav:
 *   - Modrá = žádný .srt soubor vedle nahrávky → onClick spustí přepis (generuj SRT)
 *   - Zelená = párový .srt existuje → onClick otevře SRT Editor s existujícím souborem
 *
 * MediaPlayer lifecycle: Vždy jen 1 přehrávaný soubor najednou (singleton currentPlayer).
 */
class AudioRecordingsAdapter(
    private val onTranscribeClick: (audioFile: File, existingSrt: File?) -> Unit,
    private val onDeleteClick: (File, Int) -> Unit,
    private val onExportClick: ((File) -> Unit)? = null
) : RecyclerView.Adapter<AudioRecordingsAdapter.RecordingViewHolder>() {

    private val recordings = mutableListOf<File>()
    private var currentPlayer: MediaPlayer? = null
    private var playingFile: File? = null
    private var playingHolder: RecordingViewHolder? = null

    fun submitList(files: List<File>) {
        recordings.clear()
        recordings.addAll(files.sortedByDescending { it.lastModified() })
        notifyDataSetChanged()
    }

    fun releasePlayer() {
        currentPlayer?.stop()
        currentPlayer?.release()
        currentPlayer = null
        playingHolder?.updatePlayState(false)
        playingHolder = null
        playingFile = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordingViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_audio_recording, parent, false)
        return RecordingViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecordingViewHolder, position: Int) {
        holder.bind(recordings[position], position)
    }

    override fun getItemCount(): Int = recordings.size

    inner class RecordingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tv_recording_name)
        private val tvMeta: TextView = itemView.findViewById(R.id.tv_recording_meta)
        private val tvDate: TextView = itemView.findViewById(R.id.tv_recording_date)
        private val btnPlay: ImageButton = itemView.findViewById(R.id.btn_play_recording)
        private val btnTranscribe: ImageButton = itemView.findViewById(R.id.btn_transcribe_recording)
        private val btnDelete: ImageButton = itemView.findViewById(R.id.btn_delete_recording)
        private var isPlaying = false

        fun bind(file: File, position: Int) {
            tvName.text = file.nameWithoutExtension
            tvDate.text = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                .format(Date(file.lastModified()))

            val sizeKb = file.length() / 1024
            val ext = file.extension.uppercase()

            // D2: Hledáme párový .srt soubor (stejný název, jiná přípona, stejný adresář)
            val pairedSrt: File? = File(file.parent, "${file.nameWithoutExtension}.srt")
                .takeIf { it.exists() && it.length() > 0 }

            if (pairedSrt != null) {
                // SRT existuje — zelená tužka
                tvMeta.text = "$ext · ${sizeKb} KB  ✅ SRT"
                btnTranscribe.clearColorFilter()
                btnTranscribe.setColorFilter(android.graphics.Color.parseColor("#4CAF50"))
                btnTranscribe.contentDescription = "Otevřít SRT Editor"
            } else {
                // SRT neexistuje — modrá tužka
                tvMeta.text = "$ext · ${sizeKb} KB"
                btnTranscribe.clearColorFilter()
                btnTranscribe.setColorFilter(android.graphics.Color.parseColor("#2196F3"))
                btnTranscribe.contentDescription = "Vygenerovat SRT titulky"
            }

            isPlaying = (playingFile == file)
            updatePlayState(isPlaying)

            btnPlay.setOnClickListener {
                if (isPlaying) {
                    releasePlayer()
                } else {
                    releasePlayer()
                    try {
                        currentPlayer = MediaPlayer().apply {
                            setDataSource(file.absolutePath)
                            setOnPreparedListener { 
                                it.start()
                                // Aktualizace UI pro přehrávání proběhne až když je soubor připraven
                                playingHolder?.updatePlayState(true)
                            }
                            setOnCompletionListener { releasePlayer() }
                            prepareAsync() // Ne-blokující načtení hlaviček na I/O vlákně OS
                        }
                        playingFile = file
                        playingHolder = this@RecordingViewHolder
                        isPlaying = true
                    } catch (e: Exception) {
                        android.util.Log.e("RecordingAdapter", "Chyba přehrávání: ${e.message}")
                    }
                }
            }

            btnTranscribe.setOnClickListener {
                // D2: Předáme párový .srt (nebo null) — AudioStudioActivity rozhodne co otevřít
                onTranscribeClick(file, pairedSrt)
            }

            btnDelete.setOnClickListener {
                onDeleteClick(file, position)
            }

            itemView.setOnLongClickListener {
                onExportClick?.invoke(file)
                true
            }
        }

        fun updatePlayState(playing: Boolean) {
            isPlaying = playing
            btnPlay.setImageResource(
                if (playing) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )
            itemView.alpha = if (playing) 1.0f else 0.85f
        }
    }
}
