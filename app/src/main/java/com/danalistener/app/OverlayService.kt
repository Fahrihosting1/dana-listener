package com.danalistener.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

class OverlayService : Service() {

    companion object {
        private const val OVERLAY_NOTIF_ID    = 2001
        private const val OVERLAY_CHANNEL_ID  = "dana_overlay"
    }

    private var overlayView: TextView? = null
    private lateinit var windowManager: WindowManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundNotif()
        showOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY   // restart otomatis kalau system kill
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    // ── Foreground notif agar service tidak di-kill ───────────────────────
    private fun startForegroundNotif() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                OVERLAY_CHANNEL_ID,
                "DANA Overlay",
                NotificationManager.IMPORTANCE_MIN   // paling senyap, gak bunyi
            ).apply {
                setShowBadge(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(ch)
        }

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, OVERLAY_CHANNEL_ID)
                .setContentTitle("💰 DANA Listener — Aktif")
                .setContentText("Memantau pembayaran masuk")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("💰 DANA Listener — Aktif")
                .setContentText("Memantau pembayaran masuk")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .build()
        }

        startForeground(OVERLAY_NOTIF_ID, notif)
    }

    // ── Overlay chip di pojok atas layar ─────────────────────────────────
    private fun showOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE       // gak ambil input
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END   // pojok kanan atas
            x = 16   // margin kanan
            y = 80   // margin atas (bawah status bar)
        }

        val tv = TextView(this).apply {
            text = "💰 DANA"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC1A7A3C.toInt())   // hijau gelap transparan
            setPadding(18, 8, 18, 8)
            // Tap chip → buka MainActivity
            setOnClickListener {
                val i = Intent(this@OverlayService, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(i)
            }
        }

        overlayView = tv

        try {
            windowManager.addView(tv, params)
        } catch (e: Exception) {
            // Izin belum diberikan — service tetap jalan, overlay skip
        }
    }

    private fun removeOverlay() {
        try {
            overlayView?.let { windowManager.removeView(it) }
        } catch (_: Exception) { }
        overlayView = null
    }
}
