package se.sensnology.spotnav.prices.entsoe

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Seals a small secret for storage, and opens it again; `null` whenever either cannot be done. */
internal interface SecretBox {
    fun seal(plain: String): String?

    fun open(sealed: String): String?
}

/**
 * AES-256-GCM under a non-exportable AndroidKeyStore key: platform API only, no library. Defence in
 * depth (backups and device transfer are already off): the stored text is useless without this device's
 * key store. A text that does not open -- a lost key store, another device's text -- reads as no secret,
 * and the person pastes it again. Failures are never logged or kept: an exception is only its outcome.
 */
internal object AndroidKeyStoreBox : SecretBox {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "spotnav_entsoe_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val FORMAT = "v1"

    override fun seal(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        listOf(FORMAT, encode(cipher.iv), encode(sealed)).joinToString(":")
    }.getOrNull()

    override fun open(sealed: String): String? = runCatching {
        val parts = sealed.split(':')
        if (parts.size != 3 || parts[0] != FORMAT) return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, existingKey() ?: return null, GCMParameterSpec(TAG_BITS, decode(parts[1])))
        String(cipher.doFinal(decode(parts[2])), Charsets.UTF_8)
    }.getOrNull()

    private fun existingKey(): SecretKey? {
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        return store.getKey(ALIAS, null) as? SecretKey
    }

    private fun key(): SecretKey = existingKey() ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
        init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(text: String): ByteArray = Base64.getDecoder().decode(text)
}
