package fr.plexwish.anime.feature.player

import androidx.media3.common.PlaybackException

/** Familles d'erreur, pour le message à l'utilisateur et la reprise automatique. */
enum class FailureKind { FORBIDDEN, NOT_FOUND, NETWORK, SERVER, CONTAINER, MALFORMED, VIDEO_CODEC, AUDIO_CODEC, UNKNOWN }

/** Avertissement (la lecture continue) : son ou sous-titres absents ou illisibles. */
enum class WarningKind { NO_AUDIO, AUDIO_UNSUPPORTED, NO_SUBTITLES, SUBTITLES_UNSUPPORTED, IMAGE_SUBTITLES }

/** Message utile + détails techniques (sans URL ni jeton) pour l'écran « Détails ». */
data class Diagnosis(val message: String, val details: List<Pair<String, String>>)

/**
 * Traduction des erreurs et des pistes en messages compréhensibles. Distingue : conteneur non lu (OGM, AVI exotique),
 * vidéo non décodable (HEVC 10 bits sur un téléphone sans décodeur…), son illisible, sous-titres absents ou dans un
 * format qu'Android ignore (ASS dans un MP4, sous-titres d'un AVI), erreurs réseau, lien refusé (403), fichier absent.
 */
object Diagnostics {

    fun kind(f: PlaybackFailure): FailureKind = when {
        f.httpStatus == 401 || f.httpStatus == 403 -> FailureKind.FORBIDDEN
        f.httpStatus == 404 || f.httpStatus == 410 || f.httpStatus == 416 -> FailureKind.NOT_FOUND
        f.httpStatus != null && f.httpStatus >= 500 -> FailureKind.SERVER
        f.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
            f.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> FailureKind.NOT_FOUND
        f.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            f.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            f.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            f.errorCode == PlaybackException.ERROR_CODE_TIMEOUT -> FailureKind.NETWORK
        f.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> FailureKind.SERVER
        f.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> FailureKind.CONTAINER
        f.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> FailureKind.MALFORMED
        f.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
            f.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> FailureKind.AUDIO_CODEC
        f.errorCode in 4000..4999 -> if (f.formatMime?.startsWith("audio/") == true) FailureKind.AUDIO_CODEC else FailureKind.VIDEO_CODEC
        else -> FailureKind.UNKNOWN
    }

    /** Erreur bloquante → message + détails. {@code container} : extension du fichier (« mkv », « ogm »…). */
    fun failure(f: PlaybackFailure, tracks: List<TrackInfo>, container: String?): Diagnosis {
        val c = containerName(container)
        val message = when (kind(f)) {
            FailureKind.FORBIDDEN -> "Le serveur refuse la lecture (lien de lecture expiré, ou compte désactivé). " +
                "Réessayez ; si cela continue, reconnectez-vous."
            FailureKind.NOT_FOUND -> "Ce fichier n'est plus disponible sur le serveur."
            FailureKind.NETWORK -> "Connexion au serveur perdue. Vérifiez le réseau, puis réessayez."
            FailureKind.SERVER -> "Le serveur a rencontré une erreur" + (f.httpStatus?.let { " (HTTP $it)" } ?: "") + ". Réessayez plus tard."
            FailureKind.CONTAINER -> when (container?.lowercase()) {
                "ogm", "ogv", "ogg" -> "Fichier OGM : Android ne sait pas lire ce format. " +
                    "Il faudra le convertir sur le serveur sans perte (remux, prévu au traitement média)."
                "avi" -> "Ce fichier AVI contient un codage qu'Android ne reconnaît pas."
                else -> "Android ne reconnaît pas le format de ce fichier ($c)."
            }
            FailureKind.MALFORMED -> "Le fichier ($c) semble endommagé ou incomplet."
            FailureKind.VIDEO_CODEC -> "Ce téléphone ne sait pas décoder la vidéo de ce fichier" +
                (videoName(f, tracks)?.let { " ($it)" } ?: "") + ". Il faudrait la convertir (transcodage, non prévu)."
            FailureKind.AUDIO_CODEC -> "Ce téléphone ne sait pas lire le son de ce fichier" +
                ((f.formatMime ?: tracks.firstOrNull { it.type == TrackType.AUDIO }?.mimeType)
                    ?.let { " (${codecName(it, f.formatCodecs)})" } ?: "") + "."
            FailureKind.UNKNOWN -> "La lecture a échoué (${f.errorCodeName})."
        }
        val details = buildList {
            add("Fichier" to c)
            add("Erreur" to "${f.errorCodeName} (${f.errorCode})")
            f.httpStatus?.let { add("Réponse du serveur" to "HTTP $it") }
            f.formatMime?.let { add("Piste en cause" to codecName(it, f.formatCodecs)) }
            if (f.cause.isNotBlank()) add("Cause" to f.cause)
            addAll(trackDetails(tracks))
        }
        return Diagnosis(message, details)
    }

