package com.ipvio.stream

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.Player
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Phone search: the TV serves a small web page ([RemoteLogic.page]); on the phone you search movies, series and channels
 * and start them on the TV. This object connects the phone's requests ([handle]) with the app:
 * search in the loaded playlist, start via [playReq] (MainActivity), control of the running player.
 */
object RemoteHub {
    class Play(val id: String, val season: Int, val ep: Int)

    /** Requested by the phone, picked up by MainActivity. */
    var playReq by mutableStateOf<Play?>(null)
    /** Counts up when the phone presses "Stop" (MainActivity then closes the player). */
    var closeTick by mutableIntStateOf(0)
        private set
    /** Is the server running? (for the display) */
    var active by mutableStateOf(false)
        private set
    var server: PhoneImport? = null
        private set

    /** The running player while the screen is open (set by PlayerScreen). */
    @Volatile var player: Player? = null
    @Volatile var nowTitle: String = ""
    @Volatile var nowLive: Boolean = false

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var appCtx: Context? = null

    fun start(ctx: Context): PhoneImport? {
        appCtx = ctx.applicationContext
        server?.let { if (active) return it }
        val s = PhoneImport { }
        s.onList = { name, bytes -> main.post { appCtx?.let { ListImportApply.run(it, name, bytes) } } }
        s.api = { name, q ->
            // The phone opened the page or used it: close the QR card on the TV (it would otherwise stay in front of the player).
            // "state" is polled all the time and must not count.
            if (name != "state") main.post { RemoteUi.show = false }
            runCatching { handle(name, q) }.getOrNull()
        }
        if (!s.start()) return null
        server = s
        active = true
        return s
    }

    fun stop() {
        server?.stop()
        server = null
        active = false
    }

    /** Run on the main thread and wait (the player may only be touched there). */
    private fun <T> onMain(def: T, f: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return f()
        var r = def
        val l = CountDownLatch(1)
        main.post { try { r = f() } catch (_: Throwable) { } finally { l.countDown() } }
        l.await(2, TimeUnit.SECONDS)
        return r
    }

    private fun itemJson(i: Item) = RemoteLogic.item(i.id, i.title, i.year, qualityOf(i), i.group)

    /** Live channel for the phone: like [itemJson] plus favorite flag and the running show (from the loaded XMLTV, no network). */
    private fun liveJson(i: Item): String {
        val cur = Epg.current(Xmltv.forChannel(i.tvgId))
        return RemoteLogic.live(itemJson(i), Library.isFav(i.id), cur?.title, cur?.start ?: 0L, cur?.end ?: 0L)
    }

    /** Channels of a search grouped by cleaned name and language: one entry per channel with all its versions. */
    private fun groupLive(l: List<Item>): List<List<Pair<Item, RemoteLogic.ChanName>>> {
        val by = LinkedHashMap<String, MutableList<Pair<Item, RemoteLogic.ChanName>>>()
        for (i in l) {
            val c = RemoteLogic.chanName(i.title)
            by.getOrPut(c.key + "|" + i.lang) { ArrayList() }.add(i to c)
        }
        return by.values.toList()
    }

    private fun groupJson(g: List<Pair<Item, RemoteLogic.ChanName>>): String {
        val first = g.first()
        val cur = g.asSequence().mapNotNull { Epg.current(Xmltv.forChannel(it.first.tvgId)) }.firstOrNull()
        val vs = g.map { (i, c) -> i to c.label.ifEmpty { qualityOf(i).orEmpty() } }
            .sortedBy { RemoteLogic.qualityRank(it.second) }
            .take(VARIANT_LIMIT)
            .map { RemoteLogic.variant(it.first.id, it.second, Library.isFav(it.first.id)) }
        val lang = if (first.first.lang == LangTagLogic.UNKNOWN) "" else first.first.lang
        return RemoteLogic.liveGroup(first.second.name, lang, cur?.title, cur?.start ?: 0L, cur?.end ?: 0L, vs)
    }

