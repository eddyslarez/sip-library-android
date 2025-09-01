package com.eddyslarez.siplibrary.data.services.asistente


import android.app.Application
import android.util.Log
import com.eddyslarez.siplibrary.data.services.audio.SdpType
import com.eddyslarez.siplibrary.data.services.audio.WebRtcConnectionState
import kotlinx.coroutines.*
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resumeWithException

/**
 * Cliente WebRTC optimizado para conexión directa con OpenAI Realtime
 * Sin interceptación de audio para mínima latencia
 */
class WebRTCClient(
    private val application: Application
) {
    private val TAG = "WebRTCClient"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // WebRTC components
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    // Audio components optimizados
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteAudioTrack: AudioTrack? = null

    // Configuración optimizada para OpenAI Realtime
    private var isDirectRoutingEnabled = false

    // Event listener
    private var eventListener: EventListener? = null

    interface EventListener {
        fun onRemoteAudioTrack(audioTrack: AudioTrack)
        fun onLocalAudioTrack(audioTrack: AudioTrack)
        fun onConnectionStateChanged(state: WebRtcConnectionState)
        fun onError(error: String)
    }

    /**
     * Inicializar WebRTC optimizado para Realtime API
     */
    suspend fun initialize(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Initializing WebRTC for OpenAI Realtime")

                // Inicializar PeerConnectionFactory optimizada
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(application)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )

                val options = PeerConnectionFactory.Options().apply {
                    // Optimizaciones para latencia mínima
                    disableEncryption = false
                    disableNetworkMonitor = false
                }

                peerConnectionFactory = PeerConnectionFactory.builder()
                    .setOptions(options)
                    .setAudioDeviceModule(
                        JavaAudioDeviceModule.builder(application)
                            .setUseHardwareAcousticEchoCanceler(false) // Desactivar para OpenAI
                            .setUseHardwareNoiseSuppressor(false)      // OpenAI maneja esto
                            .createAudioDeviceModule()
                    )
                    .createPeerConnectionFactory()

                Log.d(TAG, "WebRTC initialized for minimal latency")
                true

            } catch (e: Exception) {
                Log.e(TAG, "Error initializing WebRTC: ${e.message}")
                false
            }
        }
    }

    /**
     * Preparar para llamada entrante
     */
    suspend fun prepareForIncomingCall(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                createPeerConnection()
                setupOptimizedAudioConstraints()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error preparing for incoming call: ${e.message}")
                false
            }
        }
    }

    /**
     * Crear PeerConnection optimizada
     */
    private fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            // Configuración optimizada para traducción en tiempo real
            bundlePolicy = PeerConnection.BundlePolicy.BALANCED
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

//            enableDtlsSrtp = true

            activeResetSrtpParams = true

