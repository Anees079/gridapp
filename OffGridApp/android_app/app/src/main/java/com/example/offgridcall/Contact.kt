package com.example.offgridcall

/**
 * A simple in‑memory model representing a contact that can be called. The
 * application never persists contacts to disk in order to meet the privacy
 * requirements: after the app process is killed all contacts are forgotten.
 *
 * @param id A unique identifier for the contact. This is typically derived
 * from the one‑time code used to add the contact and should be unpredictable.
 * @param name A human friendly name for the contact. This is shown in the
 * UI. Names are never stored on disk.
 */
data class Contact(
    val id: String,
    val name: String,
    /**
     * Indicates whether this contact is currently online (i.e. has an active
     * messaging session open). This flag is not persisted and is updated via
     * presence messages exchanged over the data channel. When the app starts
     * all contacts are considered offline until a presence message is
     * received.
     */
    var isOnline: Boolean = false
)