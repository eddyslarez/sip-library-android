package com.eddyslarez.siplibrary.data.services.translation

import android.app.Application
import android.util.Base64
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import okhttp3.Request

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
        private const val OPENAI_REALTIME_URL = "wss://api.openai.com/v1/realtime"
        private const val MODEL = "gpt-4o-realtime-preview-2025-06-03"
    }

    private var webSocket: WebSocket? = null
    private var openAiApiKey: String? = null
    private var sourceLanguage: String = "es"
    private var targetLanguage: String = "en"
    private var isTranslationEnabled: Boolean = false
    private var listener: TranslationEventListener? = null
    private var sessionId: String? = null
    private var isConnected: Boolean = false
    private var isTestMode: Boolean = false
    private var testTargetLanguage: String = "ru"

    // Estados
    private val _translationState = MutableStateFlow(TranslationState.IDLE)
    val translationState: StateFlow<TranslationState> = _translationState.asStateFlow()

    // Audio buffering
    private val audioBuffer = mutableListOf<ByteArray>()
    private val audioBufferMutex = Mutex()

    // Coroutine scope
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Inicializar con API key de OpenAI
     */
    fun initialize(apiKey: String) {
        this.openAiApiKey = apiKey
        log.d(tag = TAG) { "RealtimeTranslationManager initialized" }
    }

    /**
     * Configurar idiomas de traducción
     */
    fun setLanguages(sourceLanguage: String, targetLanguage: String) {
        this.sourceLanguage = sourceLanguage
        this.targetLanguage = targetLanguage
        log.d(tag = TAG) { "Languages set: $sourceLanguage -> $targetLanguage" }
    }

    /**
     * Configurar listener para eventos de traducción
     */
    fun setTranslationListener(listener: TranslationEventListener) {
        this.listener = listener
    }

    /**
     * Habilitar/deshabilitar traducción
     */
    fun setTranslationEnabled(enabled: Boolean) {
        this.isTranslationEnabled = enabled
        if (enabled) {
            startRealtimeSession()
        } else {
            stopRealtimeSession()
        }
    }

    /**
     * Habilitar modo de prueba (siempre traduce al ruso)
     */
    fun setTestMode(enabled: Boolean, targetLanguage: String = "ru") {
        this.isTestMode = enabled
        this.testTargetLanguage = targetLanguage
        log.d(tag = TAG) { "Test mode ${if (enabled) "enabled" else "disabled"} -> $targetLanguage" }
    }

    /**
     * Iniciar sesión con OpenAI Realtime API
     */
    private fun startRealtimeSession() {
        if (openAiApiKey == null) {
            log.e(tag = TAG) { "OpenAI API key not set" }
            return
        }

        if (isConnected) {
            log.d(tag = TAG) { "Already connected to OpenAI Realtime API" }
            return
        }

        try {
            _translationState.value = TranslationState.CONNECTING

            val client = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()

            val request = Request.Builder()
                .url("$OPENAI_REALTIME_URL?model=$MODEL")
                .addHeader("Authorization", "Bearer $openAiApiKey")
                .addHeader("OpenAI-Beta", "realtime=v1")
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    log.d(tag = TAG) { "Connected to OpenAI Realtime API" }
                    isConnected = true
                    initializeSession()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleRealtimeMessage(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    log.d(tag = TAG) { "WebSocket closing: $code - $reason" }
                    isConnected = false
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    log.e(tag = TAG) { "WebSocket error: ${t.message}" }
                    isConnected = false
                    _translationState.value = TranslationState.ERROR
                    listener?.onTranslationError("Connection failed: ${t.message}")
                }
            })

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error starting realtime session: ${e.message}" }
            _translationState.value = TranslationState.ERROR
            listener?.onTranslationError("Failed to start session: ${e.message}")
        }
    }

    /**
     * Inicializar sesión después de conectar
     */
    private fun initializeSession() {
        try {
            sessionId = "session_${System.currentTimeMillis()}"

            val finalTargetLanguage = if (isTestMode) testTargetLanguage else targetLanguage

            val sessionConfig = JSONObject().apply {
                put("type", "session.update")
                put("session", JSONObject().apply {
                    put("modalities", JSONArray().apply {
                        put("text")
                        put("audio")
                    })
                    put("instructions", "You are a real-time translator. Translate speech from $sourceLanguage to $finalTargetLanguage. Only return the translated audio, no additional text.")
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
                    put("tools", JSONArray())
                    put("tool_choice", "none")
                    put("temperature", 0.6)
                    put("max_response_output_tokens", 4096)
                })
            }

            webSocket?.send(sessionConfig.toString())

            _translationState.value = TranslationState.READY
            listener?.onTranslationSessionStarted()

            log.d(tag = TAG) { "Session initialized with ID: $sessionId" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error initializing session: ${e.message}" }
            _translationState.value = TranslationState.ERROR
            listener?.onTranslationError("Session initialization failed: ${e.message}")
        }
    }

    /**
     * Manejar mensajes de la API Realtime
     */
    private fun handleRealtimeMessage(message: String) {
        try {
            val json = JSONObject(message)
            val type = json.getString("type")

            log.d(tag = TAG) { "Received message type: $type" }

            when (type) {
                "session.created" -> {
                    log.d(tag = TAG) { "Session created successfully" }
                    _translationState.value = TranslationState.READY
                }

                "session.updated" -> {
                    log.d(tag = TAG) { "Session updated" }
                }

                "input_audio_buffer.committed" -> {
                    log.d(tag = TAG) { "Audio buffer committed" }
                    _translationState.value = TranslationState.PROCESSING
                }

                "input_audio_buffer.speech_started" -> {
                    log.d(tag = TAG) { "Speech detected" }
                    _translationState.value = TranslationState.DETECTING_SPEECH
                    listener?.onSpeechDetected()
                }

                "input_audio_buffer.speech_stopped" -> {
                    log.d(tag = TAG) { "Speech stopped" }
                    _translationState.value = TranslationState.PROCESSING
                }

                "conversation.item.input_audio_transcription.completed" -> {
                    val transcript = json.getJSONObject("transcript")
                    val text = transcript.getString("text")
                    log.d(tag = TAG) { "Transcription: $text" }
                    listener?.onTranscriptionReceived(text)
                }

                "response.audio.delta" -> {
                    val audioData = json.getString("delta")
                    val audioBytes = Base64.decode(audioData, Base64.DEFAULT)
                    log.d(tag = TAG) { "Received audio delta: ${audioBytes.size} bytes" }
                    listener?.onTranslatedAudioReceived(audioBytes)
                }

                "response.audio_transcript.delta" -> {
                    val delta = json.getString("delta")
                    log.d(tag = TAG) { "Audio transcript delta: $delta" }
                }

                "response.done" -> {
                    log.d(tag = TAG) { "Response completed" }
                    _translationState.value = TranslationState.READY
                    listener?.onTranslationCompleted()
                }

                "error" -> {
                    val error = json.getJSONObject("error")
                    val errorMessage = error.getString("message")
                    log.e(tag = TAG) { "API Error: $errorMessage" }
                    _translationState.value = TranslationState.ERROR
                    listener?.onTranslationError("API Error: $errorMessage")
                }

                else -> {
                    log.d(tag = TAG) { "Unhandled message type: $type" }
                }
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error handling realtime message: ${e.message}" }
            listener?.onTranslationError("Message handling error: ${e.message}")
        }
    }

    /**
     * Enviar audio para traducción
     */
    fun sendAudioForTranslation(audioData: ByteArray) {
        if (!isConnected || !isTranslationEnabled) {
            return
        }

        try {
            // Convertir audio a base64
            val base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP)

            val audioMessage = JSONObject().apply {
                put("type", "input_audio_buffer.append")
                put("audio", base64Audio)
            }

            webSocket?.send(audioMessage.toString())

            log.d(tag = TAG) { "Sent audio data: ${audioData.size} bytes" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending audio: ${e.message}" }
            listener?.onTranslationError("Audio sending failed: ${e.message}")
        }
    }

    /**
     * Confirmar buffer de audio (trigger processing)
     */
    fun commitAudioBuffer() {
        if (!isConnected || !isTranslationEnabled) {
            return
        }

        try {
            val commitMessage = JSONObject().apply {
                put("type", "input_audio_buffer.commit")
            }

            webSocket?.send(commitMessage.toString())
            log.d(tag = TAG) { "Audio buffer committed" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error committing audio buffer: ${e.message}" }
        }
    }

    /**
     * Limpiar buffer de audio
     */
    fun clearAudioBuffer() {
        if (!isConnected) {
            return
        }

        try {
            val clearMessage = JSONObject().apply {
                put("type", "input_audio_buffer.clear")
            }

            webSocket?.send(clearMessage.toString())
            log.d(tag = TAG) { "Audio buffer cleared" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error clearing audio buffer: ${e.message}" }
        }
    }

    /**
     * Detener sesión de traducción
     */
    private fun stopRealtimeSession() {
        try {
            isConnected = false
            webSocket?.close(1000, "Translation disabled")
            webSocket = null
            sessionId = null
            _translationState.value = TranslationState.IDLE

            log.d(tag = TAG) { "Realtime session stopped" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error stopping session: ${e.message}" }
        }
    }

    /**
     * Obtener estado actual de la traducción
     */
    fun getCurrentState(): TranslationState = _translationState.value

    /**
     * Verificar si está conectado
     */
    fun isConnected(): Boolean = isConnected

    /**
     * Liberar recursos
     */
    fun dispose() {
        coroutineScope.cancel()
        stopRealtimeSession()
        listener = null

        coroutineScope.launch {
            audioBufferMutex.withLock {
                audioBuffer.clear()
            }
        }

        log.d(tag = TAG) { "RealtimeTranslationManager disposed" }
    }
}
/**
 * Estados de traducción
 */
enum class TranslationState {
    IDLE,
    CONNECTING,
    READY,
    DETECTING_SPEECH,
    PROCESSING,
    TRANSLATING,
    ERROR
}

/**
 * Direcciones de traducción
 */
enum class TranslationDirection {
    NONE,
    OUTGOING_ONLY,
    INCOMING_ONLY,
    BIDIRECTIONAL
}

/**
 * Información de traducción para llamadas
 */
data class CallTranslationInfo(
    val isTranslationEnabled: Boolean,
    val localLanguage: String,
    val remoteLanguage: String,
    val translationDirection: TranslationDirection,
    val supportsRealtime: Boolean = true
)

/**
 * Capacidades de traducción
 */
data class TranslationCapability(
    val supportsTranslation: Boolean,
    val preferredLanguage: String,
    val translationEnabled: Boolean,
    val supportedLanguages: List<String> = listOf("es", "en", "fr", "de", "it", "pt", "ru", "zh", "ja", "ko")
)

/**
 * Resultado de compatibilidad de traducción
 */
enum class TranslationCompatibilityResult {
    FULLY_SUPPORTED,
    PARTIALLY_SUPPORTED,
    NOT_SUPPORTED,
    LANGUAGE_MISMATCH
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