    /** Vidéo présente mais non décodable : ExoPlayer jouerait le son sur un écran noir, on le traite comme une erreur. */
    fun undecodableVideo(tracks: List<TrackInfo>, container: String?): Diagnosis? {
        val video = tracks.filter { it.type == TrackType.VIDEO }
        if (video.isEmpty() || video.any { it.supported }) return null
        val name = codecName(video.first().mimeType, video.first().codecs)
        return Diagnosis(
            "Ce téléphone ne sait pas décoder la vidéo de ce fichier ($name). Il faudrait la convertir (transcodage, non prévu).",
            listOf("Fichier" to containerName(container), "Erreur" to "Aucun décodeur vidéo pour $name") + trackDetails(tracks),
        )
    }

    /** Avertissements non bloquants, une fois les pistes connues. */
    fun warnings(tracks: List<TrackInfo>, container: String?): List<Pair<WarningKind, Diagnosis>> {
        if (tracks.isEmpty()) return emptyList()
        val c = containerName(container)
        val details = listOf("Fichier" to c) + trackDetails(tracks)
        val out = mutableListOf<Pair<WarningKind, Diagnosis>>()
        val audio = tracks.filter { it.type == TrackType.AUDIO }
        when {
            audio.isEmpty() -> out += WarningKind.NO_AUDIO to Diagnosis(
                "Pas de son : aucune piste audio reconnue dans ce fichier $c " +
                    "(format audio qu'Android ne lit pas dans ce conteneur, par exemple du HE-AAC dans un AVI).", details)
            audio.none { it.supported } -> out += WarningKind.AUDIO_UNSUPPORTED to Diagnosis(
                "Pas de son : ce téléphone ne sait pas lire l'audio de ce fichier (${audio.joinToString { codecName(it.mimeType, it.codecs) }}).",
                details)
        }
        val text = tracks.filter { it.type == TrackType.TEXT }
        when {
            text.isEmpty() -> out += WarningKind.NO_SUBTITLES to Diagnosis(
                "Aucun sous-titre lisible dans ce fichier $c : " + when (container?.lowercase()) {
                    "mp4", "m4v" -> "un MP4 ne peut porter que des sous-titres texte simples ; des sous-titres ASS mis dans un MP4 sont ignorés par Android."
                    "avi" -> "Android ne lit pas les sous-titres contenus dans un AVI (VobSub ou autres)."
                    else -> "il n'en contient pas, ou dans un format qu'Android ne lit pas."
                }, details)
            text.none { it.supported } -> out += WarningKind.SUBTITLES_UNSUPPORTED to Diagnosis(
                "Les sous-titres de ce fichier (${text.joinToString { codecName(it.mimeType, it.codecs) }}) ne sont pas lisibles par Android.",
                details)
            text.filter { it.supported }.all { isImageSubtitle(it.mimeType, it.codecs) } -> out += WarningKind.IMAGE_SUBTITLES to Diagnosis(
                "Sous-titres en images (${text.first().let { codecName(it.mimeType, it.codecs) }}) : affichés tels quels, " +
                    "sans réglage de taille ni de style.", details)
        }
        return out
    }

