package com.nicenenerd.landchadtts

import android.media.AudioFormat
import android.speech.tts.TextToSpeech
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import java.util.MissingResourceException
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
        val availability = Prefs.getVoices(this)
            .map { matchLocale(parseLocale(it.locale), lang, country, variant) }
            .maxOrNull()
            ?: TextToSpeech.LANG_NOT_SUPPORTED
        return availability
    }

    override fun onGetLanguage(): Array<String> {
        val first = Prefs.getVoices(this).firstOrNull()?.let { parseLocale(it.locale) }
        if (first != null) {
            return arrayOf(
                first.safeIso3Language(),
                first.safeIso3Country(),
                first.variant.orEmpty()
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
        return Prefs.getVoices(this)
            .maxByOrNull { matchLocale(parseLocale(it.locale), lang, country, variant) }
            ?.takeIf { matchLocale(parseLocale(it.locale), lang, country, variant) != TextToSpeech.LANG_NOT_SUPPORTED }
            ?.voiceId
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
        val variant = request.variant ?: ""
        if (lang.isNotBlank()) {
            voices.maxByOrNull { matchLocale(parseLocale(it.locale), lang, country, variant) }
                ?.takeIf { matchLocale(parseLocale(it.locale), lang, country, variant) != TextToSpeech.LANG_NOT_SUPPORTED }
                ?.let { return it.voiceId }
        }

        return voices.first().voiceId
    }

    private fun matchLocale(locale: Locale, lang: String, country: String, variant: String): Int {
        if (lang.isBlank() || !matchesLanguage(locale, lang)) {
            return TextToSpeech.LANG_NOT_SUPPORTED
        }
        if (country.isBlank()) {
            return TextToSpeech.LANG_AVAILABLE
        }
        if (!matchesCountry(locale, country)) {
            return TextToSpeech.LANG_AVAILABLE
        }
        if (variant.isBlank()) {
            return TextToSpeech.LANG_COUNTRY_AVAILABLE
        }
        return if (locale.variant.equals(variant, ignoreCase = true)) {
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
        } else {
            TextToSpeech.LANG_COUNTRY_AVAILABLE
        }
    }

    private fun matchesLanguage(locale: Locale, lang: String): Boolean {
        return locale.language.equals(lang, ignoreCase = true) ||
            locale.safeIso3Language().equals(lang, ignoreCase = true)
    }

    private fun matchesCountry(locale: Locale, country: String): Boolean {
        if (country.isBlank()) return true
        return locale.country.equals(country, ignoreCase = true) ||
            locale.safeIso3Country().equals(country, ignoreCase = true)
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

    private fun Locale.safeIso3Language(): String {
        return try {
            if (language.isBlank()) "" else iso3Language
        } catch (_: MissingResourceException) {
            language
        }
    }

    private fun Locale.safeIso3Country(): String {
        return try {
            if (country.isBlank()) "" else iso3Country
        } catch (_: MissingResourceException) {
            country
        }
    }
}
