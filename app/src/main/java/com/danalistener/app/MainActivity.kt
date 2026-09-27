package com.danalistener.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("config", MODE_PRIVATE)

        val etUrl = findViewById<EditText>(R.id.etUrl)
        val etSecret = findViewById<EditText>(R.id.etSecret)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnPermission = findViewById<Button>(R.id.btnPermission)
        val btnBattery = findViewById<Button>(R.id.btnBattery)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val tvLog = findViewById<TextView>(R.id.tvLog)

        etUrl.setText(prefs.getString("webhook_url", "http://38.47.92.24:5080/webhook/dana"))
        etSecret.setText(prefs.getString("webhook_secret", ""))

        btnSave.setOnClickListener {
            prefs.edit()
                .putString("webhook_url", etUrl.text.toString().trim())
                .putString("webhook_secret", etSecret.text.toString().trim())
                .apply()
            Toast.makeText(this, "✅ Config tersimpan!", Toast.LENGTH_SHORT).show()
            updateStatus(tvStatus, tvLog)
        }

        btnPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // Tombol disable battery optimization
        btnBattery.setOnClickListener {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                // Fallback ke battery settings umum
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }

        updateStatus(tvStatus, tvLog)
    }

    override fun onResume() {
        super.onResume()
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val tvLog = findViewById<TextView>(R.id.tvLog)
        updateStatus(tvStatus, tvLog)
    }

    private fun updateStatus(tvStatus: TextView, tvLog: TextView) {
        val notifEnabled = NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        val batteryOptimized = !pm.isIgnoringBatteryOptimizations(packageName)

        val statusText = StringBuilder()
        if (notifEnabled) {
            statusText.append("🟢 Service AKTIF — Dengerin notif DANA\n")
        } else {
            statusText.append("🔴 Izin Notifikasi BELUM diberikan!\n")
        }

        if (batteryOptimized) {
            statusText.append("⚠️ Battery optimization aktif — tap 'Nonaktifkan Battery Saver'")
        } else {
            statusText.append("🟢 Battery optimization nonaktif")
        }

        tvStatus.text = statusText.toString()
        tvStatus.setTextColor(if (notifEnabled) 0xFF00C853.toInt() else 0xFFD50000.toInt())

        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        val url = prefs.getString("webhook_url", "-")
        val logs = prefs.getString("logs", "Belum ada aktivitas.")
        tvLog.text = "Server: $url\n\n--- Log Terakhir ---\n$logs"
    }
}
