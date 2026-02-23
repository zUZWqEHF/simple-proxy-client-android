package com.simple.proxyconnect.service

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoService {

    private val secureRandom = SecureRandom()

    /**
     * Derive key from password using EVP_BytesToKey (OpenSSL compatible).
     * Used for Shadowsocks master key derivation.
     */
    fun evpBytesToKey(password: String, keyLen: Int): ByteArray {
        val passBytes = password.toByteArray(Charsets.UTF_8)
        val key = ByteArrayOutputStream()
        var lastHash = byteArrayOf()

        while (key.size() < keyLen) {
            val md = MessageDigest.getInstance("MD5")
            md.update(lastHash)
            md.update(passBytes)
            lastHash = md.digest()
            key.write(lastHash)
        }
        return key.toByteArray().copyOfRange(0, keyLen)
    }

    /**
     * HKDF-SHA1 for Shadowsocks subkey derivation.
     */
    fun ssSubkey(masterKey: ByteArray, salt: ByteArray): ByteArray {
        return hkdf(
            algorithm = "HmacSHA1",
            ikm = masterKey,
            salt = salt,
            info = "ss-subkey".toByteArray(Charsets.UTF_8),
            length = masterKey.size
        )
    }

    /**
     * HKDF-SHA256 for SimpleProtocol session key derivation.
     */
    fun deriveSimpleSessionKey(psk: ByteArray, nonce: ByteArray): ByteArray {
        return hkdf(
            algorithm = "HmacSHA256",
            ikm = psk,
            salt = nonce,
            info = "simple-data".toByteArray(Charsets.UTF_8),
            length = 32
        )
    }

    /**
     * HKDF-SHA256 with custom info for directional keys.
     */
    fun hkdfSHA256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        return hkdf("HmacSHA256", ikm, salt, info, length)
    }

    /**
     * Generic HKDF implementation (RFC 5869).
     */
    private fun hkdf(algorithm: String, ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        // Extract
        val mac = Mac.getInstance(algorithm)
        val saltKey = if (salt.isEmpty()) {
            ByteArray(mac.macLength)
        } else {
            salt
        }
        mac.init(SecretKeySpec(saltKey, algorithm))
        val prk = mac.doFinal(ikm)

        // Expand
        val result = ByteArrayOutputStream()
        var prev = byteArrayOf()
        var i: Byte = 1
        while (result.size() < length) {
            val expandMac = Mac.getInstance(algorithm)
            expandMac.init(SecretKeySpec(prk, algorithm))
            expandMac.update(prev)
            expandMac.update(info)
            expandMac.update(byteArrayOf(i))
            prev = expandMac.doFinal()
            result.write(prev)
            i++
        }
        return result.toByteArray().copyOfRange(0, length)
    }

    /**
     * AES-GCM encryption. Returns ciphertext + 16-byte tag.
     */
    fun encryptAESGCM(plaintext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        return cipher.doFinal(plaintext)
    }

    /**
     * AES-GCM decryption. Input is ciphertext + 16-byte tag.
     */
    fun decryptAESGCM(ciphertextWithTag: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
        return cipher.doFinal(ciphertextWithTag)
    }

    /**
     * HMAC-SHA256
     */
    fun hmacSHA256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /**
     * Generate cryptographically random bytes.
     */
    fun randomBytes(count: Int): ByteArray {
        val bytes = ByteArray(count)
        secureRandom.nextBytes(bytes)
        return bytes
    }

    /**
     * Build a 12-byte nonce from a 4-byte prefix and an 8-byte big-endian counter.
     */
    fun makeNonce(counter: Long, prefix: ByteArray): ByteArray {
        val nonce = ByteArray(12)
        System.arraycopy(prefix, 0, nonce, 0, minOf(4, prefix.size))
        for (i in 0 until 8) {
            nonce[4 + i] = ((counter shr (56 - i * 8)) and 0xFF).toByte()
        }
        return nonce
    }

    /**
     * Hex string to ByteArray.
     */
    fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * ByteArray to hex string.
     */
    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
