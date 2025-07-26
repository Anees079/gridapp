package com.example.offgridcall

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import android.net.Uri
import android.content.Intent
import java.security.MessageDigest
import android.app.Activity
import kotlin.text.Charsets
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription

/**
 * Activity responsible for handling an active call. It creates a
 * [PeerConnectionManager] for the call and uses a simple in‑memory signalling
 * mechanism for demonstration. In a real application the signalling would be
 * done over a secure network channel (e.g. WebSocket) to exchange offers,
 * answers and ICE candidates. For simplicity this sample starts the call
 * locally and displays the UI without actually connecting two devices.
 */
class CallActivity : AppCompatActivity(), PeerConnectionManager.SignalingCallback {

    private lateinit var peerConnectionManager: PeerConnectionManager
    private lateinit var contact: Contact
    // RecyclerView and adapter for chat messages
    private lateinit var recyclerChat: androidx.recyclerview.widget.RecyclerView
    private lateinit var messageAdapter: ChatAdapter
    private lateinit var editMessage: android.widget.EditText
    private lateinit var attachButton: android.widget.ImageButton
    private lateinit var sendButton: android.widget.Button
    private val messages = mutableListOf<ChatMessage>()
    private lateinit var messageKey: ByteArray

    // Request code for file picking
    private val REQUEST_PICK_FILE = 1001

