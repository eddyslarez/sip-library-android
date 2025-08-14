package com.eddyslarez.siplibrary.data.services.ia

/**
 * Configuración de idiomas para traducción
 */
data class TranslationConfig(
    val isEnabled: Boolean = false,
    val languagePair: LanguagePair = LanguagePair.SPANISH_ENGLISH,
    val translationMode: TranslationMode = TranslationMode.BIDIRECTIONAL,
    val voiceSettings: VoiceSettings = VoiceSettings()
)

enum class LanguagePair(
    val inputLanguage: String,
    val inputCode: String,
    val outputLanguage: String,
    val outputCode: String,
    val displayName: String
) {
    SPANISH_ENGLISH("Español", "es", "English", "en", "Español ↔ English"),
    SPANISH_RUSSIAN("Español", "es", "Русский", "ru", "Español ↔ Русский"),
    ENGLISH_RUSSIAN("English", "en", "Русский", "ru", "English ↔ Русский"),
    ENGLISH_FRENCH("English", "en", "Français", "fr", "English ↔ Français"),
    SPANISH_FRENCH("Español", "es", "Français", "fr", "Español ↔ Français");

    fun getReversePair(): LanguagePair {
        return when (this) {
            SPANISH_ENGLISH -> SPANISH_ENGLISH // Es bidireccional
            SPANISH_RUSSIAN -> SPANISH_RUSSIAN
            ENGLISH_RUSSIAN -> ENGLISH_RUSSIAN
            ENGLISH_FRENCH -> ENGLISH_FRENCH
            SPANISH_FRENCH -> SPANISH_FRENCH
        }
    }
}

enum class TranslationMode {
    BIDIRECTIONAL,      // Traduce en ambas direcciones automáticamente
    INPUT_TO_OUTPUT,    // Solo traduce del idioma de entrada al de salida
    OUTPUT_TO_INPUT     // Solo traduce del idioma de salida al de entrada
}

data class VoiceSettings(
    val voice: String = "alloy", // alloy, echo, fable, onyx, nova, shimmer
    val model: String = "gpt-4o-realtime-preview-2025-01-07",
    val temperature: Double = 0.8,
    val maxResponseTokens: Int = 150, // Limitado para respuestas rápidas
    val vadThreshold: Double = 0.5,
    val vadPrefixPadding: Int = 300,
    val vadSilenceDuration: Int = 500
)
