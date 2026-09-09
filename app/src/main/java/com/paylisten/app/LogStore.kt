package com.paylisten.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Penyimpan log aktivitas PAY LISTEN (maks 50 baris, rotasi otomatis).
 * Disimpan lokal di SharedPreferences — tidak dikirim ke mana pun.
 */
object LogStore {
    private const val KEY = "log_aktivitas"
    private const val MAX = 50

    fun add(ctx: Context, jenis: String, detail: String, ok: Boolean) {
        try {
            val prefs = ctx.getSharedPreferences(PayListenListener.PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
            val tgl = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date())
            val item = JSONObject()
                .put("waktu", tgl)
                .put("jenis", jenis)
                .put("detail", detail)
                .put("ok", ok)
            arr.put(item)
            // rotasi: buang terlama kalau lebih dari MAX
            while (arr.length() > MAX) arr.remove(0)
            prefs.edit().putString(KEY, arr.toString()).apply()
        } catch (_: Exception) {}
    }

    fun getAll(ctx: Context): JSONArray {
        return try {
            val prefs = ctx.getSharedPreferences(PayListenListener.PREFS, Context.MODE_PRIVATE)
            JSONArray(prefs.getString(KEY, "[]") ?: "[]")
        } catch (_: Exception) { JSONArray() }
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PayListenListener.PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, "[]").apply()
    }
}
