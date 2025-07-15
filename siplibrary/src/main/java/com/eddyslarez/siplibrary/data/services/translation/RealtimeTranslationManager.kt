package com.eddyslarez.siplibrary.data.services.translation

import android.app.Application
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer

/**
 * Gestor de traducción en tiempo real usando OpenAI Realtime API
 * 
 * @author Eddys Larez
 */
class RealtimeTranslationManager(
    private val application: Application
) {
    companion object {
        private const val TAG = "RealtimeTranslationManager"
        private const val OPENAI_REALTIME_WS_URL = "wss://api.openai.com/v1/realtime"
        private const val MODEL = "gpt-4o-realtime-preview-2025-06-03"
    }

    private var webSocketClient: WebSocketClient? = null
    private var isConnected = false
    private var apiKey: String? = null
    private var sessionId: String? = null
    
    // Estados de traducción
    private val _translationState = MutableStateFlow(TranslationState.IDLE)
    val translationState: StateFlow<TranslationState> = _translationState.asStateFlow()
    
    private val _isTranslationEnabled = MutableStateFlow(false)
    val isTranslationEnabled: StateFlow<Boolean> = _isTranslationEnabled.asStateFlow()
    
    // Configuración de idiomas
    private val _sourceLanguage = MutableStateFlow("es") // Español por defecto
    val sourceLanguage: StateFlow<String> = _sourceLanguage.asStateFlow()
    
    private val _targetLanguage = MutableStateFlow("en") // Inglés por defecto
    val targetLanguage: StateFlow<String> = _targetLanguage.asStateFlow()
    
    // Listener para eventos de traducción
    private var translationListener: TranslationEventListener? = null
    
    // Audio buffers
    private val audioInputBuffer = mutableListOf<ByteArray>()
    private val audioOutputBuffer = mutableListOf<ByteArray>()
    
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Inicializar el gestor de traducción
     */
    fun initialize(apiKey: String) {
        this.apiKey = apiKey
        log.d(tag = TAG) { "RealtimeTranslationManager initialized" }
    }

    /**
     * Configurar idiomas de traducción
     */
    fun setLanguages(sourceLanguage: String, targetLanguage: String) {
        _sourceLanguage.value = sourceLanguage
        _targetLanguage.value = targetLanguage
        log.d(tag = TAG) { "Languages set: $sourceLanguage -> $targetLanguage" }
    }

    /**
     * Habilitar/deshabilitar traducción
     */
    fun setTranslationEnabled(enabled: Boolean) {
        _isTranslationEnabled.value = enabled
        
        if (enabled && !isConnected) {
            connectToRealtimeAPI()
        } else if (!enabled && isConnected) {
            disconnectFromRealtimeAPI()
        }
        
        log.d(tag = TAG) { "Translation ${if (enabled) "enabled" else "disabled"}" }
    }

    /**
     * Configurar listener para eventos de traducción
     */
    fun setTranslationListener(listener: TranslationEventListener) {
        this.translationListener = listener
    }

    /**
     * Conectar a OpenAI Realtime API
     */
    private fun connectToRealtimeAPI() {
        if (apiKey == null) {
            log.e(tag = TAG) { "API key not set" }
            return
        }

        try {
            _translationState.value = TranslationState.CONNECTING
            
            val uri = URI("$OPENAI_REALTIME_WS_URL?model=$MODEL")
            val headers = mapOf(
                "Authorization" to "Bearer $apiKey",
                "OpenAI-Beta" to "realtime=v1"
            )

            webSocketClient = object : WebSocketClient(uri, headers) {
                override fun onOpen(handshake: ServerHandshake) {
                    isConnected = true
                    _translationState.value = TranslationState.CONNECTED
                    log.d(tag = TAG) { "Connected to OpenAI Realtime API" }
                    
                    // Configurar sesión inicial
                    initializeSession()
                }

                override fun onMessage(message: String) {
                    handleRealtimeEvent(message)
                }

                override fun onMessage(bytes: ByteBuffer) {
                    // Manejar audio recibido de OpenAI
                    val audioData = ByteArray(bytes.remaining())
                    bytes.get(audioData)
                    handleTranslatedAudio(audioData)
                }

                override fun onClose(code: Int, reason: String, remote: Boolean) {
                    isConnected = false
                    _translationState.value = TranslationState.DISCONNECTED
                    log.d(tag = TAG) { "Disconnected from OpenAI Realtime API: $reason" }
                }

                override fun onError(ex: Exception) {
                    _translationState.value = TranslationState.ERROR
                    log.e(tag = TAG) { "WebSocket error: ${ex.message}" }
                    translationListener?.onTranslationError("Connection error: ${ex.message}")
                }
            }

            webSocketClient?.connect()
            
        } catch (e: Exception) {
            _translationState.value = TranslationState.ERROR
            log.e(tag = TAG) { "Error connecting to Realtime API: ${e.message}" }
        }
    }

    /**
     * Desconectar de OpenAI Realtime API
     */
    private fun disconnectFromRealtimeAPI() {
        webSocketClient?.close()
        webSocketClient = null
        isConnected = false
        _translationState.value = TranslationState.DISCONNECTED
    }

    /**
     * Inicializar sesión con configuración de traducción
     */
    private fun initializeSession() {
        val sessionConfig = SessionUpdateEvent(
            session = SessionConfig(
                modalities = listOf("text", "audio"),
                instructions = buildTranslationInstructions(),
                voice = "alloy",
                input_audio_format = "pcm16",
                output_audio_format = "pcm16",
                input_audio_transcription = TranscriptionConfig(model = "whisper-1"),
                turn_detection = TurnDetectionConfig(
                    type = "server_vad",
                    threshold = 0.5,
                    prefix_padding_ms = 300,
                    silence_duration_ms = 500
                ),
                tools = emptyList(),
                tool_choice = "auto",
                temperature = 0.8,
                max_response_output_tokens = "inf"
            )
        )

        sendRealtimeEvent(sessionConfig)
    }

    /**
     * Construir instrucciones de traducción
     */
    private fun buildTranslationInstructions(): String {
        val sourceLang = getLanguageName(_sourceLanguage.value)
        val targetLang = getLanguageName(_targetLanguage.value)
        
        return """
            You are a real-time translator. Your job is to:
            1. Listen to speech in $sourceLang
            2. Translate it accurately to $targetLang
            3. Respond ONLY with the translated audio in $targetLang
            4. Maintain the tone and emotion of the original speech
            5. Be as fast and accurate as possible
            6. Do not add explanations or comments, just translate
            
            Source language: $sourceLang
            Target language: $targetLang
        """.trimIndent()
    }

    /**
     * Obtener nombre completo del idioma
     */
    private fun getLanguageName(code: String): String {
        return when (code) {
            "es" -> "Spanish"
            "en" -> "English"
            "fr" -> "French"
            "de" -> "German"
            "it" -> "Italian"
            "pt" -> "Portuguese"
            "ru" -> "Russian"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "zh" -> "Chinese"
            else -> "English"
        }
    }

    /**
     * Enviar audio para traducir
     */
    fun sendAudioForTranslation(audioData: ByteArray) {
        if (!isConnected || !_isTranslationEnabled.value) {
            return
        }

        try {
            val audioEvent = InputAudioBufferAppendEvent(
                audio = audioData.toBase64()
            )
            
            sendRealtimeEvent(audioEvent)
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending audio: ${e.message}" }
        }
    }

    /**
     * Manejar eventos de OpenAI Realtime API
     */
    private fun handleRealtimeEvent(message: String) {
        try {
            val json = Json { ignoreUnknownKeys = true }
            val event = json.decodeFromString<RealtimeEvent>(message)
            
            when (event.type) {
                "session.created" -> {
                    log.d(tag = TAG) { "Session created" }
                    translationListener?.onTranslationSessionStarted()
                }
                
                "input_audio_buffer.speech_started" -> {
                    log.d(tag = TAG) { "Speech started" }
                    translationListener?.onSpeechDetected()
                }
                
                "input_audio_buffer.speech_stopped" -> {
                    log.d(tag = TAG) { "Speech stopped" }
                    // Procesar el audio acumulado
                    val commitEvent = InputAudioBufferCommitEvent()
                    sendRealtimeEvent(commitEvent)
                }
                
                "conversation.item.input_audio_transcription.completed" -> {
                    val transcription = json.decodeFromString<TranscriptionCompletedEvent>(message)
                    log.d(tag = TAG) { "Transcription: ${transcription.transcript}" }
                    translationListener?.onTranscriptionReceived(transcription.transcript)
                }
                
                "response.audio.delta" -> {
                    val audioEvent = json.decodeFromString<AudioDeltaEvent>(message)
                    val audioData = audioEvent.delta.fromBase64()
                    handleTranslatedAudio(audioData)
                }
                
                "response.done" -> {
                    log.d(tag = TAG) { "Translation response completed" }
                    translationListener?.onTranslationCompleted()
                }
                
                "error" -> {
                    val errorEvent = json.decodeFromString<ErrorEvent>(message)
                    log.e(tag = TAG) { "API Error: ${errorEvent.error.message}" }
                    translationListener?.onTranslationError(errorEvent.error.message)
                }
            }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error handling realtime event: ${e.message}" }
        }
    }

    /**
     * Manejar audio traducido recibido
     */
    private fun handleTranslatedAudio(audioData: ByteArray) {
        audioOutputBuffer.add(audioData)
        translationListener?.onTranslatedAudioReceived(audioData)
    }

    /**
     * Enviar evento a OpenAI Realtime API
     */
    private fun sendRealtimeEvent(event: Any) {
        if (!isConnected) return
        
        try {
            val json = Json.encodeToString(event)
            webSocketClient?.send(json)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending event: ${e.message}" }
        }
    }

    /**
     * Limpiar recursos
     */
    fun dispose() {
        coroutineScope.cancel()
        disconnectFromRealtimeAPI()
        audioInputBuffer.clear()
        audioOutputBuffer.clear()
        translationListener = null
    }

    // Extensiones para Base64
    private fun ByteArray.toBase64(): String {
        return android.util.Base64.encodeToString(this, android.util.Base64.NO_WRAP)
    }

    private fun String.fromBase64(): ByteArray {
        return android.util.Base64.decode(this, android.util.Base64.NO_WRAP)
    }
}

