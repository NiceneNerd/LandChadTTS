package com.nicenenerd.landchadtts

import org.json.JSONObject
import java.util.UUID

/**
 * Represents a single TTS voice configuration: the display name shown to the user,
 * the voice ID sent to the OpenAI-compatible server, and the locale for language matching.
 */
data class VoiceConfig(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val voiceId: String,
    val locale: String
) {
    companion object {
        fun fromJson(json: JSONObject): VoiceConfig = VoiceConfig(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            displayName = json.getString("displayName"),
            voiceId = json.getString("voiceId"),
            locale = json.getString("locale")
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("displayName", displayName)
        put("voiceId", voiceId)
        put("locale", locale)
    }
}
