package fr.plexwish.anime.feature.player

import fr.plexwish.anime.MemoryStore
import fr.plexwish.anime.data.api.ProgressDto
import org.junit.Assert.assertEquals
import org.junit.Test

class RecoveryTest {

    @Test
    fun policy() {
        val p = RecoveryPolicy(networkDelaysMs = listOf(1_000, 2_000), maxUrlRefreshes = 2)
        assertEquals(Recovery.RefreshUrl, p.decide(FailureKind.FORBIDDEN, 0, 0, false))
        assertEquals(Recovery.Fail, p.decide(FailureKind.FORBIDDEN, 0, 2, false)) // 403 qui persiste : compte désactivé…
        assertEquals(Recovery.Retry(1_000, false), p.decide(FailureKind.NETWORK, 0, 0, false))
        assertEquals(Recovery.Retry(2_000, true), p.decide(FailureKind.NETWORK, 1, 0, true))
        assertEquals(Recovery.Fail, p.decide(FailureKind.NETWORK, 2, 0, false))
        for (k in listOf(FailureKind.CONTAINER, FailureKind.VIDEO_CODEC, FailureKind.NOT_FOUND)) {
            assertEquals(Recovery.Fail, p.decide(k, 0, 0, false))
        }
    }

    @Test
    fun resumePoint() {
        assertEquals(0, ResumePoint.startMs(null))
        assertEquals(600_000, ResumePoint.startMs(ProgressDto(1, 600, 1440, false)))
        assertEquals(0, ResumePoint.startMs(ProgressDto(1, 1400, 1440, true)))
        assertEquals(0, ResumePoint.startMs(ProgressDto(1, 1300, 1440, false))) // ≥ 90 %
        assertEquals(0, ResumePoint.startMs(ProgressDto(1, 3, 1440, false)))
    }

    @Test
    fun trackPreferences() {
        val store = TrackPrefsStore(MemoryStore())
        assertEquals(TrackPrefs("ja", "fr"), store.load())
        assertEquals(TrackPrefs("ja", null), store.update("ja", null, textDisabled = true))
        assertEquals(TrackPrefs("ja", null), store.load())
        assertEquals(TrackPrefs("ja", "fr"), store.update("und", "und", textDisabled = false)) // langue inconnue : français
        assertEquals(TrackPrefs("fr", "en"), store.update("fr", "en-US", textDisabled = false))
        assertEquals(TrackPrefs("fr", "en"), store.update(null, null, textDisabled = false))
    }
}
