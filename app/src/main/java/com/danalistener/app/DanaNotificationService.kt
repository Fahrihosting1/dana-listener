package com.danalistener.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
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
        private const val FOREGROUND_NOTIF_ID = 1001
        private const val FOREGROUND_CHANNEL_ID = "dana_foreground"
        private const val RESULT_CHANNEL_ID = "dana_result"

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

    // =============================================
    // FOREGROUND SERVICE — biar gak di-kill Android
    // =============================================
    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Auto-restart kalau service mati
        val restartIntent = Intent(applicationContext, BootReceiver::class.java)
        restartIntent.action = "RESTART_SERVICE"
        sendBroadcast(restartIntent)
        addLog("⚠️ Service mati — mencoba restart...")
    }

    private fun startForegroundService() {
        createNotificationChannels()

        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, FOREGROUND_CHANNEL_ID)
                .setContentTitle("💰 DANA Listener Aktif")
                .setContentText("Memantau pembayaran masuk...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true) // gak bisa di-swipe
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("💰 DANA Listener Aktif")
                .setContentText("Memantau pembayaran masuk...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }

        startForeground(FOREGROUND_NOTIF_ID, notification)
        Log.d(TAG, "Foreground service started")
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Channel foreground (low importance — gak bunyi)
            val foregroundChannel = NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "DANA Listener Status",
                NotificationManager.IMPORTANCE_LOW // silent, gak ganggu
            ).apply {
                description = "Status service DANA Listener"
                setShowBadge(false)
            }

            // Channel hasil transaksi (high importance — bunyi)
            val resultChannel = NotificationChannel(
                RESULT_CHANNEL_ID,
                "Notifikasi Pembayaran",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifikasi pembayaran masuk"
            }

            nm.createNotificationChannel(foregroundChannel)
            nm.createNotificationChannel(resultChannel)
        }
    }

    // =============================================
    // LISTENER NOTIFIKASI
    // =============================================
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

        Log.d(TAG, "Notif dari $appName: $fullText")

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

    // =============================================
    // POST KE WEBHOOK
    // =============================================
    private fun postToWebhook(amount: String, source: String, rawNotification: String, timestamp: Long) {
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        val webhookUrl = prefs.getString("webhook_url", "") ?: ""
        val secret = prefs.getString("webhook_secret", "") ?: ""

        if (webhookUrl.isEmpty()) {
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
                "matched", "success" -> {
                    val orderId = JSONObject(response).optString("order_id",
                        JSONObject(response).optString("id", "-"))
                    addLog("✅ SUKSES! $orderId — Rp$amount [$source]")
                    showResultNotif("✅ Pembayaran Berhasil", "[$source] Rp$amount diterima!")
                }
                "unmatched" -> {
                    addLog("⚠️ Unmatched Rp$amount [$source]")
                    showResultNotif("⚠️ Pembayaran Unmatched", "Rp$amount masuk tapi gak ada order pending!")
                }
                "duplicate-ignored" -> addLog("🔁 Duplikat — Rp$amount [$source]")
                else -> addLog("❓ $status — Rp$amount [$source]")
            }
            conn.disconnect()

        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}")
            addLog("❌ Error: ${e.message}")
        }
    }

    // =============================================
    // HELPER
    // =============================================
    private fun addLog(message: String) {
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val time = sdf.format(Date())
        val existing = prefs.getString("logs", "") ?: ""
        val lines = existing.split("\n").take(19)
        val newLog = "[$time] $message\n" + lines.joinToString("\n")
        prefs.edit().putString("logs", newLog).apply()
    }

    private fun showResultNotif(title: String, message: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, RESULT_CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(message)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build()
        }
        nm.notify(System.currentTimeMillis().toInt(), notif)
    }
}
