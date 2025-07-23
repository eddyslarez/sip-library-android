package com.eddyslarez.siplibrary.data.services.audio

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

import com.eddyslarez.siplibrary.utils.log

import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

import com.shepeliev.webrtckmp.onConnectionStateChange
import com.shepeliev.webrtckmp.onIceCandidate
import com.shepeliev.webrtckmp.onTrack

import com.shepeliev.webrtckmp.MediaStreamTrackKind
import kotlinx.coroutines.delay

/**
 * Enhanced Android implementation of WebRtcManager interface with comprehensive audio device support
 * FIXED: Bluetooth audio routing issues
 *
 * @author Eddys Larez
 */
import android.Manifest
import android.app.Application
import android.content.Context
import androidx.annotation.RequiresPermission
import com.eddyslarez.siplibrary.data.models.AccountInfo
import com.eddyslarez.siplibrary.data.models.CallState
import com.eddyslarez.siplibrary.data.services.audio.newAudio.RefactoredAudioManager
import com.eddyslarez.siplibrary.data.services.audio.newAudio.VirtualAudioProcessor
import com.eddyslarez.siplibrary.data.services.audio.newAudio.WebRtcEventListener
import com.eddyslarez.siplibrary.utils.CallStateManager
import com.eddyslarez.siplibrary.utils.log
import com.shepeliev.webrtckmp.AudioStreamTrack
import com.shepeliev.webrtckmp.PeerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import com.shepeliev.webrtckmp.IceCandidate
import com.shepeliev.webrtckmp.IceServer
import com.shepeliev.webrtckmp.OfferAnswerOptions
import com.shepeliev.webrtckmp.PeerConnectionState
import com.shepeliev.webrtckmp.RtcConfiguration
import com.shepeliev.webrtckmp.SessionDescription
import com.shepeliev.webrtckmp.SessionDescriptionType
import com.shepeliev.webrtckmp.onConnectionStateChange
import com.shepeliev.webrtckmp.onIceCandidate
import com.shepeliev.webrtckmp.onTrack
import com.shepeliev.webrtckmp.MediaDevices
import com.shepeliev.webrtckmp.MediaDeviceInfo
import com.shepeliev.webrtckmp.audioTracks
import kotlinx.coroutines.delay

/**
 * AndroidWebRtcManager refactorizado con gestión de audio virtual
 * Integra el RefactoredAudioManager y VirtualAudioProcessor
 *
 * @author Eddys Larez
 */
class AndroidWebRtcManager(private val application: Application) : WebRtcManager {
    private val TAG = "AndroidWebRtcManager"
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioStreamTrack? = null
    private var remoteAudioTrack: AudioStreamTrack? = null
    private var webRtcEventListener: WebRtcEventListener? = null
    private var isInitialized = false
    private var isLocalAudioReady = false
    private var context: Context = application.applicationContext

    // Gestores de audio refactorizados
    private val refactoredAudioManager = RefactoredAudioManager(application)
    private val virtualAudioProcessor = VirtualAudioProcessor(context)

    // Estados de audio virtual
    private var isVirtualAudioEnabled = false
    private var isReceivingRemoteAudio = false

    init {
        setupVirtualAudioCallbacks()
    }

    /**
     * Configurar callbacks del procesador de audio virtual
     */
    private fun setupVirtualAudioCallbacks() {
        // Callback para audio transcrito del remoto
        virtualAudioProcessor.addTranscriptionCallback { transcribedText ->
            log.d(TAG) { "Audio remoto transcrito: $transcribedText" }
            webRtcEventListener?.onRemoteAudioTranscribed(transcribedText)
        }

        // Callback para errores de procesamiento
        virtualAudioProcessor.addErrorCallback { error ->
            log.e(TAG) { "Error en procesamiento de audio virtual: $error" }
        }
    }

