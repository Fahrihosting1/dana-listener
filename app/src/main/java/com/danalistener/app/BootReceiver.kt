package com.danalistener.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "RESTART_SERVICE" -> {
                Log.d("DanaListener", "Auto-start/restart service: ${intent.action}")
                // NotificationListenerService restart otomatis oleh Android
                // tapi kita trigger manual juga buat jaga-jaga
                try {
                    val serviceIntent = Intent(context, DanaNotificationService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.e("DanaListener", "Gagal restart service: ${e.message}")
                }
            }
        }
    }
}
