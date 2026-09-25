package com.danalistener.app

import android.content.Intent
import android.os.Bundle
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
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val tvLog = findViewById<TextView>(R.id.tvLog)

        // Load saved config
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

        updateStatus(tvStatus, tvLog)
    }

    override fun onResume() {
        super.onResume()
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val tvLog = findViewById<TextView>(R.id.tvLog)
        updateStatus(tvStatus, tvLog)
    }

    private fun updateStatus(tvStatus: TextView, tvLog: TextView) {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(this)
            .contains(packageName)

        if (enabled) {
            tvStatus.text = "🟢 Service AKTIF — Dengerin notif DANA"
            tvStatus.setTextColor(0xFF00C853.toInt())
        } else {
            tvStatus.text = "🔴 Izin belum diberikan — Tap 'Kasih Izin' dulu"
            tvStatus.setTextColor(0xFFD50000.toInt())
        }

        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        val url = prefs.getString("webhook_url", "-")
        val logs = prefs.getString("logs", "Belum ada aktivitas.")
        tvLog.text = "Server: $url\n\n--- Log Terakhir ---\n$logs"
    }
}
