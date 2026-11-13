package kg.accounting.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface KeyValueStore {
    fun read(key: String): String?

    fun write(key: String, value: String?)
}

class SecureStore(context: Context) : KeyValueStore {
    private val preferences = context.getSharedPreferences("encrypted_1c", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey("accounting_aes_v1", null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            "accounting_aes_v1",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
            }
            .generateKey()
    }

    @Synchronized
    override fun read(key: String): String? {
        val data = preferences.getString(key, null) ?: return null
        val parts = data.split('.')
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])),
        )
        cipher.updateAAD(key.toByteArray())
        return String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), Charsets.UTF_8)
    }

    @Synchronized
    override fun write(key: String, value: String?) {
        val editor = preferences.edit()
        if (value == null) editor.remove(key)
        else {
            require(value.length <= 512 * 1024)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(key.toByteArray())
            editor.putString(
                key,
                Base64.getEncoder().encodeToString(cipher.iv) +
                    "." +
                    Base64.getEncoder()
                        .encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8))),
            )
        }
        check(editor.commit()) { "Не удалось сохранить данные на телефоне." }
    }
}
