package com.ipvio.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phone search of the personal fork: language filter and grouping of duplicate channels. */
class PhoneSearchGroupingTest {
    private fun check(title: String, name: String, label: String) {
        val c = RemoteLogic.chanName(title)
        assertEquals(title, name, c.name)
        assertEquals(title, name.lowercase(), c.key)
        assertEquals(title, label, c.label)
    }

    @Test fun channel_name_drops_country_prefix_and_quality_words() {
        check("FR: beIN Sports 1 FHD", "beIN Sports 1", "FHD")
        check("|FR| beIN Sports 1 4K", "beIN Sports 1", "4K")
        check("[FR] beIN Sports 1 HEVC", "beIN Sports 1", "HEVC")
        check("FR - beIN Sports 1 SD", "beIN Sports 1", "SD")
        check("beIN Sports 1 (Backup)", "beIN Sports 1", "BACKUP")
        check("FR: |FHD| beIN Sports 2", "beIN Sports 2", "FHD")
        check("Canal+ Sport 360 FHD", "Canal+ Sport 360", "FHD")
        check("beIN SPORTS MAX 4 1080p", "beIN SPORTS MAX 4", "1080P")
    }

    @Test fun channel_name_keeps_names_that_only_look_like_tags() {
        check("RMC - Sport 1 HD", "RMC - Sport 1", "HD")
        check("WWE RAW", "WWE RAW", "")
        check("TF1 4K", "TF1", "4K")
        check("C8", "C8", "")
        // A title that is only a quality word stays as it is
        assertEquals("HD", RemoteLogic.chanName("HD").name)
    }

    @Test fun versions_of_one_channel_share_a_key() {
        val keys = listOf("FR: beIN Sports 1 FHD", "FR | BEIN SPORTS 1 HD", "|FR| beIN Sports 1 4K", "beIN Sports 1 (Backup)")
            .map { RemoteLogic.chanName(it).key }.toSet()
        assertEquals(setOf("bein sports 1"), keys)
        assertFalse(RemoteLogic.chanName("beIN Sports 2 HD").key in keys)
    }

    @Test fun best_picture_first() {
        val sorted = listOf("SD", "", "HD", "4K", "FHD", "HD HEVC").sortedBy { RemoteLogic.qualityRank(it) }
        assertEquals(listOf("4K", "FHD", "HD", "HD HEVC", "SD", ""), sorted)
    }

    @Test fun language_filter_and_json() {
        assertEquals(emptySet<String>(), RemoteLogic.langSet(null))
        assertEquals(emptySet<String>(), RemoteLogic.langSet(""))
        assertEquals(setOf("fr", "en"), RemoteLogic.langSet("fr, en,"))
        assertEquals("{\"c\":\"fr\",\"n\":\"Français\",\"k\":12}", RemoteLogic.langEntry("fr", "Français", 12))
        assertEquals("{\"id\":\"a\",\"q\":\"FHD\",\"f\":true}", RemoteLogic.variant("a", "FHD", true))
        assertEquals(
            "{\"t\":\"beIN Sports 1\",\"lc\":\"fr\",\"np\":\"Match\",\"ns\":1,\"ne\":2,\"v\":[{\"id\":\"a\",\"q\":\"FHD\",\"f\":false}]}",
            RemoteLogic.liveGroup("beIN Sports 1", "fr", "Match", 1L, 2L, listOf(RemoteLogic.variant("a", "FHD", false)))
        )
        assertEquals("{\"t\":\"X\",\"lc\":\"\",\"v\":[]}", RemoteLogic.liveGroup("X", "", null, 0L, 0L, emptyList()))
    }

    @Test fun phone_page_has_the_new_calls_and_french_texts() {
        val fr = RemoteLogic.page(I18nCore.CODES.indexOf("fr"))
        assertTrue(fr.contains("'langs'"))
        assertTrue(fr.contains("lang:langs.join(',')"))
        assertTrue(fr.contains("Connecté à la télé"))
        assertTrue(RemoteLogic.page(1).contains("Connected to the TV"))
        assertFalse(fr.contains("\${"))
    }
}
