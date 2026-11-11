package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phone app of the personal fork: browse movies and series on the phone, start them on the TV. */
class PhoneAppTest {
    @Test fun json_for_the_poster_grid() {
        assertEquals(
            "{\"id\":\"m1\",\"t\":\"Film\",\"y\":2024,\"q\":\"4K\",\"g\":\"Drame\",\"p\":\"http://x/p.jpg\"}",
            RemoteLogic.vod("m1", "Film", 2024, "4K", "Drame", "http://x/p.jpg")
        )
        assertEquals("{\"id\":\"m1\",\"t\":\"Film\",\"y\":0,\"q\":\"\",\"g\":\"\",\"p\":\"\"}", RemoteLogic.vod("m1", "Film", null, null, "", null))
        assertEquals("{\"g\":\"Action \\\"VF\\\"\",\"n\":12}", RemoteLogic.cat("Action \"VF\"", 12))
        assertEquals("{\"n\":70,\"o\":60,\"it\":[1,2]}", RemoteLogic.vodPage(70, 60, listOf("1", "2")))
        assertEquals("{\"n\":0,\"o\":0,\"it\":[]}", RemoteLogic.vodPage(0, 0, emptyList()))
    }

    @Test fun json_for_the_title_page() {
        assertEquals(
            "{\"t\":\"Film\",\"y\":\"2024\",\"rt\":112,\"g\":[\"Drame\",\"Thriller\"],\"ov\":\"Text\",\"sc\":\"7.8\",\"p\":\"\"}",
            RemoteLogic.info("Film", "2024", 112, listOf("Drame", "Thriller"), "Text", "7.8", "")
        )
        assertEquals("{\"t\":\"X\",\"y\":\"\",\"rt\":0,\"g\":[],\"ov\":\"\",\"sc\":\"\",\"p\":\"\"}", RemoteLogic.info("X", "", 0, emptyList(), "", "", ""))
    }

    @Test fun page_has_the_views_and_calls_of_the_phone_app() {
        for (li in listOf(0, 1, I18nCore.CODES.indexOf("fr"))) {
            val p = RemoteLogic.page(li)
            for (a in listOf("'vcats'", "'vlist'", "'info'", "'favs'", "'resume'", "id=\"nav\"", "data-v=\"home\"", "data-v=\"find\"", "id=\"gen\"")) assertTrue("$li $a", p.contains(a))
            assertFalse(p.contains("\${"))
            // Titles and addresses from the playlist are never written as HTML
            assertFalse(p.contains("innerHTML=it") || p.contains("innerHTML=x") || p.contains("innerHTML=d"))
        }
        val fr = RemoteLogic.page(I18nCore.CODES.indexOf("fr"))
        assertTrue(fr.contains("Accueil"))
        assertTrue(fr.contains("Tout voir"))
    }
}
