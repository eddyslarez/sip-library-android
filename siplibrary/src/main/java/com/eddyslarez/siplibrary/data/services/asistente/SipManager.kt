package com.eddyslarez.siplibrary.data.services.asistente


import android.app.Application
import android.util.Log
import com.eddyslarez.siplibrary.EddysSipLibrary
import com.eddyslarez.siplibrary.core.SipCoreManager
import com.eddyslarez.siplibrary.data.services.audio.WebRtcConnectionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.webrtc.AudioTrack

/**
 * Gestión SIP optimizada para traducción en tiempo real
 * Integración directa con WebRTC para mínima latencia
 */
class SipManager(
    private val application: Application,
    private val sipCoreManager: SipCoreManager,
    private val webRTCClient: WebRTCClient
) {
    private val TAG = "SipManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Estados de llamada SIP
    private val _callStateFlow = MutableStateFlow(SipCallState.IDLE)
    val callStateFlow: StateFlow<SipCallState> = _callStateFlow.asStateFlow()

    // Audio tracks de la llamada actual
    private var currentCallAudioTracks: CallAudioTracks? = null
    private var agentMicrophoneTrack: AudioTrack? = null

    enum class SipCallState {
        IDLE,
        INCOMING,
        OUTGOING,
        CONNECTED,
        HELD,
        ENDED,
        ERROR;

        fun isActive(): Boolean {
            return this == CONNECTED || this == INCOMING || this == OUTGOING || this == HELD
        }
    }
    data class CallAudioTracks(
        val incoming: AudioTrack?,
        val outgoing: AudioTrack?,
        val callId: String
    )

    fun initialize() {
        setupSipCallbacks()
        setupWebRTCIntegration()
    }

    /**
     * Configurar callbacks SIP optimizados
     */
    private fun setupSipCallbacks() {
        sipCoreManager.setCallbacks(object : EddysSipLibrary.SipCallbacks {
            override fun onIncomingCall(callerNumber: String, callerName: String?) {
                Log.d(TAG, "Incoming SIP call from: $callerNumber")
                _callStateFlow.value = SipCallState.INCOMING

                // Preparar audio tracks para la llamada entrante
                prepareAudioTracksForIncomingCall(callerNumber)
            }

            override fun onCallConnected() {
                Log.d(TAG, "SIP call connected")
                _callStateFlow.value = SipCallState.CONNECTED

                // Configurar audio tracks para llamada establecida
                setupAudioTracksForConnectedCall()
            }

            override fun onCallTerminated() {
                Log.d(TAG, "SIP call terminated")
                _callStateFlow.value = SipCallState.ENDED

                // Limpiar audio tracks
                cleanupAudioTracks()
            }

            override fun onCallFailed(error: String) {
                Log.e(TAG, "SIP call failed: $error")
                _callStateFlow.value = SipCallState.ERROR
                cleanupAudioTracks()
            }
        })
    }

    /**
     * Configurar integración WebRTC directa
     */
    private fun setupWebRTCIntegration() {
        webRTCClient.setEventListener(object : WebRTCClient.EventListener {
            override fun onRemoteAudioTrack(audioTrack: AudioTrack) {
                Log.d(TAG, "Remote audio track received from SIP")

                // Almacenar track de audio entrante (del cliente SIP)
                val currentTracks = currentCallAudioTracks
                if (currentTracks != null) {
                    currentCallAudioTracks = currentTracks.copy(incoming = audioTrack)
                } else {
                    currentCallAudioTracks = CallAudioTracks(
                        incoming = audioTrack,
                        outgoing = null,
                        callId = sipCoreManager.currentAccountInfo?.currentCallData?.callId ?: ""
                    )
                }

                Log.d(TAG, "SIP incoming audio track configured")
            }

            override fun onLocalAudioTrack(audioTrack: AudioTrack) {
                Log.d(TAG, "Local audio track ready for SIP")

                // Almacenar track de audio saliente (hacia cliente SIP)
                val currentTracks = currentCallAudioTracks
                if (currentTracks != null) {
                    currentCallAudioTracks = currentTracks.copy(outgoing = audioTrack)
                } else {
                    currentCallAudioTracks = CallAudioTracks(
                        incoming = null,
                        outgoing = audioTrack,
                        callId = sipCoreManager.currentAccountInfo?.currentCallData?.callId ?: ""
                    )
                }

                Log.d(TAG, "SIP outgoing audio track configured")
            }

            override fun onConnectionStateChanged(state: WebRtcConnectionState) {
                Log.d(TAG, "WebRTC connection state: $state")
            }

            override fun onError(error: String) {
                Log.d(TAG, "WebRTC connection error: $error")
            }
        })
    }

    /**
     * Preparar audio tracks para llamada entrante
     */
    private fun prepareAudioTracksForIncomingCall(callerNumber: String) {
        scope.launch {
            try {
                // Preparar WebRTC para audio de llamada entrante
                webRTCClient.prepareForIncomingCall()

                // Inicializar tracks de audio del agente (micrófono)
                agentMicrophoneTrack = webRTCClient.createAgentMicrophoneTrack()

                Log.d(TAG, "Audio tracks prepared for incoming call from: $callerNumber")

            } catch (e: Exception) {
                Log.e(TAG, "Error preparing audio tracks: ${e.message}")
            }
        }
    }

    /**
     * Configurar audio tracks para llamada establecida
     */
    private fun setupAudioTracksForConnectedCall() {
        scope.launch {
            try {
                // Asegurar que tenemos todos los tracks necesarios
                if (agentMicrophoneTrack == null) {
                    agentMicrophoneTrack = webRTCClient.createAgentMicrophoneTrack()
                }

                // Configurar para enrutamiento directo (sin interceptación)
                webRTCClient.enableDirectAudioRouting(true)

                Log.d(TAG, "Audio tracks configured for connected SIP call")

            } catch (e: Exception) {
                Log.e(TAG, "Error setting up audio tracks: ${e.message}")
            }
        }
    }

    /**
     * Obtener audio tracks de la llamada actual
     */
    fun getCurrentCallAudioTracks(): CallAudioTracks? {
        return currentCallAudioTracks
    }

    /**
     * Obtener track del micrófono del agente
     */
    fun getAgentMicrophoneTrack(): AudioTrack? {
        return agentMicrophoneTrack
    }

    /**
     * Aceptar llamada SIP
     */
    fun acceptCall() {
        Log.d(TAG, "Accepting SIP call")
        sipCoreManager.acceptCall()
    }

    /**
     * Rechazar llamada SIP
     */
    fun declineCall() {
        Log.d(TAG, "Declining SIP call")
        sipCoreManager.declineCall()
    }

    /**
     * Terminar llamada SIP
     */
    fun endCall() {
        Log.d(TAG, "Ending SIP call")
        sipCoreManager.endCall()
    }

    /**
     * Realizar llamada SIP
     */
    fun makeCall(phoneNumber: String, sipName: String, domain: String) {
        Log.d(TAG, "Making SIP call to: $phoneNumber")
        _callStateFlow.value = SipCallState.OUTGOING

        scope.launch {
            try {
                // Preparar audio tracks para llamada saliente
                agentMicrophoneTrack = webRTCClient.createAgentMicrophoneTrack()

                // Realizar llamada
                sipCoreManager.makeCall(phoneNumber, sipName,domain)

            } catch (e: Exception) {
                Log.e(TAG, "Error making call: ${e.message}")
                _callStateFlow.value = SipCallState.ERROR
            }
        }
    }

    /**
     * Alternar mute del agente
     */
    fun toggleMute(): Boolean {
        return try {
            sipCoreManager.mute()
//            val isMuted = sipCoreManager.isMuted()
//            Log.d(TAG, "Agent mute toggled: $isMuted")
//            isMuted
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error toggling mute: ${e.message}")
            false
        }
    }

    /**
     * Verificar si el agente está en mute
     */
//    fun isMuted(): Boolean {
//        return sipCoreManager.isMuted()
//    }

    /**
     * Limpiar audio tracks
     */
    private fun cleanupAudioTracks() {
        currentCallAudioTracks = null
        agentMicrophoneTrack?.dispose()
        agentMicrophoneTrack = null

        Log.d(TAG, "Audio tracks cleaned up")
    }

    /**
     * Obtener información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== SIP MANAGER DIAGNOSTIC ===")
            appendLine("Call State: ${_callStateFlow.value}")
            appendLine("Current Call Audio Tracks: ${currentCallAudioTracks != null}")
            appendLine("Agent Microphone Track: ${agentMicrophoneTrack != null}")

            currentCallAudioTracks?.let { tracks ->
                appendLine("Call ID: ${tracks.callId}")
                appendLine("Incoming Track: ${tracks.incoming != null}")
                appendLine("Outgoing Track: ${tracks.outgoing != null}")
            }

//            appendLine("Is Muted: ${isMuted()}")
            appendLine("SIP Core Healthy: ${sipCoreManager.isSipCoreManagerHealthy()}")
        }
    }

    /**
     * Limpiar recursos
     */
    fun dispose() {
        Log.d(TAG, "Disposing SIP manager")

        cleanupAudioTracks()
        scope.cancel()

        Log.d(TAG, "SIP manager disposed")
    }
}
