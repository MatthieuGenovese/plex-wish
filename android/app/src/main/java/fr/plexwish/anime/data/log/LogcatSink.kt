package fr.plexwish.anime.data.log

import android.util.Log
import androidx.media3.common.util.Log as Media3Log

/** Branche {@link SafeLog} sur Logcat, et les journaux de Media3 sur {@link SafeLog} (nettoyés eux aussi). */
object LogcatSink {

    fun install(debug: Boolean) {
        SafeLog.minPriority = if (debug) SafeLog.DEBUG else SafeLog.WARN
        SafeLog.sink = SafeLog.Sink { priority, tag, message -> Log.println(priority, tag, message) }
        // Media3 journalise les erreurs de lecture (avec leurs causes) : on les fait passer par le nettoyage.
        Media3Log.setLogStackTraces(false)
        Media3Log.setLogLevel(if (debug) Media3Log.LOG_LEVEL_INFO else Media3Log.LOG_LEVEL_WARNING)
        Media3Log.setLogger(object : Media3Log.Logger {
            override fun d(tag: String, message: String, throwable: Throwable?) = SafeLog.log(SafeLog.DEBUG, tag, message, throwable)
            override fun i(tag: String, message: String, throwable: Throwable?) = SafeLog.log(SafeLog.INFO, tag, message, throwable)
            override fun w(tag: String, message: String, throwable: Throwable?) = SafeLog.log(SafeLog.WARN, tag, message, throwable)
            override fun e(tag: String, message: String, throwable: Throwable?) = SafeLog.log(SafeLog.ERROR, tag, message, throwable)
        })
    }
}
