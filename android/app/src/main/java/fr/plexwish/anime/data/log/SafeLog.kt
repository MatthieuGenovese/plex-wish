package fr.plexwish.anime.data.log

/**
 * Seul point de journalisation de l'app (Logcat compris) : tout texte passe par {@link sanitize}, qui masque
 * la signature et les paramètres des URL de lecture, les jetons (Bearer, JWT, champs JSON) et toute chaîne de requête.
 * Un test vérifie qu'aucun autre fichier de l'app n'appelle android.util.Log directement.
 */
object SafeLog {

    /** Destination des lignes (Logcat dans l'app, une liste dans les tests). */
    fun interface Sink {
        fun write(priority: Int, tag: String, message: String)
    }

    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6

    @Volatile
    var sink: Sink = Sink { _, _, _ -> }

    @Volatile
    var minPriority: Int = INFO

    private val QUERY = Regex("""(https?://[^\s?#"']+)\?[^\s#"']*""", RegexOption.IGNORE_CASE)
    private val STREAM_PATH = Regex("""(/api/stream/\d+)\?[^\s#"']*""", RegexOption.IGNORE_CASE)
    private val PARAMS = Regex("""\b(sig|exp|u|token|access_token|refresh_token|api_key)=([^&\s"']+)""", RegexOption.IGNORE_CASE)
    private val BEARER = Regex("""\bBearer\s+[A-Za-z0-9._~+/=-]+""", RegexOption.IGNORE_CASE)
    private val JWT = Regex("""\beyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]*""")
    private val JSON_SECRET = Regex(""""(accessToken|refreshToken|password)"\s*:\s*"[^"]*"""", RegexOption.IGNORE_CASE)

    /** Texte sans secret : URL tronquées avant « ? », paramètres sensibles masqués, jetons remplacés. */
    fun sanitize(text: String?): String {
        if (text == null) return ""
        var s = text
        s = JSON_SECRET.replace(s) { "\"${it.groupValues[1]}\":\"***\"" }
        s = BEARER.replace(s, "Bearer ***")
        s = JWT.replace(s, "***")
        s = QUERY.replace(s) { it.groupValues[1] + "?***" }
        s = STREAM_PATH.replace(s) { it.groupValues[1] + "?***" }
        s = PARAMS.replace(s) { it.groupValues[1] + "=***" }
        return s
    }

    /** Chaîne des causes (classe + message nettoyé), pour un diagnostic sans secret. */
    fun describe(t: Throwable?, max: Int = 4): String {
        val parts = mutableListOf<String>()
        var cur = t
        while (cur != null && parts.size < max) {
            parts += cur.javaClass.simpleName + (cur.message?.let { ": " + sanitize(it).take(200) } ?: "")
            cur = cur.cause.takeIf { it !== cur }
        }
        return parts.joinToString(" ← ")
    }

    fun d(tag: String, message: String) = log(DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(INFO, tag, message, null)
    fun w(tag: String, message: String, t: Throwable? = null) = log(WARN, tag, message, t)
    fun e(tag: String, message: String, t: Throwable? = null) = log(ERROR, tag, message, t)

    /** Jamais de pile complète (les messages des causes peuvent contenir des URL) : causes nettoyées seulement. */
    fun log(priority: Int, tag: String, message: String, t: Throwable?) {
        if (priority < minPriority) return
        val line = sanitize(message) + (t?.let { " — " + describe(it) } ?: "")
        sink.write(priority, tag.take(23), line)
    }
}
