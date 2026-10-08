package fr.plexwish.anime.feature

import androidx.lifecycle.SavedStateHandle
import fr.plexwish.anime.FakeCipher
import fr.plexwish.anime.MemoryStore
import fr.plexwish.anime.data.api.AnimeApi
import fr.plexwish.anime.data.api.AppJson
import fr.plexwish.anime.data.api.AppTokens
import fr.plexwish.anime.data.auth.SessionStore
import fr.plexwish.anime.feature.detail.AnimeDetailViewModel
import fr.plexwish.anime.feature.detail.DetailState
import fr.plexwish.anime.feature.player.ProgressBus
import fr.plexwish.anime.feature.player.ProgressSaved
import fr.plexwish.anime.feature.person.PersonState
import fr.plexwish.anime.feature.person.PersonViewModel
import fr.plexwish.anime.tokensJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Fiche d'un animé et page comédien : chargement, saisons, tranches, progression, distribution, erreurs. */
@OptIn(ExperimentalCoroutinesApi::class)
class AnimeDetailViewModelTest {

    private val server = MockWebServer()
    private lateinit var session: SessionStore
    private lateinit var api: AnimeApi
    private var castStatus = 200
    @Volatile
    private var progressBody = """[{"episodeId":1001,"positionSeconds":1400,"durationSeconds":1440,"completed":true,"updatedAt":"2026-10-05T10:00:00Z"},
        {"episodeId":1002,"positionSeconds":600,"durationSeconds":1440,"completed":false,"updatedAt":"2026-10-05T10:00:00Z"}]"""
    private val paths = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private val anime = """{"id":7,"title":"Frieren","alternativeTitle":"Sousou no Frieren","frenchTitle":"Frieren",
        "synopsis":"Une elfe…","synopsisLanguage":"fr","synopsisSource":"TMDB","tmdbUrl":"https://www.themoviedb.org/tv/1",
        "posterUrl":"https://image.tmdb.org/t/p/w342/x.jpg","posterLargeUrl":"/api/posters/big","year":2023,
        "metadataSource":"AniList","metadataUrl":"https://anilist.co/anime/154587",
        "seasons":[{"id":10,"seasonNumber":1,"label":"Saison 1","episodeCount":28},{"id":11,"seasonNumber":2,"label":"Saison 2","episodeCount":250}]}"""

    private val cast = """{"source":"AniList","sourceUrl":"https://anilist.co/anime/154587","items":[
        {"character":{"name":"Frieren","nativeName":"フリーレン","imageUrl":"/api/cast-images/perso"},"role":"MAIN","language":"ja",
         "person":{"id":"95185","name":"Atsumi Tanezaki","nativeName":"種﨑敦美","imageUrl":"/api/cast-images/voix"}},
        {"character":{"name":"Fern","nativeName":null},"role":"SUPPORTING","language":"ja",
         "person":{"id":"12","name":"Kana Ichinose","nativeName":null,"imageUrl":"https://s4.anilist.co/file/p.jpg"}},
        {"character":{"name":"Narrateur","nativeName":null},"role":"SUPPORTING","language":"ja","person":null}]}"""

    private val person = """{"id":"95185","name":"Atsumi Tanezaki","nativeName":"種﨑敦美","imageUrl":"/api/cast-images/voix",
        "sourceUrl":"https://anilist.co/staff/95185","roles":[
        {"animeId":7,"animeTitle":"Frieren","year":2023,"posterUrl":"/api/posters/x","character":{"name":"Frieren","nativeName":null},"role":"MAIN"},
        {"animeId":7,"animeTitle":"Frieren","year":2023,"posterUrl":"/api/posters/x","character":{"name":"Sein","nativeName":null},"role":"SUPPORTING"},
        {"animeId":9,"animeTitle":"Spy x Family","year":2022,"posterUrl":"https://image.tmdb.org/t/p/w342/y.jpg","character":{"name":"Anya Forger","nativeName":null},"role":"MAIN"}]}"""

