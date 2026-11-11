package com.ipvio.stream

/**
 * Phone search (remote control in the browser), pure parts without Android: JSON building blocks and the page for the phone.
 * The TV serves the page (see [PhoneImport]); it searches and starts titles via the API under /api/.
 */
object RemoteLogic {
    /** Text as a JSON string (with quotes). */
    fun q(s: String): String {
        val b = StringBuilder(s.length + 2)
        b.append('"')
        for (c in s) when {
            c == '"' -> b.append("\\\"")
            c == '\\' -> b.append("\\\\")
            c == '\n' -> b.append("\\n")
            c == '\r' -> b.append("\\r")
            c == '\t' -> b.append("\\t")
            c < ' ' -> b.append("\\u").append(String.format("%04x", c.code))
            else -> b.append(c)
        }
        return b.append('"').toString()
    }

    /** A title for the list on the phone: id, title, year, quality, category. */
    fun item(id: String, title: String, year: Int?, quality: String?, group: String): String =
        "{\"id\":" + q(id) + ",\"t\":" + q(title) + ",\"y\":" + (year ?: 0) + ",\"q\":" + q(quality.orEmpty()) + ",\"g\":" + q(group) + "}"

    /** A started title for "Continue watching": id, title, kind (m/s), season, episode, progress in percent. */
    fun resume(id: String, title: String, kind: String, season: Int, ep: Int, pct: Int): String =
        "{\"id\":" + q(id) + ",\"t\":" + q(title) + ",\"k\":" + q(kind) + ",\"s\":$season,\"e\":$ep,\"p\":${pct.coerceIn(0, 100)}}"

    /** A live channel: the [item] JSON plus favorite flag "f" and, if known, the running show "np" with start "ns" and end "ne" (epoch seconds). */
    fun live(item: String, fav: Boolean, nowTitle: String?, start: Long, end: Long): String =
        item.dropLast(1) + ",\"f\":" + fav + (if (nowTitle != null) ",\"np\":" + q(nowTitle) + ",\"ns\":" + start + ",\"ne\":" + end else "") + "}"

    fun arr(items: List<String>): String = items.joinToString(",", "[", "]")

    /** An episode: season, number, title. */
    fun episode(season: Int, num: Int, title: String): String = "{\"s\":$season,\"n\":$num,\"t\":" + q(title) + "}"

    /** Playback state for the display under the buttons. */
    fun state(active: Boolean, title: String, playing: Boolean, posSec: Long, durSec: Long, live: Boolean): String =
        "{\"a\":$active,\"t\":" + q(title) + ",\"pl\":$playing,\"pos\":$posSec,\"dur\":$durSec,\"live\":$live}"

    /** The buttons jump at least this many seconds; larger values are limited. */
    fun clampSkip(sec: Int): Int = sec.coerceIn(-600, 600)

