package com.danalistener.app

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            refreshStatus()
            uiHandler.postDelayed(this, 3000)
        }
    }

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
        val btnManual      = findViewById<Button>(R.id.btnManual)
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

        // ── Cek notif manual ──────────────────────────────────────────────
        btnManual.setOnClickListener { showManualDialog() }

        // Android 13+: tanpa izin ini notif "Pembayaran Berhasil/Unmatched" gak akan muncul
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
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
        ensureListenerBound()
        refreshStatus()
        uiHandler.postDelayed(refreshTask, 3000)

        // Kalau user baru balik dari halaman izin overlay, langsung start service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            Settings.canDrawOverlays(this)) {
            startOverlayService()
        }
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(refreshTask)
    }

    private fun refreshStatus() {
        updateStatus(findViewById(R.id.tvStatus), findViewById(R.id.tvLog))
    }

    /** Kalau izin notif sudah diberikan tapi listener belum nyambung → paksa rebind. */
    private fun ensureListenerBound() {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
        if (!enabled) return
        val svc = DanaNotificationService.instance
        if (svc == null) {
            BootReceiver.rebindListener(this)
        } else {
            Thread { svc.scanActive() }.start()
        }
    }

    // ── Cek Notif Manual ─────────────────────────────────────────────────
    private fun fmtRp(amount: String): String {
        val n = amount.toLongOrNull() ?: return amount
        return "Rp" + String.format(Locale.US, "%,d", n).replace(',', '.')
    }

    private fun fmtTime(t: Long): String =
        SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date(t))

    private fun stateLabel(state: String): String = when (state) {
        "sent" -> "✅ terkirim"
        "unmatched" -> "⚠️ unmatched"
        "dup" -> "🔁 duplikat"
        "failed" -> "❌ gagal kirim"
        "pending" -> "⏳ diproses"
        "ignored" -> "🚫 tdk cocok keyword"
        "old" -> "🕓 lama, belum dikirim"
        else -> state
    }

    private fun showManualDialog() {
        val items = Store.list(this)
        val labels = mutableListOf("✏️ Input nominal manual", "🔄 Scan notif yang masih di status bar")
        items.forEach {
            labels += "${fmtTime(it.time)} • ${fmtRp(it.amount)} • ${stateLabel(it.state)}\n${it.text.take(80)}"
        }
        AlertDialog.Builder(this)
            .setTitle("Cek Notif Manual")
            .setItems(labels.toTypedArray()) { _, which ->
                when (which) {
                    0 -> showAmountInputDialog()
                    1 -> scanNow()
                    else -> confirmSend(items[which - 2])
                }
            }
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun scanNow() {
        val svc = DanaNotificationService.instance
        if (svc == null) {
            Toast.makeText(this, "Listener belum terhubung. Cek izin notifikasi, lalu buka ulang app.", Toast.LENGTH_LONG).show()
            BootReceiver.rebindListener(this)
            return
        }
        Thread {
            val n = svc.scanActive()
            runOnUiThread {
                Toast.makeText(this, "Scan selesai — $n notif baru dikirim otomatis", Toast.LENGTH_SHORT).show()
                refreshStatus()
                showManualDialog()
            }
        }.start()
    }

    private fun showAmountInputDialog() {
        val et = EditText(this).apply {
            hint = "Nominal, contoh: 55000"
            inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Input nominal manual")
            .setMessage("Dipakai kalau transaksi masuk tapi notifnya sama sekali gak ketangkap app.")
            .setView(et)
            .setPositiveButton("Lanjut") { _, _ ->
                val amount = DanaNotificationService.normalizeAmount(et.text.toString().trim())
                if (amount == null) {
                    Toast.makeText(this, "Nominal tidak valid", Toast.LENGTH_SHORT).show()
                } else {
                    val now = System.currentTimeMillis()
                    confirmSend(
                        Store.Rec(
                            key = "manual|$now|$amount", time = now, pkg = "manual", app = "DANA Bisnis",
                            text = "MANUAL INPUT ${fmtRp(amount)}", amount = amount, state = "pending", upd = now
                        )
                    )
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun confirmSend(rec: Store.Rec) {
        val warn = if (rec.state in Store.DONE_STATES)
            "\n\n⚠️ Status sekarang: ${stateLabel(rec.state)}. Kirim ulang bisa dianggap duplikat oleh server."
        else ""
        AlertDialog.Builder(this)
            .setTitle("Kirim ke server?")
            .setMessage("${fmtRp(rec.amount)} [${rec.app}]\n${rec.text.take(150)}$warn")
            .setPositiveButton("Kirim") { _, _ ->
                Toast.makeText(this, "Mengirim ${fmtRp(rec.amount)}...", Toast.LENGTH_SHORT).show()
                val ctx = applicationContext
                Thread {
                    val state = WebhookSender.deliver(ctx, rec, true)
                    runOnUiThread {
                        Toast.makeText(this, "Hasil: ${stateLabel(state)}", Toast.LENGTH_LONG).show()
                        refreshStatus()
                    }
                }.start()
            }
            .setNegativeButton("Batal", null)
            .show()
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
            val connected = DanaNotificationService.instance != null
            statusText.append(if (notifEnabled) "🟢 Izin notifikasi aktif\n" else "🔴 Izin Notifikasi BELUM diberikan!\n")
            statusText.append(if (connected) "🟢 Listener TERHUBUNG — dengerin notif DANA\n" else "🔴 Listener BELUM terhubung — buka ulang app / matikan-nyalakan izin notifikasi\n")
            statusText.append(if (batteryOptimized) "⚠️ Battery optimization aktif — tap tombol battery\n" else "🟢 Battery optimization nonaktif\n")
            statusText.append(if (overlayGranted) "🟢 Izin overlay aktif — popup terkunci" else "⚠️ Izin overlay belum diberikan — tap tombol overlay")

            tvStatus.text = statusText.toString()
            tvStatus.setTextColor(if (notifEnabled && connected) 0xFF00C853.toInt() else 0xFFD50000.toInt())

            val prefs = getSharedPreferences("config", MODE_PRIVATE)
            val url   = prefs.getString("webhook_url", "-")
            val logs  = prefs.getString("logs", "Belum ada aktivitas.")
            tvLog.text = "Server: $url\n\n--- Log Terakhir ---\n$logs"

        } catch (e: Exception) {
            tvStatus.text = "Error: ${e.message}"
        }
    }
}
