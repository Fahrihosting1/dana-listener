package com.danalistener.app

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DanaNotificationService : NotificationListenerService() {

    companion object {
        private const val TAG = "DanaListener"
        private const val FOREGROUND_NOTIF_ID = 1001

        /** Notif yang masih nongol di status bar & umurnya <= ini akan dikirim otomatis saat catch-up. */
        private const val CATCHUP_WINDOW_MS = 15 * 60 * 1000L

        /** Dipakai MainActivity buat cek status & trigger scan manual. */
        @Volatile var instance: DanaNotificationService? = null

        private val DANA_KEYWORDS = listOf(
            "pembayaran masuk",
            "diterima dana bisnis",
            "pembayaran diterima"
        )
        private val GOPAY_KEYWORDS = listOf(
            "pembayaran qris statis diterima",
            "pembayaran diterima",
            "pembayaran masuk",
            "qris diterima"
        )

        // "Rp55.000", "Rp 55.000", "Rp55.000,00", "Rp1.500.000"
        private val AMOUNT_REGEX = Regex("""(?i)Rp\.?\s*(\d[\d.,]*)""")

        fun isDana(pkg: String) = pkg == "id.dana" || pkg.startsWith("id.dana.")

        fun appNameFor(pkg: String): String? = when {
            isDana(pkg) -> "DANA Bisnis"
            pkg.contains("gopay") || pkg.contains("gojek") -> "GoPay Merchant"
            else -> null
        }

        /**
         * Normalisasi nominal jadi angka bulat tanpa pemisah.
         *  "55.000" -> "55000", "55.000,00" -> "55000", "1,500,000" -> "1500000"
         * (Versi lama: ",00" ikut kehapus jadi angka → 55.000,00 kebaca 5.500.000!)
         */
        fun normalizeAmount(raw: String): String? {
            val s = raw.trimEnd('.', ',')
            if (s.isEmpty()) return null
            val lastComma = s.lastIndexOf(',')
            val lastDot = s.lastIndexOf('.')
            val decIdx = when {
                lastComma > lastDot && s.length - lastComma - 1 in 1..2 -> lastComma
                lastDot > lastComma && s.length - lastDot - 1 in 1..2 -> lastDot
                else -> -1
            }
            val intPart = if (decIdx >= 0) s.substring(0, decIdx) else s
            val digits = intPart.filter { it.isDigit() }.trimStart('0')
            return if (digits.isEmpty()) null else digits
        }

        /** Gabung semua field teks notif + normalisasi spasi aneh (NBSP dll). */
        fun extractText(n: Notification): String {
            val e = n.extras
            val parts = mutableListOf<CharSequence?>()
            // getCharSequence, BUKAN getString: judul berupa SpannableString bikin getString() return null
            parts += e.getCharSequence(Notification.EXTRA_TITLE)
            parts += e.getCharSequence(Notification.EXTRA_TEXT)
            parts += e.getCharSequence(Notification.EXTRA_BIG_TEXT)
            parts += e.getCharSequence(Notification.EXTRA_SUB_TEXT)
            parts += e.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)
            e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { parts += it }
            parts += n.tickerText
            return parts.mapNotNull { it?.toString() }
                .distinct()
                .joinToString(" ")
                .replace(Regex("[\\u00A0\\u202F\\u2007\\u2009]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }

    private var pool: ExecutorService = Executors.newCachedThreadPool()

    // =============================================
    // LIFECYCLE
    // =============================================
    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        if (pool.isShutdown) pool = Executors.newCachedThreadPool()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Store.log(this, "🟢 Listener terhubung ke sistem")
        startForegroundNotif()
        // Catch-up: ambil notif yang muncul pas listener lagi putus
        pool.execute { scanActive() }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
        Store.log(this, "⚠️ Listener disconnect — mencoba reconnect...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                requestRebind(ComponentName(this, DanaNotificationService::class.java))
            } catch (e: Exception) {
                Store.log(this, "❌ requestRebind gagal: ${e.message}")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        pool.shutdown()
        Store.log(this, "⚠️ Service mati — Android akan reconnect otomatis")
    }

    private fun startForegroundNotif() {
        // Jangan sampai gagal start foreground bikin listener ikut crash
        try {
            Notifier.ensureChannels(this)
            val pendingIntent = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, Notifier.FOREGROUND_CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            val notification = builder
                .setContentTitle("💰 DANA Listener Aktif")
                .setContentText("Memantau pembayaran masuk...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
            startForeground(FOREGROUND_NOTIF_ID, notification)
            Log.d(TAG, "Foreground service started")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground gagal: ${e.message}")
            Store.log(this, "⚠️ Foreground notif gagal: ${e.message}")
        }
    }

    // =============================================
    // LISTENER NOTIFIKASI
    // =============================================
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            handle(sbn, fromScan = false)
        } catch (e: Exception) {
            // Exception di sini tidak boleh lolos — bisa bikin listener mati diam-diam
            Log.e(TAG, "handle error: ${e.message}", e)
            Store.log(this, "❌ Error proses notif: ${e.message}")
        }
    }

    /**
     * Scan notif yang masih ada di status bar. Notif DANA/GoPay yang cocok & masih baru
     * dikirim otomatis; sisanya dicatat ke riwayat supaya bisa dikirim manual.
     * Return jumlah notif yang diantrikan.
     */
    fun scanActive(): Int {
        val active = try { activeNotifications } catch (e: Exception) { null } ?: return 0
        var queued = 0
        for (sbn in active) {
            try {
                if (handle(sbn, fromScan = true)) queued++
            } catch (e: Exception) {
                Store.log(this, "❌ Error scan notif: ${e.message}")
            }
        }
        return queued
    }

    /** @return true kalau notif diantrikan untuk dikirim ke webhook. */
    private fun handle(sbn: StatusBarNotification, fromScan: Boolean): Boolean {
        val pkg = sbn.packageName ?: return false
        val appName = appNameFor(pkg) ?: return false

        val text = extractText(sbn.notification)
        if (text.isEmpty()) return false

        val amount = AMOUNT_REGEX.find(text)?.groupValues?.get(1)?.let { normalizeAmount(it) }
        // Notif tanpa nominal (promo, dll) tidak kita simpan
        if (amount == null) {
            Log.d(TAG, "Notif $appName tanpa nominal: $text")
            return false
        }

        val key = "$pkg|${sbn.postTime}|${text.hashCode()}"
        val now = System.currentTimeMillis()
        val existing = Store.get(this, key)
        if (existing != null) {
            if (existing.state in Store.DONE_STATES) return false
            if (existing.state == "pending" && now - existing.upd < 120_000) return false
        }

        Log.d(TAG, "Notif dari $appName: $text")

        val keywords = if (isDana(pkg)) DANA_KEYWORDS else GOPAY_KEYWORDS
        val lower = text.lowercase()
        val matched = keywords.any { lower.contains(it) }

        if (!matched) {
            if (existing == null) {
                Store.upsert(this, Store.Rec(key, sbn.postTime, pkg, appName, text, amount, "ignored", now))
                Store.log(this, "⚠️ Notif $appName Rp$amount TIDAK cocok keyword — \"${text.take(70)}\". Kalau ini transaksi masuk: 🔍 Cek Notif Manual")
            }
            return false
        }

        if (fromScan && now - sbn.postTime > CATCHUP_WINDOW_MS) {
            if (existing == null) {
                Store.upsert(this, Store.Rec(key, sbn.postTime, pkg, appName, text, amount, "old", now))
            }
            return false
        }

        val rec = Store.Rec(key, sbn.postTime, pkg, appName, text, amount, "pending", now)
        Store.upsert(this, rec)
        Store.log(this, "💰 [$appName] Rp$amount detected${if (fromScan) " (catch-up)" else ""} — posting ke server...")

        val ctx = applicationContext
        pool.execute { WebhookSender.deliver(ctx, rec, false) }
        return true
    }
}
