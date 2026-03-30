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
        // Remember the last validated/used voice for a long window so apps that
        // omit voiceName on subsequent utterances keep using the user's choice.
        private const val RECENT_VOICE_SELECTION_WINDOW_MS = 24 * 60 * 60 * 1000L // 24h

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

    @Volatile
    private var loadedVoiceName: String? = null

    @Volatile
    private var recentValidatedVoiceName: String? = null

    @Volatile
    private var recentValidatedVoiceAtMs: Long = 0L

    // ── TextToSpeechService contract ─────────────────────────────────────────

    override fun onIsLanguageAvailable(lang: String, country: String, variant: String): Int {
        val availability = Prefs.getVoices(this)
            .map { matchLocale(parseLocale(it.locale), lang, country, variant) }
            .maxOrNull()
            ?: TextToSpeech.LANG_NOT_SUPPORTED
        Log.d(TAG, "onIsLanguageAvailable lang=$lang country=$country variant=$variant -> $availability")
        return availability
    }

    override fun onGetLanguage(): Array<String> {
        val first = Prefs.getVoices(this).firstOrNull()?.let { parseLocale(it.locale) }
        if (first != null) {
            val result = arrayOf(
                first.language,
                first.country,
                first.variant.orEmpty()
            )
            Log.d(TAG, "onGetLanguage -> ${result.contentToString()}")
            return result
        }
        Log.d(TAG, "onGetLanguage -> empty")
        return arrayOf("", "", "")
    }

    override fun onLoadLanguage(lang: String, country: String, variant: String): Int =
        onIsLanguageAvailable(lang, country, variant)

    override fun onStop() {
        isStopped = true
    }

    // ── Voice API (API 21+) ──────────────────────────────────────────────────

    override fun onGetVoices(): List<Voice> {
        val voices = Prefs.getVoices(this).map { config ->
            Voice(
                exposedVoiceName(config),
                parseLocale(config.locale),
                Voice.QUALITY_NORMAL,
                Voice.LATENCY_NORMAL,
                true,
                setOf(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS)
            )
        }
        Log.d(TAG, "onGetVoices -> ${voices.joinToString { "${it.name}:${it.locale}" }}")
        return voices
    }

    override fun onIsValidVoiceName(voiceName: String): Int {
        val config = Prefs.getVoices(this).firstOrNull { matchesVoiceName(it, voiceName) }
        if (config == null) {
            Log.d(TAG, "onIsValidVoiceName voiceName=$voiceName -> ERROR")
            return TextToSpeech.ERROR
        }
        val locale = parseLocale(config.locale)
        val result = onIsLanguageAvailable(locale.language, locale.country, locale.variant)
        if (result >= TextToSpeech.LANG_AVAILABLE) {
            val exposedName = exposedVoiceName(config)
            loadedVoiceName = exposedName
            rememberRecentVoiceSelection(exposedName)
        }
        Log.d(TAG, "onIsValidVoiceName voiceName=$voiceName locale=${config.locale} -> $result")
        return result
    }

    override fun onLoadVoice(voiceName: String): Int {
        val result = onIsValidVoiceName(voiceName)
        if (result >= TextToSpeech.LANG_AVAILABLE) {
            val normalizedVoiceName = Prefs.getVoices(this)
                .firstOrNull { matchesVoiceName(it, voiceName) }
                ?.let(::exposedVoiceName)
                ?: voiceName
            loadedVoiceName = normalizedVoiceName
            rememberRecentVoiceSelection(normalizedVoiceName)
        }
        Log.d(TAG, "onLoadVoice voiceName=$voiceName -> $result")
        return result
    }

    override fun onGetFeaturesForLanguage(lang: String?, country: String?, variant: String?): MutableSet<String> {
        val availability = onIsLanguageAvailable(lang.orEmpty(), country.orEmpty(), variant.orEmpty())
        return if (availability >= TextToSpeech.LANG_AVAILABLE) {
            mutableSetOf(TextToSpeech.Engine.KEY_FEATURE_NETWORK_SYNTHESIS)
        } else {
            mutableSetOf()
        }
    }

    override fun onGetDefaultVoiceNameFor(lang: String, country: String, variant: String): String? {
        val result = Prefs.getVoices(this)
            .maxByOrNull { matchLocale(parseLocale(it.locale), lang, country, variant) }
            ?.takeIf { matchLocale(parseLocale(it.locale), lang, country, variant) != TextToSpeech.LANG_NOT_SUPPORTED }
            ?.let(::exposedVoiceName)
        Log.d(TAG, "onGetDefaultVoiceNameFor lang=$lang country=$country variant=$variant -> $result")
        return result
    }

    // ── Synthesis ────────────────────────────────────────────────────────────

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        isStopped = false
        Log.d(
            TAG,
            "onSynthesizeText voice=${request.voiceName} lang=${request.language} country=${request.country} variant=${request.variant} length=${request.charSequenceText?.length ?: 0}"
        )

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
        Log.d(
            TAG,
            "onSynthesizeText resolved voiceId=$voiceId request.voiceName=${request.voiceName} " +
                "loadedVoice=$loadedVoiceName recentVoice=$recentValidatedVoiceName voices=${
                    Prefs.getVoices(this).joinToString { "${exposedVoiceName(it)}/${it.voiceId}" }
                }"
        )

        // Persist the chosen voice so future utterances that don't provide a
        // voiceName can still stick to the user's last selection.
        Prefs.getVoices(this)
            .firstOrNull { it.voiceId == voiceId }
            ?.let { voice ->
                val exposedName = exposedVoiceName(voice)
                loadedVoiceName = exposedName
                rememberRecentVoiceSelection(exposedName)
            }

        val speed = (request.speechRate.toDouble() / 100.0).coerceIn(0.25, 4.0)

        try {
            val ttsClient = TtsClient(
                endpoint = endpoint,
                model = Prefs.getModel(this),
                apiKey = Prefs.getApiKey(this)
            )
            var started = false
            val frameSize = CHANNEL_COUNT * 2
            var chunkSize = CHUNK_SIZE
            var carryBuffer = ByteArray(0)
            ttsClient.streamSynthesize(
                text = text,
                voiceId = voiceId,
                speed = speed,
                onResponseStarted = {
                    if (!isStopped) {
                        val startResult = callback.start(SAMPLE_RATE, AUDIO_FORMAT, CHANNEL_COUNT)
                        if (startResult == android.speech.tts.TextToSpeech.ERROR) {
                            throw IllegalStateException("callback.start() returned ERROR")
                        }
                        val maxBuffer = callback.maxBufferSize
                        val alignedChunkSize = maxBuffer - (maxBuffer % frameSize)
                        chunkSize = if (alignedChunkSize > 0) alignedChunkSize else CHUNK_SIZE
                        started = true
                    }
                }
            ) { buffer, length ->
                if (isStopped) {
                    return@streamSynthesize
                }

                carryBuffer = if (carryBuffer.isEmpty()) {
                    buffer.copyOf(length)
                } else {
                    carryBuffer + buffer.copyOf(length)
                }

                while (carryBuffer.size >= chunkSize && !isStopped) {
                    val chunk = carryBuffer.copyOfRange(0, chunkSize)
                    carryBuffer = carryBuffer.copyOfRange(chunkSize, carryBuffer.size)
                    val result = callback.audioAvailable(chunk, 0, chunk.size)
                    if (result == android.speech.tts.TextToSpeech.ERROR) {
                        Log.w(TAG, "audioAvailable() returned ERROR for ${chunk.size}-byte chunk – stopping")
                        isStopped = true
                    }
                }
            }

            if (!started && !isStopped) {
                val startResult = callback.start(SAMPLE_RATE, AUDIO_FORMAT, CHANNEL_COUNT)
                if (startResult == android.speech.tts.TextToSpeech.ERROR) {
                    throw IllegalStateException("callback.start() returned ERROR")
                }
                started = true
            }

            val finalLength = carryBuffer.size - (carryBuffer.size % frameSize)
            if (!isStopped && finalLength > 0) {
                val result = callback.audioAvailable(carryBuffer, 0, finalLength)
                if (result == android.speech.tts.TextToSpeech.ERROR) {
                    Log.w(TAG, "audioAvailable() returned ERROR for final $finalLength-byte chunk")
                }
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
        if (voices.isEmpty()) {
            Log.w(TAG, "resolveVoiceId: no voices configured")
            return null
        }

        fun logChoice(reason: String, voice: VoiceConfig, extra: String = "") {
            Log.d(
                TAG,
                "resolveVoiceId -> ${voice.voiceId} (display=${exposedVoiceName(voice)}) reason=$reason $extra " +
                    "request.voiceName=${request.voiceName} lang=${request.language} country=${request.country} variant=${request.variant} " +
                    "loadedVoice=$loadedVoiceName recentVoice=$recentValidatedVoiceName ageMs=${System.currentTimeMillis() - recentValidatedVoiceAtMs}"
            )
        }

        recentVoiceOverride(request, voices)?.let {
            logChoice("recentVoiceOverride", it)
            return it.voiceId
        }

        val voiceName = request.voiceName
        if (!voiceName.isNullOrBlank()) {
            voices.firstOrNull { matchesVoiceName(it, voiceName) }?.let {
                logChoice("request.voiceName", it)
                return it.voiceId
            }
            // If request voiceName is unknown, ignore and continue.
        }

        val loadedVoice = loadedVoiceName
        if (!loadedVoice.isNullOrBlank()) {
            voices.firstOrNull { matchesVoiceName(it, loadedVoice) }?.let {
                logChoice("loadedVoiceName", it)
                return it.voiceId
            }
        }

        val lang = request.language ?: ""
        val country = request.country ?: ""
        val variant = request.variant ?: ""
        if (lang.isNotBlank()) {
            voices
                .map { it to matchLocale(parseLocale(it.locale), lang, country, variant) }
                .maxByOrNull { it.second }
                ?.takeIf { it.second != TextToSpeech.LANG_NOT_SUPPORTED }
                ?.let { (voice, score) ->
                    logChoice("localeMatch", voice, "score=$score")
                    return voice.voiceId
                }
        }

        voices.first().also { logChoice("fallbackFirst", it) }
        return voices.first().voiceId
    }

    private fun exposedVoiceName(config: VoiceConfig): String =
        config.displayName.ifBlank { config.voiceId }

    private fun matchesVoiceName(config: VoiceConfig, voiceName: String): Boolean {
        return exposedVoiceName(config) == voiceName || config.voiceId == voiceName
    }

    private fun rememberRecentVoiceSelection(voiceName: String) {
        recentValidatedVoiceName = voiceName
        recentValidatedVoiceAtMs = System.currentTimeMillis()
        Log.d(TAG, "rememberRecentVoiceSelection voice=$voiceName at=$recentValidatedVoiceAtMs")
    }

    private fun recentVoiceOverride(request: SynthesisRequest, voices: List<VoiceConfig>): VoiceConfig? {
        val candidateName = recentValidatedVoiceName ?: return null
        val ageMs = System.currentTimeMillis() - recentValidatedVoiceAtMs
        if (ageMs !in 0..RECENT_VOICE_SELECTION_WINDOW_MS) {
            Log.d(TAG, "recentVoiceOverride skipped: ageMs=$ageMs > window")
            return null
        }

        val candidate = voices.firstOrNull { matchesVoiceName(it, candidateName) } ?: return null
        val requestVoiceName = request.voiceName
        if (!requestVoiceName.isNullOrBlank() && matchesVoiceName(candidate, requestVoiceName)) {
            return candidate
        }

        val requestLanguage = request.language.orEmpty()
        val requestCountry = request.country.orEmpty()
        val requestVariant = request.variant.orEmpty()
        if (requestLanguage.isBlank()) {
            return candidate
        }

        val candidateLocale = parseLocale(candidate.locale)
        val candidateAvailability = matchLocale(candidateLocale, requestLanguage, requestCountry, requestVariant)
        if (candidateAvailability == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.d(
                TAG,
                "recentVoiceOverride rejected: locale mismatch candidate=${candidate.locale} req=${requestLanguage}-${requestCountry}-${requestVariant}"
            )
            return null
        }

        Log.d(
            TAG,
            "Using recent voice override $candidateName for synth request voice=$requestVoiceName lang=$requestLanguage country=$requestCountry variant=$requestVariant"
        )
        return candidate
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
        val languageCode = language
        if (languageCode.isBlank()) {
            return ""
        }
        return try {
            this.isO3Language
        } catch (_: MissingResourceException) {
            languageCode
        }
    }

    private fun Locale.safeIso3Country(): String {
        val countryCode = country
        if (countryCode.isBlank()) {
            return ""
        }
        return try {
            this.isO3Country
        } catch (_: MissingResourceException) {
            countryCode
        }
    }
}
