package com.offlineinc.dumbdownlauncher.typesync

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto utilities for Type Sync encryption/decryption.
 * Mirrors the CryptoUtil in dumb-contact-sync and ContactSyncCrypto on iOS.
 */
object TypeSyncCrypto {

    fun decryptAesGcm(ciphertext: ByteArray, iv: ByteArray, keyHex: String): ByteArray {
        val key = SecretKeySpec(hexToBytes(keyHex), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    fun hmacSha256Hex(data: ByteArray, keyHex: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hexToBytes(keyHex), "HmacSHA256"))
        return bytesToHex(mac.doFinal(data))
    }

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
