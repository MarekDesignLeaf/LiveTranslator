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

class SentenceAdapter(
    private val items: MutableList<Sentence>,
    private val listener: Listener
) : RecyclerView.Adapter<SentenceAdapter.VH>() {

    interface Listener {
        fun onCopySource(sentence: Sentence)
        fun onSpeakSource(sentence: Sentence)
        fun onEditSource(sentence: Sentence)
        fun onReply(sentence: Sentence)
        fun onCopyTranslation(sentence: Sentence)
        fun onSpeakTranslation(sentence: Sentence)
        fun onCopyReply(sentence: Sentence)
        fun onDelete(sentence: Sentence)
    }

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvTimestamp: TextView   = v.findViewById(R.id.tvTimestamp)
        val tvSource: TextView      = v.findViewById(R.id.tvSource)
        val tvTranslation: TextView = v.findViewById(R.id.tvTranslation)
        val tvReply: TextView       = v.findViewById(R.id.tvReply)
        val tvStatus: TextView      = v.findViewById(R.id.tvStatus)
        val btnCopy: Button         = v.findViewById(R.id.btnCopy)
        val btnSpeak: Button        = v.findViewById(R.id.btnSpeak)
        val btnEdit: Button         = v.findViewById(R.id.btnEdit)
        val btnReply: Button        = v.findViewById(R.id.btnReply)
        val btnMore: Button         = v.findViewById(R.id.btnMore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_line, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]

        holder.tvTimestamp.text = if (s.timestamp > 0) timeFmt.format(Date(s.timestamp)) else ""
        holder.tvSource.text      = s.sourceText
        holder.tvTranslation.text = s.translatedText
        holder.tvReply.text       = s.replyText
        holder.tvStatus.text      = s.status

        // Hide empty sections
        holder.tvTranslation.visibility = if (s.translatedText.isBlank()) View.GONE else View.VISIBLE
        holder.tvReply.visibility       = if (s.replyText.isBlank())      View.GONE else View.VISIBLE

        holder.btnCopy.setOnClickListener  { listener.onCopySource(s) }
        holder.btnSpeak.setOnClickListener { listener.onSpeakTranslation(s) }
        holder.btnEdit.setOnClickListener  { listener.onEditSource(s) }
        holder.btnReply.setOnClickListener { listener.onReply(s) }

        holder.btnMore.setOnClickListener { v ->
            val pm = android.widget.PopupMenu(v.context, v)
            pm.menu.add(0, 1, 1, "Speak source")
            pm.menu.add(0, 2, 2, "Copy translation")
            pm.menu.add(0, 3, 3, "Speak translation")
            pm.menu.add(0, 4, 4, "Copy AI reply")
            pm.menu.add(0, 5, 5, "🗑 Delete")
            pm.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> listener.onSpeakSource(s)
                    2 -> listener.onCopyTranslation(s)
                    3 -> listener.onSpeakTranslation(s)
                    4 -> listener.onCopyReply(s)
                    5 -> listener.onDelete(s)
                }
                true
            }
            pm.show()
        }
    }

    fun prepend(sentence: Sentence) {
        items.add(0, sentence)
        notifyItemInserted(0)
    }

    fun update(sentence: Sentence) {
        val idx = items.indexOfFirst { it.id == sentence.id }
        if (idx >= 0) {
            items[idx] = sentence
            notifyItemChanged(idx)
        }
    }

    fun remove(sentence: Sentence) {
        val idx = items.indexOfFirst { it.id == sentence.id }
        if (idx >= 0) {
            items.removeAt(idx)
            notifyItemRemoved(idx)
        }
    }

    fun clearAll() {
        val size = items.size
        items.clear()
        notifyItemRangeRemoved(0, size)
    }

    fun all(): List<Sentence> = items.toList()
}
