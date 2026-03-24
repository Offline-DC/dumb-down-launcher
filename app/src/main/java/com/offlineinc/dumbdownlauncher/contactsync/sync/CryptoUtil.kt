package com.offlineinc.dumbdownlauncher.contactsync.sync

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

object CryptoUtil {
    fun encryptAesGcm(plaintext: ByteArray, keyHex: String): Pair<ByteArray, ByteArray> {
        val key = SecretKeySpec(hexToBytes(keyHex), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return ciphertext to iv
    }

    fun decryptAesGcm(ciphertext: ByteArray, iv: ByteArray, keyHex: String): ByteArray {
        val key = SecretKeySpec(hexToBytes(keyHex), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return bytesToHex(digest.digest(data))
    }

    fun hmacSha256Hex(data: ByteArray, keyHex: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hexToBytes(keyHex), "HmacSHA256"))
        return bytesToHex(mac.doFinal(data))
    }

    fun toBase64(data: ByteArray): String = Base64.encodeToString(data, Base64.NO_WRAP)
    fun fromBase64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
