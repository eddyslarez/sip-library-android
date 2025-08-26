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

class MCNAssistantClient(
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

    // Callbacks
    private var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var onAudioReceived: ((ByteArray) -> Unit)? = null
    private var onInputTranscriptionReceived: ((String) -> Unit)? = null
    private var onApiCallRequested: ((ApiCallRequest) -> Unit)? = null // Nueva callback para llamadas API

    // Manejo de audio truncado
    private var lastAudioReceiveTime = 0L
    private val audioTimeoutMs = 2000L

    companion object {
        private const val TAG = "MCNAssistantClient"
        private const val WEBSOCKET_URL = "wss://api.openai.com/v1/realtime"
    }

    // Buffer para audio reproducible
    private val playbackBuffer = ByteArrayOutputStream()
    private val playbackMutex = Mutex()

    // Data classes para manejo de información del cliente
    data class UserInfo(
        val id: Long,
        val fullName: String,
        val email: String,
        val phone: String,
        val selectedContractId: Long,
        val selectedAccount: AccountInfo,
        val isBlocked: Boolean,
        val isVerified: Boolean
    )

    data class AccountInfo(
        val id: Long,
        val contractId: Long,
        val credit: Double,
        val balance: Double,
        val currency: String
    )

    data class SipAccountInfo(
        val name: String,
        val phone: String,
        val accountId: Long,
        val usageType: String,
        val product: String,
        val server: String,
        val outboundDid: String,
        val isEnabled: Boolean,
        val isCurrent: Boolean
    )
    // === DATA CLASSES AUXILIARES ===
data class BalanceInfo(
    val currentBalance: Double,
    val availableCredit: Double,
    val currency: String,
    val lastPaymentDate: String?,
    val nextDueDate: String?
)

data class Operator(
    val id: String,
    val name: String,
    val isAvailable: Boolean,
    val specialization: String
)

    data class ApiCallRequest(
        val action: String,
        val parameters: Map<String, Any>
    )

    // Información actual del cliente (se actualiza cuando se obtienen datos)
    private var currentUserInfo: UserInfo? = null
    private var currentSipAccounts: Map<String, SipAccountInfo> = emptyMap()

    // === INSTRUCCIONES DEL ASISTENTE MCN ===
    private val mcnAssistantInstructions = """
        TÚ ERES EL ASISTENTE VIRTUAL DE MCN (MCN.RU) - EMPRESA DE TELECOMUNICACIONES solo hablas en ruso asi que todas tus respuestas seran en ruso.
        
        IDENTIDAD CORPORATIVA:
        - Empresa: MCN (www.mcn.ru)
        - Sector: Telecomunicaciones en Rusia
        - Servicios: Telefonía VoIP, comunicaciones empresariales, números virtuales
        
        TU MISIÓN:
        Ayudar a los clientes de MCN con consultas sobre:
        ✓ Estado de cuenta y saldo
        ✓ Información de números telefónicos
        ✓ Configuración de servicios SIP
        ✓ Resolución de problemas técnicos
        ✓ Transferencia a operadores humanos
        ✓ Información general sobre servicios
        
        PERSONALIDAD:
        - Profesional pero amigable
        - Paciente y comprensivo
        - Proactivo en ofrecer soluciones
        - Conocedor de servicios de telecomunicaciones
        - Responde en el idioma del cliente (español/ruso principalmente)
        
        CAPACIDADES ESPECIALES:
        1. CONSULTA DE DATOS: Puedes solicitar información actualizada del cliente
        2. ANÁLISIS DE CUENTA: Interpretas datos financieros y técnicos
        3. SOPORTE TÉCNICO: Guías para configuración SIP y resolución de problemas
        4. ESCALACIÓN: Puedes conectar con operadores humanos cuando sea necesario
        
        FLUJO DE CONVERSACIÓN:
        1. Saludo cordial identificándote como asistente de MCN
        2. Identificar al cliente y sus necesidades
        3. Obtener datos necesarios mediante llamadas API
        4. Proporcionar información clara y útil
        5. Ofrecer asistencia adicional o escalación si es necesario
        
        INFORMACIÓN QUE MANEJAS:
        - Saldo actual y crédito disponible
        - Estado de números telefónicos (activos/inactivos)
        - Configuración de cuentas SIP
        - Servidores y parámetros técnicos
        - Estado de verificación y bloqueos
        - Historial de contratos
        
        CUANDO NECESITES DATOS:
        - Solicita información específica del cliente
        - Espera respuesta de la API antes de continuar
        - Interpreta y explica los datos de manera comprensible
        - Ofrece acciones concretas basadas en la información
        
        CASOS ESPECIALES:
        - Si cuenta bloqueada: Explica el proceso de desbloqueo
        - Si saldo negativo: Informa sobre métodos de recarga
        - Si números inactivos: Guía para reactivación
        - Si problemas técnicos: Proporciona pasos de configuración
        
        ESCALACIÓN A OPERADOR:
        - Cuando el problema requiere intervención manual
        - Cuando el cliente lo solicite específicamente
        - Cuando no puedas resolver la consulta con datos disponibles
        
        TONO DE RESPUESTA:
        - Claro y conciso
        - Técnicamente preciso pero comprensible
        - Empático con problemas del cliente
        - Orientado a la solución
        
        NUNCA HAGAS:
        - Inventar información que no tienes
        - Prometer acciones que no puedes realizar
        - Dar información confidencial sin verificación
        - Actuar fuera del ámbito de telecomunicaciones de MCN
        
        RECUERDA: Eres un asistente especializado de MCN. Tu objetivo es brindar la mejor experiencia de atención al cliente en servicios de telecomunicaciones.
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

    fun setInputTranscriptionListener(listener: (String) -> Unit) {
        onInputTranscriptionReceived = listener
    }

    fun setApiCallRequestListener(listener: (ApiCallRequest) -> Unit) {
        onApiCallRequested = listener
    }

    // === MÉTODOS PARA ACTUALIZAR INFORMACIÓN DEL CLIENTE ===
    fun updateUserInfo(userInfo: UserInfo) {
        currentUserInfo = userInfo
        log.d(TAG) { "Información de usuario actualizada: ${userInfo.fullName}" }
    }

    fun updateSipAccounts(sipAccounts: Map<String, SipAccountInfo>) {
        currentSipAccounts = sipAccounts
        log.d(TAG) { "Cuentas SIP actualizadas: ${sipAccounts.size} números" }
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

            log.d(TAG) { "Conectando asistente MCN..." }
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
                log.d(TAG) { "Asistente MCN conectado exitosamente" }
            }

            isConnected.get()
        } catch (e: Exception) {
            log.e(TAG) { "Error de conexión: ${e.message}" }
            false
        }
    }

    private fun createWebSocketListener(): WebSocketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            log.d(TAG) { "WebSocket MCN conectado" }
            isConnected.set(true)
            onConnectionStateChanged?.invoke(true)

            coroutineScope.launch {
                try {
                    initializeSession()
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

    // === INICIALIZACIÓN DE SESIÓN ===
    private suspend fun initializeSession() {
        log.d(TAG) { "Inicializando sesión MCN Assistant..." }

        val sessionConfig = JSONObject().apply {
            put("type", "session.update")
            put("event_id", "session_init_${System.currentTimeMillis()}")

            put("session", JSONObject().apply {
                put("modalities", JSONArray().apply {
                    put("text")
                    put("audio")
                })
                put("instructions", mcnAssistantInstructions)
                put("voice", "coral") // Voz profesional para atención al cliente
                put("input_audio_format", "pcm16")
                put("output_audio_format", "pcm16")
                put("input_audio_transcription", JSONObject().apply {
                    put("model", "whisper-1")
                })
                put("turn_detection", JSONObject().apply {
                    put("type", "server_vad")
                    put("threshold", 0.3) // Ajustado para mejor detección en entorno de call center
                    put("prefix_padding_ms", 500)
                    put("silence_duration_ms", 800)
                })
                put("temperature", 0.6) // Más conservador para respuestas consistentes
                put("max_response_output_tokens", 4096)
            })
        }

        sendMessage(sessionConfig)
        log.d(TAG) { "Configuración MCN Assistant enviada" }
    }

    // === MANEJO DE MENSAJES ===
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            val type = json.getString("type")

            when (type) {
                "session.created" -> {
                    log.d(TAG) { "Sesión MCN creada exitosamente" }
                }

                "session.updated" -> {
                    isSessionInitialized.set(true)
                    log.d(TAG) { "MCN Assistant listo para atención" }

                    // Enviar mensaje inicial con información del cliente si está disponible
                    currentUserInfo?.let { user ->
                        sendUserContextUpdate(user, currentSipAccounts)
                    }
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
                        log.e(TAG) { "Error decodificando audio: ${e.message}" }
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
                    log.d(TAG) { "Respuesta MCN completada" }
                }

                "response.audio_transcript.delta" -> {
                    val delta = json.getString("delta")
                    transcriptionChannel.trySend(delta)
                    log.d(TAG) { "Respuesta MCN: $delta" }

                    // Analizar si el asistente está solicitando información
                    analyzeResponseForApiCalls(delta)
                }

                "input_audio_buffer.speech_started" -> {
                    log.d(TAG) { "Cliente iniciando consulta" }
                    playbackMutex.withLock {
                        playbackBuffer.reset()
                    }
                }

                "input_audio_buffer.speech_stopped" -> {
                    log.d(TAG) { "Cliente terminó consulta" }
                }

                "conversation.item.input_audio_transcription.completed" -> {
                    val transcript = json.getString("transcript")
                    inputTranscriptionChannel.trySend(transcript)
                    onInputTranscriptionReceived?.invoke(transcript)
                    log.d(TAG) { "Consulta cliente: $transcript" }
                }

                "response.created" -> {
                    log.d(TAG) { "MCN Assistant procesando consulta..." }
                    lastAudioReceiveTime = System.currentTimeMillis()
                }

                "response.done" -> {
                    log.d(TAG) { "MCN Assistant completó respuesta" }
                }

                "error" -> {
                    val error = json.getJSONObject("error")
                    val errorMsg = "${error.getString("type")}: ${error.getString("message")}"
                    log.e(TAG) { "Error MCN: $errorMsg" }
                    onError?.invoke("Error MCN: $errorMsg")
                }

                else -> {
                    log.d(TAG) { "Mensaje no manejado: $type" }
                }
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error parseando mensaje MCN: ${e.message}" }
        }
    }

    // === ANÁLISIS DE RESPUESTAS PARA LLAMADAS API ===
    private fun analyzeResponseForApiCalls(response: String) {
        // Analizar si el asistente necesita información específica
        when {
            response.contains("necesito consultar", ignoreCase = true) ||
                    response.contains("checking your", ignoreCase = true) ||
                    response.contains("let me check", ignoreCase = true) -> {

                when {
                    response.contains("saldo", ignoreCase = true) ||
                            response.contains("balance", ignoreCase = true) -> {
                        onApiCallRequested?.invoke(ApiCallRequest("GET_BALANCE", emptyMap()))
                    }

                    response.contains("números", ignoreCase = true) ||
                            response.contains("numbers", ignoreCase = true) -> {
                        onApiCallRequested?.invoke(ApiCallRequest("GET_SIP_ACCOUNTS", emptyMap()))
                    }

                    response.contains("cuenta", ignoreCase = true) ||
                            response.contains("account", ignoreCase = true) -> {
                        onApiCallRequested?.invoke(ApiCallRequest("GET_USER_INFO", emptyMap()))
                    }
                }
            }
        }
    }

    // === ENVÍO DE CONTEXTO DEL CLIENTE ===
    private suspend fun sendUserContextUpdate(user: UserInfo, sipAccounts: Map<String, SipAccountInfo>) {
        val contextMessage = buildString {
            appendLine("INFORMACIÓN ACTUAL DEL CLIENTE:")
            appendLine("Nombre: ${user.fullName}")
            appendLine("Email: ${user.email}")
            appendLine("Teléfono: ${user.phone}")
            appendLine("ID Cliente: ${user.id}")
            appendLine("Contrato: ${user.selectedContractId}")
            appendLine("Estado: ${if (user.isBlocked) "BLOQUEADO" else "ACTIVO"}")
            appendLine("Verificado: ${if (user.isVerified) "SÍ" else "NO"}")
            appendLine()
            appendLine("INFORMACIÓN FINANCIERA:")
            appendLine("Saldo: ${user.selectedAccount.balance} ${user.selectedAccount.currency}")
            appendLine("Crédito disponible: ${user.selectedAccount.credit} ${user.selectedAccount.currency}")
            appendLine()
            appendLine("NÚMEROS TELEFÓNICOS:")
            sipAccounts.forEach { (phone, account) ->
                appendLine("• $phone - ${if (account.isEnabled) "ACTIVO" else "INACTIVO"} - Servidor: ${account.server}")
            }
        }

        val contextUpdate = JSONObject().apply {
            put("type", "conversation.item.create")
            put("event_id", "context_${System.currentTimeMillis()}")
            put("item", JSONObject().apply {
                put("type", "message")
                put("role", "user")
                put("content", JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "input_text")
                        put("text", contextMessage)
                    })
                })
            })
        }

        sendMessage(contextUpdate)
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
                            log.d(TAG) { "Enviando audio restante por timeout" }
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
                    log.e(TAG) { "Error en loop de audio: ${e.message}" }
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
            log.e(TAG) { "Error enviando audio a OpenAI: ${e.message}" }
        }
    }

    suspend fun generateResponse() {
        try {
            val message = JSONObject().apply {
                put("type", "response.create")
                put("event_id", "force_response_${System.currentTimeMillis()}")
                put("response", JSONObject().apply {
                    put("modalities", JSONArray().apply {
                        put("text")
                        put("audio")
                    })
                })
            }
            sendMessage(message)
            log.d(TAG) { "Forzando respuesta MCN" }
        } catch (e: Exception) {
            log.e(TAG) { "Error forzando respuesta: ${e.message}" }
        }
    }

    // === MÉTODOS PÚBLICOS ===
    suspend fun addAudioData(audioData: ByteArray) {
        if (!isConnected.get()) return
        audioBufferMutex.withLock {
            audioBuffer.add(audioData)
        }
    }

    fun getAudioResponseChannel(): ReceiveChannel<ByteArray> = audioResponseChannel
    fun getTranscriptionChannel(): ReceiveChannel<String> = transcriptionChannel
    fun getInputTranscriptionChannel(): ReceiveChannel<String> = inputTranscriptionChannel

    fun isConnected(): Boolean = isConnected.get()
    fun isSessionReady(): Boolean = isSessionInitialized.get()

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
        log.d(TAG) { "Desconectando MCN Assistant..." }
        isConnected.set(false)

        try {
            webSocket?.close(1000, "MCN Assistant disconnect")
        } catch (e: Exception) {
            log.e(TAG) { "Error cerrando WebSocket: ${e.message}" }
        }

        coroutineScope.cancel()
        audioResponseChannel.close()
        transcriptionChannel.close()

        audioBufferMutex.withLock {
            audioBuffer.clear()
        }

        playbackMutex.withLock {
            playbackBuffer.reset()
        }
    }
}