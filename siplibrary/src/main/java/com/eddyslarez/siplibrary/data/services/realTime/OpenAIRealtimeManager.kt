package com.eddyslarez.siplibrary.data.services.realTime

import android.util.Log
import com.eddyslarez.siplibrary.data.services.audio.WebRtcManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Manager for OpenAI Realtime API integration for audio translation
 *
 * @author Eddys Larez
 */
class OpenAIRealtimeManager(
    private val apiKey: String,
    private val model: String = "gpt-4o-realtime-preview-2025-06-03"
) {
    private val TAG = "OpenAIRealtimeManager"

    // WebSocket client
    private var webSocket: WebSocket? = null
    private var httpClient: OkHttpClient? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Configuration
    private var targetLanguage: String = "es" // Default to Spanish
    private var translationQuality: WebRtcManager.TranslationQuality = WebRtcManager.TranslationQuality.MEDIUM
    private var isEnabled = AtomicBoolean(false)
    private var isProcessing = AtomicBoolean(false)

    // Statistics
    private val totalTranslations = AtomicInteger(0)
    private val totalLatency = AtomicLong(0)
    private val successfulTranslations = AtomicInteger(0)
    private val lastTranslationTime = AtomicLong(0)

    // Audio processing
    private var audioQueue = mutableListOf<ByteArray>()
    private var processJob: Job? = null
    private var sessionId: String? = null
    private var pendingTranslations = mutableMapOf<String, TranslationRequest>()

    // Callbacks
    private var translationListener: TranslationListener? = null

    // Supported languages
    private val supportedLanguages = listOf(
        "es", "en", "fr", "de", "it", "pt", "ru", "ja", "ko", "zh",
        "ar", "hi", "th", "vi", "tr", "pl", "nl", "sv", "da", "no"
    )

    interface TranslationListener {
        fun onTranslationCompleted(originalAudio: ByteArray, translatedAudio: ByteArray, latency: Long)
        fun onTranslationFailed(error: String)
        fun onTranslationStateChanged(isEnabled: Boolean, targetLanguage: String?)
        fun onProcessingStateChanged(isProcessing: Boolean)
    }

    private data class TranslationRequest(
        val id: String,
        val originalAudio: ByteArray,
        val startTime: Long,
        val targetLanguage: String
    )

    /**
     * Initialize the OpenAI Realtime connection
     */
    fun initialize(): Boolean {
        return try {
            httpClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

            connectToOpenAI()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize OpenAI Realtime Manager", e)
            false
        }
    }

    /**
     * Connect to OpenAI Realtime API
     */
    private fun connectToOpenAI(): Boolean {
        return try {
            val request = Request.Builder()
                .url("wss://api.openai.com/v1/realtime?model=$model")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("OpenAI-Beta", "realtime=v1")
                .build()

            webSocket = httpClient?.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "Connected to OpenAI Realtime API")
                    initializeSession()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleRealtimeMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleRealtimeAudio(bytes.toByteArray())
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "OpenAI Realtime connection closed: $reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "OpenAI Realtime connection failed", t)
                    translationListener?.onTranslationFailed("Connection failed: ${t.message}")
                }
            })

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to OpenAI", e)
            false
        }
    }

    /**
     * Initialize session with OpenAI
     */
    private fun initializeSession() {
        sessionId = "session_${System.currentTimeMillis()}_${Random.nextInt(1000)}"

        val sessionConfig = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("modalities", org.json.JSONArray().apply {
                    put("text")
                    put("audio")
                })
                put("instructions", "You are a helpful assistant that translates audio to $targetLanguage. Always respond with audio in the target language.")
                put("voice", "alloy")
                put("input_audio_format", "pcm16")
                put("output_audio_format", "pcm16")
                put("input_audio_transcription", JSONObject().apply {
                    put("model", "whisper-1")
                })
                put("turn_detection", JSONObject().apply {
                    put("type", "server_vad")
                    put("threshold", 0.5)
                    put("prefix_padding_ms", 300)
                    put("silence_duration_ms", 500)
                })
                put("tools", org.json.JSONArray())
                put("tool_choice", "auto")
                put("temperature", 0.8)
                put("max_response_output_tokens", 4096)
            })
        }

        webSocket?.send(sessionConfig.toString())
    }

    /**
     * Handle realtime messages from OpenAI
     */
    private fun handleRealtimeMessage(message: String) {
        try {
            val json = JSONObject(message)
            val type = json.getString("type")

            when (type) {
                "session.created" -> {
                    Log.d(TAG, "Session created successfully")
                    isEnabled.set(true)
                    translationListener?.onTranslationStateChanged(true, targetLanguage)
                }
                "session.updated" -> {
                    Log.d(TAG, "Session updated successfully")
                }
                "conversation.item.created" -> {
                    Log.d(TAG, "Conversation item created")
                }
                "response.created" -> {
                    Log.d(TAG, "Response created")
                    isProcessing.set(true)
                    translationListener?.onProcessingStateChanged(true)
                }
                "response.done" -> {
                    Log.d(TAG, "Response completed")
                    isProcessing.set(false)
                    translationListener?.onProcessingStateChanged(false)
                }
                "response.audio.delta" -> {
                    // Audio chunk received
                    if (json.has("delta")) {
                        val audioData = json.getString("delta")
                        handleTranslatedAudioChunk(audioData)
                    }
                }
                "response.audio.done" -> {
                    // Audio response completed
                    Log.d(TAG, "Audio response completed")
                    handleTranslationCompleted()
                }
                "error" -> {
                    val error = json.optJSONObject("error")
                    val errorMessage = error?.optString("message") ?: "Unknown error"
                    Log.e(TAG, "OpenAI API error: $errorMessage")
                    translationListener?.onTranslationFailed(errorMessage)
                }
                "input_audio_buffer.speech_started" -> {
                    Log.d(TAG, "Speech detected in input audio")
                }
                "input_audio_buffer.speech_stopped" -> {
                    Log.d(TAG, "Speech stopped in input audio")
                }
                else -> {
                    Log.d(TAG, "Received message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling realtime message", e)
        }
    }

    /**
     * Handle translated audio chunks
     */
    private fun handleTranslatedAudioChunk(audioData: String) {
        try {
            // Decode base64 audio data
            val audioBytes = android.util.Base64.decode(audioData, android.util.Base64.DEFAULT)

            // Store audio chunk for assembly
            synchronized(audioQueue) {
                audioQueue.add(audioBytes)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling translated audio chunk", e)
        }
    }

    /**
     * Handle translation completion
     */
    private fun handleTranslationCompleted() {
        try {
            // Combine all audio chunks
            val totalSize = audioQueue.sumOf { it.size }
            val combinedAudio = ByteArray(totalSize)
            var offset = 0

            synchronized(audioQueue) {
                audioQueue.forEach { chunk ->
                    System.arraycopy(chunk, 0, combinedAudio, offset, chunk.size)
                    offset += chunk.size
                }
                audioQueue.clear()
            }

            // Calculate latency and update statistics
            val currentTime = System.currentTimeMillis()
            val latency = currentTime - lastTranslationTime.get()

            totalTranslations.incrementAndGet()
            totalLatency.addAndGet(latency)
            successfulTranslations.incrementAndGet()
            lastTranslationTime.set(currentTime)

            // Notify listener
            translationListener?.onTranslationCompleted(ByteArray(0), combinedAudio, latency)

        } catch (e: Exception) {
            Log.e(TAG, "Error handling translation completion", e)
            translationListener?.onTranslationFailed("Failed to process translated audio: ${e.message}")
        }
    }

    /**
     * Handle incoming audio data from WebRTC
     */
    private fun handleRealtimeAudio(audioData: ByteArray) {
        // This method would be called when receiving audio from the remote party
        // For now, we'll just log it
        Log.d(TAG, "Received audio data for translation: ${audioData.size} bytes")
    }

    /**
     * Process audio for translation
     */
    fun processAudioForTranslation(audioData: ByteArray): Boolean {
        if (!isEnabled.get() || webSocket == null) {
            Log.w(TAG, "Translation not enabled or not connected")
            return false
        }

        return try {
            lastTranslationTime.set(System.currentTimeMillis())

            // Convert audio to base64
            val audioBase64 = android.util.Base64.encodeToString(audioData, android.util.Base64.NO_WRAP)

            // Create conversation item with audio
            val audioItem = JSONObject().apply {
                put("type", "conversation.item.create")
                put("item", JSONObject().apply {
                    put("type", "message")
                    put("role", "user")
                    put("content", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "input_audio")
                            put("audio", audioBase64)
                        })
                    })
                })
            }

            // Send audio to OpenAI
            webSocket?.send(audioItem.toString())

            // Create response request
            val responseRequest = JSONObject().apply {
                put("type", "response.create")
                put("response", JSONObject().apply {
                    put("modalities", org.json.JSONArray().apply {
                        put("text")
                        put("audio")  // <-- Ambos deben estar presentes

                    })
                    put("instructions", "Translate the received audio to $targetLanguage and respond with audio in that language.")
                })
            }

            webSocket?.send(responseRequest.toString())

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio for translation", e)
            false
        }
    }

    /**
     * Enable translation
     */
    fun enable(targetLanguage: String): Boolean {
        if (!supportedLanguages.contains(targetLanguage)) {
            Log.e(TAG, "Unsupported language: $targetLanguage")
            return false
        }

        this.targetLanguage = targetLanguage

        if (webSocket == null) {
            return initialize()
        }

        // Update session with new language
        updateSessionLanguage(targetLanguage)
        return true
    }

    /**
     * Disable translation
     */
    fun disable(): Boolean {
        isEnabled.set(false)
        webSocket?.close(1000, "Translation disabled")
        webSocket = null

        translationListener?.onTranslationStateChanged(false, null)
        return true
    }

    /**
     * Update session language
     */
    private fun updateSessionLanguage(language: String) {
        val sessionUpdate = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("instructions", "You are a helpful assistant that translates audio to $language. Always respond with audio in the target language.")
            })
        }

        webSocket?.send(sessionUpdate.toString())
    }

    /**
     * Set translation listener
     */
    fun setTranslationListener(listener: TranslationListener?) {
        this.translationListener = listener
    }

    /**
     * Get translation statistics
     */
    fun getStats(): WebRtcManager.TranslationStats {
        val total = totalTranslations.get()
        val avgLatency = if (total > 0) totalLatency.get() / total else 0L
        val successRate = if (total > 0) successfulTranslations.get().toFloat() / total else 0f

        return WebRtcManager.TranslationStats(
            totalTranslations = total,
            averageLatency = avgLatency,
            successRate = successRate,
            lastTranslationTime = lastTranslationTime.get()
        )
    }

    /**
     * Check if translation is enabled
     */
    fun isEnabled(): Boolean = isEnabled.get()

    /**
     * Check if currently processing
     */
    fun isProcessing(): Boolean = isProcessing.get()

    /**
     * Get current target language
     */
    fun getCurrentTargetLanguage(): String = targetLanguage

    /**
     * Get supported languages
     */
    fun getSupportedLanguages(): List<String> = supportedLanguages.toList()

    /**
     * Set translation quality
     */
    fun setTranslationQuality(quality: WebRtcManager.TranslationQuality): Boolean {
        this.translationQuality = quality

        // Update session with quality settings
        val temperature = when (quality) {
            WebRtcManager.TranslationQuality.LOW -> 0.3
            WebRtcManager.TranslationQuality.MEDIUM -> 0.8
            WebRtcManager.TranslationQuality.HIGH -> 1.0
        }

        val sessionUpdate = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("temperature", temperature)
            })
        }

        webSocket?.send(sessionUpdate.toString())
        return true
    }

    /**
     * Get current translation quality
     */
    fun getTranslationQuality(): WebRtcManager.TranslationQuality = translationQuality

    /**
     * Dispose resources
     */
    fun dispose() {
        processJob?.cancel()
        webSocket?.close(1000, "Manager disposed")
        webSocket = null
        httpClient = null
        scope.cancel()

        isEnabled.set(false)
        isProcessing.set(false)

        synchronized(audioQueue) {
            audioQueue.clear()
        }

        pendingTranslations.clear()
    }
}