    private fun handle(name: String, q: Map<String, String>): String? {
        val all = PlaylistStore.channels
        return when (name) {
            "search" -> {
                if (all.isEmpty()) return "{\"loading\":true}"
                val (m0, s0, l0) = SearchCache.build(all).find(q["q"].orEmpty().take(80))
                // Language filter of the phone page ("lang=fr,en"); empty = all languages
                val want = RemoteLogic.langSet(q["lang"])
                val m = if (want.isEmpty()) m0 else m0.filter { it.lang in want }
                val s = if (want.isEmpty()) s0 else s0.filter { it.lang in want }
                val l = if (want.isEmpty()) l0 else l0.filter { it.lang in want }
                // The same channel in several versions (HD, FHD, backup …) appears once, with its versions to choose from
                val groups = groupLive(l)
                "{\"nm\":${m.size},\"ns\":${s.size},\"nl\":${groups.size},\"nr\":${l.size}," +
                    "\"m\":" + RemoteLogic.arr(m.take(LIMIT).map(::itemJson)) +
                    ",\"s\":" + RemoteLogic.arr(s.take(LIMIT).map(::itemJson)) +
                    ",\"l\":" + RemoteLogic.arr(l.take(LIMIT).map(::liveJson)) +
                    ",\"lg\":" + RemoteLogic.arr(groups.take(LIMIT).map(::groupJson)) + "}"
            }
            // Phone app: categories of the movies or series ("k=m" / "k=s"), with the language filter
            "vcats" -> {
                val kind = if (q["k"] == "s") Kind.SERIES else Kind.MOVIE
                val want = RemoteLogic.langSet(q["lang"])
                val g = all.asSequence().filter { it.kind == kind && (want.isEmpty() || it.lang in want) }.groupingBy { it.group }.eachCount()
                RemoteLogic.arr(g.entries.sortedBy { it.key.lowercase() }.take(CAT_LIMIT).map { RemoteLogic.cat(it.key, it.value) })
            }
            // Phone app: one page of the poster grid ("g" = category, empty = all; "o" = offset)
            "vlist" -> {
                if (all.isEmpty()) return "{\"loading\":true}"
                val kind = if (q["k"] == "s") Kind.SERIES else Kind.MOVIE
                val want = RemoteLogic.langSet(q["lang"])
                val g = q["g"].orEmpty()
                val off = (q["o"]?.toIntOrNull() ?: 0).coerceAtLeast(0)
                val hit = all.filter { it.kind == kind && (want.isEmpty() || it.lang in want) && (g.isEmpty() || it.group == g) }
                // The same movie in several versions only once, as in the search on the TV
                val uniq = if (kind == Kind.MOVIE) PlaylistLogic.firstPerKey(hit) { it.mk + "|" + it.lang } else hit
                RemoteLogic.vodPage(uniq.size, off, uniq.drop(off).take(LIMIT).map { RemoteLogic.vod(it.id, it.title, it.year, qualityOf(it), it.group, it.logo) })
            }
            // Phone app: details of one title (description, rating, genres) if the metadata is available
            "info" -> {
                val i = all.firstOrNull { it.id == q["id"] } ?: return null
                val ctx = appCtx
                val m = MetaRepo.cached(i)
                    ?: if (ctx != null && i.kind != Kind.LIVE) runCatching { kotlinx.coroutines.runBlocking { MetaRepo.basic(ctx, i) } }.getOrNull() else null
                RemoteLogic.info(
                    i.title,
                    m?.year ?: i.year?.toString().orEmpty(),
                    m?.runtime ?: 0,
                    m?.genres.orEmpty(),
                    m?.overview.orEmpty(),
                    m?.score()?.let { String.format(java.util.Locale.US, "%.1f", it) }.orEmpty(),
                    m?.poster ?: i.logo.orEmpty()
                )
            }
            // Languages of the loaded playlist for the filter chips, most titles first, "no tag" last
            "langs" -> {
                val counts = all.groupingBy { it.lang }.eachCount()
                RemoteLogic.arr(counts.entries
                    .sortedWith(compareBy<Map.Entry<String, Int>>({ it.key == LangTagLogic.UNKNOWN }, { -it.value }))
                    .take(LANG_LIMIT)
                    .map { RemoteLogic.langEntry(it.key, if (it.key == LangTagLogic.UNKNOWN) "" else LangTagLogic.name(it.key), it.value) })
            }
            "resume" -> {
                val byId = all.associateBy { it.id }
                RemoteLogic.arr(WatchStore.entries.mapNotNull { e ->
                    val it = byId[e.itemId] ?: return@mapNotNull null
                    if (it.kind == Kind.LIVE) return@mapNotNull null
                    var s = e.season; var n = e.ep
                    if (it.kind == Kind.SERIES && s == 0 && n == 0) ResumeLogic.seasonEp(e.title)?.let { p -> s = p.first; n = p.second }
                    val pct = if (e.dur > 0) (e.pos * 100 / e.dur).toInt() else 0
                    RemoteLogic.resume(it.id, it.title, if (it.kind == Kind.SERIES) "s" else "m", s, n, pct)
                }.take(LIMIT))
            }
            "cats" -> {
                val g = all.filter { it.kind == Kind.LIVE }.groupingBy { it.group }.eachCount()
                RemoteLogic.arr(g.entries.sortedBy { it.key.lowercase() }.map { "{\"g\":" + RemoteLogic.q(it.key) + ",\"n\":" + it.value + "}" })
            }
            "channels" -> {
                val g = q["g"].orEmpty()
                RemoteLogic.arr(all.filter { it.kind == Kind.LIVE && it.group == g }.take(CHANNEL_LIMIT).map(::liveJson))
            }
            // Configure: live categories with their visible/hidden state (same setting as "hide category" on the TV)
            "groups" -> {
                val c = appCtx ?: return null
                HiddenCats.init(c)
                val hidden = HiddenCats.list.toSet()
                val g = all.filter { it.kind == Kind.LIVE }.groupingBy { it.group }.eachCount()
                RemoteLogic.arr(g.entries.sortedBy { it.key.lowercase() }.map { "{\"g\":" + RemoteLogic.q(it.key) + ",\"n\":" + it.value + ",\"h\":" + (it.key in hidden) + "}" })
            }
            "hide" -> {
                val c = appCtx ?: return null
                val g = q["g"] ?: return null
                val hide = q["h"] == "1"
                main.post { if (hide) HiddenCats.hide(c, g) else HiddenCats.show(c, g) }
                "{\"ok\":true}"
            }
            // Favorite live channels (same favorites as on the TV)
            "favs" -> {
                val byId = all.associateBy { it.id }
                RemoteLogic.arr(Library.favs.toList().mapNotNull { byId[it] }.filter { it.kind == Kind.LIVE }.take(CHANNEL_LIMIT).map(::liveJson))
            }
            // Toggle a live favorite from the phone
            "fav" -> {
                val id = q["id"] ?: return null
                val c = appCtx ?: return null
                if (all.none { it.id == id && it.kind == Kind.LIVE }) return null
                main.post { Library.toggleFav(c, id) }
                "{\"ok\":true,\"f\":" + !Library.isFav(id) + "}"
            }
            // The user's own channel lists (created on the TV)
            "lists" -> RemoteLogic.arr(ChannelLists.lists.map { "{\"i\":" + it.id + ",\"g\":" + RemoteLogic.q(it.name) + ",\"n\":" + it.ids.size + "}" })
            "list" -> {
                val l = ChannelLists.lists.firstOrNull { it.id == q["i"]?.toIntOrNull() } ?: return null
                val byId = all.associateBy { it.id }
                RemoteLogic.arr(l.ids.mapNotNull { byId[it] }.filter { it.kind == Kind.LIVE }.take(CHANNEL_LIMIT).map(::liveJson))
            }
            "eps" -> {
                val i = all.firstOrNull { it.id == q["id"] && it.kind == Kind.SERIES } ?: return null
                var full = LazySeries.full(i)
                // Series with a placeholder: fetch episodes now (this call does not run on the main thread)
                if (LazySeries.isStub(full)) {
                    val ctx = appCtx
                    if (ctx != null) kotlinx.coroutines.runBlocking { LazySeries.load(ctx, i) }?.let { full = it }
                }
                RemoteLogic.arr(full.episodes.take(EPISODE_LIMIT).map { RemoteLogic.episode(it.season, it.num, it.title) })
            }
            "play" -> {
                val id = q["id"] ?: return null
                if (all.none { it.id == id }) return null
                playReq = Play(id, q["s"]?.toIntOrNull() ?: 0, q["e"]?.toIntOrNull() ?: 0)
                "{\"ok\":true}"
            }
            "ctl" -> control(q["c"].orEmpty(), q["v"].orEmpty())
            "state" -> onMain(RemoteLogic.state(false, "", false, 0, 0, false)) {
                val p = player
                if (p == null) RemoteLogic.state(false, "", false, 0, 0, false)
                else {
                    val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
                    RemoteLogic.state(true, nowTitle, p.playWhenReady, p.currentPosition / 1000, dur / 1000, nowLive)
                }
            }
            else -> null
        }
    }

    private fun control(c: String, v: String): String {
        val idle = "{\"idle\":true}"
        when (c) {
            "stop" -> { if (player == null) return idle; closeTick++ }
            "pause" -> if (!onMain(false) { player?.let { it.playWhenReady = !it.playWhenReady; true } ?: false }) return idle
            "skip" -> {
                val d = RemoteLogic.clampSkip(v.toIntOrNull() ?: 0) * 1000L
                if (!onMain(false) {
                    val p = player
                    if (p == null) false
                    else {
                        val to = (p.currentPosition + d).coerceAtLeast(0)
                        val dur = p.duration
                        p.seekTo(if (dur != C.TIME_UNSET && dur > 0) to.coerceAtMost(dur) else to)
                        true
                    }
                }) return idle
            }
            "vol" -> {
                val am = appCtx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                am?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if ((v.toIntOrNull() ?: 1) < 0) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI
                )
            }
        }
        return "{\"ok\":true}"
    }

    private const val LIMIT = 60
    private const val VARIANT_LIMIT = 12
    private const val LANG_LIMIT = 8
    private const val CAT_LIMIT = 300
    private const val CHANNEL_LIMIT = 800
    private const val EPISODE_LIMIT = 5000
}
