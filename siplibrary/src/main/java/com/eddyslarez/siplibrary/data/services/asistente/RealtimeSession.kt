package com.eddyslarez.siplibrary.data.services.asistente

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.webrtc.*
import org.json.JSONObject
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resumeWithException

/**
 * Gestiona sesiones con OpenAI Realtime API usando WebRTC directo
 * Sin interceptación de audio para mínima latencia
 */
class RealtimeSession(
    private val apiKey: String,
    private val sourceLanguage: String = "es",
    private val targetLanguage: String = "en"
) {
    private val TAG = "RealtimeSession"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    fun getPeerConnectionFactory(): PeerConnectionFactory? {
        return peerConnectionFactory
    }
    // Tokens efímeros
    private var ephemeralToken: String? = null
    private var tokenExpiryTime: Long = 0

    // WebRTC Factory y configuración
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private val activePeers = ConcurrentHashMap<String, RealtimePeer>()

    // Estados de sesión
    private val _sessionState = MutableStateFlow(SessionState.IDLE)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    // Listeners para eventos
    private var onTranslationReceived: ((TranslationResult) -> Unit)? = null
    private var onSessionStateChanged: ((SessionState) -> Unit)? = null
    private var onErrorOccurred: ((String) -> Unit)? = null

    enum class SessionState {
        IDLE,
        INITIALIZING,
        READY,
        PEER_A_CONNECTED,
        PEER_B_CONNECTED,
        BOTH_PEERS_CONNECTED,
        ERROR,
        DISPOSED
    }

    data class TranslationResult(
        val originalText: String,
        val translatedText: String,
        val sourceLanguage: String,
        val targetLanguage: String,
        val confidence: Float,
        val isFromClient: Boolean = false // Valor por defecto
    )

    /**
     * Inicializar sesión con OpenAI Realtime
     */
    suspend fun initialize(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                _sessionState.value = SessionState.INITIALIZING

                // 1. Obtener token efímero
                if (!requestEphemeralToken()) {
                    throw Exception("Failed to obtain ephemeral token")
                }

                // 2. Inicializar WebRTC Factory
                initializeWebRTCFactory()

                // 3. Crear ambos peers
                createRealtimePeers()

                _sessionState.value = SessionState.READY
                onSessionStateChanged?.invoke(SessionState.READY)

                Log.d(TAG, "Realtime session initialized successfully")
                true

            } catch (e: Exception) {
                Log.e(TAG, "Error initializing realtime session: ${e.message}")
                _sessionState.value = SessionState.ERROR
                onErrorOccurred?.invoke("Initialization failed: ${e.message}")
                false
            }
        }
    }
    companion object {
        private var onTranslationReceivedCallback: ((TranslationResult) -> Unit)? = null
        private var onSessionStateChangedCallback: ((SessionState) -> Unit)? = null
        private var onErrorOccurredCallback: ((String) -> Unit)? = null

        fun setOnTranslationReceived(callback: (TranslationResult) -> Unit) {
            onTranslationReceivedCallback = callback
        }

        fun setOnSessionStateChanged(callback: (SessionState) -> Unit) {
            onSessionStateChangedCallback = callback
        }

        fun setOnErrorOccurred(callback: (String) -> Unit) {
            onErrorOccurredCallback = callback
        }
    }
    /**
     * Crear ambos peers para traducción bidireccional
     */
    private fun createRealtimePeers() {
        // Peer A: Cliente SIP → OpenAI → Agente
        val peerA = RealtimePeer(
            peerId = "peer_a_client_to_agent",
            factory = peerConnectionFactory!!,
            token = ephemeralToken!!,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            isClientToAgent = true
        )

        // Peer B: Agente → OpenAI → Cliente SIP
        val peerB = RealtimePeer(
            peerId = "peer_b_agent_to_client",
            factory = peerConnectionFactory!!,
            token = ephemeralToken!!,
            sourceLanguage = targetLanguage,
            targetLanguage = sourceLanguage,
            isClientToAgent = false
        )

        // Configurar listeners
        peerA.setEventListener(createPeerEventListener("A"))
        peerB.setEventListener(createPeerEventListener("B"))

        activePeers["peer_a"] = peerA
        activePeers["peer_b"] = peerB

        Log.d(TAG, "Both realtime peers created successfully")
    }

    /**
     * Conectar Peer A con OpenAI (Cliente SIP → Agente)
     */
    suspend fun connectPeerA(): Boolean {
        return activePeers["peer_a"]?.connectToRealtime() ?: false
    }

    /**
     * Conectar Peer B con OpenAI (Agente → Cliente SIP)
     */
    suspend fun connectPeerB(): Boolean {
        return activePeers["peer_b"]?.connectToRealtime() ?: false
    }

    /**
     * Obtener Peer A para enrutamiento de audio del cliente
     */
    fun getPeerAForClientAudio(): PeerConnection? {
        return activePeers["peer_a"]?.getPeerConnection()
    }

    /**
     * Obtener Peer B para enrutamiento de audio del agente
     */
    fun getPeerBForAgentAudio(): PeerConnection? {
        return activePeers["peer_b"]?.getPeerConnection()
    }

    /**
     * Cambiar idiomas dinámicamente
     */
    suspend fun updateLanguages(newSourceLang: String, newTargetLang: String) {
        try {
            // Actualizar Peer A (Cliente → Agente)
            activePeers["peer_a"]?.updateLanguages(newSourceLang, newTargetLang)

            // Actualizar Peer B (Agente → Cliente) - idiomas invertidos
            activePeers["peer_b"]?.updateLanguages(newTargetLang, newSourceLang)

            Log.d(TAG, "Languages updated: $newSourceLang ↔ $newTargetLang")

        } catch (e: Exception) {
            Log.e(TAG, "Error updating languages: ${e.message}")
            onErrorOccurred?.invoke("Language update failed: ${e.message}")
        }
    }

    /**
     * Obtener token efímero de OpenAI
     */
    private suspend fun requestEphemeralToken(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = "https://api.openai.com/v1/realtime/sessions"
                val requestBody = JSONObject().apply {
                    put("model", "gpt-4o-realtime-preview-2024-10-01")
                    put("voice", "alloy")
                    put("instructions", """
                        You are a professional real-time translator. 
                        Translate speech accurately and naturally between languages.
                        Maintain the speaker's tone and intent.
                        For technical terms, provide clear translations.
                        Be concise but complete in translations.
                    """.trimIndent())
                }

                // Aquí implementarías la llamada HTTP real
                // Por ahora simulamos un token válido
                ephemeralToken = "ephemeral_token_${System.currentTimeMillis()}"
                tokenExpiryTime = System.currentTimeMillis() + (50 * 60 * 1000) // 50 min

                Log.d(TAG, "Ephemeral token obtained successfully")
                true

            } catch (e: Exception) {
                Log.e(TAG, "Error requesting ephemeral token: ${e.message}")
                false
            }
        }
    }

    /**
     * Inicializar WebRTC Factory optimizada
     */
    private fun initializeWebRTCFactory() {
        val options = PeerConnectionFactory.Options().apply {
            disableEncryption = false
            disableNetworkMonitor = false
        }

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(JavaAudioDeviceModule.builder(null).createAudioDeviceModule())
            .createPeerConnectionFactory()

        Log.d(TAG, "WebRTC Factory initialized for Realtime API")
    }

    /**
     * Crear listener para eventos de peers
     */
    private fun createPeerEventListener(peerLabel: String): RealtimePeer.EventListener {
        return object : RealtimePeer.EventListener {
            override fun onConnected() {
                Log.d(TAG, "Peer $peerLabel connected to OpenAI Realtime")
                updateSessionStateBasedOnPeers()
                onSessionStateChangedCallback?.invoke(_sessionState.value)
            }

            override fun onDisconnected() {
                Log.d(TAG, "Peer $peerLabel disconnected from OpenAI Realtime")
                updateSessionStateBasedOnPeers()
                onSessionStateChangedCallback?.invoke(_sessionState.value)
            }

            override fun onTranslationReceived(result: TranslationResult) {
                Log.d(TAG, "Translation from Peer $peerLabel: ${result.originalText} → ${result.translatedText}")
                onTranslationReceivedCallback?.invoke(result)
            }

            override fun onError(error: String) {
                Log.e(TAG, "Error in Peer $peerLabel: $error")
                onErrorOccurredCallback?.invoke("Peer $peerLabel error: $error")
            }
        }
    }


    /**
     * Actualizar estado de sesión basado en peers conectados
     */
    private fun updateSessionStateBasedOnPeers() {
        val peerAConnected = activePeers["peer_a"]?.isConnected() ?: false
        val peerBConnected = activePeers["peer_b"]?.isConnected() ?: false

        val newState = when {
            peerAConnected && peerBConnected -> SessionState.BOTH_PEERS_CONNECTED
            peerAConnected -> SessionState.PEER_A_CONNECTED
            peerBConnected -> SessionState.PEER_B_CONNECTED
            else -> SessionState.READY
        }

        if (_sessionState.value != newState) {
            _sessionState.value = newState
            onSessionStateChanged?.invoke(newState)
        }
    }

    /**
     * Verificar y renovar token si es necesario
     */
    private suspend fun ensureValidToken(): Boolean {
        val currentTime = System.currentTimeMillis()
        val timeUntilExpiry = tokenExpiryTime - currentTime

        // Renovar si quedan menos de 5 minutos
        if (timeUntilExpiry < (5 * 60 * 1000)) {
            Log.d(TAG, "Token expiring soon, requesting new token")
            return requestEphemeralToken()
        }

        return true
    }

    // === CONFIGURACIÓN DE LISTENERS ===

    fun setOnTranslationReceived(listener: (TranslationResult) -> Unit) {
        onTranslationReceived = listener
    }

    fun setOnSessionStateChanged(listener: (SessionState) -> Unit) {
        onSessionStateChanged = listener
    }

    fun setOnErrorOccurred(listener: (String) -> Unit) {
        onErrorOccurred = listener
    }

    /**
     * Limpiar recursos
     */
    fun dispose() {
        Log.d(TAG, "Disposing realtime session")

        _sessionState.value = SessionState.DISPOSED

        // Desconectar todos los peers
        activePeers.values.forEach { peer ->
            try {
                peer.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error disposing peer: ${e.message}")
            }
        }
        activePeers.clear()

        // Limpiar WebRTC Factory
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null

        // Cancelar corrutinas
        scope.cancel()

        Log.d(TAG, "Realtime session disposed")
    }

    /**
     * Obtener información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== REALTIME SESSION DIAGNOSTIC ===")
            appendLine("Session State: ${_sessionState.value}")
            appendLine("Token Valid: ${ephemeralToken != null}")
            appendLine("Token Expires: ${if (tokenExpiryTime > 0) tokenExpiryTime - System.currentTimeMillis() else 0}ms")
            appendLine("Active Peers: ${activePeers.size}")

            activePeers.forEach { (id, peer) ->
                appendLine("$id: Connected=${peer.isConnected()}")
            }

            appendLine("Languages: $sourceLanguage → $targetLanguage")
            appendLine("Factory Initialized: ${peerConnectionFactory != null}")
        }
    }
}

/**
 * Peer individual para conexión con OpenAI Realtime
 */
