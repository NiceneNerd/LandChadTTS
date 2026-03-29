package com.nicenenerd.landchadtts

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Thin wrapper around SharedPreferences for storing and retrieving app settings.
 */
object Prefs {
    private const val PREF_FILE = "landchadtts_prefs"
    private const val KEY_ENDPOINT = "endpoint"
    private const val KEY_MODEL = "model"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_VOICES = "voices"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    fun getEndpoint(context: Context): String =
        prefs(context).getString(KEY_ENDPOINT, "") ?: ""

    fun setEndpoint(context: Context, value: String) =
        prefs(context).edit().putString(KEY_ENDPOINT, value).apply()

    fun getModel(context: Context): String =
        prefs(context).getString(KEY_MODEL, "") ?: ""

    fun setModel(context: Context, value: String) =
        prefs(context).edit().putString(KEY_MODEL, value).apply()

    fun getApiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "") ?: ""

    fun setApiKey(context: Context, value: String) =
        prefs(context).edit().putString(KEY_API_KEY, value).apply()

    fun getVoices(context: Context): List<VoiceConfig> {
        val json = prefs(context).getString(KEY_VOICES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { VoiceConfig.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setVoices(context: Context, voices: List<VoiceConfig>) {
        val arr = JSONArray()
        voices.forEach { arr.put(it.toJson()) }
        prefs(context).edit().putString(KEY_VOICES, arr.toString()).apply()
    }
}
