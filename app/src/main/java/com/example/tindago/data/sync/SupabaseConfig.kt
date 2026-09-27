package com.example.tindago.data.sync

import android.content.Context
import android.content.SharedPreferences

/**
 * Phase 2 — Supabase connection constants + session prefs.
 * Values: Supabase Dashboard → Settings → API (Project URL + anon public key).
 */
object SupabaseConfig {

    // ── Supabase project (wired 2026-09-26, Settings → API) ──
    const val SUPABASE_URL = "https://gncnluxjrpmbjmtzcwsv.supabase.co"
    const val SUPABASE_ANON_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImduY25sdXhqcnBtYmptdHpjd3N2Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTA0MTcwODAsImV4cCI6MjEwNTk5MzA4MH0.Ynto-Kz-CdIrE9jqJT2s-PJtiGFIYzr4Qg9hC16_Ris"
    // ───────────────────────────────────────────────

    private const val PREFS_NAME = "tindago_sync_prefs"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_REFRESH_TOKEN = "refresh_token"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_USER_EMAIL = "user_email"
    private const val KEY_LAST_SYNC = "last_sync_timestamp"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveSession(
        context: Context,
        accessToken: String,
        refreshToken: String?,
        uid: String?,
        email: String
    ) {
        prefs(context).edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putString(KEY_USER_ID, uid)
            .putString(KEY_USER_EMAIL, email)
            .apply()
    }

    fun getToken(context: Context): String? =
        prefs(context).getString(KEY_ACCESS_TOKEN, null)

    fun getRefreshToken(context: Context): String? =
        prefs(context).getString(KEY_REFRESH_TOKEN, null)

    fun getUid(context: Context): String? =
        prefs(context).getString(KEY_USER_ID, null)

    fun getEmail(context: Context): String? =
        prefs(context).getString(KEY_USER_EMAIL, null)

    fun isLoggedIn(context: Context): Boolean =
        getToken(context) != null

    fun logout(context: Context) {
        prefs(context).edit().clear().apply()
    }

    fun saveLastSyncTime(context: Context, timestamp: Long) {
        prefs(context).edit().putLong(KEY_LAST_SYNC, timestamp).apply()
    }

    fun getLastSyncTime(context: Context): Long =
        prefs(context).getLong(KEY_LAST_SYNC, 0L)
}
