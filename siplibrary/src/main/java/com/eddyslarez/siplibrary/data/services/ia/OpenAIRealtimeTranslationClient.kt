package com.eddyslarez.siplibrary.data.services.ia

import android.os.Build
import androidx.annotation.RequiresApi
import com.eddyslarez.siplibrary.utils.log
import com.google.gson.JsonParser
import okhttp3.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import java.util.Base64
import java.util.LinkedList
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
/**
 * Cliente mejorado de OpenAI Realtime con soporte para traducción
 * ARREGLADO: Acumula chunks pequeños hasta alcanzar el mínimo requerido
 */
class OpenAIRealtimeTranslationClient(
    private val apiKey: String,
    private val translationConfig: TranslationConfig
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private var audioBuffer = LinkedList<ByteArray>()
    private var lastProcessTime = System.currentTimeMillis()
    private var processInterval = 2000L // Reducir a 2 segundos para llamadas
    private var isProcessingChunk = false

    private var webSocket: WebSocket? = null
    internal var isConnected = false
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Canales para comunicación
    private val audioResponseChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val translatedTextChannel = Channel<String>(Channel.UNLIMITED)

    // Estado de la sesión
    private var sessionId: String? = null
    private var isProcessingAudio = false
    private var lastActivityTime = System.currentTimeMillis()

    // Callbacks
    private var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var onTranslatedText: ((String, String) -> Unit)? = null
    private var onSpeechDetected: ((Boolean) -> Unit)? = null

    companion object {
        private const val TAG = "OpenAITranslationClient"
        private const val WEBSOCKET_URL = "wss://api.openai.com/v1/realtime"
        private const val SESSION_TIMEOUT = 30000L
        private const val KEEPALIVE_INTERVAL = 15000L
        private const val SILENCE_THRESHOLD = 0.005f // Más sensible para llamadas
        private const val MIN_AUDIO_BYTES = 3200 // OpenAI requiere mínimo 100ms (3200 bytes a 16kHz)
        private const val MAX_BUFFER_SIZE = 25 // Máximo chunks antes de forzar procesamiento
        private const val OPTIMAL_CHUNK_SIZE = 6400 // 200ms de audio (más eficiente)
    }

    fun setConnectionStateListener(listener: (Boolean) -> Unit) {
        onConnectionStateChanged = listener
    }

    fun setErrorListener(listener: (String) -> Unit) {
        onError = listener
    }

    fun setTranslationListener(listener: (String, String) -> Unit) {
        onTranslatedText = listener
    }

    fun setSpeechDetectionListener(listener: (Boolean) -> Unit) {
        onSpeechDetected = listener
    }

    suspend fun connect(): Boolean {
        return try {
            if (isConnected) {
                log.d(TAG) { "Already connected" }
                return true
            }

            val request = Request.Builder()
                .url("$WEBSOCKET_URL?model=${translationConfig.voiceSettings.model}")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("OpenAI-Beta", "realtime=v1")
                .build()

            webSocket = client.newWebSocket(request, createWebSocketListener())

            // Esperar conexión con timeout
            var attempts = 0
            while (!isConnected && attempts < 10) {
                delay(500)
                attempts++
            }

            if (isConnected) {
                startKeepAliveTimer()
                log.d(TAG) { "Connected to OpenAI Realtime API" }
            }

            isConnected
        } catch (e: Exception) {
            log.e(TAG) { "Error connecting to OpenAI: ${e.message}" }
            onError?.invoke("Error de conexión: ${e.message}")
            false
        }
    }

    private fun createWebSocketListener(): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            log.d(TAG) { "OpenAI WebSocket connected" }
            isConnected = true
            onConnectionStateChanged?.invoke(true)
            lastActivityTime = System.currentTimeMillis()

            coroutineScope.launch {
                delay(100)
                initializeTranslationSession()
            }
        }

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onMessage(webSocket: WebSocket, text: String) {
            lastActivityTime = System.currentTimeMillis()
            coroutineScope.launch { handleMessage(text) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            log.e(TAG) { "WebSocket failure: ${t.message}" }
            isConnected = false
            onConnectionStateChanged?.invoke(false)
            onError?.invoke("Error de WebSocket: ${t.message}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            log.d(TAG) { "WebSocket closed: $code $reason" }
            isConnected = false
            onConnectionStateChanged?.invoke(false)
        }
    }

    private suspend fun initializeTranslationSession() {
        val languagePair = translationConfig.languagePair
        val voiceSettings = translationConfig.voiceSettings

        val translationInstructions = """
        Eres un traductor profesional en tiempo real para llamadas telefónicas.

        REGLAS CRÍTICAS:
        1. Si detectas ${languagePair.inputLanguage}, RESPONDE ÚNICAMENTE en ${languagePair.outputLanguage}
        2. Si detectas ${languagePair.outputLanguage}, RESPONDE ÚNICAMENTE en ${languagePair.inputLanguage}  
        3. NUNCA respondas en el mismo idioma que detectes
        4. Si no hay contenido hablado, responde con silencio (no digas nada)
        5. Si el audio tiene ruido de fondo sin voz humana, no respondas
        6. Solo proporciona la traducción directa, sin comentarios
        7. Para llamadas: mantén las respuestas cortas y claras
        """.trimIndent()

        val sessionConfig = buildJsonObject {
            put("type", "session.update")
            put("session", buildJsonObject {
                put("modalities", buildJsonArray {
                    add("text")
                    add("audio")
                })
                put("instructions", translationInstructions)
                put("voice", voiceSettings.voice)
                put("input_audio_format", "pcm16")
                put("output_audio_format", "pcm16")

                put("input_audio_transcription", buildJsonObject {
                    put("model", "whisper-1")
                })

                put("turn_detection", buildJsonObject {
                    put("type", "server_vad")
                    put("threshold", 0.5) // Más sensible para llamadas
                    put("prefix_padding_ms", 300) // Capturar más contexto
                    put("silence_duration_ms", 500) // Menos tiempo de silencio
                })

                put("temperature", voiceSettings.temperature)
                put("max_response_output_tokens", voiceSettings.maxResponseTokens)
            })
        }

        sendMessage(sessionConfig)
    }

    /**
     * ARREGLADO: Envía audio continuamente acumulando hasta el tamaño mínimo
     */
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun sendAudioContinuous(audioData: ByteArray) {
        if (!isConnected) return

        try {
            // Agregar al buffer
            audioBuffer.add(audioData)

            val currentTime = System.currentTimeMillis()
            val timeSinceLastProcess = currentTime - lastProcessTime
            val totalBufferSize = audioBuffer.sumOf { it.size }

            log.d(TAG) { "Buffer: ${audioBuffer.size} chunks, ${totalBufferSize} bytes total" }

            // Condiciones para procesar:
            val shouldProcessByTime = timeSinceLastProcess >= processInterval
            val shouldProcessBySize = totalBufferSize >= OPTIMAL_CHUNK_SIZE
            val shouldProcessByCount = audioBuffer.size >= MAX_BUFFER_SIZE
            val hasMinimumSize = totalBufferSize >= MIN_AUDIO_BYTES

            if (hasMinimumSize && (shouldProcessByTime || shouldProcessBySize || shouldProcessByCount)) {
                log.d(TAG) { "Processing trigger - Time: $shouldProcessByTime, Size: $shouldProcessBySize, Count: $shouldProcessByCount" }
                processAudioBuffer()
            }

        } catch (e: Exception) {
            log.e(TAG) { "Error processing continuous audio: ${e.message}" }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun processAudioBuffer() {
        if (isProcessingChunk || audioBuffer.isEmpty()) return

        isProcessingChunk = true

        try {
            // Combinar todo el audio del buffer
            val combinedAudio = combineAudioBuffers(audioBuffer)
            val bufferCount = audioBuffer.size
            audioBuffer.clear()

            // ARREGLADO: Solo verificar el mínimo real de OpenAI
            if (combinedAudio.size < MIN_AUDIO_BYTES) {
                log.w(TAG) { "Chunk aún muy pequeño: ${combinedAudio.size} bytes (mínimo ${MIN_AUDIO_BYTES})" }
                // Re-agregar al buffer para la próxima vez
                audioBuffer.add(combinedAudio)
                return
            }

            log.d(TAG) { "Processing ${bufferCount} chunks -> ${combinedAudio.size} bytes" }

            // Verificar si hay contenido de voz
            if (hasVoiceContent(combinedAudio)) {
                log.d(TAG) { "Voice detected - sending to OpenAI" }

                // Enviar el chunk completo
                val base64Audio = Base64.getEncoder().encodeToString(combinedAudio)
                val appendMessage = buildJsonObject {
                    put("type", "input_audio_buffer.append")
                    put("audio", base64Audio)
                }
                sendMessage(appendMessage)

                // Pequeña pausa para estabilidad
                delay(50)

                lastProcessTime = System.currentTimeMillis()

            } else {
                log.d(TAG) { "No voice content detected - skipping" }
            }

        } catch (e: Exception) {
            log.e(TAG) { "Error processing audio buffer: ${e.message}" }
        } finally {
            isProcessingChunk = false
        }
    }

    private fun combineAudioBuffers(buffers: List<ByteArray>): ByteArray {
        val totalSize = buffers.sumOf { it.size }
        val combined = ByteArray(totalSize)
        var offset = 0

        for (buffer in buffers) {
            buffer.copyInto(combined, offset)
            offset += buffer.size
        }

        return combined
    }

    private fun hasVoiceContent(audioData: ByteArray): Boolean {
        if (audioData.size < 320) return false // Menos restrictivo

        var sum = 0L
        var count = 0

        // Analizar cada muestra de 16-bit
        for (i in audioData.indices step 2) {
            if (i + 1 < audioData.size) {
                val sample = (audioData[i].toInt() and 0xFF) or
                        ((audioData[i + 1].toInt() and 0xFF) shl 8)
                val shortSample = sample.toShort()
                sum += (shortSample * shortSample).toLong()
                count++
            }
        }

        if (count == 0) return false

        val rms = sqrt(sum.toDouble() / count)
        val normalizedRms = rms / 32768.0

        val hasVoice = normalizedRms > SILENCE_THRESHOLD

        if (hasVoice) {
            log.d(TAG) { "Voice RMS: ${String.format("%.6f", normalizedRms)} (threshold: $SILENCE_THRESHOLD)" }
        }

        return hasVoice
    }

    /**
     * ARREGLADO: Fuerza el procesamiento incluso con chunks pequeños
     */
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun forceProcessBuffer() {
        if (audioBuffer.isEmpty()) {
            log.d(TAG) { "No audio buffer to process" }
            return
        }

        val totalSize = audioBuffer.sumOf { it.size }
        log.d(TAG) { "Force processing ${audioBuffer.size} chunks (${totalSize} bytes)" }

        if (totalSize >= MIN_AUDIO_BYTES) {
            processAudioBuffer()
        } else {
            log.w(TAG) { "Cannot force process - total size ${totalSize} < minimum ${MIN_AUDIO_BYTES}" }
        }
    }

    /**
     * Commit audio buffer y solicitar respuesta
     */
    suspend fun commitAudio() {
        if (!isConnected) return

        try {
            log.d(TAG) { "Committing audio buffer" }

            val commitMessage = buildJsonObject {
                put("type", "input_audio_buffer.commit")
            }
            sendMessage(commitMessage)

            delay(100)
            createResponse()

        } catch (e: Exception) {
            log.e(TAG) { "Error committing audio: ${e.message}" }
        }
    }

    fun clearBuffer() {
        val size = audioBuffer.size
        audioBuffer.clear()
        log.d(TAG) { "Cleared ${size} chunks from audio buffer" }
    }

    fun setProcessingInterval(intervalMs: Long) {
        processInterval = intervalMs
        log.d(TAG) { "Processing interval set to ${intervalMs}ms" }
    }

    // Métodos auxiliares para debugging
    fun getBufferInfo(): String {
        val totalBytes = audioBuffer.sumOf { it.size }
        return "Buffer: ${audioBuffer.size} chunks, ${totalBytes} bytes"
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun handleMessage(text: String) {
        try {
            val json = JsonParser.parseString(text).asJsonObject
            val type = json.get("type").asString

            log.d(TAG) { "Received: $type" }

            when (type) {
                "session.created" -> {
                    sessionId = json.get("session")?.asJsonObject?.get("id")?.asString
                    log.d(TAG) { "Session created: $sessionId" }
                }

                "session.updated" -> {
                    log.d(TAG) { "Session updated successfully" }
                }

                "input_audio_buffer.speech_started" -> {
                    log.d(TAG) { "Speech started" }
                    isProcessingAudio = true
                    onSpeechDetected?.invoke(true)
                }

                "input_audio_buffer.speech_stopped" -> {
                    log.d(TAG) { "Speech stopped" }
                    isProcessingAudio = false
                    onSpeechDetected?.invoke(false)

                    // Auto-commit y crear respuesta
                    delay(100)
                    commitAudio()
                }

                "response.audio.delta" -> {
                    val delta = json.get("delta").asString
                    val audioBytes = Base64.getDecoder().decode(delta)
                    audioResponseChannel.trySend(audioBytes)
                }

                "response.audio_transcript.delta" -> {
                    val delta = json.get("delta").asString
                    translatedTextChannel.trySend(delta)
                }

                "response.audio_transcript.done" -> {
                    val transcript = json.get("transcript").asString
                    log.d(TAG) { "Translation: $transcript" }
                    onTranslatedText?.invoke("", transcript)
                }

                "response.done" -> {
                    log.d(TAG) { "Response complete - ready for next" }
                    isProcessingAudio = false
                }

                "error" -> {
                    val error = json.getAsJsonObject("error")
                    val message = error.get("message").asString
                    val code = error.get("code")?.asString ?: "unknown"
                    log.e(TAG) { "OpenAI error [$code]: $message" }
                    onError?.invoke("Error OpenAI: $message")

                    if (code == "invalid_session" || code == "session_expired") {
                        reconnect()
                    }
                }

                else -> {
                    log.d(TAG) { "Unhandled message: $type" }
                }
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error parsing message: ${e.message}" }
        }
    }

    private suspend fun createResponse() {
        if (!isConnected) return

        try {
            val request = buildJsonObject {
                put("type", "response.create")
                put("response", buildJsonObject {
                    put("modalities", buildJsonArray {
                        add("text")
                        add("audio")
                    })
                })
            }
            sendMessage(request)
        } catch (e: Exception) {
            log.e(TAG) { "Error creating response: ${e.message}" }
        }
    }

    private suspend fun sendMessage(jsonObject: JsonObject) {
        try {
            val jsonString = jsonObject.toString()
            webSocket?.send(jsonString)
            log.d(TAG) { "Sent: ${jsonObject.get("type")}" }
        } catch (e: Exception) {
            log.e(TAG) { "Error sending message: ${e.message}" }
        }
    }

    private fun startKeepAliveTimer() {
        coroutineScope.launch {
            while (isConnected) {
                delay(KEEPALIVE_INTERVAL)

                val timeSinceLastActivity = System.currentTimeMillis() - lastActivityTime
                if (timeSinceLastActivity > SESSION_TIMEOUT) {
                    log.w(TAG) { "Session timeout - reconnecting" }
                    reconnect()
                    break
                }

                try {
                    if (webSocket != null && isConnected) {
                        val pingBytes = "ping".toByteArray()
                        webSocket?.send(okio.ByteString.of(*pingBytes))
                    }
                } catch (e: Exception) {
                    log.e(TAG) { "Keepalive failed: ${e.message}" }
                }
            }
        }
    }

    private suspend fun reconnect() {
        log.d(TAG) { "Reconnecting..." }
        disconnect()
        delay(1000)
        connect()
    }

    fun getAudioResponseChannel(): ReceiveChannel<ByteArray> = audioResponseChannel
    fun getTranslatedTextChannel(): ReceiveChannel<String> = translatedTextChannel

    suspend fun disconnect() {
        try {
            isConnected = false
            webSocket?.close(1000, "Client disconnect")
            coroutineScope.cancel()
            audioResponseChannel.close()
            translatedTextChannel.close()
        } catch (e: Exception) {
            log.e(TAG) { "Error disconnecting: ${e.message}" }
        }
    }
}