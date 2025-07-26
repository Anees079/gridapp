package com.example.offgridcall

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Provides static helper methods to encrypt and decrypt byte arrays using
 * AES in GCM mode. GCM provides both confidentiality and authentication.
 * This class is used to encrypt audio frames before they are sent over
 * the network and to decrypt frames on receipt. The keys and IVs are
 * generated per call and never stored on disk.
 */
object AudioEncryptor {
    private const val KEY_SIZE_BYTES = 16 // 128 bits
    private const val IV_SIZE_BYTES = 12  // 96 bits, recommended for GCM
    private const val TAG_LENGTH_BITS = 128
    private val secureRandom = SecureRandom()

    /**
     * Generates a fresh random AES key. Keys should be generated at the start
     * of each call and exchanged securely over the signalling channel.
     */
    fun generateKey(): ByteArray {
        val key = ByteArray(KEY_SIZE_BYTES)
        secureRandom.nextBytes(key)
        return key
    }

    /**
     * Generates a fresh random IV (initialisation vector). IVs should never be
     * reused with the same key. A unique IV must be generated for each
     * encrypted packet or stream segment.
     */
    fun generateIv(): ByteArray {
        val iv = ByteArray(IV_SIZE_BYTES)
        secureRandom.nextBytes(iv)
        return iv
    }

    /**
     * Encrypts the given data with AES/GCM/NoPadding using the provided key
     * and IV. The returned array contains the ciphertext followed by the
     * authentication tag (the GCM mode appends the tag automatically). Callers
     * are responsible for prepending or sending the IV separately so the
     * recipient can decrypt.
     */
    fun encrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        return cipher.doFinal(data)
    }

    /**
     * Decrypts the given ciphertext with AES/GCM/NoPadding using the provided
     * key and IV. The same IV used during encryption must be supplied. An
     * exception will be thrown if authentication fails.
     */
    fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        return cipher.doFinal(data)
    }
}