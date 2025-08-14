package com.eddyslarez.siplibrary.data.services.ia

/**
 * OpenAI Realtime Audio Integration for AndroidWebRtcManager
 * This implementation captures remote WebRTC audio, sends it to OpenAI Realtime API,
 * and plays back the AI response locally instead of the original remote audio.
 *
 * @author Eddys Larez
 */

import android.os.Build
import androidx.annotation.RequiresApi
import com.eddyslarez.siplibrary.utils.log
import okhttp3.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import java.util.Base64
import java.util.concurrent.TimeUnit

@Serializable
data class OpenAIRealtimeRequest(
    val type: String,
    val audio: String? = null,
    @SerialName("event_id") val eventId: String? = null,
    val session: SessionConfig? = null,
    val input_audio_format: String? = null,
    val output_audio_format: String? = null
)

@Serializable
data class SessionConfig(
    val modalities: List<String> = listOf("text", "audio"),
    val instructions: String = "You are a helpful AI assistant. Keep responses concise and natural.",
    val voice: String = "alloy",
    val input_audio_format: String = "pcm16",
    val output_audio_format: String = "pcm16",
    val input_audio_transcription: InputAudioTranscription? = null,
    val turn_detection: TurnDetection? = null,
    val tools: List<Tool> = emptyList(),
    val tool_choice: String = "auto",
    val temperature: Double = 0.8,
    val max_response_output_tokens: String = "inf"
)

@Serializable
data class InputAudioTranscription(
    val model: String = "whisper-1"
)

@Serializable
data class TurnDetection(
    val type: String = "server_vad",
    val threshold: Double = 0.5,
    val prefix_padding_ms: Int = 300,
    val silence_duration_ms: Int = 500
)

@Serializable
data class Tool(
    val type: String,
    val name: String,
    val description: String,
    val parameters: JsonObject
)

@Serializable
data class OpenAIRealtimeResponse(
    val type: String,
    val audio: String? = null,
    val delta: String? = null,
    val transcript: String? = null,
    val item_id: String? = null,
    val event_id: String? = null,
    val error: ErrorInfo? = null,
    val session: SessionConfig? = null // para capturar actualizaciones de sesión
)

@Serializable
data class ErrorInfo(
    val type: String,
    val code: String,
    val message: String,
    val param: String? = null
)

class OpenAIRealtimeClient(
    private val apiKey: String,
    private val model: String = "gpt-4o-realtime-preview-2025-06-03"
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    internal var isConnected = false
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val audioResponseChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val transcriptionChannel = Channel<String>(Channel.UNLIMITED)

    private var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null

    // Json parser seguro
    private val jsonParser = Json { ignoreUnknownKeys = true }

    companion object {
        private const val TAG = "OpenAIRealtimeClient"
        private const val WEBSOCKET_URL = "wss://api.openai.com/v1/realtime"
    }

    fun setConnectionStateListener(listener: (Boolean) -> Unit) {
        onConnectionStateChanged = listener
    }

    fun setErrorListener(listener: (String) -> Unit) {
        onError = listener
    }

    suspend fun connect(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$WEBSOCKET_URL?model=$model")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("OpenAI-Beta", "realtime=v1")
                .build()

            webSocket = client.newWebSocket(request, createWebSocketListener())

            // Esperamos a que se conecte
            delay(2000)
            isConnected
        } catch (e: Exception) {
            log.e(TAG) { "Error connecting to OpenAI: ${e.message}" }
            onError?.invoke("Connection failed: ${e.message}")
            false
        }
    }

    private fun createWebSocketListener(): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            log.d(TAG) { "OpenAI WebSocket connected" }
            isConnected = true
            onConnectionStateChanged?.invoke(true)
            coroutineScope.launch { initializeSession() }
        }

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onMessage(webSocket: WebSocket, text: String) {
            coroutineScope.launch { handleMessage(text) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            log.e(TAG) { "WebSocket failure: ${t.message}" }
            isConnected = false
            onConnectionStateChanged?.invoke(false)
            onError?.invoke("WebSocket error: ${t.message}")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            log.d(TAG) { "WebSocket closed: $code $reason" }
            isConnected = false
            onConnectionStateChanged?.invoke(false)
        }
    }

    private suspend fun initializeSession() {
        val sessionConfig = SessionConfig(
            modalities = listOf("text", "audio"),
            instructions = "You are a helpful AI assistant in a phone call. Keep responses brief and natural. Respond as if you're having a conversation.",
            voice = "alloy",
            input_audio_format = "pcm16",
            output_audio_format = "pcm16",
            turn_detection = TurnDetection(
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

        val sessionUpdate = OpenAIRealtimeRequest(
            type = "session.update",
            session = sessionConfig
        )

        sendMessage(sessionUpdate)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun handleMessage(text: String) {
        try {
            val response = jsonParser.decodeFromString<OpenAIRealtimeResponse>(text)

            when (response.type) {
                "response.audio.delta" -> response.delta?.let {
                    audioResponseChannel.trySend(Base64.getDecoder().decode(it))
                }

                "response.audio_transcript.delta" -> response.delta?.let {
                    transcriptionChannel.trySend(it)
                }

                "error" -> response.error?.let {
                    log.e(TAG) { "OpenAI error: ${it.message}" }
                    onError?.invoke("OpenAI error: ${it.message}")
                }

                "session.created", "session.updated" -> {
                    log.d(TAG) { "Session event: ${response.type}" }
                }

                "input_audio_buffer.speech_started" -> log.d(TAG) { "Speech started" }
                "input_audio_buffer.speech_stopped" -> log.d(TAG) { "Speech stopped" }
                "response.created" -> log.d(TAG) { "Response created" }
                "response.done" -> log.d(TAG) { "Response done" }
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error parsing OpenAI message: ${e.message}" }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun sendAudio(audioData: ByteArray) {
        if (!isConnected) return
        val base64Audio = Base64.getEncoder().encodeToString(audioData)
        val request = OpenAIRealtimeRequest(
            type = "input_audio_buffer.append",
            audio = base64Audio
        )
        sendMessage(request)
    }

    suspend fun commitAudio() {
        if (!isConnected) return
        sendMessage(OpenAIRealtimeRequest(type = "input_audio_buffer.commit"))
    }

    suspend fun createResponse() {
        if (!isConnected) return
        val request = OpenAIRealtimeRequest(
            type = "response.create",
            eventId = "event_${System.currentTimeMillis()}"
        )
        sendMessage(request)
    }

    private suspend fun sendMessage(request: OpenAIRealtimeRequest) {
        try {
            val json = jsonParser.encodeToString(request)
            webSocket?.send(json)
        } catch (e: Exception) {
            log.e(TAG) { "Error sending message: ${e.message}" }
        }
    }

    fun getAudioResponseChannel(): ReceiveChannel<ByteArray> = audioResponseChannel
    fun getTranscriptionChannel(): ReceiveChannel<String> = transcriptionChannel

    suspend fun disconnect() {
        isConnected = false
        webSocket?.close(1000, "Client disconnect")
        coroutineScope.cancel()
        audioResponseChannel.close()
        transcriptionChannel.close()
    }
}
