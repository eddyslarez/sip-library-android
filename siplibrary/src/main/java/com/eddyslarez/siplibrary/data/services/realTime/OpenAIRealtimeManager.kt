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
 * Manager for OpenAI Realtime API integration for audio translation (OPTIMIZED)
 */
class OpenAIRealtimeManager(
    private val apiKey: String,
    private val model: String = "gpt-4o-realtime-preview-2025-06-03"
) {
    private val TAG = "OpenAIRealtimeManager"

    // WebSocket client optimizado
    private var webSocket: WebSocket? = null
    private var httpClient: OkHttpClient? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Configuration optimizada
    private var targetLanguage: String = "es"
    private var translationQuality: WebRtcManager.TranslationQuality = WebRtcManager.TranslationQuality.HIGH
    private var isEnabled = AtomicBoolean(false)
    private var isProcessing = AtomicBoolean(false)

    // Statistics
    private val totalTranslations = AtomicInteger(0)
    private val totalLatency = AtomicLong(0)
    private val successfulTranslations = AtomicInteger(0)
    private val lastTranslationTime = AtomicLong(0)

    // Audio processing optimizado
    private var audioQueue = mutableListOf<ByteArray>()
    private var processJob: Job? = null
    private var sessionId: String? = null

    // Callbacks
    private var translationListener: TranslationListener? = null

    // Optimized supported languages
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

    /**
     * Initialize with optimized settings
     */
    fun initialize(): Boolean {
        return try {
            // Cliente HTTP optimizado para baja latencia
            httpClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)
                .build()

            connectToOpenAI()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize OpenAI Realtime Manager", e)
            false
        }
    }

    /**
     * Connect to OpenAI with optimized settings
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
                    initializeOptimizedSession()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleRealtimeMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleRealtimeAudio(bytes.toByteArray())
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "OpenAI connection closed: $reason")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "OpenAI connection failed", t)
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
     * Initialize session with optimized translation settings
     */
    private fun initializeOptimizedSession() {
        sessionId = "session_${System.currentTimeMillis()}_${Random.nextInt(1000)}"

        val sessionConfig = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("modalities", org.json.JSONArray().apply {
                    put("audio")
                    put("text")
                })

                // CRÍTICO: Instrucciones optimizadas para traducción pura
                put("instructions", """
                    You are a professional real-time audio translator. 
                    Your ONLY job is to translate spoken audio to $targetLanguage.
                    
                    Rules:
                    1. Translate EXACTLY what is said, nothing more, nothing less
                    2. Do NOT add greetings, comments, or explanations
                    3. Do NOT say "I will translate" or similar phrases
                    4. Do NOT add context or interpretations
                    5. Maintain the original tone and emotion
                    6. Translate in real-time as fast as possible
                    7. Use natural, fluent speech in the target language
                    8. If you hear silence, respond with silence
                    9. If unclear, translate your best approximation
                    10. NEVER break character as a translator
                    
                    Simply translate the audio content directly to $targetLanguage.
                """.trimIndent())

                put("voice", "ash") // o "alloy", "echo", etc.
                // Voz más natural y rápida
                put("input_audio_format", "pcm16")
                put("output_audio_format", "pcm16")

                // Configuración optimizada para baja latencia
                put("input_audio_transcription", JSONObject().apply {
                    put("model", "whisper-1")
                })

                // VAD optimizado para respuesta rápida
                put("turn_detection", JSONObject().apply {
                    put("type", "server_vad")
                    put("threshold", 0.3) // Más sensible
                    put("prefix_padding_ms", 100) // Menos padding
                    put("silence_duration_ms", 300) // Detección más rápida
                })

                put("tools", org.json.JSONArray())
                put("tool_choice", "none")
                put("temperature", 0.6) // Más determinista para traducción
                put("max_response_output_tokens", 1000) // Limitar para respuesta rápida
            })
        }

        webSocket?.send(sessionConfig.toString())
    }

    /**
     * Handle realtime messages with optimized processing
     */
    private fun handleRealtimeMessage(message: String) {
        try {
            val json = JSONObject(message)
            val type = json.getString("type")

            when (type) {
                "session.created" -> {
                    Log.d(TAG, "Optimized session created")
                    isEnabled.set(true)
                    translationListener?.onTranslationStateChanged(true, targetLanguage)
                }

                "session.updated" -> {
                    Log.d(TAG, "Session updated for optimized translation")
                }

                "response.created" -> {
                    Log.d(TAG, "Translation response created")
                    isProcessing.set(true)
                    translationListener?.onProcessingStateChanged(true)
                }

                "response.audio.delta" -> {
                    // Audio chunk received - procesado inmediato
                    if (json.has("delta")) {
                        val audioData = json.getString("delta")
                        handleTranslatedAudioChunk(audioData)
                    }
                }

                "response.audio.done" -> {
                    // Audio response completed
                    Log.d(TAG, "Translation audio completed")
                    handleTranslationCompleted()
                }

                "response.done" -> {
                    Log.d(TAG, "Translation response done")
                    isProcessing.set(false)
                    translationListener?.onProcessingStateChanged(false)
                }

                "error" -> {
                    val error = json.optJSONObject("error")
                    val errorMessage = error?.optString("message") ?: "Unknown error"
                    Log.e(TAG, "OpenAI API error: $errorMessage")
                    translationListener?.onTranslationFailed(errorMessage)
                }

                "input_audio_buffer.speech_started" -> {
                    Log.d(TAG, "Speech detected - starting translation")
                }

                "input_audio_buffer.speech_stopped" -> {
                    Log.d(TAG, "Speech stopped - processing translation")
                }

                else -> {
                    Log.d(TAG, "Message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling realtime message", e)
        }
    }

    /**
     * Handle translated audio chunks with immediate processing
     */
    private fun handleTranslatedAudioChunk(audioData: String) {
        try {
            val audioBytes = android.util.Base64.decode(audioData, android.util.Base64.DEFAULT)

            synchronized(audioQueue) {
                audioQueue.add(audioBytes)
            }

            // Procesamiento inmediato para menor latencia
            if (audioQueue.size >= 1) {
                processAudioQueue()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling translated audio chunk", e)
        }
    }

    /**
     * Process audio queue immediately
     */
    private fun processAudioQueue() {
        try {
            val chunks = mutableListOf<ByteArray>()
            synchronized(audioQueue) {
                chunks.addAll(audioQueue)
                audioQueue.clear()
            }

            if (chunks.isNotEmpty()) {
                val combinedAudio = combineAudioChunks(chunks)

                // Calcular latencia
                val currentTime = System.currentTimeMillis()
                val latency = currentTime - lastTranslationTime.get()

                // Actualizar estadísticas
                totalTranslations.incrementAndGet()
                totalLatency.addAndGet(latency)
                successfulTranslations.incrementAndGet()
                lastTranslationTime.set(currentTime)

                // Enviar audio traducido inmediatamente
                translationListener?.onTranslationCompleted(ByteArray(0), combinedAudio, latency)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio queue", e)
        }
    }

    /**
     * Combine audio chunks efficiently
     */
    private fun combineAudioChunks(chunks: List<ByteArray>): ByteArray {
        val totalSize = chunks.sumOf { it.size }
        val combined = ByteArray(totalSize)
        var offset = 0

        chunks.forEach { chunk ->
            System.arraycopy(chunk, 0, combined, offset, chunk.size)
            offset += chunk.size
        }

        return combined
    }

    /**
     * Handle translation completion
     */
    private fun handleTranslationCompleted() {
        try {
            // Procesar cualquier audio restante
            if (audioQueue.isNotEmpty()) {
                processAudioQueue()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling translation completion", e)
        }
    }

    /**
     * Process audio for translation with optimized settings
     */
    fun processAudioForTranslation(audioData: ByteArray): Boolean {
        if (!isEnabled.get() || webSocket == null) {
            return false
        }

        return try {
            lastTranslationTime.set(System.currentTimeMillis())

            // Convertir audio a base64
            val audioBase64 = android.util.Base64.encodeToString(audioData, android.util.Base64.NO_WRAP)

            // Enviar audio directamente para traducción inmediata
            val audioMessage = JSONObject().apply {
                put("type", "input_audio_buffer.append")
                put("audio", audioBase64)
            }

            webSocket?.send(audioMessage.toString())

            // Solicitar respuesta inmediata
            val commitMessage = JSONObject().apply {
                put("type", "input_audio_buffer.commit")
            }

            webSocket?.send(commitMessage.toString())

            // Crear respuesta optimizada
            val responseRequest = JSONObject().apply {
                put("type", "response.create")
                put("response", JSONObject().apply {
                    put("modalities", org.json.JSONArray().apply {
                        put("audio")
                        put("text")
                    })
                    // Sin instrucciones adicionales para máxima velocidad
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
     * Enable translation with optimized settings
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

        // Actualizar sesión inmediatamente
        updateSessionLanguage(targetLanguage)
        return true
    }

    /**
     * Update session language with optimized instructions
     */
    private fun updateSessionLanguage(language: String) {
        val sessionUpdate = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("instructions", """
                    Translate audio to $language. 
                    Rules: Only translate, no comments, no additions, maintain original meaning.
                    Respond immediately with natural $language speech.
                """.trimIndent())
                put("voice", "ash")
                put("temperature", 0.6)
            })
        }

        webSocket?.send(sessionUpdate.toString())
    }

    /**
     * Disable translation
     */
    fun disable(): Boolean {
        isEnabled.set(false)
        webSocket?.close(1000, "Translation disabled")
        webSocket = null

        synchronized(audioQueue) {
            audioQueue.clear()
        }

        translationListener?.onTranslationStateChanged(false, null)
        return true
    }

    /**
     * Set translation listener
     */
    fun setTranslationListener(listener: TranslationListener?) {
        this.translationListener = listener
    }

    /**
     * Get optimized translation statistics
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

    fun isEnabled(): Boolean = isEnabled.get()
    fun isProcessing(): Boolean = isProcessing.get()
    fun getCurrentTargetLanguage(): String = targetLanguage
    fun getSupportedLanguages(): List<String> = supportedLanguages.toList()

    /**
     * Set translation quality with optimized settings
     */
    fun setTranslationQuality(quality: WebRtcManager.TranslationQuality): Boolean {
        this.translationQuality = quality

        val (temperature, voice) = when (quality) {
            WebRtcManager.TranslationQuality.LOW -> Pair(0.6, "alloy") // <-- mínimo permitido
            WebRtcManager.TranslationQuality.MEDIUM -> Pair(0.7, "echo")
            WebRtcManager.TranslationQuality.HIGH -> Pair(0.8, "ash")
        }


        val sessionUpdate = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("temperature", temperature)
                put("voice", voice)
            })
        }

        webSocket?.send(sessionUpdate.toString())
        return true
    }
    /**
     * Handle incoming audio data from WebRTC
     */
    private fun handleRealtimeAudio(audioData: ByteArray) {
        // This method would be called when receiving audio from the remote party
        // For now, we'll just log it
        Log.d(TAG, "Received audio data for translation: ${audioData.size} bytes")
    }
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
    }
}
