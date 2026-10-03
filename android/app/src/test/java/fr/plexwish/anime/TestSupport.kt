package fr.plexwish.anime

import fr.plexwish.anime.data.auth.KeyValueStore
import fr.plexwish.anime.data.auth.TokenCipher
import java.util.concurrent.ConcurrentHashMap

/** Stockage en mémoire (remplace SharedPreferences). */
class MemoryStore : KeyValueStore {
    val values = ConcurrentHashMap<String, String>()
    override fun get(key: String): String? = values[key]
    override fun put(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** Faux chiffrement réversible (le Keystore n'existe pas sur la JVM) : le stockage ne voit jamais le texte clair. */
class FakeCipher : TokenCipher {
    override fun encrypt(plain: String) = "enc:" + plain.reversed()
    override fun decrypt(encoded: String): String {
        require(encoded.startsWith("enc:")) { "clé perdue" }
        return encoded.removePrefix("enc:").reversed()
    }
}

fun tokensJson(access: String, refresh: String, user: String = "alice") =
    """{"accessToken":"$access","tokenType":"Bearer","expiresIn":900,"refreshToken":"$refresh","user":{"id":1,"username":"$user","role":"USER"}}"""
