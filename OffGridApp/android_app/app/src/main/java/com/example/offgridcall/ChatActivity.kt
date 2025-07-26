package com.example.offgridcall

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.security.MessageDigest
import kotlin.text.Charsets

/**
 * Activity that provides a secure messaging interface with a contact. Messages
 * and files are exchanged via a WebRTC data channel and encrypted using
 * AES/GCM. Presence messages are also sent to indicate when a user is
 * online or offline. A call button allows initiating a voice call from
 * within the chat.
 */
class ChatActivity : AppCompatActivity(), PeerConnectionManager.SignalingCallback {

    private lateinit var peerConnectionManager: PeerConnectionManager
    private lateinit var contact: Contact
    private lateinit var recyclerChat: RecyclerView
    private lateinit var messageAdapter: ChatAdapter
    private lateinit var editMessage: EditText
    private lateinit var attachButton: ImageButton
    private lateinit var sendButton: Button
    private lateinit var statusText: TextView
    private val messages = mutableListOf<ChatMessage>()
    private lateinit var messageKey: ByteArray

    private val REQUEST_PICK_FILE = 2001
    private val KEY_SALT = "MSG_KEY_SALT"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Prevent screenshots or recordings
        window.setFlags(
            android.view.WindowManager.LayoutParams.FLAG_SECURE,
            android.view.WindowManager.LayoutParams.FLAG_SECURE
        )
        setContentView(R.layout.activity_chat)

        val contactId = intent.getStringExtra(EXTRA_CONTACT_ID)
        val found = contactId?.let { ContactManager.getContact(it) }
        if (found == null) {
            Toast.makeText(this, "Unknown contact", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        contact = found
        // Derive a deterministic encryption key per contact
        messageKey = MessageDigest.getInstance("SHA-256")
            .digest((contact.id + KEY_SALT).toByteArray())
            .copyOf(16)

        // Set up UI
        val nameText: TextView = findViewById(R.id.text_contact_name)
        nameText.text = contact.name
        statusText = findViewById(R.id.text_contact_status)
        updateStatusDisplay(contact.isOnline)

        recyclerChat = findViewById(R.id.recycler_chat)
        recyclerChat.layoutManager = LinearLayoutManager(this)
        messageAdapter = ChatAdapter(messages)
        recyclerChat.adapter = messageAdapter
        editMessage = findViewById(R.id.edit_message)
        attachButton = findViewById(R.id.button_attach)
        sendButton = findViewById(R.id.button_send)

        // Set up call button
        val callButton: Button = findViewById(R.id.button_start_call)
        callButton.setOnClickListener {
            // Start voice call activity
            val intent = Intent(this@ChatActivity, CallActivity::class.java)
            intent.putExtra(CallActivity.EXTRA_CONTACT_ID, contact.id)
            startActivity(intent)
        }

        // Initialise peer connection and data channel
        peerConnectionManager = PeerConnectionManager(this, this)
        peerConnectionManager.initialize()
        peerConnectionManager.createPeerConnection()
        peerConnectionManager.setDataCallback { data -> handleIncomingData(data) }
        // Create offer to start connection
        peerConnectionManager.createOffer()

        // Immediately announce online presence
        sendPresence(true)

        sendButton.setOnClickListener {
            val text = editMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                sendEncryptedMessage(text)
                editMessage.text.clear()
            }
        }
        attachButton.setOnClickListener { selectFile() }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Announce offline presence and close connection
        sendPresence(false)
        peerConnectionManager.close()
    }

