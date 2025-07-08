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
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Implementación del servicio de traducción usando OpenAI
 * 
 * @author Eddys Larez
 */
class OpenAITranslationService(
    private val apiKey: String
) : TranslationService {
    
    companion object {
        private const val TAG = "OpenAITranslationService"
        private const val BASE_URL = "https://api.openai.com/v1"
        private const val WHISPER_MODEL = "whisper-1"
        private const val TTS_MODEL = "tts-1"
        private const val TRANSLATION_MODEL = "gpt-4"
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
                log.d(tag = TAG) { "Transcribing audio (${audioData.size} bytes) in language: $language" }
                
                // Crear archivo temporal para el audio
                val tempFile = File.createTempFile("audio_", ".wav")
                tempFile.writeBytes(audioData)
                
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file", 
                        tempFile.name,
                        RequestBody.create("audio/wav".toMediaType(), tempFile)
                    )
                    .addFormDataPart("model", WHISPER_MODEL)
                    .addFormDataPart("language", language)
                    .addFormDataPart("response_format", "json")
                    .build()
                
                val request = Request.Builder()
                    .url("$BASE_URL/audio/transcriptions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(requestBody)
                    .build()
                
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                
                // Limpiar archivo temporal
                tempFile.delete()
                
                if (response.isSuccessful) {
                    val transcriptionResponse = json.decodeFromString<TranscriptionResponse>(responseBody)
                    log.d(tag = TAG) { "Transcription successful: ${transcriptionResponse.text}" }
                    return@withContext transcriptionResponse.text
                } else {
                    log.e(tag = TAG) { "Transcription failed: ${response.code} - $responseBody" }
                    return@withContext ""
                }
                
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error transcribing audio: ${e.message}" }
                return@withContext ""
            }
        }
    }

    override suspend fun translateText(text: String, sourceLanguage: String, targetLanguage: String): String {
        return withContext(Dispatchers.IO) {
            try {
                log.d(tag = TAG) { "Translating text from $sourceLanguage to $targetLanguage: $text" }
                
                val prompt = """
                    Translate the following text from $sourceLanguage to $targetLanguage. 
                    Only return the translation, no explanations:
                    
                    $text
                """.trimIndent()
                
                val requestBody = ChatCompletionRequest(
                    model = TRANSLATION_MODEL,
                    messages = listOf(
                        ChatMessage(
                            role = "user",
                            content = prompt
                        )
                    ),
                    max_tokens = 150,
                    temperature = 0.3
                )
                
                val request = Request.Builder()
                    .url("$BASE_URL/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(json.encodeToString(ChatCompletionRequest.serializer(), requestBody).toRequestBody("application/json".toMediaType()))
                    .build()
                
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                
                if (response.isSuccessful) {
                    val chatResponse = json.decodeFromString<ChatCompletionResponse>(responseBody)
                    val translatedText = chatResponse.choices.firstOrNull()?.message?.content?.trim() ?: ""
                    log.d(tag = TAG) { "Translation successful: $translatedText" }
                    return@withContext translatedText
                } else {
                    log.e(tag = TAG) { "Translation failed: ${response.code} - $responseBody" }
                    return@withContext ""
                }
                
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error translating text: ${e.message}" }
                return@withContext ""
            }
        }
    }

    override suspend fun generateSpeech(text: String, language: String, voice: String?): ByteArray {
        return withContext(Dispatchers.IO) {
            try {
                log.d(tag = TAG) { "Generating speech for text: $text (language: $language, voice: $voice)" }
                
                val selectedVoice = voice ?: getDefaultVoiceForLanguage(language)
                
                val requestBody = TTSRequest(
                    model = TTS_MODEL,
                    input = text,
                    voice = selectedVoice,
                    response_format = "mp3"
                )
                
                val request = Request.Builder()
                    .url("$BASE_URL/audio/speech")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(json.encodeToString(TTSRequest.serializer(), requestBody).toRequestBody("application/json".toMediaType()))
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val audioBytes = response.body?.bytes() ?: byteArrayOf()
                    log.d(tag = TAG) { "Speech generation successful (${audioBytes.size} bytes)" }
                    return@withContext audioBytes
                } else {
                    val errorBody = response.body?.string() ?: ""
                    log.e(tag = TAG) { "Speech generation failed: ${response.code} - $errorBody" }
                    return@withContext byteArrayOf()
                }
                
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error generating speech: ${e.message}" }
                return@withContext byteArrayOf()
            }
        }
    }

    override suspend fun detectLanguage(audioData: ByteArray): String? {
        return withContext(Dispatchers.IO) {
            try {
                log.d(tag = TAG) { "Detecting language from audio (${audioData.size} bytes)" }
                
                // Usar Whisper para detectar idioma
                val tempFile = File.createTempFile("audio_detect_", ".wav")
                tempFile.writeBytes(audioData)
                
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "file", 
                        tempFile.name,
                        RequestBody.create("audio/wav".toMediaType(), tempFile)
                    )
                    .addFormDataPart("model", WHISPER_MODEL)
                    .addFormDataPart("response_format", "verbose_json")
                    .build()
                
                val request = Request.Builder()
                    .url("$BASE_URL/audio/transcriptions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(requestBody)
                    .build()
                
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                
                tempFile.delete()
                
                if (response.isSuccessful) {
                    val transcriptionResponse = json.decodeFromString<VerboseTranscriptionResponse>(responseBody)
                    val detectedLanguage = transcriptionResponse.language
                    log.d(tag = TAG) { "Language detection successful: $detectedLanguage" }
                    return@withContext detectedLanguage
                } else {
                    log.e(tag = TAG) { "Language detection failed: ${response.code} - $responseBody" }
                    return@withContext null
                }
                
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error detecting language: ${e.message}" }
                return@withContext null
            }
        }
    }

    override suspend fun getAvailableVoices(language: String): List<Voice> {
        // OpenAI TTS tiene voces predefinidas
        return when (language.lowercase()) {
            "en", "english" -> listOf(
                Voice("alloy", "Alloy", "en", VoiceGender.NEUTRAL, VoiceQuality.NEURAL),
                Voice("echo", "Echo", "en", VoiceGender.MALE, VoiceQuality.NEURAL),
                Voice("fable", "Fable", "en", VoiceGender.NEUTRAL, VoiceQuality.NEURAL),
                Voice("onyx", "Onyx", "en", VoiceGender.MALE, VoiceQuality.NEURAL),
                Voice("nova", "Nova", "en", VoiceGender.FEMALE, VoiceQuality.NEURAL),
                Voice("shimmer", "Shimmer", "en", VoiceGender.FEMALE, VoiceQuality.NEURAL)
            )
            "es", "spanish" -> listOf(
                Voice("alloy", "Alloy (ES)", "es", VoiceGender.NEUTRAL, VoiceQuality.NEURAL),
                Voice("nova", "Nova (ES)", "es", VoiceGender.FEMALE, VoiceQuality.NEURAL),
                Voice("onyx", "Onyx (ES)", "es", VoiceGender.MALE, VoiceQuality.NEURAL)
            )
            else -> listOf(
                Voice("alloy", "Alloy", language, VoiceGender.NEUTRAL, VoiceQuality.NEURAL)
            )
        }
    }

    private fun getDefaultVoiceForLanguage(language: String): String {
        return when (language.lowercase()) {
            "en", "english" -> "alloy"
            "es", "spanish" -> "nova"
            "fr", "french" -> "shimmer"
            "de", "german" -> "echo"
            "it", "italian" -> "fable"
            "pt", "portuguese" -> "nova"
            else -> "alloy"
        }
    }

    override fun dispose() {
        // Limpiar recursos si es necesario
    }

    // Modelos de datos para las APIs de OpenAI
    @Serializable
    private data class TranscriptionResponse(
        val text: String
    )

    @Serializable
    private data class VerboseTranscriptionResponse(
        val text: String,
        val language: String
    )

    @Serializable
    private data class ChatCompletionRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val max_tokens: Int,
        val temperature: Double
    )

    @Serializable
    private data class ChatMessage(
        val role: String,
        val content: String
    )

    @Serializable
    private data class ChatCompletionResponse(
        val choices: List<ChatChoice>
    )

    @Serializable
    private data class ChatChoice(
        val message: ChatMessage
    )

    @Serializable
    private data class TTSRequest(
        val model: String,
        val input: String,
        val voice: String,
        val response_format: String = "mp3"
    )
}