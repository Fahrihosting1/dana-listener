package com.danalistener.app

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val prefs = getSharedPreferences("config", MODE_PRIVATE)

        val etUrl          = findViewById<EditText>(R.id.etUrl)
        val etSecret       = findViewById<EditText>(R.id.etSecret)
        val btnSave        = findViewById<Button>(R.id.btnSave)
        val btnPermission  = findViewById<Button>(R.id.btnPermission)
        val btnBattery     = findViewById<Button>(R.id.btnBattery)
        val btnOverlay     = findViewById<Button>(R.id.btnOverlay)
        val btnMiui        = findViewById<Button>(R.id.btnMiui)
        val btnOppo        = findViewById<Button>(R.id.btnOppo)
        val btnVivo        = findViewById<Button>(R.id.btnVivo)
        val tvStatus       = findViewById<TextView>(R.id.tvStatus)
        val tvLog          = findViewById<TextView>(R.id.tvLog)

        etUrl.setText(prefs.getString("webhook_url", ""))
        etSecret.setText(prefs.getString("webhook_secret", ""))

        // ── Tampilkan button sesuai merek HP ──────────────────────────────
        val brand = Build.MANUFACTURER.lowercase()
        btnMiui.visibility = if (brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco")) View.VISIBLE else View.GONE
        btnOppo.visibility = if (brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus")) View.VISIBLE else View.GONE
        btnVivo.visibility = if (brand.contains("vivo")) View.VISIBLE else View.GONE

        // ── Simpan config ─────────────────────────────────────────────────
        btnSave.setOnClickListener {
            prefs.edit()
                .putString("webhook_url", etUrl.text.toString().trim())
                .putString("webhook_secret", etSecret.text.toString().trim())
                .apply()
            Toast.makeText(this, "✅ Config tersimpan!", Toast.LENGTH_SHORT).show()
            updateStatus(tvStatus, tvLog)
        }

        // ── Izin notifikasi ───────────────────────────────────────────────
        btnPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // ── Battery optimization (semua HP) ──────────────────────────────
        btnBattery.setOnClickListener {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } else {
                    startActivity(Intent(Settings.ACTION_SETTINGS))
                }
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (e2: Exception) {
                    Toast.makeText(this, "Buka Settings → Battery → Optimize manually", Toast.LENGTH_LONG).show()
                }
            }
        }

        // ── Izin tampil di atas layar (SYSTEM_ALERT_WINDOW) ──────────────
        btnOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    // Langsung buka halaman izin untuk app ini
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    } catch (e: Exception) {
                        // Fallback: buka halaman overlay general
                        try {
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                        } catch (e2: Exception) {
                            Toast.makeText(
                                this,
                                "Buka Settings → Apps → DANA Listener → Tampilkan di atas app lain → Aktifkan",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } else {
                    Toast.makeText(this, "✅ Izin overlay sudah aktif!", Toast.LENGTH_SHORT).show()
                    // Start overlay service setelah izin sudah ada
                    startOverlayService()
                }
            } else {
                // Android < 6: tidak butuh izin manual
                startOverlayService()
            }
        }

        // ── MIUI Autostart (Xiaomi / Redmi / POCO) ───────────────────────
        btnMiui.setOnClickListener {
            val opened = tryStartActivity(
                Intent().apply {
                    component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"
                    )
                },
                Intent().apply {
                    component = ComponentName(
                        "com.miui.securitycenter",
                        "com.miui.permcenter.MainAcitivity"
                    )
                },
                Intent().apply {
                    setPackage("com.miui.securitycenter")
                    action = Intent.ACTION_MAIN
                }
            )
            if (!opened) {
                Toast.makeText(
                    this,
                    "Buka Keamanan (Security) → Izin → Mulai Otomatis → aktifkan DANA Listener",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // ── Oppo / Realme / OnePlus battery whitelist ────────────────────
        btnOppo.setOnClickListener {
            val opened = tryStartActivity(
                Intent().apply {
                    component = ComponentName(
                        "com.coloros.safecenter",
                        "com.coloros.privacypermissionsentry.PermissionTopActivity"
                    )
                },
                Intent().apply {
                    component = ComponentName(
                        "com.oppo.safe",
                        "com.oppo.safe.permission.startup.StartupAppListActivity"
                    )
                },
                Intent().apply {
                    component = ComponentName(
                        "com.coloros.oppoguardelf",
                        "com.coloros.powermanager.powersaving.PowerConsumptionActivity"
                    )
                },
                Intent().apply {
                    setPackage("com.coloros.safecenter")
                    action = Intent.ACTION_MAIN
                }
            )
            if (!opened) {
                Toast.makeText(
                    this,
                    "Buka Manajer Telepon → Startup → aktifkan DANA Listener\natau Settings → Battery → App Battery Management",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        // ── Vivo battery whitelist ────────────────────────────────────────
        btnVivo.setOnClickListener {
            val opened = tryStartActivity(
                Intent().apply {
                    component = ComponentName(
                        "com.iqoo.secure",
                        "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                    )
                },
                Intent().apply {
                    component = ComponentName(
                        "com.vivo.permissionmanager",
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                    )
                },
                Intent().apply {
                    setPackage("com.iqoo.secure")
                    action = Intent.ACTION_MAIN
                }
            )
            if (!opened) {
                Toast.makeText(
                    this,
                    "Buka iManager → Startup → aktifkan DANA Listener\natau Settings → Battery → High Background Power Consumption",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        updateStatus(tvStatus, tvLog)

        // Auto-start overlay kalau izin sudah ada
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(this)) {
            startOverlayService()
        }
    }

    override fun onResume() {
        super.onResume()
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val tvLog    = findViewById<TextView>(R.id.tvLog)
        updateStatus(tvStatus, tvLog)

        // Kalau user baru balik dari halaman izin overlay, langsung start service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            Settings.canDrawOverlays(this)) {
            startOverlayService()
        }
    }

    private fun startOverlayService() {
        try {
            val intent = Intent(this, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            // Overlay service gagal start — tidak fatal, notif foreground tetap jalan
        }
    }

    private fun tryStartActivity(vararg intents: Intent): Boolean {
        for (intent in intents) {
            try {
                startActivity(intent)
                return true
            } catch (_: Exception) { }
        }
        return false
    }

    private fun isBatteryOptimizationActive(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName).not()
        } catch (ex: Exception) {
            false
        }
    }

    private fun isOverlayGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            Settings.canDrawOverlays(this)
        else true
    }

    private fun updateStatus(tvStatus: TextView, tvLog: TextView) {
        try {
            val notifEnabled   = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
            val batteryOptimized = isBatteryOptimizationActive()
            val overlayGranted = isOverlayGranted()

            val statusText = StringBuilder()
            statusText.append(if (notifEnabled) "🟢 Service AKTIF — Dengerin notif DANA\n" else "🔴 Izin Notifikasi BELUM diberikan!\n")
            statusText.append(if (batteryOptimized) "⚠️ Battery optimization aktif — tap tombol battery\n" else "🟢 Battery optimization nonaktif\n")
            statusText.append(if (overlayGranted) "🟢 Izin overlay aktif — popup terkunci" else "⚠️ Izin overlay belum diberikan — tap tombol overlay")

            tvStatus.text = statusText.toString()
            tvStatus.setTextColor(if (notifEnabled) 0xFF00C853.toInt() else 0xFFD50000.toInt())

            val prefs = getSharedPreferences("config", MODE_PRIVATE)
            val url   = prefs.getString("webhook_url", "-")
            val logs  = prefs.getString("logs", "Belum ada aktivitas.")
            tvLog.text = "Server: $url\n\n--- Log Terakhir ---\n$logs"

        } catch (e: Exception) {
            tvStatus.text = "Error: ${e.message}"
        }
    }
}
