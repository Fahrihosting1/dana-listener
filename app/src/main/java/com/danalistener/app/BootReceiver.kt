package com.danalistener.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Jangan startForegroundService() manual di sini (bikin instance conflict).
                // Cukup minta sistem rebind listener-nya.
                Log.d("DanaListener", "Boot/update: ${intent.action} — rebind listener")
                rebindListener(context)
            }
        }
    }

    companion object {
        /** Toggle component + requestRebind: cara standar maksa Android nge-bind ulang listener. */
        fun rebindListener(context: Context) {
            val cn = ComponentName(context, DanaNotificationService::class.java)
            try {
                val pm = context.packageManager
                pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                pm.setComponentEnabledSetting(cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            } catch (e: Exception) {
                Log.e("DanaListener", "toggle component gagal: ${e.message}")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    NotificationListenerService.requestRebind(cn)
                } catch (e: Exception) {
                    Log.e("DanaListener", "requestRebind gagal: ${e.message}")
                }
            }
        }
    }
}
