package com.eddyslarez.siplibrary.data.services.realTime

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.eddyslarez.siplibrary.data.services.audio.WebRtcManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Manager for OpenAI Realtime API integration for audio translation
 * VERSIÓN CORREGIDA: Solucionados problemas de audio y flujo de datos
 *
 * @author Eddys Larez
 */
class OpenAIRealtimeManager(
    private val apiKey: String,
    private val model: String = "gpt-4o-realtime-preview-2024-10-01"
) {
    private val TAG = "OpenAIRealtimeManager"

    // WebSocket client
    private var webSocket: WebSocket? = null
    private var httpClient: OkHttpClient? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Configuration
    private var targetLanguage: String = "es" // Default to Spanish
    private var translationQuality: WebRtcManager.TranslationQuality =
        WebRtcManager.TranslationQuality.MEDIUM
    private var isEnabled = AtomicBoolean(false)
    private var isProcessing = AtomicBoolean(false)

    // CORREGIDO: Control de estado más robusto
    private var isSessionReady = AtomicBoolean(false)
    private var isWaitingForResponse = AtomicBoolean(false)
    private var lastRequestTime = AtomicLong(0)
    private val minRequestInterval = 1500L // Reducido para mejor fluidez

    // CORREGIDO: Usar concurrent collections para thread safety
    private val audioChunksBuffer = ConcurrentLinkedQueue<ByteArray>()
    private var currentAudioBuffer = ByteArrayOutputStream()
    private val audioBufferLock = Mutex()

    // Statistics
    private val totalTranslations = AtomicInteger(0)
    private val totalLatency = AtomicLong(0)
    private val successfulTranslations = AtomicInteger(0)
    private val lastTranslationTime = AtomicLong(0)

    // Audio processing - CORREGIDO: Parámetros optimizados para OpenAI
    private val minAudioSizeBytes = 1600 // ~50ms de audio PCM 16-bit 16kHz mono
    private val maxAudioSizeBytes = 16000 // ~500ms máximo para mejor latencia
    private val targetSampleRate = 24000 // OpenAI prefiere 24kHz

    // Session management
    private var sessionId: String? = null
    private var conversationId: String? = null

    // Callbacks
    private var translationListener: TranslationListener? = null

    // Supported languages
    private val supportedLanguages = listOf(
        "es", "en", "fr", "de", "it", "pt", "ru", "ja", "ko", "zh",
        "ar", "hi", "th", "vi", "tr", "pl", "nl", "sv", "da", "no"
    )

    interface TranslationListener {
        fun onTranslationCompleted(
            originalAudio: ByteArray,
            translatedAudio: ByteArray,
            latency: Long
        )

        fun onTranslationFailed(error: String)
        fun onTranslationStateChanged(isEnabled: Boolean, targetLanguage: String?)
        fun onProcessingStateChanged(isProcessing: Boolean)
        fun onSessionReady()
    }

    /**
     * Initialize the OpenAI Realtime connection
     */
    fun initialize(): Boolean {
        return try {
            httpClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.SECONDS) // Sin timeout para websocket
                .writeTimeout(10, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS) // Keep alive
                .build()

            connectToOpenAI()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize OpenAI Realtime Manager", e)
            false
        }
    }

    /**
     * CORREGIDO: Connect to OpenAI Realtime API con mejor manejo de errores
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
                    Log.i(TAG, "✅ Connected to OpenAI Realtime API")
                    scope.launch {
                        delay(500) // Dar tiempo a que se estabilice la conexión
                        initializeSession()
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    scope.launch {
                        handleRealtimeMessage(text)
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    scope.launch {
                        handleRealtimeAudio(bytes.toByteArray())
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.w(TAG, "OpenAI Realtime connection closed: $code - $reason")
                    resetState()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "❌ OpenAI Realtime connection failed", t)
                    resetState()
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
     * CORREGIDO: Reset state on connection issues
     */
    private fun resetState() {
        isSessionReady.set(false)
        isWaitingForResponse.set(false)
        isProcessing.set(false)
        audioChunksBuffer.clear()
        scope.launch {
            audioBufferLock.withLock {
                currentAudioBuffer.reset()
            }
        }
    }

    /**
     * CORREGIDO: Initialize session con configuración específica para traducción
     */
    private fun initializeSession() {
        sessionId = "session_${System.currentTimeMillis()}_${Random.nextInt(10000)}"

        Log.d(TAG, "🔧 Initializing session: $sessionId")

        val sessionConfig = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("modalities", JSONArray().apply {
                    put("text")
                    put("audio")
                })

                // CORREGIDO: Instrucciones más específicas
                put(
                    "instructions", """
                    You are a real-time audio translator. Translate speech to $targetLanguage.
                    
                    Rules:
                    - Only output translated speech, no explanations
                    - Maintain original tone and emotion  
                    - Use natural pronunciation
                    - If audio is unclear, stay silent
                    - Translate immediately when speech ends
                """.trimIndent()
                )

                put("voice", "alloy") // Voz más estable para traducción
                put("input_audio_format", "pcm16")
                put("output_audio_format", "pcm16")
                put("input_audio_transcription", JSONObject().apply {
                    put("model", "whisper-1")
                })

                // CORREGIDO: VAD más sensible pero no tanto que capture ruido
                put("turn_detection", JSONObject().apply {
                    put("type", "server_vad")
                    put("threshold", 0.5) // Más sensible
                    put("prefix_padding_ms", 300)
                    put("silence_duration_ms", 700) // Menor pausa para respuesta rápida
                })

                put("tools", JSONArray()) // Sin herramientas
                put("tool_choice", "none")
                put("temperature", 0.7)
                put("max_response_output_tokens", 200)
            })
        }

        val success = webSocket?.send(sessionConfig.toString()) ?: false
        if (!success) {
            Log.e(TAG, "❌ Failed to send session config")
            translationListener?.onTranslationFailed("Failed to initialize session")
        }
    }

    /**
     * CORREGIDO: Handle realtime messages con mejor logging
     */
    @SuppressLint("NewApi")
    private suspend fun handleRealtimeMessage(message: String) {
        try {
            val json = JSONObject(message)
            val type = json.getString("type")

            when (type) {
                "session.created" -> {
                    Log.i(TAG, "✅ Session created successfully")
                    isSessionReady.set(true)
                    isEnabled.set(true)
                    translationListener?.onSessionReady()
                    translationListener?.onTranslationStateChanged(true, targetLanguage)
                }

                "session.updated" -> {
                    Log.i(TAG, "✅ Session updated successfully")
                }

                "input_audio_buffer.speech_started" -> {
                    Log.d(TAG, "🎤 Speech started")
                    isProcessing.set(true)
                    translationListener?.onProcessingStateChanged(true)
                }

                "input_audio_buffer.speech_stopped" -> {
                    Log.d(TAG, "🎤 Speech stopped")
                }

                "conversation.item.created" -> {
                    val item = json.optJSONObject("item")
                    val itemType = item?.optString("type")
                    Log.d(TAG, "📝 Item created: $itemType")
                }

                "response.created" -> {
                    Log.d(TAG, "🤖 Response created")
                    // Limpiar buffer para nueva respuesta
                    audioChunksBuffer.clear()
                }

                "response.output_item.added" -> {
                    val item = json.optJSONObject("item")
                    val itemType = item?.optString("type")
                    Log.d(TAG, "➕ Output item added: $itemType")
                }

                "response.audio.delta" -> {
                    if (json.has("delta")) {
                        val audioData = json.getString("delta")
                        handleTranslatedAudioChunk(audioData)
                    }
                }

                "response.audio.done" -> {
                    Log.i(TAG, "🔊 Audio response completed")
                    handleTranslationCompleted()
                }

                "response.done" -> {
                    Log.i(TAG, "✅ Response completed")
                    isProcessing.set(false)
                    isWaitingForResponse.set(false)
                    translationListener?.onProcessingStateChanged(false)
                }

                "error" -> {
                    val error = json.optJSONObject("error")
                    val errorMessage = error?.optString("message") ?: "Unknown error"
                    val errorCode = error?.optString("code") ?: "unknown"
                    Log.e(TAG, "❌ API Error [$errorCode]: $errorMessage")

                    isWaitingForResponse.set(false)
                    isProcessing.set(false)
                    translationListener?.onTranslationFailed("API Error: $errorMessage")
                }

                else -> {
                    Log.d(TAG, "📨 Message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling realtime message: $message", e)
        }
    }

    /**
     * CORREGIDO: Handle translated audio chunks
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleTranslatedAudioChunk(audioData: String) {
        try {
            val audioBytes = Base64.getDecoder().decode(audioData)
            Log.v(TAG, "🔊 Audio chunk: ${audioBytes.size} bytes")

            audioChunksBuffer.offer(audioBytes)

        } catch (e: Exception) {
            Log.e(TAG, "Error handling translated audio chunk", e)
        }
    }

    /**
     * CORREGIDO: Handle translation completion
     */
    private fun handleTranslationCompleted() {
        try {
            // Combinar todos los chunks de audio
            val chunks = mutableListOf<ByteArray>()
            while (audioChunksBuffer.isNotEmpty()) {
                audioChunksBuffer.poll()?.let { chunks.add(it) }
            }

            if (chunks.isEmpty()) {
                Log.w(TAG, "⚠️ No audio chunks received")
                return
            }

            val totalSize = chunks.sumOf { it.size }
            val combinedAudio = ByteArray(totalSize)
            var offset = 0

            chunks.forEach { chunk ->
                System.arraycopy(chunk, 0, combinedAudio, offset, chunk.size)
                offset += chunk.size
            }

            Log.i(TAG, "✅ Translation completed: ${combinedAudio.size} bytes")

            // Calcular latencia
            val currentTime = System.currentTimeMillis()
            val latency = currentTime - lastTranslationTime.get()

            // Actualizar estadísticas
            totalTranslations.incrementAndGet()
            totalLatency.addAndGet(latency)
            successfulTranslations.incrementAndGet()

            // Notificar listener
            translationListener?.onTranslationCompleted(ByteArray(0), combinedAudio, latency)

        } catch (e: Exception) {
            Log.e(TAG, "Error handling translation completion", e)
            translationListener?.onTranslationFailed("Failed to process translated audio: ${e.message}")
        }
    }

    /**
     * Handle incoming audio from WebRTC (no usado actualmente)
     */
    private fun handleRealtimeAudio(audioData: ByteArray) {
        Log.v(TAG, "Received realtime audio: ${audioData.size} bytes")
    }

    /**
     * CORREGIDO: Process audio con mejor validación y flujo
     */
    @SuppressLint("NewApi")
    fun processAudioForTranslation(audioData: ByteArray): Boolean {
        if (!isSessionReady.get() || webSocket == null) {
            Log.w(TAG, "⚠️ Session not ready or not connected")
            return false
        }

        if (audioData.isEmpty()) {
            Log.w(TAG, "⚠️ Empty audio data received")
            return false
        }

        // Rate limiting más inteligente
        val currentTime = System.currentTimeMillis()
        if (isWaitingForResponse.get() && (currentTime - lastRequestTime.get()) < minRequestInterval) {
            Log.v(TAG, "⏳ Rate limiting - buffering audio")
            bufferAudio(audioData)
            return true
        }

        return try {
            scope.launch {
                processAudioAsync(audioData)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio", e)
            false
        }
    }

    /**
     * NUEVO: Buffer de audio asíncrono
     */
    @SuppressLint("NewApi")
    private fun bufferAudio(audioData: ByteArray) {
        scope.launch {
            audioBufferLock.withLock {
                currentAudioBuffer.write(audioData)

                // Si el buffer se hace muy grande, enviar
                if (currentAudioBuffer.size() > maxAudioSizeBytes) {
                    val bufferedData = currentAudioBuffer.toByteArray()
                    currentAudioBuffer.reset()
                    sendAudioToOpenAI(bufferedData)
                }
            }
        }

    }

    /**
     * NUEVO: Procesamiento de audio asíncrono
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun processAudioAsync(audioData: ByteArray) {
        audioBufferLock.withLock {
            currentAudioBuffer.write(audioData)
            val combinedAudio = currentAudioBuffer.toByteArray()

            // Validar tamaño mínimo
            if (combinedAudio.size < minAudioSizeBytes) {
                Log.v(TAG, "📊 Buffer size: ${combinedAudio.size}/$minAudioSizeBytes bytes")
                return@withLock
            }

            // Limitar tamaño máximo
            val finalAudio = if (combinedAudio.size > maxAudioSizeBytes) {
                Log.d(TAG, "✂️ Trimming audio buffer to ${maxAudioSizeBytes} bytes")
                combinedAudio.copyOfRange(
                    combinedAudio.size - maxAudioSizeBytes,
                    combinedAudio.size
                )
            } else {
                combinedAudio
            }

            currentAudioBuffer.reset()
            sendAudioToOpenAI(finalAudio)
        }
    }

    /**
     * CORREGIDO: Send audio to OpenAI con mejor manejo de errores
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun sendAudioToOpenAI(audioData: ByteArray): Boolean {
        return try {
            if (isWaitingForResponse.get()) {
                Log.v(TAG, "⏳ Already waiting for response, skipping")
                return false
            }

            isWaitingForResponse.set(true)
            lastRequestTime.set(System.currentTimeMillis())
            lastTranslationTime.set(System.currentTimeMillis())

            Log.d(TAG, "🚀 Sending ${audioData.size} bytes to OpenAI")

            val audioBase64 = Base64.getEncoder().encodeToString(audioData)

            // PASO 1: Limpiar buffer previo
            val clearBuffer = JSONObject().apply {
                put("type", "input_audio_buffer.clear")
            }

            // PASO 2: Agregar audio
            val audioInput = JSONObject().apply {
                put("type", "input_audio_buffer.append")
                put("audio", audioBase64)
            }

            // PASO 3: Commit audio
            val commitAudio = JSONObject().apply {
                put("type", "input_audio_buffer.commit")
            }

            // PASO 4: Solicitar respuesta
            val responseRequest = JSONObject().apply {
                put("type", "response.create")
                put("response", JSONObject().apply {
                    put("modalities", JSONArray().apply {
                        put("text")
                        put("audio")
                    })
                })
            }

            // Enviar comandos secuencialmente con pequeños delays
            val success1 = webSocket?.send(clearBuffer.toString()) ?: false
            delay(10)
            val success2 = webSocket?.send(audioInput.toString()) ?: false
            delay(10)
            val success3 = webSocket?.send(commitAudio.toString()) ?: false
            delay(10)
            val success4 = webSocket?.send(responseRequest.toString()) ?: false

            val allSuccess = success1 && success2 && success3 && success4

            if (!allSuccess) {
                Log.e(TAG, "❌ Failed to send audio commands")
                isWaitingForResponse.set(false)
                return false
            }

            Log.d(TAG, "✅ Audio sent successfully")
            true

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sending audio to OpenAI", e)
            isWaitingForResponse.set(false)
            false
        }
    }

    // ... resto de métodos sin cambios significativos ...

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

        updateSessionLanguage(targetLanguage)
        return true
    }

    /**
     * Update session language
     */
    private fun updateSessionLanguage(language: String) {
        val sessionUpdate = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put(
                    "instructions", """
                    You are a real-time audio translator. Translate speech to $language.
                    
                    Rules:
                    - Only output translated speech, no explanations
                    - Maintain original tone and emotion  
                    - Use natural pronunciation
                    - If audio is unclear, stay silent
                    - Translate immediately when speech ends
                """.trimIndent()
                )
            })
        }

        webSocket?.send(sessionUpdate.toString())
    }

    /**
     * Disable translation
     */
    fun disable(): Boolean {
        resetState()
        isEnabled.set(false)

        webSocket?.close(1000, "Translation disabled")
        webSocket = null

        translationListener?.onTranslationStateChanged(false, null)
        return true
    }

    // Getters y setters sin cambios...
    fun setTranslationListener(listener: TranslationListener?) {
        this.translationListener = listener
    }

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

    fun setTranslationQuality(quality: WebRtcManager.TranslationQuality): Boolean {
        this.translationQuality = quality
        // Implementar actualización de calidad si es necesario
        return true
    }

    fun getTranslationQuality(): WebRtcManager.TranslationQuality = translationQuality

    /**
     * Dispose resources
     */
    fun dispose() {
        scope.cancel()
        webSocket?.close(1000, "Manager disposed")
        webSocket = null
        httpClient = null

        resetState()
        isEnabled.set(false)
    }
}