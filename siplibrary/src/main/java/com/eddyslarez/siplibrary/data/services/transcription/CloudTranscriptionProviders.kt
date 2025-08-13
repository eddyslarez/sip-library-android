package com.eddyslarez.siplibrary.data.services.transcription

import android.os.Build
import androidx.annotation.RequiresApi
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Proveedores de transcripción en la nube para mayor precisión
 * 
 * @author Eddys Larez
 */

/**
 * Proveedor base para servicios de transcripción en la nube
 */
abstract class CloudTranscriptionProvider {
    protected val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    protected val json = Json { ignoreUnknownKeys = true }
    
    abstract suspend fun transcribeAudio(
        audioData: ByteArray,
        config: AudioTranscriptionService.TranscriptionConfig
    ): CloudTranscriptionResult?
    
    abstract fun startStreamingTranscription(
        config: AudioTranscriptionService.TranscriptionConfig,
        onResult: (CloudTranscriptionResult) -> Unit,
        onError: (String) -> Unit
    )
    
    abstract fun stopStreamingTranscription()
    abstract fun isStreamingActive(): Boolean
}

/**
 * Resultado de transcripción en la nube
 */
@Serializable
data class CloudTranscriptionResult(
    val text: String,
    val confidence: Float,
    val isFinal: Boolean,
    val alternatives: List<TranscriptionAlternative> = emptyList(),
    val wordTimestamps: List<WordTimestamp> = emptyList(),
    val languageCode: String = "",
    val speakerTag: Int? = null
)

@Serializable
data class TranscriptionAlternative(
    val text: String,
    val confidence: Float
)

@Serializable
data class WordTimestamp(
    val word: String,
    val startTime: String, // Formato: "1.200s"
    val endTime: String,   // Formato: "1.800s"
    val confidence: Float
)

/**
 * Proveedor de Google Cloud Speech-to-Text
 */
class GoogleCloudSpeechProvider(
    private val apiKey: String
) : CloudTranscriptionProvider() {
    
    private val TAG = "GoogleCloudSpeech"
    private val baseUrl = "https://speech.googleapis.com/v1/speech"
    
    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun transcribeAudio(
        audioData: ByteArray,
        config: AudioTranscriptionService.TranscriptionConfig
    ): CloudTranscriptionResult? {
        return try {
            val request = GoogleSpeechRequest(
                config = GoogleRecognitionConfig(
                    encoding = "LINEAR16",
                    sampleRateHertz = 16000,
                    languageCode = config.language,
                    enableWordTimeOffsets = true,
                    enableAutomaticPunctuation = config.enablePunctuation,
                    profanityFilter = config.enableProfanityFilter
                ),
                audio = GoogleAudioContent(
                    content = Base64.getEncoder().encodeToString(audioData)
                )
            )
            
            val response = makeHttpRequest("$baseUrl:recognize", request)
            parseGoogleResponse(response)
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in Google Cloud transcription: ${e.message}" }
            null
        }
    }
    
    override fun startStreamingTranscription(
        config: AudioTranscriptionService.TranscriptionConfig,
        onResult: (CloudTranscriptionResult) -> Unit,
        onError: (String) -> Unit
    ) {
        // Implementar streaming con WebSocket o gRPC
        log.d(tag = TAG) { "Starting Google Cloud streaming transcription" }
        
        scope.launch {
            try {
                // Implementación de streaming transcription
                // Esto requeriría configurar una conexión WebSocket o gRPC
                // con Google Cloud Speech-to-Text Streaming API
                
            } catch (e: Exception) {
                onError("Google Cloud streaming error: ${e.message}")
            }
        }
    }
    
    override fun stopStreamingTranscription() {
        log.d(tag = TAG) { "Stopping Google Cloud streaming transcription" }
    }
    
    override fun isStreamingActive(): Boolean = false
    
    private suspend fun makeHttpRequest(url: String, request: GoogleSpeechRequest): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        
        return try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.doOutput = true
            
            val requestJson = json.encodeToString(GoogleSpeechRequest.serializer(), request)
            connection.outputStream.use { it.write(requestJson.toByteArray()) }
            
            connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            
        } finally {
            connection.disconnect()
        }
    }
    
    private fun parseGoogleResponse(response: String): CloudTranscriptionResult? {
        return try {
            val googleResponse = json.decodeFromString(GoogleSpeechResponse.serializer(), response)
            
            val result = googleResponse.results?.firstOrNull()?.alternatives?.firstOrNull()
            if (result != null) {
                CloudTranscriptionResult(
                    text = result.transcript,
                    confidence = result.confidence,
                    isFinal = true,
                    alternatives = googleResponse.results.firstOrNull()?.alternatives?.drop(1)?.map {
                        TranscriptionAlternative(it.transcript, it.confidence)
                    } ?: emptyList(),
                    wordTimestamps = result.words?.map { word ->
                        WordTimestamp(
                            word = word.word,
                            startTime = word.startTime,
                            endTime = word.endTime,
                            confidence = word.confidence
                        )
                    } ?: emptyList(),
                    languageCode = googleResponse.results.firstOrNull()?.languageCode ?: ""
                )
            } else {
                null
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error parsing Google response: ${e.message}" }
            null
        }
    }
}

/**
 * Proveedor de Azure Cognitive Services Speech
 */
