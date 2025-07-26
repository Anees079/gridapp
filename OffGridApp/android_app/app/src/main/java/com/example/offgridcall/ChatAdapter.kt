package com.example.offgridcall

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView adapter that displays chat messages. Messages are aligned to
 * the left or right depending on whether they originate from the remote
 * peer or the local user. File messages are displayed as a simple line of
 * text indicating the file name and size.
 */
class ChatAdapter(private val messages: MutableList<ChatMessage>) :
    RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val message = messages[position]
        holder.bind(message)
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.text_message)
        fun bind(message: ChatMessage) {
            // Set the message text
            if (message.isFile) {
                val size = message.fileSize ?: 0
                textView.text = "Received file: ${message.fileName} (${size} bytes)"
            } else {
                textView.text = message.text
            }
            // Align text based on sender
            val params = textView.layoutParams as ViewGroup.MarginLayoutParams
            if (message.isLocal) {
                textView.gravity = Gravity.END
                params.marginStart = 64
                params.marginEnd = 0
                textView.setBackgroundResource(android.R.color.holo_blue_light)
            } else {
                textView.gravity = Gravity.START
                params.marginStart = 0
                params.marginEnd = 64
                textView.setBackgroundResource(android.R.color.darker_gray)
            }
            textView.layoutParams = params
        }
    }
}