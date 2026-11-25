package com.novatv.iptv.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class XtreamProfile(val name: String, val server: String, val username: String, val password: String)

/** Stores provider credentials encrypted with a non-exportable Android Keystore key. */
class SecureProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("secure_profiles", Context.MODE_PRIVATE)

    fun save(profile: XtreamProfile) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = JSONObject().put("name", profile.name).put("server", profile.server)
            .put("username", profile.username).put("password", profile.password).toString()
        val ciphertext = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        val packed = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        prefs.edit().putString(storageKey(profile.name), packed).apply()
    }

    fun profiles(): List<XtreamProfile> = prefs.all.keys.filter { it.startsWith(PREFIX) }
        .mapNotNull { decrypt(it) }.sortedBy { it.name.lowercase() }

    fun load(name: String): XtreamProfile? = decrypt(storageKey(name))

    fun delete(name: String) { prefs.edit().remove(storageKey(name)).apply() }

    private fun decrypt(storageKey: String): XtreamProfile? = runCatching {
        val packed = prefs.getString(storageKey, null) ?: return null
        val pieces = packed.split(':', limit = 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP)))
        val data = JSONObject(String(cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP)), Charsets.UTF_8))
        XtreamProfile(data.getString("name"), data.getString("server"), data.getString("username"), data.getString("password"))
    }.getOrNull()

    private fun storageKey(name: String) = PREFIX + Base64.encodeToString(name.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    private companion object { const val ALIAS = "nova.iptv.profile.v1"; const val PREFIX = "profile:" }
}
