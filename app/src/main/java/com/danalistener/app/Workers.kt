package com.danalistener.app

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.Data
import java.util.concurrent.TimeUnit

/**
 * Kirim ulang webhook yang gagal. Dijalankan WorkManager → tahan proses di-kill OEM,
 * nunggu internet beneran ada, dan jalan di maintenance window Doze (layar mati).
 */
class DeliveryWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    companion object {
        private const val MAX_ATTEMPTS = 12   // backoff 30s → ... ± beberapa jam

        fun enqueue(ctx: Context, key: String) {
            val req = OneTimeWorkRequest.Builder(DeliveryWorker::class.java)
                .setInputData(Data.Builder().putString("key", key).build())
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniqueWork("deliver-$key", ExistingWorkPolicy.KEEP, req)
        }
    }

    override fun doWork(): Result {
        val key = inputData.getString("key") ?: return Result.success()
        val rec = Store.get(applicationContext, key) ?: return Result.success()
        if (rec.state in Store.DONE_STATES) return Result.success()

        return when (WebhookSender.deliver(applicationContext, rec, manual = false, fromWorker = true)) {
            "queued" -> {
                if (runAttemptCount + 1 >= MAX_ATTEMPTS) {
                    WebhookSender.giveUp(applicationContext, rec)
                    Result.failure()
                } else Result.retry()
            }
            else -> Result.success()   // sent / unmatched / dup / failed (non-retryable)
        }
    }
}

/**
 * Penjaga tiap ±15 menit (hemat baterai, gantikan polling/overlay):
 *  - listener putus → rebind
 *  - scan notif yang masih di status bar (catch-up)
 *  - antrikan lagi transaksi yang gagal / nyangkut
 */
class WatchdogWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequest.Builder(WatchdogWorker::class.java, 15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(ctx.applicationContext)
                .enqueueUniquePeriodicWork("watchdog", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }

    override fun doWork(): Result {
        val c = applicationContext
        try {
            val enabled = NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)
            if (enabled) {
                val svc = DanaNotificationService.instance
                if (svc == null) {
                    Store.log(c, "🐕 Watchdog: listener putus — rebind")
                    BootReceiver.rebindListener(c)
                } else {
                    svc.scanActive()
                }
            }
            val now = System.currentTimeMillis()
            Store.list(c).filter {
                it.pkg != "manual" &&
                    now - it.time <= DanaNotificationService.CATCHUP_WINDOW_MS &&
                    (it.state == "failed" ||
                        (it.state == "pending" && now - it.upd > 120_000) ||
                        (it.state == "queued" && now - it.upd > 15 * 60_000L))
            }.forEach { DeliveryWorker.enqueue(c, it.key) }
        } catch (e: Exception) {
            Store.log(c, "❌ Watchdog error: ${e.message}")
        }
        return Result.success()
    }
}
