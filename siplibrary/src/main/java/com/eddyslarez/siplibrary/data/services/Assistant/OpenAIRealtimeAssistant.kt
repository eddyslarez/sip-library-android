package com.eddyslarez.siplibrary.data.services.Assistant

import android.content.Context
import android.util.Base64
import com.eddyslarez.siplibrary.data.services.audio.AndroidWebRtcManager
import com.eddyslarez.siplibrary.data.services.audio.WebRtcManager
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
/**
 * Asistente de Tiempo Real usando OpenAI Realtime API
 * Intercepta el audio de llamadas WebRTC y responde con IA
 */
class OpenAIRealtimeAssistant(
    private val context: Context,
    private val apiKey: String
) {
    private val TAG = "OpenAIRealtimeAssistant"

    private var webSocket: OkHttpWebSocket? = null
    private var isConnected = false
    private var isActive = false

    // Gestores de audio
    private var audioCapture: AudioCapture? = null
    private var audioPlayer: AudioPlayer? = null
    private var webRtcManager: WebRtcManager? = null

    // Configuración
    private var assistantConfig = AssistantConfig()
    private var listener: RealtimeAssistantListener? = null

    // Control de sesión
    private var sessionId: String? = null
    private var conversationId: String? = null

    data class AssistantConfig(
        val model: String = "gpt-4o-realtime-preview-2024-12-17",
        val voice: String = "verse",
        val instructions: String = "Eres un asistente telefónico útil y profesional. Responde de manera clara y concisa.",
        val turnDetection: TurnDetectionConfig = TurnDetectionConfig(),
        val inputAudioFormat: String = "pcm16",
        val outputAudioFormat: String = "pcm16",
        val inputAudioTranscription: TranscriptionConfig = TranscriptionConfig()
    )

    data class TurnDetectionConfig(
        val type: String = "server_vad",
        val threshold: Double = 0.5,
        val prefixPaddingMs: Int = 300,
        val silenceDurationMs: Int = 500
    )

    data class TranscriptionConfig(
        val model: String = "whisper-1"
    )

    interface RealtimeAssistantListener {
        fun onConnected()
        fun onDisconnected()
        fun onError(error: String)
        fun onTranscriptionReceived(text: String, isComplete: Boolean)
        fun onResponseStarted()
        fun onResponseCompleted()
        fun onFunctionCall(name: String, arguments: String)
    }

    /**
     * Inicializa el asistente con el manager WebRTC
     */
    fun initialize(webRtcManager: WebRtcManager, listener: RealtimeAssistantListener) {
        this.webRtcManager = webRtcManager
        this.listener = listener

        setupAudioComponents()
        log.d(TAG) { "Assistant initialized" }
    }

    /**
     * Configura los componentes de audio
     */
    private fun setupAudioComponents() {
        // CORRECCIÓN: Pasar el context como primer parámetro
        audioCapture = AudioCapture(context) { audioData ->
            if (isActive && isConnected) {
                sendAudioToAssistant(audioData)
            }
        }

        // Configurar reproductor de audio para respuestas del asistente
        audioPlayer = AudioPlayer()
    }

    /**
     * Conecta con la OpenAI Realtime API
     */
    /**
     * Conecta con la OpenAI Realtime API
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            log.d(TAG) { "Connecting to OpenAI Realtime API..." }

            val client = OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()

            val request = Request.Builder()
                .url("wss://api.openai.com/v1/realtime?model=${assistantConfig.model}")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("OpenAI-Beta", "realtime=v1")
                .build()

            // SOLUCIÓN: Envolver el WebSocket de OkHttp en tu clase wrapper
            val okHttpWebSocket = client.newWebSocket(request, createWebSocketListener())
            webSocket = OkHttpWebSocket(okHttpWebSocket)

            // Esperar conexión
            var attempts = 0
            while (!isConnected && attempts < 50) { // 5 segundos máximo
                delay(100)
                attempts++
            }

            if (isConnected) {
                initializeSession()
                log.d(TAG) { "Successfully connected to OpenAI Realtime API" }
            }

            isConnected
        } catch (e: Exception) {
            log.e(TAG) { "Error connecting to OpenAI Realtime API: ${e.message}" }
            listener?.onError("Connection failed: ${e.message}")
            false
        }
    }

    /**
     * Crea el listener del WebSocket
     */
    private fun createWebSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                log.d(TAG) { "WebSocket connected to OpenAI Realtime API" }
                isConnected = true
                listener?.onConnected()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleRealtimeMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                log.d(TAG) { "WebSocket closed: $code - $reason" }
                isConnected = false
                isActive = false
                listener?.onDisconnected()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                log.e(TAG) { "WebSocket error: ${t.message}" }
                isConnected = false
                isActive = false
                listener?.onError("Connection error: ${t.message}")
            }
        }
    }

    /**
     * Inicializa la sesión con configuración
     */
    private fun initializeSession() {
        val sessionUpdate = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("modalities", JSONArray().apply {
                    put("text")
                    put("audio")
                })
                put("instructions", assistantConfig.instructions)
                put("voice", assistantConfig.voice)
                put("input_audio_format", assistantConfig.inputAudioFormat)
                put("output_audio_format", assistantConfig.outputAudioFormat)
                put("input_audio_transcription", JSONObject().apply {
                    put("model", assistantConfig.inputAudioTranscription.model)
                })
                put("turn_detection", JSONObject().apply {
                    put("type", assistantConfig.turnDetection.type)
                    put("threshold", assistantConfig.turnDetection.threshold)
                    put("prefix_padding_ms", assistantConfig.turnDetection.prefixPaddingMs)
                    put("silence_duration_ms", assistantConfig.turnDetection.silenceDurationMs)
                })
                put("tools", JSONArray()) // Agregar herramientas si las necesitas
            })
        }

        sendMessage(sessionUpdate.toString())
        log.d(TAG) { "Session initialized with configuration" }
    }

    /**
     * Maneja mensajes de la API Realtime
     */
    private fun handleRealtimeMessage(message: String) {
        try {
            val json = JSONObject(message)
            val type = json.getString("type")

            log.d(TAG) { "Received message type: $type" }

            when (type) {
                "session.created" -> {
                    sessionId = json.getJSONObject("session").getString("id")
                    log.d(TAG) { "Session created: $sessionId" }
                }

                "session.updated" -> {
                    log.d(TAG) { "Session updated successfully" }
                }

                "input_audio_buffer.speech_started" -> {
                    log.d(TAG) { "Speech detected from caller" }
                }

                "input_audio_buffer.speech_stopped" -> {
                    log.d(TAG) { "Speech ended from caller" }
                }

                "conversation.item.input_audio_transcription.completed" -> {
                    val transcript = json.getString("transcript")
                    log.d(TAG) { "Transcription: $transcript" }
                    listener?.onTranscriptionReceived(transcript, true)
                }

                "response.created" -> {
                    val responseId = json.getJSONObject("response").getString("id")
                    log.d(TAG) { "Response created: $responseId" }
                    listener?.onResponseStarted()
                }

                "response.audio.delta" -> {
                    // Audio de respuesta del asistente
                    val delta = json.getString("delta")
                    val audioData = Base64.decode(delta, Base64.DEFAULT)
                    playAssistantAudio(audioData)
                }

                "response.audio.done" -> {
                    log.d(TAG) { "Assistant response audio completed" }
                    listener?.onResponseCompleted()
                }

                "response.text.delta" -> {
                    val delta = json.getString("delta")
                    log.d(TAG) { "Assistant text delta: $delta" }
                    listener?.onTranscriptionReceived(delta, false)
                }

                "error" -> {
                    val error = json.getJSONObject("error")
                    val errorMessage = error.getString("message")
                    log.e(TAG) { "OpenAI API Error: $errorMessage" }
                    listener?.onError(errorMessage)
                }

                else -> {
                    log.d(TAG) { "Unhandled message type: $type" }
                }
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error handling realtime message: ${e.message}" }
        }
    }

    /**
     * Inicia el asistente durante una llamada
     */
    fun startAssistant() {
        if (!isConnected) {
            log.w(TAG) { "Cannot start assistant - not connected" }
            return
        }

        isActive = true

        // Configurar captura de audio remoto
        webRtcManager?.let { rtc ->
            setupRemoteAudioCapture(rtc)
        }

        // Iniciar conversación
        startConversation()

        log.d(TAG) { "Assistant started and capturing remote audio" }
    }

    /**
     * Detiene el asistente
     */
    fun stopAssistant() {
        isActive = false

        audioCapture?.stop()
        audioPlayer?.stop()

        // Finalizar conversación
        if (conversationId != null) {
            val message = JSONObject().apply {
                put("type", "conversation.item.truncate")
                put("conversation_id", conversationId)
            }
            sendMessage(message.toString())
        }

        log.d(TAG) { "Assistant stopped" }
    }

    /**
     * Configura captura del audio remoto de WebRTC
     */
    private fun setupRemoteAudioCapture(webRtcManager: WebRtcManager) {
        // Esta implementación dependerá de tu WebRtcManager específico
        // Necesitas acceder al audio track remoto y capturar los datos

        if (webRtcManager is AndroidWebRtcManager) {
            setupAndroidAudioCapture(webRtcManager)
        }
    }

    /**
     * Configuración específica para Android WebRTC
     */
    private fun setupAndroidAudioCapture(androidWebRtc: AndroidWebRtcManager) {
        try {
            // Necesitas modificar AndroidWebRtcManager para exponer el audio remoto
            // O usar un MediaProjection si es necesario

            // Por ahora, usamos el micrófono del dispositivo como ejemplo
            // En producción, necesitarás capturar el audio remoto directamente
            audioCapture?.startCapturing()

        } catch (e: Exception) {
            log.e(TAG) { "Error setting up audio capture: ${e.message}" }
        }
    }

    /**
     * Inicia una nueva conversación
     */
    private fun startConversation() {
        conversationId = "conv_${System.currentTimeMillis()}"

        val message = JSONObject().apply {
            put("type", "conversation.item.create")
            put("conversation_id", conversationId)
            put("item", JSONObject().apply {
                put("type", "message")
                put("role", "system")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "input_text")
                        put("text", "Nueva llamada telefónica iniciada. El usuario llamará y tú debes responder como su asistente.")
                    })
                })
            })
        }

        sendMessage(message.toString())
    }

    /**
     * Envía datos de audio al asistente
     */
    private fun sendAudioToAssistant(audioData: ByteArray) {
        if (!isActive || !isConnected) return

        try {
            val base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP)

            val message = JSONObject().apply {
                put("type", "input_audio_buffer.append")
                put("audio", base64Audio)
            }

            sendMessage(message.toString())
        } catch (e: Exception) {
            log.e(TAG) { "Error sending audio to assistant: ${e.message}" }
        }
    }

    /**
     * Reproduce audio del asistente
     */
    private fun playAssistantAudio(audioData: ByteArray) {
        try {
            audioPlayer?.playAudio(audioData)
        } catch (e: Exception) {
            log.e(TAG) { "Error playing assistant audio: ${e.message}" }
        }
    }

    /**
     * Envía mensaje al WebSocket
     */
    private fun sendMessage(message: String) {
        webSocket?.send(message)
    }

    /**
     * Desconecta del asistente
     */
    fun disconnect() {
        isActive = false
        isConnected = false

        audioCapture?.stop()
        audioPlayer?.stop()
        webSocket?.close(1000, "Client disconnect")

        log.d(TAG) { "Disconnected from assistant" }
    }

    /**
     * Actualiza la configuración del asistente
     */
    fun updateConfig(config: AssistantConfig) {
        this.assistantConfig = config

        if (isConnected) {
            initializeSession() // Reinicializar con nueva configuración
        }
    }

    /**
     * Envía texto al asistente
     */
    fun sendTextToAssistant(text: String) {
        if (!isConnected || !isActive) return

        val message = JSONObject().apply {
            put("type", "conversation.item.create")
            put("conversation_id", conversationId)
            put("item", JSONObject().apply {
                put("type", "message")
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "input_text")
                        put("text", text)
                    })
                })
            })
        }

        sendMessage(message.toString())

        // Crear respuesta
        val responseMessage = JSONObject().apply {
            put("type", "response.create")
        }
        sendMessage(responseMessage.toString())
    }
}
