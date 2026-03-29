package com.nicenenerd.landchadtts

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

/**
 * Android TTS engine service that forwards synthesis requests to a user-configured
 * OpenAI-compatible TTS server and streams the returned PCM audio back to the system.
 */
class LandChadTtsService : TextToSpeechService() {

    companion object {
        private const val TAG = "LandChadTtsService"

        /** Sample rate of the PCM audio returned by OpenAI TTS (pcm format). */
        private const val SAMPLE_RATE = 24000

        /** PCM 16-bit little-endian – matches the OpenAI 'pcm' response format. */
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /** Mono channel count. */
        private const val CHANNEL_COUNT = 1

        /** Size of each audio chunk written to the SynthesisCallback. */
        private const val CHUNK_SIZE = 8192
    }

    @Volatile
    private var isStopped = false

    // ── TextToSpeechService contract ─────────────────────────────────────────

    override fun onIsLanguageAvailable(lang: String, country: String, variant: String): Int {
        val voices = Prefs.getVoices(this)
        return if (voices.any { it.locale.startsWith(lang, ignoreCase = true) }) {
            android.speech.tts.TextToSpeech.LANG_AVAILABLE
        } else {
            android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onGetLanguage(): Array<String> {
        val first = Prefs.getVoices(this).firstOrNull()
        if (first != null) {
            val parts = first.locale.replace('_', '-').split("-")
            return arrayOf(
                parts.getOrElse(0) { "" },
                parts.getOrElse(1) { "" },
                ""
            )
        }
        return arrayOf("", "", "")
    }

    override fun onLoadLanguage(lang: String, country: String, variant: String): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onStop() {
        isStopped = true
    }

    // ── Voice API (API 21+) ──────────────────────────────────────────────────

    override fun onGetVoices(): List<Voice> {
        return Prefs.getVoices(this).map { config ->
            Voice(
                config.voiceId,
                parseLocale(config.locale),
                Voice.QUALITY_NORMAL,
                Voice.LATENCY_HIGH,
                true,   // requires network connection
                emptySet<String>()
            )
        }
    }

    override fun onIsValidVoiceName(voiceName: String): Int =
        if (Prefs.getVoices(this).any { it.voiceId == voiceName }) {
            android.speech.tts.TextToSpeech.SUCCESS
        } else {
            android.speech.tts.TextToSpeech.ERROR
        }

    override fun onLoadVoice(voiceName: String): Int =
        if (Prefs.getVoices(this).any { it.voiceId == voiceName }) {
            android.speech.tts.TextToSpeech.SUCCESS
        } else {
            android.speech.tts.TextToSpeech.ERROR
        }

    override fun onGetDefaultVoiceNameFor(lang: String, country: String, variant: String): String? {
        val voices = Prefs.getVoices(this)
        val prefix = if (country.isNotBlank()) "$lang-$country" else lang
        return voices.firstOrNull { it.locale.startsWith(prefix, ignoreCase = true) }?.voiceId
            ?: voices.firstOrNull { it.locale.startsWith(lang, ignoreCase = true) }?.voiceId
    }

    // ── Synthesis ────────────────────────────────────────────────────────────

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        isStopped = false

        val text = request.charSequenceText?.toString()
        if (text.isNullOrBlank()) {
            callback.done()
            return
        }

        val endpoint = Prefs.getEndpoint(this)
        if (endpoint.isBlank()) {
            Log.e(TAG, "No TTS endpoint configured – open the app to set one up")
            callback.error()
            return
        }

        val voiceId = resolveVoiceId(request)
        if (voiceId == null) {
            Log.e(TAG, "No matching voice found for request (lang=${request.language})")
            callback.error()
            return
        }

        try {
            val ttsClient = TtsClient(
                endpoint = endpoint,
                model = Prefs.getModel(this),
                apiKey = Prefs.getApiKey(this)
            )
            val pcmData = ttsClient.synthesize(text, voiceId)

            if (isStopped) {
                callback.done()
                return
            }

            callback.start(SAMPLE_RATE, AUDIO_FORMAT, CHANNEL_COUNT)

            var offset = 0
            while (offset < pcmData.size && !isStopped) {
                val length = minOf(CHUNK_SIZE, pcmData.size - offset)
                val result = callback.audioAvailable(pcmData, offset, length)
                if (result == android.speech.tts.TextToSpeech.ERROR) {
                    Log.w(TAG, "audioAvailable() returned ERROR at offset $offset – stopping")
                    break
                }
                offset += length
            }

            callback.done()
        } catch (e: Exception) {
            Log.e(TAG, "Speech synthesis failed", e)
            callback.error()
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Determines which voice ID to use for a synthesis request.
     *
     * Priority:
     * 1. Exact match on [SynthesisRequest.voiceName].
     * 2. Locale match on lang-country.
     * 3. Locale match on lang only.
     * 4. First configured voice (fallback).
     */
    private fun resolveVoiceId(request: SynthesisRequest): String? {
        val voices = Prefs.getVoices(this)
        if (voices.isEmpty()) return null

        val voiceName = request.voiceName
        if (!voiceName.isNullOrBlank()) {
            voices.firstOrNull { it.voiceId == voiceName }?.let { return it.voiceId }
        }

        val lang = request.language ?: ""
        val country = request.country ?: ""
        if (lang.isNotBlank()) {
            val fullLocale = if (country.isNotBlank()) "$lang-$country" else lang
            voices.firstOrNull { it.locale.startsWith(fullLocale, ignoreCase = true) }
                ?.let { return it.voiceId }
            voices.firstOrNull { it.locale.startsWith(lang, ignoreCase = true) }
                ?.let { return it.voiceId }
        }

        return voices.first().voiceId
    }

    private fun parseLocale(locale: String): Locale {
        return try {
            val parts = locale.replace('_', '-').split("-")
            when (parts.size) {
                1 -> Locale(parts[0])
                2 -> Locale(parts[0], parts[1])
                else -> Locale(parts[0], parts[1], parts[2])
            }
        } catch (e: Exception) {
            Locale.US
        }
    }
}
