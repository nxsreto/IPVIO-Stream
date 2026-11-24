package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** EuroLeague from the competition's own feed (personal fork). The sample follows the real answer of the feed. */
class EuroLeagueTest {
    private val league = SportData.league("EuroLeague")!!

    private fun game(id: String, utc: String, played: Boolean, home: Int, road: Int) = """
        {"identifier":"$id","gameCode":139,"utcDate":"$utc","localDate":"2026-11-24T20:00:00","played":$played,
         "gameStatus":"Confirmed","round":14,"roundName":"Round 14","venue":{"name":"Belgrade Arena"},
         "local":{"club":{"code":"RED","name":"Crvena Zvezda Meridianbet Belgrade","abbreviatedName":"Zvezda","editorialName":"Zvezda",
                  "images":{"crest":"https://example.org/red.png"}},"score":$home},
         "road":{"club":{"code":"VIR","name":"Virtus Bologna","abbreviatedName":"Virtus","images":{"crest":""}},"score":$road}}
    """.trimIndent()

    private fun feed(vararg games: String) = "{\"data\":[" + games.joinToString(",") + "],\"total\":" + games.size + "}"

    private val start = SportData.parseDate("2026-11-24T19:00:00Z")

    @Test fun league_is_listed_under_basketball() {
        assertEquals(SportData.KIND_EURO, league.kind)
        assertEquals("basketball", league.sport)
        assertEquals("E", SportData.euroCode(league))
        assertEquals("U", SportData.euroCode(SportData.league("EuroCup")!!))
        assertEquals(SportData.KIND_FIGHT, SportData.league("PFL")!!.kind)
    }

    @Test fun season_code_changes_in_august() {
        assertEquals("E2026", SportData.euroSeason("E", SportData.parseDate("2026-11-24T19:00:00Z") * 1000))
        assertEquals("E2026", SportData.euroSeason("E", SportData.parseDate("2027-05-20T19:00:00Z") * 1000))
        assertEquals("U2027", SportData.euroSeason("U", SportData.parseDate("2027-09-01T10:00:00Z") * 1000))
    }

    @Test fun a_game_before_its_start() {
        val (list, total) = SportData.parseEuro(feed(game("E2026_135", "2026-11-24T19:00:00Z", false, 0, 0)), league, start - 3600)
        assertEquals(1, total)
        val g = list.single()
        assertEquals(14, g.round)
        assertEquals("E2026_135", g.game.id)
        assertEquals("EuroLeague", g.game.league)
        assertEquals(start, g.game.start)
        assertEquals(0, g.game.state)
        assertEquals("Crvena Zvezda Meridianbet Belgrade", g.game.home.name)
        assertEquals("Zvezda", g.game.home.short)
        assertEquals("https://example.org/red.png", g.game.home.logo)
        assertNull(g.game.away.logo)
        assertNull(g.game.home.score)
        assertEquals("Round 14", g.game.note)
        assertEquals("Belgrade Arena", g.game.venue)
    }

    @Test fun a_running_game_shows_the_score() {
        val g = SportData.parseEuro(feed(game("a", "2026-11-24T19:00:00Z", false, 41, 38)), league, start + 3000).first.single().game
        assertEquals(1, g.state)
        assertEquals("41", g.home.score)
        assertEquals("38", g.away.score)
        assertFalse(g.home.winner)
        // Three hours after the start it no longer counts as running, even if the feed is late
        assertEquals(0, SportData.parseEuro(feed(game("a", "2026-11-24T19:00:00Z", false, 41, 38)), league, start + 4 * 3600).first.single().game.state)
    }

    @Test fun a_finished_game_has_a_winner() {
        val g = SportData.parseEuro(feed(game("a", "2026-11-24T19:00:00Z", true, 80, 86)), league, start + 86_400).first.single().game
        assertEquals(2, g.state)
        assertTrue(g.away.winner)
        assertFalse(g.home.winner)
    }

    @Test fun broken_entries_are_skipped() {
        val json = "{\"data\":[{\"identifier\":\"x\"},{\"utcDate\":\"2026-11-24T19:00:00Z\",\"local\":{},\"road\":{}}," +
            game("ok", "2026-11-24T19:00:00Z", false, 0, 0) + "],\"total\":3}"
        val (list, total) = SportData.parseEuro(json, league, start - 60)
        assertEquals(3, total)
        assertEquals(listOf("ok"), list.map { it.game.id })
        assertTrue(SportData.parseEuro("{}", league).first.isEmpty())
    }

    @Test fun the_guide_matches_with_a_translated_club_name() {
        val g = SportData.parseEuro(feed(game("a", "2026-11-24T19:00:00Z", false, 0, 0)), league, start - 60).first.single().game
        assertTrue(SportData.euroShowMatches(g, "Euroligue : Etoile Rouge de Belgrade - Virtus Bologne"))
        assertTrue(SportData.euroShowMatches(g, "Basket. EuroLeague. Virtus - Etoile Rouge"))
        assertFalse(SportData.euroShowMatches(g, "Basket. Serie A. Virtus - Milan"))
        assertFalse(SportData.euroShowMatches(g, "Euroligue : Monaco - Paris"))
    }
}
