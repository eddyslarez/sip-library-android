package com.eddyslarez.siplibrary.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Modelos para el sistema de traducción en tiempo real
 * 
 * @author Eddys Larez
 */

@Parcelize
data class TranslationConfig(
    val isEnabled: Boolean = false,
    val preferredLanguage: String = "es", // Idioma preferido del usuario
    val voiceGender: VoiceGender = VoiceGender.FEMALE,
    val openAiApiKey: String = "",
    val autoDetectLanguage: Boolean = true,
    val translationQuality: TranslationQuality = TranslationQuality.HIGH
) : Parcelable

@Parcelize
enum class VoiceGender : Parcelable {
    MALE,
    FEMALE,
    NEUTRAL
}

@Parcelize
enum class TranslationQuality : Parcelable {
    FAST,    // Menor latencia, calidad estándar
    HIGH     // Mayor latencia, mejor calidad
}

@Parcelize
enum class SupportedLanguage(
    val code: String,
    val displayName: String,
    val openAiVoices: Map<VoiceGender, String>
) : Parcelable {
    SPANISH("es", "Español", mapOf(
        VoiceGender.FEMALE to "alloy",
        VoiceGender.MALE to "echo",
        VoiceGender.NEUTRAL to "shimmer"
    )),
    ENGLISH("en", "English", mapOf(
        VoiceGender.FEMALE to "nova",
        VoiceGender.MALE to "onyx", 
        VoiceGender.NEUTRAL to "shimmer"
    )),
    FRENCH("fr", "Français", mapOf(
        VoiceGender.FEMALE to "alloy",
        VoiceGender.MALE to "echo",
        VoiceGender.NEUTRAL to "shimmer"
    )),
    GERMAN("de", "Deutsch", mapOf(
        VoiceGender.FEMALE to "alloy",
        VoiceGender.MALE to "echo",
        VoiceGender.NEUTRAL to "shimmer"
    )),
    ITALIAN("it", "Italiano", mapOf(
        VoiceGender.FEMALE to "alloy",
        VoiceGender.MALE to "echo",
        VoiceGender.NEUTRAL to "shimmer"
    )),
    PORTUGUESE("pt", "Português", mapOf(
        VoiceGender.FEMALE to "alloy",
        VoiceGender.MALE to "echo",
        VoiceGender.NEUTRAL to "shimmer"
    )),
    RUSSIAN("ru", "Русский", mapOf(
        VoiceGender.FEMALE to "alloy",
        VoiceGender.MALE to "echo",
        VoiceGender.NEUTRAL to "shimmer"
    )),
    CHINESE("zh", "中文", mapOf(
        VoiceGender.FEMALE to "alloy",
        VoiceGender.MALE to "echo",
        VoiceGender.NEUTRAL to "shimmer"
    )),
    JAPANESE("ja", "日本語", mapOf(
        VoiceGender.FEMALE to "alloy",
        VoiceGender.MALE to "echo",
        VoiceGender.NEUTRAL to "shimmer"
    )),
    KOREAN("ko", "한국어", mapOf(
        VoiceGender.FEMALE to "alloy",
        VoiceGender.MALE to "echo",
        VoiceGender.NEUTRAL to "shimmer"
    ));

    fun getVoiceForGender(gender: VoiceGender): String {
        return openAiVoices[gender] ?: openAiVoices[VoiceGender.NEUTRAL] ?: "alloy"
    }

    companion object {
        fun fromCode(code: String): SupportedLanguage? {
            return values().find { it.code.equals(code, ignoreCase = true) }
        }
    }
}

@Parcelize
data class TranslationSession(
    val sessionId: String,
    val callId: String,
    val userLanguage: String,
    val detectedRemoteLanguage: String?,
    val isActive: Boolean,
    val startTime: Long,
    val translatedMessages: Int = 0
) : Parcelable

@Parcelize
data class LanguageDetectionResult(
    val detectedLanguage: String,
    val confidence: Float,
    val timestamp: Long
) : Parcelable

enum class TranslationDirection {
    INCOMING,  // Audio del remoto traducido a mi idioma
    OUTGOING   // Mi audio traducido al idioma del remoto
}

data class AudioTranslationRequest(
    val audioData: ByteArray,
    val direction: TranslationDirection,
    val sourceLanguage: String?,
    val targetLanguage: String,
    val sessionId: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AudioTranslationRequest

        if (!audioData.contentEquals(other.audioData)) return false
        if (direction != other.direction) return false
        if (sourceLanguage != other.sourceLanguage) return false
        if (targetLanguage != other.targetLanguage) return false
        if (sessionId != other.sessionId) return false

        return true
    }

    override fun hashCode(): Int {
        var result = audioData.contentHashCode()
        result = 31 * result + direction.hashCode()
        result = 31 * result + (sourceLanguage?.hashCode() ?: 0)
        result = 31 * result + targetLanguage.hashCode()
        result = 31 * result + sessionId.hashCode()
        return result
    }
}

data class AudioTranslationResponse(
    val translatedAudio: ByteArray,
    val originalText: String?,
    val translatedText: String?,
    val detectedLanguage: String?,
    val sessionId: String,
    val success: Boolean,
    val errorMessage: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AudioTranslationResponse

        if (!translatedAudio.contentEquals(other.translatedAudio)) return false
        if (originalText != other.originalText) return false
        if (translatedText != other.translatedText) return false
        if (detectedLanguage != other.detectedLanguage) return false
        if (sessionId != other.sessionId) return false
        if (success != other.success) return false
        if (errorMessage != other.errorMessage) return false

        return true
    }

    override fun hashCode(): Int {
        var result = translatedAudio.contentHashCode()
        result = 31 * result + (originalText?.hashCode() ?: 0)
        result = 31 * result + (translatedText?.hashCode() ?: 0)
        result = 31 * result + (detectedLanguage?.hashCode() ?: 0)
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + success.hashCode()
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        return result
    }
}