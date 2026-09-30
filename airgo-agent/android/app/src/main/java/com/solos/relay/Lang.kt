package com.solos.relay

import java.util.Locale

/**
 * The conversation language. One switch drives all three places language lives:
 * speech recognition (what we hear), TextToSpeech (the voice), and the instruction to
 * Gemini (what it says). Plus the few phrases the phone speaks on its own, like the
 * steering cues, which never go through the model.
 */
enum class Lang(val code: String, val locale: Locale, val displayName: String) {
    EN("en", Locale.US, "English"),
    FR("fr", Locale.FRANCE, "French");

    fun steer(key: String): String = (if (this == FR) FR_PHRASES else EN_PHRASES)[key] ?: key

    companion object {
        @Volatile var current: Lang = EN

        fun from(code: String?): Lang? = entries.firstOrNull {
            code != null && (it.code.equals(code, true) || it.displayName.equals(code, true) ||
                (it == FR && code.equals("français", true)))
        }

        private val EN_PHRASES = mapOf(
            "stop" to "Stop. That way.",
            "around" to "Turn around",
            "right" to "Right",
            "left" to "Left",
            "little_right" to "A little right",
            "little_left" to "A little left",
            "error" to "I hit an error. Stopping there.",
        )
        private val FR_PHRASES = mapOf(
            "stop" to "Stop. C'est par là.",
            "around" to "Retournez-vous",
            "right" to "À droite",
            "left" to "À gauche",
            "little_right" to "Un peu à droite",
            "little_left" to "Un peu à gauche",
            "error" to "J'ai rencontré une erreur. Je m'arrête là.",
        )
    }
}
