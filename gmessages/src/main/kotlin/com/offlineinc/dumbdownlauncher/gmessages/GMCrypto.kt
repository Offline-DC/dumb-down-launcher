package com.offlineinc.dumbdownlauncher.gmessages

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cryptographic primitives the Google Messages pairing flow needs.
 *
 * The handshake mirrors Signal's at a high level — Curve25519 ECDH to derive
 * a shared secret, then AES-CTR for symmetric encryption of the actual
 * pairing payload — but the wire format and HKDF info strings are different,
 * so we can't reuse Signal's helpers directly.
 *
 * Reference: mautrix-signal `pkg/libgm/crypto/aes_ctr.go` +
 * `pkg/libgm/util/ecdh.go`. The key generation, ECDH, and HKDF inputs are
 * all the same shape — the only thing Google-specific is the HKDF `info`
 * string (`"client_init"` for the client's half of the handshake).
 */
internal object GMCrypto {

    /** Curve25519 keypair for the pairing handshake. Lives only in memory
     *  — discarded as soon as pairing completes (or fails). Keys are raw
     *  32-byte arrays (no libsignal 0x05 type-prefix). */
    fun generateEphemeralKeyPair(): X25519.KeyPair = X25519.generateKeyPair()

    /**
     * X25519 ECDH between our private key and the primary phone's public
     * key (delivered in the encrypted PairingResponse). Both keys are raw
     * 32-byte little-endian arrays; returns the 32-byte shared secret.
     *
     * Backed by our in-repo [X25519] (TweetNaCl port) — replaces the
     * libsignal-android dependency, which we pulled in only for this one
     * primitive and which broke the launcher's dexing pipeline.
     */
    fun ecdh(ourPrivate: ByteArray, theirPublic: ByteArray): ByteArray =
        X25519.sharedSecret(ourPrivate, theirPublic)

    /**
     * HKDF (RFC 5869) extract+expand to derive a symmetric key from the
     * ECDH shared secret. mautrix-gmessages uses HKDF-SHA256 with an empty
     * salt and a Google-defined `info` string per handshake stage.
     *
     * @param ikm input keying material (the raw ECDH output, 32 bytes)
     * @param info domain-separation string (e.g. `"client_init"`)
     * @param length number of bytes to derive (usually 32 for AES-256)
     */
    fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int, salt: ByteArray = ByteArray(32)): ByteArray {
        // Extract: PRK = HMAC-SHA256(salt, IKM)
        val prk = hmacSha256(salt, ikm)

        // Expand: T(1) = HMAC-SHA256(PRK, info || 0x01), T(2) = HMAC-SHA256(PRK, T(1) || info || 0x02), …
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            val input = t + info + byteArrayOf(counter.toByte())
            t = hmacSha256(prk, input)
            val toCopy = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, toCopy)
            pos += toCopy
            counter++
        }
        return out
    }

    /**
     * AES-256-CTR encrypt. Google Messages pairs use CTR (not CBC, unlike
     * Signal attachments) for the encrypted PairingResponse body. Caller
     * supplies the 32-byte AES key + a 16-byte IV (random per message).
     */
    fun aesCtrEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == 32) { "AES-CTR key must be 32 bytes (got ${key.size})" }
        require(iv.size == 16) { "AES-CTR IV must be 16 bytes (got ${iv.size})" }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(plaintext)
    }

    /** AES-256-CTR decrypt — counterpart to [aesCtrEncrypt]. */
    fun aesCtrDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
        require(key.size == 32) { "AES-CTR key must be 32 bytes (got ${key.size})" }
        require(iv.size == 16) { "AES-CTR IV must be 16 bytes (got ${iv.size})" }
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ciphertext)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
