package fr.plexwish.anime.data.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Chiffrement du refresh token au repos. Interface pour remplacer le Keystore dans les tests JVM. */
interface TokenCipher {
    fun encrypt(plain: String): String
    fun decrypt(encoded: String): String
}

/**
 * AES-256-GCM avec une clé de l'**Android Keystore** : générée sur l'appareil, non exportable, matérielle
 * (TEE / StrongBox) quand le téléphone le permet. Le texte chiffré (IV + données + tag) est stocké en Base64 dans
 * des préférences exclues des sauvegardes. Choix (ARCHITECTURE §5.1.1, §20) : EncryptedSharedPreferences
 * (androidx.security:security-crypto) est déprécié ; Tink ferait l'affaire mais n'apporte rien ici (un seul secret,
 * chiffré une fois par connexion ou rafraîchissement) : le Keystore direct évite une dépendance.
 * Si la clé disparaît (restauration sur un autre téléphone, réinitialisation), le déchiffrement échoue :
 * l'utilisateur se reconnecte, rien d'autre.
 */
class KeystoreTokenCipher(private val alias: String = "plexwish_refresh_token") : TokenCipher {

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key()) // IV aléatoire choisi par le Keystore
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + data, Base64.NO_WRAP)
    }

    override fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_LENGTH))
        return String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
    }
}