    /**
     * Habilita el audio virtual personalizado
     */
    suspend fun enableVirtualAudio(enable: Boolean) {
        log.d(TAG) { "Habilitando audio virtual: $enable" }
        isVirtualAudioEnabled = enable

        if (enable) {
            virtualAudioProcessor.initialize()
        } else {
            virtualAudioProcessor.dispose()
        }
    }

    /**
     * Inyecta audio personalizado en lugar del micrófono
     */
    fun injectCustomAudio(audioData: ByteArray, sampleRate: Int = 16000) {
        if (!isVirtualAudioEnabled) {
            log.w(TAG) { "Audio virtual no habilitado" }
            return
        }

        virtualAudioProcessor.injectAudioData(audioData, sampleRate)
    }

    /**
     * Reproduce audio personalizado en lugar del audio remoto recibido
     */
    fun playCustomAudio(audioData: ByteArray, sampleRate: Int = 16000) {
        if (!isVirtualAudioEnabled) {
            log.w(TAG) { "Audio virtual no habilitado" }
            return
        }

        virtualAudioProcessor.playCustomAudio(audioData, sampleRate)
    }

    /**
     * Inicia la transcripción del audio remoto recibido
     */
    fun startRemoteAudioTranscription() {
        if (!isVirtualAudioEnabled) {
            log.w(TAG) { "Audio virtual no habilitado para transcripción" }
            return
        }

        virtualAudioProcessor.startRemoteAudioProcessing()
        isReceivingRemoteAudio = true
    }

    /**
     * Detiene la transcripción del audio remoto
     */
    fun stopRemoteAudioTranscription() {
        virtualAudioProcessor.stopRemoteAudioProcessing()
        isReceivingRemoteAudio = false
    }

    @SuppressLint("MissingPermission")
    override fun initialize() {
        log.d(TAG) { "Inicializando AndroidWebRtcManager refactorizado..." }

        if (!isInitialized) {
            // Inicializar gestores de audio
            refactoredAudioManager.initialize()

            // Inicializar WebRTC
            initializePeerConnection()

            coroutineScope.launch {
                getAudioInputDevices()
            }

            isInitialized = true
            log.d(TAG) { "AndroidWebRtcManager inicializado exitosamente" }
        } else {
            log.d(TAG) { "AndroidWebRtcManager ya inicializado" }
        }
    }

