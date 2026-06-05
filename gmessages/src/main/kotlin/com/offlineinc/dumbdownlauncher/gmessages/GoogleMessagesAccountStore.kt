package com.offlineinc.dumbdownlauncher.gmessages

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * EncryptedSharedPreferences-backed persistence for a Google Messages
 * device pair. Analog of `dpad-messenger-backend/signal/SignalAccountStore`.
 *
 * What we persist after a successful QR relay pairing handshake:
 *  - `tachyonAuthToken` — bearer token Google's RPC + long-poll endpoints
 *    want (the long-lived one the phone sends in the pair confirmation)
 *  - `browserSourceId` — the device id the primary phone assigned us; goes
 *    in every subsequent RPC as "the device sending this"
 *  - `mobileSourceId` — the primary phone's device id
 *  - `ecdsaPrivatePkcs8` — our device identity private key (PKCS#8 DER), so
 *    we can refresh the relay registration after the token expires
 *  - `aesKey` / `hmacKey` — the symmetric session keys from the QR; the
 *    authenticated message session encrypts/HMACs payloads with these
 *    (see [GMCrypto]).
 */
class GoogleMessagesAccountStore(context: Context) {

    private val ctx = context.applicationContext

    private val masterKey by lazy {
        MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            ctx,
            "dpad_gmessages_account",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun save(account: GoogleMessagesAccount) {
        prefs.edit()
            .putString(KEY_TACHYON_AUTH, encode(account.tachyonAuthToken))
            .putString(KEY_BROWSER_SOURCE_ID, account.browserSourceId)
            .putString(KEY_MOBILE_SOURCE_ID, account.mobileSourceId)
            .putString(KEY_ECDSA_PRIV, encode(account.ecdsaPrivatePkcs8))
            .putString(KEY_AES, encode(account.aesKey))
            .putString(KEY_HMAC, encode(account.hmacKey))
            .apply()
    }

    fun load(): GoogleMessagesAccount? {
        val auth = prefs.getString(KEY_TACHYON_AUTH, null)?.let(::decode) ?: return null
        val browser = prefs.getString(KEY_BROWSER_SOURCE_ID, null) ?: return null
        val mobile = prefs.getString(KEY_MOBILE_SOURCE_ID, null) ?: return null
        val priv = prefs.getString(KEY_ECDSA_PRIV, null)?.let(::decode) ?: return null
        val aes = prefs.getString(KEY_AES, null)?.let(::decode) ?: return null
        val hmac = prefs.getString(KEY_HMAC, null)?.let(::decode) ?: return null
        return GoogleMessagesAccount(
            tachyonAuthToken = auth,
            browserSourceId = browser,
            mobileSourceId = mobile,
            ecdsaPrivatePkcs8 = priv,
            aesKey = aes,
            hmacKey = hmac,
        )
    }

    fun isPaired(): Boolean = prefs.contains(KEY_TACHYON_AUTH)

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun encode(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun decode(s: String) = Base64.decode(s, Base64.NO_WRAP)

    companion object {
        private const val KEY_TACHYON_AUTH = "tachyonAuthToken"
        private const val KEY_BROWSER_SOURCE_ID = "browserSourceId"
        private const val KEY_MOBILE_SOURCE_ID = "mobileSourceId"
        private const val KEY_ECDSA_PRIV = "ecdsaPrivatePkcs8"
        private const val KEY_AES = "aesKey"
        private const val KEY_HMAC = "hmacKey"
    }
}

/**
 * Plain-data record of everything we got back from (and need to persist
 * after) a successful QR relay pairing with the user's primary Android
 * phone. Serialized into EncryptedSharedPreferences by
 * [GoogleMessagesAccountStore].
 */
data class GoogleMessagesAccount(
    /** Long-lived bearer token for the authenticated session. */
    val tachyonAuthToken: ByteArray,
    /** Our device id assigned by the phone. */
    val browserSourceId: String,
    /** The primary phone's device id. */
    val mobileSourceId: String,
    /** Our device identity private key, PKCS#8 DER (for relay refresh). */
    val ecdsaPrivatePkcs8: ByteArray,
    /** Session AES-256 key (from the QR). */
    val aesKey: ByteArray,
    /** Session HMAC-SHA256 key (from the QR). */
    val hmacKey: ByteArray,
) {
    // ByteArray equality is reference-based by default — override so two
    // accounts with the same content compare equal (useful in tests).
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GoogleMessagesAccount) return false
        return tachyonAuthToken.contentEquals(other.tachyonAuthToken) &&
            browserSourceId == other.browserSourceId &&
            mobileSourceId == other.mobileSourceId &&
            ecdsaPrivatePkcs8.contentEquals(other.ecdsaPrivatePkcs8) &&
            aesKey.contentEquals(other.aesKey) &&
            hmacKey.contentEquals(other.hmacKey)
    }

    override fun hashCode(): Int {
        var r = tachyonAuthToken.contentHashCode()
        r = 31 * r + browserSourceId.hashCode()
        r = 31 * r + mobileSourceId.hashCode()
        r = 31 * r + ecdsaPrivatePkcs8.contentHashCode()
        r = 31 * r + aesKey.contentHashCode()
        r = 31 * r + hmacKey.contentHashCode()
        return r
    }
}
