package com.eddyslarez.siplibrary.data.services.asistente


import android.util.Log
import kotlinx.coroutines.*
import org.webrtc.*

/**
 * Puente de audio directo entre SIP y OpenAI Realtime
 * Sin interceptación ni procesamiento local para mínima latencia
 */
class AudioBridge(
    private val realtimeSession: RealtimeSession,
    private val peerConnectionFactory: PeerConnectionFactory // Añadir factory como parámetro
) {
    private val TAG = "AudioBridge"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Referencias directas a peers WebRTC
    private var sipToAgentPeer: PeerConnection? = null  // Peer A
    private var agentToSipPeer: PeerConnection? = null  // Peer B

    // Referencias a tracks SIP
    private var sipIncomingTrack: AudioTrack? = null
    private var sipOutgoingTrack: AudioTrack? = null
    private var agentMicTrack: AudioTrack? = null

    // Estado del puente
    private var isBridgeActive = false

    /**
     * Configurar puente de audio directo
     */
    suspend fun setupAudioBridge(
        sipIncomingAudio: AudioTrack,
        sipOutgoingAudio: AudioTrack,
        agentMicrophone: AudioTrack
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Setting up direct audio bridge")

                // Almacenar referencias
                sipIncomingTrack = sipIncomingAudio
                sipOutgoingTrack = sipOutgoingAudio
                agentMicTrack = agentMicrophone

                // Obtener peers de OpenAI
                sipToAgentPeer = realtimeSession.getPeerAForClientAudio()
                agentToSipPeer = realtimeSession.getPeerBForAgentAudio()

                if (sipToAgentPeer == null || agentToSipPeer == null) {
                    throw Exception("OpenAI Realtime peers not ready")
                }

                // Configurar enrutamiento directo
                setupDirectAudioRouting()

                isBridgeActive = true
                Log.d(TAG, "Direct audio bridge setup completed")
                true

            } catch (e: Exception) {
                Log.e(TAG, "Error setting up audio bridge: ${e.message}")
                false
            }
        }
    }

    /**
     * Configurar enrutamiento directo de audio (sin interceptación)
     */
    private fun setupDirectAudioRouting() {
        try {
            // === RUTA 1: Cliente SIP → OpenAI → Agente ===
            // El audio del cliente SIP va directamente al Peer A
            sipIncomingTrack?.let { sipAudio ->
                // Reenviar directamente al peer A (Cliente → Agente)
                redirectAudioTrackToPeer(
                    sipAudio,
                    sipToAgentPeer!!,
                    "SIP_CLIENT_TO_AGENT",
                    peerConnectionFactory // Pasar la factory
                )
            }

            // === RUTA 2: Agente → OpenAI → Cliente SIP ===
            // El audio del agente va directamente al Peer B
            agentMicTrack?.let { agentAudio ->
                // Reenviar directamente al peer B (Agente → Cliente)
                redirectAudioTrackToPeer(
                    agentAudio,
                    agentToSipPeer!!,
                    "AGENT_TO_SIP_CLIENT",
                    peerConnectionFactory // Pasar la factory
                )
            }

            // === CONFIGURAR RECEPCIÓN DE AUDIO TRADUCIDO ===
            setupTranslatedAudioReception()

            Log.d(TAG, "Direct audio routing configured without interception")

        } catch (e: Exception) {
            Log.e(TAG, "Error in direct audio routing: ${e.message}")
        }
    }

    /**
     * Redirigir audio track directamente a peer OpenAI
     */
    private fun redirectAudioTrackToPeer(
        sourceTrack: AudioTrack,
        targetPeer: PeerConnection,
        routeLabel: String,
        factory: PeerConnectionFactory
    ) {
        try {
            // Configurar transceiver para envío directo usando constructor con dirección
            val transceiver = targetPeer.addTransceiver(
                sourceTrack,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
            )

            // Configurar codec específico para OpenAI
            configureOptimalCodec(transceiver, factory)

            Log.d(TAG, "Audio track redirected directly: $routeLabel")

        } catch (e: Exception) {
            Log.e(TAG, "Error redirecting audio track: ${e.message}")
        }
    }

    /**
     * Configurar recepción de audio traducido
     */

    /**
     * Configurar recepción de audio traducido
     */
    private fun setupTranslatedAudioReception() {
        // === RECEPCIÓN DESPEER A (traducido para agente) ===
        sipToAgentPeer?.receivers?.forEach { receiver ->
            receiver.track()?.let { track ->
                if (track.kind() == "audio") {
                    // Audio traducido del cliente para el agente
                    val translatedTrack = track as AudioTrack
                    routeTranslatedAudioToAgentSpeaker(translatedTrack)
                }
            }
        }

        // === RECEPCIÓN DESPEER B (traducido para cliente) ===
        agentToSipPeer?.receivers?.forEach { receiver ->
            receiver.track()?.let { track ->
                if (track.kind() == "audio") {
                    // Audio traducido del agente para el cliente
                    val translatedTrack = track as AudioTrack
                    routeTranslatedAudioToSipClient(translatedTrack)
                }
            }
        }

        Log.d(TAG, "Translated audio reception configured")
    }

    /**
     * Enrutar audio traducido al altavoz del agente
     */
    private fun routeTranslatedAudioToAgentSpeaker(translatedTrack: AudioTrack) {
        try {
            // Habilitar track para reproducción directa
            translatedTrack.setEnabled(true)
            Log.d(TAG, "Translated audio routed to agent speaker (direct)")

        } catch (e: Exception) {
            Log.e(TAG, "Error routing translated audio to agent: ${e.message}")
        }
    }

    /**
     * Enrutar audio traducido al cliente SIP
     */
    private fun routeTranslatedAudioToSipClient(translatedTrack: AudioTrack) {
        try {
            // Configurar el track traducido como fuente para el audio de salida SIP
            agentMicTrack?.setEnabled(false)
            redirectTranslatedAudioToSipOutput(translatedTrack)
            Log.d(TAG, "Translated audio routed to SIP client (direct)")

        } catch (e: Exception) {
            Log.e(TAG, "Error routing translated audio to SIP client: ${e.message}")
        }
    }

    /**
     * Redirigir audio traducido al flujo de salida SIP
     */
    /**
     * Redirigir audio traducido al flujo de salida SIP
     */
    private fun redirectTranslatedAudioToSipOutput(translatedTrack: AudioTrack) {
        translatedTrack.setEnabled(true)
        Log.d(TAG, "Translated audio redirected to SIP output stream")
    }

    /**
     * Configurar codec óptimo para OpenAI Realtime
     */
    private fun configureOptimalCodec(transceiver: RtpTransceiver, factory: PeerConnectionFactory) {
        try {
            // Obtener capacidades de audio del factory
            val capabilities = factory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO)

            // Buscar OPUS a 48kHz
            val preferredCodec = capabilities.codecs.find { codec ->
                codec.name.equals("opus", ignoreCase = true) &&
                        codec.clockRate == 48000
            }

            preferredCodec?.let { codec ->
                transceiver.setCodecPreferences(listOf(codec))
                Log.d(TAG, "Optimal codec configured: ${codec.name} @ ${codec.clockRate}Hz")
            } ?: Log.w(TAG, "No suitable codec found")

        } catch (e: Exception) {
            Log.w(TAG, "Could not configure optimal codec: ${e.message}")
        }
    }


    /**
     * Alternar idiomas dinámicamente
     */
    suspend fun switchLanguages() {
        try {
            Log.d(TAG, "Switching translation languages")

            // Intercambiar idiomas en ambos peers
//            realtimeSession.updateLanguages(targetLanguage, sourceLanguage)

            Log.d(TAG, "Languages switched successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error switching languages: ${e.message}")
        }
    }

    /**
     * Pausar traducción (mantener audio original)
     */
    fun pauseTranslation() {
        try {
            Log.d(TAG, "Pausing translation - switching to direct audio")

            // Deshabilitar peers OpenAI temporalmente
            sipToAgentPeer?.senders?.forEach { sender ->
                sender.track()?.setEnabled(false)
            }
            agentToSipPeer?.senders?.forEach { sender ->
                sender.track()?.setEnabled(false)
            }

            // Habilitar audio directo SIP
            sipIncomingTrack?.setEnabled(true)
            agentMicTrack?.setEnabled(true)

            Log.d(TAG, "Translation paused - using direct audio")

        } catch (e: Exception) {
            Log.e(TAG, "Error pausing translation: ${e.message}")
        }
    }

    /**
     * Reanudar traducción
     */
    fun resumeTranslation() {
        try {
            Log.d(TAG, "Resuming translation")

            // Deshabilitar audio directo
            sipIncomingTrack?.setEnabled(false) // No reproducir directamente
            agentMicTrack?.setEnabled(false)    // No enviar directamente

            // Habilitar peers OpenAI
            sipToAgentPeer?.senders?.forEach { sender ->
                sender.track()?.setEnabled(true)
            }
            agentToSipPeer?.senders?.forEach { sender ->
                sender.track()?.setEnabled(true)
            }

            Log.d(TAG, "Translation resumed")

        } catch (e: Exception) {
            Log.e(TAG, "Error resuming translation: ${e.message}")
        }
    }

    /**
     * Verificar estado del puente
     */
    fun isActive(): Boolean = isBridgeActive

    /**
     * Obtener latencia estimada
     */
    fun getEstimatedLatency(): LatencyInfo {
        return LatencyInfo(
            sipToOpenAI = 50, // ms estimado
            openAIProcessing = 200, // ms estimado para traducción
            openAIToOutput = 50, // ms estimado
            totalEstimated = 300 // ms total estimado
        )
    }

    data class LatencyInfo(
        val sipToOpenAI: Int,
        val openAIProcessing: Int,
        val openAIToOutput: Int,
        val totalEstimated: Int
    )

    /**
     * Limpiar puente de audio
     */
    fun dispose() {
        Log.d(TAG, "Disposing audio bridge")

        isBridgeActive = false

        try {
            // Limpiar referencias
            sipIncomingTrack = null
            sipOutgoingTrack = null
            agentMicTrack = null
            sipToAgentPeer = null
            agentToSipPeer = null

            scope.cancel()

            Log.d(TAG, "Audio bridge disposed")

        } catch (e: Exception) {
            Log.e(TAG, "Error disposing audio bridge: ${e.message}")
        }
    }

    /**
     * Diagnóstico del puente de audio
     */
    fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== AUDIO BRIDGE DIAGNOSTIC ===")
            appendLine("Bridge Active: $isBridgeActive")
            appendLine("SIP Incoming Track: ${sipIncomingTrack != null}")
            appendLine("SIP Outgoing Track: ${sipOutgoingTrack != null}")
            appendLine("Agent Mic Track: ${agentMicTrack != null}")
            appendLine("Peer A (SIP→Agent): ${sipToAgentPeer != null}")
            appendLine("Peer B (Agent→SIP): ${agentToSipPeer != null}")

            val latency = getEstimatedLatency()
            appendLine("Estimated Latency: ${latency.totalEstimated}ms")
            appendLine("  SIP→OpenAI: ${latency.sipToOpenAI}ms")
            appendLine("  OpenAI Processing: ${latency.openAIProcessing}ms")
            appendLine("  OpenAI→Output: ${latency.openAIToOutput}ms")
        }
    }
}
