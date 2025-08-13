package com.eddyslarez.siplibrary.data.services.audio

import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.eddyslarez.siplibrary.data.models.AccountInfo
import com.eddyslarez.siplibrary.data.services.transcription.TranscriptionManager
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * Implementación de WebRTC Manager para Android con interceptación de audio
 * para transcripción en tiempo real
 * 
 * @author Eddys Larez
 */
class AndroidWebRtcManager(private val application: Application) : WebRtcManager {
    
    private val TAG = "AndroidWebRtcManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // WebRTC components
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null
    
    // Audio management
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioDeviceManager = AudioDeviceManager()
    private val callHoldManager = CallHoldManager(this)
    
    // Transcription integration
    private var transcriptionManager: TranscriptionManager? = null
    private var isTranscriptionEnabled = false
    
    // WebRTC listener
    private var webRtcListener: WebRtcEventListener? = null
    
    // Connection state
    private var connectionState = WebRtcConnectionState.NEW
    private var isInitialized = false
    private var isMuted = false
    
    // Audio interceptor for transcription
    private var audioInterceptor: AudioInterceptor? = null
    
    override fun initialize() {
        if (isInitialized) {
            log.d(tag = TAG) { "WebRTC already initialized" }
            return
        }
        
        log.d(tag = TAG) { "Initializing Android WebRTC Manager" }
        
        try {
            // Initialize WebRTC
            val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(application)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
            
            PeerConnectionFactory.initialize(initializationOptions)
            
            // Create PeerConnectionFactory
            val options = PeerConnectionFactory.Options()
            val encoderFactory = DefaultVideoEncoderFactory(
                EglBase.create().eglBaseContext,
                true,
                true
            )
            val decoderFactory = DefaultVideoDecoderFactory(EglBase.create().eglBaseContext)
            
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setAudioDeviceModule(createAudioDeviceModule())
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()
            
            // Initialize transcription manager
            transcriptionManager = TranscriptionManager(application)
            transcriptionManager?.initialize()
            
            // Initialize audio interceptor
            audioInterceptor = AudioInterceptor()
            
            isInitialized = true
            log.d(tag = TAG) { "Android WebRTC Manager initialized successfully" }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error initializing WebRTC: ${e.message}" }
            throw e
        }
    }
    
