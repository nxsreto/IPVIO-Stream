package com.ipvio.stream

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Personal fork: a small "flight recorder" for the case that the app is closed without a crash report
 * (typically the system ending it for lack of memory, which no crash handler sees).
 *
 * [mark] writes one line with the time, what happened and the memory figures to the private settings at once,
 * so the last lines survive when the process is killed. At the next start the lines of the session before are
 * kept in [prev] and shown under Settings > Diagnostics, together with the system's own reason for the last
 * stop where Android reports one ([systemExit], Android 11 and later).
 */
object Trail {
    private const val PREFS = "tk_diag"
    private const val KEEP = 14
    @Volatile private var app: Context? = null

    /** Lines of the session before this one, newest last. Empty = nothing recorded. */
    @Volatile var prev: String = ""
        private set

    /** Once per process: keep the lines of the last session and start a new list. */
    @Synchronized fun init(c: Context) {
        if (app != null) return
        app = c.applicationContext
        runCatching {
            val p = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prev = p.getString("trail", "").orEmpty()
            p.edit().putString("prev", prev).putString("trail", "").commit()
        }
        mark("start " + runCatching { AppUpdate.currentName(c) }.getOrDefault(""))
    }

    /** Record [what] with the memory figures right now. Cheap enough for single events, not for loops. */
    @Synchronized fun mark(what: String) {
        val c = app ?: return
        runCatching {
            val rt = Runtime.getRuntime()
            val used = (rt.totalMemory() - rt.freeMemory()) / MB
            val max = rt.maxMemory() / MB
            val mi = ActivityManager.MemoryInfo()
            (c.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(mi)
            val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "  " + what.take(60) +
                "  ·  app " + used + "/" + max + " MB  ·  free " + (mi.availMem / MB) + " MB" + (if (mi.lowMemory) "  ·  LOW" else "")
            val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val all = (p.getString("trail", "").orEmpty().lines().filter { it.isNotBlank() } + line).takeLast(KEEP)
            p.edit().putString("trail", all.joinToString("\n")).commit()
        }
    }

    /** Why the system says the app stopped the last times (newest first), or null if this Android version does not tell. */
    fun systemExit(c: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT < 30) return null
        val am = c.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val list = am.getHistoricalProcessExitReasons(c.packageName, 0, 3)
        if (list.isEmpty()) return null
        val fmt = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.US)
        list.joinToString("\n") { e ->
            fmt.format(Date(e.timestamp)) + "  " + reasonName(e.reason) + "  ·  " + (e.rss / 1024) + " MB" +
                (e.description?.takeIf { it.isNotBlank() }?.let { "  ·  " + it.take(80) } ?: "")
        }
    }.getOrNull()

    /** Names of ApplicationExitInfo.REASON_* (plain numbers, so this stays a pure function). */
    fun reasonName(reason: Int): String = when (reason) {
        1 -> "exit (app closed itself)"
        2 -> "signal (killed)"
        3 -> "LOW MEMORY (system ended the app)"
        4 -> "crash (Java)"
        5 -> "crash (native)"
        6 -> "ANR (app did not respond)"
        7 -> "initialization failure"
        8 -> "permission change"
        9 -> "excessive resource usage"
        10 -> "closed by the user"
        11 -> "user stopped"
        12 -> "dependency died"
        13 -> "other (system)"
        else -> "reason $reason"
    }

    private const val MB = 1024L * 1024L
}
