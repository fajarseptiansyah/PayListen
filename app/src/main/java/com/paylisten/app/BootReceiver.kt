package com.paylisten.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Auto-start setelah HP reboot/mati-listrik:
 * - Nyalakan ScreenOnService (layar terjaga + heartbeat ke STB)
 * - Daftarkan ulang listener notifikasi tetap aktif (biasanya ikut aktif sendiri,
 *   tapi service perlu di-start agar heartbeat & overlay berjalan)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(PayListenListener.TAG, "BootReceiver: $action")

        // BOOT_COMPLETED: HP selesai boot normal
        if (action == Intent.ACTION_BOOT_COMPLETED) {
            // Hanya nyalakan layar-terus jika sebelumnya memang aktif (user pernah menyalakan)
            val prefs = context.getSharedPreferences(PayListenListener.PREFS, Context.MODE_PRIVATE)
            val screenOnSebelumnya = prefs.getBoolean("screen_on", false)
            Log.d(PayListenListener.TAG, "screen_on sebelumnya: $screenOnSebelumnya")

            if (screenOnSebelumnya) {
                try {
                    val svc = Intent(context, ScreenOnService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(svc)
                    } else {
                        context.startService(svc)
                    }
                    Log.d(PayListenListener.TAG, "ScreenOnService dinyalakan setelah boot")
                } catch (e: Exception) {
                    Log.e(PayListenListener.TAG, "Gagal start service setelah boot", e)
                }
            }
        }
    }
}
