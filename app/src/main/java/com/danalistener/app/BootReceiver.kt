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
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // NotificationListenerService dimanage Android secara otomatis.
                // JANGAN startForegroundService() di sini — bisa bikin instance
                // conflict dan listener jadi tidak stabil / berhenti detect.
                // Android akan rebind service sendiri setelah boot/update.
                Log.d("DanaListener", "Boot/update detected: ${intent.action} — listener akan direbind Android")
            }
            // "RESTART_SERVICE" case DIHAPUS — ini yang dulu bikin detection mati
        }
    }
}