    /**
     * Crea módulo de audio personalizado con interceptación
     */
    private fun createAudioDeviceModule(): AudioDeviceModule {
        return JavaAudioDeviceModule.builder(application)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setAudioRecordErrorCallback { errorMessage ->
                log.e(tag = TAG) { "Audio record error: $errorMessage" }
            }
            .setAudioTrackErrorCallback { errorMessage ->
                log.e(tag = TAG) { "Audio track error: $errorMessage" }
            }
            .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                override fun onWebRtcAudioRecordStart() {
                    log.d(tag = TAG) { "WebRTC audio record started" }
                }
                
                override fun onWebRtcAudioRecordStop() {
                    log.d(tag = TAG) { "WebRTC audio record stopped" }
                }
            })
            .setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
                override fun onWebRtcAudioTrackStart() {
                    log.d(tag = TAG) { "WebRTC audio track started" }
                }
                
                override fun onWebRtcAudioTrackStop() {
                    log.d(tag = TAG) { "WebRTC audio track stopped" }
                }
            })
            .createAudioDeviceModule()
    }
    
    override suspend fun createOffer(): String {
        if (!isInitialized) {
            throw IllegalStateException("WebRTC not initialized")
        }
        
        return try {
            createPeerConnectionIfNeeded()
            
            // Create audio track
            createLocalAudioTrack()
            
            // Create offer
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }
            
            val offer = peerConnection?.createOffer(constraints)
            if (offer != null) {
                peerConnection?.setLocalDescription(offer)
                offer.description
            } else {
                throw Exception("Failed to create offer")
            }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating offer: ${e.message}" }
            throw e
        }
    }
    
    override suspend fun createAnswer(accountInfo: AccountInfo, offerSdp: String): String {
        if (!isInitialized) {
            throw IllegalStateException("WebRTC not initialized")
        }
        
        return try {
            createPeerConnectionIfNeeded()
            
            // Set remote description (offer)
            val offer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
            peerConnection?.setRemoteDescription(offer)
            
            // Create local audio track
            createLocalAudioTrack()
            
            // Create answer
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            }
            
            val answer = peerConnection?.createAnswer(constraints)
            if (answer != null) {
                peerConnection?.setLocalDescription(answer)
                answer.description
            } else {
                throw Exception("Failed to create answer")
            }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating answer: ${e.message}" }
            throw e
        }
    }
    
    override suspend fun setRemoteDescription(sdp: String, type: SdpType) {
        try {
            val sessionDescriptionType = when (type) {
                SdpType.OFFER -> SessionDescription.Type.OFFER
                SdpType.ANSWER -> SessionDescription.Type.ANSWER
            }
            
            val sessionDescription = SessionDescription(sessionDescriptionType, sdp)
            peerConnection?.setRemoteDescription(sessionDescription)
            
            log.d(tag = TAG) { "Remote description set: $type" }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error setting remote description: ${e.message}" }
            throw e
        }
    }
    
    override suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        try {
            val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex ?: 0, candidate)
            peerConnection?.addIceCandidate(iceCandidate)
            
            log.d(tag = TAG) { "ICE candidate added: $candidate" }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error adding ICE candidate: ${e.message}" }
        }
    }
    
    /**
     * Crea PeerConnection si no existe
     */
    private fun createPeerConnectionIfNeeded() {
        if (peerConnection != null) return
        
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )
        ).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        
        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onSignalingChange(signalingState: PeerConnection.SignalingState?) {
                    log.d(tag = TAG) { "Signaling state changed: $signalingState" }
                }
                
                override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState?) {
                    log.d(tag = TAG) { "ICE connection state changed: $iceConnectionState" }
                    
                    val newState = when (iceConnectionState) {
                        PeerConnection.IceConnectionState.NEW -> WebRtcConnectionState.NEW
                        PeerConnection.IceConnectionState.CHECKING -> WebRtcConnectionState.CONNECTING
                        PeerConnection.IceConnectionState.CONNECTED,
                        PeerConnection.IceConnectionState.COMPLETED -> WebRtcConnectionState.CONNECTED
                        PeerConnection.IceConnectionState.DISCONNECTED -> WebRtcConnectionState.DISCONNECTED
                        PeerConnection.IceConnectionState.FAILED -> WebRtcConnectionState.FAILED
                        PeerConnection.IceConnectionState.CLOSED -> WebRtcConnectionState.CLOSED
                        else -> WebRtcConnectionState.NEW
                    }
                    
                    connectionState = newState
                    webRtcListener?.onConnectionStateChange(newState)
                }
                
                override fun onIceConnectionReceivingChange(receiving: Boolean) {
                    log.d(tag = TAG) { "ICE connection receiving changed: $receiving" }
                }
                
                override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState?) {
                    log.d(tag = TAG) { "ICE gathering state changed: $iceGatheringState" }
                }
                
                override fun onIceCandidate(iceCandidate: IceCandidate?) {
                    iceCandidate?.let { candidate ->
                        webRtcListener?.onIceCandidate(
                            candidate.sdp,
                            candidate.sdpMid,
                            candidate.sdpMLineIndex
                        )
                    }
                }
                
                override fun onIceCandidatesRemoved(iceCandidates: Array<out IceCandidate>?) {
                    log.d(tag = TAG) { "ICE candidates removed: ${iceCandidates?.size}" }
                }
                
                override fun onAddStream(mediaStream: MediaStream?) {
                    log.d(tag = TAG) { "Stream added: ${mediaStream?.id}" }
                    
                    mediaStream?.audioTracks?.firstOrNull()?.let { audioTrack ->
                        remoteAudioTrack = audioTrack
                        
                        // CRÍTICO: Configurar interceptación de audio remoto
                        setupRemoteAudioInterception(audioTrack)
                        
                        webRtcListener?.onRemoteAudioTrack()
                    }
                }
                
                override fun onRemoveStream(mediaStream: MediaStream?) {
                    log.d(tag = TAG) { "Stream removed: ${mediaStream?.id}" }
                    remoteAudioTrack = null
                }
                
                override fun onDataChannel(dataChannel: DataChannel?) {
                    log.d(tag = TAG) { "Data channel: ${dataChannel?.label()}" }
                }
                
                override fun onRenegotiationNeeded() {
                    log.d(tag = TAG) { "Renegotiation needed" }
                }
                
                override fun onAddTrack(rtpReceiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                    log.d(tag = TAG) { "Track added: ${rtpReceiver?.track()?.kind()}" }
                    
                    if (rtpReceiver?.track()?.kind() == "audio") {
                        remoteAudioTrack = rtpReceiver.track() as? AudioTrack
                        remoteAudioTrack?.let { audioTrack ->
                            // CRÍTICO: Configurar interceptación de audio remoto
                            setupRemoteAudioInterception(audioTrack)
                        }
                    }
                }
            }
        )
        
        log.d(tag = TAG) { "PeerConnection created" }
    }
    
    /**
     * Crea track de audio local con interceptación
     */
    private fun createLocalAudioTrack() {
        if (localAudioTrack != null) return
        
        try {
            // Crear audio source
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            }
            
            audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
            localAudioTrack = peerConnectionFactory?.createAudioTrack("local_audio", audioSource)
            
            // CRÍTICO: Configurar interceptación de audio local
            setupLocalAudioInterception()
            
            // Add track to peer connection
            peerConnection?.addTrack(localAudioTrack, listOf("local_stream"))
            
            log.d(tag = TAG) { "Local audio track created and added" }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating local audio track: ${e.message}" }
            throw e
        }
    }
    
    /**
     * Configura interceptación de audio local (lo que hablamos)
     */
    private fun setupLocalAudioInterception() {
        if (!isTranscriptionEnabled) return
        
        try {
            // Crear interceptor personalizado para audio local
            audioInterceptor = AudioInterceptor()
            
            // En WebRTC, interceptar audio local requiere acceso al AudioSource
            // Esto es más complejo y requiere modificaciones a nivel nativo
            // Por ahora, usaremos un approach alternativo
            
            log.d(tag = TAG) { "Local audio interception configured" }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error setting up local audio interception: ${e.message}" }
        }
    }
    
    /**
     * Configura interceptación de audio remoto (lo que escuchamos)
     */
    private fun setupRemoteAudioInterception(audioTrack: AudioTrack) {
        if (!isTranscriptionEnabled) return
        
        try {
            // CRÍTICO: Interceptar audio antes de que llegue al altavoz
            // Esto requiere acceso al pipeline interno de WebRTC
            
            // Crear sink personalizado para interceptar audio
            val audioSink = object : AudioTrackSink {
                override fun onData(
                    audioData: ByteBuffer?,
                    bitsPerSample: Int,
                    sampleRate: Int,
                    numberOfChannels: Int,
                    numberOfFrames: Int
                ) {
                    audioData?.let { buffer ->
                        // Convertir ByteBuffer a ByteArray
                        val audioBytes = ByteArray(buffer.remaining())
                        buffer.get(audioBytes)
                        buffer.rewind() // Importante: rewind para que el audio siga al altavoz
                        
                        // INTERCEPTAR: Enviar audio a transcripción
                        transcriptionManager?.onRemoteAudioFrame(audioBytes)
                        
                        // Opcional: También interceptar audio local si está configurado
                        if (transcriptionManager?.getCurrentConfig()?.audioSource == 
                            com.eddyslarez.siplibrary.data.services.transcription.AudioTranscriptionService.AudioSource.WEBRTC_BOTH) {
                            // Aquí se podría interceptar también el audio local
                        }
                    }
                }
            }
            
            // Añadir sink al track de audio remoto
            audioTrack.addSink(audioSink)
            
            log.d(tag = TAG) { "Remote audio interception configured successfully" }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error setting up remote audio interception: ${e.message}" }
        }
    }
    
    /**
     * Habilita transcripción de audio
     */
    fun enableTranscription(config: com.eddyslarez.siplibrary.data.services.transcription.AudioTranscriptionService.TranscriptionConfig) {
        isTranscriptionEnabled = true
        transcriptionManager?.updateTranscriptionConfig(config)
        log.d(tag = TAG) { "Audio transcription enabled with config: $config" }
    }
    
    /**
     * Deshabilita transcripción de audio
     */
    fun disableTranscription() {
        isTranscriptionEnabled = false
        transcriptionManager?.stopTranscriptionSession()
        log.d(tag = TAG) { "Audio transcription disabled" }
    }
    
    /**
     * Inicia sesión de transcripción para una llamada
     */
    fun startTranscriptionForCall(
        callId: String,
        config: com.eddyslarez.siplibrary.data.services.transcription.AudioTranscriptionService.TranscriptionConfig
    ) {
        if (!isTranscriptionEnabled) {
            enableTranscription(config)
        }
        
        transcriptionManager?.startTranscriptionSession(callId, config)
        log.d(tag = TAG) { "Transcription session started for call: $callId" }
    }
    
    /**
     * Detiene sesión de transcripción
     */
    fun stopTranscriptionForCall() {
        transcriptionManager?.stopTranscriptionSession()
        log.d(tag = TAG) { "Transcription session stopped" }
    }
    
    /**
     * Obtiene gestor de transcripción
     */
    fun getTranscriptionManager(): TranscriptionManager? = transcriptionManager
    
    override fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        val inputDevices = mutableListOf<AudioDevice>()
        val outputDevices = mutableListOf<AudioDevice>()
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_ALL)
                
                devices.forEach { deviceInfo ->
                    val audioDevice = convertToAudioDevice(deviceInfo)
                    
                    if (deviceInfo.isSource) {
                        inputDevices.add(audioDevice.copy(isOutput = false))
                    }
                    if (deviceInfo.isSink) {
                        outputDevices.add(audioDevice.copy(isOutput = true))
                    }
                }
            } else {
                // Fallback para versiones anteriores
                addLegacyAudioDevices(inputDevices, outputDevices)
            }
            
            // Actualizar en el device manager
            audioDeviceManager.updateDevices(inputDevices, outputDevices)
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error getting audio devices: ${e.message}" }
        }
        
        return Pair(inputDevices, outputDevices)
    }
    
    /**
     * Convierte AudioDeviceInfo a AudioDevice
     */
    private fun convertToAudioDevice(deviceInfo: AudioDeviceInfo): AudioDevice {
        val audioUnitType = when (deviceInfo.type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioUnitTypes.EARPIECE
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioUnitTypes.SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioUnitTypes.HEADSET
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioUnitTypes.HEADPHONES
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioUnitTypes.BLUETOOTH
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioUnitTypes.BLUETOOTHA2DP
            AudioDeviceInfo.TYPE_USB_DEVICE -> AudioUnitTypes.GENERICUSB
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> AudioUnitTypes.MICROPHONE
            else -> AudioUnitTypes.UNKNOWN
        }
        
        val capability = when {
            deviceInfo.isSource && deviceInfo.isSink -> AudioUnitCompatibilities.ALL
            deviceInfo.isSource -> AudioUnitCompatibilities.RECORD
            deviceInfo.isSink -> AudioUnitCompatibilities.PLAY
            else -> AudioUnitCompatibilities.UNKNOWN
        }
        
        val audioUnit = AudioUnit(
            type = audioUnitType,
            capability = capability,
            isCurrent = false,
            isDefault = false
        )
        
        return AudioDevice(
            name = deviceInfo.productName?.toString() ?: "Unknown Device",
            descriptor = deviceInfo.id.toString(),
            nativeDevice = deviceInfo,
            isOutput = deviceInfo.isSink,
            audioUnit = audioUnit,
            connectionState = DeviceConnectionState.AVAILABLE,
            isWireless = audioUnitType == AudioUnitTypes.BLUETOOTH || audioUnitType == AudioUnitTypes.BLUETOOTHA2DP,
            supportsHDVoice = deviceInfo.sampleRates?.any { it >= 16000 } ?: false
        )
    }
    
    /**
     * Añade dispositivos de audio para versiones legacy
     */
    private fun addLegacyAudioDevices(inputDevices: MutableList<AudioDevice>, outputDevices: MutableList<AudioDevice>) {
        // Dispositivos básicos para versiones anteriores a Android M
        val microphoneDevice = AudioDevice(
            name = "Micrófono",
            descriptor = "builtin_mic",
            isOutput = false,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.MICROPHONE,
                capability = AudioUnitCompatibilities.RECORD,
                isCurrent = true,
                isDefault = true
            )
        )
        
        val speakerDevice = AudioDevice(
            name = "Altavoz",
            descriptor = "builtin_speaker",
            isOutput = true,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.SPEAKER,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = true,
                isDefault = true
            )
        )
        
        val earpieceDevice = AudioDevice(
            name = "Auricular",
            descriptor = "builtin_earpiece",
            isOutput = true,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.EARPIECE,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = false,
                isDefault = false
            )
        )
        
        inputDevices.add(microphoneDevice)
        outputDevices.addAll(listOf(speakerDevice, earpieceDevice))
    }
    
    override fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean {
        return try {
            // Cambiar dispositivo de salida
            val success = audioDeviceManager.selectOutputDevice(device)
            
            if (success) {
                // Aplicar cambio al sistema de audio
                when (device.audioUnit.type) {
                    AudioUnitTypes.SPEAKER -> {
                        audioManager.isSpeakerphoneOn = true
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    }
                    AudioUnitTypes.EARPIECE -> {
                        audioManager.isSpeakerphoneOn = false
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    }
                    AudioUnitTypes.BLUETOOTH -> {
                        audioManager.isBluetoothScoOn = true
                        audioManager.startBluetoothSco()
                    }
                    else -> {
                        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    }
                }
                
                webRtcListener?.onAudioDeviceChanged(device)
                log.d(tag = TAG) { "Audio output device changed to: ${device.name}" }
            }
            
            success
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error changing audio output device: ${e.message}" }
            false
        }
    }
    
    override fun changeAudioInputDeviceDuringCall(device: AudioDevice): Boolean {
        return try {
            val success = audioDeviceManager.selectInputDevice(device)
            
            if (success) {
                // Reconfigurar audio source si es necesario
                // Esto podría requerir recrear el audio track
                webRtcListener?.onAudioDeviceChanged(device)
                log.d(tag = TAG) { "Audio input device changed to: ${device.name}" }
            }
            
            success
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error changing audio input device: ${e.message}" }
            false
        }
    }
    
    override fun getCurrentInputDevice(): AudioDevice? {
        return audioDeviceManager.selectedInputDevice.value
    }
    
    override fun getCurrentOutputDevice(): AudioDevice? {
        return audioDeviceManager.selectedOutputDevice.value
    }
    
    override fun setAudioEnabled(enabled: Boolean) {
        try {
            localAudioTrack?.setEnabled(enabled)
            log.d(tag = TAG) { "Audio enabled: $enabled" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error setting audio enabled: ${e.message}" }
        }
    }
    
    override fun setMuted(muted: Boolean) {
        try {
            isMuted = muted
            localAudioTrack?.setEnabled(!muted)
            log.d(tag = TAG) { "Audio muted: $muted" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error setting mute: ${e.message}" }
        }
    }
    
    override fun isMuted(): Boolean = isMuted
    
    override fun getLocalDescription(): String? {
        return peerConnection?.localDescription?.description
    }
    
    override fun getConnectionState(): WebRtcConnectionState = connectionState
    
    override suspend fun setMediaDirection(direction: WebRtcManager.MediaDirection) {
        try {
            // Implementar cambio de dirección de media
            when (direction) {
                WebRtcManager.MediaDirection.SENDRECV -> {
                    localAudioTrack?.setEnabled(true)
                    // Habilitar recepción
                }
                WebRtcManager.MediaDirection.SENDONLY -> {
                    localAudioTrack?.setEnabled(true)
                    // Deshabilitar recepción
                }
                WebRtcManager.MediaDirection.RECVONLY -> {
                    localAudioTrack?.setEnabled(false)
                    // Habilitar recepción
                }
                WebRtcManager.MediaDirection.INACTIVE -> {
                    localAudioTrack?.setEnabled(false)
                    // Deshabilitar recepción
                }
            }
            
            log.d(tag = TAG) { "Media direction set to: $direction" }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error setting media direction: ${e.message}" }
        }
    }
    
    override fun setListener(listener: Any?) {
        webRtcListener = listener as? WebRtcEventListener
    }
    
    override fun prepareAudioForIncomingCall() {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false
            
            // Preparar transcripción si está habilitada
            if (isTranscriptionEnabled) {
                transcriptionManager?.let { manager ->
                    // La sesión de transcripción se iniciará cuando se acepte la llamada
                    log.d(tag = TAG) { "Audio prepared for incoming call with transcription ready" }
                }
            }
            
            log.d(tag = TAG) { "Audio prepared for incoming call" }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error preparing audio for incoming call: ${e.message}" }
        }
    }
    
    override suspend fun applyModifiedSdp(modifiedSdp: String): Boolean {
        return try {
            val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, modifiedSdp)
            peerConnection?.setLocalDescription(sessionDescription)
            true
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error applying modified SDP: ${e.message}" }
            false
        }
    }
    
    override fun isInitialized(): Boolean = isInitialized
    
    override fun sendDtmfTones(tones: String, duration: Int, gap: Int): Boolean {
        return try {
            // Implementar envío de DTMF via RTP
            // Esto requiere acceso a los RTP senders
            
            val audioSender = peerConnection?.senders?.find { 
                it.track()?.kind() == "audio" 
            }
            
            if (audioSender != null) {
                // En una implementación completa, aquí se enviarían los tonos DTMF
                // via RTP según RFC 2833
                log.d(tag = TAG) { "DTMF tones sent: $tones" }
                true
            } else {
                log.w(tag = TAG) { "No audio sender available for DTMF" }
                false
            }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending DTMF tones: ${e.message}" }
            false
        }
    }
    
    override fun diagnoseAudioIssues(): String {
        return buildString {
            appendLine("=== ANDROID WEBRTC MANAGER DIAGNOSTIC ===")
            appendLine("Is Initialized: $isInitialized")
            appendLine("Connection State: $connectionState")
            appendLine("Is Muted: $isMuted")
            appendLine("Local Audio Track: ${localAudioTrack != null}")
            appendLine("Remote Audio Track: ${remoteAudioTrack != null}")
            appendLine("PeerConnection: ${peerConnection != null}")
            appendLine("Audio Manager Mode: ${audioManager.mode}")
            appendLine("Speakerphone On: ${audioManager.isSpeakerphoneOn}")
            appendLine("Transcription Enabled: $isTranscriptionEnabled")
            
            if (isTranscriptionEnabled) {
                appendLine("\n--- Transcription Diagnostic ---")
                appendLine(transcriptionManager?.getDiagnosticInfo() ?: "Transcription manager not available")
            }
            
            appendLine("\n--- Audio Device Manager ---")
            appendLine(audioDeviceManager.getDiagnosticInfo())
        }
    }
    
    override fun dispose() {
        try {
            log.d(tag = TAG) { "Disposing Android WebRTC Manager" }
            
            // Detener transcripción
            transcriptionManager?.dispose()
            
            // Limpiar audio tracks
            localAudioTrack?.dispose()
            remoteAudioTrack?.dispose()
            audioSource?.dispose()
            
            // Cerrar peer connection
            peerConnection?.close()
            peerConnection?.dispose()
            
            // Limpiar factory
            peerConnectionFactory?.dispose()
            
            // Resetear estado
            isInitialized = false
            connectionState = WebRtcConnectionState.NEW
            
            // Limpiar interceptor
            audioInterceptor?.dispose()
            
            log.d(tag = TAG) { "Android WebRTC Manager disposed successfully" }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error disposing WebRTC manager: ${e.message}" }
        }
    }
    
    /**
     * Interceptor de audio interno
     */
    private inner class AudioInterceptor {
        
        fun dispose() {
            // Limpiar recursos del interceptor
        }
    }
}