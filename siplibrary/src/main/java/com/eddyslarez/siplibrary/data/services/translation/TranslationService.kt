package com.eddyslarez.siplibrary.data.services.translation

/**
 * Interfaz para servicios de traducción de audio en tiempo real
 * 
 * @author Eddys Larez
 */
interface TranslationService {
    
    /**
     * Transcribe audio a texto
     */
    suspend fun transcribeAudio(audioData: ByteArray, language: String): String
    
    /**
     * Traduce texto de un idioma a otro
     */
    suspend fun translateText(text: String, sourceLanguage: String, targetLanguage: String): String
    
    /**
     * Genera audio a partir de texto (Text-to-Speech)
     */
    suspend fun generateSpeech(text: String, language: String, voice: String? = null): ByteArray
    
    /**
     * Detecta el idioma del audio
     */
    suspend fun detectLanguage(audioData: ByteArray): String?
    
    /**
     * Obtiene las voces disponibles para un idioma
     */
    suspend fun getAvailableVoices(language: String): List<Voice>
    
    /**
     * Limpia recursos
     */
    fun dispose()
}

/**
 * Información de voz para TTS
 */
data class Voice(
    val id: String,
    val name: String,
    val language: String,
    val gender: VoiceGender,
    val quality: VoiceQuality
)

enum class VoiceGender {
    MALE, FEMALE, NEUTRAL
}

enum class VoiceQuality {
    STANDARD, PREMIUM, NEURAL
}