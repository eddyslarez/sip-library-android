package com.eddyslarez.siplibrary.data.services.translation

import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Implementación del servicio de traducción usando Azure Cognitive Services
 * 
 * @author Eddys Larez
 */
class AzureTranslationService(
    private val apiKey: String,
    private val region: String = "eastus"
) : TranslationService {
    
    companion object {
        private const val TAG = "AzureTranslationService"
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
                log.d(tag = TAG) { "Transcribing audio with Azure Speech Services" }
                
                val url = "https://$region.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1"
                
                val request = Request.Builder()
                    .url("$url?language=${mapLanguageCode(language)}&format=simple")
                    .addHeader("Ocp-Apim-Subscription-Key", apiKey)
                    .addHeader("Content-Type", "audio/wav")
                    .post(RequestBody.create("audio/wav".toMediaType(), audioData))
                    .build()
                
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                
                if (response.isSuccessful) {
                    val speechResponse = json.decodeFromString<AzureSpeechResponse>(responseBody)
                    log.d(tag = TAG) { "Azure transcription successful: ${speechResponse.DisplayText}" }
                    return@withContext speechResponse.DisplayText ?: ""
                } else {
                    log.e(tag = TAG) { "Azure transcription failed: ${response.code} - $responseBody" }
                    return@withContext ""
                }
                
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error with Azure transcription: ${e.message}" }
                return@withContext ""
            }
        }
    }

    override suspend fun translateText(text: String, sourceLanguage: String, targetLanguage: String): String {
        return withContext(Dispatchers.IO) {
            try {
                log.d(tag = TAG) { "Translating with Azure Translator" }
                
                val url = "https://api.cognitive.microsofttranslator.com/translate"
                val params = "api-version=3.0&from=${mapLanguageCode(sourceLanguage)}&to=${mapLanguageCode(targetLanguage)}"
                
                val requestBody = listOf(
                    mapOf("Text" to text)
                )
                
                val request = Request.Builder()
                    .url("$url?$params")
                    .addHeader("Ocp-Apim-Subscription-Key", apiKey)
                    .addHeader("Ocp-Apim-Subscription-Region", region)
                    .addHeader("Content-Type", "application/json")
                    .post(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.builtins.MapSerializer(kotlinx.serialization.builtins.serializer(), kotlinx.serialization.builtins.serializer())), requestBody).toRequestBody("application/json".toMediaType()))
                    .build()
                
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                
                if (response.isSuccessful) {
                    val translateResponse = json.decodeFromString<List<AzureTranslateResponse>>(responseBody)
                    val translatedText = translateResponse.firstOrNull()?.translations?.firstOrNull()?.text ?: ""
                    log.d(tag = TAG) { "Azure translation successful: $translatedText" }
                    return@withContext translatedText
                } else {
                    log.e(tag = TAG) { "Azure translation failed: ${response.code} - $responseBody" }
                    return@withContext ""
                }
                
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error with Azure translation: ${e.message}" }
                return@withContext ""
            }
        }
    }

    override suspend fun generateSpeech(text: String, language: String, voice: String?): ByteArray {
        return withContext(Dispatchers.IO) {
            try {
                log.d(tag = TAG) { "Generating speech with Azure Speech Services" }
                
                val selectedVoice = voice ?: getDefaultVoiceForLanguage(language)
                val url = "https://$region.tts.speech.microsoft.com/cognitiveservices/v1"
                
                val ssml = """
                    <speak version='1.0' xml:lang='${mapLanguageCode(language)}'>
                        <voice xml:lang='${mapLanguageCode(language)}' name='$selectedVoice'>
                            $text
                        </voice>
                    </speak>
                """.trimIndent()
                
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Ocp-Apim-Subscription-Key", apiKey)
                    .addHeader("Content-Type", "application/ssml+xml")
                    .addHeader("X-Microsoft-OutputFormat", "audio-16khz-32kbitrate-mono-mp3")
                    .post(RequestBody.create("application/ssml+xml".toMediaType(), ssml))
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val audioBytes = response.body?.bytes() ?: byteArrayOf()
                    log.d(tag = TAG) { "Azure TTS successful (${audioBytes.size} bytes)" }
                    return@withContext audioBytes
                } else {
                    val errorBody = response.body?.string() ?: ""
                    log.e(tag = TAG) { "Azure TTS failed: ${response.code} - $errorBody" }
                    return@withContext byteArrayOf()
                }
                
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error with Azure TTS: ${e.message}" }
                return@withContext byteArrayOf()
            }
        }
    }

    override suspend fun detectLanguage(audioData: ByteArray): String? {
        return withContext(Dispatchers.IO) {
            try {
                log.d(tag = TAG) { "Detecting language with Azure Speech Services" }
                
                val url = "https://$region.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1"
                
                val request = Request.Builder()
                    .url("$url?language=auto-detect&format=simple")
                    .addHeader("Ocp-Apim-Subscription-Key", apiKey)
                    .addHeader("Content-Type", "audio/wav")
                    .post(RequestBody.create("audio/wav".toMediaType(), audioData))
                    .build()
                
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                
                if (response.isSuccessful) {
                    val speechResponse = json.decodeFromString<AzureSpeechResponse>(responseBody)
                    return@withContext speechResponse.Language
                } else {
                    return@withContext null
                }
                
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error detecting language with Azure: ${e.message}" }
                return@withContext null
            }
        }
    }

    override suspend fun getAvailableVoices(language: String): List<Voice> {
        return when (mapLanguageCode(language)) {
            "en-US" -> listOf(
                Voice("en-US-AriaNeural", "Aria Neural (Female)", "en-US", VoiceGender.FEMALE, VoiceQuality.NEURAL),
                Voice("en-US-GuyNeural", "Guy Neural (Male)", "en-US", VoiceGender.MALE, VoiceQuality.NEURAL),
                Voice("en-US-JennyNeural", "Jenny Neural (Female)", "en-US", VoiceGender.FEMALE, VoiceQuality.NEURAL)
            )
            "es-ES" -> listOf(
                Voice("es-ES-ElviraNeural", "Elvira Neural (Female)", "es-ES", VoiceGender.FEMALE, VoiceQuality.NEURAL),
                Voice("es-ES-AlvaroNeural", "Alvaro Neural (Male)", "es-ES", VoiceGender.MALE, VoiceQuality.NEURAL)
            )
            else -> listOf(
                Voice("$language-Standard", "Standard Voice", language, VoiceGender.NEUTRAL, VoiceQuality.STANDARD)
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
            "en-US" -> "en-US-AriaNeural"
            "es-ES" -> "es-ES-ElviraNeural"
            "fr-FR" -> "fr-FR-DeniseNeural"
            "de-DE" -> "de-DE-KatjaNeural"
            else -> "$language-Standard"
        }
    }

    override fun dispose() {
        // Limpiar recursos
    }

    // Modelos de datos para Azure APIs
    @Serializable
    private data class AzureSpeechResponse(
        val RecognitionStatus: String,
        val DisplayText: String? = null,
        val Language: String? = null
    )

    @Serializable
    private data class AzureTranslateResponse(
        val translations: List<AzureTranslation>
    )

    @Serializable
    private data class AzureTranslation(
        val text: String,
        val to: String
    )
}