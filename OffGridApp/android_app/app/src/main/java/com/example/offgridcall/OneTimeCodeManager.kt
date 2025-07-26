package com.example.offgridcall

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Responsible for generating and verifying one‑time codes used to add
 * contacts. A one‑time code can only be used once: after it has been
 * redeemed the code is removed from the internal map. The codes are
 * intentionally not persisted to disk so there is no record once the app
 * terminates. Codes are generated using a cryptographically strong random
 * number generator.
 */
object OneTimeCodeManager {
    private val random = SecureRandom()
    private val codes = ConcurrentHashMap<String, String>()

    /**
     * Generates a new one‑time code for the given contact identifier. If a
     * code already exists for the contact it will be replaced. The returned
     * code consists of a random alphanumeric string. This function is thread
     * safe.
     */
    fun generateCodeForContact(contactId: String): String {
        val code = generateRandomCode()
        codes[code] = contactId
        return code
    }

    /**
     * Redeems a code, returning the associated contact identifier if the code
     * exists and removing it from the map. Returns null if the code is
     * unknown or has already been redeemed.
     */
    fun redeemCode(code: String): String? {
        return codes.remove(code)
    }

    /**
     * Generates an eight character random alphanumeric string. The character
     * set excludes ambiguous characters (like O and 0) to avoid confusion when
     * exchanging codes verbally or through screenshots.
     */
    private fun generateRandomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val code = CharArray(8)
        for (i in code.indices) {
            val index = random.nextInt(chars.length)
            code[i] = chars[index]
        }
        return String(code)
    }
}