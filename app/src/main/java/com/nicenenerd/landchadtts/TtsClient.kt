package com.nicenenerd.landchadtts

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for communicating with an OpenAI-compatible TTS server.
 *
 * Synthesizes text to raw 16-bit PCM audio (24 kHz, mono) using the
 * /v1/audio/speech endpoint, and optionally discovers available voices
 * via /v1/audio/voices or /v1/models.
 */
class TtsClient(
    private val endpoint: String,
    private val model: String,
    private val apiKey: String
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val baseUrl: String get() = endpoint.trimEnd('/')

    /**
     * Calls POST /v1/audio/speech and returns raw PCM bytes (24 kHz, 16-bit, mono).
     *
     * @throws IOException on network or server errors.
     */
    fun synthesize(text: String, voiceId: String): ByteArray {
        val bodyJson = JSONObject().apply {
            if (model.isNotBlank()) put("model", model)
            put("input", text)
            put("voice", voiceId)
            put("response_format", "pcm")
        }

        val requestBuilder = Request.Builder()
            .url("$baseUrl/v1/audio/speech")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))

        if (apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        val response = client.newCall(requestBuilder.build()).execute()
        if (!response.isSuccessful) {
            val body = response.body?.string() ?: ""
            throw IOException("TTS synthesis failed (HTTP ${response.code}): $body")
        }
        return response.body?.bytes()
            ?: throw IOException("Server returned an empty response body")
    }

    /**
     * Attempts to discover available voices from the server.
     *
     * Strategy (in order):
     * 1. GET /v1/audio/voices – supported by many local servers.
     * 2. GET /v1/models – filter entries whose id contains "tts".
     *
     * Returns an empty list if neither endpoint yields usable data.
     */
    fun fetchVoices(): List<VoiceConfig> =
        tryFetchAudioVoices() ?: tryFetchModels() ?: emptyList()

    // ── private helpers ──────────────────────────────────────────────────────

    private fun tryFetchAudioVoices(): List<VoiceConfig>? = runCatching {
        val response = get("$baseUrl/v1/audio/voices") ?: return null
        val json = JSONObject(response)
        val arr = json.optJSONArray("voices") ?: json.optJSONArray("data") ?: return null

        (0 until arr.length()).mapNotNull { i ->
            val v = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = v.optString("voice_id").ifBlank {
                v.optString("id").ifBlank {
                    v.optString("name").ifBlank { return@mapNotNull null }
                }
            }
            val name = v.optString("name").ifBlank { id }
            val locale = normalizeLocale(v.optString("language").ifBlank { "en-US" })
            VoiceConfig(displayName = name, voiceId = id, locale = locale)
        }
    }.getOrNull()

    private fun tryFetchModels(): List<VoiceConfig>? = runCatching {
        val response = get("$baseUrl/v1/models") ?: return null
        val json = JSONObject(response)
        val data = json.optJSONArray("data") ?: return null

        (0 until data.length()).mapNotNull { i ->
            val m = data.optJSONObject(i) ?: return@mapNotNull null
            val id = m.optString("id").ifBlank { return@mapNotNull null }
            if (!id.contains("tts", ignoreCase = true)) return@mapNotNull null
            VoiceConfig(displayName = id, voiceId = id, locale = "en-US")
        }
    }.getOrNull()

    private fun get(url: String): String? {
        val requestBuilder = Request.Builder().url(url).get()
        if (apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }
        val response = client.newCall(requestBuilder.build()).execute()
        return if (response.isSuccessful) response.body?.string() else null
    }

    private fun normalizeLocale(language: String): String =
        language.replace('_', '-')
}
