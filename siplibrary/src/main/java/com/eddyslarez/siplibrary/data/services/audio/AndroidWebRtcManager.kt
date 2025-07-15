package com.eddyslarez.siplibrary.data.services.audio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.eddyslarez.siplibrary.data.models.AccountInfo
import com.eddyslarez.siplibrary.data.services.translation.TranslationAudioProcessor
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DtmfSender
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resumeWithException


/**
 * Implementación Android de WebRTC Manager con soporte para traducción
 *
 * @author Eddys Larez
 */
class AndroidWebRtcManager(
    private val application: Application
) : WebRtcManager {
    private val activeCandidates = mutableSetOf<IceCandidate>()

    companion object {
        private const val TAG = "AndroidWebRtcManager"
        private const val AUDIO_CODEC_OPUS = "opus"
        private const val AUDIO_SAMPLE_RATE = 48000
        private const val AUDIO_CHANNELS = 1
    }

    // WebRTC components
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null
    private var listener: WebRtcEventListener? = null

    // Audio management
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var currentInputDevice: AudioDevice? = null
    private var currentOutputDevice: AudioDevice? = null
    private val availableDevices = ConcurrentHashMap<String, AudioDevice>()

    // Audio processing for translation
    private var audioProcessor: TranslationAudioProcessor? = null
    private var isTranslationEnabled = false
    private var translationCallback: ((ByteArray) -> Unit)? = null

    // State management
    private var isInitialized = false
    private var isMuted = false
    private var connectionState = WebRtcConnectionState.NEW

    // DTMF support
    private var dtmfSender: DtmfSender? = null

    // Coroutine scope for async operations
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @SuppressLint("MissingPermission")
    override fun initialize() {
        if (isInitialized) {
            log.w(tag = TAG) { "WebRTC already initialized" }
            return
        }

        try {
            log.d(tag = TAG) { "Initializing WebRTC" }

            // Initialize WebRTC
            val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(application)
                .setEnableInternalTracer(false)
                .createInitializationOptions()

            PeerConnectionFactory.initialize(initializationOptions)

            // Create PeerConnectionFactory
            val options = PeerConnectionFactory.Options()
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .createPeerConnectionFactory()

            // Initialize audio devices
            refreshAudioDevices()

            // Initialize audio processor for translation
            audioProcessor = TranslationAudioProcessor()
            audioProcessor?.initialize()

            isInitialized = true
            log.d(tag = TAG) { "WebRTC initialized successfully" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error initializing WebRTC: ${e.message}" }
            throw e
        }
    }

    override fun dispose() {
        log.d(tag = TAG) { "Disposing WebRTC" }

        try {
            // Stop translation audio processing
            audioProcessor?.dispose()
            audioProcessor = null

            // Close peer connection
            peerConnection?.close()
            peerConnection = null

            // Dispose audio tracks
            localAudioTrack?.dispose()
            localAudioTrack = null

            remoteAudioTrack?.dispose()
            remoteAudioTrack = null

            audioSource?.dispose()
            audioSource = null

            // Dispose factory
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null

            // Cancel coroutines
            coroutineScope.cancel()

            isInitialized = false
            log.d(tag = TAG) { "WebRTC disposed successfully" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error disposing WebRTC: ${e.message}" }
        }
    }

    override suspend fun createOffer(): String = withContext(Dispatchers.IO) {
        checkInitialized()

        try {
            log.d(tag = TAG) { "Creating offer" }

            // Create peer connection if not exists
            if (peerConnection == null) {
                createPeerConnection()
            }

            // Add local audio track
            addLocalAudioTrack()

            // Configurar constraints más específicos para SIP
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                // Configuraciones adicionales para compatibilidad SIP
                mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("VoiceActivityDetection", "true"))
                // Opcional: Deshabilitar BUNDLE si causa problemas
                optional.add(MediaConstraints.KeyValuePair("googUseRtpMUX", "true"))
            }

            val offer = suspendCancellableCoroutine<SessionDescription> { continuation ->
                peerConnection?.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(sessionDescription: SessionDescription) {
                        continuation.resume(sessionDescription, null)
                    }

                    override fun onCreateFailure(error: String) {
                        continuation.resumeWithException(Exception("Failed to create offer: $error"))
                    }

                    override fun onSetSuccess() {}
                    override fun onSetFailure(error: String) {}
                }, constraints)
            }

            // Set local description
            suspendCancellableCoroutine<Unit> { continuation ->
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        continuation.resume(Unit, null)
                    }

                    override fun onSetFailure(error: String) {
                        continuation.resumeWithException(Exception("Failed to set local description: $error"))
                    }

                    override fun onCreateSuccess(sessionDescription: SessionDescription) {}
                    override fun onCreateFailure(error: String) {}
                }, offer)
            }

            log.d(tag = TAG) { "Offer created successfully" }

            // Procesar SDP para compatibilidad SIP si es necesario
            val processedSdp = processSdpForSipCompatibility(offer.description)
            return@withContext processedSdp

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating offer: ${e.message}" }
            throw e
        }
    }

    override suspend fun createAnswer(accountInfo: AccountInfo, offerSdp: String): String = withContext(Dispatchers.IO) {
        checkInitialized()

        try {
            log.d(tag = TAG) { "Creating answer for offer" }

            // Debug del SDP entrante
            debugSdp(offerSdp, "INCOMING_OFFER")

            // Create peer connection if not exists
            if (peerConnection == null) {
                createPeerConnection()
            }

            // Validar y procesar SDP entrante
            if (offerSdp.isBlank()) {
                throw IllegalArgumentException("Offer SDP cannot be blank")
            }

            val processedOfferSdp = processSdpForSipCompatibility(offerSdp)
            debugSdp(processedOfferSdp, "PROCESSED_OFFER")

            // Set remote description con manejo de errores mejorado
            try {
                setRemoteDescription(processedOfferSdp, SdpType.OFFER)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Failed to set remote description, trying with original SDP" }
                setRemoteDescription(offerSdp, SdpType.OFFER)
            }

            // Add local audio track
            addLocalAudioTrack()

            // Crear answer con constraints
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("VoiceActivityDetection", "true"))
                optional.add(MediaConstraints.KeyValuePair("googUseRtpMUX", "true"))
            }

            val answer = suspendCancellableCoroutine<SessionDescription> { continuation ->
                peerConnection?.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(sessionDescription: SessionDescription) {
                        continuation.resume(sessionDescription, null)
                    }

                    override fun onCreateFailure(error: String) {
                        continuation.resumeWithException(Exception("Failed to create answer: $error"))
                    }

                    override fun onSetSuccess() {}
                    override fun onSetFailure(error: String) {}
                }, constraints)
            }

            // Set local description
            suspendCancellableCoroutine<Unit> { continuation ->
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        continuation.resume(Unit, null)
                    }

                    override fun onSetFailure(error: String) {
                        continuation.resumeWithException(Exception("Failed to set local description: $error"))
                    }

                    override fun onCreateSuccess(sessionDescription: SessionDescription) {}
                    override fun onCreateFailure(error: String) {}
                }, answer)
            }

            log.d(tag = TAG) { "Answer created successfully" }

            // Procesar y validar SDP de respuesta
            val processedAnswerSdp = processSdpForSipCompatibility(answer.description)
            debugSdp(processedAnswerSdp, "FINAL_ANSWER")

            return@withContext processedAnswerSdp

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating answer: ${e.message}" }
            throw e
        }
    }

    override suspend fun setRemoteDescription(sdp: String, type: SdpType) = withContext(Dispatchers.IO) {
        checkInitialized()

        try {
            log.d(tag = TAG) { "Setting remote description: $type" }

            // Validación previa del SDP
            if (sdp.isBlank()) {
                throw IllegalArgumentException("SDP cannot be blank")
            }

            // Procesar SDP para compatibilidad
            val processedSdp = processSdpForSipCompatibility(sdp)
            log.d(tag = TAG) { "Original SDP length: ${sdp.length}, Processed: ${processedSdp.length}" }

            // Validación adicional después del procesamiento
            if (processedSdp.isBlank()) {
                throw IllegalArgumentException("Processed SDP is blank")
            }

            // Crear SessionDescription con validación
            val sessionDescription = try {
                SessionDescription(
                    if (type == SdpType.OFFER) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER,
                    processedSdp
                )
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error creating SessionDescription: ${e.message}" }
                throw IllegalArgumentException("Failed to create SessionDescription: ${e.message}")
            }

            // Validar que SessionDescription no sea null
            if (sessionDescription.description.isNullOrBlank()) {
                throw IllegalStateException("SessionDescription created but description is null/blank")
            }

            // Intentar establecer la descripción remota
            suspendCancellableCoroutine<Unit> { continuation ->
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        log.d(tag = TAG) { "Remote description set successfully" }
                        continuation.resume(Unit, null)
                    }

                    override fun onSetFailure(error: String) {
                        val detailedError = "Failed to set remote description: $error"
                        log.e(tag = TAG) { detailedError }
                        log.e(tag = TAG) { "SDP that failed: ${processedSdp.take(200)}..." }
                        continuation.resumeWithException(Exception(detailedError))
                    }

                    override fun onCreateSuccess(sessionDescription: SessionDescription) {}
                    override fun onCreateFailure(error: String) {}
                }, sessionDescription)
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error setting remote description: ${e.message}" }
            throw e
        }
    }
    private fun debugSdp(sdp: String, label: String) {
        try {
            log.d(tag = TAG) { "=== SDP DEBUG: $label ===" }
            log.d(tag = TAG) { "Length: ${sdp.length}" }
            log.d(tag = TAG) { "Starts with: ${sdp.take(50)}" }
            log.d(tag = TAG) { "Ends with: ${sdp.takeLast(50)}" }

            val lines = sdp.split("\r\n")
            log.d(tag = TAG) { "Line count: ${lines.size}" }

            // Mostrar primeras líneas
            lines.take(10).forEachIndexed { index, line ->
                log.d(tag = TAG) { "Line $index: $line" }
            }

            // Verificar line endings
            val crlfCount = sdp.count { it == '\r' }
            val lfCount = sdp.count { it == '\n' }
            log.d(tag = TAG) { "CRLF count: $crlfCount, LF count: $lfCount" }

            // Verificar campos obligatorios
            val requiredFields = listOf("v=", "o=", "s=", "m=")
            requiredFields.forEach { field ->
                val hasField = sdp.contains(field)
                log.d(tag = TAG) { "Has $field: $hasField" }
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error debugging SDP: ${e.message}" }
        }
    }
    override suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        checkInitialized()

        try {
            val iceCandidate = IceCandidate(sdpMid ?: "", sdpMLineIndex ?: 0, candidate)
            peerConnection?.addIceCandidate(iceCandidate)
            log.d(tag = TAG) { "ICE candidate added" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error adding ICE candidate: ${e.message}" }
        }
    }

    /**
     * Procesa el SDP para mejorar la compatibilidad con servidores SIP
     */
    private fun processSdpForSipCompatibility(originalSdp: String): String {
        try {
            // Validación básica del SDP
            if (originalSdp.isBlank()) {
                log.e(tag = TAG) { "SDP is blank" }
                return originalSdp
            }

            // Verificar que tenga líneas básicas requeridas
            if (!originalSdp.contains("v=") || !originalSdp.contains("o=") || !originalSdp.contains("s=")) {
                log.e(tag = TAG) { "SDP missing required fields" }
                return originalSdp
            }

            var processedSdp = originalSdp

            // Normalizar line endings PRIMERO
            processedSdp = processedSdp.replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace("\n", "\r\n")

            // Validar que cada línea termine correctamente
            val lines = processedSdp.split("\r\n").toMutableList()

            // Remover líneas vacías
            lines.removeAll { it.isBlank() }

            // Validar formato de cada línea
            val validLines = lines.filter { line ->
                if (line.length < 2) return@filter false
                val prefix = line.substring(0, 2)
                prefix.matches(Regex("[a-z]=")) && line.contains("=")
            }

            if (validLines.size != lines.size) {
                log.w(tag = TAG) { "Removed ${lines.size - validLines.size} invalid SDP lines" }
            }

            // Reconstruir SDP con líneas válidas
            processedSdp = validLines.joinToString("\r\n")

            // Asegurar que termine con CRLF
            if (!processedSdp.endsWith("\r\n")) {
                processedSdp += "\r\n"
            }

            // Validar estructura básica después del procesamiento
            if (!validateSdpStructure(processedSdp)) {
                log.e(tag = TAG) { "Processed SDP has invalid structure" }
                return originalSdp
            }

            log.d(tag = TAG) { "SDP processing completed successfully" }
            return processedSdp

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error processing SDP: ${e.message}" }
            return originalSdp
        }
    }
    private fun validateSdpStructure(sdp: String): Boolean {
        try {
            val lines = sdp.split("\r\n")

            // Verificar líneas obligatorias en orden
            val requiredOrder = listOf("v=", "o=", "s=")
            var currentIndex = 0

            for (line in lines) {
                if (line.isBlank()) continue

                if (currentIndex < requiredOrder.size && line.startsWith(requiredOrder[currentIndex])) {
                    currentIndex++
                }
            }

            return currentIndex == requiredOrder.size

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error validating SDP structure: ${e.message}" }
            return false
        }
    }

    @SuppressLint("WrongConstant")
    override fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        val inputDevices = mutableListOf<AudioDevice>()
        val outputDevices = mutableListOf<AudioDevice>()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_ALL)

                devices.forEach { deviceInfo ->
                    val audioDevice = createAudioDeviceFromInfo(deviceInfo)

                    if (deviceInfo.isSink) {
                        outputDevices.add(audioDevice)
                    }
                    if (deviceInfo.isSource) {
                        inputDevices.add(audioDevice)
                    }
                }
            } else {
                // Fallback for older Android versions
                addLegacyAudioDevices(inputDevices, outputDevices)
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error getting audio devices: ${e.message}" }
        }

        return Pair(inputDevices, outputDevices)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun createAudioDeviceFromInfo(deviceInfo: AudioDeviceInfo): AudioDevice {
        val audioUnitType = when (deviceInfo.type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioUnitTypes.EARPIECE
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioUnitTypes.SPEAKER
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioUnitTypes.HEADSET
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioUnitTypes.HEADPHONES
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioUnitTypes.BLUETOOTH
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioUnitTypes.BLUETOOTHA2DP
            AudioDeviceInfo.TYPE_USB_DEVICE -> AudioUnitTypes.GENERICUSB
            else -> AudioUnitTypes.UNKNOWN
        }

        val capability = when {
            deviceInfo.isSink && deviceInfo.isSource -> AudioUnitCompatibilities.ALL
            deviceInfo.isSink -> AudioUnitCompatibilities.PLAY
            deviceInfo.isSource -> AudioUnitCompatibilities.RECORD
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
            supportsHDVoice = deviceInfo.sampleRates?.contains(AUDIO_SAMPLE_RATE) == true
        )
    }

    private fun addLegacyAudioDevices(inputDevices: MutableList<AudioDevice>, outputDevices: MutableList<AudioDevice>) {
        // Add basic devices for older Android versions
        val earpiece = AudioDevice(
            name = "Earpiece",
            descriptor = "earpiece",
            isOutput = true,
            audioUnit = AudioUnit(AudioUnitTypes.EARPIECE, AudioUnitCompatibilities.PLAY, false, true)
        )

        val microphone = AudioDevice(
            name = "Microphone",
            descriptor = "microphone",
            isOutput = false,
            audioUnit = AudioUnit(AudioUnitTypes.MICROPHONE, AudioUnitCompatibilities.RECORD, false, true)
        )

        val speaker = AudioDevice(
            name = "Speaker",
            descriptor = "speaker",
            isOutput = true,
            audioUnit = AudioUnit(AudioUnitTypes.SPEAKER, AudioUnitCompatibilities.PLAY, false, false)
        )

        inputDevices.add(microphone)
        outputDevices.add(earpiece)
        outputDevices.add(speaker)
    }

    override fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean {
        return try {
            log.d(tag = TAG) { "Changing audio output device to: ${device.name}" }

            when (device.audioUnit.type) {
                AudioUnitTypes.SPEAKER -> {
                    audioManager.isSpeakerphoneOn = true
                    audioManager.isBluetoothScoOn = false
                }
                AudioUnitTypes.EARPIECE -> {
                    audioManager.isSpeakerphoneOn = false
                    audioManager.isBluetoothScoOn = false
                }
                AudioUnitTypes.BLUETOOTH, AudioUnitTypes.BLUETOOTHA2DP -> {
                    audioManager.isBluetoothScoOn = true
                    audioManager.isSpeakerphoneOn = false
                }
                else -> {
                    log.w(tag = TAG) { "Unsupported audio device type: ${device.audioUnit.type}" }
                    return false
                }
            }

            currentOutputDevice = device
            listener?.onAudioDeviceChanged(device)
            true

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error changing audio output device: ${e.message}" }
            false
        }
    }

    override fun changeAudioInputDeviceDuringCall(device: AudioDevice): Boolean {
        return try {
            log.d(tag = TAG) { "Changing audio input device to: ${device.name}" }

            // For input devices, we mainly handle Bluetooth
            when (device.audioUnit.type) {
                AudioUnitTypes.BLUETOOTH -> {
                    audioManager.isBluetoothScoOn = true
                }
                AudioUnitTypes.MICROPHONE -> {
                    audioManager.isBluetoothScoOn = false
                }
                else -> {
                    log.w(tag = TAG) { "Input device change not fully supported: ${device.audioUnit.type}" }
                }
            }

            currentInputDevice = device
            listener?.onAudioDeviceChanged(device)
            true

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error changing audio input device: ${e.message}" }
            false
        }
    }

    override fun getCurrentInputDevice(): AudioDevice? = currentInputDevice

    override fun getCurrentOutputDevice(): AudioDevice? = currentOutputDevice

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
            log.d(tag = TAG) { "Muted: $muted" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error setting muted: ${e.message}" }
        }
    }

    override fun isMuted(): Boolean = isMuted

    override fun getLocalDescription(): String? {
        return peerConnection?.localDescription?.description
    }

    override fun diagnoseAudioIssues(): String {
        return buildString {
            appendLine("=== ANDROID WEBRTC AUDIO DIAGNOSTIC ===")
            appendLine("Initialized: $isInitialized")
            appendLine("Muted: $isMuted")
            appendLine("Connection State: $connectionState")
            appendLine("Local Audio Track: ${localAudioTrack != null}")
            appendLine("Remote Audio Track: ${remoteAudioTrack != null}")
            appendLine("Current Input Device: ${currentInputDevice?.name ?: "None"}")
            appendLine("Current Output Device: ${currentOutputDevice?.name ?: "None"}")
            appendLine("Translation Enabled: $isTranslationEnabled")
            appendLine("Audio Processor: ${audioProcessor != null}")

            appendLine("\n--- Audio Manager State ---")
            appendLine("Speaker On: ${audioManager.isSpeakerphoneOn}")
            appendLine("Bluetooth SCO On: ${audioManager.isBluetoothScoOn}")
            appendLine("Mode: ${audioManager.mode}")

            val (inputs, outputs) = getAllAudioDevices()
            appendLine("\n--- Available Devices ---")
            appendLine("Input Devices: ${inputs.size}")
            inputs.forEach { device ->
                appendLine("  - ${device.name} (${device.audioUnit.type})")
            }
            appendLine("Output Devices: ${outputs.size}")
            outputs.forEach { device ->
                appendLine("  - ${device.name} (${device.audioUnit.type})")
            }
        }
    }

    override fun getConnectionState(): WebRtcConnectionState = connectionState

    override suspend fun setMediaDirection(direction: WebRtcManager.MediaDirection) {
        // Implementation for media direction control
        log.d(tag = TAG) { "Setting media direction: $direction" }
    }

    override fun setListener(listener: Any?) {
        this.listener = listener as? WebRtcEventListener
        log.d(tag = TAG) { "WebRTC listener set: ${this.listener != null}" }
    }

    override fun prepareAudioForIncomingCall() {
        try {
            log.d(tag = TAG) { "Preparing audio for incoming call" }

            // Set audio mode for voice call
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            // Initialize audio devices if not done
            if (currentInputDevice == null || currentOutputDevice == null) {
                val (inputs, outputs) = getAllAudioDevices()

                currentInputDevice = inputs.find { it.audioUnit.isDefault } ?: inputs.firstOrNull()
                currentOutputDevice = outputs.find { it.audioUnit.isDefault } ?: outputs.firstOrNull()
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error preparing audio for incoming call: ${e.message}" }
        }
    }

    override suspend fun applyModifiedSdp(modifiedSdp: String): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            log.d(tag = TAG) { "Applying modified SDP" }

            // Procesar SDP modificado para compatibilidad
            val processedSdp = processSdpForSipCompatibility(modifiedSdp)

            val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, modifiedSdp)

            suspendCancellableCoroutine<Unit> { continuation ->
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        continuation.resume(Unit, null)
                    }

                    override fun onSetFailure(error: String) {
                        log.e(tag = TAG) { "Failed to apply modified SDP: $error" }
                        continuation.resumeWithException(Exception("Failed to apply modified SDP: $error"))
                    }

                    override fun onCreateSuccess(sessionDescription: SessionDescription) {}
                    override fun onCreateFailure(error: String) {}
                }, sessionDescription)
            }

            log.d(tag = TAG) { "Modified SDP applied successfully" }
            true

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error applying modified SDP: ${e.message}" }
            false
        }
    }

    override fun isInitialized(): Boolean = isInitialized

    override fun sendDtmfTones(tones: String, duration: Int, gap: Int): Boolean {
        return try {
            log.d(tag = TAG) { "Sending DTMF tones: $tones" }

            if (dtmfSender == null) {
                log.w(tag = TAG) { "DTMF sender not available" }
                return false
            }

            coroutineScope.launch {
                tones.forEach { tone ->
                    try {
                        dtmfSender?.insertDtmf(tone.toString(), duration, gap)
                        delay(duration + gap.toLong())
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error sending DTMF tone $tone: ${e.message}" }
                    }
                }
            }

            true

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending DTMF tones: ${e.message}" }
            false
        }
    }

    // === MÉTODOS PARA TRADUCCIÓN ===

    /**
     * Habilitar procesamiento de audio para traducción
     */
    fun enableTranslation(callback: (ByteArray) -> Unit) {
        try {
            log.d(tag = TAG) { "Enabling translation audio processing" }

            isTranslationEnabled = true
            translationCallback = callback

            // Configurar callback para audio capturado
            audioProcessor?.setAudioInputCallback { audioData ->
                callback(audioData)
            }

            // Iniciar captura de audio
            audioProcessor?.startAudioCapture()

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error enabling translation: ${e.message}" }
        }
    }

    /**
     * Deshabilitar procesamiento de audio para traducción
     */
    fun disableTranslation() {
        try {
            log.d(tag = TAG) { "Disabling translation audio processing" }

            isTranslationEnabled = false
            translationCallback = null

            // Detener captura de audio
            audioProcessor?.stopAudioCapture()
            audioProcessor?.stopAudioPlayback()

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error disabling translation: ${e.message}" }
        }
    }

    /**
     * Reproducir audio traducido
     */
    fun playTranslatedAudio(audioData: ByteArray) {
        try {
            audioProcessor?.playTranslatedAudio(audioData)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error playing translated audio: ${e.message}" }
        }
    }

    /**
     * Verificar si la traducción está habilitada
     */
    fun isTranslationEnabled(): Boolean = isTranslationEnabled

    // === MÉTODOS PRIVADOS ===

    private fun createPeerConnection() {
        try {
            log.d(tag = TAG) { "Creating peer connection" }

            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                bundlePolicy = PeerConnection.BundlePolicy.BALANCED
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                iceConnectionReceivingTimeout = 30000
                iceBackupCandidatePairPingInterval = 25000
                keyType = PeerConnection.KeyType.ECDSA
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }

            peerConnection = peerConnectionFactory?.createPeerConnection(
                rtcConfig,
                object : PeerConnection.Observer {
                    override fun onIceCandidate(iceCandidate: IceCandidate) {
                        try {
                            activeCandidates.add(iceCandidate)
                            listener?.onIceCandidate(
                                iceCandidate.sdp,
                                iceCandidate.sdpMid,
                                iceCandidate.sdpMLineIndex
                            )
                            log.d(tag = TAG) { "New ICE candidate: ${iceCandidate.sdp.take(50)}..." }
                        } catch (e: Exception) {
                            log.e(tag = TAG) { "Error handling ICE candidate: ${e.message}" }
                        }
                    }

                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate?>?) {
                        try {
                            val validCandidates = candidates?.filterNotNull() ?: emptyList()
                            if (validCandidates.isNotEmpty()) {
                                activeCandidates.removeAll(validCandidates.toSet())
                                listener?.onIceCandidatesRemoved(validCandidates)
                            }
                        } catch (e: Exception) {
                            log.e(tag = TAG) { "Error handling removed ICE candidates: ${e.message}" }
                            // NO llamar a listener?.onError aquí para evitar crashes
                        }
                    }

                    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                        try {
                            connectionState = when (newState) {
                                PeerConnection.PeerConnectionState.NEW -> WebRtcConnectionState.NEW
                                PeerConnection.PeerConnectionState.CONNECTING -> WebRtcConnectionState.CONNECTING
                                PeerConnection.PeerConnectionState.CONNECTED -> WebRtcConnectionState.CONNECTED
                                PeerConnection.PeerConnectionState.DISCONNECTED -> WebRtcConnectionState.DISCONNECTED
                                PeerConnection.PeerConnectionState.FAILED -> WebRtcConnectionState.FAILED
                                PeerConnection.PeerConnectionState.CLOSED -> WebRtcConnectionState.CLOSED
                            }
                            listener?.onConnectionStateChange(connectionState)
                        } catch (e: Exception) {
                            log.e(tag = TAG) { "Error handling connection state change: ${e.message}" }
                        }
                    }

                    override fun onAddTrack(rtpReceiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                        try {
                            val track = rtpReceiver.track()
                            if (track is AudioTrack) {
                                remoteAudioTrack = track
                                listener?.onRemoteAudioTrack()
                            }
                        } catch (e: Exception) {
                            log.e(tag = TAG) { "Error handling added track: ${e.message}" }
                        }
                    }

                    // Implementar otros métodos con manejo de errores...
                    override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {}
                    override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState) {}
                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                    override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState) {}
                    override fun onAddStream(mediaStream: MediaStream) {}
                    override fun onRemoveStream(mediaStream: MediaStream) {}
                    override fun onDataChannel(dataChannel: DataChannel) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onRemoveTrack(rtpReceiver: RtpReceiver) {}
                }
            )

            log.d(tag = TAG) { "Peer connection created successfully" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating peer connection: ${e.message}" }
            throw e
        }
    }

    private fun addLocalAudioTrack() {
        try {
            if (localAudioTrack != null) {
                log.d(tag = TAG) { "Local audio track already exists" }
                return
            }

            log.d(tag = TAG) { "Adding local audio track" }

            // Configurar constraints de audio más específicos para SIP
            val audioConstraints = MediaConstraints().apply {
                // Configuraciones básicas
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))

                // Configuraciones adicionales para mejor calidad de voz
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAudioMirroring", "false"))

                // Configuraciones opcionales
                optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression2", "true"))
                optional.add(MediaConstraints.KeyValuePair("googEchoCancellation2", "true"))
            }

            audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
            localAudioTrack = peerConnectionFactory?.createAudioTrack("audio_track", audioSource)

            // Add track to peer connection
            val sender = peerConnection?.addTrack(localAudioTrack, listOf("stream"))

            // Setup DTMF sender
            dtmfSender = sender?.dtmf()

            log.d(tag = TAG) { "Local audio track added successfully" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error adding local audio track: ${e.message}" }
            throw e
        }
    }

    private fun refreshAudioDevices() {
        try {
            val (inputs, outputs) = getAllAudioDevices()

            // Update available devices map
            availableDevices.clear()
            (inputs + outputs).forEach { device ->
                availableDevices[device.descriptor] = device
            }

            // Set default devices if not set
            if (currentInputDevice == null) {
                currentInputDevice = inputs.find { it.audioUnit.isDefault } ?: inputs.firstOrNull()
            }

            if (currentOutputDevice == null) {
                currentOutputDevice = outputs.find { it.audioUnit.isDefault } ?: outputs.firstOrNull()
            }

            log.d(tag = TAG) { "Audio devices refreshed: ${inputs.size} inputs, ${outputs.size} outputs" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error refreshing audio devices: ${e.message}" }
        }
    }

    private fun checkInitialized() {
        if (!isInitialized) {
            throw IllegalStateException("WebRTC not initialized. Call initialize() first.")
        }
    }
}
