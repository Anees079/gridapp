package com.example.offgridcall

/**
 * Represents a chat message in a conversation. A message can either be a
 * piece of text or a file notification. Only one of [text] or [fileName]
 * should be non‑null. [isLocal] indicates whether the message originated
 * locally or from the remote peer, which controls alignment and styling.
 */
data class ChatMessage(
    val text: String? = null,
    val fileName: String? = null,
    val fileSize: Int? = null,
    val isLocal: Boolean = false,
    val isFile: Boolean = false
)