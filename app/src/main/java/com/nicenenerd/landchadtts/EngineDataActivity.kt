package com.nicenenerd.landchadtts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.appcompat.app.AppCompatActivity

class EngineDataActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent?.action) {
            TextToSpeech.Engine.ACTION_CHECK_TTS_DATA -> {
                val availableLocales = ArrayList(
                    Prefs.getVoices(this)
                        .map { java.util.Locale.forLanguageTag(it.locale.replace('_', '-')) }
                        .mapNotNull { locale ->
                            val language = try {
                                locale.isO3Language
                            } catch (_: java.util.MissingResourceException) {
                                null
                            } ?: return@mapNotNull null
                            val country = try {
                                locale.isO3Country
                            } catch (_: java.util.MissingResourceException) {
                                ""
                            }
                            buildString {
                                append(language)
                                if (country.isNotBlank()) {
                                    append('-').append(country)
                                }
                                if (locale.variant.isNotBlank()) {
                                    append('-').append(locale.variant)
                                }
                            }
                        }
                        .distinct()
                )
                val result = if (availableLocales.isEmpty()) {
                    TextToSpeech.Engine.CHECK_VOICE_DATA_MISSING_DATA
                } else {
                    TextToSpeech.Engine.CHECK_VOICE_DATA_PASS
                }
                setResult(
                    result,
                    Intent().apply {
                        putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, availableLocales)
                        putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, arrayListOf())
                    }
                )
            }

            TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                setResult(Activity.RESULT_OK)
            }

            TextToSpeech.Engine.ACTION_GET_SAMPLE_TEXT -> {
                setResult(
                    TextToSpeech.LANG_AVAILABLE,
                    Intent().putExtra(
                        TextToSpeech.Engine.EXTRA_SAMPLE_TEXT,
                        getString(R.string.sample_text)
                    )
                )
            }

            else -> setResult(Activity.RESULT_CANCELED)
        }

        finish()
    }
}
