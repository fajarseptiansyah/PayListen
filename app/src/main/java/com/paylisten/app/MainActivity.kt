package com.paylisten.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 (API 35): opt-out edge-to-edge agar status bar tidak menutupi header
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.setDecorFitsSystemWindows(true)
        }
        setContentView(R.layout.activity_main)

        val etUrl = findViewById<EditText>(R.id.etUrl)
        val etKeywords = findViewById<EditText>(R.id.etKeywords)
        val btnSave = findViewById<TextView>(R.id.btnSave)
        val btnTest = findViewById<TextView>(R.id.btnTest)
        val btnClearLog = findViewById<TextView>(R.id.btnClearLog)
        val btnLogPrev = findViewById<TextView>(R.id.btnLogPrev)
        val btnLogNext = findViewById<TextView>(R.id.btnLogNext)
        val headKonfig = findViewById<View>(R.id.headKonfig)
        val cardKonfig = findViewById<View>(R.id.cardKonfig)
        val boxNotif = findViewById<View>(R.id.boxNotif)
        val boxBattery = findViewById<View>(R.id.boxBattery)
        val boxOverlay = findViewById<View>(R.id.boxOverlay)
        val boxScreenOn = findViewById<View>(R.id.boxScreenOn)

        val prefs = getSharedPreferences(PayListenListener.PREFS, Context.MODE_PRIVATE)
        etUrl.setText(
            prefs.getString(PayListenListener.KEY_URL, null) ?: ""
        )
        etKeywords.setText(
            prefs.getString(PayListenListener.KEY_KEYWORDS, PayListenListener.DEFAULT_KEYWORDS)
        )

        // Klik kotak status = buka setting izin terkait
        boxNotif.setOnClickListener { openNotifSettings() }
        boxBattery.setOnClickListener { openBatterySettings() }
        boxOverlay.setOnClickListener { openOverlaySettings() }
        boxScreenOn.setOnClickListener { toggleScreenOn() }

        // SIMPAN (teks kecil di kartu konfigurasi)
        btnSave.setOnClickListener {
            prefs.edit()
                .putString(PayListenListener.KEY_URL, etUrl.text.toString().trim())
                .putString(PayListenListener.KEY_KEYWORDS, etKeywords.text.toString().trim())
                .apply()
            Toast.makeText(this, "Tersimpan", Toast.LENGTH_SHORT).show()
        }

        // TES KIRIM (ke /api/pay/test — tidak mengkredit saldo)
        btnTest.setOnClickListener { tesKirimKeServer() }

        // Bersihkan log
        btnClearLog.setOnClickListener {
            LogStore.clear(this)
            halamanLog = 0
            renderLog()
            Toast.makeText(this, "Log dibersihkan", Toast.LENGTH_SHORT).show()
        }

        // Pagination log
        btnLogPrev.setOnClickListener {
            if (halamanLog < ((LogStore.getAll(this).length() + 4) / 5) - 1) {
                halamanLog++; renderLog()
            }
        }
        btnLogNext.setOnClickListener {
            if (halamanLog > 0) { halamanLog--; renderLog() }
        }

        // Lipat/buka konfigurasi — kolaps saat kondisi normal agar ringkas
        cardKonfig.visibility = View.GONE
        headKonfig.setOnClickListener {
            val sedangTerbuka = cardKonfig.visibility == View.VISIBLE
            if (sedangTerbuka) {
                cardKonfig.visibility = View.GONE
                findViewById<TextView>(R.id.tvChevron).text = "▾"
            } else {
                cardKonfig.visibility = View.VISIBLE
                findViewById<TextView>(R.id.tvChevron).text = "▴"
            }
        }

        updateAllStatus()
        renderLog()
    }

    override fun onResume() {
        super.onResume()
        updateAllStatus()
        renderLog()
    }

    private fun openNotifSettings() {
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    private fun openBatterySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }

    private fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }

    private fun toggleScreenOn() {
        val running = isScreenOnRunning()
        val prefs = getSharedPreferences(PayListenListener.PREFS, Context.MODE_PRIVATE)
        if (running) {
            stopService(Intent(this, ScreenOnService::class.java))
            prefs.edit().putBoolean("screen_on", false).apply()
            Toast.makeText(this, "Layar selalu nyala: MATI", Toast.LENGTH_SHORT).show()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(Intent(this, ScreenOnService::class.java))
            } else {
                startService(Intent(this, ScreenOnService::class.java))
            }
            prefs.edit().putBoolean("screen_on", true).apply()
            Toast.makeText(this, "Layar selalu nyala: HIDUP", Toast.LENGTH_SHORT).show()
        }
        updateAllStatus()
    }

    // ===== TES KIRIM ke /api/pay/test (endpoint khusus — TANPA efek saldo) =====
    private fun tesKirimKeServer() {
        Toast.makeText(this, "Mengirim tes…", Toast.LENGTH_SHORT).show()
        Thread {
            var sukses = false
            var pesan = "gagal (tidak ada respon)"
            try {
                val webhook = getSharedPreferences(PayListenListener.PREFS, Context.MODE_PRIVATE)
                    .getString(PayListenListener.KEY_URL, null) ?: ""
                // URL kosong = belum dikonfigurasi — jangan coba kirim
                if (webhook.isBlank()) {
                    pesan = "isi URL webhook dulu, lalu SIMPAN"
                } else {
                    // UNIVERSAL: tes langsung ke URL yang diisi (tanpa memanipulasi path).
                    // Payload {"ts"} aman: server hanya mencatat, tidak memproses apa pun.
                    val url = webhook
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    val body = """{"ts":${System.currentTimeMillis()}}"""
                    java.io.OutputStreamWriter(conn.outputStream).use { it.write(body) }
                    val code = conn.responseCode
                    // Baca balasan untuk INFO (server utama vs server lain) — TIDAK menolak server lain
                    val balasan = try {
                        conn.inputStream.bufferedReader().readText()
                    } catch (e: Exception) { "" }
                    val json = try { org.json.JSONObject(balasan) } catch (e: Exception) { org.json.JSONObject() }
                    val serverUtama = json.optString("server", "") == "paylisten-server"
                    conn.disconnect()
                    sukses = code in 200..299
                    pesan = when {
                        code !in 200..299 -> "server balas kode $code"
                        serverUtama -> "server utama merespon OK ($code)"
                        else -> "server merespon OK ($code)"
                    }
                }
            } catch (e: Exception) {
                pesan = "gagal: ${e.message ?: "jaringan"}"
            } finally {
                LogStore.add(this, "TES", pesan, sukses)
                runOnUiThread {
                    renderLog()
                    updateAllStatus()
                    if (sukses) Toast.makeText(this, "✓ Server terhubung", Toast.LENGTH_SHORT).show()
                    else Toast.makeText(this, "✘ $pesan", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    // ===== Render LOG AKTIVITAS (terbaru di atas, 5 per halaman + pagination) =====
    private var halamanLog = 0

    private fun renderLog() {
        val tvLog = findViewById<TextView>(R.id.tvLog)
        val arr = LogStore.getAll(this)
        if (arr.length() == 0) {
            tvLog.text = "Belum ada aktivitas.\nLog otomatis terisi saat pembayaran terdeteksi atau tes kirim."
            findViewById<TextView>(R.id.btnLogPrev).visibility = View.GONE
            findViewById<TextView>(R.id.btnLogNext).visibility = View.GONE
            return
        }
        val perHalaman = 5
        val totalHalaman = ((arr.length() + perHalaman - 1) / perHalaman)
        if (halamanLog >= totalHalaman) halamanLog = totalHalaman - 1
        if (halamanLog < 0) halamanLog = 0

        // halaman 0 = terbaru di atas; halaman besar = log lama
        val awal = arr.length() - 1 - halamanLog * perHalaman
        val sb = StringBuilder()
        var tampil = 0
        var i = awal
        while (i >= 0 && tampil < perHalaman) {
            val it = arr.optJSONObject(i)
            if (it != null) {
                val tanda = if (it.optBoolean("ok")) "✓" else "✘"
                sb.append("${it.optString("waktu")}  $tanda ${it.optString("detail")}\n")
                tampil++
            }
            i--
        }
        tvLog.text = sb.toString().trim()

        // tombol pagination
        val btnPrev = findViewById<TextView>(R.id.btnLogPrev)
        val btnNext = findViewById<TextView>(R.id.btnLogNext)
        val tvPage = findViewById<TextView>(R.id.tvLogPage)
        if (totalHalaman > 1) {
            btnPrev.visibility = View.VISIBLE
            btnNext.visibility = View.VISIBLE
            tvPage.visibility = View.VISIBLE
            tvPage.text = "${halamanLog + 1}/${totalHalaman}"
            btnPrev.visibility = if (halamanLog < totalHalaman - 1) View.VISIBLE else View.INVISIBLE
            btnNext.visibility = if (halamanLog > 0) View.VISIBLE else View.INVISIBLE
        } else {
            btnPrev.visibility = View.GONE
            btnNext.visibility = View.GONE
            tvPage.visibility = View.GONE
        }
    }

    private fun updateAllStatus() {
        val notifGranted = isNotificationAccessGranted()
        val batteryIgnored = isBatteryIgnored()
        val overlayGranted = isOverlayGranted()
        val screenOn = isScreenOnRunning()
        val serverOk = serverTerakhir()

        // kotak status: teks + warna + TILE state-aware
        setTile(findViewById(R.id.boxNotif), findViewById(R.id.tvNotifStatus), notifGranted)
        setTile(findViewById(R.id.boxBattery), findViewById(R.id.tvBatteryStatus), batteryIgnored)
        setTile(findViewById(R.id.boxOverlay), findViewById(R.id.tvOverlayStatus), overlayGranted)

        // kotak SERVER (highlight cyan, bukan hijau/merah)
        val tvServer = findViewById<TextView>(R.id.tvServerStatus)
        val boxServer = tvServer.parent as View
        if (serverOk == null) {
            tvServer.text = "—"; tvServer.setTextColor(Color.parseColor("#8FA3BF"))
            boxServer.setBackgroundResource(R.drawable.bg_tile_mid)
        } else if (serverOk) {
            tvServer.text = "✓"; tvServer.setTextColor(Color.parseColor("#4FD8FF"))
            boxServer.setBackgroundResource(R.drawable.bg_tile_hl)
        } else {
            tvServer.text = "✘"; tvServer.setTextColor(Color.parseColor("#FF6B6B"))
            boxServer.setBackgroundResource(R.drawable.bg_tile_no)
        }

        // kotak LAYAR (ON cyan-highlight / OFF abu)
        val tvScreen = findViewById<TextView>(R.id.tvScreenStatus)
        val boxScreen = tvScreen.parent as View
        if (screenOn) {
            tvScreen.text = "ON"
            tvScreen.setTextColor(Color.parseColor("#4FD8FF"))
            boxScreen.setBackgroundResource(R.drawable.bg_tile_hl)
        } else {
            tvScreen.text = "OFF"
            tvScreen.setTextColor(Color.parseColor("#9AA7B8"))
            boxScreen.setBackgroundResource(R.drawable.bg_tile_off)
        }

        // counter "siap/total" di section STATUS
        val siap = listOf(notifGranted, batteryIgnored, overlayGranted, serverOk == true, screenOn).count { it }
        findViewById<TextView>(R.id.tvStatusCount).text = "$siap/5"

        // badge utama + dot
        val badge = findViewById<View>(R.id.badgeStatus)
        val dot = findViewById<View>(R.id.statusDot)
        val tvBesar = findViewById<TextView>(R.id.tvStatusBesar)
        val ready = notifGranted && batteryIgnored && overlayGranted && screenOn
        if (ready) {
            badge.setBackgroundResource(R.drawable.bg_badge_ok)
            dot.setBackgroundResource(R.drawable.bg_badge_ok)
            tvBesar.text = "AKTIF"
            tvBesar.setTextColor(getColor(R.color.pl_ok))
        } else {
            badge.setBackgroundResource(R.drawable.bg_badge_danger)
            dot.setBackgroundResource(R.drawable.bg_badge_danger)
            tvBesar.text = "BELUM SIAP"
            tvBesar.setTextColor(getColor(R.color.pl_danger))
        }
    }

    // hasil TES terakhir: null = belum pernah tes
    private fun serverTerakhir(): Boolean? {
        val arr = LogStore.getAll(this)
        for (i in arr.length() - 1 downTo 0) {
            val it = arr.optJSONObject(i) ?: continue
            if (it.optString("jenis") == "TES") return it.optBoolean("ok")
        }
        return null
    }

    /** Tile state-aware: hijau saat OK, merah saat belum */
    private fun setTile(box: View, tv: TextView, ok: Boolean) {
        if (ok) {
            tv.text = "✓"; tv.setTextColor(Color.parseColor("#3BE08B"))
            box.setBackgroundResource(R.drawable.bg_tile_ok)
        } else {
            tv.text = "✘"; tv.setTextColor(Color.parseColor("#FF6B6B"))
            box.setBackgroundResource(R.drawable.bg_tile_no)
        }
    }

    private fun isNotificationAccessGranted(): Boolean {
        val cn = ComponentName(this, PayListenListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }

    private fun isBatteryIgnored(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun isOverlayGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return Settings.canDrawOverlays(this)
    }

    private fun isScreenOnRunning(): Boolean {
        return getSharedPreferences(PayListenListener.PREFS, Context.MODE_PRIVATE)
            .getBoolean("screen_on", false)
    }
}