/**
 * Estados de traducción
 */
enum class TranslationState {
    IDLE,
    CONNECTING,
    CONNECTED,
    TRANSLATING,
    DISCONNECTED,
    ERROR
}

/**
 * Listener para eventos de traducción
 */
interface TranslationEventListener {
    fun onTranslationSessionStarted()
    fun onSpeechDetected()
    fun onTranscriptionReceived(text: String)
    fun onTranslatedAudioReceived(audioData: ByteArray)
    fun onTranslationCompleted()
    fun onTranslationError(error: String)
}

// Modelos de datos para OpenAI Realtime API
@Serializable
data class RealtimeEvent(
    val type: String
)

@Serializable
data class SessionUpdateEvent(
    val type: String = "session.update",
    val session: SessionConfig
)

@Serializable
data class SessionConfig(
    val modalities: List<String>,
    val instructions: String,
    val voice: String,
    val input_audio_format: String,
    val output_audio_format: String,
    val input_audio_transcription: TranscriptionConfig,
    val turn_detection: TurnDetectionConfig,
    val tools: List<String>,
    val tool_choice: String,
    val temperature: Double,
    val max_response_output_tokens: String
)

@Serializable
data class TranscriptionConfig(
    val model: String
)

@Serializable
data class TurnDetectionConfig(
    val type: String,
    val threshold: Double,
    val prefix_padding_ms: Int,
    val silence_duration_ms: Int
)

@Serializable
data class InputAudioBufferAppendEvent(
    val type: String = "input_audio_buffer.append",
    val audio: String
)

@Serializable
data class InputAudioBufferCommitEvent(
    val type: String = "input_audio_buffer.commit"
)

@Serializable
data class TranscriptionCompletedEvent(
    val type: String,
    val transcript: String
)

@Serializable
data class AudioDeltaEvent(
    val type: String,
    val delta: String
)

@Serializable
data class ErrorEvent(
    val type: String,
    val error: ApiError
)

@Serializable
data class ApiError(
    val message: String,
    val type: String? = null,
    val code: String? = null
)