    // Salt used to derive a deterministic encryption key per contact. In a
    // production system this should be replaced by a proper key exchange.
    private val KEY_SALT = "MSG_KEY_SALT"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent screenshots and screen recordings for additional privacy. This
        // flag ensures the content of this activity cannot be captured by
        // Android's built‑in screen recorders or by other apps.
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_call)

        val contactId = intent.getStringExtra(EXTRA_CONTACT_ID)
        val found = contactId?.let { ContactManager.getContact(it) }
        if (found == null) {
            Toast.makeText(this, "Unknown contact", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        contact = found

        // Compute a shared symmetric key for messaging based on the contact ID.
        messageKey = java.security.MessageDigest.getInstance("SHA-256")
            .digest((contact.id + KEY_SALT).toByteArray())
            .copyOf(16)

        // Set up UI elements
        val textContact: TextView = findViewById(R.id.text_contact)
        textContact.text = contact.name
        recyclerChat = findViewById(R.id.recycler_chat)
        recyclerChat.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        messageAdapter = ChatAdapter(messages)
        recyclerChat.adapter = messageAdapter
        editMessage = findViewById(R.id.edit_message)
        attachButton = findViewById(R.id.button_attach)
        sendButton = findViewById(R.id.button_send)

        // Initialise the peer connection manager and start a call. In a real
        // application you would wait for both parties to be ready and exchange
        // keys/SDP through a secure channel. Here we simply create a peer
        // connection and generate an offer.
        peerConnectionManager = PeerConnectionManager(this, this)
        peerConnectionManager.initialize()
        peerConnectionManager.createPeerConnection()
        peerConnectionManager.setDataCallback { data -> handleIncomingData(data) }
        // Generate an offer; the onOfferCreated callback will be invoked.
        peerConnectionManager.createOffer()

        // Announce online presence for call chat
        sendPresence(true)

        // Set up listeners for sending messages and attaching files
        sendButton.setOnClickListener {
            val text = editMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                sendEncryptedMessage(text)
                editMessage.text.clear()
            }
        }
        attachButton.setOnClickListener {
            selectFile()
        }

        val endButton: Button = findViewById(R.id.button_end_call)
        endButton.setOnClickListener {
            finishCall()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        finishCall()
    }

    private fun finishCall() {
        // Announce offline presence before shutting down
        sendPresence(false)
        peerConnectionManager.close()
        finish()
    }

    /**
     * Sends a presence message (online/offline) over the data channel. This
     * allows the remote peer to update the online status of this contact.
     */
    private fun sendPresence(isOnline: Boolean) {
        val statusText = if (isOnline) "ONLINE" else "OFFLINE"
        val iv = AudioEncryptor.generateIv()
        val cipher = AudioEncryptor.encrypt(statusText.toByteArray(Charsets.UTF_8), messageKey, iv)
        val payload = ByteArray(1 + iv.size + cipher.size)
        payload[0] = 2.toByte()
        System.arraycopy(iv, 0, payload, 1, iv.size)
        System.arraycopy(cipher, 0, payload, 1 + iv.size, cipher.size)
        peerConnectionManager.sendData(payload)
        // Update local manager for our contact (we don't update remote status)
        ContactManager.setOnlineStatus(contact.id, isOnline)
    }

    /**
     * Handles incoming raw data from the WebRTC data channel. Parses the
     * message, decrypts the payload and updates the chat UI accordingly.
     */
    private fun handleIncomingData(data: ByteArray) {
        // Data format: first byte = type (0=text, 1=file). Next 12 bytes = IV.
        if (data.isEmpty()) return
        val type = data[0]
        // Copy IV (12 bytes) from positions 1..12 inclusive
        if (data.size < 1 + 12) return
        val iv = data.copyOfRange(1, 13)
        if (type.toInt() == 0) {
            // Text message: rest of data is ciphertext
            val cipher = data.copyOfRange(13, data.size)
            try {
                val plain = AudioEncryptor.decrypt(cipher, messageKey, iv)
                val text = String(plain, Charsets.UTF_8)
                runOnUiThread {
                    messageAdapter.addMessage(
                        ChatMessage(text = text, isLocal = false, isFile = false)
                    )
                    recyclerChat.smoothScrollToPosition(messageAdapter.itemCount - 1)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt message", e)
            }
        } else if (type.toInt() == 1) {
            // File: next two bytes (unsigned short) indicate file name length
            if (data.size < 13 + 2) return
            val nameLenBytes = data.copyOfRange(13, 15)
            val nameLen = ((nameLenBytes[0].toInt() and 0xFF) shl 8) or (nameLenBytes[1].toInt() and 0xFF)
            if (data.size < 15 + nameLen) return
            val nameBytes = data.copyOfRange(15, 15 + nameLen)
            val fileName = String(nameBytes, Charsets.UTF_8)
            val cipher = data.copyOfRange(15 + nameLen, data.size)
            val fileData = try {
                AudioEncryptor.decrypt(cipher, messageKey, iv)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt file", e)
                return
            }
            // For demonstration we do not persist the file; we just notify the user
            runOnUiThread {
                messageAdapter.addMessage(
                    ChatMessage(
                        fileName = fileName,
                        fileSize = fileData.size,
                        isLocal = false,
                        isFile = true
                    )
                )
                recyclerChat.smoothScrollToPosition(messageAdapter.itemCount - 1)
                Toast.makeText(this, "Received file ${fileName}", Toast.LENGTH_SHORT).show()
            }
        }
        else if (type.toInt() == 2) {
            // Presence message: update status
            val cipher = data.copyOfRange(13, data.size)
            try {
                val plain = AudioEncryptor.decrypt(cipher, messageKey, iv)
                val status = String(plain, Charsets.UTF_8)
                val isOnline = status.equals("ONLINE", ignoreCase = true)
                ContactManager.setOnlineStatus(contact.id, isOnline)
                // Optionally update UI: not needed in call screen
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt presence message", e)
            }
        }
    }

    /**
     * Encrypts a plain text message and sends it over the data channel.
     */
    private fun sendEncryptedMessage(text: String) {
        val iv = AudioEncryptor.generateIv()
        val cipher = AudioEncryptor.encrypt(text.toByteArray(Charsets.UTF_8), messageKey, iv)
        val payload = ByteArray(1 + iv.size + cipher.size)
        payload[0] = 0 // type 0 for text
        System.arraycopy(iv, 0, payload, 1, iv.size)
        System.arraycopy(cipher, 0, payload, 1 + iv.size, cipher.size)
        peerConnectionManager.sendData(payload)
        // Update UI with local message
        messageAdapter.addMessage(
            ChatMessage(text = text, isLocal = true, isFile = false)
        )
        recyclerChat.smoothScrollToPosition(messageAdapter.itemCount - 1)
    }

    /**
     * Initiates an intent to allow the user to pick a file from the device.
     */
    private fun selectFile() {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        // Optionally allow multiple selection: not necessary here
        startActivityForResult(intent, REQUEST_PICK_FILE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_FILE && resultCode == android.app.Activity.RESULT_OK) {
            data?.data?.let { uri ->
                sendEncryptedFile(uri)
            }
        }
    }

    /**
     * Reads a file from the given URI, encrypts its contents and sends it
     * over the data channel. The file name and size are included in the
     * payload for the recipient.
     */
    private fun sendEncryptedFile(uri: android.net.Uri) {
        try {
            // Read bytes from the content resolver
            val input = contentResolver.openInputStream(uri) ?: return
            val fileBytes = input.use { it.readBytes() }
            // Derive file name from the URI
            var fileName = uri.lastPathSegment ?: "file"
            // Some content providers include the full path; extract after last '/'
            val slashIndex = fileName.lastIndexOf('/')
            if (slashIndex >= 0 && slashIndex < fileName.length - 1) {
                fileName = fileName.substring(slashIndex + 1)
            }
            val nameBytes = fileName.toByteArray(Charsets.UTF_8)
            if (nameBytes.size > 0xFFFF) {
                Toast.makeText(this, "File name too long", Toast.LENGTH_SHORT).show()
                return
            }
            val iv = AudioEncryptor.generateIv()
            val cipher = AudioEncryptor.encrypt(fileBytes, messageKey, iv)
            // Build payload: 1 byte type, 12 bytes IV, 2 bytes name length, name, cipher
            val payload = ByteArray(1 + iv.size + 2 + nameBytes.size + cipher.size)
            var offset = 0
            payload[offset++] = 1 // type = file
            System.arraycopy(iv, 0, payload, offset, iv.size)
            offset += iv.size
            // Encode name length as unsigned short
            payload[offset++] = ((nameBytes.size shr 8) and 0xFF).toByte()
            payload[offset++] = (nameBytes.size and 0xFF).toByte()
            System.arraycopy(nameBytes, 0, payload, offset, nameBytes.size)
            offset += nameBytes.size
            System.arraycopy(cipher, 0, payload, offset, cipher.size)
            peerConnectionManager.sendData(payload)
            // Update UI with local file message
            messageAdapter.addMessage(
                ChatMessage(
                    fileName = fileName,
                    fileSize = fileBytes.size,
                    isLocal = true,
                    isFile = true
                )
            )
            recyclerChat.smoothScrollToPosition(messageAdapter.itemCount - 1)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send file", e)
            Toast.makeText(this, "Failed to send file", Toast.LENGTH_SHORT).show()
        }
    }

    /* SignalingCallback implementation. For demonstration purposes these
     * callbacks simply log the SDP and ICE candidates. In a real system you
     * would transmit these payloads over a secure signalling channel to the
     * remote peer, then apply the remote answer or candidates as they arrive.
     */
    override fun onOfferCreated(offer: SessionDescription) {
        Log.d(TAG, "Offer created: ${offer.description}")
        // In a real implementation, send this offer to the remote peer and
        // wait for an answer. For demonstration we simply pretend the remote
        // accepted and call handleRemoteAnswer() with the same description.
        // Note: this is not a real call – it is only to illustrate the flow.
        peerConnectionManager.handleRemoteAnswer(offer)
    }

    override fun onAnswerCreated(answer: SessionDescription) {
        Log.d(TAG, "Answer created: ${answer.description}")
        // Not used in this demonstration since we loop back the offer.
    }

    override fun onIceCandidateGenerated(candidate: IceCandidate) {
        Log.d(TAG, "ICE candidate: ${candidate.sdp}")
        // In a real implementation, send this candidate to the remote peer.
    }

    companion object {
        const val EXTRA_CONTACT_ID = "extra_contact_id"
        private const val TAG = "CallActivity"
    }
}