package com.example.offgridcall

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * The main activity displays a list of contacts that have been added via
 * one‑time codes. Users can initiate calls by tapping on a contact. A
 * floating action button allows the user to add new contacts by entering a
 * code and a name. All state is kept in memory only.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ContactAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        recyclerView = findViewById(R.id.recycler_contacts)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = ContactAdapter(
            onCallClick = { contact ->
                // Start the call activity
                val intent = Intent(this@MainActivity, CallActivity::class.java)
                intent.putExtra(CallActivity.EXTRA_CONTACT_ID, contact.id)
                startActivity(intent)
            },
            onChatClick = { contact ->
                // Start the chat activity
                val intent = Intent(this@MainActivity, ChatActivity::class.java)
                intent.putExtra(ChatActivity.EXTRA_CONTACT_ID, contact.id)
                startActivity(intent)
            }
        )
        recyclerView.adapter = adapter

        val buttonAdd: Button = findViewById(R.id.button_add_contact)
        buttonAdd.setOnClickListener { showAddContactDialog() }

        refreshContacts()
    }

    override fun onResume() {
        super.onResume()
        refreshContacts()
    }

    private fun refreshContacts() {
        adapter.submitList(ContactManager.getAllContacts())
    }

    private fun showAddContactDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_add_contact, null)
        val codeInput: EditText = dialogView.findViewById(R.id.edit_code)
        val nameInput: EditText = dialogView.findViewById(R.id.edit_name)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.add_contact))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.confirm), null)
            .setNegativeButton(getString(R.string.cancel)) { d, _ -> d.dismiss() }
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.setOnClickListener {
                val code = codeInput.text.toString().trim().uppercase()
                val name = nameInput.text.toString().trim()
                if (code.isEmpty() || name.isEmpty()) {
                    Toast.makeText(this, R.string.invalid_code_or_name, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val contactId = OneTimeCodeManager.redeemCode(code)
                if (contactId != null) {
                    val contact = Contact(contactId, name)
                    val added = ContactManager.addContact(contact)
                    if (added) {
                        Toast.makeText(this, R.string.contact_added, Toast.LENGTH_SHORT).show()
                        refreshContacts()
                    } else {
                        Toast.makeText(this, R.string.contact_already_exists, Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                } else {
                    Toast.makeText(this, R.string.invalid_one_time_code, Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.show()
    }

    /**
     * RecyclerView adapter that displays a simple list of contacts. Each
     * contact item shows the name and an invite code and triggers the onClick
     * callback when tapped.
     */
    private class ContactAdapter(
        private val onCallClick: (Contact) -> Unit,
        private val onChatClick: (Contact) -> Unit
    ) : RecyclerView.Adapter<ContactAdapter.ViewHolder>() {
        private var contacts: List<Contact> = emptyList()

        fun submitList(list: List<Contact>) {
            contacts = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_contact, parent, false)
            return ViewHolder(view, onCallClick, onChatClick)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val contact = contacts[position]
            holder.bind(contact)
        }

        override fun getItemCount(): Int = contacts.size

        class ViewHolder(
            itemView: View,
            private val onCallClick: (Contact) -> Unit,
            private val onChatClick: (Contact) -> Unit
        ) : RecyclerView.ViewHolder(itemView) {
            private val nameText: TextView = itemView.findViewById(R.id.text_contact_name)
            private val statusText: TextView = itemView.findViewById(R.id.text_contact_status)
            private val callButton: android.widget.ImageButton = itemView.findViewById(R.id.button_call)
            private val chatButton: android.widget.ImageButton = itemView.findViewById(R.id.button_chat)
            fun bind(contact: Contact) {
                nameText.text = contact.name
                statusText.text = if (contact.isOnline) {
                    statusText.setTextColor(itemView.resources.getColor(android.R.color.holo_green_dark, null))
                    itemView.context.getString(R.string.online)
                } else {
                    statusText.setTextColor(itemView.resources.getColor(android.R.color.darker_gray, null))
                    itemView.context.getString(R.string.offline)
                }
                callButton.setOnClickListener { onCallClick(contact) }
                chatButton.setOnClickListener { onChatClick(contact) }
            }
        }
    }
}