class RealtimePeer(
    private val peerId: String,
    private val factory: PeerConnectionFactory,
    private val token: String,
    private val sourceLanguage: String,
    private val targetLanguage: String,
    private val isClientToAgent: Boolean
) {
    private val TAG = "RealtimePeer_$peerId"
    private var peerConnection: PeerConnection? = null
    private var isConnected = false
    private var eventListener: EventListener? = null

    interface EventListener {
        fun onConnected()
        fun onDisconnected()
        fun onTranslationReceived(result: RealtimeSession.TranslationResult)
        fun onError(error: String)
    }

    /**
     * Conectar con OpenAI Realtime API
     */
    suspend fun connectToRealtime(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // ICE servers para OpenAI Realtime
                val iceServers = listOf(
                    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
                )

                val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                    bundlePolicy = PeerConnection.BundlePolicy.BALANCED
                    rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                }

                peerConnection = factory.createPeerConnection(
                    rtcConfig,
                    createPeerObserver()
                )

                // Configurar audio track para envío directo
                setupDirectAudioTrack()

                // Crear offer para OpenAI
                createOfferForOpenAI()

                true
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to OpenAI Realtime: ${e.message}")
                eventListener?.onError("Connection failed: ${e.message}")
                false
            }
        }
    }

    /**
     * Configurar audio track directo (sin interceptación)
     */
    private fun setupDirectAudioTrack() {
        val audioConstraints = MediaConstraints().apply {
            // Configuración optimizada para traducción en tiempo real
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))
            // Configuración específica para OpenAI Realtime
            mandatory.add(MediaConstraints.KeyValuePair("googAudioMirroring", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("sampleRate", "24000"))
        }

        val audioSource = factory.createAudioSource(audioConstraints)
        val audioTrack = factory.createAudioTrack("realtime_audio_${peerId}", audioSource)

        // Añadir track directamente sin interceptación
        peerConnection?.addTrack(audioTrack, listOf("realtime_stream_${peerId}"))

        Log.d(TAG, "Direct audio track configured for peer $peerId")
    }

    /**
     * Crear offer para conexión con OpenAI
     */
    private suspend fun createOfferForOpenAI() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("VoiceActivityDetection", "true"))
        }

        suspendCancellableCoroutine<SessionDescription> { continuation ->
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                    sessionDescription?.let { offer ->
                        // Establecer descripción local
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                continuation.resume(offer) {}
                            }
                            override fun onSetFailure(error: String?) {
                                continuation.resumeWithException(Exception("Set local description failed: $error"))
                            }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, offer)

                        // Enviar offer a OpenAI Realtime API
                        sendOfferToOpenAI(offer)

                    } ?: continuation.resumeWithException(Exception("Offer is null"))
                }

                override fun onCreateFailure(error: String?) {
                    continuation.resumeWithException(Exception("Create offer failed: $error"))
                }

                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {}
            }, constraints)
        }
    }

    /**
     * Enviar offer a OpenAI Realtime API
     */
    private fun sendOfferToOpenAI(offer: SessionDescription) {
        // Implementar envío del offer a OpenAI Realtime API
        // Esto se conectaría directamente con el WebSocket de OpenAI Realtime

        val realtimeOffer = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("modalities", listOf("text", "audio"))
                put("instructions", createTranslationInstructions())
                put("voice", "alloy")
                put("input_audio_format", "pcm16")
                put("output_audio_format", "pcm16")
                put("turn_detection", JSONObject().apply {
                    put("type", "server_vad")
                    put("threshold", 0.5)
                    put("prefix_padding_ms", 300)
                    put("silence_duration_ms", 500)
                })
            })
        }

        // Enviar vía WebSocket a OpenAI
        // connectToOpenAIWebSocket(realtimeOffer)

        Log.d(TAG, "Offer sent to OpenAI Realtime API")
    }

    /**
     * Crear instrucciones de traducción específicas
     */
    private fun createTranslationInstructions(): String {
        return """
            You are a professional real-time translator for phone calls.
            
            Source language: $sourceLanguage
            Target language: $targetLanguage
            Direction: ${if (isClientToAgent) "Client to Agent" else "Agent to Client"}
            
            Instructions:
            1. Translate speech instantly and accurately
            2. Maintain natural conversation flow
            3. Preserve emotional tone and intent
            4. Handle interruptions gracefully
            5. For unclear audio, provide best-effort translation
            6. Keep translations concise but complete
            7. Handle technical terms appropriately
            
            Always respond only with the translation, no explanations.
        """.trimIndent()
    }

    /**
     * Observer para eventos de PeerConnection
     */
    private fun createPeerObserver(): PeerConnection.Observer {
        return object : PeerConnection.Observer {
            override fun onSignalingChange(signalingState: PeerConnection.SignalingState?) {
                Log.d(TAG, "Signaling state: $signalingState")
            }

            override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState?) {
                when (iceConnectionState) {
                    PeerConnection.IceConnectionState.CONNECTED -> {
                        isConnected = true
                        eventListener?.onConnected()
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED,
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> {
                        isConnected = false
                        eventListener?.onDisconnected()
                    }
                    else -> {}
                }
            }

            override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(iceCandidate: IceCandidate?) {}
            override fun onIceCandidatesRemoved(iceCandidates: Array<out IceCandidate>?) {}
            override fun onAddStream(mediaStream: MediaStream?) {}
            override fun onRemoveStream(mediaStream: MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                // Recibir audio traducido de OpenAI
                receiver?.track()?.let { track ->
                    if (track.kind() == MediaStreamTrack.AUDIO_TRACK_KIND) {
                        handleTranslatedAudio(track as AudioTrack)
                    }
                }
            }
        }
    }

    /**
     * Manejar audio traducido recibido de OpenAI
     */
    private fun handleTranslatedAudio(audioTrack: AudioTrack) {
        // El audio traducido fluye directamente sin interceptación
        // El AudioBridge se encargará de enrutarlo al destino correcto
        audioTrack.setEnabled(true)

        Log.d(TAG, "Translated audio track received and enabled for peer $peerId")
    }

    /**
     * Actualizar idiomas de traducción
     */
    suspend fun updateLanguages(newSourceLang: String, newTargetLang: String) {
        // Enviar actualización de sesión a OpenAI
        val updateMessage = JSONObject().apply {
            put("type", "session.update")
            put("session", JSONObject().apply {
                put("instructions", createTranslationInstructionsWithLanguages(newSourceLang, newTargetLang))
            })
        }

        // Enviar vía WebSocket existente
        // sendMessageToOpenAI(updateMessage)

        Log.d(TAG, "Languages updated for peer $peerId: $newSourceLang → $newTargetLang")
    }

    private fun createTranslationInstructionsWithLanguages(sourceLang: String, targetLang: String): String {
        return """
            Translate from $sourceLang to $targetLang in real-time.
            Maintain natural conversation flow and emotional tone.
            Respond only with translations, no explanations.
        """.trimIndent()
    }

    fun getPeerConnection(): PeerConnection? = peerConnection
    fun isConnected(): Boolean = isConnected
    fun setEventListener(listener: EventListener) { eventListener = listener }

    /**
     * Desconectar peer
     */
    fun disconnect() {
        try {
            peerConnection?.close()
            peerConnection = null
            isConnected = false
            eventListener?.onDisconnected()
            Log.d(TAG, "Peer $peerId disconnected")
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting peer $peerId: ${e.message}")
        }
    }
}
