package com.danalistener.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Notifier {
    const val FOREGROUND_CHANNEL_ID = "dana_foreground"
    const val RESULT_CHANNEL_ID = "dana_result"

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(FOREGROUND_CHANNEL_ID, "DANA Listener Status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Status service DANA Listener"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(RESULT_CHANNEL_ID, "Notifikasi Pembayaran", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Notifikasi pembayaran masuk"
            }
        )
    }

    fun result(ctx: Context, title: String, message: String) {
        try {
            ensureChannels(ctx)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(ctx, RESULT_CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(ctx)
            }
            val n = b.setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build()
            nm.notify(System.currentTimeMillis().toInt(), n)
        } catch (_: Exception) { }
    }
}

object WebhookSender {

    private data class Attempt(val ok: Boolean, val status: String, val retryable: Boolean, val detail: String, val body: String)

    private fun attempt(ctx: Context, rec: Store.Rec, manual: Boolean): Attempt {
        val prefs = ctx.getSharedPreferences("config", Context.MODE_PRIVATE)
        val webhookUrl = prefs.getString("webhook_url", "") ?: ""
        val secret = prefs.getString("webhook_secret", "") ?: ""
        if (webhookUrl.isEmpty()) return Attempt(false, "no-url", false, "Webhook URL belum diisi di app", "")

        var conn: HttpURLConnection? = null
        return try {
            val c = URL(webhookUrl).openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "POST"
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-webhook-secret", secret)
            c.doOutput = true
            c.connectTimeout = 10000
            c.readTimeout = 10000

            val payload = JSONObject().apply {
                put("amount", rec.amount)
                put("sender", rec.app)
                put("timestamp", rec.time)
                put("raw_notification", rec.text)
                put("manual", manual)
                put("notif_key", rec.key)
            }
            c.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }

            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""

            if (code in 200..299) {
                // Respons bukan JSON pun tetap dianggap terkirim (HTTP 2xx)
                val status = try { JSONObject(body).optString("status", "ok") } catch (_: Exception) { "ok" }
                Attempt(true, status, false, "HTTP $code", body)
            } else {
                Attempt(false, "http-$code", code >= 500 || code == 408 || code == 429, "HTTP $code ${body.take(80)}", body)
            }
        } catch (e: Exception) {
            Attempt(false, "error", true, e.message ?: e.javaClass.simpleName, "")
        } finally {
            try { conn?.disconnect() } catch (_: Exception) { }
        }
    }

    /**
     * Kirim ke webhook dengan retry (0s, 3s, 10s, 30s) lalu simpan hasilnya ke riwayat.
     * Blocking — panggil dari background thread. Return state akhir.
     */
    fun deliver(ctx: Context, rec: Store.Rec, manual: Boolean): String {
        val app = ctx.applicationContext
        Store.upsert(app, rec.copy(state = "pending", upd = System.currentTimeMillis()))

        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DanaListener:webhook")
        wl.acquire(150_000L)

        try {
            val delays = longArrayOf(0, 3_000, 10_000, 30_000)
            var last: Attempt? = null
            for ((i, d) in delays.withIndex()) {
                if (d > 0) Thread.sleep(d)
                val r = attempt(app, rec, manual)
                last = r
                if (r.ok || !r.retryable) break
                if (i < delays.size - 1) Store.log(app, "🔄 Gagal kirim Rp${rec.amount} (${r.detail}) — retry ${i + 1}/${delays.size - 1}")
            }
            val r = last!!
            val tag = if (manual) " (manual)" else ""

            val state: String
            if (!r.ok) {
                state = "failed"
                Store.log(app, "❌ Gagal kirim Rp${rec.amount} [${rec.app}]$tag — ${r.detail}. Bisa kirim ulang lewat 🔍 Cek Notif Manual")
                Notifier.result(app, "❌ Gagal kirim ke server", "Rp${rec.amount} — buka app → Cek Notif Manual")
            } else when (r.status) {
                "matched", "success" -> {
                    state = "sent"
                    val j = try { JSONObject(r.body) } catch (_: Exception) { JSONObject() }
                    val orderId = j.optString("order_id", j.optString("id", "-"))
                    Store.log(app, "✅ SUKSES! $orderId — Rp${rec.amount} [${rec.app}]$tag")
                    Notifier.result(app, "✅ Pembayaran Berhasil", "[${rec.app}] Rp${rec.amount} diterima!")
                }
                "unmatched" -> {
                    state = "unmatched"
                    Store.log(app, "⚠️ Unmatched Rp${rec.amount} [${rec.app}]$tag — server tidak nemu order pending dgn nominal ini")
                    Notifier.result(app, "⚠️ Pembayaran Unmatched", "Rp${rec.amount} masuk tapi gak ada order pending!")
                }
                "duplicate-ignored" -> {
                    state = "dup"
                    Store.log(app, "🔁 Duplikat — Rp${rec.amount} [${rec.app}]$tag")
                }
                else -> {
                    state = "sent"
                    Store.log(app, "❓ Status '${r.status}' — Rp${rec.amount} [${rec.app}]$tag")
                }
            }
            Store.setState(app, rec.key, state)
            return state
        } catch (e: Exception) {
            Store.setState(app, rec.key, "failed")
            Store.log(app, "❌ Error: ${e.message}")
            return "failed"
        } finally {
            if (wl.isHeld) wl.release()
        }
    }
}
