package com.example.offgridcall

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the list of contacts that can be called. This manager keeps
 * everything in memory only; there is intentionally no persistence layer so
 * that when the application process is destroyed there is no trace of the
 * contacts or the calls. The API is thread safe and can be used from
 * background threads.
 */
object ContactManager {
    // A thread‑safe set of contacts keyed by their unique identifier.
    private val contacts = ConcurrentHashMap<String, Contact>()

    /**
     * Returns a snapshot of all contacts currently in the manager. The
     * returned list is immutable so callers cannot modify internal state.
     */
    fun getAllContacts(): List<Contact> {
        return Collections.unmodifiableList(contacts.values.toList())
    }

    /**
     * Adds a new contact to the manager. If the contact already exists (same
     * identifier) then this is a no‑op. Returns true if the contact was added
     * successfully and false if it already existed.
     */
    fun addContact(contact: Contact): Boolean {
        return contacts.putIfAbsent(contact.id, contact) == null
    }

    /**
     * Looks up a contact by their identifier. Returns null if the contact is
     * not known.
     */
    fun getContact(id: String): Contact? {
        return contacts[id]
    }

    /**
     * Updates the online status of a contact. This does nothing if the
     * contact does not exist. Status changes are kept in memory only and are
     * lost when the process terminates.
     */
    fun setOnlineStatus(id: String, isOnline: Boolean) {
        contacts[id]?.let { it.isOnline = isOnline }
    }

    /**
     * Returns true if the contact is currently marked as online.
     */
    fun isOnline(id: String): Boolean {
        return contacts[id]?.isOnline ?: false
    }
}