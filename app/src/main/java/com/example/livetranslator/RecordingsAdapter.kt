package com.example.livetranslator

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingsAdapter(
    private val items: MutableList<RecordingItem>,
    private val listener: Listener
) : RecyclerView.Adapter<RecordingsAdapter.VH>() {

    interface Listener {
        fun onPlay(item: RecordingItem)
        fun onStop(item: RecordingItem)
        fun onDelete(item: RecordingItem)
        fun onTranscribe(item: RecordingItem)
    }

    private val dateFmt = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvName        : TextView = v.findViewById(R.id.tvRecName)
        val tvDate        : TextView = v.findViewById(R.id.tvRecDate)
        val tvSize        : TextView = v.findViewById(R.id.tvRecSize)
        val tvTranscript  : TextView = v.findViewById(R.id.tvRecTranscript)
        val tvTranslation : TextView = v.findViewById(R.id.tvRecTranslation)
        val btnPlay       : Button   = v.findViewById(R.id.btnRecPlay)
        val btnTranscribe : Button   = v.findViewById(R.id.btnRecTranscribe)
        val btnDelete     : Button   = v.findViewById(R.id.btnRecDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_recording, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]

        holder.tvName.text  = item.name
        holder.tvDate.text  = dateFmt.format(Date(item.timestamp))
        holder.tvSize.text  = formatSize(item.sizeBytes)

        holder.tvTranscript.text       = item.transcription
        holder.tvTranscript.visibility = if (item.transcription.isBlank()) View.GONE else View.VISIBLE

        holder.tvTranslation.text       = item.translation
        holder.tvTranslation.visibility = if (item.translation.isBlank()) View.GONE else View.VISIBLE

        if (item.isPlaying) {
            holder.btnPlay.text = "⏹ Stop"
            holder.btnPlay.setOnClickListener { listener.onStop(item) }
        } else {
            holder.btnPlay.text = "▶ Play"
            holder.btnPlay.setOnClickListener { listener.onPlay(item) }
        }

        holder.btnTranscribe.setOnClickListener { listener.onTranscribe(item) }
        holder.btnDelete.setOnClickListener     { listener.onDelete(item) }
    }

    fun setAll(newItems: List<RecordingItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun update(item: RecordingItem) {
        val idx = items.indexOfFirst { it.file.absolutePath == item.file.absolutePath }
        if (idx >= 0) notifyItemChanged(idx)
    }

    fun remove(item: RecordingItem) {
        val idx = items.indexOfFirst { it.file.absolutePath == item.file.absolutePath }
        if (idx >= 0) { items.removeAt(idx); notifyItemRemoved(idx) }
    }

    fun all(): List<RecordingItem> = items.toList()

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024        -> "${bytes} B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        else                -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
    }
}
