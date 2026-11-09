package dev.irshadm.volta.data.ismart

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.irshadm.volta.core.ismart.Credentials
import dev.irshadm.volta.core.ismart.LoginFrame
import dev.irshadm.volta.core.ismart.SessionStore
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small encrypted key-value store: AES-256-GCM with a non-exportable Android Keystore key.
 * Holds the MG credentials, the session token and the control-PIN hash. The hash is PIN-equivalent (see
 * MgCrypto.pinHash), which is why it lives here and not in plain preferences.
 * Excluded from backups (see data_extraction_rules.xml).
 */
class SecureStore(context: Context) : SessionStore {
    private val prefs = context.getSharedPreferences("secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    @Synchronized
    fun put(name: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(name).apply()
            return
        }
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = c.iv + c.doFinal(value.toByteArray())
        prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    @Synchronized
    fun get(name: String): String? {
        val b64 = prefs.getString(name, null) ?: return null
        return runCatching {
            val blob = Base64.decode(b64, Base64.NO_WRAP)
            val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12)) }
            String(c.doFinal(blob, 12, blob.size - 12))
        }.getOrNull()
    }

    var credentials: Credentials?
        get() {
            val phone = get(PHONE) ?: return null
            val pass = get(PASSWORD) ?: return null
            return Credentials(phone, pass)
        }
        set(value) {
            put(PHONE, value?.phone)
            put(PASSWORD, value?.password)
        }

    var pinHash: String?
        get() = get(PIN_HASH)
        set(value) = put(PIN_HASH, value)

    /** The first-run PIN step was skipped for this login. */
    var pinSkipped: Boolean
        get() = get(PIN_SKIPPED) != null
        set(value) = put(PIN_SKIPPED, if (value) "1" else null)

    override fun load(): LoginFrame.Session? {
        val uid = get(UID) ?: return null
        val token = get(TOKEN) ?: return null
        return LoginFrame.Session(uid, token)
    }

    override fun save(session: LoginFrame.Session?) {
        put(UID, session?.uid)
        put(TOKEN, session?.token)
    }

    /**
     * Forgets the MG login, session, PIN hash and whether the PIN step was skipped. Only these: the same encrypted file also holds the Google Maps key,
     * which stays through a sign-out.
     */
    fun clearLogin() = prefs.edit().apply { listOf(PHONE, PASSWORD, UID, TOKEN, PIN_HASH, PIN_SKIPPED).forEach { remove(it) } }.apply()

    private companion object {
        const val ALIAS = "volta_secure"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val PHONE = "mg.phone"
        const val PASSWORD = "mg.password"
        const val UID = "mg.uid"
        const val TOKEN = "mg.token"
        const val PIN_HASH = "mg.pin_hash"
        const val PIN_SKIPPED = "mg.pin_skipped"
    }
}
