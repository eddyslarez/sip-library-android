package com.eddyslarez.siplibrary.data.services.ia

import android.os.Build
import androidx.annotation.RequiresApi
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MCNTranslatorClient(
    private val apiKey: String,
    private val model: String = "gpt-4o-realtime-preview-2024-10-01"
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private val isSessionInitialized = AtomicBoolean(false)
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Audio management
    private val audioBuffer = mutableListOf<ByteArray>()
    private val audioBufferMutex = Mutex()
    private val minAudioChunkSize = 3200 // 200ms at 16kHz
    private val maxAudioChunkSize = 15 * 1024 * 1024 // 15MB OpenAI limit
    private val audioSendInterval = 80L

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
    private var currentSourceLanguage: String = "auto" // "es", "ru", "auto"
    private var isTranslationMode = true
    private var confidenceThreshold = 0.7f

    // Manejo de audio truncado
    private var lastAudioReceiveTime = 0L
    private val audioTimeoutMs = 2000L

    companion object {
        private const val TAG = "MCNTranslatorClient"
        private const val WEBSOCKET_URL = "wss://api.openai.com/v1/realtime"
    }

    // Buffer para audio reproducible
    private val playbackBuffer = ByteArrayOutputStream()
    private val playbackMutex = Mutex()

    // Data classes para el traductor
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

    // === INSTRUCCIONES DEL TRADUCTOR EN TIEMPO REAL ===
    private val translatorInstructions = """
        TÚ ERES UN TRADUCTOR PROFESIONAL EN TIEMPO REAL ESPAÑOL-RUSO / RUSO-ESPAÑOL.
        
        MISIÓN PRINCIPAL:
        Traducir automáticamente conversaciones telefónicas entre español y ruso de manera fluida y precisa.
        
        FUNCIONAMIENTO:
        1. DETECTA AUTOMÁTICAMENTE el idioma del audio entrante
        2. TRADUCE INMEDIATAMENTE al idioma opuesto:
           - Si detectas ESPAÑOL → Traduce a RUSO
           - Si detectas RUSO → Traduce a ESPAÑOL
        3. MANTÉN el contexto de la conversación
        4. CONSERVA el tono y formalidad del hablante
        
        REGLAS DE TRADUCCIÓN:
        ✓ Traducción directa y precisa
        ✓ Mantener el contexto conversacional
        ✓ Preservar nombres propios y marcas
        ✓ Adaptar expresiones idiomáticas
        ✓ Conservar el nivel de formalidad
        ✓ Traducir números y fechas según el formato local
        
        FORMATO DE RESPUESTA:
        - SIEMPRE responde solo con la traducción
        - NO agregues explicaciones o comentarios
        - NO digas "La traducción es..." o similares
        - RESPONDE directamente con el texto traducido
        - USA el mismo tono emocional del original
        
        DETECCIÓN DE IDIOMA:
        - Si el texto contiene cirílico → Es RUSO, traduce a ESPAÑOL
        - Si el texto contiene caracteres latinos → Es ESPAÑOL, traduce a RUSO
        - Si hay palabras mezcladas, traduce la parte principal
        - Si no estás seguro, pregunta brevemente "¿En qué idioma prefieres continuar?"
        
        CASOS ESPECIALES:
        - Términos técnicos: Traducir pero mantener claridad
        - Nombres de empresas: Mantener original y agregar traducción si es necesario
        - Números de teléfono: Leer en el idioma de destino
        - Direcciones: Adaptar al formato local
        
        CONTEXTO TELEFÓNICO:
        - Conversaciones de negocios
        - Atención al cliente
        - Consultas técnicas
        - Trámites administrativos
        
        CALIDAD DE TRADUCCIÓN:
        - Precisión > Velocidad
        - Claridad > Literalidad
        - Naturalidad en el idioma destino
        - Coherencia contextual
        
        EJEMPLOS:
        Español → Ruso:
        "Buenos días, necesito ayuda con mi cuenta" → "Доброе утро, мне нужна помощь с моим счётом"
        
        Ruso → Español:
        "Как дела с балансом?" → "¿Cómo está el saldo?"
        
        NUNCA HAGAS:
        - Traducir nombres propios comunes
        - Cambiar el sentido del mensaje original
        - Agregar información no presente en el original
        - Usar lenguaje demasiado formal si el original es casual
        - Omitir partes importantes del mensaje
        
        RECUERDA: Eres un puente de comunicación transparente entre hablantes de español y ruso.
        Tu objetivo es que la conversación fluya naturalmente como si ambas personas hablaran el mismo idioma.
        """.trimIndent()

    // === CONFIGURACIÓN ===
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

    // === MÉTODOS DE CONFIGURACIÓN DEL TRADUCTOR ===
    fun setTranslationMode(enabled: Boolean) {
        isTranslationMode = enabled
        log.d(TAG) { "Modo traducción: ${if (enabled) "ACTIVADO" else "DESACTIVADO"}" }
    }

    fun setSourceLanguage(language: String) {
        currentSourceLanguage = language
        log.d(TAG) { "Idioma fuente establecido: $language" }
    }

    fun setConfidenceThreshold(threshold: Float) {
        confidenceThreshold = threshold.coerceIn(0.1f, 1.0f)
        log.d(TAG) { "Umbral de confianza: $confidenceThreshold" }
    }

    // === CONEXIÓN ===
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun connect(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$WEBSOCKET_URL?model=$model")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("OpenAI-Beta", "realtime=v1")
                .build()

            log.d(TAG) { "Conectando Traductor MCN..." }
            webSocket = client.newWebSocket(request, createWebSocketListener())

            // Esperar conexión
            var attempts = 0
            while (!isConnected.get() && attempts < 50) {
                delay(100)
                attempts++
            }

            if (isConnected.get()) {
                startAudioStreamingLoop()
                startAudioTimeoutWatcher()
                log.d(TAG) { "Traductor MCN conectado exitosamente" }
            }

            isConnected.get()
        } catch (e: Exception) {
            log.e(TAG) { "Error de conexión: ${e.message}" }
            false
        }
    }

    private fun createWebSocketListener(): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            log.d(TAG) { "WebSocket Traductor conectado" }
            isConnected.set(true)
            onConnectionStateChanged?.invoke(true)

            coroutineScope.launch {
                try {
                    initializeTranslatorSession()
                } catch (e: Exception) {
                    log.e(TAG) { "Error inicializando sesión: ${e.message}" }
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
                    log.e(TAG) { "Error procesando mensaje: ${e.message}" }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            log.e(TAG) { "Error WebSocket: ${t.message}" }
            isConnected.set(false)
            onConnectionStateChanged?.invoke(false)
            onError?.invoke("Error WebSocket: ${t.message}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            log.d(TAG) { "WebSocket cerrado: $code - $reason" }
            isConnected.set(false)
            onConnectionStateChanged?.invoke(false)
        }
    }

    // === INICIALIZACIÓN DE SESIÓN PARA TRADUCCIÓN ===
    private suspend fun initializeTranslatorSession() {
        log.d(TAG) { "Inicializando sesión de Traductor..." }

        val sessionConfig = JSONObject().apply {
            put("type", "session.update")
            put("event_id", "translator_init_${System.currentTimeMillis()}")

            put("session", JSONObject().apply {
                put("modalities", JSONArray().apply {
                    put("text")
                    put("audio")
                })
                put("instructions", translatorInstructions)
                put("voice", "coral") // Voz neutra para traducción
                put("input_audio_format", "pcm16")
                put("output_audio_format", "pcm16")
                put("input_audio_transcription", JSONObject().apply {
                    put("model", "whisper-1")
                })
                put("turn_detection", JSONObject().apply {
                    put("type", "server_vad")
                    put("threshold", 0.4) // Más sensible para captar mejor el audio
                    put("prefix_padding_ms", 300) // Menor padding para mayor velocidad
                    put("silence_duration_ms", 600) // Pausa más corta para traducción rápida
                })
                put("temperature", 0.6) // Muy conservador para traducciones precisas
                put("max_response_output_tokens", 2048)
            })
        }

        sendMessage(sessionConfig)
        log.d(TAG) { "Configuración de Traductor enviada" }
    }

    // === MANEJO DE MENSAJES PARA TRADUCCIÓN ===
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.getString("type")

            when (type) {
                "session.created" -> {
                    log.d(TAG) { "Sesión de Traductor creada" }
                }

                "session.updated" -> {
                    isSessionInitialized.set(true)
                    log.d(TAG) { "Traductor listo para conversaciones" }
                }

                "response.audio.delta" -> {
                    lastAudioReceiveTime = System.currentTimeMillis()
                    val delta = json.getString("delta")
                    try {
                        val audioBytes = Base64.getDecoder().decode(delta)

                        playbackMutex.withLock {
                            playbackBuffer.write(audioBytes)
                            val data = playbackBuffer.toByteArray()

                            if (data.size >= 320 && data.size % 2 == 0) {
                                onAudioReceived?.invoke(data.copyOf())
                                playbackBuffer.reset()
                            }
                        }
                    } catch (e: Exception) {
                        log.e(TAG) { "Error decodificando audio traducción: ${e.message}" }
                    }
                }

                "response.audio.done" -> {
                    playbackMutex.withLock {
                        if (playbackBuffer.size() > 0) {
                            val remainingData = playbackBuffer.toByteArray()
                            if (remainingData.size % 2 == 0) {
                                onAudioReceived?.invoke(remainingData)
                            }
                            playbackBuffer.reset()
                        }
                    }
                    log.d(TAG) { "Traducción completada" }
                }

                "response.audio_transcript.delta" -> {
                    val delta = json.getString("delta")
                    transcriptionChannel.trySend(delta)
                    log.d(TAG) { "Traducción: $delta" }
                }

                "input_audio_buffer.speech_started" -> {
                    log.d(TAG) { "Detectando idioma y preparando traducción..." }
                    playbackMutex.withLock {
                        playbackBuffer.reset()
                    }
                }

                "input_audio_buffer.speech_stopped" -> {
                    log.d(TAG) { "Procesando traducción..." }
                }

                "conversation.item.input_audio_transcription.completed" -> {
                    val transcript = json.getString("transcript")
                    inputTranscriptionChannel.trySend(transcript)

                    if (isTranslationMode) {
                        processTranscriptionForTranslation(transcript)
                    }

                    log.d(TAG) { "Texto original: $transcript" }
                }

                "response.created" -> {
                    log.d(TAG) { "Generando traducción..." }
                    lastAudioReceiveTime = System.currentTimeMillis()
                }

                "response.done" -> {
                    log.d(TAG) { "Traducción lista" }
                }

                "error" -> {
                    val error = json.getJSONObject("error")
                    val errorMsg = "${error.getString("type")}: ${error.getString("message")}"
                    log.e(TAG) { "Error Traductor: $errorMsg" }
                    onError?.invoke("Error Traductor: $errorMsg")
                }

                else -> {
                    log.d(TAG) { "Mensaje no manejado: $type" }
                }
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error parseando mensaje Traductor: ${e.message}" }
        }
    }

    // === PROCESAMIENTO DE TRADUCCIÓN ===
    private fun processTranscriptionForTranslation(transcript: String) {
        if (transcript.isBlank()) return

        // Detectar idioma del texto
        val detectedLanguage = detectLanguage(transcript)

        // Notificar idioma detectado
        onLanguageDetected?.invoke(detectedLanguage)

        // Determinar idioma objetivo basado en el detectado
        val targetLanguage = when (detectedLanguage.language) {
            "es" -> "ru"
            "ru" -> "es"
            else -> "es" // Por defecto a español si no se detecta
        }

        log.d(TAG) { "Idioma detectado: ${detectedLanguage.language} → Traduciendo a: $targetLanguage" }

        // La traducción se realizará automáticamente por las instrucciones del modelo
        // El resultado se capturará en response.audio_transcript.delta
    }

    // === DETECCIÓN DE IDIOMA ===
    private fun detectLanguage(text: String): DetectedLanguage {
        // Detectar caracteres cirílicos
        val cyrillicPattern = Regex("[а-яё]", RegexOption.IGNORE_CASE)
        val latinPattern = Regex("[a-záéíóúñü]", RegexOption.IGNORE_CASE)

        val hasCyrillic = cyrillicPattern.containsMatchIn(text)
        val hasLatinSpanish = latinPattern.containsMatchIn(text)

        // Palabras clave en ruso
        val russianKeywords = listOf(
            "здравствуйте", "привет", "как дела", "спасибо", "пожалуйста",
            "да", "нет", "хорошо", "плохо", "счёт", "баланс"
        )

        // Palabras clave en español
        val spanishKeywords = listOf(
            "hola", "buenos días", "cómo está", "gracias", "por favor",
            "sí", "no", "bien", "mal", "cuenta", "saldo"
        )

        val textLower = text.toLowerCase()
        val russianMatches = russianKeywords.count { textLower.contains(it) }
        val spanishMatches = spanishKeywords.count { textLower.contains(it) }

        return when {
            hasCyrillic || russianMatches > 0 -> {
                val confidence = if (hasCyrillic) 0.9f else (russianMatches * 0.3f).coerceAtMost(0.8f)
                DetectedLanguage("ru", confidence, text)
            }
            hasLatinSpanish || spanishMatches > 0 -> {
                val confidence = if (hasLatinSpanish) 0.8f else (spanishMatches * 0.3f).coerceAtMost(0.7f)
                DetectedLanguage("es", confidence, text)
            }
            else -> DetectedLanguage("es", 0.5f, text) // Por defecto español
        }
    }

    // === MÉTODOS DE STREAMING DE AUDIO (similares al original) ===
    private fun startAudioTimeoutWatcher() {
        coroutineScope.launch {
            while (isConnected.get()) {
                val currentTime = System.currentTimeMillis()
                if (lastAudioReceiveTime > 0 &&
                    currentTime - lastAudioReceiveTime > audioTimeoutMs) {

                    playbackMutex.withLock {
                        if (playbackBuffer.size() > 0) {
                            log.d(TAG) { "Enviando audio traducido restante por timeout" }
                            val remainingData = playbackBuffer.toByteArray()
                            if (remainingData.size % 2 == 0) {
                                onAudioReceived?.invoke(remainingData)
                            }
                            playbackBuffer.reset()
                        }
                    }
                    lastAudioReceiveTime = 0L
                }
                delay(500)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startAudioStreamingLoop() {
        coroutineScope.launch {
            while (isConnected.get()) {
                try {
                    processAndSendAudioBuffer()
                    delay(audioSendInterval)
                } catch (e: Exception) {
                    log.e(TAG) { "Error en loop de audio traducción: ${e.message}" }
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

        if (totalSize > maxAudioChunkSize) {
            val result = ByteArray(maxAudioChunkSize)
            var offset = 0

            for (chunk in chunks) {
                val remainingSpace = maxAudioChunkSize - offset
                if (remainingSpace <= 0) break

                val copySize = minOf(chunk.size, remainingSpace)
                System.arraycopy(chunk, 0, result, offset, copySize)
                offset += copySize
            }
            return result
        }

        val result = ByteArray(totalSize)
        var offset = 0

        for (chunk in chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.size)
            offset += chunk.size
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
            log.e(TAG) { "Error enviando audio para traducción: ${e.message}" }
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
            log.d(TAG) { "Solicitando traducción" }
        } catch (e: Exception) {
            log.e(TAG) { "Error solicitando traducción: ${e.message}" }
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
            webSocket?.send(jsonString) ?: false
        } catch (e: Exception) {
            log.e(TAG) { "Error enviando mensaje: ${e.message}" }
            false
        }
    }

    suspend fun disconnect() {
        log.d(TAG) { "Desconectando Traductor MCN..." }
        isConnected.set(false)

        try {
            webSocket?.close(1000, "MCN Translator disconnect")
        } catch (e: Exception) {
            log.e(TAG) { "Error cerrando WebSocket: ${e.message}" }
        }

        coroutineScope.cancel()
        audioResponseChannel.close()
        transcriptionChannel.close()
        translationChannel.close()

        audioBufferMutex.withLock {
            audioBuffer.clear()
        }

        playbackMutex.withLock {
            playbackBuffer.reset()
        }
    }
}