package com.eddyslarez.siplibrary.data.services.ia

import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Log

class MCNTranslatorClient6(
    private val apiKey: String,
    private val model: String = "gpt-4o-realtime-preview-2025-06-03"
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isSessionInitialized = AtomicBoolean(false)
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Audio management optimizado
    private val audioBuffer = mutableListOf<ByteArray>()
    private val audioBufferMutex = Mutex()
    private val minAudioChunkSize = 1600 // Reducido para mayor responsividad
    private val maxAudioChunkSize = 10 * 1024 * 1024 // Reducido para evitar timeouts
    private val audioSendInterval = 50L // Más frecuente para tiempo real

    // Response channels
    private val audioResponseChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val transcriptionChannel = Channel<String>(Channel.UNLIMITED)
    private val inputTranscriptionChannel = Channel<String>(Channel.UNLIMITED)
    private val translationChannel = Channel<TranslationResult>(Channel.UNLIMITED)

    // Callbacks
    private var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var onAudioReceived: ((ByteArray) -> Unit)? = null
    private var onTranslationReceived: ((TranslationResult) -> Unit)? = null
    private var onLanguageDetected: ((DetectedLanguage) -> Unit)? = null

    // Language detection and management
    private var currentSourceLanguage: String = "auto"
    private var isTranslationMode = true
    private var confidenceThreshold = 0.7f

    // Audio timeout mejorado
    private var lastAudioReceiveTime = 0L
    private val audioTimeoutMs = 1500L // Reducido para mayor responsividad

    companion object {
        private const val TAG = "MCNTranslatorClient"
        private const val WEBSOCKET_URL = "wss://api.openai.com/v1/realtime"
    }

    // Buffer para audio reproducible optimizado
    private val playbackBuffer = ByteArrayOutputStream()
    private val playbackMutex = Mutex()

    // Manejo de reconexión automática
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private var isReconnecting = AtomicBoolean(false)

    // Data classes
    data class TranslationResult(
        val originalText: String,
        val translatedText: String,
        val sourceLanguage: String,
        val targetLanguage: String,
        val confidence: Float,
        val timestamp: Long
    )

    data class DetectedLanguage(
        val language: String,
        val confidence: Float,
        val text: String
    )

    // === INSTRUCCIONES OPTIMIZADAS PARA MEJOR RENDIMIENTO ===
    private val translatorInstructions = """
        Eres un traductor profesional ESPAÑOL-RUSO / RUSO-ESPAÑOL en tiempo real.
        
        REGLAS CRÍTICAS:
        1. DETECTA automáticamente el idioma (español o ruso)
        2. TRADUCE inmediatamente al idioma opuesto
        3. RESPONDE SOLO con la traducción, sin explicaciones
        4. MANTÉN el tono y contexto original
        5. SÉ RÁPIDO y PRECISO
        
        EJEMPLOS:
        Español → Ruso: "Hola, ¿cómo estás?" → "Привет, как дела?"
        Ruso → Español: "Спасибо за помощь" → "Gracias por la ayuda"
        
        NUNCA agregues comentarios como "La traducción es..." o similares.
        RESPONDE directamente con el texto traducido.
        """.trimIndent()

    // === MÉTODOS DE CONFIGURACIÓN ===
    fun setConnectionStateListener(listener: (Boolean) -> Unit) {
        onConnectionStateChanged = listener
    }

    fun setErrorListener(listener: (String) -> Unit) {
        onError = listener
    }

    fun setAudioReceivedListener(listener: (ByteArray) -> Unit) {
        onAudioReceived = listener
    }

    fun setTranslationReceivedListener(listener: (TranslationResult) -> Unit) {
        onTranslationReceived = listener
    }

    fun setLanguageDetectedListener(listener: (DetectedLanguage) -> Unit) {
        onLanguageDetected = listener
    }

    fun setTranslationMode(enabled: Boolean) {
        isTranslationMode = enabled
        Log.d(TAG, "Modo traducción: ${if (enabled) "ACTIVADO" else "DESACTIVADO"}")
    }

    fun setSourceLanguage(language: String) {
        currentSourceLanguage = language
        Log.d(TAG, "Idioma fuente establecido: $language")
    }

    fun setConfidenceThreshold(threshold: Float) {
        confidenceThreshold = threshold.coerceIn(0.1f, 1.0f)
        Log.d(TAG, "Umbral de confianza: $confidenceThreshold")
    }

    // === CONEXIÓN MEJORADA ===
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun connect(): Boolean {
        return try {
            if (isConnected.get()) {
                Log.d(TAG, "Ya está conectado")
                return true
            }

            val request = Request.Builder()
                .url("$WEBSOCKET_URL?model=$model")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("OpenAI-Beta", "realtime=v1")
                .addHeader("User-Agent", "MCNTranslator/1.0")
                .build()

            Log.d(TAG, "Conectando Traductor MCN...")
            webSocket = client.newWebSocket(request, createWebSocketListener())

            // Esperar conexión con timeout
            var attempts = 0
            while (!isConnected.get() && attempts < 100) {
                delay(50)
                attempts++
            }

            if (isConnected.get()) {
                reconnectAttempts = 0
                startAudioStreamingLoop()
                startAudioTimeoutWatcher()
                Log.d(TAG, "Traductor MCN conectado exitosamente")
                true
            } else {
                Log.e(TAG, "Timeout en conexión")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error de conexión: ${e.message}")
            onError?.invoke("Error de conexión: ${e.message}")
            false
        }
    }

    private fun createWebSocketListener(): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "WebSocket conectado - Status: ${response.code}")
            isConnected.set(true)
            onConnectionStateChanged?.invoke(true)

            coroutineScope.launch {
                try {
                    delay(100) // Pequeña pausa antes de inicializar
                    initializeTranslatorSession()
                } catch (e: Exception) {
                    Log.e(TAG, "Error inicializando sesión: ${e.message}")
                    onError?.invoke("Error de inicialización: ${e.message}")
                }
            }
        }

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onMessage(webSocket: WebSocket, text: String) {
            coroutineScope.launch {
                try {
                    handleMessage(text)
                } catch (e: Exception) {
                    Log.e(TAG, "Error procesando mensaje: ${e.message}")
                }
            }
        }

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Error WebSocket: ${t.message}")
            isConnected.set(false)
            isSessionInitialized.set(false)
            onConnectionStateChanged?.invoke(false)

            // Intentar reconexión automática
            if (!isReconnecting.get() && reconnectAttempts < maxReconnectAttempts) {
                attemptReconnection()
            } else {
                onError?.invoke("Error WebSocket: ${t.message}")
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket cerrado: $code - $reason")
            isConnected.set(false)
            isSessionInitialized.set(false)
            onConnectionStateChanged?.invoke(false)
        }
    }

    // === RECONEXIÓN AUTOMÁTICA ===
    @RequiresApi(Build.VERSION_CODES.O)
    private fun attemptReconnection() {
        if (isReconnecting.get()) return

        isReconnecting.set(true)
        coroutineScope.launch {
            try {
                reconnectAttempts++
                Log.d(TAG, "Intentando reconexión #$reconnectAttempts")


                if (connect()) {
                    Log.d(TAG, "Reconexión exitosa")
                } else {
                    Log.e(TAG, "Falló reconexión #$reconnectAttempts")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en reconexión: ${e.message}")
            } finally {
                isReconnecting.set(false)
            }
        }
    }

    // === INICIALIZACIÓN OPTIMIZADA ===
    private suspend fun initializeTranslatorSession() {
        Log.d(TAG, "Inicializando sesión optimizada...")

        val sessionConfig = JSONObject().apply {
            put("type", "session.update")
            put("event_id", "translator_init_${System.currentTimeMillis()}")

            put("session", JSONObject().apply {
                put("modalities", JSONArray().apply {
                    put("text")
                    put("audio")
                })
                put("instructions", translatorInstructions)
                put("voice", "sage") // Voz más rápida
                put("input_audio_format", "pcm16")
                put("output_audio_format", "pcm16")
                put("input_audio_transcription", JSONObject().apply {
                    put("model", "whisper-1")
                })
                put("turn_detection", JSONObject().apply {
                    put("type", "server_vad")
                    put("threshold", 0.3) // Más sensible
                    put("prefix_padding_ms", 200) // Menos padding
                    put("silence_duration_ms", 500) // Pausa más corta
                })
                put("temperature", 0.6) // Muy conservador para precisión
                put("max_response_output_tokens", 1024) // Reducido para velocidad
            })
        }

        if (sendMessage(sessionConfig)) {
            Log.d(TAG, "Configuración enviada exitosamente")
        } else {
            Log.e(TAG, "Error enviando configuración")
            onError?.invoke("Error enviando configuración de sesión")
        }
    }

    // === MANEJO DE MENSAJES OPTIMIZADO ===
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.getString("type")

            when (type) {
                "session.created" -> {
                    Log.d(TAG, "Sesión creada")
                }

                "session.updated" -> {
                    isSessionInitialized.set(true)
                    Log.d(TAG, "Traductor listo ✓")
                }

                "response.audio.delta" -> {
                    lastAudioReceiveTime = System.currentTimeMillis()
                    val delta = json.getString("delta")
                    try {
                        val audioBytes = Base64.getDecoder().decode(delta)

                        playbackMutex.withLock {
                            playbackBuffer.write(audioBytes)
                            val data = playbackBuffer.toByteArray()

                            // Enviar chunks más pequeños para menor latencia
                            if (data.size >= 160) { // 10ms de audio
                                onAudioReceived?.invoke(data.copyOf())
                                playbackBuffer.reset()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error decodificando audio: ${e.message}")
                    }
                }

                "response.audio.done" -> {
                    playbackMutex.withLock {
                        if (playbackBuffer.size() > 0) {
                            val remainingData = playbackBuffer.toByteArray()
                            onAudioReceived?.invoke(remainingData)
                            playbackBuffer.reset()
                        }
                    }
                    Log.d(TAG, "Audio completado")
                }

                "response.audio_transcript.delta" -> {
                    val delta = json.getString("delta")
                    transcriptionChannel.trySend(delta)
                    Log.d(TAG, "Traducción: $delta")
                }

                "input_audio_buffer.speech_started" -> {
                    Log.d(TAG, "🎤 Detectando voz...")
                    playbackMutex.withLock {
                        playbackBuffer.reset()
                    }
                }

                "input_audio_buffer.speech_stopped" -> {
                    Log.d(TAG, "🔄 Procesando...")
                }

                "conversation.item.input_audio_transcription.completed" -> {
                    val transcript = json.getString("transcript")
                    inputTranscriptionChannel.trySend(transcript)

                    if (isTranslationMode && transcript.isNotBlank()) {
                        processTranscriptionForTranslation(transcript)
                    }

                    Log.d(TAG, "📝 Original: $transcript")
                }

                "response.created" -> {
                    Log.d(TAG, "⚡ Generando traducción...")
                    lastAudioReceiveTime = System.currentTimeMillis()
                }

                "response.done" -> {
                    Log.d(TAG, "✅ Traducción completada")
                }

                "error" -> {
                    val error = json.getJSONObject("error")
                    val errorMsg = "${error.getString("type")}: ${error.getString("message")}"
                    Log.e(TAG, "❌ Error: $errorMsg")
                    onError?.invoke("Error: $errorMsg")

                    // Si es error de rate limit, esperar y reintentar
                    if (errorMsg.contains("rate_limit")) {
                        coroutineScope.launch {
                            delay(5000)
                            if (!isConnected.get()) {
                                connect()
                            }
                        }
                    }
                }

                else -> {
                    // Log.d(TAG, "Mensaje: $type") // Comentado para reducir logs
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parseando mensaje: ${e.message}")
        }
    }

    // === PROCESAMIENTO DE TRADUCCIÓN MEJORADO ===
    private fun processTranscriptionForTranslation(transcript: String) {
        if (transcript.isBlank() || transcript.length < 2) return

        try {
            // Detectar idioma mejorado
            val detectedLanguage = detectLanguage(transcript)
            onLanguageDetected?.invoke(detectedLanguage)

            val targetLanguage = when (detectedLanguage.language) {
                "es" -> "ru"
                "ru" -> "es"
                else -> "es"
            }

            Log.d(TAG, "🔍 ${detectedLanguage.language} → $targetLanguage")

            // La traducción se maneja automáticamente por el modelo
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando transcripción: ${e.message}")
        }
    }

    // === DETECCIÓN DE IDIOMA MEJORADA ===
    private fun detectLanguage(text: String): DetectedLanguage {
        val cyrillicPattern = Regex("[а-яё]", RegexOption.IGNORE_CASE)
        val latinPattern = Regex("[a-záéíóúñü¿¡]", RegexOption.IGNORE_CASE)

        val hasCyrillic = cyrillicPattern.containsMatchIn(text)
        val hasLatinSpanish = latinPattern.containsMatchIn(text)

        // Palabras clave más completas
        val russianKeywords = listOf(
            "здравствуйте", "привет", "как", "дела", "спасибо", "пожалуйста",
            "да", "нет", "хорошо", "плохо", "что", "где", "когда", "почему",
            "счёт", "баланс", "помощь", "проблема", "вопрос"
        )

        val spanishKeywords = listOf(
            "hola", "buenos", "días", "cómo", "está", "gracias", "por", "favor",
            "sí", "no", "bien", "mal", "qué", "dónde", "cuándo", "por", "qué",
            "cuenta", "saldo", "ayuda", "problema", "pregunta"
        )

        val textLower = text.toLowerCase()
        val russianMatches = russianKeywords.count { textLower.contains(it) }
        val spanishMatches = spanishKeywords.count { textLower.contains(it) }

        return when {
            hasCyrillic || russianMatches > spanishMatches -> {
                val confidence = if (hasCyrillic) 0.95f else (russianMatches * 0.2f + 0.6f).coerceAtMost(0.9f)
                DetectedLanguage("ru", confidence, text)
            }
            hasLatinSpanish || spanishMatches > russianMatches -> {
                val confidence = if (hasLatinSpanish) 0.85f else (spanishMatches * 0.2f + 0.6f).coerceAtMost(0.8f)
                DetectedLanguage("es", confidence, text)
            }
            else -> DetectedLanguage("es", 0.6f, text)
        }
    }

    // === AUDIO STREAMING OPTIMIZADO ===
    private fun startAudioTimeoutWatcher() {
        coroutineScope.launch {
            while (isConnected.get()) {
                val currentTime = System.currentTimeMillis()
                if (lastAudioReceiveTime > 0 &&
                    currentTime - lastAudioReceiveTime > audioTimeoutMs) {

                    playbackMutex.withLock {
                        if (playbackBuffer.size() > 0) {
                            Log.d(TAG, "⏰ Enviando audio restante")
                            val remainingData = playbackBuffer.toByteArray()
                            onAudioReceived?.invoke(remainingData)
                            playbackBuffer.reset()
                        }
                    }
                    lastAudioReceiveTime = 0L
                }
                delay(300) // Más frecuente
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startAudioStreamingLoop() {
        coroutineScope.launch {
            while (isConnected.get()) {
                try {
                    if (isSessionInitialized.get()) {
                        processAndSendAudioBuffer()
                    }
                    delay(audioSendInterval)
                } catch (e: Exception) {
                    Log.e(TAG, "Error en loop de audio: ${e.message}")
                    delay(1000)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun processAndSendAudioBuffer() {
        if (!isSessionInitialized.get()) return

        val audioChunks = audioBufferMutex.withLock {
            if (audioBuffer.isNotEmpty()) {
                val chunks = audioBuffer.toList()
                audioBuffer.clear()
                chunks
            } else {
                emptyList()
            }
        }

        if (audioChunks.isNotEmpty()) {
            val combinedAudio = combineAudioChunks(audioChunks)
            if (combinedAudio.size >= minAudioChunkSize) {
                sendAudioToOpenAI(combinedAudio)
            }
        }
    }

    private fun combineAudioChunks(chunks: List<ByteArray>): ByteArray {
        val totalSize = chunks.sumOf { it.size }
        val finalSize = minOf(totalSize, maxAudioChunkSize)

        val result = ByteArray(finalSize)
        var offset = 0

        for (chunk in chunks) {
            val remainingSpace = finalSize - offset
            if (remainingSpace <= 0) break

            val copySize = minOf(chunk.size, remainingSpace)
            System.arraycopy(chunk, 0, result, offset, copySize)
            offset += copySize
        }

        return result
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun sendAudioToOpenAI(audioData: ByteArray) {
        try {
            val base64Audio = Base64.getEncoder().encodeToString(audioData)
            val message = JSONObject().apply {
                put("type", "input_audio_buffer.append")
                put("event_id", "audio_${System.currentTimeMillis()}")
                put("audio", base64Audio)
            }

            sendMessage(message)
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando audio: ${e.message}")
        }
    }

    suspend fun generateTranslation() {
        try {
            val message = JSONObject().apply {
                put("type", "response.create")
                put("event_id", "translate_${System.currentTimeMillis()}")
                put("response", JSONObject().apply {
                    put("modalities", JSONArray().apply {
                        put("text")
                        put("audio")
                    })
                })
            }
            sendMessage(message)
            Log.d(TAG, "🚀 Solicitando traducción")
        } catch (e: Exception) {
            Log.e(TAG, "Error solicitando traducción: ${e.message}")
        }
    }

    // === MÉTODOS PÚBLICOS ===
    suspend fun addAudioData(audioData: ByteArray) {
        if (!isConnected.get() || !isTranslationMode) return
        audioBufferMutex.withLock {
            audioBuffer.add(audioData)
        }
    }

    fun getAudioResponseChannel(): ReceiveChannel<ByteArray> = audioResponseChannel
    fun getTranscriptionChannel(): ReceiveChannel<String> = transcriptionChannel
    fun getInputTranscriptionChannel(): ReceiveChannel<String> = inputTranscriptionChannel
    fun getTranslationChannel(): ReceiveChannel<TranslationResult> = translationChannel

    fun isConnected(): Boolean = isConnected.get()
    fun isSessionReady(): Boolean = isSessionInitialized.get()
    fun isTranslating(): Boolean = isTranslationMode

    private suspend fun sendMessage(jsonObject: JSONObject): Boolean {
        return try {
            val jsonString = jsonObject.toString()
            val success = webSocket?.send(jsonString) ?: false
            if (!success) {
                Log.e(TAG, "Error enviando mensaje WebSocket")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando mensaje: ${e.message}")
            false
        }
    }

    suspend fun disconnect() {
        Log.d(TAG, "🔌 Desconectando...")
        isConnected.set(false)
        isSessionInitialized.set(false)

        try {
            webSocket?.close(1000, "MCN Translator disconnect")
        } catch (e: Exception) {
            Log.e(TAG, "Error cerrando WebSocket: ${e.message}")
        }

        coroutineScope.cancel()
        audioResponseChannel.close()
        transcriptionChannel.close()
        translationChannel.close()
        inputTranscriptionChannel.close()

        audioBufferMutex.withLock {
            audioBuffer.clear()
        }

        playbackMutex.withLock {
            playbackBuffer.reset()
        }

        Log.d(TAG, "✅ Desconectado")
    }
}