    /**
     * Obtiene dispositivos de entrada de audio
     */
    suspend fun getAudioInputDevices(): List<MediaDeviceInfo> {
        return MediaDevices.enumerateDevices()
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
    override fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        return refactoredAudioManager.refreshAudioDevices()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean {
        log.d(TAG) { "Cambiando dispositivo de salida: ${device.name}" }

        if (!isInitialized || peerConnection == null) {
            log.w(TAG) { "No se puede cambiar dispositivo: WebRTC no inicializado" }
            return false
        }

        val success = refactoredAudioManager.changeOutputDevice(device)
        if (success) {
            webRtcEventListener?.onAudioDeviceChanged(device)
        }

        return success
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
    override fun changeAudioInputDeviceDuringCall(device: AudioDevice): Boolean {
        log.d(TAG) { "Cambiando dispositivo de entrada: ${device.name}" }

        if (!isInitialized || peerConnection == null) {
            log.w(TAG) { "No se puede cambiar dispositivo: WebRTC no inicializado" }
            return false
        }

        val success = refactoredAudioManager.changeInputDevice(device)
        if (success) {
            webRtcEventListener?.onAudioDeviceChanged(device)
        }

        return success
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun getCurrentInputDevice(): AudioDevice? {
        return refactoredAudioManager.currentInputDevice.value
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun getCurrentOutputDevice(): AudioDevice? {
        return refactoredAudioManager.currentOutputDevice.value
    }

    override suspend fun createOffer(): String {
        log.d(TAG) { "Creando oferta SDP..." }

        if (!isInitialized) {
            log.d(TAG) { "WebRTC no inicializado, inicializando ahora" }
            initialize()
        }

        val peerConn = peerConnection ?: run {
            log.d(TAG) { "PeerConnection no inicializada, reinicializando" }
            initializePeerConnection()
            peerConnection ?: throw IllegalStateException("Falló la inicialización de PeerConnection")
        }

        // Asegurar que el track de audio local esté listo
        if (!isLocalAudioReady) {
            log.d(TAG) { "Asegurando que el track de audio local esté listo..." }
            isLocalAudioReady = ensureLocalAudioTrack()
            if (!isLocalAudioReady) {
                log.w(TAG) { "Falló la preparación del track de audio local" }
            }
        }

        val options = OfferAnswerOptions(voiceActivityDetection = true)
        val sessionDescription = peerConn.createOffer(options)
        peerConn.setLocalDescription(sessionDescription)

        // Asegurar que el micrófono no esté silenciado
        refactoredAudioManager.setMicrophoneMuted(false)

        log.d(TAG) { "Oferta SDP creada: ${sessionDescription.sdp}" }
        return sessionDescription.sdp
    }

    override suspend fun createAnswer(accountInfo: AccountInfo, offerSdp: String): String {
        log.d(TAG) { "Creando respuesta SDP..." }

        if (!isInitialized) {
            log.d(TAG) { "WebRTC no inicializado, inicializando ahora" }
            initialize()
        }

        val peerConn = peerConnection ?: run {
            log.d(TAG) { "PeerConnection no inicializada, reinicializando" }
            initializePeerConnection()
            peerConnection ?: throw IllegalStateException("Falló la inicialización de PeerConnection")
        }

        // Asegurar que el track de audio local esté listo ANTES de establecer la descripción remota
        if (!isLocalAudioReady) {
            log.d(TAG) { "Asegurando que el track de audio local esté listo antes de responder..." }
            isLocalAudioReady = ensureLocalAudioTrack()
            if (!isLocalAudioReady) {
                log.w(TAG) { "Falló la preparación del track de audio local para responder" }
            }
        }

        // Establecer la oferta remota
        val remoteOffer = SessionDescription(
            type = SessionDescriptionType.Offer,
            sdp = offerSdp
        )
        peerConn.setRemoteDescription(remoteOffer)

        // Crear respuesta
        val options = OfferAnswerOptions(voiceActivityDetection = true)
        val sessionDescription = peerConn.createAnswer(options)
        peerConn.setLocalDescription(sessionDescription)

        // Habilitar audio para responder la llamada
        setAudioEnabled(true)
        refactoredAudioManager.setMicrophoneMuted(false)

        log.d(TAG) { "Respuesta SDP creada: ${sessionDescription.sdp}" }
        return sessionDescription.sdp
    }

    override suspend fun setRemoteDescription(sdp: String, type: SdpType) {
        log.d(TAG) { "Estableciendo descripción remota tipo: $type" }

        if (!isInitialized) {
            log.d(TAG) { "WebRTC no inicializado, inicializando ahora" }
            initialize()
        }

        val peerConn = peerConnection ?: run {
            log.d(TAG) { "PeerConnection no inicializada, reinicializando" }
            initializePeerConnection()
            peerConnection ?: throw IllegalStateException("Falló la inicialización de PeerConnection")
        }

        if (type == SdpType.OFFER && !isLocalAudioReady) {
            log.d(TAG) { "Asegurando que el track de audio local esté listo antes de procesar la oferta..." }
            isLocalAudioReady = ensureLocalAudioTrack()
        }

        val sdpType = when (type) {
            SdpType.OFFER -> SessionDescriptionType.Offer
            SdpType.ANSWER -> SessionDescriptionType.Answer
        }

        val sessionDescription = SessionDescription(type = sdpType, sdp = sdp)
        peerConn.setRemoteDescription(sessionDescription)

        if (type == SdpType.ANSWER) {
            setAudioEnabled(true)
            refactoredAudioManager.setMicrophoneMuted(false)
        }
    }

    override suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        log.d(TAG) { "Agregando candidato ICE: $candidate" }

        if (!isInitialized) {
            log.d(TAG) { "WebRTC no inicializado, inicializando ahora" }
            initialize()
            if (peerConnection == null) {
                log.w(TAG) { "Falló la inicialización de PeerConnection, no se puede agregar candidato ICE" }
                return
            }
        }

        val peerConn = peerConnection ?: run {
            log.w(TAG) { "PeerConnection no disponible, no se puede agregar candidato ICE" }
            return
        }

        val iceCandidate = IceCandidate(
            sdpMid = sdpMid ?: "",
            sdpMLineIndex = sdpMLineIndex ?: 0,
            candidate = candidate
        )

        peerConn.addIceCandidate(iceCandidate)
    }

    override fun setMuted(muted: Boolean) {
        log.d(TAG) { "Estableciendo silencio del micrófono: $muted" }

        refactoredAudioManager.setMicrophoneMuted(muted)
        localAudioTrack?.enabled = !muted
    }

    override fun isMuted(): Boolean {
        return refactoredAudioManager.isMicrophoneMuted() || (localAudioTrack?.enabled?.not() ?: false)
    }

    override fun getLocalDescription(): String? {
        return peerConnection?.localDescription?.sdp
    }

    override suspend fun setMediaDirection(direction: WebRtcManager.MediaDirection) {
        log.d(TAG) { "Estableciendo dirección de medios: $direction" }

        if (!isInitialized || peerConnection == null) {
            log.w(TAG) { "No se puede establecer dirección de medios: WebRTC no inicializado" }
            return
        }

        val peerConn = peerConnection ?: return

        try {
            val currentDesc = peerConn.localDescription ?: return
            val modifiedSdp = updateSdpDirection(currentDesc.sdp, direction)
            val newDesc = SessionDescription(type = currentDesc.type, sdp = modifiedSdp)
            peerConn.setLocalDescription(newDesc)

            if (peerConn.remoteDescription != null) {
                val options = OfferAnswerOptions(voiceActivityDetection = true)
                val sessionDesc = if (currentDesc.type == SessionDescriptionType.Offer) {
                    peerConn.createOffer(options)
                } else {
                    peerConn.createAnswer(options)
                }

                val finalSdp = updateSdpDirection(sessionDesc.sdp, direction)
                val finalDesc = SessionDescription(type = sessionDesc.type, sdp = finalSdp)
                peerConn.setLocalDescription(finalDesc)
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error estableciendo dirección de medios: ${e.message}" }
        }
    }

    private fun updateSdpDirection(sdp: String, direction: WebRtcManager.MediaDirection): String {
        val directionStr = when (direction) {
            WebRtcManager.MediaDirection.SENDRECV -> "sendrecv"
            WebRtcManager.MediaDirection.SENDONLY -> "sendonly"
            WebRtcManager.MediaDirection.RECVONLY -> "recvonly"
            WebRtcManager.MediaDirection.INACTIVE -> "inactive"
        }

        val lines = sdp.lines().toMutableList()
        var inMediaSection = false
        var inAudioSection = false

        for (i in lines.indices) {
            val line = lines[i]

            if (line.startsWith("m=")) {
                inMediaSection = true
                inAudioSection = line.startsWith("m=audio")
            }

            if (inMediaSection && inAudioSection) {
                if (line.startsWith("a=sendrecv") ||
                    line.startsWith("a=sendonly") ||
                    line.startsWith("a=recvonly") ||
                    line.startsWith("a=inactive")) {
                    lines[i] = "a=$directionStr"
                }
            }

            if (inMediaSection && line.trim().isEmpty()) {
                inMediaSection = false
                inAudioSection = false
            }
        }

        return lines.joinToString("\r\n")
    }

    override suspend fun applyModifiedSdp(modifiedSdp: String): Boolean {
        return try {
            val description = SessionDescription(SessionDescriptionType.Offer, modifiedSdp)
            peerConnection?.setLocalDescription(description)
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error aplicando SDP modificado: ${e.message}" }
            false
        }
    }

    override fun setAudioEnabled(enabled: Boolean) {
        log.d(TAG) { "Estableciendo audio habilitado: $enabled" }

        refactoredAudioManager.setMicrophoneMuted(!enabled)

        if (localAudioTrack == null && isInitialized) {
            log.d(TAG) { "No hay track de audio local pero WebRTC está inicializado, intentando agregar track de audio" }
            coroutineScope.launch {
                ensureLocalAudioTrack()
                localAudioTrack?.enabled = enabled
            }
        } else {
            localAudioTrack?.enabled = enabled
        }
    }

    override fun getConnectionState(): WebRtcConnectionState {
        if (!isInitialized || peerConnection == null) {
            return WebRtcConnectionState.NEW
        }

        val state = peerConnection?.connectionState ?: return WebRtcConnectionState.NEW
        return mapConnectionState(state)
    }

    override fun setListener(listener: Any?) {
        if (listener is WebRtcEventListener) {
            webRtcEventListener = listener
            log.d(TAG) { "Listener de eventos WebRTC establecido" }
        } else {
            log.w(TAG) { "Tipo de listener inválido proporcionado" }
        }
    }

    @SuppressLint("MissingPermission")
    override fun prepareAudioForIncomingCall() {
        log.d(TAG) { "Preparando audio para llamada entrante" }
        refactoredAudioManager.initialize()
    }

    override fun diagnoseAudioIssues(): String {
        return buildString {
            appendLine("=== DIAGNÓSTICO DE AUDIO ANDROID WEBRTC ===")
            appendLine("WebRTC Inicializado: $isInitialized")
            appendLine("Audio Local Listo: $isLocalAudioReady")
            appendLine("Track Audio Local: ${localAudioTrack != null}")
            appendLine("Track Audio Local Habilitado: ${localAudioTrack?.enabled}")
            appendLine("Track Audio Remoto: ${remoteAudioTrack != null}")
            appendLine("Track Audio Remoto Habilitado: ${remoteAudioTrack?.enabled}")
            appendLine("Audio Virtual Habilitado: $isVirtualAudioEnabled")
            appendLine("Recibiendo Audio Remoto: $isReceivingRemoteAudio")
            appendLine()
            appendLine(refactoredAudioManager.getCompleteDiagnosticInfo())
            appendLine()
            appendLine(virtualAudioProcessor.getDiagnosticInfo())
        }
    }

    override fun isInitialized(): Boolean = isInitialized

    override fun sendDtmfTones(tones: String, duration: Int, gap: Int): Boolean {
        log.d(TAG) { "Enviando tonos DTMF: $tones (duración: $duration, pausa: $gap)" }

        if (!isInitialized || peerConnection == null) {
            log.w(TAG) { "No se pueden enviar DTMF: WebRTC no inicializado" }
            return false
        }

        try {
            val audioSender = peerConnection?.getSenders()?.find { sender ->
                sender.track?.kind == com.shepeliev.webrtckmp.MediaStreamTrackKind.Audio
            }

            if (audioSender == null) {
                log.w(TAG) { "No se pueden enviar DTMF: No se encontró sender de audio" }
                return false
            }

            val dtmfSender = audioSender.dtmf ?: run {
                log.w(TAG) { "No se pueden enviar DTMF: DtmfSender no disponible" }
                return false
            }

            val sanitizedTones = sanitizeDtmfTones(tones)
            if (sanitizedTones.isEmpty()) {
                log.w(TAG) { "No se pueden enviar DTMF: No hay tonos válidos para enviar" }
                return false
            }

            val result = dtmfSender.insertDtmf(
                tones = sanitizedTones,
                durationMs = duration,
                interToneGapMs = gap
            )

            log.d(TAG) { "Resultado del envío de tonos DTMF: $result" }
            return result
        } catch (e: Exception) {
            log.e(TAG) { "Error enviando tonos DTMF: ${e.message}" }
            return false
        }
    }

    private fun sanitizeDtmfTones(tones: String): String {
        val validDtmfPattern = Regex("[0-9A-D*#,]", RegexOption.IGNORE_CASE)
        return tones.filter { tone ->
            validDtmfPattern.matches(tone.toString())
        }
    }

    private fun mapConnectionState(state: PeerConnectionState): WebRtcConnectionState {
        return when (state) {
            PeerConnectionState.New -> WebRtcConnectionState.NEW
            PeerConnectionState.Connecting -> WebRtcConnectionState.CONNECTING
            PeerConnectionState.Connected -> WebRtcConnectionState.CONNECTED
            PeerConnectionState.Disconnected -> WebRtcConnectionState.DISCONNECTED
            PeerConnectionState.Failed -> WebRtcConnectionState.FAILED
            PeerConnectionState.Closed -> WebRtcConnectionState.CLOSED
        }
    }

    private fun initializePeerConnection() {
        log.d(TAG) { "Inicializando PeerConnection..." }
        cleanupCall()

        try {
            val rtcConfig = RtcConfiguration(
                iceServers = listOf(
                    IceServer(
                        urls = listOf(
                            "stun:stun.l.google.com:19302",
                            "stun:stun1.l.google.com:19302"
                        )
                    )
                )
            )

            peerConnection = PeerConnection(rtcConfig).apply {
                setupPeerConnectionObservers()
            }

            log.d(TAG) { "PeerConnection creada: ${peerConnection != null}" }
            isLocalAudioReady = false
        } catch (e: Exception) {
            log.e(TAG) { "Error inicializando PeerConnection: ${e.message}" }
            peerConnection = null
            isInitialized = false
            isLocalAudioReady = false
        }
    }

    private fun PeerConnection.setupPeerConnectionObservers() {
        onIceCandidate.onEach { candidate ->
            log.d(TAG) { "Nuevo candidato ICE: ${candidate.candidate}" }
            webRtcEventListener?.onIceCandidate(
                candidate.candidate,
                candidate.sdpMid,
                candidate.sdpMLineIndex
            )
        }.launchIn(coroutineScope)

        onConnectionStateChange.onEach { state ->
            log.d(TAG) { "Estado de conexión cambiado: $state" }

            when (state) {
                PeerConnectionState.Connected -> {
                    log.d(TAG) { "Llamada activa: Conectada" }
                    CallStateManager.updateCallState(CallState.CONNECTED)
                    setAudioEnabled(true)
                    refactoredAudioManager.setMicrophoneMuted(false)

                    // Iniciar transcripción del audio remoto si está habilitado
                    if (isVirtualAudioEnabled) {
                        startRemoteAudioTranscription()
                    }
                }

                PeerConnectionState.Disconnected,
                PeerConnectionState.Failed,
                PeerConnectionState.Closed -> {
                    CallStateManager.updateCallState(CallState.ENDED)
                    log.d(TAG) { "Llamada terminada" }

                    // Detener transcripción del audio remoto
                    if (isReceivingRemoteAudio) {
                        stopRemoteAudioTranscription()
                    }
                }

                else -> {
                    log.d(TAG) { "Otro estado de conexión: $state" }
                }
            }

            webRtcEventListener?.onConnectionStateChange(mapConnectionState(state))
        }.launchIn(coroutineScope)

        onTrack.onEach { event ->
            log.d(TAG) { "Track remoto recibido: $event" }
            val track = event.receiver.track

            if (track is AudioStreamTrack) {
                log.d(TAG) { "Track de audio remoto establecido" }
                remoteAudioTrack = track
                remoteAudioTrack?.enabled = true

                // Si el audio virtual está habilitado, procesar el audio remoto
                if (isVirtualAudioEnabled) {
                    setupRemoteAudioProcessing(track)
                }

                webRtcEventListener?.onRemoteAudioTrack()
            }
        }.launchIn(coroutineScope)
    }

    /**
     * Configura el procesamiento del audio remoto recibido
     */
    private fun setupRemoteAudioProcessing(audioTrack: AudioStreamTrack) {
        log.d(TAG) { "Configurando procesamiento de audio remoto" }

        coroutineScope.launch {
            try {
                // Aquí necesitarías acceder a los datos de audio del track
                // Esto depende de la implementación específica de WebRTC que uses
                // Por ahora, simulamos el procesamiento

                while (isReceivingRemoteAudio && audioTrack.enabled) {
                    // Simular obtención de datos de audio del track remoto
                    // En una implementación real, necesitarías:
                    // 1. Obtener los datos PCM del AudioStreamTrack
                    // 2. Pasarlos al VirtualAudioProcessor

                    delay(100) // Simular procesamiento cada 100ms

                    // Ejemplo de cómo sería:
                    // val audioData = getAudioDataFromTrack(audioTrack)
                    // virtualAudioProcessor.processRemoteAudio(audioData)
                }
            } catch (e: Exception) {
                log.e(TAG) { "Error procesando audio remoto: ${e.message}" }
            }
        }
    }

    private suspend fun ensureLocalAudioTrack(): Boolean {
        return try {
            val peerConn = peerConnection ?: run {
                log.w(TAG) { "PeerConnection no inicializada" }
                return false
            }

            if (localAudioTrack != null) {
                log.d(TAG) { "Track de audio local ya existe" }
                return true
            }

            log.d(TAG) { "Obteniendo stream de audio local..." }

            // Si el audio virtual está habilitado, usar el track virtual
            val audioTrack = if (isVirtualAudioEnabled) {
                virtualAudioProcessor.getVirtualAudioTrack()
            } else {
                // Usar MediaDevices normal
                val mediaStream = MediaDevices.getUserMedia(audio = true, video = false)
                mediaStream.audioTracks.firstOrNull()
            }

            if (audioTrack != null) {
                log.d(TAG) { "Track de audio obtenido exitosamente" }
                localAudioTrack = audioTrack
                localAudioTrack?.enabled = true

                // Crear un MediaStream para agregar el track
                val mediaStream = if (isVirtualAudioEnabled) {
                    virtualAudioProcessor.getVirtualMediaStream()
                } else {
                    MediaDevices.getUserMedia(audio = true, video = false)
                }

                mediaStream?.let { peerConn.addTrack(audioTrack, it) }
                log.d(TAG) { "Track de audio agregado exitosamente: ${audioTrack.label}" }
                true
            } else {
                log.e(TAG) { "Error: No se encontró track de audio" }
                false
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error obteniendo audio: ${e.message}" }
            false
        }
    }

    private fun cleanupCall() {
        try {
            localAudioTrack?.enabled = false

            peerConnection?.let { pc ->
                pc.getSenders().forEach { sender ->
                    try {
                        pc.removeTrack(sender)
                    } catch (e: Exception) {
                        log.w(TAG) { "Error removiendo track: ${e.message}" }
                    }
                }
            }

            peerConnection?.close()
            peerConnection = null

            Thread.sleep(100)

            localAudioTrack = null
            remoteAudioTrack = null
            isLocalAudioReady = false

            System.gc()

        } catch (e: Exception) {
            log.e(TAG) { "Error en cleanupCall: ${e.message}" }
        }
    }

    override fun dispose() {
        log.d(TAG) { "Disposing AndroidWebRtcManager..." }

        try {
            // Detener procesamiento de audio virtual
            if (isVirtualAudioEnabled) {
                virtualAudioProcessor.dispose()
            }

            // Limpiar recursos de WebRTC
            cleanupCall()

            // Limpiar gestores de audio
            refactoredAudioManager.dispose()

            // Reset estados
            isInitialized = false
            isLocalAudioReady = false
            isVirtualAudioEnabled = false
            isReceivingRemoteAudio = false

            log.d(TAG) { "AndroidWebRtcManager disposed exitosamente" }

        } catch (e: Exception) {
            log.e(TAG) { "Error disposing AndroidWebRtcManager: ${e.message}" }
        }
    }
}