//            enableRtpDataChannel = false

            surfaceIceCandidatesOnIceTransportTypeChanged=  true
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(signalingState: PeerConnection.SignalingState?) {
                    Log.d(TAG, "Signaling state: $signalingState")
                }

                override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState?) {
                    val state = mapIceConnectionState(iceConnectionState)
                    eventListener?.onConnectionStateChanged(state)
                }

                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                    receiver?.track()?.let { track ->
                        if (track.kind() == MediaStreamTrack.AUDIO_TRACK_KIND) {
                            val audioTrack = track as AudioTrack
                            remoteAudioTrack = audioTrack

                            // Notificar sin interceptación - conexión directa
                            eventListener?.onRemoteAudioTrack(audioTrack)

                            Log.d(TAG, "Remote audio track received (direct routing)")
                        }
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
            }
        )

        Log.d(TAG, "PeerConnection created with optimal configuration")
    }

    /**
     * Configurar constraints de audio optimizadas
     */
    private fun setupOptimizedAudioConstraints() {
        val audioConstraints = MediaConstraints().apply {
            // Configuración específica para OpenAI Realtime API
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "false"))

            // Configuraciones de calidad para traducción
            mandatory.add(MediaConstraints.KeyValuePair("googAudioMirroring", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("googDAEchoCancellation", "false"))

            // Sample rate óptimo para OpenAI
            optional.add(MediaConstraints.KeyValuePair("googSampleRate", "24000"))
        }

        audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
        Log.d(TAG, "Optimized audio constraints configured")
    }

    /**
     * Crear track de micrófono del agente
     */
    suspend fun createAgentMicrophoneTrack(): AudioTrack? {
        return withContext(Dispatchers.IO) {
            try {
                if (audioSource == null) {
                    setupOptimizedAudioConstraints()
                }

                val audioTrack = peerConnectionFactory?.createAudioTrack(
                    "agent_microphone_${System.currentTimeMillis()}",
                    audioSource
                )

                audioTrack?.setEnabled(true)
                localAudioTrack = audioTrack

                // Añadir al PeerConnection si existe
                peerConnection?.let { pc ->
                    pc.addTrack(audioTrack, listOf("agent_audio_stream"))
                }

                // Notificar disponibilidad
                audioTrack?.let { track ->
                    eventListener?.onLocalAudioTrack(track)
                }

                Log.d(TAG, "Agent microphone track created successfully")
                audioTrack

            } catch (e: Exception) {
                Log.e(TAG, "Error creating agent microphone track: ${e.message}")
                null
            }
        }
    }

    /**
     * Habilitar enrutamiento directo de audio
     */
    fun enableDirectAudioRouting(enabled: Boolean) {
        isDirectRoutingEnabled = enabled

        if (enabled) {
            Log.d(TAG, "Direct audio routing enabled - no interception")

            // Configurar tracks para flujo directo
            localAudioTrack?.setEnabled(true)
            remoteAudioTrack?.setEnabled(true)

        } else {
            Log.d(TAG, "Direct audio routing disabled")
        }
    }

    /**
     * Crear offer SDP optimizado
     */
    suspend fun createOffer(): String {
        return suspendCancellableCoroutine { continuation ->
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("VoiceActivityDetection", "false")) // Desactivar para OpenAI
            }

            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                    sessionDescription?.let { offer ->
                        // Optimizar SDP para OpenAI Realtime
                        val optimizedSdp = optimizeSdpForRealtime(offer.description)
                        continuation.resume(optimizedSdp) {}
                    } ?: continuation.resumeWithException(Exception("Offer SDP is null"))
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
     * Optimizar SDP para OpenAI Realtime API
     */
    private fun optimizeSdpForRealtime(originalSdp: String): String {
        return originalSdp
            .replace("a=fmtp:111 minptime=10;useinbandfec=1", "a=fmtp:111 minptime=10;useinbandfec=1;maxaveragebitrate=40000")
            .replace("a=maxptime:60", "a=maxptime:20") // Reducir para menor latencia
            .let { sdp ->
                // Añadir configuración específica para OpenAI si es necesario
                if (!sdp.contains("a=rtcp-fb:111 transport-cc")) {
                    sdp + "\r\na=rtcp-fb:111 transport-cc"
                } else sdp
            }
    }

    /**
     * Establecer descripción remota
     */
    suspend fun setRemoteDescription(sdp: String, type: SdpType): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val sdpType = when (type) {
                    SdpType.OFFER -> SessionDescription.Type.OFFER
                    SdpType.ANSWER -> SessionDescription.Type.ANSWER
                }

                val sessionDescription = SessionDescription(sdpType, sdp)

                suspendCancellableCoroutine<Boolean> { continuation ->
                    peerConnection?.setRemoteDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            continuation.resume(true) {}
                        }

                        override fun onSetFailure(error: String?) {
                            continuation.resume(false) {}
                        }

                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, sessionDescription)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting remote description: ${e.message}")
                false
            }
        }
    }

    /**
     * Mapear estado de ICE connection
     */
    private fun mapIceConnectionState(state: PeerConnection.IceConnectionState?): WebRtcConnectionState {
        return when (state) {
            PeerConnection.IceConnectionState.NEW -> WebRtcConnectionState.NEW
            PeerConnection.IceConnectionState.CHECKING -> WebRtcConnectionState.CONNECTING
            PeerConnection.IceConnectionState.CONNECTED -> WebRtcConnectionState.CONNECTED
            PeerConnection.IceConnectionState.COMPLETED -> WebRtcConnectionState.CONNECTED
            PeerConnection.IceConnectionState.FAILED -> WebRtcConnectionState.FAILED
            PeerConnection.IceConnectionState.DISCONNECTED -> WebRtcConnectionState.DISCONNECTED
            PeerConnection.IceConnectionState.CLOSED -> WebRtcConnectionState.CLOSED
            else -> WebRtcConnectionState.NEW
        }
    }

    /**
     * Configurar listener de eventos
     */
    fun setEventListener(listener: EventListener) {
        eventListener = listener
    }

    /**
     * Obtener estadísticas de conexión
     */
    suspend fun getConnectionStats(): ConnectionStats {
        return withContext(Dispatchers.IO) {
            try {
                // Obtener estadísticas reales de WebRTC
                // Esto requeriría implementación de RTCStatsCollector

                ConnectionStats(
                    latency = 50, // ms
                    jitter = 5,   // ms
                    packetLoss = 0.1, // %
                    audioCodec = "opus",
                    bitrate = 32000 // bps
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error getting connection stats: ${e.message}")
                ConnectionStats()
            }
        }
    }

    data class ConnectionStats(
        val latency: Int = 0,
        val jitter: Int = 0,
        val packetLoss: Double = 0.0,
        val audioCodec: String = "unknown",
        val bitrate: Int = 0
    )

    /**
     * Limpiar recursos
     */
    fun dispose() {
        Log.d(TAG, "Disposing WebRTC client")

        try {
            localAudioTrack?.dispose()
            audioSource?.dispose()
            peerConnection?.close()
            peerConnectionFactory?.dispose()

            scope.cancel()

            Log.d(TAG, "WebRTC client disposed")

        } catch (e: Exception) {
            Log.e(TAG, "Error disposing WebRTC client: ${e.message}")
        }
    }
}
