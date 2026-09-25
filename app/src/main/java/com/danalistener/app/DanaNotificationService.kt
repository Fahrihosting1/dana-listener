package com.danalistener.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class DanaNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "DanaListener"

        private val SUPPORTED_APPS = mapOf(
            "id.dana" to "DANA Bisnis",
            "com.gojek.gopay.merchant" to "GoPay Merchant",
            "com.go-jek.gopay.merchant" to "GoPay Merchant",
            "com.gojek.merchant" to "GoPay Merchant"
        )

        private val KEYWORDS = mapOf(
            "id.dana" to listOf(
                "Pembayaran Masuk",
                "diterima DANA Bisnis",
                "pembayaran diterima"
            ),
            "gopay" to listOf(
                "Pembayaran QRIS statis diterima",
                "Pembayaran diterima",
                "pembayaran masuk",
                "QRIS diterima"
            )
        )

        private val AMOUNT_REGEX = Regex("""Rp[\s]?([\d.,]+)""")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName

        val appName = SUPPORTED_APPS[pkg]
            ?: if (pkg.contains("gopay") || pkg.contains("gojek")) "GoPay Merchant"
            else return

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val fullText = "$title $text $bigText"

        val keywords = if (pkg == "id.dana") KEYWORDS["id.dana"]!! else KEYWORDS["gopay"]!!
        val isPembayaran = keywords.any { fullText.contains(it, ignoreCase = true) }
        if (!isPembayaran) return

        val amountMatch = AMOUNT_REGEX.find(fullText)
        val rawAmount = amountMatch?.groupValues?.get(1) ?: return
        val amount = rawAmount.replace(".", "").replace(",", "")

        addLog("💰 [$appName] Rp$amount detected — posting ke server...")

        Thread {
            postToWebhook(amount, appName, fullText, sbn.postTime)
        }.start()
    }

    private fun postToWebhook(amount: String, source: String, rawNotification: String, timestamp: Long) {
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        val webhookUrl = prefs.getString("webhook_url", "") ?: ""
        val secret = prefs.getString("webhook_secret", "") ?: ""

        if (webhookUrl.isEmpty()) {
            addLog("❌ Error: Webhook URL belum diisi")
            return
        }

        try {
            val url = URL(webhookUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-webhook-secret", secret)
            conn.doOutput = true
            conn.connectTimeout = 10000
            conn.readTimeout = 10000

            val payload = JSONObject().apply {
                put("amount", amount)
                put("sender", source)
                put("timestamp", timestamp)
                put("raw_notification", rawNotification)
            }

            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(payload.toString())
            writer.flush()
            writer.close()

            val response = conn.inputStream.bufferedReader().readText()
            val status = JSONObject(response).optString("status", "unknown")

            when (status) {
                "matched" -> {
                    val orderId = JSONObject(response).optString("order_id", "-")
                    addLog("✅ AUTO-ACC! Order $orderId — Rp$amount [$source]")
                    showLocalNotif("✅ Auto-ACC Berhasil", "[$source] Rp$amount")
                }
                "unmatched" -> {
                    addLog("⚠️ Unmatched Rp$amount [$source]")
                    showLocalNotif("⚠️ Unmatched", "[$source] Rp$amount — gak ada order pending")
                }
                "duplicate-ignored" -> addLog("🔁 Duplikat — Rp$amount [$source]")
                else -> addLog("❓ $status — Rp$amount [$source]")
            }
            conn.disconnect()
        } catch (e: Exception) {
            addLog("❌ Error: ${e.message}")
        }
    }

    private fun addLog(message: String) {
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val time = sdf.format(Date())
        val existing = prefs.getString("logs", "") ?: ""
        val lines = existing.split("\n").take(19)
        val newLog = "[$time] $message\n" + lines.joinToString("\n")
        prefs.edit().putString("logs", newLog).apply()
    }

    private fun showLocalNotif(title: String, message: String) {
        val channelId = "dana_listener"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(channelId, "DANA Listener", NotificationManager.IMPORTANCE_HIGH))
        }
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle(title).setContentText(message)
                .setSmallIcon(android.R.drawable.ic_dialog_info).build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title).setContentText(message)
                .setSmallIcon(android.R.drawable.ic_dialog_info).build()
        }
        nm.notify(System.currentTimeMillis().toInt(), notif)
    }
}
