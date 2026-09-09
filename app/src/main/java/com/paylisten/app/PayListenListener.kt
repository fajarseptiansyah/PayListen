package com.paylisten.app

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class PayListenListener : NotificationListenerService() {

    companion object {
        const val TAG = "PayListen"
        const val PREFS = "paylisten_prefs"
        const val KEY_URL = "webhook_url"
        const val KEY_KEYWORDS = "filter_keywords"
        const val DEFAULT_URL = "" // kosong: admin mengisi manual per server — tidak ada server utama hardcoded
        const val DEFAULT_KEYWORDS = "pembayaran,diterima,di terima"
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Listener connected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            val extras = sbn.notification?.extras ?: return
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val full = "$title $text"

            // Hanya proses notifikasi pembayaran diterima (mengandung "pembayaran" + "Rp")
            if (!isPaymentNotification(full)) return

            val amount = extractAmount(full) ?: return
            Log.d(TAG, "Pembayaran terdeteksi: Rp $amount")

            sendWebhook(amount)

            // Bersihkan notifikasi setelah diproses
            cancelNotification(sbn.key)
        } catch (e: Exception) {
            Log.e(TAG, "onNotificationPosted error", e)
        }
    }

    private fun isPaymentNotification(full: String): Boolean {
        val f = full.lowercase()
        if (!f.contains("rp")) return false
        val keywords = getKeywords()
        if (keywords.isEmpty()) return true // kalau keyword dikosongkan, semua notif "Rp" diproses
        return keywords.any { f.contains(it.lowercase()) }
    }

    private fun getKeywords(): List<String> {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_KEYWORDS, DEFAULT_KEYWORDS) ?: DEFAULT_KEYWORDS
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun extractAmount(text: String): Long? {
        // Cari "Rp" diikuti angka (bisa pakai pemisah ribuan titik/koma)
        val regex = Regex("""[Rr][Pp]\s*([0-9][0-9.,]*)""")
        val m = regex.find(text) ?: return null
        val raw = m.groupValues[1]
        // Ambil digit saja — buang titik/koma pemisah ribuan
        val digits = raw.filter { it.isDigit() }
        return digits.toLongOrNull()
    }

    private fun sendWebhook(amount: Long) {
        Thread {
            var sukses = false
            var pesan = "Rp $amount — gagal (tidak ada respon server)"
            try {
                val url = getWebhookUrl()
                // URL kosong = server belum dikonfigurasi — jangan kirim ke mana pun (aman)
                if (url.isBlank()) {
                    pesan = "Rp $amount — dilewati: URL webhook kosong"
                } else {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    val body = """{"nominal":"$amount","message":""}"""
                    OutputStreamWriter(conn.outputStream).use { it.write(body) }
                    val code = conn.responseCode
                    // baca respon body (kalau ada)
                    val resp = conn.inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                    Log.d(TAG, "Webhook response: $code $resp")
                    if (code in 200..299) {
                        sukses = true
                        pesan = "Rp $amount — terkirim (server $code)"
                    } else {
                        pesan = "Rp $amount — server balas $code"
                    }
                    conn.disconnect()
                }
            } catch (e: Exception) {
                pesan = "Rp $amount — gagal: ${e.message ?: "jaringan"} "
                Log.e(TAG, "sendWebhook error", e)
            } finally {
                LogStore.add(this, "WEBHOOK", pesan, sukses)
            }
        }.start()
    }

    private fun getWebhookUrl(): String {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