    private fun selectFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        startActivityForResult(intent, REQUEST_PICK_FILE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_FILE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                sendEncryptedFile(uri)
            }
        }
    }

    private fun updateStatusDisplay(isOnline: Boolean) {
        runOnUiThread {
            statusText.text = if (isOnline) getString(R.string.online) else getString(R.string.offline)
            val colorRes = if (isOnline) android.R.color.holo_green_dark else android.R.color.darker_gray
            statusText.setTextColor(resources.getColor(colorRes, null))
        }
    }

    private fun handleIncomingData(data: ByteArray) {
        if (data.isEmpty()) return
        val type = data[0]
        val iv = data.copyOfRange(1, 13)
        when (type.toInt()) {
            0 -> { // text
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
            }
            1 -> { // file
                if (data.size < 15) return
                val nameLenBytes = data.copyOfRange(13, 15)
                val nameLen = ((nameLenBytes[0].toInt() and 0xFF) shl 8) or (nameLenBytes[1].toInt() and 0xFF)
                if (data.size < 15 + nameLen) return
                val nameBytes = data.copyOfRange(15, 15 + nameLen)
                val fileName = String(nameBytes, Charsets.UTF_8)
                val cipher = data.copyOfRange(15 + nameLen, data.size)
                try {
                    val fileData = AudioEncryptor.decrypt(cipher, messageKey, iv)
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
                        Toast.makeText(this, "Received file $fileName", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decrypt file", e)
                }
            }
            2 -> { // presence
                val cipher = data.copyOfRange(13, data.size)
                try {
                    val plain = AudioEncryptor.decrypt(cipher, messageKey, iv)
                    val status = String(plain, Charsets.UTF_8)
                    val isOnline = status.equals("ONLINE", ignoreCase = true)
                    // Update contact status in manager and UI
                    ContactManager.setOnlineStatus(contact.id, isOnline)
                    updateStatusDisplay(isOnline)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decrypt presence", e)
                }
            }
        }
    }

    private fun sendEncryptedMessage(text: String) {
        val iv = AudioEncryptor.generateIv()
        val cipher = AudioEncryptor.encrypt(text.toByteArray(Charsets.UTF_8), messageKey, iv)
        val payload = ByteArray(1 + iv.size + cipher.size)
        payload[0] = 0
        System.arraycopy(iv, 0, payload, 1, iv.size)
        System.arraycopy(cipher, 0, payload, 1 + iv.size, cipher.size)
        peerConnectionManager.sendData(payload)
        messageAdapter.addMessage(
            ChatMessage(text = text, isLocal = true, isFile = false)
        )
        recyclerChat.smoothScrollToPosition(messageAdapter.itemCount - 1)
    }

    private fun sendEncryptedFile(uri: Uri) {
        try {
            val input = contentResolver.openInputStream(uri) ?: return
            val fileBytes = input.use { it.readBytes() }
            var fileName = uri.lastPathSegment ?: "file"
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
            val payload = ByteArray(1 + iv.size + 2 + nameBytes.size + cipher.size)
            var offset = 0
            payload[offset++] = 1
            System.arraycopy(iv, 0, payload, offset, iv.size)
            offset += iv.size
            payload[offset++] = ((nameBytes.size shr 8) and 0xFF).toByte()
            payload[offset++] = (nameBytes.size and 0xFF).toByte()
            System.arraycopy(nameBytes, 0, payload, offset, nameBytes.size)
            offset += nameBytes.size
            System.arraycopy(cipher, 0, payload, offset, cipher.size)
            peerConnectionManager.sendData(payload)
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

    private fun sendPresence(isOnline: Boolean) {
        val statusText = if (isOnline) "ONLINE" else "OFFLINE"
        val iv = AudioEncryptor.generateIv()
        val cipher = AudioEncryptor.encrypt(statusText.toByteArray(Charsets.UTF_8), messageKey, iv)
        val payload = ByteArray(1 + iv.size + cipher.size)
        payload[0] = 2.toByte()
        System.arraycopy(iv, 0, payload, 1, iv.size)
        System.arraycopy(cipher, 0, payload, 1 + iv.size, cipher.size)
        peerConnectionManager.sendData(payload)
        // Update own status in contact manager (local contact's status not changed)
        if (isOnline) {
            ContactManager.setOnlineStatus(contact.id, true)
        } else {
            ContactManager.setOnlineStatus(contact.id, false)
        }
    }

    // Signalling callback stubs; they behave like in CallActivity
    override fun onOfferCreated(offer: org.webrtc.SessionDescription) {
        Log.d(TAG, "Offer created: ${offer.description}")
        peerConnectionManager.handleRemoteAnswer(offer)
    }
    override fun onAnswerCreated(answer: org.webrtc.SessionDescription) {
        Log.d(TAG, "Answer created: ${answer.description}")
    }
    override fun onIceCandidateGenerated(candidate: org.webrtc.IceCandidate) {
        Log.d(TAG, "ICE candidate: ${candidate.sdp}")
    }

    companion object {
        const val EXTRA_CONTACT_ID = "extra_contact_id"
        private const val TAG = "ChatActivity"
    }
}