    fun trackDetails(tracks: List<TrackInfo>): List<Pair<String, String>> = tracks.map { t ->
        val type = when (t.type) {
            TrackType.VIDEO -> "Vidéo"
            TrackType.AUDIO -> "Audio"
            TrackType.TEXT -> "Sous-titres"
            TrackType.OTHER -> "Autre piste"
        }
        type to listOfNotNull(
            codecName(t.mimeType, t.codecs),
            t.language?.let { "langue $it" },
            if (t.supported) "lisible" else "non lisible sur ce téléphone",
            if (t.selected) "choisie" else null,
        ).joinToString(", ")
    }

    fun containerName(ext: String?): String = when (ext?.lowercase()) {
        null, "" -> "format inconnu"
        "mkv" -> "MKV"
        "mp4", "m4v" -> "MP4"
        "avi" -> "AVI"
        "ogm", "ogv", "ogg" -> "OGM"
        "webm" -> "WebM"
        "ts", "m2ts" -> "MPEG-TS"
        else -> ext.uppercase()
    }

    private fun videoName(f: PlaybackFailure, tracks: List<TrackInfo>): String? {
        val mime = f.formatMime?.takeIf { it.startsWith("video/") }
        if (mime != null) return codecName(mime, f.formatCodecs)
        return tracks.firstOrNull { it.type == TrackType.VIDEO }?.let { codecName(it.mimeType, it.codecs) }
    }

    /** Sous-titres convertis à l'extraction : Media3 met « application/x-media3-cues » et garde le type d'origine dans codecs. */
    private fun realMime(mime: String?, codecs: String?) =
        if (mime == "application/x-media3-cues" && !codecs.isNullOrBlank()) codecs else mime

    fun isImageSubtitle(mime: String?, codecs: String?) =
        realMime(mime, codecs) in setOf("application/vobsub", "application/pgs", "application/dvbsubs")

    /** Nom lisible d'un codec, avec la profondeur 10 bits quand la chaîne de codec la donne. */
    fun codecName(mime: String?, codecs: String?): String {
        val m = realMime(mime, codecs)
        val tenBit = if (isTenBit(m, codecs)) " 10 bits" else ""
        return when (m) {
            "video/hevc" -> "HEVC (H.265)$tenBit"
            "video/avc" -> "H.264$tenBit"
            "video/mp4v-es", "video/mp42", "video/mp43" -> "MPEG-4 Part 2 (DivX/Xvid)"
            "video/x-vnd.on2.vp9" -> "VP9"
            "video/x-vnd.on2.vp8" -> "VP8"
            "video/av01" -> "AV1"
            "video/mpeg2" -> "MPEG-2"
            "video/mjpeg" -> "MJPEG"
            "audio/mp4a-latm" -> if (codecs?.contains("40.5") == true || codecs?.contains("40.29") == true) "HE-AAC" else "AAC"
            "audio/mpeg" -> "MP3"
            "audio/ac3" -> "AC3 (Dolby Digital)"
            "audio/eac3", "audio/eac3-joc" -> "E-AC3"
            "audio/vnd.dts", "audio/vnd.dts.hd", "audio/vnd.dts.uhd;profile=p2" -> "DTS"
            "audio/true-hd" -> "TrueHD"
            "audio/vorbis" -> "Vorbis"
            "audio/opus" -> "Opus"
            "audio/flac" -> "FLAC"
            "audio/raw" -> "PCM"
            "text/x-ssa" -> "ASS/SSA"
            "application/x-subrip" -> "SRT"
            "text/vtt" -> "WebVTT"
            "application/vobsub" -> "VobSub (images)"
            "application/pgs" -> "PGS (images)"
            "application/dvbsubs" -> "DVB (images)"
            null -> "codec inconnu"
            else -> m
        }
    }

    /** HEVC profil 2 (Main 10) ou H.264 profil 110 (High 10), d'après la chaîne de codec. */
    fun isTenBit(mime: String?, codecs: String?): Boolean {
        val parts = codecs?.split('.') ?: return false
        return when {
            mime == "video/hevc" && parts.size > 1 -> parts[1].trimStart('A', 'B', 'C').toIntOrNull() == 2
            mime == "video/avc" && parts.size > 1 && parts[1].length >= 2 -> parts[1].substring(0, 2).toIntOrNull(16) == 110
            else -> false
        }
    }
}
