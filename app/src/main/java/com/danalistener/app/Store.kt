package com.danalistener.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Penyimpanan log + riwayat notifikasi pembayaran (thread-safe).
 * Riwayat dipakai buat fitur "Cek Notif Manual" dan buat dedupe catch-up.
 */
object Store {
    private const val PREFS = "config"
    private const val MAX_HISTORY = 40
    private const val MAX_LOG_LINES = 30
    private val lock = Any()

    data class Rec(
        val key: String,
        val time: Long,
        val pkg: String,
        val app: String,
        val text: String,
        val amount: String,
        val state: String,   // pending | sent | unmatched | dup | failed | ignored | old
        val upd: Long
    )

    /** State yang artinya server sudah menjawab — jangan kirim ulang otomatis. */
    val DONE_STATES = setOf("sent", "unmatched", "dup")

    // ───────────────────────── LOG ─────────────────────────
    fun log(ctx: Context, message: String) {
        synchronized(lock) {
            val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            val old = (prefs.getString("logs", "") ?: "")
                .split("\n").filter { it.isNotBlank() }.take(MAX_LOG_LINES - 1)
            val line = "[$time] ${message.replace("\n", " ")}"
            prefs.edit().putString("logs", (listOf(line) + old).joinToString("\n")).apply()
        }
    }

    // ───────────────────────── HISTORY ─────────────────────────
    private fun readAll(ctx: Context): MutableList<Rec> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("history", "[]") ?: "[]"
        val out = mutableListOf<Rec>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out += Rec(
                    o.getString("key"), o.getLong("time"), o.optString("pkg"), o.optString("app"),
                    o.optString("text"), o.optString("amount"), o.optString("state"), o.optLong("upd")
                )
            }
        } catch (_: Exception) { }
        return out
    }

    private fun writeAll(ctx: Context, list: List<Rec>) {
        val arr = JSONArray()
        list.take(MAX_HISTORY).forEach {
            arr.put(JSONObject().apply {
                put("key", it.key); put("time", it.time); put("pkg", it.pkg); put("app", it.app)
                put("text", it.text); put("amount", it.amount); put("state", it.state); put("upd", it.upd)
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("history", arr.toString()).apply()
    }

    fun get(ctx: Context, key: String): Rec? = synchronized(lock) { readAll(ctx).firstOrNull { it.key == key } }

    /** Terbaru di atas (urut berdasarkan waktu notif). */
    fun list(ctx: Context): List<Rec> = synchronized(lock) { readAll(ctx).sortedByDescending { it.time } }

    fun upsert(ctx: Context, rec: Rec) {
        synchronized(lock) {
            val all = readAll(ctx)
            all.removeAll { it.key == rec.key }
            all.add(0, rec)
            writeAll(ctx, all.sortedByDescending { it.time })
        }
    }

    fun setState(ctx: Context, key: String, state: String) {
        synchronized(lock) {
            val all = readAll(ctx)
            val i = all.indexOfFirst { it.key == key }
            if (i >= 0) {
                all[i] = all[i].copy(state = state, upd = System.currentTimeMillis())
                writeAll(ctx, all)
            }
        }
    }
}