class AzureSpeechProvider(
    private val subscriptionKey: String,
    private val region: String
) : CloudTranscriptionProvider() {
    
    private val TAG = "AzureSpeech"
    private val baseUrl = "https://$region.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1"
    
    override suspend fun transcribeAudio(
        audioData: ByteArray,
        config: AudioTranscriptionService.TranscriptionConfig
    ): CloudTranscriptionResult? {
        return try {
            val url = "$baseUrl?language=${config.language}&format=detailed"
            val connection = URL(url).openConnection() as HttpURLConnection
            
            connection.requestMethod = "POST"
            connection.setRequestProperty("Ocp-Apim-Subscription-Key", subscriptionKey)
            connection.setRequestProperty("Content-Type", "audio/wav")
            connection.doOutput = true
            
            // Convertir audio a formato WAV
            val wavData = convertToWav(audioData)
            connection.outputStream.use { it.write(wavData) }
            
            val response = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            parseAzureResponse(response)
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in Azure transcription: ${e.message}" }
            null
        }
    }
    
    override fun startStreamingTranscription(
        config: AudioTranscriptionService.TranscriptionConfig,
        onResult: (CloudTranscriptionResult) -> Unit,
        onError: (String) -> Unit
    ) {
        log.d(tag = TAG) { "Starting Azure streaming transcription" }
        // Implementar streaming con WebSocket
    }
    
    override fun stopStreamingTranscription() {
        log.d(tag = TAG) { "Stopping Azure streaming transcription" }
    }
    
    override fun isStreamingActive(): Boolean = false
    
    private fun convertToWav(pcmData: ByteArray): ByteArray {
        val sampleRate = 16000
        val channels = 1
        val bitsPerSample = 16
        
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val fileSize = 36 + dataSize
        
        val output = ByteArrayOutputStream()
        
        // WAV header
        output.write("RIFF".toByteArray())
        output.write(intToByteArray(fileSize))
        output.write("WAVE".toByteArray())
        output.write("fmt ".toByteArray())
        output.write(intToByteArray(16)) // PCM format size
        output.write(shortToByteArray(1)) // PCM format
        output.write(shortToByteArray(channels.toShort()))
        output.write(intToByteArray(sampleRate))
        output.write(intToByteArray(byteRate))
        output.write(shortToByteArray(blockAlign.toShort()))
        output.write(shortToByteArray(bitsPerSample.toShort()))
        output.write("data".toByteArray())
        output.write(intToByteArray(dataSize))
        output.write(pcmData)
        
        return output.toByteArray()
    }
    
    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }
    
    private fun shortToByteArray(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() and 0xFF).toByte(),
            ((value.toInt() shr 8) and 0xFF).toByte()
        )
    }
    
    private fun parseAzureResponse(response: String): CloudTranscriptionResult? {
        return try {
            val azureResponse = json.decodeFromString(AzureSpeechResponse.serializer(), response)
            
            if (azureResponse.RecognitionStatus == "Success") {
                CloudTranscriptionResult(
                    text = azureResponse.DisplayText,
                    confidence = azureResponse.Confidence ?: 0.5f,
                    isFinal = true,
                    languageCode = azureResponse.Language ?: ""
                )
            } else {
                null
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error parsing Azure response: ${e.message}" }
            null
        }
    }
}

/**
 * Factory para crear proveedores de transcripción
 */
object TranscriptionProviderFactory {
    
    fun createProvider(
        provider: AudioTranscriptionService.TranscriptionProvider,
        apiKey: String = "",
        region: String = ""
    ): CloudTranscriptionProvider? {
        return when (provider) {
            AudioTranscriptionService.TranscriptionProvider.GOOGLE_CLOUD -> {
                if (apiKey.isNotEmpty()) {
                    GoogleCloudSpeechProvider(apiKey)
                } else {
                    null
                }
            }
            AudioTranscriptionService.TranscriptionProvider.AZURE_COGNITIVE -> {
                if (apiKey.isNotEmpty() && region.isNotEmpty()) {
                    AzureSpeechProvider(apiKey, region)
                } else {
                    null
                }
            }
            else -> null
        }
    }
}

// === MODELOS DE DATOS PARA GOOGLE CLOUD ===

@Serializable
data class GoogleSpeechRequest(
    val config: GoogleRecognitionConfig,
    val audio: GoogleAudioContent
)

@Serializable
data class GoogleRecognitionConfig(
    val encoding: String,
    val sampleRateHertz: Int,
    val languageCode: String,
    val enableWordTimeOffsets: Boolean = true,
    val enableAutomaticPunctuation: Boolean = true,
    val profanityFilter: Boolean = false,
    val maxAlternatives: Int = 1
)

@Serializable
data class GoogleAudioContent(
    val content: String // Base64 encoded audio
)

@Serializable
data class GoogleSpeechResponse(
    val results: List<GoogleRecognitionResult>?
)

@Serializable
data class GoogleRecognitionResult(
    val alternatives: List<GoogleSpeechAlternative>,
    val languageCode: String? = null
)

@Serializable
data class GoogleSpeechAlternative(
    val transcript: String,
    val confidence: Float,
    val words: List<GoogleWordInfo>? = null
)

@Serializable
data class GoogleWordInfo(
    val word: String,
    val startTime: String,
    val endTime: String,
    val confidence: Float
)

// === MODELOS DE DATOS PARA AZURE ===

@Serializable
data class AzureSpeechResponse(
    val RecognitionStatus: String,
    val DisplayText: String,
    val Confidence: Float? = null,
    val Language: String? = null,
    val Offset: Long? = null,
    val Duration: Long? = null
)