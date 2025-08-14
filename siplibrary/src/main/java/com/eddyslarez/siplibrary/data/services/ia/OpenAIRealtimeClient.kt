package com.eddyslarez.siplibrary.data.services.ia

/**
 * OpenAI Realtime Audio Integration for AndroidWebRtcManager
 * This implementation captures remote WebRTC audio, sends it to OpenAI Realtime API,
 * and plays back the AI response locally instead of the original remote audio.
 *
 * @author Eddys Larez
 */
/**
 * FIXED OpenAI Realtime Audio Integration for AndroidWebRtcManager
 * This implementation captures remote WebRTC audio, sends it to OpenAI Realtime API,
 * and plays back the AI response locally instead of the original remote audio.
 *
 * @author Eddys Larez - Fixed version
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
import java.util.concurrent.atomic.AtomicBoolean

// FIXED: Separate data classes for different message types
@Serializable
data class SessionUpdateMessage(
    val type: String = "session.update",
    @SerialName("event_id") val eventId: String? = null,
    // Session configuration directly at root level, not nested
    val modalities: List<String>? = null,
    val instructions: String? = null,
    val voice: String? = null,
    val input_audio_format: String? = null,
    val output_audio_format: String? = null,
    val input_audio_transcription: InputAudioTranscription? = null,
    val turn_detection: TurnDetection? = null,
    val tools: List<Tool>? = null,
    val tool_choice: String? = null,
    val temperature: Double? = null,
    val max_response_output_tokens: String? = null
)

@Serializable
data class AudioAppendMessage(
    val type: String = "input_audio_buffer.append",
    @SerialName("event_id") val eventId: String? = null,
    val audio: String
)

@Serializable
data class AudioCommitMessage(
    val type: String = "input_audio_buffer.commit",
    @SerialName("event_id") val eventId: String? = null
)

@Serializable
data class ResponseCreateMessage(
    val type: String = "response.create",
    @SerialName("event_id") val eventId: String? = null
)

@Serializable
data class InputAudioTranscription(
    val model: String = "whisper-1"
)

@Serializable
data class TurnDetection(
    val type: String = "server_vad", // Changed from semantic_vad to server_vad for better compatibility
    val threshold: Double? = 0.5,
    val prefix_padding_ms: Int? = 300,
    val silence_duration_ms: Int? = 200
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
    val session: SessionInfo? = null,
    val response: ResponseInfo? = null,
    val item: ItemInfo? = null
)

@Serializable
data class ErrorInfo(
    val type: String,
    val code: String,
    val message: String,
    val param: String? = null,
    val event_id: String? = null
)

@Serializable
data class SessionInfo(
    val id: String? = null,
    val model: String? = null,
    val expires_at: Long? = null,
    val modalities: List<String>? = null,
    val instructions: String? = null,
    val voice: String? = null,
    val input_audio_format: String? = null,
    val output_audio_format: String? = null,
    val input_audio_transcription: InputAudioTranscription? = null,
    val turn_detection: TurnDetection? = null,
    val tools: List<Tool>? = null,
    val tool_choice: String? = null,
    val temperature: Double? = null,
    val max_response_output_tokens: String? = null
)

@Serializable
data class ResponseInfo(
    val id: String? = null,
    val status: String? = null,
    val status_details: JsonObject? = null
)

@Serializable
data class ItemInfo(
    val id: String? = null,
    val type: String? = null,
    val role: String? = null,
    val content: List<ContentInfo>? = null
)

@Serializable
data class ContentInfo(
    val type: String? = null,
    val audio: String? = null,
    val transcript: String? = null
)

class OpenAIRealtimeClient(
    private val apiKey: String,
    private val model: String = "gpt-4o-realtime-preview-2024-10-01" // Updated model name
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    internal val isConnected = AtomicBoolean(false)
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Channels for responses
    private val audioResponseChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val transcriptionChannel = Channel<String>(Channel.UNLIMITED)
    private val fullTranscriptChannel = Channel<String>(Channel.UNLIMITED)

    // Callbacks
    private var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var onSpeechStarted: (() -> Unit)? = null
    private var onSpeechStopped: (() -> Unit)? = null
    private var onResponseCreated: ((String) -> Unit)? = null
    private var onResponseDone: ((String) -> Unit)? = null

    // Json parser with robust configuration
    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false // Don't encode null values
        explicitNulls = false
    }

    // Reconnection control
    private var reconnectJob: Job? = null
    private var shouldReconnect = AtomicBoolean(true)
    private val maxReconnectAttempts = 5
    private var reconnectAttempts = 0

    companion object {
        private const val TAG = "OpenAIRealtimeClient"
        private const val WEBSOCKET_URL = "wss://api.openai.com/v1/realtime"
    }

    // Listeners (same as original)
    fun setConnectionStateListener(listener: (Boolean) -> Unit) {
        onConnectionStateChanged = listener
    }

    fun setErrorListener(listener: (String) -> Unit) {
        onError = listener
    }

    fun setSpeechStartedListener(listener: () -> Unit) {
        onSpeechStarted = listener
    }

    fun setSpeechStoppedListener(listener: () -> Unit) {
        onSpeechStopped = listener
    }

    fun setResponseCreatedListener(listener: (String) -> Unit) {
        onResponseCreated = listener
    }

    fun setResponseDoneListener(listener: (String) -> Unit) {
        onResponseDone = listener
    }

    suspend fun connect(): Boolean {
        return try {
            shouldReconnect.set(true)
            reconnectAttempts = 0
            connectInternal()
        } catch (e: Exception) {
            log.e(TAG) { "Error connecting to OpenAI: ${e.message}" }
            onError?.invoke("Connection failed: ${e.message}")
            false
        }
    }

    private suspend fun connectInternal(): Boolean {
        try {
            val request = Request.Builder()
                .url("$WEBSOCKET_URL?model=$model")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("OpenAI-Beta", "realtime=v1")
                .build()

            log.d(TAG) { "Connecting to OpenAI WebSocket..." }
            webSocket = client.newWebSocket(request, createWebSocketListener())

            // Wait for connection with timeout
            var attempts = 0
            while (!isConnected.get() && attempts < 50) {
                delay(100)
                attempts++
            }

            return isConnected.get()
        } catch (e: Exception) {
            log.e(TAG) { "Connection error: ${e.message}" }
            return false
        }
    }

    private fun createWebSocketListener(): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            log.d(TAG) { "OpenAI WebSocket connected successfully" }
            isConnected.set(true)
            reconnectAttempts = 0
            onConnectionStateChanged?.invoke(true)

            // Initialize session in coroutine
            coroutineScope.launch {
                try {
                    delay(500) // Small pause for stability
                    initializeSession()
                } catch (e: Exception) {
                    log.e(TAG) { "Error initializing session: ${e.message}" }
                    onError?.invoke("Session initialization failed: ${e.message}")
                }
            }
        }

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onMessage(webSocket: WebSocket, text: String) {
            coroutineScope.launch {
                try {
                    handleMessage(text)
                } catch (e: Exception) {
                    log.e(TAG) { "Error handling message: ${e.message}" }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            log.e(TAG) { "WebSocket failure: ${t.message}, Response: ${response?.message}" }
            isConnected.set(false)
            onConnectionStateChanged?.invoke(false)
            onError?.invoke("WebSocket error: ${t.message}")

            if (shouldReconnect.get() && reconnectAttempts < maxReconnectAttempts) {
                scheduleReconnect()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            log.d(TAG) { "WebSocket closed: $code - $reason" }
            isConnected.set(false)
            onConnectionStateChanged?.invoke(false)

            if (code != 1000 && shouldReconnect.get() && reconnectAttempts < maxReconnectAttempts) {
                scheduleReconnect()
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = coroutineScope.launch {
            reconnectAttempts++
            val delay = minOf(2000L * reconnectAttempts, 30000L)
            log.d(TAG) { "Scheduling reconnect attempt $reconnectAttempts in ${delay}ms" }
            delay(delay)

            if (shouldReconnect.get()) {
                log.d(TAG) { "Attempting to reconnect..." }
                connectInternal()
            }
        }
    }

    // FIXED: Correct session initialization format
    private suspend fun initializeSession() {
        log.d(TAG) { "Initializing session with server VAD..." }

        val sessionUpdate = SessionUpdateMessage(
            type = "session.update",
            eventId = "session_init_${System.currentTimeMillis()}",
            modalities = listOf("text", "audio"),
            instructions = "You are a helpful AI assistant in a phone call. Keep responses brief, natural and conversational. Respond as if you're having a real-time voice conversation with a human.",
            voice = "alloy",
            input_audio_format = "pcm16",
            output_audio_format = "pcm16",
            input_audio_transcription = InputAudioTranscription(model = "whisper-1"),
            turn_detection = TurnDetection(
                type = "server_vad",
                threshold = 0.5,
                prefix_padding_ms = 300,
                silence_duration_ms = 200
            ),
            tools = emptyList(),
            tool_choice = "auto",
            temperature = 0.8,
            max_response_output_tokens = "inf"
        )

        sendMessage(sessionUpdate)
        log.d(TAG) { "Session initialization request sent" }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun handleMessage(text: String) {
        try {
            log.d(TAG) { "Received message: ${text.take(200)}..." }
            val response = jsonParser.decodeFromString<OpenAIRealtimeResponse>(text)

            when (response.type) {
                "session.created" -> {
                    log.d(TAG) { "Session created successfully" }
                }

                "session.updated" -> {
                    log.d(TAG) { "Session updated successfully" }
                }

                "response.audio.delta" -> {
                    response.delta?.let { delta ->
                        try {
                            val audioBytes = Base64.getDecoder().decode(delta)
                            audioResponseChannel.trySend(audioBytes)
                            log.d(TAG) { "Audio delta received: ${audioBytes.size} bytes" }
                        } catch (e: Exception) {
                            log.e(TAG) { "Error decoding audio delta: ${e.message}" }
                        }
                    }
                }

                "response.audio_transcript.delta" -> {
                    response.delta?.let { delta ->
                        transcriptionChannel.trySend(delta)
                        log.d(TAG) { "Transcript delta: $delta" }
                    }
                }

                "response.audio_transcript.done" -> {
                    response.transcript?.let { transcript ->
                        fullTranscriptChannel.trySend(transcript)
                        log.d(TAG) { "Full transcript: $transcript" }
                    }
                }

                "input_audio_buffer.speech_started" -> {
                    log.d(TAG) { "Speech detection: Started" }
                    onSpeechStarted?.invoke()
                }

                "input_audio_buffer.speech_stopped" -> {
                    log.d(TAG) { "Speech detection: Stopped" }
                    onSpeechStopped?.invoke()
                }

                "response.created" -> {
                    log.d(TAG) { "Response created: ${response.response?.id}" }
                    response.response?.id?.let { onResponseCreated?.invoke(it) }
                }

                "response.done" -> {
                    log.d(TAG) { "Response completed: ${response.response?.id}" }
                    response.response?.id?.let { onResponseDone?.invoke(it) }
                }

                "error" -> {
                    response.error?.let { error ->
                        val errorMsg = "${error.type}: ${error.message}"
                        log.e(TAG) { "OpenAI error: $errorMsg" }
                        onError?.invoke("OpenAI error: $errorMsg")
                    }
                }

                "input_audio_buffer.committed" -> {
                    log.d(TAG) { "Audio buffer committed" }
                }

                else -> {
                    log.d(TAG) { "Unhandled message type: ${response.type}" }
                }
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error parsing OpenAI message: ${e.message}" }
            log.e(TAG) { "Raw message: $text" }
        }
    }

    // FIXED: Use separate message classes for different types
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun sendAudio(audioData: ByteArray): Boolean {
        if (!isConnected.get()) {
            log.w(TAG) { "Cannot send audio: not connected" }
            return false
        }

        return try {
            val base64Audio = Base64.getEncoder().encodeToString(audioData)
            val request = AudioAppendMessage(
                type = "input_audio_buffer.append",
                eventId = "audio_${System.currentTimeMillis()}",
                audio = base64Audio
            )
            sendMessage(request)
            log.d(TAG) { "Audio sent: ${audioData.size} bytes" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error sending audio: ${e.message}" }
            false
        }
    }

    suspend fun commitAudio(): Boolean {
        if (!isConnected.get()) {
            log.w(TAG) { "Cannot commit audio: not connected" }
            return false
        }

        return try {
            val request = AudioCommitMessage(
                type = "input_audio_buffer.commit",
                eventId = "commit_${System.currentTimeMillis()}"
            )
            sendMessage(request)
            log.d(TAG) { "Audio buffer committed" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error committing audio: ${e.message}" }
            false
        }
    }

    suspend fun createResponse(): Boolean {
        if (!isConnected.get()) {
            log.w(TAG) { "Cannot create response: not connected" }
            return false
        }

        return try {
            val request = ResponseCreateMessage(
                type = "response.create",
                eventId = "response_${System.currentTimeMillis()}"
            )
            sendMessage(request)
            log.d(TAG) { "Response creation requested" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error creating response: ${e.message}" }
            false
        }
    }

    // FIXED: Update VAD settings with correct format
    suspend fun updateVADSettings(
        threshold: Double = 0.5,
        prefixPaddingMs: Int = 300,
        silenceDurationMs: Int = 200
    ): Boolean {
        if (!isConnected.get()) {
            log.w(TAG) { "Cannot update VAD settings: not connected" }
            return false
        }

        return try {
            val request = SessionUpdateMessage(
                type = "session.update",
                eventId = "vad_update_${System.currentTimeMillis()}",
                turn_detection = TurnDetection(
                    type = "server_vad",
                    threshold = threshold,
                    prefix_padding_ms = prefixPaddingMs,
                    silence_duration_ms = silenceDurationMs
                )
            )

            sendMessage(request)
            log.d(TAG) { "VAD settings updated: threshold=$threshold" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error updating VAD settings: ${e.message}" }
            false
        }
    }

    private suspend fun sendMessage(message: Any): Boolean {
        return try {
            val json = jsonParser.encodeToString(message)
            log.d(TAG) { "Sending message: ${json.take(500)}..." }
            val success = webSocket?.send(json) ?: false
            if (!success) {
                log.e(TAG) { "Failed to send message: WebSocket send returned false" }
            }
            success
        } catch (e: Exception) {
            log.e(TAG) { "Error encoding/sending message: ${e.message}" }
            false
        }
    }

    // Response channels
    fun getAudioResponseChannel(): ReceiveChannel<ByteArray> = audioResponseChannel
    fun getTranscriptionChannel(): ReceiveChannel<String> = transcriptionChannel
    fun getFullTranscriptChannel(): ReceiveChannel<String> = fullTranscriptChannel

    // Connection state
    fun isConnected(): Boolean = isConnected.get()

    suspend fun disconnect() {
        log.d(TAG) { "Disconnecting..." }
        shouldReconnect.set(false)
        reconnectJob?.cancel()
        isConnected.set(false)

        try {
            webSocket?.close(1000, "Client disconnect")
        } catch (e: Exception) {
            log.e(TAG) { "Error closing WebSocket: ${e.message}" }
        }

        coroutineScope.cancel()
        audioResponseChannel.close()
        transcriptionChannel.close()
        fullTranscriptChannel.close()
    }
}