package fr.plexwish.anime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Le nom affiché vient d'une seule constante (build.gradle.kts → BuildConfig.APP_NAME et @string/app_name). */
class AppNameTest {
    @Test
    fun appNameIsNeverWrittenInTheCode() {
        assertEquals("Anime Server", BuildConfig.APP_NAME)
        val offenders = File("src/main").walkTopDown().filter { it.isFile && it.extension in setOf("kt", "xml") }
            .filter { f -> f.readLines().any { line -> "Anime Server" in line && !line.trimStart().startsWith("*") && !line.trimStart().startsWith("/") } }
            .map { it.path }.toList()
        assertTrue("nom écrit en dur dans : $offenders", offenders.isEmpty())
    }
}
