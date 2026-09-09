package com.paylisten.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class ScreenOnService : Service() {

    companion object {
        const val CHANNEL_ID = "paylisten_screenon"
        const val ACTION_STOP = "com.paylisten.app.STOP_SCREEN"
        const val TAG = "PayListenHeartbeat"
        private var wakeLock: PowerManager.WakeLock? = null
        private var overlay: View? = null
        private var wm: WindowManager? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var heartbeatRunning = false

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            sendHeartbeat()
            if (heartbeatRunning) handler.postDelayed(this, 10 * 60 * 1000L) // 10 menit
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForeground()
        addOverlay()
        acquireWakeLock()
        startHeartbeat()
    }

    private fun startForeground() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Pay Listen Screen On", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Menjaga layar tetap menyala" }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)

        val stopIntent = Intent(this, ScreenOnService::class.java).apply { action = ACTION_STOP }
        val piStop = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Pay Listen — Layar Aktif")
            .setContentText("Layar dijaga menyala untuk menangkap notifikasi pembayaran")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Matikan", piStop)
            .setOngoing(true)
            .build()
        startForeground(2, notif)
    }

    // ===== HEARTBEAT: kirim status ke STB tiap 10 menit =====
    private fun startHeartbeat() {
        if (heartbeatRunning) return
        heartbeatRunning = true
        handler.post(heartbeatRunnable)
    }

    private fun sendHeartbeat() {
        Thread {
            try {
                val url = getHeartbeatUrl()
                // URL kosong = server belum dikonfigurasi — skip heartbeat (aman, tidak kirim ke mana pun)
                if (url.isBlank()) {
                    Log.d(TAG, "Heartbeat dilewati: URL webhook kosong")
                    return@Thread
                }
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                val body = """{"listener":${isNotificationAccessGranted()},"battery":${isBatteryIgnored()},"overlay":${isOverlayGranted()},"screen":true,"batt":${getBatteryLevel()}}"""
                OutputStreamWriter(conn.outputStream).use { it.write(body) }
                val code = conn.responseCode
                Log.d(TAG, "Heartbeat terkirim: $code")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Heartbeat gagal: ${e.message}")
            }
        }.start()
    }

    private fun getHeartbeatUrl(): String {
        // Ambil webhook URL, ganti path jadi /api/pay/heartbeat
        val prefs = getSharedPreferences(PayListenListener.PREFS, Context.MODE_PRIVATE)
        val webhook = prefs.getString(PayListenListener.KEY_URL, PayListenListener.DEFAULT_URL)
            ?: PayListenListener.DEFAULT_URL
        // URL kosong = kembalikan kosong (heartbeat akan dilewati)
        if (webhook.isBlank()) return ""
        val base = webhook.substringBeforeLast("/api/")
        return "$base/api/pay/heartbeat"
    }

    private fun getBatteryLevel(): Int {
        return try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (e: Exception) { -1 }
    }

    private fun isNotificationAccessGranted(): Boolean {
        val cn = ComponentName(this, PayListenListener::class.java)
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(cn.flattenToString())
    }

    private fun isBatteryIgnored(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun isOverlayGranted(): Boolean {
        return Settings.canDrawOverlays(this)
    }

    // Overlay 1x1 pixel dengan FLAG_KEEP_SCREEN_ON — cara paling bandel jaga layar
    private fun addOverlay() {
        if (overlay != null) return
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val v = View(this)
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 0
        try {
            wm?.addView(v, params)
            overlay = v
        } catch (e: Exception) {
            // overlay permission belum ada — fallback ke wake lock saja
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "PayListen::ScreenOn"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        heartbeatRunning = false
        handler.removeCallbacks(heartbeatRunnable)
        removeOverlay()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun removeOverlay() {
        try {
            overlay?.let { wm?.removeView(it) }
        } catch (e: Exception) {}
        overlay = null
        wm = null
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