    private fun episodes(n: Int) = (1..n).joinToString(",", "[", "]") { """{"id":${1000 + it},"episodeNumber":$it,"title":null,"durationSeconds":1440}""" }

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                paths += url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
                return when (url.encodedPath) {
                    "/api/anime/7" -> MockResponse().setBody(anime)
                    "/api/anime/70" -> MockResponse().setBody(anime.replace(""""id":7,""", """"id":70,""").replace(
                        """"seasons":[""", """"resume":{"kind":"RESUME","episodeId":1150,"seasonId":11,"seasonNumber":2,"episodeNumber":150,
                        "positionSeconds":300,"durationSeconds":1440},"genres":[{"genre":"Fantasy","label":"Fantastique"}],"seasons":["""))
                    "/api/anime/8" -> MockResponse().setResponseCode(404).setBody("""{"status":404,"error":"ANIME_NOT_FOUND","message":"Animé introuvable"}""")
                    "/api/seasons/10/episodes" -> MockResponse().setBody(episodes(28))
                    "/api/seasons/11/episodes" -> MockResponse().setBody(episodes(250))
                    "/api/me/progress" -> MockResponse().setBody(progressBody)
                    "/api/anime/7/cast" -> if (castStatus == 200) MockResponse().setBody(cast) else MockResponse().setResponseCode(castStatus)
                    "/api/people/95185" -> MockResponse().setBody(person)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        session = SessionStore(MemoryStore(), FakeCipher())
        session.startSession(server.url("/"), AppJson.decodeFromString<AppTokens>(tokensJson("a", "r")))
        api = AnimeApi(session, OkHttpClient())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        server.shutdown()
    }

    private fun base() = "http://localhost:${server.port}"

    private fun wait(vm: AnimeDetailViewModel, p: (DetailState) -> Boolean) = runBlocking { withTimeout(5000) { vm.state.first(p) } }

    private fun wait(vm: PersonViewModel, p: (PersonState) -> Boolean) = runBlocking { withTimeout(5000) { vm.state.first(p) } }

    @Test
    fun detailLoadsSeasonEpisodesProgressAndCast() {
        val vm = AnimeDetailViewModel(api, session, SavedStateHandle(mapOf("id" to 7L)))
        val s = wait(vm) { it.anime != null && it.episodes.isNotEmpty() && it.cast.isNotEmpty() && it.progress.isNotEmpty() }
        assertEquals("Frieren", s.anime!!.title)
        assertEquals("Sousou no Frieren", s.subtitle) // titre français identique au titre : pas répété
        assertNull("jamais d'image TMDB / AniList", s.anime!!.posterUrl)
        assertEquals("${base()}/api/posters/big", s.anime!!.posterLargeUrl)
        assertEquals(10L, s.seasonId) // première saison par défaut
        assertEquals(28, s.visibleEpisodes.size)
        assertTrue(s.chunks.isEmpty())
        assertTrue(s.progress.getValue(1001).completed)
        assertEquals(600, s.progress.getValue(1002).positionSeconds)
        // Distribution : photo du comédien (notre serveur seulement), nom du personnage, rôle ; pas d'image de personnage.
        assertEquals(3, s.cast.size)
        assertEquals("${base()}/api/cast-images/voix", s.cast[0].person!!.imageUrl)
        assertNull(s.cast[1].person!!.imageUrl)
        assertEquals("Frieren", s.cast[0].character.name)
        assertNull(s.cast[2].person)
        assertEquals("AniList", s.castSource)
        assertTrue(paths.contains("/api/me/progress?animeId=7"))
    }

    @Test
    fun seasonChoiceIsRestoredAndLongSeasonsAreChunked() {
        val saved = SavedStateHandle(mapOf("id" to 7L))
        val vm = AnimeDetailViewModel(api, session, saved)
        wait(vm) { it.episodes.size == 28 }
        vm.selectSeason(11)
        var s = wait(vm) { it.episodes.size == 250 }
        assertEquals(listOf("1–100", "101–200", "201–250"), s.chunks.map { it.label })
        assertEquals(100, s.visibleEpisodes.size)
        vm.selectChunk(2)
        s = vm.state.value
        assertEquals(50, s.visibleEpisodes.size)
        assertEquals(201, s.visibleEpisodes.first().episodeNumber)
        vm.selectSeason(999) // saison inconnue : ignorée
        assertEquals(11L, vm.state.value.seasonId)
        // Processus recréé : la saison choisie revient.
        val restored = AnimeDetailViewModel(api, session, saved)
        assertEquals(11L, wait(restored) { it.anime != null }.seasonId)
    }

    @Test
    fun castErrorKeepsTheDetailUsable() {
        castStatus = 500
        val vm = AnimeDetailViewModel(api, session, SavedStateHandle(mapOf("id" to 7L)))
        val s = wait(vm) { it.anime != null && it.episodes.isNotEmpty() }
        assertTrue(s.cast.isEmpty())
        assertNull(s.error)
    }

