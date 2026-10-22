package com.danalistener.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Alarm berulang ±5 menit yang tembus Doze (setAndAllowWhileIdle, tanpa izin tambahan).
 * Tiap bunyi: pastikan listener masih ke-bind (kalau nggak → rebind) lalu scan notif aktif
 * supaya notif yang kelewat pas layar mati / proses dibekukan tetap terkirim.
 * WatchdogWorker (min 15 menit) terlalu jarang buat ini.
 */
class ScanAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val INTERVAL_MS = 5 * 60 * 1000L
        private const val REQ = 4242

        fun schedule(ctx: Context) {
            try {
                val app = ctx.applicationContext
                val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val pi = PendingIntent.getBroadcast(
                    app, REQ, Intent(app, ScanAlarmReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + INTERVAL_MS,
                    pi
                )
            } catch (e: Exception) {
                Store.log(ctx, "⚠️ Jadwal alarm gagal: ${e.message}")
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        // Jadwalkan berikutnya dulu — rantai alarm tidak boleh putus
        schedule(app)
        val pending = goAsync()
        Thread {
            try {
                val svc = DanaNotificationService.instance
                if (svc == null) {
                    val enabled = androidx.core.app.NotificationManagerCompat
                        .getEnabledListenerPackages(app).contains(app.packageName)
                    if (enabled) {
                        Store.log(app, "⏰ Alarm: listener putus — rebind")
                        BootReceiver.rebindListener(app)
                    }
                } else {
                    svc.scanActive()
                }
            } catch (e: Exception) {
                Store.log(app, "❌ Alarm error: ${e.message}")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
