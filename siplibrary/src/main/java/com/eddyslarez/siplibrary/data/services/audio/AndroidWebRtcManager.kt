package com.eddyslarez.siplibrary.data.services.audio

import android.Manifest
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

//import android.Manifest
//import android.annotation.SuppressLint
//import android.app.Application
//import android.bluetooth.BluetoothA2dp
//import android.bluetooth.BluetoothAdapter
//import android.bluetooth.BluetoothClass
//import android.bluetooth.BluetoothDevice
//import android.bluetooth.BluetoothHeadset
//import android.bluetooth.BluetoothManager
//import android.bluetooth.BluetoothProfile
//import android.content.BroadcastReceiver
//import android.content.Context
//import android.content.Intent
//import android.content.IntentFilter
//import android.content.pm.PackageManager
//import android.media.AudioAttributes
//import android.media.AudioDeviceCallback
//import android.media.AudioDeviceInfo
//import android.media.AudioFocusRequest
//import android.media.AudioManager
//import android.os.Build
//import androidx.annotation.RequiresApi
//import androidx.annotation.RequiresPermission
//import androidx.core.content.ContextCompat
//import com.eddyslarez.siplibrary.data.models.AccountInfo
//import com.eddyslarez.siplibrary.data.models.CallState
//import com.eddyslarez.siplibrary.data.services.translation.TranslationAudioProcessor
//import com.eddyslarez.siplibrary.utils.CallStateManager
//import com.eddyslarez.siplibrary.utils.log
//import com.shepeliev.webrtckmp.SessionDescriptionType
////import com.shepeliev.webrtckmp.MediaDeviceInfo
////import com.shepeliev.webrtckmp.MediaDevices
////import com.shepeliev.webrtckmp.AudioStreamTrack
////import com.shepeliev.webrtckmp.PeerConnection
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.SupervisorJob
//import kotlinx.coroutines.flow.launchIn
//import kotlinx.coroutines.flow.onEach
//import kotlinx.coroutines.launch
////import com.shepeliev.webrtckmp.IceCandidate
////import com.shepeliev.webrtckmp.IceServer
////import com.shepeliev.webrtckmp.OfferAnswerOptions
////import com.shepeliev.webrtckmp.PeerConnectionState
////import com.shepeliev.webrtckmp.RtcConfiguration
////import com.shepeliev.webrtckmp.SessionDescription
////import com.shepeliev.webrtckmp.SessionDescriptionType
////import com.shepeliev.webrtckmp.audioTracks
////import com.shepeliev.webrtckmp.onConnectionStateChange
////import com.shepeliev.webrtckmp.onIceCandidate
////import com.shepeliev.webrtckmp.onTrack
////import com.shepeliev.webrtckmp.MediaDevices
////import com.shepeliev.webrtckmp.MediaDeviceInfo
////import com.shepeliev.webrtckmp.MediaStreamTrackKind
//import org.webrtc.*
//import kotlinx.coroutines.cancel
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.suspendCancellableCoroutine
//import kotlinx.coroutines.withContext
//import org.webrtc.PeerConnectionFactory
//import java.util.concurrent.ConcurrentHashMap
//import kotlin.coroutines.resumeWithException
//



/**
 * Implementación Android de WebRTC Manager con soporte para traducción
 *
 * @author Eddys Larez
 */