    @Test
    fun missingAnimeIsReportedAsNotFound() {
        val vm = AnimeDetailViewModel(api, session, SavedStateHandle(mapOf("id" to 8L)))
        val s = wait(vm) { !it.loading }
        assertTrue(s.notFound)
        assertEquals("Animé introuvable", s.error)
    }

    @Test
    fun personPageGroupsRolesByAnime() {
        val vm = PersonViewModel(api, session, SavedStateHandle(mapOf("id" to "95185")))
        val s = wait(vm) { !it.loading }
        assertEquals("Atsumi Tanezaki", s.person!!.name)
        assertEquals("${base()}/api/cast-images/voix", s.person!!.imageUrl)
        assertEquals(listOf(7L, 9L), s.animes.map { it.animeId })
        assertEquals(listOf("Frieren", "Sein"), s.animes[0].roles.map { it.character.name })
        assertEquals("${base()}/api/posters/x", s.animes[0].posterUrl)
        assertNull(s.animes[1].posterUrl)
    }

    @Test
    fun invalidPersonIdNeverReachesTheServer() {
        val vm = PersonViewModel(api, session, SavedStateHandle(mapOf("id" to "../admin")))
        val s = wait(vm) { !it.loading }
        assertTrue(s.notFound)
        assertFalse(paths.any { it.contains("admin") })
    }

    @Test
    fun savedPositionUpdatesTheEpisodeBarWithoutLeavingTheDetail() {
        // Retour du lecteur : la dernière position est enregistrée APRÈS le retour à la fiche. La barre doit suivre.
        val bus = ProgressBus()
        val vm = AnimeDetailViewModel(api, session, SavedStateHandle(mapOf("id" to 7L)), bus)
        wait(vm) { it.episodes.isNotEmpty() && it.progress.isNotEmpty() }
        val before = paths.count { it.startsWith("/api/me/progress") }
        progressBody = """[{"episodeId":1001,"positionSeconds":1400,"durationSeconds":1440,"completed":true,"updatedAt":"2026-10-05T10:00:00Z"},
            {"episodeId":1002,"positionSeconds":300,"durationSeconds":1440,"completed":false,"updatedAt":"2026-10-05T10:05:00Z"}]"""
        bus.saved(ProgressSaved(7, 1002, 300, 1440)) // retour en arrière dans l'épisode : 600 → 300
        assertEquals(300, vm.state.value.progress.getValue(1002).positionSeconds) // tout de suite
        runBlocking { withTimeout(5000) { while (paths.count { it.startsWith("/api/me/progress") } == before) kotlinx.coroutines.delay(10) } }
        assertEquals(300, wait(vm) { it.progress[1002]?.positionSeconds == 300 }.progress.getValue(1002).positionSeconds)
        // Au-delà de 90 % : « vu » sans attendre la relecture (le serveur, relu ensuite, le confirme).
        progressBody = progressBody.trimEnd().trimEnd(']') + ",{\"episodeId\":1003,\"positionSeconds\":1350,\"durationSeconds\":1440,\"completed\":true}]"
        bus.saved(ProgressSaved(7, 1003, 1350, 1440))
        assertTrue(vm.state.value.progress.getValue(1003).completed)
    }

    @Test
    fun positionOfAnotherAnimeIsIgnored() {
        val bus = ProgressBus()
        val vm = AnimeDetailViewModel(api, session, SavedStateHandle(mapOf("id" to 7L)), bus)
        wait(vm) { it.episodes.isNotEmpty() && it.progress.isNotEmpty() }
        val before = paths.count { it.startsWith("/api/me/progress") }
        bus.saved(ProgressSaved(9, 1002, 10, 1440))
        Thread.sleep(200)
        assertEquals(600, vm.state.value.progress.getValue(1002).positionSeconds)
        assertEquals(before, paths.count { it.startsWith("/api/me/progress") })
    }

    @Test
    fun detailOpensOnTheSeasonAndRangeOfTheEpisodeToResume() {
        // S5 : le bouton principal vise l'épisode 150 de la saison 2 (250 épisodes) : saison 2, tranche 101–200.
        val vm = AnimeDetailViewModel(api, session, SavedStateHandle(mapOf("id" to 70L)))
        val s = wait(vm) { it.episodes.size == 250 }
        assertEquals(11L, s.seasonId)
        assertEquals("101–200", s.chunks[s.chunk].label)
        assertEquals("RESUME", s.anime!!.resume!!.kind)
        assertEquals(listOf("Fantastique"), s.anime!!.genres.map { it.label })
    }
}
