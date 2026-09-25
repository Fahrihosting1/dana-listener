package com.danalistener.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

// Auto-start service saat HP restart/reboot
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("DanaListener", "Boot completed — service akan aktif otomatis via NotificationListenerService")
            // NotificationListenerService otomatis di-restart Android setelah boot
            // kalau izin sudah diberikan sebelumnya
        }
    }
}
