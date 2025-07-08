package com.eddyslarez.siplibrary.data.services.translation

import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Implementación del servicio de traducción usando Google Cloud
 * 
 * @author Eddys Larez
 */
class GoogleTranslationService(
    private val apiKey: String
) : TranslationService {
    
    companion object {
        private const val TAG = "GoogleTranslationService"
        private const val SPEECH_TO_TEXT_URL = "https://speech.googleapis.com/v1/speech:recognize"
        private const val TRANSLATE_URL = "https://translation.googleapis.com/language/translate/v2"
        private const val TEXT_TO_SPEECH_URL = "https://texttospeech.googleapis.com/v1/text:synthesize"
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun transcribeAudio(audioData: ByteArray, language: String): String {
        return withContext(Dispatchers.IO) {
            try {
                log.d(tag = TAG) { "Transcribing audio with Google Speech-to-Text" }
                
                // Convertir audio a base64
                val audioBase64 = android.util.Base64.encodeToString(audioData, android.util.Base64.NO_WRAP)
                
                val requestBody = SpeechRecognitionRequest(
                    config = RecognitionConfig(
                        encoding = "LINEAR16",
                        sampleRateHertz = 16000,
                        languageCode = mapLanguageCode(language)
                    ),
                    audio = AudioContent(content = audioBase64)
                )
                
                val request = Request.Builder()
                    .url("$SPEECH_TO_TEXT_URL?key=$apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(json.encodeToString(SpeechRecognitionRequest.serializer(), requestBody).toRequestBody("application/json".toMediaType()))
                    .build()
                
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                
                if (response.isSuccessful) {
                    val speechResponse = json.decodeFromString<SpeechRecognitionResponse>(responseBody)
                    val transcript = speechResponse.results.firstOrNull()?.alternatives?.firstOrNull()?.transcript ?: ""
                    log.d(tag = TAG) { "Google transcription successful: $transcript" }
                    return@withContext transcript
                } else {
                    log.e(tag = TAG) { "Google transcription failed: ${response.code} - $responseBody" }
                    return@withContext ""
                }
                
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error with Google transcription: ${e.message}" }
                return@withContext ""
            }
        }
    }

    override suspend fun translateText(text: String, sourceLanguage: String, targetLanguage: String): String {
        return withContext(Dispatchers.IO) {
            try {
                log.d(tag = TAG) { "Translating with Google Translate" }
                
                val requestBody = FormBody.Builder()
                    .add("q", text)
                    .add("source", mapLanguageCode(sourceLanguage))
                    .add("target", mapLanguageCode(targetLanguage))
                    .add("format", "text")
                    .build()
                
                val request = Request.Builder()
                    .url("$TRANSLATE_URL?key=$apiKey")
                    .post(requestBody)
                    .build()
                
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                
                if (response.isSuccessful) {
                    val translateResponse = json.decodeFromString<TranslateResponse>(responseBody)
                    val translatedText = translateResponse.data.translations.firstOrNull()?.translatedText ?: ""
                    log.d(tag = TAG) { "Google translation successful: $translatedText" }
                    return@withContext translatedText
                } else {
                    log.e(tag = TAG) { "Google translation failed: ${response.code} - $responseBody" }
                    return@withContext ""
                }
                
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error with Google translation: ${e.message}" }
                return@withContext ""
            }
        }
    }

    override suspend fun generateSpeech(text: String, language: String, voice: String?): ByteArray {
        return withContext(Dispatchers.IO) {
            try {
                log.d(tag = TAG) { "Generating speech with Google Text-to-Speech" }
                
                val selectedVoice = voice ?: getDefaultVoiceForLanguage(language)
                
                val requestBody = TextToSpeechRequest(
                    input = SynthesisInput(text = text),
                    voice = VoiceSelectionParams(
                        languageCode = mapLanguageCode(language),
                        name = selectedVoice
                    ),
                    audioConfig = AudioConfig(
                        audioEncoding = "MP3"
                    )
                )
                
                val request = Request.Builder()
                    .url("$TEXT_TO_SPEECH_URL?key=$apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(json.encodeToString(TextToSpeechRequest.serializer(), requestBody).toRequestBody("application/json".toMediaType()))
                    .build()
                
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                
                if (response.isSuccessful) {
                    val ttsResponse = json.decodeFromString<TextToSpeechResponse>(responseBody)
                    val audioBytes = android.util.Base64.decode(ttsResponse.audioContent, android.util.Base64.DEFAULT)
                    log.d(tag = TAG) { "Google TTS successful (${audioBytes.size} bytes)" }
                    return@withContext audioBytes
                } else {
                    log.e(tag = TAG) { "Google TTS failed: ${response.code} - $responseBody" }
                    return@withContext byteArrayOf()
                }
                
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error with Google TTS: ${e.message}" }
                return@withContext byteArrayOf()
            }
        }
    }

    override suspend fun detectLanguage(audioData: ByteArray): String? {
        // Google Speech-to-Text puede detectar idioma automáticamente
        return withContext(Dispatchers.IO) {
            try {
                val audioBase64 = android.util.Base64.encodeToString(audioData, android.util.Base64.NO_WRAP)
                
                val requestBody = SpeechRecognitionRequest(
                    config = RecognitionConfig(
                        encoding = "LINEAR16",
                        sampleRateHertz = 16000,
                        languageCode = "auto" // Detección automática
                    ),
                    audio = AudioContent(content = audioBase64)
                )
                
                val request = Request.Builder()
                    .url("$SPEECH_TO_TEXT_URL?key=$apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(json.encodeToString(SpeechRecognitionRequest.serializer(), requestBody).toRequestBody("application/json".toMediaType()))
                    .build()
                
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                
                if (response.isSuccessful) {
                    val speechResponse = json.decodeFromString<SpeechRecognitionResponse>(responseBody)
                    // Google devuelve el idioma detectado en los metadatos
                    return@withContext speechResponse.results.firstOrNull()?.languageCode
                } else {
                    return@withContext null
                }
                
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error detecting language with Google: ${e.message}" }
                return@withContext null
            }
        }
    }

    override suspend fun getAvailableVoices(language: String): List<Voice> {
        // Implementar llamada a la API de Google para obtener voces disponibles
        return when (mapLanguageCode(language)) {
            "en-US" -> listOf(
                Voice("en-US-Standard-A", "Standard A (Female)", "en-US", VoiceGender.FEMALE, VoiceQuality.STANDARD),
                Voice("en-US-Standard-B", "Standard B (Male)", "en-US", VoiceGender.MALE, VoiceQuality.STANDARD),
                Voice("en-US-Neural2-A", "Neural A (Female)", "en-US", VoiceGender.FEMALE, VoiceQuality.NEURAL),
                Voice("en-US-Neural2-C", "Neural C (Female)", "en-US", VoiceGender.FEMALE, VoiceQuality.NEURAL)
            )
            "es-ES" -> listOf(
                Voice("es-ES-Standard-A", "Standard A (Female)", "es-ES", VoiceGender.FEMALE, VoiceQuality.STANDARD),
                Voice("es-ES-Standard-B", "Standard B (Male)", "es-ES", VoiceGender.MALE, VoiceQuality.STANDARD),
                Voice("es-ES-Neural2-A", "Neural A (Female)", "es-ES", VoiceGender.FEMALE, VoiceQuality.NEURAL)
            )
            else -> listOf(
                Voice("$language-Standard-A", "Standard A", language, VoiceGender.NEUTRAL, VoiceQuality.STANDARD)
            )
        }
    }

    private fun mapLanguageCode(language: String): String {
        return when (language.lowercase()) {
            "en", "english" -> "en-US"
            "es", "spanish" -> "es-ES"
            "fr", "french" -> "fr-FR"
            "de", "german" -> "de-DE"
            "it", "italian" -> "it-IT"
            "pt", "portuguese" -> "pt-BR"
            "ja", "japanese" -> "ja-JP"
            "ko", "korean" -> "ko-KR"
            "zh", "chinese" -> "zh-CN"
            else -> language
        }
    }

    private fun getDefaultVoiceForLanguage(language: String): String {
        return when (mapLanguageCode(language)) {
            "en-US" -> "en-US-Neural2-A"
            "es-ES" -> "es-ES-Neural2-A"
            "fr-FR" -> "fr-FR-Neural2-A"
            "de-DE" -> "de-DE-Neural2-A"
            else -> "$language-Standard-A"
        }
    }

    override fun dispose() {
        // Limpiar recursos
    }

    // Modelos de datos para Google Cloud APIs
    @Serializable
    private data class SpeechRecognitionRequest(
        val config: RecognitionConfig,
        val audio: AudioContent
    )

    @Serializable
    private data class RecognitionConfig(
        val encoding: String,
        val sampleRateHertz: Int,
        val languageCode: String
    )

    @Serializable
    private data class AudioContent(
        val content: String
    )

    @Serializable
    private data class SpeechRecognitionResponse(
        val results: List<SpeechRecognitionResult>
    )

    @Serializable
    private data class SpeechRecognitionResult(
        val alternatives: List<SpeechRecognitionAlternative>,
        val languageCode: String? = null
    )

    @Serializable
    private data class SpeechRecognitionAlternative(
        val transcript: String,
        val confidence: Double? = null
    )

    @Serializable
    private data class TranslateResponse(
        val data: TranslateData
    )

    @Serializable
    private data class TranslateData(
        val translations: List<Translation>
    )

    @Serializable
    private data class Translation(
        val translatedText: String
    )

    @Serializable
    private data class TextToSpeechRequest(
        val input: SynthesisInput,
        val voice: VoiceSelectionParams,
        val audioConfig: AudioConfig
    )

    @Serializable
    private data class SynthesisInput(
        val text: String
    )

    @Serializable
    private data class VoiceSelectionParams(
        val languageCode: String,
        val name: String
    )

    @Serializable
    private data class AudioConfig(
        val audioEncoding: String
    )

    @Serializable
    private data class TextToSpeechResponse(
        val audioContent: String
    )
}