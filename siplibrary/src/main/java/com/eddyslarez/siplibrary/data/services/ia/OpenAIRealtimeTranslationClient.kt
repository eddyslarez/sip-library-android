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
import java.util.concurrent.TimeUnit

/**
 * Cliente mejorado de OpenAI Realtime con soporte para traducción
 * Soluciona el problema de que la IA deje de responder
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
    private var onTranslatedText: ((String, String) -> Unit)? = null // original, translated
    private var onSpeechDetected: ((Boolean) -> Unit)? = null // true = started, false = stopped

    // Json parser
    private val jsonParser = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "OpenAITranslationClient"
        private const val WEBSOCKET_URL = "wss://api.openai.com/v1/realtime"
        private const val SESSION_TIMEOUT = 30000L // 30 segundos
        private const val KEEPALIVE_INTERVAL = 15000L // 15 segundos
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

            // Inicializar sesión inmediatamente
            coroutineScope.launch {
                delay(100) // Pequeña pausa para estabilizar
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

        // Instrucciones mejoradas para traducción
        val translationInstructions = """
        Eres un traductor profesional en tiempo real. Tu única función es traducir entre ${languagePair.inputLanguage} y ${languagePair.outputLanguage}.

        REGLAS CRÍTICAS:
        1. Si el usuario habla en ${languagePair.inputLanguage}, RESPONDE ÚNICAMENTE en ${languagePair.outputLanguage}
        2. Si el usuario habla en ${languagePair.outputLanguage}, RESPONDE ÚNICAMENTE en ${languagePair.inputLanguage}  
        3. NUNCA respondas en el mismo idioma que el usuario
        4. NUNCA agregues explicaciones, comentarios o saludos
        5. Solo proporciona la traducción directa
        6. Si no entiendes algo, responde "No entendí" en el idioma de salida correspondiente
        7. Mantente siempre activo y listo para traducir
        8. Cada mensaje del usuario requiere una respuesta tuya
        
        IMPORTANTE: Después de cada traducción, quédate en silencio esperando el próximo audio del usuario.
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

                // VAD configurado para ser más responsivo
                put("turn_detection", buildJsonObject {
                    put("type", "server_vad")
                    put("threshold", voiceSettings.vadThreshold)
                    put("prefix_padding_ms", voiceSettings.vadPrefixPadding)
                    put("silence_duration_ms", voiceSettings.vadSilenceDuration)
                })

                put("temperature", voiceSettings.temperature)
                put("max_response_output_tokens", voiceSettings.maxResponseTokens)
            })
        }

        sendMessage(sessionConfig)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun handleMessage(text: String) {
        try {
            val json = JsonParser.parseString(text).asJsonObject
            val type = json.get("type").asString

            log.d(TAG) { "Received message type: $type" }

            when (type) {
                "session.created" -> {
                    sessionId = json.get("session")?.asJsonObject?.get("id")?.asString
                    log.d(TAG) { "Session created: $sessionId" }
                }

                "session.updated" -> {
                    log.d(TAG) { "Session updated successfully" }
                }

                "input_audio_buffer.speech_started" -> {
                    log.d(TAG) { "Speech detection started" }
                    isProcessingAudio = true
                    onSpeechDetected?.invoke(true)
                }

                "input_audio_buffer.speech_stopped" -> {
                    log.d(TAG) { "Speech detection stopped" }
                    isProcessingAudio = false
                    onSpeechDetected?.invoke(false)

                    // CRÍTICO: Forzar la generación de respuesta
                    delay(100)
                    createResponse()
                }

                "conversation.item.created" -> {
                    val item = json.getAsJsonObject("item")
                    val role = item.get("role").asString

                    if (role == "user") {
                        val content = item.getAsJsonArray("content")
                        if (content.size() > 0) {
                            val firstContent = content[0].asJsonObject
                            if (firstContent.get("type").asString == "input_text") {
                                val userText = firstContent.get("text").asString
                                log.d(TAG) { "User said: $userText" }
                            }
                        }
                    }
                }

                "response.created" -> {
                    log.d(TAG) { "Response created" }
                }

                "response.output_item.added" -> {
                    log.d(TAG) { "Response item added" }
                }

                "response.content_part.added" -> {
                    log.d(TAG) { "Content part added" }
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
                    log.d(TAG) { "AI translated: $transcript" }
                    onTranslatedText?.invoke("", transcript) // Sin texto original en este punto
                }

                "response.done" -> {
                    log.d(TAG) { "Response completed - ready for next input" }
                    // CRÍTICO: Preparar para la próxima entrada
                    isProcessingAudio = false
                }

                "error" -> {
                    val error = json.getAsJsonObject("error")
                    val message = error.get("message").asString
                    val code = error.get("code")?.asString ?: "unknown"
                    log.e(TAG) { "OpenAI error [$code]: $message" }
                    onError?.invoke("Error OpenAI: $message")

                    // Reintentar conexión en caso de error
                    if (code == "invalid_session" || code == "session_expired") {
                        reconnect()
                    }
                }

                else -> {
                    log.d(TAG) { "Unhandled message type: $type" }
                }
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error parsing message: ${e.message}" }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun sendAudio(audioData: ByteArray) {
        if (!isConnected || isProcessingAudio) return

        try {
            val base64Audio = Base64.getEncoder().encodeToString(audioData)
            val request = buildJsonObject {
                put("type", "input_audio_buffer.append")
                put("audio", base64Audio)
            }
            sendMessage(request)
        } catch (e: Exception) {
            log.e(TAG) { "Error sending audio: ${e.message}" }
        }
    }

    suspend fun commitAudio() {
        if (!isConnected) return

        try {
            val commitMessage = buildJsonObject {
                put("type", "input_audio_buffer.commit")
            }
            sendMessage(commitMessage)

            // Esperar un momento antes de solicitar respuesta
            delay(50)
            createResponse()
        } catch (e: Exception) {
            log.e(TAG) { "Error committing audio: ${e.message}" }
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
            log.d(TAG) { "Sent message: ${jsonObject.get("type").toString()}" }
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
                    log.w(TAG) { "Session timeout detected, reconnecting..." }
                    reconnect()
                    break
                }

                // Usar WebSocket ping nativo en lugar de mensajes de API
                try {
                    if (webSocket != null && isConnected) {
                        // WebSocket ping nativo (más eficiente)
                        val pingBytes = "ping".toByteArray()
                        webSocket?.send(okio.ByteString.of(*pingBytes))
                        log.d(TAG) { "Sent WebSocket ping" }
                    }
                } catch (e: Exception) {
                    log.e(TAG) { "Error sending keepalive ping: ${e.message}" }
                    // Fallback: usar input_audio_buffer.clear
                    try {
                        if (isConnected) {
                            val keepAliveMessage = buildJsonObject {
                                put("type", "input_audio_buffer.clear")
                            }
                            sendMessage(keepAliveMessage)
                        }
                    } catch (fallbackException: Exception) {
                        log.e(TAG) { "Fallback keepalive failed: ${fallbackException.message}" }
                    }
                }
            }
        }
    }

    private suspend fun reconnect() {
        log.d(TAG) { "Attempting to reconnect..." }
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
