package com.paylisten.app

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.widget.RemoteViews
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

        // Bypass: Abaikan jika ini adalah Grup Summary, karena isinya biasanya cuma "2 pesan baru" (tanpa detail Rp)
        val isGroupSummary = sbn.notification?.flags?.and(android.app.Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary) return

        try {
            val extras = sbn.notification?.extras ?: return
            
            // Kumpulkan SEMUA teks dari extras notifikasi (mengatasi BCA yg pakai EXTRA_BIG_TEXT atau format lain)
            val sb = StringBuilder()
            for (key in extras.keySet()) {
                val v = extras.get(key)
                if (v is CharSequence) sb.append(v.toString()).append(" ")
                else if (v is Array<*> && v.isArrayOf<CharSequence>()) {
                    v.forEach { if (it != null) sb.append(it.toString()).append(" ") }
                }
            }
            val ticker = sbn.notification?.tickerText?.toString() ?: ""
            sb.append(ticker)
            
            var full = sb.toString()
            
            // JIKA TEKS STANDAR KOSONG & PUNYA REMOTE VIEWS, BONGKAR DENGAN REFLECTION
            if (full.trim().isEmpty() && sbn.notification != null) {
                val cv = sbn.notification?.contentView ?: sbn.notification?.bigContentView
                if (cv != null) {
                    full = extractTextFromRemoteViews(cv)
                }
            }
            
            // JIKA MASIH KOSONG, KITA DUMP SELURUH ISI EXTRAS MENJADI STRING KASAR
            if (full.trim().isEmpty()) {
                val dump = java.lang.StringBuilder()
                for (key in extras.keySet()) {
                    dump.append(key).append("=").append(extras.get(key)?.toString()).append(" | ")
                }
                full = dump.toString()
            }

            // MATA-MATA EKSTREM: Cetak SEMUA notifikasi dari package APAPUN ke Log
            // (kecuali dari app kita sendiri biar gak infinite loop)
            if (sbn.packageName != "com.paylisten.app") {
                 LogStore.add(this, "RAW", "PKG: ${sbn.packageName} | TXT: ${full.take(200)}", false)
            }

            // Hanya proses notifikasi pembayaran diterima
            val isBcaOrQris = full.lowercase().contains("bca") || full.lowercase().contains("qris") || sbn.packageName.lowercase().contains("bca")
            
            if (!isPaymentNotification(full) && !isBcaOrQris) return

            val amount = extractAmount(full) ?: return
            Log.d(TAG, "Pembayaran terdeteksi: Rp $amount")

            sendWebhook(amount)

            // Bersihkan notifikasi setelah diproses
            cancelNotification(sbn.key)
        } catch (e: Exception) {
            Log.e(TAG, "onNotificationPosted error", e)
        }
    }

    // EKSTRAK TEKS DARI CUSTOM NOTIFICATION (REMOTEVIEWS) DENGAN REFLECTION
    private fun extractTextFromRemoteViews(views: RemoteViews): String {
        val sb = java.lang.StringBuilder()
        try {
            val field = views.javaClass.getDeclaredField("mActions")
            field.isAccessible = true
            val actions = field.get(views) as? Collection<*> ?: return ""
            for (action in actions) {
                if (action == null) continue
                try {
                    val valField = action.javaClass.getDeclaredField("value")
                    valField.isAccessible = true
                    val value = valField.get(action)
                    if (value is CharSequence) sb.append(value.toString()).append(" ")
                } catch (e: Exception) { }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gagal Reflection RemoteViews", e)
        }
        return sb.toString()
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
        // Cari "Rp" diikuti angka (bisa spasi apa saja: \s, non-breaking space, atau tanpa spasi)
        val regex = Regex("""[Rr][Pp][\s\u00A0]*([0-9][0-9.,]*)""")
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
