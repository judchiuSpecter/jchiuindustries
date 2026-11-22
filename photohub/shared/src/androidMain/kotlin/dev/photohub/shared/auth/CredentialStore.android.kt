package dev.photohub.shared.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Android credential store backed by EncryptedSharedPreferences.
 *
 * The encryption key lives in the hardware-backed Android Keystore, so the
 * on-disk file is unreadable even with root or an extracted backup. The app also
 * sets `allowBackup=false`, keeping tokens off Google's backup servers.
 */
class AndroidCredentialStore(context: Context) : CredentialStore {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun get(key: String): String? = prefs.getString(key, null)

    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun removeAll(keyPrefix: String) {
        val editor = prefs.edit()
        // Keys are encrypted at rest but the in-memory view is plaintext, so
        // prefix matching works here.
        prefs.all.keys.filter { it.startsWith(keyPrefix) }.forEach(editor::remove)
        editor.apply()
    }

    private companion object {
        const val FILE_NAME = "photohub_credentials"
        const val MASTER_KEY_ALIAS = "photohub_master_key"
    }
}