class AndroidWebRtcManager(
    private val application: Application
) : WebRtcManager {

    companion object {
        private const val TAG = "AndroidWebRtcManager"
        /**
         * Implementación Android de WebRTC Manager con soporte para traducción
         *
         * @author Eddys Larez
         */
        class AndroidWebRtcManager(
            private val application: Application
        ) : WebRtcManager {

            companion object {
                private const val TAG = "AndroidWebRtcManager"
                private const val AUDIO_CODEC_OPUS = "opus"
                private const val AUDIO_SAMPLE_RATE = 48000
                private const val AUDIO_CHANNELS = 1
            }
            private val activeCandidates = mutableSetOf<IceCandidate>()

            // WebRTC components
            private var peerConnectionFactory: PeerConnectionFactory? = null
            private var peerConnection: PeerConnection? = null
            private var localAudioTrack: AudioTrack? = null
            private var remoteAudioTrack: AudioTrack? = null
            private var audioSource: AudioSource? = null
            private var listener: WebRtcEventListener? = null

            // Audio management
            private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
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

            @RequiresPermission(Manifest.permission.RECORD_AUDIO)
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

                    // Create offer
                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
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
                    return@withContext offer.description

                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error creating offer: ${e.message}" }
                    throw e
                }
            }

            override suspend fun createAnswer(accountInfo: AccountInfo, offerSdp: String): String = withContext(Dispatchers.IO) {
                checkInitialized()

                try {
                    log.d(tag = TAG) { "Creating answer for offer" }

                    // Create peer connection if not exists
                    if (peerConnection == null) {
                        createPeerConnection()
                    }

                    // Set remote description (offer)
                    setRemoteDescription(offerSdp, SdpType.OFFER)

                    // Add local audio track
                    addLocalAudioTrack()

                    // Create answer
                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
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
                    return@withContext answer.description

                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error creating answer: ${e.message}" }
                    throw e
                }
            }

            override suspend fun setRemoteDescription(sdp: String, type: SdpType) = withContext(Dispatchers.IO) {
                checkInitialized()

                try {
                    log.d(tag = TAG) { "Setting remote description: $type" }

                    val sessionDescription = SessionDescription(
                        if (type == SdpType.OFFER) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER,
                        sdp
                    )

                    suspendCancellableCoroutine<Unit> { continuation ->
                        peerConnection?.setRemoteDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                continuation.resume(Unit, null)
                            }

                            override fun onSetFailure(error: String) {
                                continuation.resumeWithException(Exception("Failed to set remote description: $error"))
                            }

                            override fun onCreateSuccess(sessionDescription: SessionDescription) {}
                            override fun onCreateFailure(error: String) {}
                        }, sessionDescription)
                    }

                    log.d(tag = TAG) { "Remote description set successfully" }

                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error setting remote description: ${e.message}" }
                    throw e
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

            override fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
                val inputDevices = mutableListOf<AudioDevice>()
                val outputDevices = mutableListOf<AudioDevice>()

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)

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
                    audioManager.mode = android.media.AudioManager.MODE_IN_COMMUNICATION

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

                    val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, modifiedSdp)

                    suspendCancellableCoroutine<Unit> { continuation ->
                        peerConnection?.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                continuation.resume(Unit, null)
                            }

                            override fun onSetFailure(error: String) {
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

                    val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
                        bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                        rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                        tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
                        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                    }

                    peerConnection = peerConnectionFactory?.createPeerConnection(
                        rtcConfig,
                        object : PeerConnection.Observer {
                            override fun onIceCandidate(iceCandidate: IceCandidate) {
                                try {
                                    // Agregar a nuestro tracking
                                    activeCandidates.add(iceCandidate)

                                    // Notificar al listener
                                    listener?.onIceCandidate(
                                        iceCandidate.sdp,
                                        iceCandidate.sdpMid,
                                        iceCandidate.sdpMLineIndex
                                    )

                                    log.d(tag = TAG) {
                                        "New ICE candidate: ${iceCandidate.sdp.take(50)}... " +
                                                "(Total active: ${activeCandidates.size})"
                                    }

                                } catch (e: Exception) {
                                    log.e(tag = TAG) { "Error handling ICE candidate: ${e.message}" }
                                }
                            }

                            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate?>?) {
                                try {
                                    log.d(tag = TAG) { "ICE candidates removed: ${candidates?.size ?: 0}" }

                                    // Filtrar candidatos nulos y procesar los válidos
                                    val validCandidates = candidates?.filterNotNull() ?: emptyList()

                                    if (validCandidates.isNotEmpty()) {
                                        // Remover de nuestro tracking local
                                        activeCandidates.removeAll(validCandidates.toSet())

                                        // Notificar al listener sobre los candidatos removidos
                                        listener?.onIceCandidatesRemoved(validCandidates)

                                        // Log detallado para debugging
                                        validCandidates.forEach { candidate ->
                                            log.d(tag = TAG) {
                                                "Removed ICE candidate: ${candidate.sdp.take(50)}... " +
                                                        "(MID: ${candidate.sdpMid}, MLineIndex: ${candidate.sdpMLineIndex})"
                                            }
                                        }

                                        // Opcional: Si quedan pocos candidatos, podrías triggerar una nueva gathering
                                        if (activeCandidates.size < 2) {
                                            log.w(tag = TAG) { "Low ICE candidate count after removal: ${activeCandidates.size}" }
                                            // Aquí podrías implementar lógica adicional si es necesario
                                        }
                                    }

                                } catch (e: Exception) {
                                    log.e(tag = TAG) { "Error handling removed ICE candidates: ${e.message}" }
                                    listener?.onError("Failed to process removed ICE candidates: ${e.message}")
                                }
                            }

                            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                                connectionState = when (newState) {
                                    PeerConnection.PeerConnectionState.NEW -> WebRtcConnectionState.NEW
                                    PeerConnection.PeerConnectionState.CONNECTING -> WebRtcConnectionState.CONNECTING
                                    PeerConnection.PeerConnectionState.CONNECTED -> WebRtcConnectionState.CONNECTED
                                    PeerConnection.PeerConnectionState.DISCONNECTED -> WebRtcConnectionState.DISCONNECTED
                                    PeerConnection.PeerConnectionState.FAILED -> WebRtcConnectionState.FAILED
                                    PeerConnection.PeerConnectionState.CLOSED -> WebRtcConnectionState.CLOSED
                                }
                                listener?.onConnectionStateChange(connectionState)
                            }

                            override fun onAddTrack(rtpReceiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                                val track = rtpReceiver.track()
                                if (track is AudioTrack) {
                                    remoteAudioTrack = track
                                    listener?.onRemoteAudioTrack()
                                }
                            }

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

                    // Create audio source
                    val audioConstraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                        mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                        mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
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
    private val audioManager = application.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
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

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
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

            // Create offer
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
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
            return@withContext offer.description

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating offer: ${e.message}" }
            throw e
        }
    }

    override suspend fun createAnswer(accountInfo: AccountInfo, offerSdp: String): String = withContext(Dispatchers.IO) {
        checkInitialized()

        try {
            log.d(tag = TAG) { "Creating answer for offer" }

            // Create peer connection if not exists
            if (peerConnection == null) {
                createPeerConnection()
            }

            // Set remote description (offer)
            setRemoteDescription(offerSdp, SdpType.OFFER)

            // Add local audio track
            addLocalAudioTrack()

            // Create answer
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
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
            return@withContext answer.description

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating answer: ${e.message}" }
            throw e
        }
    }

    override suspend fun setRemoteDescription(sdp: String, type: SdpType) = withContext(Dispatchers.IO) {
        checkInitialized()

        try {
            log.d(tag = TAG) { "Setting remote description: $type" }

            val sessionDescription = SessionDescription(
                if (type == SdpType.OFFER) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER,
                sdp
            )

            suspendCancellableCoroutine<Unit> { continuation ->
                peerConnection?.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        continuation.resume(Unit, null)
                    }

                    override fun onSetFailure(error: String) {
                        continuation.resumeWithException(Exception("Failed to set remote description: $error"))
                    }

                    override fun onCreateSuccess(sessionDescription: SessionDescription) {}
                    override fun onCreateFailure(error: String) {}
                }, sessionDescription)
            }

            log.d(tag = TAG) { "Remote description set successfully" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error setting remote description: ${e.message}" }
            throw e
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

    override fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        val inputDevices = mutableListOf<AudioDevice>()
        val outputDevices = mutableListOf<AudioDevice>()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)

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

            val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, modifiedSdp)

            suspendCancellableCoroutine<Unit> { continuation ->
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        continuation.resume(Unit, null)
                    }

                    override fun onSetFailure(error: String) {
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

            val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }

            peerConnection = peerConnectionFactory?.createPeerConnection(
                rtcConfig,
                object : PeerConnection.Observer {
                    override fun onIceCandidate(iceCandidate: IceCandidate) {
                        listener?.onIceCandidate(
                            iceCandidate.sdp,
                            iceCandidate.sdpMid,
                            iceCandidate.sdpMLineIndex
                        )
                    }

                    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate?>?) {
                        TODO("Not yet implemented")
                    }

                    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                        connectionState = when (newState) {
                            PeerConnection.PeerConnectionState.NEW -> WebRtcConnectionState.NEW
                            PeerConnection.PeerConnectionState.CONNECTING -> WebRtcConnectionState.CONNECTING
                            PeerConnection.PeerConnectionState.CONNECTED -> WebRtcConnectionState.CONNECTED
                            PeerConnection.PeerConnectionState.DISCONNECTED -> WebRtcConnectionState.DISCONNECTED
                            PeerConnection.PeerConnectionState.FAILED -> WebRtcConnectionState.FAILED
                            PeerConnection.PeerConnectionState.CLOSED -> WebRtcConnectionState.CLOSED
                        }
                        listener?.onConnectionStateChange(connectionState)
                    }

                    override fun onAddTrack(rtpReceiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                        val track = rtpReceiver.track()
                        if (track is AudioTrack) {
                            remoteAudioTrack = track
                            listener?.onRemoteAudioTrack()
                        }
                    }

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

            // Create audio source
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
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
///**
// * Enhanced Android implementation of WebRtcManager interface with comprehensive audio device support
// * FIXED: Bluetooth audio routing issues
// *
// * @author Eddys Larez
// */
//class AndroidWebRtcManager(private val application: Application) : WebRtcManager {
//    private val TAG = "AndroidWebRtcManager"
//    private var dtmfSender: DtmfSender? = null
//    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
//
//    private var peerConnectionFactory: PeerConnectionFactory? = null
//    private var peerConnection: PeerConnection? = null
//    private var localAudioTrack: AudioTrack? = null
//    private var remoteAudioTrack: AudioTrack? = null
//    private var webRtcEventListener: WebRtcEventListener? = null
//    private var isInitialized = false
//    private var isLocalAudioReady = false
//    private var context: Context = application.applicationContext
//    private var audioProcessor: TranslationAudioProcessor? = null
//    private var isTranslationEnabled = false
//    private var translationCallback: ((ByteArray) -> Unit)? = null
//    // Enhanced audio management fields
//    private var audioManager: AudioManager? = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
//    private var bluetoothAdapter: BluetoothAdapter? = null
//    private var bluetoothManager: BluetoothManager? = null
//    private var audioDeviceCallback: AudioDeviceCallback? = null
//    private var currentInputDevice: AudioDevice? = null
//    private var currentOutputDevice: AudioDevice? = null
//    private var listener: WebRtcEventListener? = null
//    private var connectionState = WebRtcConnectionState.NEW
//
//    // FIXED: Add Bluetooth SCO state tracking
//    private var isBluetoothScoRequested = false
//    private var bluetoothScoReceiver: BroadcastReceiver? = null
//    private val availableDevices = ConcurrentHashMap<String, AudioDevice>()
//    private var isMuted = false
//
//    // Audio state management
//    private var savedAudioMode = AudioManager.MODE_NORMAL
//    private var savedIsSpeakerPhoneOn = false
//    private var savedIsMicrophoneMute = false
//    private var audioFocusRequest: AudioFocusRequest? = null
//    private var audioSource: AudioSource? = null
//
//    // Device monitoring
//    private var deviceChangeListeners = mutableListOf<(List<AudioDevice>) -> Unit>()
//
//    init {
//        initializeBluetoothComponents()
//        setupAudioDeviceMonitoring()
//        setupBluetoothScoReceiver() // FIXED: Add Bluetooth SCO monitoring
//    }
//
//    /**
//     * FIXED: Setup Bluetooth SCO state monitoring
//     */
//    private fun setupBluetoothScoReceiver() {
//        bluetoothScoReceiver = object : BroadcastReceiver() {
//            override fun onReceive(context: Context?, intent: Intent?) {
//                when (intent?.action) {
//                    AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
//                        val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
//                        handleBluetoothScoStateChange(state)
//                    }
//                }
//            }
//        }
//
//        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
//        context.registerReceiver(bluetoothScoReceiver, filter)
//        log.d(TAG) { "Bluetooth SCO receiver registered" }
//    }
//
//    /**
//     * FIXED: Handle Bluetooth SCO state changes
//     */
//    private fun handleBluetoothScoStateChange(state: Int) {
//        when (state) {
//            AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
//                log.d(TAG) { "Bluetooth SCO connected" }
//                isBluetoothScoRequested = false
//                // Notify success if we were trying to connect
//                webRtcEventListener?.onAudioDeviceChanged(currentOutputDevice)
//            }
//            AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
//                log.d(TAG) { "Bluetooth SCO disconnected" }
//                isBluetoothScoRequested = false
//            }
//            AudioManager.SCO_AUDIO_STATE_CONNECTING -> {
//                log.d(TAG) { "Bluetooth SCO connecting..." }
//            }
//            AudioManager.SCO_AUDIO_STATE_ERROR -> {
//                log.e(TAG) { "Bluetooth SCO error" }
//                isBluetoothScoRequested = false
//            }
//        }
//    }
//
//    /**
//     * Initialize Bluetooth components for enhanced device detection
//     */
//    private fun initializeBluetoothComponents() {
//        try {
//            bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
//            bluetoothAdapter = bluetoothManager?.adapter
//            log.d(TAG) { "Bluetooth components initialized" }
//        } catch (e: Exception) {
//            log.e(TAG) { "Error initializing Bluetooth: ${e.message}" }
//        }
//    }
//
//    /**
//     * Setup audio device monitoring for real-time changes
//     */
//    @RequiresApi(Build.VERSION_CODES.M)
//    private fun setupAudioDeviceMonitoring() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//            audioDeviceCallback = object : AudioDeviceCallback() {
//                @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
//                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
//                    log.d(TAG) { "Audio devices added: ${addedDevices.size}" }
//                    notifyDeviceChange()
//                }
//
//                @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
//                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
//                    log.d(TAG) { "Audio devices removed: ${removedDevices.size}" }
//                    notifyDeviceChange()
//                }
//            }
//            audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
//        }
//    }
//
//    /**
//     * Notify listeners about device changes
//     */
//    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
//    private fun notifyDeviceChange() {
//        try {
//            val (inputs, outputs) = getAllAudioDevices()
//            val allDevices = inputs + outputs
//            deviceChangeListeners.forEach { listener ->
//                try {
//                    listener(allDevices)
//                } catch (e: Exception) {
//                    log.e(TAG) { "Error in device change listener: ${e.message}" }
//                }
//            }
//        } catch (e: Exception) {
//            log.e(TAG) { "Error notifying device change: ${e.message}" }
//        }
//    }
//
//    @SuppressLint("MissingPermission")
//    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
//    override fun initialize() {
//        if (isInitialized) {
//            log.w(tag = TAG) { "WebRTC already initialized" }
//            return
//        }
//
//        try {
//            log.d(tag = TAG) { "Initializing WebRTC" }
//
//            // Initialize WebRTC
//            val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(application)
//                .setEnableInternalTracer(false)
//                .createInitializationOptions()
//
//            PeerConnectionFactory.initialize(initializationOptions)
//
//            // Create PeerConnectionFactory
//            val options = PeerConnectionFactory.Options()
//            peerConnectionFactory = PeerConnectionFactory.builder()
//                .setOptions(options)
//                .createPeerConnectionFactory()
//
//            // Initialize audio devices
//            refreshAudioDevices()
//
//            // Initialize audio processor for translation
//            audioProcessor = TranslationAudioProcessor()
//            audioProcessor?.initialize()
//
//            isInitialized = true
//            log.d(tag = TAG) { "WebRTC initialized successfully" }
//
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error initializing WebRTC: ${e.message}" }
//            throw e
//        }
//    }
//    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
//    private fun refreshAudioDevices() {
//        try {
//            val (inputs, outputs) = getAllAudioDevices()
//
//            // Update available devices map
//            availableDevices.clear()
//            (inputs + outputs).forEach { device ->
//                availableDevices[device.descriptor] = device
//            }
//
//            // Set default devices if not set
//            if (currentInputDevice == null) {
//                currentInputDevice = inputs.find { it.audioUnit.isDefault } ?: inputs.firstOrNull()
//            }
//
//            if (currentOutputDevice == null) {
//                currentOutputDevice = outputs.find { it.audioUnit.isDefault } ?: outputs.firstOrNull()
//            }
//
//            log.d(tag = TAG) { "Audio devices refreshed: ${inputs.size} inputs, ${outputs.size} outputs" }
//
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error refreshing audio devices: ${e.message}" }
//        }
//    }
//    /**
//     * Initialize audio system for calls
//     */
//    private fun initializeAudio() {
//        audioManager?.let { am ->
//            // Save current audio state
//            savedAudioMode = am.mode
//            savedIsSpeakerPhoneOn = am.isSpeakerphoneOn
//            savedIsMicrophoneMute = am.isMicrophoneMute
//
//            log.d(TAG) { "Saved audio state - Mode: $savedAudioMode, Speaker: $savedIsSpeakerPhoneOn, Mic muted: $savedIsMicrophoneMute" }
//
//            // Configure audio for communication
//            am.mode = AudioManager.MODE_IN_COMMUNICATION
//            am.isSpeakerphoneOn = false
//            am.isMicrophoneMute = false
//
//            // Request audio focus
//            requestAudioFocus()
//
//            log.d(TAG) { "Audio configured for WebRTC communication" }
//        } ?: run {
//            log.d(TAG) { "AudioManager not available!" }
//        }
//    }
//
//    /**
//     * Request audio focus for the call
//     */
//    private fun requestAudioFocus() {
//        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
//            val audioAttributes = AudioAttributes.Builder()
//                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
//                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
//                .build()
//
//            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
//                .setAudioAttributes(audioAttributes)
//                .setAcceptsDelayedFocusGain(false)
//                .setWillPauseWhenDucked(true)
//                .setOnAudioFocusChangeListener { focusChange ->
//                    // Handle audio focus changes
//                    when (focusChange) {
//                        AudioManager.AUDIOFOCUS_GAIN -> {
//                            log.d(TAG) { "Audio focus gained" }
//                            setAudioEnabled(true)
//                        }
//                        AudioManager.AUDIOFOCUS_LOSS -> {
//                            log.d(TAG) { "Audio focus lost" }
//                        }
//                    }
//                }
//                .build()
//
//            audioFocusRequest = focusRequest
//            val result = audioManager?.requestAudioFocus(focusRequest) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
//            log.d(TAG) { "Audio focus request result: $result" }
//
//        } else {
//            // Legacy audio focus request for older Android versions
//            @Suppress("DEPRECATION")
//            val result = audioManager?.requestAudioFocus(
//                { focusChange ->
//                    when (focusChange) {
//                        AudioManager.AUDIOFOCUS_GAIN -> setAudioEnabled(true)
//                    }
//                },
//                AudioManager.STREAM_VOICE_CALL,
//                AudioManager.AUDIOFOCUS_GAIN
//            ) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
//
//            log.d(TAG) { "Legacy audio focus request result: $result" }
//        }
//    }
//
//    /**
//     * Releases audio focus and restores previous audio settings
//     */
//    private fun releaseAudioFocus() {
//        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
//            audioFocusRequest?.let { request ->
//                audioManager?.abandonAudioFocusRequest(request)
//            }
//        } else {
//            @Suppress("DEPRECATION")
//            audioManager?.abandonAudioFocus(null)
//        }
//
//        // Restore previous audio settings
//        audioManager?.let { am ->
//            am.mode = savedAudioMode
//            am.isSpeakerphoneOn = savedIsSpeakerPhoneOn
//            am.isMicrophoneMute = savedIsMicrophoneMute
//
//            log.d(TAG) { "Restored audio state" }
//        }
//    }
//
//
//    /**
//     * Enhanced device detection with comprehensive AudioDevice mapping
//     */
//    @RequiresPermission(allOf = [
//        Manifest.permission.BLUETOOTH_CONNECT,
//        Manifest.permission.RECORD_AUDIO
//    ])
//    override fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
//        log.d(TAG) { "Getting all enhanced audio devices..." }
//
//        val inputDevices = mutableListOf<AudioDevice>()
//        val outputDevices = mutableListOf<AudioDevice>()
//
//        try {
//            val am = audioManager ?: return Pair(emptyList(), emptyList())
//
//            // Get current device states for comparison
//            val currentInputDescriptor = getCurrentAudioInputDescriptor()
//            val currentOutputDescriptor = getCurrentAudioOutputDescriptor()
//            val defaultInputDescriptor = getDefaultInputDescriptor()
//            val defaultOutputDescriptor = getDefaultOutputDescriptor()
//
//            // Built-in devices (always available)
//            addBuiltInDevices(inputDevices, outputDevices,
//                currentInputDescriptor, currentOutputDescriptor,
//                defaultInputDescriptor, defaultOutputDescriptor)
//
//            // Wired devices
//            addWiredDevices(inputDevices, outputDevices,
//                currentInputDescriptor, currentOutputDescriptor)
//
//            // Bluetooth devices
//            addBluetoothDevices(inputDevices, outputDevices,
//                currentInputDescriptor, currentOutputDescriptor)
//
//            // USB and other devices (API 23+)
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//                addUsbAndOtherDevices(inputDevices, outputDevices,
//                    currentInputDescriptor, currentOutputDescriptor)
//            }
//
//            log.d(TAG) { "Found ${inputDevices.size} input and ${outputDevices.size} output devices" }
//
//        } catch (e: Exception) {
//            log.e(TAG) { "Error getting audio devices: ${e.message}" }
//            // Return basic fallback devices
//            return getFallbackDevices()
//        }
//
//        return Pair(inputDevices, outputDevices)
//    }
//
//    /**
//     * Add built-in audio devices
//     */
//    private fun addBuiltInDevices(
//        inputDevices: MutableList<AudioDevice>,
//        outputDevices: MutableList<AudioDevice>,
//        currentInputDescriptor: String?,
//        currentOutputDescriptor: String?,
//        defaultInputDescriptor: String?,
//        defaultOutputDescriptor: String?
//    ) {
//        // Built-in microphone
//        val micDescriptor = "builtin_mic"
//        val micAudioUnit = AudioUnit(
//            type = AudioUnitTypes.MICROPHONE,
//            capability = AudioUnitCompatibilities.RECORD,
//            isCurrent = currentInputDescriptor == micDescriptor,
//            isDefault = defaultInputDescriptor == micDescriptor
//        )
//
//        inputDevices.add(
//            AudioDevice(
//                name = "Built-in Microphone",
//                descriptor = micDescriptor,
//                nativeDevice = null,
//                isOutput = false,
//                audioUnit = micAudioUnit,
//                connectionState = DeviceConnectionState.AVAILABLE,
//                isWireless = false,
//                supportsHDVoice = true,
//                latency = 10,
//                vendorInfo = "Built-in"
//            )
//        )
//
//        // Earpiece (if available)
//        if (audioManager?.hasEarpiece() == true) {
//            val earpieceDescriptor = "earpiece"
//            val earpieceAudioUnit = AudioUnit(
//                type = AudioUnitTypes.EARPIECE,
//                capability = AudioUnitCompatibilities.PLAY,
//                isCurrent = currentOutputDescriptor == earpieceDescriptor,
//                isDefault = defaultOutputDescriptor == earpieceDescriptor
//            )
//
//            outputDevices.add(
//                AudioDevice(
//                    name = "Earpiece",
//                    descriptor = earpieceDescriptor,
//                    nativeDevice = null,
//                    isOutput = true,
//                    audioUnit = earpieceAudioUnit,
//                    connectionState = DeviceConnectionState.AVAILABLE,
//                    isWireless = false,
//                    supportsHDVoice = true,
//                    latency = 5,
//                    vendorInfo = "Built-in"
//                )
//            )
//        }
//
//        // Built-in speaker
//        val speakerDescriptor = "speaker"
//        val speakerAudioUnit = AudioUnit(
//            type = AudioUnitTypes.SPEAKER,
//            capability = AudioUnitCompatibilities.PLAY,
//            isCurrent = currentOutputDescriptor == speakerDescriptor,
//            isDefault = if (audioManager?.hasEarpiece() == true) false else defaultOutputDescriptor == speakerDescriptor
//        )
//
//        outputDevices.add(
//            AudioDevice(
//                name = "Speaker",
//                descriptor = speakerDescriptor,
//                nativeDevice = null,
//                isOutput = true,
//                audioUnit = speakerAudioUnit,
//                connectionState = DeviceConnectionState.AVAILABLE,
//                isWireless = false,
//                supportsHDVoice = true,
//                latency = 15,
//                vendorInfo = "Built-in"
//            )
//        )
//    }
//
//    /**
//     * Add wired audio devices
//     */
//    private fun addWiredDevices(
//        inputDevices: MutableList<AudioDevice>,
//        outputDevices: MutableList<AudioDevice>,
//        currentInputDescriptor: String?,
//        currentOutputDescriptor: String?
//    ) {
//        if (audioManager?.isWiredHeadsetOn == true) {
//            // Wired headset output
//            val headsetOutDescriptor = "wired_headset"
//            val headsetOutAudioUnit = AudioUnit(
//                type = AudioUnitTypes.HEADSET,
//                capability = AudioUnitCompatibilities.PLAY,
//                isCurrent = currentOutputDescriptor == headsetOutDescriptor,
//                isDefault = false
//            )
//
//            outputDevices.add(
//                AudioDevice(
//                    name = "Wired Headset",
//                    descriptor = headsetOutDescriptor,
//                    nativeDevice = null,
//                    isOutput = true,
//                    audioUnit = headsetOutAudioUnit,
//                    connectionState = DeviceConnectionState.CONNECTED,
//                    isWireless = false,
//                    supportsHDVoice = true,
//                    latency = 20,
//                    vendorInfo = extractVendorFromWiredDevice()
//                )
//            )
//
//            // Wired headset microphone
//            val headsetMicDescriptor = "wired_headset_mic"
//            val headsetMicAudioUnit = AudioUnit(
//                type = AudioUnitTypes.HEADSET,
//                capability = AudioUnitCompatibilities.RECORD,
//                isCurrent = currentInputDescriptor == headsetMicDescriptor,
//                isDefault = false
//            )
//
//            inputDevices.add(
//                AudioDevice(
//                    name = "Wired Headset Microphone",
//                    descriptor = headsetMicDescriptor,
//                    nativeDevice = null,
//                    isOutput = false,
//                    audioUnit = headsetMicAudioUnit,
//                    connectionState = DeviceConnectionState.CONNECTED,
//                    isWireless = false,
//                    supportsHDVoice = true,
//                    latency = 20,
//                    vendorInfo = extractVendorFromWiredDevice()
//                )
//            )
//        }
//    }
//
//    /**
//     * Add Bluetooth audio devices
//     */
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    private fun addBluetoothDevices(
//        inputDevices: MutableList<AudioDevice>,
//        outputDevices: MutableList<AudioDevice>,
//        currentInputDescriptor: String?,
//        currentOutputDescriptor: String?
//    ) {
//        try {
//            if (audioManager?.isBluetoothScoAvailableOffCall == true) {
//                val connectedBluetoothDevices = getConnectedBluetoothDevices()
//
//                connectedBluetoothDevices.forEach { bluetoothDevice ->
//                    val deviceName = getBluetoothDeviceName(bluetoothDevice)
//                    val deviceAddress = bluetoothDevice.address
//                    val vendorInfo = extractVendorFromBluetoothDevice(bluetoothDevice)
//                    val signalStrength = estimateBluetoothSignalStrength()
//                    val batteryLevel = getBluetoothBatteryLevel(bluetoothDevice)
//
//                    // Determine device type and capabilities
//                    val (audioUnitType, supportsA2DP) = classifyBluetoothDevice(bluetoothDevice)
//
//                    // Bluetooth output device
//                    val btOutDescriptor = "bluetooth_${deviceAddress}"
//                    val btOutAudioUnit = AudioUnit(
//                        type = audioUnitType,
//                        capability = AudioUnitCompatibilities.PLAY,
//                        isCurrent = currentOutputDescriptor == btOutDescriptor,
//                        isDefault = false
//                    )
//
//                    outputDevices.add(
//                        AudioDevice(
//                            name = deviceName,
//                            descriptor = btOutDescriptor,
//                            nativeDevice = bluetoothDevice,
//                            isOutput = true,
//                            audioUnit = btOutAudioUnit,
//                            connectionState = getBluetoothConnectionState(bluetoothDevice),
//                            signalStrength = signalStrength,
//                            batteryLevel = batteryLevel,
//                            isWireless = true,
//                            supportsHDVoice = supportsA2DP,
//                            latency = if (supportsA2DP) 200 else 150,
//                            vendorInfo = vendorInfo
//                        )
//                    )
//
//                    // Bluetooth input device (for HFP/HSP)
//                    if (supportsHandsFreeProfile(bluetoothDevice)) {
//                        val btMicDescriptor = "bluetooth_mic_${deviceAddress}"
//                        val btMicAudioUnit = AudioUnit(
//                            type = AudioUnitTypes.BLUETOOTH,
//                            capability = AudioUnitCompatibilities.RECORD,
//                            isCurrent = currentInputDescriptor == btMicDescriptor,
//                            isDefault = false
//                        )
//
//                        inputDevices.add(
//                            AudioDevice(
//                                name = "$deviceName Microphone",
//                                descriptor = btMicDescriptor,
//                                nativeDevice = bluetoothDevice,
//                                isOutput = false,
//                                audioUnit = btMicAudioUnit,
//                                connectionState = getBluetoothConnectionState(bluetoothDevice),
//                                signalStrength = signalStrength,
//                                batteryLevel = batteryLevel,
//                                isWireless = true,
//                                supportsHDVoice = false, // HFP typically doesn't support HD Voice
//                                latency = 150,
//                                vendorInfo = vendorInfo
//                            )
//                        )
//                    }
//                }
//            }
//        } catch (e: SecurityException) {
//            log.w(TAG) { "Bluetooth permission not granted: ${e.message}" }
//        } catch (e: Exception) {
//            log.e(TAG) { "Error adding Bluetooth devices: ${e.message}" }
//        }
//    }
//
//    /**
//     * Add USB and other audio devices (API 23+)
//     */
//    @RequiresApi(Build.VERSION_CODES.M)
//    private fun addUsbAndOtherDevices(
//        inputDevices: MutableList<AudioDevice>,
//        outputDevices: MutableList<AudioDevice>,
//        currentInputDescriptor: String?,
//        currentOutputDescriptor: String?
//    ) {
//        try {
//            val audioDevices = audioManager?.getDevices(
//                AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS
//            ) ?: return
//
//            audioDevices.forEach { deviceInfo ->
//                when (deviceInfo.type) {
//                    AudioDeviceInfo.TYPE_USB_ACCESSORY,
//                    AudioDeviceInfo.TYPE_USB_DEVICE,
//                    AudioDeviceInfo.TYPE_USB_HEADSET -> {
//                        addUsbDevice(
//                            deviceInfo, inputDevices, outputDevices,
//                            currentInputDescriptor, currentOutputDescriptor
//                        )
//                    }
//                    AudioDeviceInfo.TYPE_DOCK -> {
//                        addDockDevice(
//                            deviceInfo, inputDevices, outputDevices,
//                            currentInputDescriptor, currentOutputDescriptor
//                        )
//                    }
//                    AudioDeviceInfo.TYPE_AUX_LINE -> {
//                        addAuxDevice(
//                            deviceInfo, inputDevices, outputDevices,
//                            currentInputDescriptor, currentOutputDescriptor
//                        )
//                    }
//                    AudioDeviceInfo.TYPE_HEARING_AID -> {
//                        addHearingAidDevice(
//                            deviceInfo, inputDevices, outputDevices,
//                            currentInputDescriptor, currentOutputDescriptor
//                        )
//                    }
//                }
//            }
//        } catch (e: Exception) {
//            log.e(TAG) { "Error adding USB/other devices: ${e.message}" }
//        }
//    }
//
//    /**
//     * Add USB audio device
//     */
//    @RequiresApi(Build.VERSION_CODES.M)
//    private fun addUsbDevice(
//        deviceInfo: AudioDeviceInfo,
//        inputDevices: MutableList<AudioDevice>,
//        outputDevices: MutableList<AudioDevice>,
//        currentInputDescriptor: String?,
//        currentOutputDescriptor: String?
//    ) {
//        val deviceName = deviceInfo.productName?.toString() ?: "USB Audio Device"
//        val deviceId = deviceInfo.id.toString()
//        val isSource = deviceInfo.isSource
//        val isSink = deviceInfo.isSink
//
//        if (isSink) {
//            val usbOutDescriptor = "usb_out_$deviceId"
//            val usbOutAudioUnit = AudioUnit(
//                type = AudioUnitTypes.GENERICUSB,
//                capability = AudioUnitCompatibilities.PLAY,
//                isCurrent = currentOutputDescriptor == usbOutDescriptor,
//                isDefault = false
//            )
//
//            outputDevices.add(
//                AudioDevice(
//                    name = deviceName,
//                    descriptor = usbOutDescriptor,
//                    nativeDevice = deviceInfo,
//                    isOutput = true,
//                    audioUnit = usbOutAudioUnit,
//                    connectionState = DeviceConnectionState.CONNECTED,
//                    isWireless = false,
//                    supportsHDVoice = true,
//                    latency = 30,
//                    vendorInfo = extractVendorFromDeviceName(deviceName)
//                )
//            )
//        }
//
//        if (isSource) {
//            val usbInDescriptor = "usb_in_$deviceId"
//            val usbInAudioUnit = AudioUnit(
//                type = AudioUnitTypes.GENERICUSB,
//                capability = AudioUnitCompatibilities.RECORD,
//                isCurrent = currentInputDescriptor == usbInDescriptor,
//                isDefault = false
//            )
//
//            inputDevices.add(
//                AudioDevice(
//                    name = "$deviceName Microphone",
//                    descriptor = usbInDescriptor,
//                    nativeDevice = deviceInfo,
//                    isOutput = false,
//                    audioUnit = usbInAudioUnit,
//                    connectionState = DeviceConnectionState.CONNECTED,
//                    isWireless = false,
//                    supportsHDVoice = true,
//                    latency = 30,
//                    vendorInfo = extractVendorFromDeviceName(deviceName)
//                )
//            )
//        }
//    }
//
//    /**
//     * Add dock audio device
//     */
//    @RequiresApi(Build.VERSION_CODES.M)
//    private fun addDockDevice(
//        deviceInfo: AudioDeviceInfo,
//        inputDevices: MutableList<AudioDevice>,
//        outputDevices: MutableList<AudioDevice>,
//        currentInputDescriptor: String?,
//        currentOutputDescriptor: String?
//    ) {
//        val deviceName = "Dock Audio"
//        val deviceId = deviceInfo.id.toString()
//
//        if (deviceInfo.isSink) {
//            val dockDescriptor = "dock_$deviceId"
//            val dockAudioUnit = AudioUnit(
//                type = AudioUnitTypes.AUXLINE,
//                capability = AudioUnitCompatibilities.PLAY,
//                isCurrent = currentOutputDescriptor == dockDescriptor,
//                isDefault = false
//            )
//
//            outputDevices.add(
//                AudioDevice(
//                    name = deviceName,
//                    descriptor = dockDescriptor,
//                    nativeDevice = deviceInfo,
//                    isOutput = true,
//                    audioUnit = dockAudioUnit,
//                    connectionState = DeviceConnectionState.CONNECTED,
//                    isWireless = false,
//                    supportsHDVoice = false,
//                    latency = 25,
//                    vendorInfo = null
//                )
//            )
//        }
//    }
//
//    /**
//     * Add auxiliary line device
//     */
//    @RequiresApi(Build.VERSION_CODES.M)
//    private fun addAuxDevice(
//        deviceInfo: AudioDeviceInfo,
//        inputDevices: MutableList<AudioDevice>,
//        outputDevices: MutableList<AudioDevice>,
//        currentInputDescriptor: String?,
//        currentOutputDescriptor: String?
//    ) {
//        val deviceName = "Auxiliary Audio"
//        val deviceId = deviceInfo.id.toString()
//
//        if (deviceInfo.isSink) {
//            val auxDescriptor = "aux_$deviceId"
//            val auxAudioUnit = AudioUnit(
//                type = AudioUnitTypes.AUXLINE,
//                capability = AudioUnitCompatibilities.PLAY,
//                isCurrent = currentOutputDescriptor == auxDescriptor,
//                isDefault = false
//            )
//
//            outputDevices.add(
//                AudioDevice(
//                    name = deviceName,
//                    descriptor = auxDescriptor,
//                    nativeDevice = deviceInfo,
//                    isOutput = true,
//                    audioUnit = auxAudioUnit,
//                    connectionState = DeviceConnectionState.CONNECTED,
//                    isWireless = false,
//                    supportsHDVoice = false,
//                    latency = 15,
//                    vendorInfo = null
//                )
//            )
//        }
//    }
//
//    /**
//     * Add hearing aid device
//     */
//    @RequiresApi(Build.VERSION_CODES.M)
//    private fun addHearingAidDevice(
//        deviceInfo: AudioDeviceInfo,
//        inputDevices: MutableList<AudioDevice>,
//        outputDevices: MutableList<AudioDevice>,
//        currentInputDescriptor: String?,
//        currentOutputDescriptor: String?
//    ) {
//        val deviceName = "Hearing Aid"
//        val deviceId = deviceInfo.id.toString()
//
//        if (deviceInfo.isSink) {
//            val hearingAidDescriptor = "hearing_aid_$deviceId"
//            val hearingAidAudioUnit = AudioUnit(
//                type = AudioUnitTypes.HEARINGAID,
//                capability = AudioUnitCompatibilities.PLAY,
//                isCurrent = currentOutputDescriptor == hearingAidDescriptor,
//                isDefault = false
//            )
//
//            outputDevices.add(
//                AudioDevice(
//                    name = deviceName,
//                    descriptor = hearingAidDescriptor,
//                    nativeDevice = deviceInfo,
//                    isOutput = true,
//                    audioUnit = hearingAidAudioUnit,
//                    connectionState = DeviceConnectionState.CONNECTED,
//                    isWireless = true,
//                    supportsHDVoice = true,
//                    latency = 50,
//                    vendorInfo = extractVendorFromDeviceName(deviceInfo.productName?.toString() ?: "")
//                )
//            )
//        }
//    }
//
//    /**
//     * Get fallback devices when detection fails
//     */
//    private fun getFallbackDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
//        val inputDevices = listOf(
//            AudioDevice(
//                name = "Built-in Microphone",
//                descriptor = "builtin_mic",
//                nativeDevice = null,
//                isOutput = false,
//                audioUnit = AudioUnit(
//                    type = AudioUnitTypes.MICROPHONE,
//                    capability = AudioUnitCompatibilities.RECORD,
//                    isCurrent = true,
//                    isDefault = true
//                ),
//                connectionState = DeviceConnectionState.AVAILABLE,
//                isWireless = false,
//                supportsHDVoice = true,
//                latency = 10,
//                vendorInfo = "Built-in"
//            )
//        )
//
//        val outputDevices = mutableListOf<AudioDevice>()
//
//        // Add earpiece if available
//        if (audioManager?.hasEarpiece() == true) {
//            outputDevices.add(
//                AudioDevice(
//                    name = "Earpiece",
//                    descriptor = "earpiece",
//                    nativeDevice = null,
//                    isOutput = true,
//                    audioUnit = AudioUnit(
//                        type = AudioUnitTypes.EARPIECE,
//                        capability = AudioUnitCompatibilities.PLAY,
//                        isCurrent = true,
//                        isDefault = true
//                    ),
//                    connectionState = DeviceConnectionState.AVAILABLE,
//                    isWireless = false,
//                    supportsHDVoice = true,
//                    latency = 5,
//                    vendorInfo = "Built-in"
//                )
//            )
//        }
//
//        // Always add speaker
//        outputDevices.add(
//            AudioDevice(
//                name = "Speaker",
//                descriptor = "speaker",
//                nativeDevice = null,
//                isOutput = true,
//                audioUnit = AudioUnit(
//                    type = AudioUnitTypes.SPEAKER,
//                    capability = AudioUnitCompatibilities.PLAY,
//                    isCurrent = !audioManager?.hasEarpiece()!!,
//                    isDefault = !audioManager?.hasEarpiece()!!
//                ),
//                connectionState = DeviceConnectionState.AVAILABLE,
//                isWireless = false,
//                supportsHDVoice = true,
//                latency = 15,
//                vendorInfo = "Built-in"
//            )
//        )
//
//        return Pair(inputDevices, outputDevices)
//    }
//
//    // Helper methods for device detection and classification
//
//    /**
//     * Get current audio input descriptor
//     */
//    private fun getCurrentAudioInputDescriptor(): String? {
//        return when {
//            audioManager?.isBluetoothScoOn == true -> "bluetooth_mic_active"
//            audioManager?.isWiredHeadsetOn == true -> "wired_headset_mic"
//            else -> "builtin_mic"
//        }
//    }
//
//    /**
//     * Get current audio output descriptor
//     */
//    private fun getCurrentAudioOutputDescriptor(): String? {
//        return when {
//            audioManager?.isBluetoothScoOn == true -> "bluetooth_active"
//            audioManager?.isSpeakerphoneOn == true -> "speaker"
//            audioManager?.isWiredHeadsetOn == true -> "wired_headset"
//            else -> if (audioManager?.hasEarpiece() == true) "earpiece" else "speaker"
//        }
//    }
//
//    /**
//     * Get default input descriptor
//     */
//    private fun getDefaultInputDescriptor(): String {
//        return "builtin_mic"
//    }
//
//    /**
//     * Get default output descriptor
//     */
//    private fun getDefaultOutputDescriptor(): String {
//        return if (audioManager?.hasEarpiece() == true) "earpiece" else "speaker"
//    }
//
//    /**
//     * Get connected Bluetooth devices
//     */
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    private fun getConnectedBluetoothDevices(): List<BluetoothDevice> {
//        return try {
//            bluetoothAdapter?.bondedDevices?.filter { device ->
//                isBluetoothDeviceConnected(device)
//            } ?: emptyList()
//        } catch (e: SecurityException) {
//            log.w(TAG) { "Bluetooth permission not granted for getting connected devices" }
//            emptyList()
//        } catch (e: Exception) {
//            log.e(TAG) { "Error getting connected Bluetooth devices: ${e.message}" }
//            emptyList()
//        }
//    }
//
//    /**
//     * Check if Bluetooth device is connected - FIXED VERSION
//     */
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    private fun isBluetoothDeviceConnected(device: BluetoothDevice): Boolean {
//        return try {
//            // Method 1: Using BluetoothManager (API 18+)
//            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
//            val connectionState = bluetoothManager?.getConnectionState(device, BluetoothProfile.HEADSET)
//
//            when (connectionState) {
//                BluetoothProfile.STATE_CONNECTED -> return true
//                BluetoothProfile.STATE_CONNECTING -> return false
//                BluetoothProfile.STATE_DISCONNECTED -> return false
//                BluetoothProfile.STATE_DISCONNECTING -> return false
//                else -> {
//                    // Method 2: Alternative check using reflection (fallback)
//                    return checkBluetoothConnectionViaReflection(device)
//                }
//            }
//        } catch (e: Exception) {
//            log.w(TAG) { "Error checking Bluetooth connection state: ${e.message}" }
//            // Method 3: Fallback - assume bonded devices are connected if Bluetooth audio is available
//            device.bondState == BluetoothDevice.BOND_BONDED &&
//                    audioManager?.isBluetoothScoAvailableOffCall == true
//        }
//    }
//
//    /**
//     * Alternative method using BluetoothProfile service listener (more reliable)
//     */
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    private fun checkBluetoothConnectionWithProfile(device: BluetoothDevice, callback: (Boolean) -> Unit) {
//        val profileListener = object : BluetoothProfile.ServiceListener {
//            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
//                try {
//                    val isConnected = when (profile) {
//                        BluetoothProfile.HEADSET -> {
//                            val headsetProxy = proxy as? BluetoothHeadset
//                            headsetProxy?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
//                        }
//                        BluetoothProfile.A2DP -> {
//                            val a2dpProxy = proxy as? BluetoothA2dp
//                            a2dpProxy?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
//                        }
//                        else -> false
//                    }
//                    callback(isConnected)
//                    bluetoothAdapter?.closeProfileProxy(profile, proxy)
//                } catch (e: Exception) {
//                    log.e(TAG) { "Error in profile service connected: ${e.message}" }
//                    callback(false)
//                }
//            }
//
//            override fun onServiceDisconnected(profile: Int) {
//                callback(false)
//            }
//        }
//
//        // Try to get headset profile first, then A2DP
//        val headsetConnected = bluetoothAdapter?.getProfileProxy(
//            context,
//            profileListener,
//            BluetoothProfile.HEADSET
//        ) ?: false
//
//        if (!headsetConnected) {
//            bluetoothAdapter?.getProfileProxy(
//                context,
//                profileListener,
//                BluetoothProfile.A2DP
//            )
//        }
//    }
//
//    /**
//     * Fallback method using reflection (use with caution)
//     */
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    private fun checkBluetoothConnectionViaReflection(device: BluetoothDevice): Boolean {
//        return try {
//            val method = device.javaClass.getMethod("isConnected")
//            method.invoke(device) as Boolean
//        } catch (e: Exception) {
//            log.w(TAG) { "Reflection method failed: ${e.message}" }
//            // Final fallback
//            device.bondState == BluetoothDevice.BOND_BONDED
//        }
//    }
//
//    /**
//     * Enhanced method to get all connected Bluetooth devices with proper state checking
//     */
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    private fun getConnectedBluetoothDevicesEnhanced(): List<BluetoothDevice> {
//        val connectedDevices = mutableListOf<BluetoothDevice>()
//
//        try {
//            // Get bonded devices first
//            val bondedDevices = bluetoothAdapter?.bondedDevices ?: emptySet()
//
//            // Filter for audio-capable devices
//            val audioDevices = bondedDevices.filter { device ->
//                device.bluetoothClass?.hasService(BluetoothClass.Service.AUDIO) == true ||
//                        device.bluetoothClass?.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES ||
//                        device.bluetoothClass?.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET ||
//                        device.bluetoothClass?.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE ||
//                        device.bluetoothClass?.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO
//            }
//
//            // Check connection state for each audio device
//            audioDevices.forEach { device ->
//                if (isBluetoothDeviceConnected(device)) {
//                    connectedDevices.add(device)
//                }
//            }
//
//            log.d(TAG) { "Found ${connectedDevices.size} connected Bluetooth audio devices" }
//
//        } catch (e: SecurityException) {
//            log.w(TAG) { "Bluetooth permission not granted for getting connected devices" }
//        } catch (e: Exception) {
//            log.e(TAG) { "Error getting connected Bluetooth devices: ${e.message}" }
//        }
//
//        return connectedDevices
//    }
//
//    /**
//     * Simplified synchronous check for immediate use
//     */
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    private fun isBluetoothAudioDeviceConnected(): Boolean {
//        return try {
//            // Quick check using AudioManager
//            audioManager?.isBluetoothScoOn == true ||
//                    audioManager?.isBluetoothA2dpOn == true ||
//                    (audioManager?.isBluetoothScoAvailableOffCall == true && hasConnectedBluetoothAudioDevice())
//        } catch (e: Exception) {
//            log.w(TAG) { "Error checking Bluetooth audio connection: ${e.message}" }
//            false
//        }
//    }
//
//    /**
//     * Check if there's any connected Bluetooth audio device
//     */
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    private fun hasConnectedBluetoothAudioDevice(): Boolean {
//        return try {
//            bluetoothAdapter?.bondedDevices?.any { device ->
//                device.bluetoothClass?.hasService(BluetoothClass.Service.AUDIO) == true &&
//                        device.bondState == BluetoothDevice.BOND_BONDED
//            } ?: false
//        } catch (e: Exception) {
//            false
//        }
//    }
//
//    /**
//     * Get Bluetooth device name safely
//     */
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    private fun getBluetoothDeviceName(device: BluetoothDevice): String {
//        return try {
//            device.name ?: "Bluetooth Device"
//        } catch (e: SecurityException) {
//            "Bluetooth Device"
//        }
//    }
//
//    /**
//     * Classify Bluetooth device type and capabilities
//     */
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    private fun classifyBluetoothDevice(device: BluetoothDevice): Pair<AudioUnitTypes, Boolean> {
//        return try {
//            val deviceClass = device.bluetoothClass
//            val majorClass = deviceClass?.majorDeviceClass
//            val deviceType = deviceClass?.deviceClass
//
//            val supportsA2DP = hasBluetoothProfile(device, BluetoothProfile.A2DP)
//            val supportsHFP = hasBluetoothProfile(device, BluetoothProfile.HEADSET)
//
//            val audioUnitType = when {
//                supportsA2DP && supportsHFP -> AudioUnitTypes.BLUETOOTH
//                supportsA2DP -> AudioUnitTypes.BLUETOOTHA2DP
//                supportsHFP -> AudioUnitTypes.BLUETOOTH
//                majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO -> {
//                    if (supportsA2DP) AudioUnitTypes.BLUETOOTHA2DP else AudioUnitTypes.BLUETOOTH
//                }
//                else -> AudioUnitTypes.BLUETOOTH
//            }
//
//            Pair(audioUnitType, supportsA2DP)
//        } catch (e: Exception) {
//            log.w(TAG) { "Error classifying Bluetooth device: ${e.message}" }
//            Pair(AudioUnitTypes.BLUETOOTH, false)
//        }
//    }
//
//    /**
//     * Check if device supports hands-free profile
//     */
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    private fun supportsHandsFreeProfile(device: BluetoothDevice): Boolean {
//        return hasBluetoothProfile(device, BluetoothProfile.HEADSET) ||
//                hasBluetoothProfile(device, BluetoothProfile.HID_DEVICE)
//    }
//
//    /**
//     * Check if device supports specific Bluetooth profile
//     */
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    private fun hasBluetoothProfile(device: BluetoothDevice, profile: Int): Boolean {
//        return try {
//            bluetoothAdapter?.getProfileConnectionState(profile) == BluetoothAdapter.STATE_CONNECTED
//        } catch (e: Exception) {
//            false
//        }
//    }
//
//    /**
//     * Get Bluetooth connection state
//     */
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    private fun getBluetoothConnectionState(device: BluetoothDevice): DeviceConnectionState {
//        return if (isBluetoothDeviceConnected(device)) {
//            DeviceConnectionState.CONNECTED
//        } else {
//            DeviceConnectionState.AVAILABLE
//        }
//    }
//
//    /**
//     * Estimate Bluetooth signal strength
//     */
//    private fun estimateBluetoothSignalStrength(): Int {
//        // In a real implementation, you might use RSSI values if available
//        return (70..95).random()
//    }
//
//    /**
//     * Get Bluetooth device battery level using reflection
//     */
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    private fun getBluetoothBatteryLevel(device: BluetoothDevice): Int? {
//        return try {
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//                // Android 13+ - Try to use reflection to access hidden getBatteryLevel method
//                val getBatteryLevelMethod = BluetoothDevice::class.java.getMethod("getBatteryLevel")
//                val batteryLevel = getBatteryLevelMethod.invoke(device) as? Int
//
//                // Check if battery level is valid (not -1 which typically means unknown)
//                batteryLevel?.takeIf { it >= 0 && it <= 100 }
//            } else {
//                null
//            }
//        } catch (e: Exception) {
//            log.w(TAG) { "Could not get battery level via reflection: ${e.message}" }
//            null
//        }
//    }
//
//    /**
//     * Extract vendor information from wired device
//     */
//    private fun extractVendorFromWiredDevice(): String? {
//        // This could be enhanced with more sophisticated detection
//        return null
//    }
//
//    /**
//     * Extract vendor from Bluetooth device
//     */
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    private fun extractVendorFromBluetoothDevice(device: BluetoothDevice): String? {
//        return try {
//            val deviceName = device.name?.lowercase() ?: return null
//            extractVendorFromDeviceName(deviceName)
//        } catch (e: SecurityException) {
//            null
//        }
//    }
//
//    /**
//     * Extract vendor information from device name
//     */
//    private fun extractVendorFromDeviceName(deviceName: String): String? {
//        val name = deviceName.lowercase()
//        return when {
//            name.contains("apple") || name.contains("airpods") -> "Apple"
//            name.contains("samsung") -> "Samsung"
//            name.contains("sony") -> "Sony"
//            name.contains("bose") -> "Bose"
//            name.contains("jabra") -> "Jabra"
//            name.contains("plantronics") -> "Plantronics"
//            name.contains("logitech") -> "Logitech"
//            name.contains("sennheiser") -> "Sennheiser"
//            name.contains("jbl") -> "JBL"
//            name.contains("beats") -> "Beats"
//            name.contains("google") -> "Google"
//            name.contains("microsoft") -> "Microsoft"
//            name.contains("razer") -> "Razer"
//            name.contains("steelseries") -> "SteelSeries"
//            else -> null
//        }
//    }
//
//    /**
//     * FIXED: Enhanced device change during call with improved error handling
//     */
//    override fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean {
//        log.d(TAG) { "Changing audio output to: ${device.name} (${device.descriptor})" }
//
//        if (!isInitialized || peerConnection == null) {
//            log.w(TAG) { "Cannot change audio output: WebRTC not initialized" }
//            return false
//        }
//
//        return try {
//            val am = audioManager ?: return false
//
//            // FIXED: Stop any previous Bluetooth SCO if switching to non-Bluetooth device
//            if (!device.descriptor.startsWith("bluetooth_") && am.isBluetoothScoOn) {
//                log.d(TAG) { "Stopping Bluetooth SCO for non-Bluetooth device" }
//                am.stopBluetoothSco()
//                isBluetoothScoRequested = false
//            }
//
//            val success = when {
//                device.descriptor.startsWith("bluetooth_") -> {
//                    switchToBluetoothOutput(device, am)
//                }
//                device.descriptor == "speaker" -> {
//                    switchToSpeaker(am)
//                }
//                device.descriptor == "wired_headset" -> {
//                    switchToWiredHeadset(am)
//                }
//                device.descriptor == "earpiece" -> {
//                    switchToEarpiece(am)
//                }
//                device.descriptor.startsWith("usb_out_") -> {
//                    switchToUsbOutput(device, am)
//                }
//                else -> {
//                    log.w(TAG) { "Unknown audio device type: ${device.descriptor}" }
//                    false
//                }
//            }
//
//            if (success) {
//                currentOutputDevice = device
//                log.d(TAG) { "Successfully changed audio output to: ${device.name}" }
//
//                // FIXED: Notify only after connection is established
//                if (device.descriptor.startsWith("bluetooth_")) {
//                    // For Bluetooth, wait for SCO connection to be established
//                    coroutineScope.launch {
//                        var attempts = 0
//                        while (attempts < 10 && !am.isBluetoothScoOn) {
//                            delay(200)
//                            attempts++
//                        }
//                        if (am.isBluetoothScoOn) {
//                            webRtcEventListener?.onAudioDeviceChanged(device)
//                        }
//                    }
//                } else {
//                    webRtcEventListener?.onAudioDeviceChanged(device)
//                }
//            }
//
//            success
//        } catch (e: Exception) {
//            log.e(TAG) { "Error changing audio output: ${e.message}" }
//            false
//        }
//    }
//
//    /**
//     * Enhanced device change for input during call
//     */
//    override fun changeAudioInputDeviceDuringCall(device: AudioDevice): Boolean {
//        log.d(TAG) { "Changing audio input to: ${device.name} (${device.descriptor})" }
//
//        if (!isInitialized || peerConnection == null) {
//            log.w(TAG) { "Cannot change audio input: WebRTC not initialized" }
//            return false
//        }
//
//        return try {
//            val am = audioManager ?: return false
//            val success = when {
//                device.descriptor.startsWith("bluetooth_mic_") -> {
//                    switchToBluetoothInput(device, am)
//                }
//                device.descriptor == "wired_headset_mic" -> {
//                    switchToWiredHeadsetMic(am)
//                }
//                device.descriptor == "builtin_mic" -> {
//                    switchToBuiltinMic(am)
//                }
//                device.descriptor.startsWith("usb_in_") -> {
//                    switchToUsbInput(device, am)
//                }
//                else -> {
//                    log.w(TAG) { "Unknown audio input device: ${device.descriptor}" }
//                    false
//                }
//            }
//
//            if (success) {
//                currentInputDevice = device
//                webRtcEventListener?.onAudioDeviceChanged(device)
//                log.d(TAG) { "Successfully changed audio input to: ${device.name}" }
//            }
//
//            success
//        } catch (e: Exception) {
//            log.e(TAG) { "Error changing audio input: ${e.message}" }
//            false
//        }
//    }
//
//    // Audio switching methods
//
//    /**
//     * FIXED: Enhanced Bluetooth output switching with proper SCO handling
//     */
//    private fun switchToBluetoothOutput(device: AudioDevice, am: AudioManager): Boolean {
//        return try {
//            log.d(TAG) { "Switching to Bluetooth output: ${device.name}" }
//
//            // Verify if Bluetooth SCO is available
//            if (!am.isBluetoothScoAvailableOffCall) {
//                log.w(TAG) { "Bluetooth SCO not available for off-call use" }
//                return false
//            }
//
//            // Check permissions
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//                if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
//                    log.w(TAG) { "BLUETOOTH_CONNECT permission not granted" }
//                    return false
//                }
//            }
//
//            // FIXED: Proper sequence for Bluetooth audio routing
//
//            // 1. First, stop other audio modes
//            am.isSpeakerphoneOn = false
//
//            // 2. Ensure we're in communication mode
//            am.mode = AudioManager.MODE_IN_COMMUNICATION
//
//            // 3. Check if SCO is already connected
//            if (am.isBluetoothScoOn) {
//                log.d(TAG) { "Bluetooth SCO already connected" }
//                return true
//            }
//
//            // 4. Verify that the specific Bluetooth device is connected
//            val bluetoothDevice = device.nativeDevice as? BluetoothDevice
//            if (bluetoothDevice != null && !isBluetoothDeviceConnected(bluetoothDevice)) {
//                log.w(TAG) { "Bluetooth device not connected: ${device.name}" }
//                return false
//            }
//
//            // 5. Start Bluetooth SCO if not already requested
//            if (!isBluetoothScoRequested && !am.isBluetoothScoOn) {
//                log.d(TAG) { "Starting Bluetooth SCO..." }
//                isBluetoothScoRequested = true
//                am.startBluetoothSco()
//
//                // FIXED: Wait a bit for SCO connection to establish
//                // In a real environment, this should be handled asynchronously
//                coroutineScope.launch {
//                    delay(1000) // Wait 1 second
//                    if (!am.isBluetoothScoOn) {
//                        log.w(TAG) { "Bluetooth SCO failed to connect after timeout" }
//                        isBluetoothScoRequested = false
//                    }
//                }
//            }
//
//            true
//        } catch (e: SecurityException) {
//            log.e(TAG) { "Security error switching to Bluetooth: ${e.message}" }
//            false
//        } catch (e: Exception) {
//            log.e(TAG) { "Error switching to Bluetooth output: ${e.message}" }
//            false
//        }
//    }
//
//    /**
//     * FIXED: Enhanced speaker switching
//     */
//    private fun switchToSpeaker(am: AudioManager): Boolean {
//        return try {
//            // FIXED: Stop Bluetooth SCO if active
//            if (am.isBluetoothScoOn) {
//                log.d(TAG) { "Stopping Bluetooth SCO for speaker" }
//                am.stopBluetoothSco()
//                isBluetoothScoRequested = false
//            }
//
//            // Enable speaker
//            am.isSpeakerphoneOn = true
//            am.mode = AudioManager.MODE_IN_COMMUNICATION
//
//            log.d(TAG) { "Switched to speaker successfully" }
//            true
//        } catch (e: Exception) {
//            log.e(TAG) { "Error switching to speaker: ${e.message}" }
//            false
//        }
//    }
//
//    /**
//     * FIXED: Enhanced wired headset switching
//     */
//    private fun switchToWiredHeadset(am: AudioManager): Boolean {
//        return try {
//            // FIXED: Stop other audio modes
//            am.isSpeakerphoneOn = false
//            if (am.isBluetoothScoOn) {
//                log.d(TAG) { "Stopping Bluetooth SCO for wired headset" }
//                am.stopBluetoothSco()
//                isBluetoothScoRequested = false
//            }
//
//            am.mode = AudioManager.MODE_IN_COMMUNICATION
//            log.d(TAG) { "Switched to wired headset successfully" }
//            true
//        } catch (e: Exception) {
//            log.e(TAG) { "Error switching to wired headset: ${e.message}" }
//            false
//        }
//    }
//
//    /**
//     * FIXED: Enhanced earpiece switching
//     */
//    private fun switchToEarpiece(am: AudioManager): Boolean {
//        return try {
//            // FIXED: Reset all other output modes
//            am.isSpeakerphoneOn = false
//            if (am.isBluetoothScoOn) {
//                log.d(TAG) { "Stopping Bluetooth SCO for earpiece" }
//                am.stopBluetoothSco()
//                isBluetoothScoRequested = false
//            }
//
//            am.mode = AudioManager.MODE_IN_COMMUNICATION
//            log.d(TAG) { "Switched to earpiece successfully" }
//            true
//        } catch (e: Exception) {
//            log.e(TAG) { "Error switching to earpiece: ${e.message}" }
//            false
//        }
//    }
//
//    private fun switchToUsbOutput(device: AudioDevice, am: AudioManager): Boolean {
//        return try {
//            // For USB devices, we might need to use AudioDeviceInfo routing (API 23+)
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && device.nativeDevice is AudioDeviceInfo) {
//                // Reset other outputs first
//                am.isSpeakerphoneOn = false
//                if (am.isBluetoothScoOn) {
//                    am.stopBluetoothSco()
//                    am.isBluetoothScoOn = false
//                }
//                // USB routing is typically handled automatically by the system
//                true
//            } else {
//                false
//            }
//        } catch (e: Exception) {
//            log.e(TAG) { "Error switching to USB output: ${e.message}" }
//            false
//        }
//    }
//
//    /**
//     * FIXED: Enhanced Bluetooth input switching
//     */
//    private fun switchToBluetoothInput(device: AudioDevice, am: AudioManager): Boolean {
//        return try {
//            log.d(TAG) { "Switching to Bluetooth input: ${device.name}" }
//
//            // Verify Bluetooth SCO availability
//            if (!am.isBluetoothScoAvailableOffCall) {
//                log.w(TAG) { "Bluetooth SCO not available for input" }
//                return false
//            }
//
//            // Check permissions
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//                if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
//                    log.w(TAG) { "BLUETOOTH_CONNECT permission not granted" }
//                    return false
//                }
//            }
//
//            // FIXED: Proper Bluetooth input routing
//            am.mode = AudioManager.MODE_IN_COMMUNICATION
//
//            if (!am.isBluetoothScoOn && !isBluetoothScoRequested) {
//                log.d(TAG) { "Starting Bluetooth SCO for input..." }
//                isBluetoothScoRequested = true
//                am.startBluetoothSco()
//
//                coroutineScope.launch {
//                    delay(1000)
//                    if (!am.isBluetoothScoOn) {
//                        log.w(TAG) { "Bluetooth SCO failed to connect for input" }
//                        isBluetoothScoRequested = false
//                    }
//                }
//            }
//
//            true
//        } catch (e: Exception) {
//            log.e(TAG) { "Error switching to Bluetooth input: ${e.message}" }
//            false
//        }
//    }
//
//    private fun switchToWiredHeadsetMic(am: AudioManager): Boolean {
//        return try {
//            if (am.isBluetoothScoOn) {
//                am.stopBluetoothSco()
//                am.isBluetoothScoOn = false
//            }
//            true
//        } catch (e: Exception) {
//            log.e(TAG) { "Error switching to wired headset mic: ${e.message}" }
//            false
//        }
//    }
//
//    private fun switchToBuiltinMic(am: AudioManager): Boolean {
//        return try {
//            if (am.isBluetoothScoOn) {
//                am.stopBluetoothSco()
//                am.isBluetoothScoOn = false
//            }
//            true
//        } catch (e: Exception) {
//            log.e(TAG) { "Error switching to builtin mic: ${e.message}" }
//            false
//        }
//    }
//
//    private fun switchToUsbInput(device: AudioDevice, am: AudioManager): Boolean {
//        return try {
//            // Similar to USB output, routing is typically automatic
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && device.nativeDevice is AudioDeviceInfo) {
//                if (am.isBluetoothScoOn) {
//                    am.stopBluetoothSco()
//                    am.isBluetoothScoOn = false
//                }
//                true
//            } else {
//                false
//            }
//        } catch (e: Exception) {
//            log.e(TAG) { "Error switching to USB input: ${e.message}" }
//            false
//        }
//    }
//
//    /**
//     * Enhanced current device detection
//     */
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    override fun getCurrentInputDevice(): AudioDevice? {
//        if (currentInputDevice != null) {
//            return currentInputDevice
//        }
//
//        return try {
//            val am = audioManager ?: return null
//            val descriptor = getCurrentAudioInputDescriptor()
//
//            when {
//                descriptor?.startsWith("bluetooth_mic_") == true -> {
//                    findBluetoothInputDevice()
//                }
//                descriptor == "wired_headset_mic" -> {
//                    createWiredHeadsetMicDevice()
//                }
//                else -> {
//                    createBuiltinMicDevice()
//                }
//            }.also {
//                currentInputDevice = it
//            }
//        } catch (e: Exception) {
//            log.e(TAG) { "Error getting current input device: ${e.message}" }
//            createBuiltinMicDevice()
//        }
//    }
//
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    override fun getCurrentOutputDevice(): AudioDevice? {
//        if (currentOutputDevice != null) {
//            return currentOutputDevice
//        }
//
//        return try {
//            val am = audioManager ?: return null
//            val descriptor = getCurrentAudioOutputDescriptor()
//
//            when {
//                descriptor?.startsWith("bluetooth_") == true -> {
//                    findBluetoothOutputDevice()
//                }
//                descriptor == "speaker" -> {
//                    createSpeakerDevice()
//                }
//                descriptor == "wired_headset" -> {
//                    createWiredHeadsetDevice()
//                }
//                descriptor == "earpiece" -> {
//                    createEarpieceDevice()
//                }
//                else -> {
//                    if (am.hasEarpiece()) createEarpieceDevice() else createSpeakerDevice()
//                }
//            }.also {
//                currentOutputDevice = it
//            }
//        } catch (e: Exception) {
//            log.e(TAG) { "Error getting current output device: ${e.message}" }
//            if (audioManager?.hasEarpiece() == true) createEarpieceDevice() else createSpeakerDevice()
//        }
//    }
//
//    // Helper methods for creating device objects
//
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    private fun findBluetoothInputDevice(): AudioDevice? {
//        val connectedDevices = getConnectedBluetoothDevices()
//        val firstDevice = connectedDevices.firstOrNull() ?: return null
//
//        return AudioDevice(
//            name = "${getBluetoothDeviceName(firstDevice)} Microphone",
//            descriptor = "bluetooth_mic_${firstDevice.address}",
//            nativeDevice = firstDevice,
//            isOutput = false,
//            audioUnit = AudioUnit(
//                type = AudioUnitTypes.BLUETOOTH,
//                capability = AudioUnitCompatibilities.RECORD,
//                isCurrent = true,
//                isDefault = false
//            ),
//            connectionState = DeviceConnectionState.CONNECTED,
//            signalStrength = estimateBluetoothSignalStrength(),
//            batteryLevel = getBluetoothBatteryLevel(firstDevice),
//            isWireless = true,
//            supportsHDVoice = false,
//            latency = 150,
//            vendorInfo = extractVendorFromBluetoothDevice(firstDevice)
//        )
//    }
//
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    private fun findBluetoothOutputDevice(): AudioDevice? {
//        val connectedDevices = getConnectedBluetoothDevices()
//        val firstDevice = connectedDevices.firstOrNull() ?: return null
//
//        val (audioUnitType, supportsA2DP) = classifyBluetoothDevice(firstDevice)
//
//        return AudioDevice(
//            name = getBluetoothDeviceName(firstDevice),
//            descriptor = "bluetooth_${firstDevice.address}",
//            nativeDevice = firstDevice,
//            isOutput = true,
//            audioUnit = AudioUnit(
//                type = audioUnitType,
//                capability = AudioUnitCompatibilities.PLAY,
//                isCurrent = true,
//                isDefault = false
//            ),
//            connectionState = DeviceConnectionState.CONNECTED,
//            signalStrength = estimateBluetoothSignalStrength(),
//            batteryLevel = getBluetoothBatteryLevel(firstDevice),
//            isWireless = true,
//            supportsHDVoice = supportsA2DP,
//            latency = if (supportsA2DP) 200 else 150,
//            vendorInfo = extractVendorFromBluetoothDevice(firstDevice)
//        )
//    }
//
//    private fun createWiredHeadsetMicDevice(): AudioDevice {
//        return AudioDevice(
//            name = "Wired Headset Microphone",
//            descriptor = "wired_headset_mic",
//            nativeDevice = null,
//            isOutput = false,
//            audioUnit = AudioUnit(
//                type = AudioUnitTypes.HEADSET,
//                capability = AudioUnitCompatibilities.RECORD,
//                isCurrent = true,
//                isDefault = false
//            ),
//            connectionState = DeviceConnectionState.CONNECTED,
//            isWireless = false,
//            supportsHDVoice = true,
//            latency = 20,
//            vendorInfo = extractVendorFromWiredDevice()
//        )
//    }
//
//    private fun createBuiltinMicDevice(): AudioDevice {
//        return AudioDevice(
//            name = "Built-in Microphone",
//            descriptor = "builtin_mic",
//            nativeDevice = null,
//            isOutput = false,
//            audioUnit = AudioUnit(
//                type = AudioUnitTypes.MICROPHONE,
//                capability = AudioUnitCompatibilities.RECORD,
//                isCurrent = true,
//                isDefault = true
//            ),
//            connectionState = DeviceConnectionState.AVAILABLE,
//            isWireless = false,
//            supportsHDVoice = true,
//            latency = 10,
//            vendorInfo = "Built-in"
//        )
//    }
//
//    private fun createWiredHeadsetDevice(): AudioDevice {
//        return AudioDevice(
//            name = "Wired Headset",
//            descriptor = "wired_headset",
//            nativeDevice = null,
//            isOutput = true,
//            audioUnit = AudioUnit(
//                type = AudioUnitTypes.HEADSET,
//                capability = AudioUnitCompatibilities.PLAY,
//                isCurrent = true,
//                isDefault = false
//            ),
//            connectionState = DeviceConnectionState.CONNECTED,
//            isWireless = false,
//            supportsHDVoice = true,
//            latency = 20,
//            vendorInfo = extractVendorFromWiredDevice()
//        )
//    }
//
//    private fun createSpeakerDevice(): AudioDevice {
//        return AudioDevice(
//            name = "Speaker",
//            descriptor = "speaker",
//            nativeDevice = null,
//            isOutput = true,
//            audioUnit = AudioUnit(
//                type = AudioUnitTypes.SPEAKER,
//                capability = AudioUnitCompatibilities.PLAY,
//                isCurrent = true,
//                isDefault = !audioManager?.hasEarpiece()!!
//            ),
//            connectionState = DeviceConnectionState.AVAILABLE,
//            isWireless = false,
//            supportsHDVoice = true,
//            latency = 15,
//            vendorInfo = "Built-in"
//        )
//    }
//
//    private fun createEarpieceDevice(): AudioDevice {
//        return AudioDevice(
//            name = "Earpiece",
//            descriptor = "earpiece",
//            nativeDevice = null,
//            isOutput = true,
//            audioUnit = AudioUnit(
//                type = AudioUnitTypes.EARPIECE,
//                capability = AudioUnitCompatibilities.PLAY,
//                isCurrent = true,
//                isDefault = true
//            ),
//            connectionState = DeviceConnectionState.AVAILABLE,
//            isWireless = false,
//            supportsHDVoice = true,
//            latency = 5,
//            vendorInfo = "Built-in"
//        )
//    }
//
//    /**
//     * FIXED: Enhanced cleanup with Bluetooth SCO handling
//     */
//    override fun dispose() {
//        log.d(tag = TAG) { "Disposing WebRTC" }
//
//        try {
//            // Stop translation audio processing
//            audioProcessor?.dispose()
//            audioProcessor = null
//
//            // Close peer connection
//            peerConnection?.close()
//            peerConnection = null
//
//            // Dispose audio tracks
//            localAudioTrack?.dispose()
//            localAudioTrack = null
//
//            remoteAudioTrack?.dispose()
//            remoteAudioTrack = null
//
//            audioSource?.dispose()
//            audioSource = null
//
//            // Dispose factory
//            peerConnectionFactory?.dispose()
//            peerConnectionFactory = null
//
//            // Cancel coroutines
//            coroutineScope.cancel()
//
//            isInitialized = false
//            log.d(tag = TAG) { "WebRTC disposed successfully" }
//
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error disposing WebRTC: ${e.message}" }
//        }
//    }
//    private fun addLocalAudioTrack() {
//        try {
//            if (localAudioTrack != null) {
//                log.d(tag = TAG) { "Local audio track already exists" }
//                return
//            }
//
//            log.d(tag = TAG) { "Adding local audio track" }
//
//            // Create audio source
//            val audioConstraints = MediaConstraints().apply {
//                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
//                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
//                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
//            }
//
//            audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
//            localAudioTrack = peerConnectionFactory?.createAudioTrack("audio_track", audioSource)
//
//            // Add track to peer connection
//            val sender = peerConnection?.addTrack(localAudioTrack, listOf("stream"))
//
//            // Setup DTMF sender
//            dtmfSender = sender?.dtmf()
//
//            log.d(tag = TAG) { "Local audio track added successfully" }
//
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error adding local audio track: ${e.message}" }
//            throw e
//        }
//    }
//
//    override suspend fun createOffer(): String = withContext(Dispatchers.IO) {
//        checkInitialized()
//
//        try {
//            log.d(tag = TAG) { "Creating offer" }
//
//            // Create peer connection if not exists
//            if (peerConnection == null) {
//                createPeerConnection()
//            }
//
//            // Add local audio track
//            addLocalAudioTrack()
//
//            // Create offer
//            val constraints = MediaConstraints().apply {
//                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
//                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
//            }
//
//            val offer = suspendCancellableCoroutine<SessionDescription> { continuation ->
//                peerConnection?.createOffer(object : SdpObserver {
//                    override fun onCreateSuccess(sessionDescription: SessionDescription) {
//                        continuation.resume(sessionDescription, null)
//                    }
//
//                    override fun onCreateFailure(error: String) {
//                        continuation.resumeWithException(Exception("Failed to create offer: $error"))
//                    }
//
//                    override fun onSetSuccess() {}
//                    override fun onSetFailure(error: String) {}
//                }, constraints)
//            }
//
//            // Set local description
//            suspendCancellableCoroutine<Unit> { continuation ->
//                peerConnection?.setLocalDescription(object : SdpObserver {
//                    override fun onSetSuccess() {
//                        continuation.resume(Unit, null)
//                    }
//
//                    override fun onSetFailure(error: String) {
//                        continuation.resumeWithException(Exception("Failed to set local description: $error"))
//                    }
//
//                    override fun onCreateSuccess(sessionDescription: SessionDescription) {}
//                    override fun onCreateFailure(error: String) {}
//                }, offer)
//            }
//
//            log.d(tag = TAG) { "Offer created successfully" }
//            return@withContext offer.description
//
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error creating offer: ${e.message}" }
//            throw e
//        }
//    }
//    private fun checkInitialized() {
//        if (!isInitialized) {
//            throw IllegalStateException("WebRTC not initialized. Call initialize() first.")
//        }
//    }
//    /**
//     * Create an SDP answer in response to an offer
//     * @param accountInfo The current account information
//     * @param offerSdp The SDP offer from the remote party
//     * @return The SDP answer string
//     */
//    @SuppressLint("MissingPermission")
//    override suspend fun createAnswer(accountInfo: AccountInfo, offerSdp: String): String = withContext(Dispatchers.IO) @androidx.annotation.RequiresPermission(
//        android.Manifest.permission.RECORD_AUDIO
//    ) {
//        checkInitialized()
//
//        try {
//            log.d(tag = TAG) { "Creating answer for offer" }
//
//            // Create peer connection if not exists
//            if (peerConnection == null) {
//                createPeerConnection()
//            }
//
//            // Set remote description (offer)
//            setRemoteDescription(offerSdp, SdpType.OFFER)
//
//            // Add local audio track
//            addLocalAudioTrack()
//
//            // Create answer
//            val constraints = MediaConstraints().apply {
//                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
//                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
//            }
//
//            val answer = suspendCancellableCoroutine<SessionDescription> { continuation ->
//                peerConnection?.createAnswer(object : SdpObserver {
//                    override fun onCreateSuccess(sessionDescription: SessionDescription) {
//                        continuation.resume(sessionDescription, null)
//                    }
//
//                    override fun onCreateFailure(error: String) {
//                        continuation.resumeWithException(Exception("Failed to create answer: $error"))
//                    }
//
//                    override fun onSetSuccess() {}
//                    override fun onSetFailure(error: String) {}
//                }, constraints)
//            }
//
//            // Set local description
//            suspendCancellableCoroutine<Unit> { continuation ->
//                peerConnection?.setLocalDescription(object : SdpObserver {
//                    override fun onSetSuccess() {
//                        continuation.resume(Unit, null)
//                    }
//
//                    override fun onSetFailure(error: String) {
//                        continuation.resumeWithException(Exception("Failed to set local description: $error"))
//                    }
//
//                    override fun onCreateSuccess(sessionDescription: SessionDescription) {}
//                    override fun onCreateFailure(error: String) {}
//                }, answer)
//            }
//
//            log.d(tag = TAG) { "Answer created successfully" }
//            return@withContext answer.description
//
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error creating answer: ${e.message}" }
//            throw e
//        }
//    }
//    private fun createPeerConnection() {
//        try {
//            log.d(tag = TAG) { "Creating peer connection" }
//
//            val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
//                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
//                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
//                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
//                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
//            }
//
//            peerConnection = peerConnectionFactory?.createPeerConnection(
//                rtcConfig,
//                object : PeerConnection.Observer {
//                    override fun onIceCandidate(iceCandidate: IceCandidate) {
//                        listener?.onIceCandidate(
//                            iceCandidate.sdp,
//                            iceCandidate.sdpMid,
//                            iceCandidate.sdpMLineIndex
//                        )
//                    }
//
//                    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate?>?) {
//                        TODO("Not yet implemented")
//                    }
//
//                    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
//                        connectionState = when (newState) {
//                            PeerConnection.PeerConnectionState.NEW -> WebRtcConnectionState.NEW
//                            PeerConnection.PeerConnectionState.CONNECTING -> WebRtcConnectionState.CONNECTING
//                            PeerConnection.PeerConnectionState.CONNECTED -> WebRtcConnectionState.CONNECTED
//                            PeerConnection.PeerConnectionState.DISCONNECTED -> WebRtcConnectionState.DISCONNECTED
//                            PeerConnection.PeerConnectionState.FAILED -> WebRtcConnectionState.FAILED
//                            PeerConnection.PeerConnectionState.CLOSED -> WebRtcConnectionState.CLOSED
//                        }
//                        listener?.onConnectionStateChange(connectionState)
//                    }
//
//                    override fun onAddTrack(rtpReceiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
//                        val track = rtpReceiver.track()
//                        if (track is AudioTrack) {
//                            remoteAudioTrack = track
//                            listener?.onRemoteAudioTrack()
//                        }
//                    }
//
//                    override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {}
//                    override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState) {}
//                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
//                    override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState) {}
//                    override fun onAddStream(mediaStream: MediaStream) {}
//                    override fun onRemoveStream(mediaStream: MediaStream) {}
//                    override fun onDataChannel(dataChannel: DataChannel) {}
//                    override fun onRenegotiationNeeded() {}
//                    override fun onRemoveTrack(rtpReceiver: RtpReceiver) {}
//                }
//            )
//
//            log.d(tag = TAG) { "Peer connection created successfully" }
//
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error creating peer connection: ${e.message}" }
//            throw e
//        }
//    }
//
//    /**
//     * Set the remote description (offer or answer)
//     * @param sdp The remote SDP string
//     * @param type The SDP type (offer or answer)
//     */
//    override suspend fun setRemoteDescription(sdp: String, type: SdpType) = withContext(Dispatchers.IO) {
//        checkInitialized()
//
//        try {
//            log.d(tag = TAG) { "Setting remote description: $type" }
//
//            val sessionDescription = SessionDescription(
//                if (type == SdpType.OFFER) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER,
//                sdp
//            )
//
//            suspendCancellableCoroutine<Unit> { continuation ->
//                peerConnection?.setRemoteDescription(object : SdpObserver {
//                    override fun onSetSuccess() {
//                        continuation.resume(Unit, null)
//                    }
//
//                    override fun onSetFailure(error: String) {
//                        continuation.resumeWithException(Exception("Failed to set remote description: $error"))
//                    }
//
//                    override fun onCreateSuccess(sessionDescription: SessionDescription) {}
//                    override fun onCreateFailure(error: String) {}
//                }, sessionDescription)
//            }
//
//            log.d(tag = TAG) { "Remote description set successfully" }
//
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error setting remote description: ${e.message}" }
//            throw e
//        }
//    }
//
//
//    /**
//     * Add an ICE candidate received from the remote party
//     * @param candidate The ICE candidate string
//     * @param sdpMid The media ID
//     * @param sdpMLineIndex The media line index
//     */
//    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
//    override suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
//        checkInitialized()
//
//        try {
//            val iceCandidate = IceCandidate(sdpMid ?: "", sdpMLineIndex ?: 0, candidate)
//            peerConnection?.addIceCandidate(iceCandidate)
//            log.d(tag = TAG) { "ICE candidate added" }
//
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error adding ICE candidate: ${e.message}" }
//        }
//    }
//
//    override fun setMuted(muted: Boolean) {
//        try {
//            isMuted = muted
//            localAudioTrack?.setEnabled(!muted)
//            log.d(tag = TAG) { "Muted: $muted" }
//
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error setting muted: ${e.message}" }
//        }
//    }
//
//    override fun isMuted(): Boolean = isMuted
//
//    override fun getLocalDescription(): String? {
//        return peerConnection?.localDescription?.description
//    }
//
//    /**
//     * Sets the media direction (sendrecv, sendonly, recvonly, inactive)
//     * @param direction The desired media direction
//     */
//    override suspend fun setMediaDirection(direction: WebRtcManager.MediaDirection) {
//        // Implementation for media direction control
//        log.d(tag = TAG) { "Setting media direction: $direction" }
//    }
//
//    /**
//     * Modifies the SDP to update the media direction attribute
//     */
//    private fun updateSdpDirection(sdp: String, direction: WebRtcManager.MediaDirection): String {
//        val directionStr = when (direction) {
//            WebRtcManager.MediaDirection.SENDRECV -> "sendrecv"
//            WebRtcManager.MediaDirection.SENDONLY -> "sendonly"
//            WebRtcManager.MediaDirection.RECVONLY -> "recvonly"
//            WebRtcManager.MediaDirection.INACTIVE -> "inactive"
//        }
//
//        val lines = sdp.lines().toMutableList()
//        var inMediaSection = false
//        var inAudioSection = false
//
//        for (i in lines.indices) {
//            val line = lines[i]
//
//            // Track media sections
//            if (line.startsWith("m=")) {
//                inMediaSection = true
//                inAudioSection = line.startsWith("m=audio")
//            }
//
//            // Update direction in audio section
//            if (inMediaSection && inAudioSection) {
//                if (line.startsWith("a=sendrecv") ||
//                    line.startsWith("a=sendonly") ||
//                    line.startsWith("a=recvonly") ||
//                    line.startsWith("a=inactive")) {
//                    lines[i] = "a=$directionStr"
//                }
//            }
//
//            // End of section
//            if (inMediaSection && line.trim().isEmpty()) {
//                inMediaSection = false
//                inAudioSection = false
//            }
//        }
//
//        return lines.joinToString("\r\n")
//    }
//
//    /**
//     * Applies modified SDP to the peer connection
//     * @param modifiedSdp The modified SDP string
//     * @return true if successful, false otherwise
//     */
//    override suspend fun applyModifiedSdp(modifiedSdp: String): Boolean {
//        return try {
//            val description = SessionDescription(SessionDescriptionType.Offer, modifiedSdp)
//            peerConnection?.setLocalDescription(description)
//            true
//        } catch (e: Exception) {
//            log.d(TAG) { "Error applying modified SDP: ${e.message}" }
//            false
//        }
//    }
//
//    /**
//     * Extension function to check if a device has an earpiece
//     * Most phones have an earpiece, but tablets and some devices don't
//     */
//    private fun AudioManager.hasEarpiece(): Boolean {
//        // This is a heuristic approach - we can't directly detect earpiece
//        // Most phones support MODE_IN_COMMUNICATION, tablets typically don't
//        return context.packageManager?.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ?: false
//    }
//
//    /**
//     * Extension function to check if a Bluetooth device is connected (Android S+ only)
//     */
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    @RequiresApi(Build.VERSION_CODES.S)
//    private fun BluetoothDevice.isConnected(): Boolean {
//        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
//        val connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
//        return connectedDevices.contains(this)
//    }
//
//    /**
//     * Enable or disable the local audio track
//     * @param enabled Whether audio should be enabled
//     */
//    override fun setAudioEnabled(enabled: Boolean) {
//        log.d(TAG) { "Setting audio enabled: $enabled" }
//
//        // Use AudioManager to ensure microphone state
//        audioManager?.isMicrophoneMute = !enabled
//
//        if (localAudioTrack == null && isInitialized) {
//            log.d(TAG) { "No local audio track but WebRTC is initialized, trying to add audio track" }
//            coroutineScope.launch {
//                ensureLocalAudioTrack()
//                localAudioTrack?.enabled = enabled
//            }
//        } else {
//            localAudioTrack?.enabled = enabled
//        }
//    }
//
//    /**
//     * Get current connection state
//     * @return The connection state
//     */
//    override fun getConnectionState(): WebRtcConnectionState {
//        if (!isInitialized || peerConnection == null) {
//            return WebRtcConnectionState.NEW
//        }
//
//        val state = peerConnection?.connectionState ?: return WebRtcConnectionState.NEW
//        return mapConnectionState(state)
//    }
//
//    /**
//     * Set a listener for WebRTC events
//     * @param listener The WebRTC event listener
//     */
//    override fun setListener(listener: Any?) {
//        if (listener is WebRtcEventListener) {
//            webRtcEventListener = listener
//            log.d(TAG) { "WebRTC event listener set" }
//        } else {
//            log.d(TAG) { "Invalid listener type provided" }
//        }
//    }
//
//    override fun prepareAudioForIncomingCall() {
//        log.d(TAG) { "Preparing audio for incoming call" }
//        initializeAudio()
//    }
//
//    /**
//     * Enhanced audio diagnostics
//     */
//    @SuppressLint("MissingPermission")
//    override fun diagnoseAudioIssues(): String {
//        return buildString {
//            appendLine("=== ENHANCED AUDIO DIAGNOSIS ===")
//
//            // Basic WebRTC state
//            appendLine("WebRTC Initialized: $isInitialized")
//            appendLine("Local Audio Ready: $isLocalAudioReady")
//            appendLine("Local Audio Track: ${localAudioTrack != null}")
//            appendLine("Local Audio Enabled: ${localAudioTrack?.enabled}")
//            appendLine("Remote Audio Track: ${remoteAudioTrack != null}")
//            appendLine("Remote Audio Enabled: ${remoteAudioTrack?.enabled}")
//
//            // AudioManager state
//            audioManager?.let { am ->
//                appendLine("\n--- AudioManager State ---")
//                appendLine("Audio Mode: ${am.mode}")
//                appendLine("Speaker On: ${am.isSpeakerphoneOn}")
//                appendLine("Mic Muted: ${am.isMicrophoneMute}")
//                appendLine("Bluetooth SCO On: ${am.isBluetoothScoOn}")
//                appendLine("Bluetooth SCO Available: ${am.isBluetoothScoAvailableOffCall}")
//                appendLine("Wired Headset On: ${am.isWiredHeadsetOn}")
//                appendLine("Music Active: ${am.isMusicActive}")
//                appendLine("Has Earpiece: ${am.hasEarpiece()}")
//            }
//
//            // Current devices
//            appendLine("\n--- Current Devices ---")
//            appendLine("Current Input: ${currentInputDevice?.name ?: "Not set"}")
//            appendLine("Current Output: ${currentOutputDevice?.name ?: "Not set"}")
//
//            // Device counts
//            try {
//                val (inputs, outputs) = getAllAudioDevices()
//                appendLine("\n--- Available Devices ---")
//                appendLine("Input Devices: ${inputs.size}")
//                inputs.forEach { device ->
//                    appendLine("  - ${device.name} (${device.audioUnit.type}, ${device.connectionState})")
//                }
//                appendLine("Output Devices: ${outputs.size}")
//                outputs.forEach { device ->
//                    appendLine("  - ${device.name} (${device.audioUnit.type}, ${device.connectionState})")
//                }
//            } catch (e: Exception) {
//                appendLine("Error getting devices: ${e.message}")
//            }
//
//            // Bluetooth state
//            appendLine("\n--- Bluetooth State ---")
//            appendLine("Bluetooth Adapter: ${bluetoothAdapter != null}")
//            appendLine("Bluetooth Enabled: ${bluetoothAdapter?.isEnabled}")
//            try {
//                val connectedBt = getConnectedBluetoothDevices()
//                appendLine("Connected BT Devices: ${connectedBt.size}")
//            } catch (e: Exception) {
//                appendLine("BT Device Check Error: ${e.message}")
//            }
//
//            // Permission status
//            appendLine("\n--- Permissions ---")
//            appendLine("Record Audio: ${hasPermission(Manifest.permission.RECORD_AUDIO)}")
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
//                appendLine("Bluetooth Connect: ${hasPermission(Manifest.permission.BLUETOOTH_CONNECT)}")
//            }
//        }
//    }
//
//    override fun isInitialized(): Boolean = isInitialized
//
//    /**
//     * Send DTMF tones via RTP (RFC 4733)
//     * @param tones The DTMF tones to send (0-9, *, #, A-D)
//     * @param duration Duration in milliseconds for each tone (optional, default 100ms)
//     * @param gap Gap between tones in milliseconds (optional, default 70ms)
//     * @return true if successfully started sending tones, false otherwise
//     */
//    override fun sendDtmfTones(tones: String, duration: Int, gap: Int): Boolean {
//        log.d(TAG) { "Sending DTMF tones: $tones (duration: $duration, gap: $gap)" }
//
//        // Check if WebRTC is initialized and connection is established
//        if (!isInitialized || peerConnection == null) {
//            log.d(TAG) { "Cannot send DTMF: WebRTC not initialized" }
//            return false
//        }
//
//        try {
//            // Get audio sender
//            val audioSender = peerConnection?.getSenders()?.find { sender ->
//                sender.track?.kind == MediaStreamTrackKind.Audio
//            }
//
//            if (audioSender == null) {
//                log.d(TAG) { "Cannot send DTMF: No audio sender found" }
//                return false
//            }
//
//            // Get the DTMF sender for this audio track
//            val dtmfSender = audioSender.dtmf ?: run {
//                log.d(TAG) { "Cannot send DTMF: DtmfSender not available" }
//                return false
//            }
//
//            // Send the DTMF tones
//            val sanitizedTones = sanitizeDtmfTones(tones)
//            if (sanitizedTones.isEmpty()) {
//                log.d(TAG) { "Cannot send DTMF: No valid tones to send" }
//                return false
//            }
//
//            val result = dtmfSender.insertDtmf(
//                tones = sanitizedTones,
//                durationMs = duration,
//                interToneGapMs = gap
//            )
//
//            log.d(TAG) { "DTMF tone sending result: $result" }
//            return result
//        } catch (e: Exception) {
//            log.d(TAG) { "Error sending DTMF tones: ${e.message}" }
//            return false
//        }
//    }
//
//    /**
//     * Sanitizes DTMF tones to ensure only valid characters are sent
//     * Valid DTMF characters are 0-9, *, #, A-D, and comma (,) for pause
//     */
//    private fun sanitizeDtmfTones(tones: String): String {
//        // WebRTC supports 0-9, *, #, A-D and comma (,) for pause
//        val validDtmfPattern = Regex("[0-9A-D*#,]", RegexOption.IGNORE_CASE)
//
//        return tones.filter { tone ->
//            validDtmfPattern.matches(tone.toString())
//        }
//    }
//
//    /**
//     * Check if permission is granted
//     */
//    private fun hasPermission(permission: String): Boolean {
//        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
//    }
//
//    /**
//     * Add device change listener
//     */
//    fun addDeviceChangeListener(listener: (List<AudioDevice>) -> Unit) {
//        deviceChangeListeners.add(listener)
//    }
//
//    /**
//     * Remove device change listener
//     */
//    fun removeDeviceChangeListener(listener: (List<AudioDevice>) -> Unit) {
//        deviceChangeListeners.remove(listener)
//    }
//
//    /**
//     * Get devices by quality score
//     */
//    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
//    fun getAudioDevicesByQuality(): Pair<List<AudioDevice>, List<AudioDevice>> {
//        val (inputs, outputs) = getAllAudioDevices()
//        return Pair(
//            inputs.sortedByDescending { it.qualityScore },
//            outputs.sortedByDescending { it.qualityScore }
//        )
//    }
//
//    /**
//     * Get recommended device for optimal call quality
//     */
//    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
//    fun getRecommendedAudioDevice(isOutput: Boolean): AudioDevice? {
//        val (inputs, outputs) = getAudioDevicesByQuality()
//        val devices = if (isOutput) outputs else inputs
//
//        // Prefer current device if it has good quality
//        val currentDevice = if (isOutput) currentOutputDevice else currentInputDevice
//        if (currentDevice != null && currentDevice.qualityScore >= 70) {
//            return currentDevice
//        }
//
//        // Otherwise, return the highest quality available device
//        return devices.firstOrNull {
//            it.connectionState == DeviceConnectionState.AVAILABLE ||
//                    it.connectionState == DeviceConnectionState.CONNECTED
//        }
//    }
//
//    /**
//     * Maps WebRTC's PeerConnectionState to our WebRtcConnectionState enum
//     */
//    private fun mapConnectionState(state: PeerConnectionState): WebRtcConnectionState {
//        return when (state) {
//            PeerConnectionState.New -> WebRtcConnectionState.NEW
//            PeerConnectionState.Connecting -> WebRtcConnectionState.CONNECTING
//            PeerConnectionState.Connected -> WebRtcConnectionState.CONNECTED
//            PeerConnectionState.Disconnected -> WebRtcConnectionState.DISCONNECTED
//            PeerConnectionState.Failed -> WebRtcConnectionState.FAILED
//            PeerConnectionState.Closed -> WebRtcConnectionState.CLOSED
//        }
//    }
//
//    /**
//     * Initializes the PeerConnection with ICE configuration and sets up event observers.
//     */
//    private fun initializePeerConnection() {
//        log.d(TAG) { "Initializing PeerConnection..." }
//        cleanupCall()
//
//        try {
//            val rtcConfig = RtcConfiguration(
//                iceServers = listOf(
//                    IceServer(
//                        urls = listOf(
//                            "stun:stun.l.google.com:19302",
//                            "stun:stun1.l.google.com:19302"
//                        )
//                    )
//                )
//            )
//
//            log.d(TAG) { "RTC Configuration: $rtcConfig" }
//
//            peerConnection = PeerConnection(rtcConfig).apply {
//                setupPeerConnectionObservers()
//            }
//
//            log.d(TAG) { "PeerConnection created: ${peerConnection != null}" }
//
//            // Don't add local audio track here - will be done when needed
//            // This prevents requesting microphone permission unnecessarily
//            isLocalAudioReady = false
//        } catch (e: Exception) {
//            log.d(TAG) { "Error initializing PeerConnection: ${e.message}" }
//            peerConnection = null
//            isInitialized = false
//            isLocalAudioReady = false
//        }
//    }
//
//    /**
//     * Configures the observers for the PeerConnection events.
//     */
//    private fun PeerConnection.setupPeerConnectionObservers() {
//        onIceCandidate.onEach { candidate ->
//            log.d(TAG) { "New ICE Candidate: ${candidate.candidate}" }
//
//            // Notify the listener
//            webRtcEventListener?.onIceCandidate(
//                candidate.candidate,
//                candidate.sdpMid,
//                candidate.sdpMLineIndex
//            )
//        }.launchIn(coroutineScope)
//
//        onConnectionStateChange.onEach { state ->
//            log.d(TAG) { "Connection state changed: $state" }
//
//            // Update call state based on connection state
//            when (state) {
//                PeerConnectionState.Connected -> {
//                    log.d(TAG) { "Call active: Connected" }
//                    CallStateManager.updateCallState(CallState.CONNECTED)
//                    // Ensure audio is enabled when connected and microphone is not muted
//                    setAudioEnabled(true)
//                    audioManager?.isMicrophoneMute = false
//                }
//
//                PeerConnectionState.Disconnected,
//                PeerConnectionState.Failed,
//                PeerConnectionState.Closed -> {
//                    CallStateManager.updateCallState(CallState.ENDED)
//                    log.d(TAG) { "Call ended" }
//                    // Release audio focus when call ends
//                    releaseAudioFocus()
//                }
//
//                else -> {
//                    log.d(TAG) { "Other connection state: $state" }
//                }
//            }
//
//            // Notify the listener
//            webRtcEventListener?.onConnectionStateChange(mapConnectionState(state))
//        }.launchIn(coroutineScope)
//
//        onTrack.onEach { event ->
//            log.d(TAG) { "Remote track received: $event" }
//            val track = event.receiver.track
//
//            if (track is AudioStreamTrack) {
//                log.d(TAG) { "Remote audio track established" }
//                remoteAudioTrack = track
//                remoteAudioTrack?.enabled = true
//
//                // Notify the listener
//                webRtcEventListener?.onRemoteAudioTrack()
//            }
//        }.launchIn(coroutineScope)
//    }
//
//    /**
//     * Ensures the local audio track is created and added to the PeerConnection.
//     * Returns true if successful, false otherwise.
//     */
//    private suspend fun ensureLocalAudioTrack(): Boolean {
//        return try {
//            val peerConn = peerConnection ?: run {
//                log.d(TAG) { "PeerConnection not initialized" }
//                return false
//            }
//
//            // Check if we already have a track
//            if (localAudioTrack != null) {
//                log.d(TAG) { "Local audio track already exists" }
//                return true
//            }
//
//            // Make sure audio mode is set for communication
//            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
//            audioManager?.isMicrophoneMute = false
//
//            log.d(TAG) { "Getting local audio stream..." }
//
//            val mediaStream = MediaDevices.getUserMedia(
//                audio = true,
//                video = false
//            )
//
//            val audioTrack = mediaStream.audioTracks.firstOrNull()
//            if (audioTrack != null) {
//                log.d(TAG) { "Audio track obtained successfully!" }
//
//                localAudioTrack = audioTrack
//                localAudioTrack?.enabled = true
//
//                peerConn.addTrack(audioTrack, mediaStream)
//
//                log.d(TAG) { "Audio track added successfully: ${audioTrack.label}" }
//
//                // Additional troubleshooting for audio routing
//                val outputDevice = when {
//                    audioManager?.isBluetoothScoOn == true -> "Bluetooth SCO"
//                    audioManager?.isBluetoothA2dpOn == true -> "Bluetooth A2DP"
//                    audioManager?.isSpeakerphoneOn == true -> "Speakerphone"
//                    audioManager?.isWiredHeadsetOn == true -> "Wired Headset"
//                    else -> "Earpiece/Default"
//                }
//
//                log.d(TAG) { "Current audio output device: $outputDevice" }
//
//                true
//            } else {
//                log.d(TAG) { "Error: No audio track found" }
//                false
//            }
//        } catch (e: Exception) {
//            log.d(TAG) { "Error getting audio: ${e.message}" }
//            false
//        }
//    }
//
//    /**
//     * Cleans up call resources
//     */
//    private fun cleanupCall() {
//        try {
//            // First, stop any active media operations
//            localAudioTrack?.enabled = false
//
//            // Remove tracks from peer connection
//            peerConnection?.let { pc ->
//                pc.getSenders().forEach { sender ->
//                    try {
//                        pc.removeTrack(sender)
//                    } catch (e: Exception) {
//                        log.d(TAG) { "Error removing track: ${e.message}" }
//                    }
//                }
//            }
//
//            // Close peer connection first
//            peerConnection?.close()
//            peerConnection = null
//
//            // Wait a short moment to ensure connections are closed
//            Thread.sleep(100)
//
//            // Dispose of media resources
//            localAudioTrack = null
//            remoteAudioTrack = null
//            isLocalAudioReady = false
//
//            // Force garbage collection to ensure native objects are released
//            System.gc()
//
//        } catch (e: Exception) {
//            log.d(TAG) { "Error in cleanupCall: ${e.message}" }
//        }
//    }
//}
