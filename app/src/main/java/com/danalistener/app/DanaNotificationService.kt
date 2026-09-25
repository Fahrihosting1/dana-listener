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
        // Package name app DANA di Android
        private const val DANA_PACKAGE = "id.dana"
        // Kata kunci notif pembayaran masuk DANA Bisnis
        private val KEYWORDS = listOf("Pembayaran Masuk", "diterima DANA Bisnis", "pembayaran diterima")
        // Regex extract nominal Rp
        private val AMOUNT_REGEX = Regex("""Rp[\s]?([\d.,]+)""")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Filter: hanya proses notif dari app DANA
        if (sbn.packageName != DANA_PACKAGE) return

        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE) ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""

        val fullText = "$title $text $bigText"
        Log.d(TAG, "Notif DANA: $fullText")

        // Filter: hanya proses notif pembayaran masuk
        val isPembayaranMasuk = KEYWORDS.any { keyword ->
            fullText.contains(keyword, ignoreCase = true)
        }
        if (!isPembayaranMasuk) return

        // Extract nominal
        val amountMatch = AMOUNT_REGEX.find(fullText)
        val rawAmount = amountMatch?.groupValues?.get(1) ?: run {
            Log.w(TAG, "Gak bisa extract nominal dari: $fullText")
            return
        }

        // Bersihkan nominal: hapus titik/koma pemisah ribuan
        val amount = rawAmount.replace(".", "").replace(",", "")

        Log.d(TAG, "Pembayaran masuk: Rp$amount | Teks: $fullText")
        addLog("💰 Rp$amount detected — posting ke server...")

        // Kirim ke webhook di background thread
        Thread {
            postToWebhook(amount, fullText, sbn.postTime)
        }.start()
    }

    private fun postToWebhook(amount: String, rawNotification: String, timestamp: Long) {
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        val webhookUrl = prefs.getString("webhook_url", "") ?: ""
        val secret = prefs.getString("webhook_secret", "") ?: ""

        if (webhookUrl.isEmpty()) {
            Log.e(TAG, "Webhook URL belum diisi!")
            addLog("❌ Error: Webhook URL belum diisi di app")
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
                put("sender", "DANA Bisnis")
                put("timestamp", timestamp)
                put("raw_notification", rawNotification)
            }

            val writer = OutputStreamWriter(conn.outputStream)
            writer.write(payload.toString())
            writer.flush()
            writer.close()

            val responseCode = conn.responseCode
            val response = conn.inputStream.bufferedReader().readText()

            Log.d(TAG, "Response: $responseCode — $response")

            val status = JSONObject(response).optString("status", "unknown")
            when (status) {
                "matched" -> {
                    val orderId = JSONObject(response).optString("order_id", "-")
                    addLog("✅ AUTO-ACC! Order $orderId — Rp$amount")
                    showLocalNotif("✅ Auto-ACC Berhasil", "Order $orderId — Rp$amount di-acc otomatis!")
                }
                "unmatched" -> {
                    addLog("⚠️ Unmatched Rp$amount — gak ada order pending")
                    showLocalNotif("⚠️ Pembayaran Unmatched", "Rp$amount masuk tapi gak ada order pending!")
                }
                "duplicate-ignored" -> addLog("🔁 Duplikat diabaikan — Rp$amount")
                else -> addLog("❓ Response: $status — Rp$amount")
            }

            conn.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "Error posting webhook: ${e.message}")
            addLog("❌ Error: ${e.message}")
        }
    }

    private fun addLog(message: String) {
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val time = sdf.format(Date())
        val existing = prefs.getString("logs", "") ?: ""
        val lines = existing.split("\n").take(19) // keep last 20 lines
        val newLog = "[$time] $message\n" + lines.joinToString("\n")
        prefs.edit().putString("logs", newLog).apply()
    }

    private fun showLocalNotif(title: String, message: String) {
        val channelId = "dana_listener"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "DANA Listener", NotificationManager.IMPORTANCE_HIGH)
            nm.createNotificationChannel(channel)
        }

        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build()
        }

        nm.notify(System.currentTimeMillis().toInt(), notif)
    }
}
