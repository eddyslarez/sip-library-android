package com.eddyslarez.siplibrary.data.services.translation

import com.eddyslarez.siplibrary.data.models.*
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.util.*
import kotlin.collections.HashMap

/**
 * Cliente para la API Realtime de OpenAI para traducción de audio en tiempo real
 * 
 * @author Eddys Larez
 */
class OpenAiRealtimeClient(
    private val apiKey: String,
    private val config: TranslationConfig
) {
    companion object {
        private const val TAG = "OpenAiRealtimeClient"
        private const val OPENAI_REALTIME_URL = "wss://api.openai.com/v1/realtime"
        private const val MODEL = "gpt-4o-realtime-preview-2024-12-17"
        private const val AUDIO_FORMAT = "pcm16"
        private const val SAMPLE_RATE = 24000
    }

    private var webSocketClient: WebSocketClient? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Estados de conexión
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    // Sesiones activas por dirección
    private val activeSessions = HashMap<TranslationDirection, RealtimeSession>()
    
    // Colas de audio para procesar
    private val incomingAudioQueue = Channel<AudioTranslationRequest>(Channel.UNLIMITED)
    private val outgoingAudioQueue = Channel<AudioTranslationRequest>(Channel.UNLIMITED)
    
    // Callbacks para audio traducido
    private var onTranslatedAudio: ((AudioTranslationResponse) -> Unit)? = null
    private var onLanguageDetected: ((LanguageDetectionResult) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    
    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
    }
    
    private data class RealtimeSession(
        val sessionId: String,
        val direction: TranslationDirection,
        val sourceLanguage: String?,
        val targetLanguage: String,
        val isActive: Boolean = false
    )

    @Serializable
    private data class SessionUpdateEvent(
        val type: String = "session.update",
        val session: SessionConfig
    )

    @Serializable
    private data class SessionConfig(
        val modalities: List<String> = listOf("audio"),
        val instructions: String,
        val voice: String,
        val input_audio_format: String = AUDIO_FORMAT,
        val output_audio_format: String = AUDIO_FORMAT,
        val input_audio_transcription: AudioTranscriptionConfig? = AudioTranscriptionConfig(),
        val turn_detection: TurnDetectionConfig = TurnDetectionConfig(),
        val temperature: Double = 0.6,
        val max_response_output_tokens: String = "inf"
    )

    @Serializable
    private data class AudioTranscriptionConfig(
        val model: String = "whisper-1"
    )

    @Serializable
    private data class TurnDetectionConfig(
        val type: String = "server_vad",
        val threshold: Double = 0.5,
        val prefix_padding_ms: Int = 300,
        val silence_duration_ms: Int = 200,
        val create_response: Boolean = true
    )

    @Serializable
    private data class InputAudioBufferAppendEvent(
        val type: String = "input_audio_buffer.append",
        val audio: String
    )

    @Serializable
    private data class ResponseCreateEvent(
        val type: String = "response.create",
        val response: ResponseConfig = ResponseConfig()
    )

    @Serializable
    private data class ResponseConfig(
        val modalities: List<String> = listOf("audio"),
        val instructions: String? = null
    )

    /**
     * Conecta al servicio de OpenAI Realtime
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (_connectionState.value == ConnectionState.CONNECTED) {
            log.d(tag = TAG) { "Already connected to OpenAI Realtime" }
            return@withContext true
        }

        try {
            _connectionState.value = ConnectionState.CONNECTING
            log.d(tag = TAG) { "Connecting to OpenAI Realtime API..." }

            val uri = URI("$OPENAI_REALTIME_URL?model=$MODEL")
            val headers = mapOf(
                "Authorization" to "Bearer $apiKey",
                "OpenAI-Beta" to "realtime=v1"
            )

            webSocketClient = object : WebSocketClient(uri, headers) {
                override fun onOpen(handshake: ServerHandshake) {
                    log.d(tag = TAG) { "Connected to OpenAI Realtime API" }
                    _connectionState.value = ConnectionState.CONNECTED
                    
                    // Iniciar procesamiento de colas de audio
                    startAudioProcessing()
                }

                override fun onMessage(message: String) {
                    handleServerMessage(message)
                }

                override fun onClose(code: Int, reason: String, remote: Boolean) {
                    log.d(tag = TAG) { "Disconnected from OpenAI Realtime API: $reason" }
                    _connectionState.value = ConnectionState.DISCONNECTED
                    stopAudioProcessing()
                }

                override fun onError(ex: Exception) {
                    log.e(tag = TAG) { "OpenAI Realtime API error: ${ex.message}" }
                    _connectionState.value = ConnectionState.ERROR
                    onError?.invoke("Connection error: ${ex.message}")
                }
            }

            webSocketClient?.connect()
            
            // Esperar conexión con timeout
            var attempts = 0
            while (_connectionState.value == ConnectionState.CONNECTING && attempts < 50) {
                delay(100)
                attempts++
            }

            return@withContext _connectionState.value == ConnectionState.CONNECTED

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error connecting to OpenAI Realtime: ${e.message}" }
            _connectionState.value = ConnectionState.ERROR
            onError?.invoke("Failed to connect: ${e.message}")
            return@withContext false
        }
    }

    /**
     * Desconecta del servicio
     */
    fun disconnect() {
        log.d(tag = TAG) { "Disconnecting from OpenAI Realtime API" }
        stopAudioProcessing()
        webSocketClient?.close()
        webSocketClient = null
        activeSessions.clear()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /**
     * Configura una sesión de traducción para una dirección específica
     */
    suspend fun setupTranslationSession(
        direction: TranslationDirection,
        sourceLanguage: String?,
        targetLanguage: String
    ): String {
        val sessionId = "session_${System.currentTimeMillis()}_${direction.name.lowercase()}"
        
        val session = RealtimeSession(
            sessionId = sessionId,
            direction = direction,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            isActive = true
        )
        
        activeSessions[direction] = session
        
        // Configurar la sesión en OpenAI
        val instructions = createTranslationInstructions(sourceLanguage, targetLanguage)
        val voice = getVoiceForLanguage(targetLanguage)
        
        val sessionUpdate = SessionUpdateEvent(
            session = SessionConfig(
                instructions = instructions,
                voice = voice
            )
        )
        
        sendMessage(json.encodeToString(sessionUpdate))
        
        log.d(tag = TAG) { "Translation session setup: $direction ($sourceLanguage -> $targetLanguage)" }
        return sessionId
    }

    /**
     * Procesa audio para traducción
     */
    suspend fun translateAudio(request: AudioTranslationRequest) {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            log.w(tag = TAG) { "Not connected to OpenAI Realtime API" }
            return
        }

        when (request.direction) {
            TranslationDirection.INCOMING -> incomingAudioQueue.trySend(request)
            TranslationDirection.OUTGOING -> outgoingAudioQueue.trySend(request)
        }
    }

    /**
     * Configura callbacks para eventos
     */
    fun setCallbacks(
        onTranslatedAudio: (AudioTranslationResponse) -> Unit,
        onLanguageDetected: (LanguageDetectionResult) -> Unit,
        onError: (String) -> Unit
    ) {
        this.onTranslatedAudio = onTranslatedAudio
        this.onLanguageDetected = onLanguageDetected
        this.onError = onError
    }

    private fun startAudioProcessing() {
        // Procesar audio entrante (del remoto)
        coroutineScope.launch {
            for (request in incomingAudioQueue) {
                processAudioRequest(request)
            }
        }
        
        // Procesar audio saliente (del usuario)
        coroutineScope.launch {
            for (request in outgoingAudioQueue) {
                processAudioRequest(request)
            }
        }
    }

    private fun stopAudioProcessing() {
        incomingAudioQueue.close()
        outgoingAudioQueue.close()
        coroutineScope.cancel()
    }

    private suspend fun processAudioRequest(request: AudioTranslationRequest) {
        try {
            val session = activeSessions[request.direction]
            if (session == null || !session.isActive) {
                log.w(tag = TAG) { "No active session for direction: ${request.direction}" }
                return
            }

            // Si necesitamos detectar idioma primero
            if (request.sourceLanguage == null && config.autoDetectLanguage) {
                // Configurar sesión para detección de idioma
                setupLanguageDetectionSession(request)
                return
            }

            // Configurar sesión de traducción si es necesario
            if (session.sourceLanguage != request.sourceLanguage) {
                setupTranslationSession(
                    request.direction,
                    request.sourceLanguage,
                    request.targetLanguage
                )
            }

            // Enviar audio a OpenAI
            val base64Audio = Base64.getEncoder().encodeToString(request.audioData)
            val audioEvent = InputAudioBufferAppendEvent(audio = base64Audio)
            sendMessage(json.encodeToString(audioEvent))

            // Solicitar respuesta
            val responseEvent = ResponseCreateEvent()
            sendMessage(json.encodeToString(responseEvent))

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error processing audio request: ${e.message}" }
            onError?.invoke("Audio processing error: ${e.message}")
        }
    }

    private suspend fun setupLanguageDetectionSession(request: AudioTranslationRequest) {
        val instructions = """
            Listen to the audio and detect the language being spoken. 
            Respond only with the ISO 639-1 language code (e.g., 'es', 'en', 'fr', 'de').
            Do not translate or respond in any other way.
        """.trimIndent()

        val sessionUpdate = SessionUpdateEvent(
            session = SessionConfig(
                instructions = instructions,
                voice = "alloy",
                modalities = listOf("text") // Solo texto para detección
            )
        )

        sendMessage(json.encodeToString(sessionUpdate))

        // Enviar audio para detección
        val base64Audio = Base64.getEncoder().encodeToString(request.audioData)
        val audioEvent = InputAudioBufferAppendEvent(audio = base64Audio)
        sendMessage(json.encodeToString(audioEvent))

        val responseEvent = ResponseCreateEvent(
            response = ResponseConfig(modalities = listOf("text"))
        )
        sendMessage(json.encodeToString(responseEvent))
    }

    private fun handleServerMessage(message: String) {
        try {
            val jsonElement = json.parseToJsonElement(message)
            val messageObj = jsonElement.asJsonObject
            val type = messageObj["type"]?.asJsonPrimitive?.content

            when (type) {
                "session.created" -> {
                    log.d(tag = TAG) { "OpenAI session created" }
                }
                
                "response.audio.delta" -> {
                    handleAudioDelta(messageObj)
                }
                
                "response.text.done" -> {
                    handleTextResponse(messageObj)
                }
                
                "response.done" -> {
                    log.d(tag = TAG) { "Response completed" }
                }
                
                "input_audio_buffer.speech_started" -> {
                    log.d(tag = TAG) { "Speech detected" }
                }
                
                "input_audio_buffer.speech_stopped" -> {
                    log.d(tag = TAG) { "Speech ended" }
                }
                
                "error" -> {
                    val error = messageObj["error"]?.asJsonObject
                    val errorMessage = error?.get("message")?.asJsonPrimitive?.content ?: "Unknown error"
                    log.e(tag = TAG) { "OpenAI API error: $errorMessage" }
                    onError?.invoke("API error: $errorMessage")
                }
                
                else -> {
                    log.d(tag = TAG) { "Unhandled message type: $type" }
                }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error handling server message: ${e.message}" }
        }
    }

    private fun handleAudioDelta(messageObj: kotlinx.serialization.json.JsonObject) {
        try {
            val delta = messageObj["delta"]?.asJsonPrimitive?.content
            if (delta != null) {
                val audioBytes = Base64.getDecoder().decode(delta)
                
                // Determinar la dirección y sesión
                val direction = determineDirectionFromContext()
                val session = activeSessions[direction]
                
                if (session != null) {
                    val response = AudioTranslationResponse(
                        translatedAudio = audioBytes,
                        originalText = null,
                        translatedText = null,
                        detectedLanguage = session.sourceLanguage,
                        sessionId = session.sessionId,
                        success = true
                    )
                    
                    onTranslatedAudio?.invoke(response)
                }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error handling audio delta: ${e.message}" }
        }
    }

    private fun handleTextResponse(messageObj: kotlinx.serialization.json.JsonObject) {
        try {
            val text = messageObj["text"]?.asJsonPrimitive?.content
            if (text != null) {
                // Si es una respuesta de detección de idioma
                val detectedLang = SupportedLanguage.fromCode(text.trim())
                if (detectedLang != null) {
                    val result = LanguageDetectionResult(
                        detectedLanguage = detectedLang.code,
                        confidence = 0.9f, // OpenAI no proporciona confianza específica
                        timestamp = System.currentTimeMillis()
                    )
                    onLanguageDetected?.invoke(result)
                }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error handling text response: ${e.message}" }
        }
    }

    private fun determineDirectionFromContext(): TranslationDirection {
        // Lógica para determinar la dirección basada en el contexto actual
        // Por simplicidad, retornamos INCOMING por defecto
        return TranslationDirection.INCOMING
    }

    private fun createTranslationInstructions(sourceLanguage: String?, targetLanguage: String): String {
        val sourceLangName = SupportedLanguage.fromCode(sourceLanguage ?: "auto")?.displayName ?: "the detected language"
        val targetLangName = SupportedLanguage.fromCode(targetLanguage)?.displayName ?: targetLanguage
        
        return """
            You are a real-time translator. Your ONLY task is to translate everything you hear from $sourceLangName into $targetLanguage.
            
            CRITICAL RULES:
            1. Translate EXACTLY what is said, word-for-word
            2. Do NOT respond to questions or add commentary
            3. Do NOT interact beyond translation
            4. Maintain the same tone and emotion
            5. If you hear silence, remain silent
            6. Translate immediately as you hear speech
            
            Simply repeat everything in $targetLangName and nothing else.
        """.trimIndent()
    }

    private fun getVoiceForLanguage(languageCode: String): String {
        val language = SupportedLanguage.fromCode(languageCode)
        return language?.getVoiceForGender(config.voiceGender) ?: "alloy"
    }

    private fun sendMessage(message: String) {
        try {
            webSocketClient?.send(message)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending message: ${e.message}" }
            onError?.invoke("Send error: ${e.message}")
        }
    }

    fun dispose() {
        disconnect()
        coroutineScope.cancel()
    }
}