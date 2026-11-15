package com.ipvio.stream

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sports: which days of the scoreboard are needed for one day of the viewer (personal fork, fix for late US games). */
class SportDaysTest {
    private val paris = TimeZone.getTimeZone("Europe/Paris")
    private val newYork = TimeZone.getTimeZone("America/New_York")
    private val tokyo = TimeZone.getTimeZone("Asia/Tokyo")

    @Test fun a_european_day_needs_the_us_day_before_as_well() {
        // 16 Nov in Paris runs from 18:00 on the 15th to 18:00 on the 16th in New York
        assertEquals(listOf("20261115", "20261116"), SportData.sourceDays("20261116", paris, carryOver = false))
        assertEquals(listOf("20261115", "20261116"), SportData.sourceDays("20261116", paris, carryOver = true))
    }

    @Test fun the_day_a_running_late_game_belongs_to_is_included() {
        // A game that started at 23:00 Paris time on the 15th is listed on the source's 15th and still runs after midnight
        val start = SportData.parseDate("2026-11-15T22:00Z")
        val sourceDay = SportData.ymd(0, newYork, start * 1000)
        assertEquals("20261115", sourceDay)
        assertTrue(sourceDay in SportData.sourceDays("20261116", paris, carryOver = true))
    }

    @Test fun other_time_zones() {
        assertEquals(listOf("20261115", "20261116"), SportData.sourceDays("20261115", newYork, carryOver = false))
        assertEquals(listOf("20261114", "20261115", "20261116"), SportData.sourceDays("20261115", newYork, carryOver = true))
        assertEquals(listOf("20261115", "20261116"), SportData.sourceDays("20261116", tokyo, carryOver = false))
    }

    @Test fun summer_time_in_europe() {
        // 1 July in Paris (UTC+2) runs from 18:00 on 30 June to 18:00 on 1 July in New York
        assertEquals(listOf("20260630", "20260701"), SportData.sourceDays("20260701", paris, carryOver = false))
    }
}