    /** mm:ss or h:mm:ss. */
    fun clock(sec: Long): String {
        val s = sec.coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val r = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, r) else String.format("%d:%02d", m, r)
    }

    // ------------------------------------------------------------------ Phone search: languages and duplicate channels

    /** Language filter of the phone search: "fr,en" → {fr, en}. Empty = all languages. */
    fun langSet(s: String?): Set<String> = (s ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /** A language for the filter chips: code, display name (empty = no tag), number of titles. */
    fun langEntry(code: String, name: String, count: Int): String = "{\"c\":" + q(code) + ",\"n\":" + q(name) + ",\"k\":" + count + "}"

    /** A channel name without country prefix and quality words: [name] to show, [key] to group by, [label] = the quality words found. */
    class ChanName(val name: String, val key: String, val label: String)

    // "FR: …", "FR | …", "|FR| …", "[FR] …", "FR - …" (a dash only after exactly two capital letters, so "RMC - Sport" stays whole)
    private val CH_PREFIX = Regex("""^\s*(?:\[[^\]]{1,8}\]|\|[^|]{1,8}\||\p{L}{2,4}\s*[:|►•]|\p{Lu}{2}\s+[-–]\s)\s*""")
    private val CH_QUAL = Regex("""(?i)(?<![\p{L}\d])(?:4K|8K|UHD|FHD|HD|SD|HEVC|H\.?265|H\.?264|2160p?|1080[pi]?|720p?|50\s?FPS|60\s?FPS|BACKUP|LOW)(?![\p{L}\d])""")
    private val CH_SUPER = Regex("""[ᴴᴰᶠᵁˢᴷ⁴]+""")
    private val CH_JUNK = Regex("""[\[\](){}|•►◉●★☆⭐✦]+""")
    private val CH_WS = Regex("""\s+""")

    fun chanName(title: String): ChanName {
        // Quality words are read from the whole title: some providers put them into the prefix ("|FHD| …")
        val labels = LinkedHashSet<String>()
        CH_SUPER.findAll(title).forEach { m ->
            labels.add(m.value.map { c -> when (c) { 'ᴴ' -> 'H'; 'ᴰ' -> 'D'; 'ᶠ' -> 'F'; 'ᵁ' -> 'U'; 'ˢ' -> 'S'; 'ᴷ' -> 'K'; '⁴' -> '4'; else -> c } }.joinToString(""))
        }
        CH_QUAL.findAll(title).forEach { labels.add(it.value.uppercase().replace(" ", "").replace(".", "")) }
        var s = title
        repeat(2) { s = CH_PREFIX.replaceFirst(s, "") }
        s = CH_QUAL.replace(CH_SUPER.replace(s, " "), " ")
        s = CH_WS.replace(CH_JUNK.replace(s, " "), " ").trim().trim('-', '–', ':', '.', ',', ' ')
        if (s.isEmpty()) s = title.trim()
        return ChanName(s, s.lowercase(), labels.joinToString(" "))
    }

    /** Sort order of the versions of one channel: best picture first (4K, FHD, HD, SD, the rest). */
    fun qualityRank(label: String): Int {
        val l = label.uppercase()
        return when {
            "4K" in l || "UHD" in l || "2160" in l || "8K" in l -> 0
            "FHD" in l || "1080" in l -> 1
            "SD" in l || "LOW" in l -> 3
            "HD" in l || "720" in l -> 2
            else -> 4
        }
    }

    /** One version of a channel: id, quality label, favorite flag. */
    fun variant(id: String, label: String, fav: Boolean): String = "{\"id\":" + q(id) + ",\"q\":" + q(label) + ",\"f\":" + fav + "}"

    /** A channel with all its versions: name, language code, the running show (if known) and the versions "v". */
    fun liveGroup(name: String, lang: String, nowTitle: String?, start: Long, end: Long, variants: List<String>): String =
        "{\"t\":" + q(name) + ",\"lc\":" + q(lang) +
            (if (nowTitle != null) ",\"np\":" + q(nowTitle) + ",\"ns\":" + start + ",\"ne\":" + end else "") +
            ",\"v\":" + arr(variants) + "}"

    // ------------------------------------------------------------------ Phone app: browse movies and series

    /** A movie or series for the poster grid: the [item] JSON plus the poster address "p". */
    fun vod(id: String, title: String, year: Int?, quality: String?, group: String, poster: String?): String =
        item(id, title, year, quality, group).dropLast(1) + ",\"p\":" + q(poster.orEmpty()) + "}"

    /** A category with its number of titles. */
    fun cat(name: String, count: Int): String = "{\"g\":" + q(name) + ",\"n\":" + count + "}"

    /** One page of the poster grid: total number, offset of this page, the titles. */
    fun vodPage(total: Int, offset: Int, items: List<String>): String = "{\"n\":" + total + ",\"o\":" + offset + ",\"it\":" + arr(items) + "}"

    /** Details of a title for its page on the phone; empty texts and runtime 0 mean "not known". */
    fun info(title: String, year: String, runtime: Int, genres: List<String>, overview: String, score: String, poster: String): String =
        "{\"t\":" + q(title) + ",\"y\":" + q(year) + ",\"rt\":" + runtime + ",\"g\":" + arr(genres.map { q(it) }) +
            ",\"ov\":" + q(overview) + ",\"sc\":" + q(score) + ",\"p\":" + q(poster) + "}"

    private fun tr(li: Int, de: String, en: String): String = when (li) {
        0 -> de
        1 -> en
        else -> I18nCore.translate(de, li).takeIf { it != de } ?: en
    }

    /** Text for an HTML attribute or a JavaScript string in single quotes. */
    private fun js(s: String): String = s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("<", "\\x3c")

    private fun h(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")

    /** The page for the phone. */
    fun page(li: Int): String {
        val lang = I18nCore.CODES.getOrElse(li) { "en" }
        fun t(de: String, en: String, fr: String? = null) = if (fr != null && lang == "fr") fr else tr(li, de, en)
        return """<!doctype html><html lang="$lang"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><meta name="theme-color" content="#0b0d1a"><meta name="apple-mobile-web-app-capable" content="yes"><meta name="mobile-web-app-capable" content="yes"><meta name="apple-mobile-web-app-title" content="TK TV"><meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<title>TK TV</title>
<style>
*{box-sizing:border-box}
body{margin:0;background:#0b0d1a;color:#f6f3ee;font:16px/1.35 system-ui,-apple-system,sans-serif;padding-bottom:calc(170px + env(safe-area-inset-bottom))}
button{font:inherit;color:inherit}
header{position:sticky;top:0;background:#0b0d1a;padding:calc(14px + env(safe-area-inset-top)) 16px 8px;z-index:5}
#top{display:flex;align-items:center;justify-content:space-between;margin-bottom:10px}
#top b{font-size:21px;font-weight:800;letter-spacing:-.3px}
#conn{display:flex;align-items:center;gap:7px;font-size:13px;color:#b7b4c4}
#conn i{width:8px;height:8px;border-radius:50%;background:#6b6f85}
#conn.ok i{background:#5bd68a}
#sb{display:flex;align-items:center;gap:8px;height:52px;border-radius:26px;background:#1f2338;padding:0 6px 0 16px;color:#b7b4c4}
#q{flex:1;min-width:0;height:100%;border:0;background:transparent;color:#f6f3ee;font-size:17px;outline:0;-webkit-appearance:none;appearance:none}
#q::-webkit-search-cancel-button{display:none}
#clr{width:44px;height:44px;border:0;background:transparent;color:#b7b4c4;font-size:24px;line-height:1;display:none}
#langs{display:flex;gap:8px;overflow-x:auto;padding:12px 0 2px;scrollbar-width:none}
#langs:empty{display:none}
#langs button{flex:none;height:40px;padding:0 15px;border-radius:20px;border:1px solid #2c3150;background:transparent;color:#f6f3ee;font-size:15px;white-space:nowrap}
#langs button.on{background:#ffb36b;border-color:#ffb36b;color:#0b0d1a;font-weight:700}
#tabs{display:flex;gap:4px;margin-top:10px;padding:4px;border-radius:16px;background:#151829}
#tabs button{flex:1;height:44px;border-radius:12px;border:0;background:transparent;color:#b7b4c4;font-size:15px;font-weight:500}
#tabs button.on{background:#1f2338;color:#f6f3ee;font-weight:700}
main{padding:6px 16px}
.row{display:flex;align-items:center;gap:10px;width:100%;text-align:left;border:0;background:#151829;color:#f6f3ee;border-radius:16px;padding:13px 14px;margin:8px 0;font-size:16px}
.row b{display:block;font-weight:600}
.row small{color:#b7b4c4}
.row .m{flex:1;min-width:0}
.star{font-size:24px;color:#ffb36b;padding:0 6px 0 0}
.row .m b,.row .m small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.bd{background:#1f2338;color:#f6f3ee;border-radius:8px;padding:3px 8px;font-size:12px;font-weight:700}
.msg{color:#b7b4c4;text-align:center;padding:30px 10px}
.info{color:#b7b4c4;font-size:14px;padding:8px 2px 0}
.card{background:#151829;border-radius:20px;padding:14px;margin:10px 0}
.ch{display:flex;align-items:center;gap:8px}
.ch .m{flex:1;min-width:0}
.ch .m b{display:block;font-size:18px;font-weight:700}
.ch .m small{display:block;color:#b7b4c4;font-size:14px}
.ch .m b,.ch .m small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.lc{font-size:11px;font-weight:700;color:#b7b4c4;border:1px solid #2c3150;border-radius:6px;padding:1px 6px;margin-left:8px;vertical-align:middle}
.fv{flex:none;width:44px;height:44px;border:0;background:transparent;color:#b7b4c4;font-size:24px;line-height:1}
.fv.on{color:#ffb36b}
.pl{flex:none;width:48px;height:48px;border-radius:50%;border:0;background:#ffb36b;color:#0b0d1a;display:flex;align-items:center;justify-content:center}
.qs{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}
.qs button{height:36px;min-width:52px;padding:0 12px;border-radius:10px;border:1px solid #2c3150;background:transparent;color:#b7b4c4;font-size:13px;font-weight:700}
.qs button.on{background:#f6f3ee;border-color:#f6f3ee;color:#0b0d1a}
.bar{display:flex;gap:8px;align-items:center;margin:8px 0}
.bar select{flex:1;height:46px;padding:0 12px;border-radius:14px;border:1px solid #2c3150;background:#1f2338;color:#f6f3ee;font-size:16px}
.back{border:0;background:#1f2338;color:#f6f3ee;border-radius:14px;height:46px;padding:0 16px;font-size:15px}
#ctl{position:fixed;left:10px;right:10px;bottom:calc(74px + env(safe-area-inset-bottom));background:#1f2338;border-radius:20px;padding:8px 8px 8px 14px;z-index:6}
#mini{display:flex;align-items:center;gap:6px}
#now{flex:1;min-width:0;font-size:14px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
#mini button{flex:none;width:44px;height:44px;border-radius:50%;border:0;background:transparent;color:#f6f3ee;display:flex;align-items:center;justify-content:center}
#mini #mp{background:#f6f3ee;color:#0b0d1a}
#btns{display:none;grid-template-columns:repeat(4,1fr);gap:6px;margin:10px 6px 4px 0}
#ctl.open #btns{display:grid}
#btns button{height:44px;padding:0 2px;border-radius:12px;border:0;background:#2c3150;color:#f6f3ee;font-size:14px;font-weight:600}
#btns button.go{background:#f6f3ee;color:#0b0d1a}
#toast{position:fixed;left:50%;transform:translateX(-50%);bottom:calc(150px + env(safe-area-inset-bottom));z-index:9;background:#000c;color:#fff;padding:10px 16px;border-radius:20px;font-size:14px;opacity:0;transition:opacity .2s;pointer-events:none;max-width:90%;text-align:center}
#toast.on{opacity:1}
#nav{position:fixed;left:0;right:0;bottom:0;display:flex;background:#151829;border-top:1px solid #2c3150;padding:4px 4px calc(6px + env(safe-area-inset-bottom));z-index:6}
#nav button{flex:1;height:56px;border:0;background:transparent;color:#b7b4c4;font-size:11px;font-weight:500;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:3px}
#nav button.on{color:#ffb36b;font-weight:700}
#gen{display:flex;gap:8px;overflow-x:auto;padding:10px 0 2px;scrollbar-width:none}
#gen:empty{display:none}
#gen button{flex:none;height:36px;padding:0 14px;border-radius:18px;border:1px solid #2c3150;background:transparent;color:#f6f3ee;font-size:14px;white-space:nowrap}
#gen button.on{background:#f6f3ee;border-color:#f6f3ee;color:#0b0d1a;font-weight:700}
body.v-home #sb,body.v-home #tabs,body.v-home #langs,body.v-home #gen,body.v-live #sb,body.v-live #tabs,body.v-live #langs,body.v-live #gen{display:none}
body.v-m #sb,body.v-m #tabs,body.v-s #sb,body.v-s #tabs,body.v-find #gen{display:none}
body.v-sub header{display:none}
.grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px 10px;padding-top:10px}
.pc{display:flex;flex-direction:column;gap:6px;min-width:0;border:0;background:transparent;padding:0;text-align:left}
.pc small{font-size:12px;color:#b7b4c4;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.ph{position:relative;width:100%;aspect-ratio:2/3;border-radius:12px;overflow:hidden;background:#1f2338;display:flex;align-items:center;justify-content:center}
.ph span{padding:8px;font-size:12px;color:#b7b4c4;text-align:center;overflow:hidden}
.ph img{position:absolute;left:0;top:0;width:100%;height:100%;object-fit:cover}
.hrow{display:flex;gap:10px;overflow-x:auto;scrollbar-width:none;margin:0 -16px;padding:0 16px}
.hrow .pc{flex:none;width:112px}
.sech{display:flex;align-items:baseline;justify-content:space-between;margin:22px 0 10px}
.sech b{font-size:18px;font-weight:700}
.lnk{border:0;background:transparent;color:#b7b4c4;font-size:13px;height:44px;padding:0 0 0 12px}
.hero{display:flex;align-items:flex-end;gap:12px;background:#151829;border-radius:20px;padding:18px 16px;margin-top:4px}
.hero .m{flex:1;min-width:0;display:flex;flex-direction:column;gap:4px}
.hero small{font-size:12px;font-weight:700;color:#ffb36b}
.hero b{font-size:22px;font-weight:800;letter-spacing:-.4px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.hero span{font-size:13px;color:#b7b4c4}
.pg{height:4px;border-radius:2px;background:#2c3150;margin-top:8px}
.pg i{display:block;height:4px;border-radius:2px;background:#ffb36b}
.det{display:flex;flex-direction:column;gap:12px;padding-top:8px}
.det .ph{width:46%;align-self:center}
.det h1{margin:4px 0 0;font-size:28px;line-height:1.1;font-weight:800;letter-spacing:-.6px}
.det .meta{font-size:14px;color:#b7b4c4}
.det .ov{margin:0;font-size:15px;line-height:1.5;color:#b7b4c4}
.big{height:56px;border-radius:28px;border:0;background:#ffb36b;color:#0b0d1a;font-size:18px;font-weight:800;display:flex;align-items:center;justify-content:center;gap:10px}
.more{display:block;width:100%;height:48px;margin:16px 0 0;border-radius:24px;border:1px solid #2c3150;background:transparent;color:#f6f3ee;font-size:15px;font-weight:600}
</style></head><body>
<header><div id="top"><b>TK TV</b><span id="conn"><i></i><span id="connt"></span></span></div>
<div id="sb"><svg aria-hidden="true" width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.5-4.5"/></svg><input id="q" type="search" placeholder="${h(t("Filme, Serien, Sender suchen …", "Search movies, series, channels …"))}" autocomplete="off" autocapitalize="off" enterkeyhint="search"><button id="clr" aria-label="${h(t("Löschen", "Clear", "Effacer"))}">&times;</button></div>
<div id="langs"></div>
<div id="gen"></div>
<div id="tabs"><button data-k="m" class="on">${h(t("Filme", "Movies"))}</button><button data-k="s">${h(t("Serien", "Series"))}</button><button data-k="l">Live TV</button></div></header>
<main id="list"></main>
<div id="ctl"><div id="mini"><div id="now"></div><button id="mp" data-c="pause" aria-label="${h(t("Pause / Weiter", "Pause / Play"))}"><svg aria-hidden="true" width="20" height="20" viewBox="0 0 24 24"><rect x="6" y="4" width="4" height="16" rx="1" fill="currentColor"/><rect x="14" y="4" width="4" height="16" rx="1" fill="currentColor"/></svg></button><button id="mx" aria-label="${h(t("Fernbedienung", "Remote control", "Télécommande"))}"><svg aria-hidden="true" width="22" height="22" viewBox="0 0 24 24" fill="currentColor"><circle cx="5" cy="12" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="19" cy="12" r="2"/></svg></button></div><div id="btns">
<button data-c="skip" data-v="-30">-30 s</button><button data-c="skip" data-v="-10">-10 s</button><button class="go" data-c="pause">${h(t("Pause / Weiter", "Pause / Play"))}</button>
<button data-c="skip" data-v="10">+10 s</button><button data-c="skip" data-v="30">+30 s</button><button data-c="stop">${h(t("Stopp", "Stop"))}</button>
<button data-c="vol" data-v="-1">${h(t("Leiser", "Vol -"))}</button><button data-c="vol" data-v="1">${h(t("Lauter", "Vol +"))}</button></div></div>
<nav id="nav"><button data-v="home"><svg aria-hidden="true" width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 11l9-8 9 8"/><path d="M5 10v10h14V10"/></svg><span>${h(t("Start", "Home", "Accueil"))}</span></button><button data-v="live"><svg aria-hidden="true" width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="7" width="18" height="13" rx="2"/><path d="M8 3l4 4 4-4"/></svg><span>${h(t("Live TV", "Live TV", "Direct"))}</span></button><button data-v="m"><svg aria-hidden="true" width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="3" width="16" height="18" rx="2"/><path d="M8 3v18M16 3v18M4 9h4M4 15h4M16 9h4M16 15h4"/></svg><span>${h(t("Filme", "Movies"))}</span></button><button data-v="s"><svg aria-hidden="true" width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="8" width="18" height="12" rx="2"/><path d="M3 12h18M7 4l3 4M13 4l3 4"/></svg><span>${h(t("Serien", "Series"))}</span></button><button data-v="find"><svg aria-hidden="true" width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.5-4.5"/></svg><span>${h(t("Suche", "Search", "Chercher"))}</span></button></nav>
<div id="toast"></div>
<script>
var L={code:'${js(t("Code vom Fernseher:", "Code from the TV:"))}',none:'${js(t("Nichts gefunden.", "Nothing found."))}',hint:'${js(t("Tippe einen Titel ein.", "Type a title."))}',
cats:'${js(t("Kategorien", "Categories"))}',favs:'${js(t("Meine Favoriten", "My favorites"))}',manage:'${js(t("Kategorien ein-/ausblenden", "Show / hide categories"))}',manageHint:'${js(t("Tippen blendet eine Kategorie am Fernseher ein oder aus.", "Tap to show or hide a category on the TV."))}',hidden:'${js(t("ausgeblendet", "hidden"))}',shown:'${js(t("sichtbar", "visible"))}',mylists:'${js(t("Meine Senderlisten", "My channel lists"))}',imp:'${js(t("Liste importieren (Letterboxd, MyAnimeList, Trakt)", "Import list (Letterboxd, MyAnimeList, Trakt)"))}',impDone:'${js(t("Liste wurde an den Fernseher gesendet", "List sent to the TV"))}',back:'${js(t("Zurück", "Back"))}',season:'${js(t("Staffel", "Season"))}',ep:'${js(t("Folge", "Episode"))}',
ok:'${js(t("Läuft auf dem Fernseher.", "Playing on the TV."))}',err:'${js(t("Fehler. Ist der Fernseher noch an?", "Error. Is the TV still on?"))}',
load:'${js(t("Lädt …", "Loading …"))}',cont:'${js(t("Weiterschauen", "Continue watching"))}',idle:'${js(t("Es läuft nichts.", "Nothing is playing."))}',wait:'${js(t("Die Playlist wird noch geladen.", "The playlist is still loading."))}'};
var L2={all:'${js(t("Alle", "All", "Toutes"))}',untagged:'${js(t("Ohne Kennung", "Untagged", "Sans étiquette"))}',grouped:'${js(t("%1 Sender, %2 Links zusammengefasst", "%1 channels, %2 links grouped", "%1 chaînes, %2 liens regroupés"))}',
link:'${js(t("Link", "Link", "Lien"))}',playtv:'${js(t("Auf dem Fernseher starten", "Play on the TV", "Lancer sur la télé"))}',fav:'${js(t("Favorit", "Favorite", "Favori"))}',
on:'${js(t("Mit dem Fernseher verbunden", "Connected to the TV", "Connecté à la télé"))}',off:'${js(t("Fernseher nicht erreichbar", "TV not reachable", "Télé injoignable"))}'};
var TM='${js(t("Filme", "Movies"))}',TS='${js(t("Serien", "Series"))}';
var L3={allc:'${js(t("Alle", "All", "Tous"))}',more:'${js(t("Mehr laden", "Load more", "Voir plus"))}',seeall:'${js(t("Alle ansehen", "See all", "Tout voir"))}',episodes:'${js(t("Folgen", "Episodes", "Épisodes"))}',wake:'${js(t("App auf dem Fernseher anzeigen", "Show the app on the TV", "Afficher TK TV sur la télé"))}',woke:'${js(t("An den Fernseher gesendet.", "Sent to the TV.", "Envoyé à la télé."))}'};
var PLAY='<svg aria-hidden="true" width="18" height="18" viewBox="0 0 24 24"><path d="M7 4l13 8-13 8z" fill="currentColor"/></svg>';
var langs=[],langsLoaded=0;try{langs=JSON.parse(localStorage.getItem('tk_langs')||'[]')||[]}catch(e){langs=[]}
var code='';try{code=new URLSearchParams(location.search).get('code')||sessionStorage.getItem('ipvio_code')||''}catch(e){}
var kind='m',data={m:[],s:[],l:[]},cat=null,tm=null,seq=0,view='home',bcat={m:'',s:''},bseq=0;
var qEl=document.getElementById('q'),list=document.getElementById('list'),toastEl=document.getElementById('toast');
var gen=document.getElementById('gen'),ctlEl=document.getElementById('ctl'),langBox=document.getElementById('langs'),clr=document.getElementById('clr'),conn=document.getElementById('conn'),connT=document.getElementById('connt');
function toast(s){toastEl.textContent=s;toastEl.className='on';clearTimeout(toast.t);toast.t=setTimeout(function(){toastEl.className=''},2200)}
function api(p,o){var u='/api/'+p+'?code='+encodeURIComponent(code);for(var k in o||{})u+='&'+k+'='+encodeURIComponent(o[k]);
 return fetch(u).then(function(r){if(r.status==403||r.status==429){var c=r.status==403&&!api.asked?prompt(L.code):null;api.asked=1;if(c){code=c;api.asked=0;try{sessionStorage.setItem('ipvio_code',c)}catch(e){}setTimeout(render,50)}throw 0}
  api.asked=0;
  if(!r.ok)throw 1;return r.json()})}
function el(t,c,x){var e=document.createElement(t);if(c)e.className=c;if(x!=null)e.textContent=x;return e}
function msg(s){list.innerHTML='';list.appendChild(el('div','msg',s))}
function row(title,sub,badge,fn){var b=el('button','row'),m=el('div','m');m.appendChild(el('b',null,title));if(sub)m.appendChild(el('small',null,sub));b.appendChild(m);
 if(badge)b.appendChild(el('span','bd',badge));b.onclick=fn;return b}
function play(id,s,n){var o={id:id};if(s!=null){o.s=s;o.e=n}api('play',o).then(function(){toast(L.ok)}).catch(function(e){if(e!==0)toast(L.err)})}
function hm(s){var d=new Date(s*1000);return (d.getHours()<10?'0':'')+d.getHours()+':'+(d.getMinutes()<10?'0':'')+d.getMinutes()}
function liveRow(it,sub0){var sub=it.np?it.np+'  '+hm(it.ns)+' - '+hm(it.ne):(sub0||'');
 var b=row(it.t,sub,it.q,function(){play(it.id)});
 var st=el('span','star',it.f?'\u2605':'\u2606');st.onclick=function(ev){ev.stopPropagation();api('fav',{id:it.id}).then(function(d){it.f=d.f;st.textContent=d.f?'\u2605':'\u2606'}).catch(function(e){if(e!==0)toast(L.err)})};
 b.insertBefore(st,b.firstChild);return b}
function grp(g){var c=el('div','card'),h=el('div','ch'),m=el('div','m'),sel=0,qs=null;
 var b=el('b',null,g.t);if(g.lc&&langs.length!=1)b.appendChild(el('span','lc',g.lc.toUpperCase()));m.appendChild(b);
 if(g.np)m.appendChild(el('small',null,g.np+'  '+hm(g.ns)+' - '+hm(g.ne)));
 h.appendChild(m);
 var st=el('button','fv',null);st.setAttribute('aria-label',L2.fav);
 function star(){var f=g.v[sel].f;st.textContent=f?'\u2605':'\u2606';st.className=f?'fv on':'fv'}
 st.onclick=function(){var v=g.v[sel];api('fav',{id:v.id}).then(function(d){v.f=d.f;star()}).catch(function(e){if(e!==0)toast(L.err)})};
 var go=el('button','pl',null);go.innerHTML=PLAY;go.setAttribute('aria-label',L2.playtv);go.onclick=function(){play(g.v[sel].id)};
 h.appendChild(st);h.appendChild(go);c.appendChild(h);
 if(g.v.length>1||g.v[0].q){qs=el('div','qs');g.v.forEach(function(v,i){var qb=el('button',i==sel?'on':'',v.q||(L2.link+' '+(i+1)));
  qb.onclick=function(){sel=i;Array.prototype.forEach.call(qs.children,function(x,j){x.className=j==sel?'on':''});star();play(v.id)};qs.appendChild(qb)});c.appendChild(qs)}
 star();return c}
function items(a){list.innerHTML='';
 if(kind=='l'&&data.lg){var g=data.lg;if(!g.length){msg(L.none);return}
  if(data.nr>data.nl)list.appendChild(el('div','info',L2.grouped.replace('%1',data.nl).replace('%2',data.nr)));
  g.forEach(function(x){list.appendChild(grp(x))});return}
 if(!a||!a.length){msg(L.none);return}
 a.forEach(function(it){var sub=[it.y?it.y:'',it.g].filter(Boolean).join(' · ');
  if(kind=='l')list.appendChild(liveRow(it,sub));
  else list.appendChild(row(it.t,sub,it.q,function(){if(kind=='s')eps(it);else play(it.id)}))})}
function eps(it,back){msg(L.load);api('eps',{id:it.id}).then(function(a){list.innerHTML='';
 var bar=el('div','bar'),bk=el('button','back','< '+L.back);bk.onclick=back||show;bar.appendChild(bk);
 var seasons=[];a.forEach(function(e){if(seasons.indexOf(e.s)<0)seasons.push(e.s)});seasons.sort(function(x,y){return x-y});
 var sel=el('select');seasons.forEach(function(s){var o=el('option',null,L.season+' '+s);o.value=s;sel.appendChild(o)});bar.appendChild(sel);list.appendChild(bar);
 list.appendChild(el('div','msg',it.t));var box=el('div');list.appendChild(box);
 function fill(){box.innerHTML='';a.filter(function(e){return e.s==sel.value}).forEach(function(e){box.appendChild(row(e.n+'. '+e.t,null,null,function(){play(it.id,e.s,e.n)}))})}
 sel.onchange=fill;fill()}).catch(function(e){if(e!==0)msg(L.err)})}
function chList(call,o,backFn,backLabel,title){msg(L.load);api(call,o).then(function(a){list.innerHTML='';var bk=el('button','back','< '+backLabel);bk.onclick=backFn;list.appendChild(bk);
 if(title)list.appendChild(el('div','msg',title));if(!a.length){list.appendChild(el('div','msg',L.none));return}
 a.forEach(function(it){list.appendChild(liveRow(it))})}).catch(function(e){if(e!==0)msg(L.err)})}
function cats(){msg(L.load);api('cats').then(function(a){list.innerHTML='';
 var fb=row(L.favs,null,null,function(){chList('favs',{},cats,L.cats,L.favs)});list.appendChild(fb);
 list.appendChild(row(L.manage,null,null,manage));
 list.appendChild(row(L.imp,null,null,importList));
 list.appendChild(el('div','msg',L.mylists));
 api('lists').then(function(ls){ls.forEach(function(l){list.insertBefore(row(l.g,l.n+'',null,function(){chList('list',{i:l.i},cats,L.cats,l.g)}),mark)})}).catch(function(){});
 var mark=el('div','msg',L.cats);list.appendChild(mark);if(!a.length){return}
 a.forEach(function(c){list.appendChild(row(c.g,c.n+'',null,function(){chans(c.g)}))})}).catch(function(e){if(e!==0)msg(L.err)})}
function importList(){var f=document.createElement('input');f.type='file';f.onchange=function(){var x=f.files[0];if(!x)return;msg(L.load);
 fetch('/listimport?name='+encodeURIComponent(x.name)+'&code='+encodeURIComponent(code),{method:'POST',body:x}).then(function(r){if(!r.ok)throw 1;toast(L.impDone);cats()}).catch(function(){toast(L.err)})};f.click()}
function chans(g){chList('channels',{g:g},cats,L.cats,null)}
function manage(){msg(L.load);api('groups').then(function(a){list.innerHTML='';var bk=el('button','back','< '+L.back);bk.onclick=cats;list.appendChild(bk);
 list.appendChild(el('div','msg',L.manageHint));
 a.forEach(function(c){var b=row(c.g,c.n+'',c.h?L.hidden:L.shown,null);
  b.onclick=function(){c.h=!c.h;api('hide',{g:c.g,h:c.h?1:0}).then(function(){var bd=b.querySelector('.bd');bd.textContent=c.h?L.hidden:L.shown;b.style.opacity=c.h?0.5:1}).catch(function(e){c.h=!c.h;if(e!==0)toast(L.err)})};
  if(c.h)b.style.opacity=0.5;list.appendChild(b)})}).catch(function(e){if(e!==0)msg(L.err)})}
var rdata=null,rt=0;
function resumeView(){var go=function(){list.innerHTML='';var a=(rdata||[]).filter(function(x){return x.k==kind});
 if(!a.length){msg(L.hint);return}
 list.appendChild(el('div','msg',L.cont));
 a.forEach(function(x){var sub=(x.k=='s'&&x.s?L.season+' '+x.s+' · '+L.ep+' '+x.e:'')+(x.p>0?(x.k=='s'&&x.s?' · ':'')+x.p+' %':'');
  list.appendChild(row(x.t,sub,null,function(){if(x.k=='s'&&x.s)play(x.id,x.s,x.e);else play(x.id)}))})};
 if(rdata&&Date.now()-rt<30000){go();return}
 api('resume').then(function(d){rdata=d;rt=Date.now();if(!qEl.value.trim())go()}).catch(function(e){if(e!==0)msg(L.hint)})}
function show(){var q=qEl.value.trim();
 if(!q){msg(L.hint);return}
 items(data[kind])}
function cnt(d,k){return k=='m'?d.nm:k=='s'?d.ns:d.nl}
function setKind(k){kind=k;Array.prototype.forEach.call(document.querySelectorAll('#tabs button'),function(x){x.className=x.getAttribute('data-k')==k?'on':''})}
function setTabs(d){var bs=document.querySelectorAll('#tabs button');bs[0].textContent=TM+(d&&d.nm?' · '+d.nm:'');
 bs[1].textContent=TS+(d&&d.ns?' · '+d.ns:'');bs[2].textContent='Live TV'+(d&&d.nl?' · '+d.nl:'')}
function saveLangs(){try{localStorage.setItem('tk_langs',JSON.stringify(langs))}catch(e){}}
function drawLangs(a){langBox.innerHTML='';if(!a||a.length<2)return;
 var known={};a.forEach(function(x){known[x.c]=1});langs=langs.filter(function(c){return known[c]});
 a.forEach(function(x){var b=el('button',langs.indexOf(x.c)>=0?'on':'',x.n||L2.untagged);
  b.onclick=function(){var i=langs.indexOf(x.c);if(i>=0)langs.splice(i,1);else langs.push(x.c);saveLangs();drawLangs(a);relang()};langBox.appendChild(b)});
 var all=el('button',langs.length?'':'on',L2.all);all.onclick=function(){langs=[];saveLangs();drawLangs(a);relang()};langBox.appendChild(all)}
function loadLangs(){api('langs').then(drawLangs).catch(function(){})}
function search(){var q=qEl.value.trim(),me=++seq;clr.style.display=q?'block':'none';if(!q){setTabs(null);show();return}
 api('search',{q:q,lang:langs.join(',')}).then(function(d){if(me!=seq)return;if(d.loading){msg(L.wait);return}data=d;setTabs(d);
  if(!cnt(d,kind)){var o=['l','m','s'].filter(function(k){return cnt(d,k)})[0];if(o)setKind(o)}
  show()}).catch(function(e){if(e!==0)msg(L.err)})}
function relang(){if(view=='find')search();else render()}
function img(u){var im=document.createElement('img');im.alt='';im.loading='lazy';im.referrerPolicy='no-referrer';im.onerror=function(){im.style.display='none'};im.src=u;return im}
function pcard(it){var b=el('button','pc'),ph=el('div','ph');ph.appendChild(el('span',null,it.t));if(it.p)ph.appendChild(img(it.p));
 b.appendChild(ph);b.appendChild(el('small',null,it.t));b.onclick=function(){detail(it)};return b}
function detail(it){var top=window.pageYOffset||0;document.body.className='v-sub';list.innerHTML='';window.scrollTo(0,0);
 function open(){detail(it)}
 var bk=el('button','back','< '+L.back);bk.onclick=function(){document.body.className='v-'+view;render()};list.appendChild(bk);
 var d=el('div','det'),ph=el('div','ph');ph.appendChild(el('span',null,it.t));if(it.p)ph.appendChild(img(it.p));d.appendChild(ph);
 d.appendChild(el('h1',null,it.t));
 var meta=el('div','meta',[it.y?it.y:'',it.q,it.g].filter(Boolean).join(' · '));d.appendChild(meta);
 var b=el('button','big',null);b.innerHTML=PLAY;b.appendChild(el('span',null,it.k=='s'?L3.episodes:L2.playtv));
 b.onclick=function(){if(it.k=='s')eps(it,open);else play(it.id)};d.appendChild(b);
 var ov=el('p','ov','');d.appendChild(ov);list.appendChild(d);
 api('info',{id:it.id}).then(function(x){var parts=[x.y||(it.y?it.y:''),x.g&&x.g.length?x.g.slice(0,3).join(', '):'',x.rt?x.rt+' min':'',x.sc?'\u2605 '+x.sc:'',it.q].filter(Boolean);
  if(parts.length)meta.textContent=parts.join(' · ');if(x.ov)ov.textContent=x.ov;if(x.p&&!it.p)ph.appendChild(img(x.p))}).catch(function(){})}
function grid(k,me){list.innerHTML='';var g=el('div','grid'),more=el('button','more',L3.more),off=0;list.appendChild(g);
 function load(){api('vlist',{k:k,g:bcat[k],lang:langs.join(','),o:off}).then(function(d){if(me!=bseq)return;if(d.loading){msg(L.wait);return}
  if(!d.n){msg(L.none);return}
  d.it.forEach(function(it){it.k=k;g.appendChild(pcard(it))});off+=d.it.length;
  if(off<d.n&&d.it.length)list.appendChild(more);else if(more.parentNode)more.parentNode.removeChild(more)}).catch(function(e){if(e!==0)msg(L.err)})}
 more.onclick=load;load()}
function browse(){var k=view,me=++bseq;msg(L.load);
 api('vcats',{k:k,lang:langs.join(',')}).then(function(a){if(me!=bseq)return;gen.innerHTML='';
  var names={};a.forEach(function(c){names[c.g]=1});if(bcat[k]&&!names[bcat[k]])bcat[k]='';
  function chipB(label,g){var b=el('button',bcat[k]==g?'on':'',label);b.onclick=function(){bcat[k]=g;Array.prototype.forEach.call(gen.children,function(x){x.className=x==b?'on':''});bseq++;grid(k,bseq)};gen.appendChild(b)}
  chipB(L3.allc,'');a.forEach(function(c){chipB(c.g,c.g)});
  grid(k,me)}).catch(function(e){if(e!==0)msg(L.err)})}
function hrow(k,title,me,w){api('vlist',{k:k,g:'',lang:langs.join(','),o:0}).then(function(d){if(me!=bseq||!d.it||!d.it.length)return;
  var hd=el('div','sech'),all=el('button','lnk',L3.seeall),r=el('div','hrow');hd.appendChild(el('b',null,title));all.onclick=function(){nav(k)};hd.appendChild(all);w.appendChild(hd);
  d.it.slice(0,12).forEach(function(it){it.k=k;r.appendChild(pcard(it))});w.appendChild(r)}).catch(function(){})}
function homeView(){var me=++bseq;list.innerHTML='';var w0=el('div'),w1=el('div'),w2=el('div'),w3=el('div'),w4=el('div');[w0,w1,w2,w3,w4].forEach(function(w){list.appendChild(w)});
 w4.appendChild(el('div','sech',''));w4.appendChild(row(L3.wake,null,null,function(){api('wake').then(function(){toast(L3.woke)}).catch(function(e){if(e!==0)toast(L.err)})}));
 api('resume').then(function(a){if(me!=bseq||!a.length)return;var x=a[0],h=el('div','hero'),m=el('div','m');
  m.appendChild(el('small',null,L.cont));m.appendChild(el('b',null,x.t));
  if(x.k=='s'&&x.s)m.appendChild(el('span',null,L.season+' '+x.s+' · '+L.ep+' '+x.e));
  var bar=el('div','pg'),fill=el('i');fill.style.width=(x.p||0)+'%';bar.appendChild(fill);m.appendChild(bar);h.appendChild(m);
  var b=el('button','pl',null);b.innerHTML=PLAY;b.setAttribute('aria-label',L2.playtv);b.onclick=function(){if(x.k=='s'&&x.s)play(x.id,x.s,x.e);else play(x.id)};h.appendChild(b);
  w0.appendChild(h);
  a.slice(1,5).forEach(function(y){var sub=(y.k=='s'&&y.s?L.season+' '+y.s+' · '+L.ep+' '+y.e:'')+(y.p>0?(y.k=='s'&&y.s?' · ':'')+y.p+' %':'');
   w0.appendChild(row(y.t,sub,null,function(){if(y.k=='s'&&y.s)play(y.id,y.s,y.e);else play(y.id)}))})}).catch(function(){});
 hrow('m',TM,me,w1);hrow('s',TS,me,w2);
 api('favs').then(function(a){if(me!=bseq||!a.length)return;var hd=el('div','sech');hd.appendChild(el('b',null,L.favs));w3.appendChild(hd);
  a.slice(0,8).forEach(function(it){w3.appendChild(liveRow(it))})}).catch(function(){})}
function render(){if(view=='home')homeView();else if(view=='live'){bseq++;cats()}else if(view=='m'||view=='s')browse();else{bseq++;if(qEl.value.trim())search();else show()}}
function nav(v){view=v;if(v=='m'||v=='s')kind=v;document.body.className='v-'+v;window.scrollTo(0,0);
 Array.prototype.forEach.call(document.querySelectorAll('#nav button'),function(x){x.className=x.getAttribute('data-v')==v?'on':''});
 if(v=='find')setKind(kind);render();if(v=='find')qEl.focus()}
Array.prototype.forEach.call(document.querySelectorAll('#nav button'),function(b){b.onclick=function(){nav(b.getAttribute('data-v'))}});
document.getElementById('mx').onclick=function(){ctlEl.className=ctlEl.className=='open'?'':'open'};
clr.onclick=function(){qEl.value='';search();qEl.focus()};
qEl.oninput=function(){clearTimeout(tm);tm=setTimeout(search,300)};
Array.prototype.forEach.call(document.querySelectorAll('#tabs button'),function(b){b.onclick=function(){kind=b.getAttribute('data-k');
 Array.prototype.forEach.call(document.querySelectorAll('#tabs button'),function(x){x.className=x==b?'on':''});show()}});
Array.prototype.forEach.call(document.querySelectorAll('#ctl button[data-c]'),function(b){b.onclick=function(){
 api('ctl',{c:b.getAttribute('data-c'),v:b.getAttribute('data-v')||''}).then(function(d){if(d&&d.idle)toast(L.idle)}).catch(function(e){if(e!==0)toast(L.err)})}});
function clock(s){s=Math.max(0,s|0);var h=(s/3600)|0,m=((s%3600)/60)|0,r=s%60;return(h?h+':'+(m<10?'0':''):'')+m+':'+(r<10?'0':'')+r}
function poll(){api('state').then(function(d){var n=document.getElementById('now');conn.className='ok';connT.textContent=L2.on;if(!langsLoaded){langsLoaded=1;loadLangs()}
 n.textContent=d.a?(d.t?d.t+'  ':'')+(d.live?'LIVE':clock(d.pos)+(d.dur>0?' / '+clock(d.dur):''))+(d.pl?'':'  ||'):L.idle}).catch(function(){conn.className='';connT.textContent=L2.off})}
setInterval(poll,3000);poll();nav('home');
</script></body></html>"""
    }
}
