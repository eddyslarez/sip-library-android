package com.eddyslarez.siplibrary.data.services.audio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.eddyslarez.siplibrary.data.models.AccountInfo
import com.eddyslarez.siplibrary.data.models.CallState
import com.eddyslarez.siplibrary.utils.CallStateManager
import com.shepeliev.webrtckmp.AudioStreamTrack
import com.shepeliev.webrtckmp.PeerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
import com.shepeliev.webrtckmp.audioTracks
import com.shepeliev.webrtckmp.onConnectionStateChange
import com.shepeliev.webrtckmp.onIceCandidate
import com.shepeliev.webrtckmp.onTrack
import com.shepeliev.webrtckmp.MediaDevices
import com.shepeliev.webrtckmp.MediaDeviceInfo
import com.shepeliev.webrtckmp.MediaStreamTrackKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android implementation of WebRtcManager interface with TelecomManager support
 *
 * @author Eddys Larez
 */
class AndroidWebRtcManager(private val application: Application) : WebRtcManager {
    private val TAG = "AndroidWebRtcManager"
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)

    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioStreamTrack? = null
    private var remoteAudioTrack: AudioStreamTrack? = null
    private var webRtcEventListener: WebRtcEventListener? = null
    private var isInitialized = false
    private var isLocalAudioReady = false
    private var context: Context = application.applicationContext
    private val remoteAudioBuffer = mutableListOf<ByteArray>()
    private val audioProcessingListeners = mutableListOf<(ByteArray, Boolean) -> Unit>()
    private var audioProcessingJob: Job? = null
    // Audio management fields
    private var audioManager: AudioManager? = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var savedIsSpeakerPhoneOn = false
    private var savedIsMicrophoneMute = false
    private var audioFocusRequest: AudioFocusRequest? = null

    // Audio capture/injection para traducción
    private val audioCaptureListeners = mutableListOf<(ByteArray) -> Unit>()
    private val audioInjectionEnabled = AtomicBoolean(false)
    private var audioCaptureJob: Job? = null

    override fun initialize() {
        Log.d(TAG, "Initializing WebRTC Manager...")
        if (!isInitialized) {
            initializeAudio()
            initializePeerConnection()
            coroutineScope.launch {
                getAudioInputDevices()
            }
            isInitialized = true
        } else {
            Log.d(TAG, "WebRTC already initialized")
        }
    }

    private fun initializeAudio() {
        audioManager?.let { am ->
            // Save current audio state
            savedAudioMode = am.mode
            savedIsSpeakerPhoneOn = am.isSpeakerphoneOn
            savedIsMicrophoneMute = am.isMicrophoneMute

            Log.d(TAG, "Saved audio state - Mode: $savedAudioMode, Speaker: $savedIsSpeakerPhoneOn, Mic muted: $savedIsMicrophoneMute")

            // Configure audio for communication
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = false
            am.isMicrophoneMute = false

            // Request audio focus
            requestAudioFocus()

            Log.d(TAG, "Audio configured for WebRTC communication")
        } ?: run {
            Log.d(TAG, "AudioManager not available!")
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            Log.d(TAG, "Audio focus gained")
                            setAudioEnabled(true)
                        }
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            Log.d(TAG, "Audio focus lost")
                        }
                    }
                }
                .build()

            audioFocusRequest = focusRequest
            val result = audioManager?.requestAudioFocus(focusRequest) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
            Log.d(TAG, "Audio focus request result: $result")

        } else {
            @Suppress("DEPRECATION")
            val result = audioManager?.requestAudioFocus(
                { focusChange ->
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_GAIN -> setAudioEnabled(true)
                    }
                },
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN
            ) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED

            Log.d(TAG, "Legacy audio focus request result: $result")
        }
    }

    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { request ->
                audioManager?.abandonAudioFocusRequest(request)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
        }

        // Restore previous audio settings
        audioManager?.let { am ->
            am.mode = savedAudioMode
            am.isSpeakerphoneOn = savedIsSpeakerPhoneOn
            am.isMicrophoneMute = savedIsMicrophoneMute

            Log.d(TAG, "Restored audio state")
        }
    }

    suspend fun getAudioInputDevices(): List<MediaDeviceInfo> {
        return MediaDevices.enumerateDevices()
    }

    override fun dispose() {
        Log.d(TAG, "Disposing WebRTC resources...")
        releaseAudioFocus()
        cleanupCall()
        stopAudioCapture()
        isInitialized = false
        isLocalAudioReady = false
    }

    override suspend fun createOffer(): String {
        Log.d(TAG, "Creating SDP offer...")

        if (!isInitialized) {
            Log.d(TAG, "WebRTC not initialized, initializing now")
            initialize()
        } else {
            initializeAudio()
        }

        val peerConn = peerConnection ?: run {
            Log.d(TAG, "PeerConnection not initialized, reinitializing")
            initializePeerConnection()
            peerConnection ?: throw IllegalStateException("PeerConnection initialization failed")
        }

        if (!isLocalAudioReady) {
            Log.d(TAG, "Ensuring local audio track is ready...")
            isLocalAudioReady = ensureLocalAudioTrack()
            if (!isLocalAudioReady) {
                Log.d(TAG, "Failed to prepare local audio track!")
            }
        }

        val options = OfferAnswerOptions(voiceActivityDetection = true)
        val sessionDescription = peerConn.createOffer(options)
        peerConn.setLocalDescription(sessionDescription)

        audioManager?.isMicrophoneMute = false

        Log.d(TAG, "Created offer SDP: ${sessionDescription.sdp}")
        return sessionDescription.sdp
    }

    override suspend fun createAnswer(accountInfo: AccountInfo, offerSdp: String): String {
        Log.d(TAG, "Creating SDP answer...")

        if (!isInitialized) {
            Log.d(TAG, "WebRTC not initialized, initializing now")
            initialize()
        } else {
            initializeAudio()
        }

        val peerConn = peerConnection ?: run {
            Log.d(TAG, "PeerConnection not initialized, reinitializing")
            initializePeerConnection()
            peerConnection ?: throw IllegalStateException("PeerConnection initialization failed")
        }

        if (!isLocalAudioReady) {
            Log.d(TAG, "Ensuring local audio track is ready before answering...")
            isLocalAudioReady = ensureLocalAudioTrack()
            if (!isLocalAudioReady) {
                Log.d(TAG, "Failed to prepare local audio track for answering!")
            }
        }

        // Set the remote offer
        val remoteOffer = SessionDescription(
            type = SessionDescriptionType.Offer,
            sdp = offerSdp
        )
        peerConn.setRemoteDescription(remoteOffer)

        // Create answer
        val options = OfferAnswerOptions(voiceActivityDetection = true)
        val sessionDescription = peerConn.createAnswer(options)
        peerConn.setLocalDescription(sessionDescription)

        setAudioEnabled(true)
        audioManager?.isMicrophoneMute = false

        Log.d(TAG, "Created answer SDP: ${sessionDescription.sdp}")
        return sessionDescription.sdp
    }

    override suspend fun setRemoteDescription(sdp: String, type:SdpType) {
        Log.d(TAG, "Setting remote description type: $type")

        if (!isInitialized) {
            Log.d(TAG, "WebRTC not initialized, initializing now")
            initialize()
        }

        val peerConn = peerConnection ?: run {
            Log.d(TAG, "PeerConnection not initialized, reinitializing")
            initializePeerConnection()
            peerConnection ?: throw IllegalStateException("PeerConnection initialization failed")
        }

        if (type == SdpType.OFFER && !isLocalAudioReady) {
            Log.d(TAG, "Ensuring local audio track is ready before processing offer...")
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
            audioManager?.isMicrophoneMute = false
        }
    }

    override suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        Log.d(TAG, "Adding ICE candidate: $candidate")

        if (!isInitialized) {
            Log.d(TAG, "WebRTC not initialized, initializing now")
            initialize()
            if (peerConnection == null) {
                Log.d(TAG, "Failed to initialize PeerConnection, cannot add ICE candidate")
                return
            }
        }

        val peerConn = peerConnection ?: run {
            Log.d(TAG, "PeerConnection not available, cannot add ICE candidate")
            return
        }

        val iceCandidate = IceCandidate(
            sdpMid = sdpMid ?: "",
            sdpMLineIndex = sdpMLineIndex ?: 0,
            candidate = candidate
        )

        peerConn.addIceCandidate(iceCandidate)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        Log.d(TAG, "Getting all audio devices...")

        val inputDevices = mutableListOf<AudioDevice>()
        val outputDevices = mutableListOf<AudioDevice>()

        try {
            val am = audioManager ?: context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

            // Check for built-in earpiece
            if (am?.hasEarpiece() == true) {
                outputDevices.add(AudioDevice(
                    name = "Earpiece",
                    descriptor = "earpiece",
                    isOutput = true
                ))
            }

            // Built-in speaker always exists
            outputDevices.add(AudioDevice(
                name = "Speaker",
                descriptor = "speaker",
                isOutput = true
            ))

            // Built-in microphone always exists
            inputDevices.add(AudioDevice(
                name = "Built-in Microphone",
                descriptor = "builtin_mic",
                isOutput = false
            ))

            // Check for wired headset
            if (am?.isWiredHeadsetOn == true) {
                outputDevices.add(AudioDevice(
                    name = "Wired Headset",
                    descriptor = "wired_headset",
                    isOutput = true
                ))

                inputDevices.add(AudioDevice(
                    name = "Wired Headset Microphone",
                    descriptor = "wired_headset_mic",
                    isOutput = false
                ))
            }

            // Check for Bluetooth SCO devices
            if (am?.isBluetoothScoAvailableOffCall == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    val connectedDevices = bluetoothManager?.adapter?.bondedDevices?.filter {
                        it.isConnected()
                    } ?: emptyList()

                    connectedDevices.forEach { device ->
                        val deviceName = device.name ?: "Bluetooth Device"
                        val deviceAddress = device.address

                        outputDevices.add(AudioDevice(
                            name = "$deviceName (Bluetooth)",
                            descriptor = "bluetooth_$deviceAddress",
                            nativeDevice = device,
                            isOutput = true
                        ))

                        inputDevices.add(AudioDevice(
                            name = "$deviceName Microphone (Bluetooth)",
                            descriptor = "bluetooth_mic_$deviceAddress",
                            nativeDevice = device,
                            isOutput = false
                        ))
                    }
                } else {
                    outputDevices.add(AudioDevice(
                        name = "Bluetooth Headset",
                        descriptor = "bluetooth_headset",
                        isOutput = true
                    ))

                    inputDevices.add(AudioDevice(
                        name = "Bluetooth Microphone",
                        descriptor = "bluetooth_mic",
                        isOutput = false
                    ))
                }
            }

            Log.d(TAG, "Found ${inputDevices.size} input and ${outputDevices.size} output devices")
        } catch (e: Exception) {
            Log.d(TAG, "Error getting audio devices: ${e.stackTraceToString()}")
        }

        return Pair(inputDevices, outputDevices)
    }

    override fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean {
        Log.d(TAG, "Changing audio output to: ${device.name}")

        if (!isInitialized || peerConnection == null) {
            Log.d(TAG, "Cannot change audio output: WebRTC not initialized")
            return false
        }

        try {
            val am = audioManager ?: return false

            // Reset all output modes first
            am.isSpeakerphoneOn = false
            if (am.isBluetoothScoOn) {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
            }

            // Switch based on the device descriptor
            when {
                device.descriptor.startsWith("bluetooth_") -> {
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                    Log.d(TAG, "Audio routed to Bluetooth device")
                }
                device.descriptor == "speaker" -> {
                    am.isSpeakerphoneOn = true
                    Log.d(TAG, "Audio routed to speaker")
                }
                device.descriptor == "wired_headset" -> {
                    Log.d(TAG, "Audio routed to wired headset")
                }
                device.descriptor == "earpiece" -> {
                    Log.d(TAG, "Audio routed to earpiece")
                }
                else -> {
                    Log.d(TAG, "Unknown audio device type: ${device.descriptor}")
                    return false
                }
            }

            webRtcEventListener?.onAudioDeviceChanged(device)
            return true
        } catch (e: Exception) {
            Log.d(TAG, "Error changing audio output: ${e.stackTraceToString()}")
            return false
        }
    }

    override fun changeAudioInputDeviceDuringCall(device: AudioDevice): Boolean {
        Log.d(TAG, "Changing audio input to: ${device.name}")

        if (!isInitialized || peerConnection == null) {
            Log.d(TAG, "Cannot change audio input: WebRTC not initialized")
            return false
        }

        try {
            val am = audioManager ?: return false

            when {
                device.descriptor.startsWith("bluetooth_mic_") -> {
                    if (!am.isBluetoothScoOn) {
                        am.startBluetoothSco()
                        am.isBluetoothScoOn = true
                    }
                    Log.d(TAG, "Audio input set to Bluetooth microphone")
                    webRtcEventListener?.onAudioDeviceChanged(device)
                    return true
                }
                device.descriptor == "wired_headset_mic" -> {
                    if (am.isBluetoothScoOn) {
                        am.stopBluetoothSco()
                        am.isBluetoothScoOn = false
                    }
                    Log.d(TAG, "Audio input set to wired headset microphone")
                    webRtcEventListener?.onAudioDeviceChanged(device)
                    return true
                }
                device.descriptor == "builtin_mic" -> {
                    if (am.isBluetoothScoOn) {
                        am.stopBluetoothSco()
                        am.isBluetoothScoOn = false
                    }
                    Log.d(TAG, "Audio input set to built-in microphone")
                    webRtcEventListener?.onAudioDeviceChanged(device)
                    return true
                }
            }

            Log.d(TAG, "Unknown audio input device: ${device.descriptor}")
            return false
        } catch (e: Exception) {
            Log.d(TAG, "Error changing audio input: ${e.stackTraceToString()}")
            return false
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun getCurrentInputDevice(): AudioDevice? {
        if (!isInitialized) return null

        try {
            val am = audioManager ?: return null

            return when {
                am.isBluetoothScoOn -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                        val connectedDevice = bluetoothManager?.adapter?.bondedDevices?.firstOrNull {
                            it.isConnected()
                        }

                        if (connectedDevice != null) {
                            AudioDevice(
                                name = "${connectedDevice.name ?: "Bluetooth"} Microphone",
                                descriptor = "bluetooth_mic_${connectedDevice.address}",
                                nativeDevice = connectedDevice,
                                isOutput = false
                            )
                        } else {
                            AudioDevice(
                                name = "Bluetooth Microphone",
                                descriptor = "bluetooth_mic",
                                isOutput = false
                            )
                        }
                    } else {
                        AudioDevice(
                            name = "Bluetooth Microphone",
                            descriptor = "bluetooth_mic",
                            isOutput = false
                        )
                    }
                }
                am.isWiredHeadsetOn -> {
                    AudioDevice(
                        name = "Wired Headset Microphone",
                        descriptor = "wired_headset_mic",
                        isOutput = false
                    )
                }
                else -> {
                    AudioDevice(
                        name = "Built-in Microphone",
                        descriptor = "builtin_mic",
                        isOutput = false
                    )
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Error getting current input device: ${e.stackTraceToString()}")
            return null
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun getCurrentOutputDevice(): AudioDevice? {
        if (!isInitialized) return null

        try {
            val am = audioManager ?: return null

            return when {
                am.isBluetoothScoOn -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                        val connectedDevice = bluetoothManager?.adapter?.bondedDevices?.firstOrNull {
                            it.isConnected()
                        }

                        if (connectedDevice != null) {
                            AudioDevice(
                                name = "${connectedDevice.name ?: "Bluetooth"} Headset",
                                descriptor = "bluetooth_${connectedDevice.address}",
                                nativeDevice = connectedDevice,
                                isOutput = true
                            )
                        } else {
                            AudioDevice(
                                name = "Bluetooth Headset",
                                descriptor = "bluetooth_headset",
                                isOutput = true
                            )
                        }
                    } else {
                        AudioDevice(
                            name = "Bluetooth Headset",
                            descriptor = "bluetooth_headset",
                            isOutput = true
                        )
                    }
                }
                am.isSpeakerphoneOn -> {
                    AudioDevice(
                        name = "Speaker",
                        descriptor = "speaker",
                        isOutput = true
                    )
                }
                am.isWiredHeadsetOn -> {
                    AudioDevice(
                        name = "Wired Headset",
                        descriptor = "wired_headset",
                        isOutput = true
                    )
                }
                else -> {
                    if (am.hasEarpiece()) {
                        AudioDevice(
                            name = "Earpiece",
                            descriptor = "earpiece",
                            isOutput = true
                        )
                    } else {
                        AudioDevice(
                            name = "Speaker",
                            descriptor = "speaker",
                            isOutput = true
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Error getting current output device: ${e.stackTraceToString()}")
            return null
        }
    }

    override fun setMuted(muted: Boolean) {
        Log.d(TAG, "Setting microphone mute: $muted")

        try {
            audioManager?.isMicrophoneMute = muted
            localAudioTrack?.enabled = !muted
        } catch (e: Exception) {
            Log.d(TAG, "Error setting mute state: ${e.stackTraceToString()}")
        }
    }

    override fun isMuted(): Boolean {
        val isAudioManagerMuted = audioManager?.isMicrophoneMute ?: false
        val isTrackDisabled = localAudioTrack?.enabled?.not() ?: false
        return isAudioManagerMuted || isTrackDisabled
    }

    override fun getLocalDescription(): String? {
        return peerConnection?.localDescription?.sdp
    }

    override suspend fun setMediaDirection(direction: WebRtcManager.MediaDirection) {
        Log.d(TAG, "Setting media direction: $direction")

        if (!isInitialized || peerConnection == null) {
            Log.d(TAG, "Cannot set media direction: WebRTC not initialized")
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
            Log.d(TAG, "Error setting media direction: ${e.stackTraceToString()}")
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
            Log.d(TAG, "Error applying modified SDP: ${e.stackTraceToString()}")
            false
        }
    }

    private fun AudioManager.hasEarpiece(): Boolean {
        return context.packageManager?.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ?: false
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @RequiresApi(Build.VERSION_CODES.S)
    private fun BluetoothDevice.isConnected(): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
        return connectedDevices.contains(this)
    }

    override fun setAudioEnabled(enabled: Boolean) {
        Log.d(TAG, "Setting audio enabled: $enabled")

        audioManager?.isMicrophoneMute = !enabled

        if (localAudioTrack == null && isInitialized) {
            Log.d(TAG, "No local audio track but WebRTC is initialized, trying to add audio track")
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

    override fun setListener(listener: Any) {
        if (listener is WebRtcEventListener) {
            webRtcEventListener = listener
            Log.d(TAG, "WebRTC event listener set")
        } else {
            Log.d(TAG, "Invalid listener type provided")
        }
    }

    override fun prepareAudioForIncomingCall() {
        Log.d(TAG, "Preparing audio for incoming call")
        initializeAudio()
    }

    override fun diagnoseAudioIssues(): String {
        return buildString {
            appendLine("=== Audio Diagnosis ===")
            appendLine("Audio Manager Available: ${audioManager != null}")
            appendLine("Audio Mode: ${audioManager?.mode}")
            appendLine("Speaker On: ${audioManager?.isSpeakerphoneOn}")
            appendLine("Mic Muted: ${audioManager?.isMicrophoneMute}")
            appendLine("Bluetooth SCO On: ${audioManager?.isBluetoothScoOn}")
            appendLine("Wired Headset On: ${audioManager?.isWiredHeadsetOn}")
            appendLine("Local Audio Track: ${localAudioTrack != null}")
            appendLine("Local Audio Enabled: ${localAudioTrack?.enabled}")
            appendLine("WebRTC Initialized: $isInitialized")
        }
    }

    override fun isInitialized(): Boolean = isInitialized

    override fun sendDtmfTones(tones: String, duration: Int, gap: Int): Boolean {
        Log.d(TAG, "Sending DTMF tones: $tones (duration: $duration, gap: $gap)")

        if (!isInitialized || peerConnection == null) {
            Log.d(TAG, "Cannot send DTMF: WebRTC not initialized")
            return false
        }

        try {
            val audioSender = peerConnection?.getSenders()?.find { sender ->
                sender.track?.kind == MediaStreamTrackKind.Audio
            }

            if (audioSender == null) {
                Log.d(TAG, "Cannot send DTMF: No audio sender found")
                return false
            }

            val dtmfSender = audioSender.dtmf ?: run {
                Log.d(TAG, "Cannot send DTMF: DtmfSender not available")
                return false
            }

            val sanitizedTones = sanitizeDtmfTones(tones)
            if (sanitizedTones.isEmpty()) {
                Log.d(TAG, "Cannot send DTMF: No valid tones to send")
                return false
            }

            val result = dtmfSender.insertDtmf(
                tones = sanitizedTones,
                durationMs = duration,
                interToneGapMs = gap
            )

            Log.d(TAG, "DTMF tone sending result: $result")
            return result
        } catch (e: Exception) {
            Log.d(TAG, "Error sending DTMF tones: ${e.stackTraceToString()}")
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
        Log.d(TAG, "Initializing PeerConnection...")
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

            Log.d(TAG, "RTC Configuration: $rtcConfig")

            peerConnection = PeerConnection(rtcConfig).apply {
                setupPeerConnectionObservers()
            }

            Log.d(TAG, "PeerConnection created: ${peerConnection != null}")
            isLocalAudioReady = false
        } catch (e: Exception) {
            Log.d(TAG, "Error initializing PeerConnection: ${e.stackTraceToString()}")
            peerConnection = null
            isInitialized = false
            isLocalAudioReady = false
        }
    }

    private fun PeerConnection.setupPeerConnectionObservers() {
        onIceCandidate.onEach { candidate ->
            Log.d(TAG, "New ICE Candidate: ${candidate.candidate}")
            webRtcEventListener?.onIceCandidate(
                candidate.candidate,
                candidate.sdpMid,
                candidate.sdpMLineIndex
            )
        }.launchIn(coroutineScope)

        onConnectionStateChange.onEach { state ->
            Log.d(TAG, "Connection state changed: $state")

            when (state) {
                PeerConnectionState.Connected -> {
                    Log.d(TAG, "Call active: Connected")
                    CallStateManager.updateCallState(CallState.CONNECTED)
                    setAudioEnabled(true)
                    audioManager?.isMicrophoneMute = false

                    // Iniciar captura de audio para traducción
                    startAudioCapture()
                }

                PeerConnectionState.Disconnected,
                PeerConnectionState.Failed,
                PeerConnectionState.Closed -> {
                    CallStateManager.updateCallState(CallState.ENDED)
                    Log.d(TAG, "Call ended")
                    releaseAudioFocus()
                    stopAudioCapture()
                }

                else -> {
                    Log.d(TAG, "Other connection state: $state")
                }
            }

            webRtcEventListener?.onConnectionStateChange(mapConnectionState(state))
        }.launchIn(coroutineScope)

        onTrack.onEach { event ->
            Log.d(TAG, "Remote track received: $event")
            val track = event.receiver.track

            if (track is AudioStreamTrack) {
                Log.d(TAG, "Remote audio track established")
                remoteAudioTrack = track
                remoteAudioTrack?.enabled = true
                webRtcEventListener?.onRemoteAudioTrack()
            }
        }.launchIn(coroutineScope)
    }
//    /**
//     * Intercepta audio remoto directamente desde WebRTC
//     */
//    private fun setupRemoteAudioInterception() {
//        audioProcessingJob = coroutineScope.launch {
//            while (isActive && getConnectionState() == WebRtcConnectionState.CONNECTED) {
//                try {
//                    // Procesar buffer de audio remoto
//                    synchronized(remoteAudioBuffer) {
//                        if (remoteAudioBuffer.isNotEmpty()) {
//                            val audioData = combineAudioBuffers(remoteAudioBuffer.toList())
//                            remoteAudioBuffer.clear()
//
//                            // Notificar a listeners (false = audio remoto)
//                            audioProcessingListeners.forEach { listener ->
//                                listener(audioData, false)
//                            }
//                        }
//                    }
//                    delay(50) // Procesar cada 50ms
//                } catch (e: Exception) {
//                    Log.e(TAG, "Error procesando audio remoto", e)
//                }
//            }
//        }
//    }
    private suspend fun ensureLocalAudioTrack(): Boolean {
        return try {
            val peerConn = peerConnection ?: run {
                Log.d(TAG, "PeerConnection not initialized")
                return false
            }

            if (localAudioTrack != null) {
                Log.d(TAG, "Local audio track already exists")
                return true
            }

            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager?.isMicrophoneMute = false

            Log.d(TAG, "Getting local audio stream...")

            val mediaStream = MediaDevices.getUserMedia(audio = true, video = false)
            val audioTrack = mediaStream.audioTracks.firstOrNull()

            if (audioTrack != null) {
                Log.d(TAG, "Audio track obtained successfully!")

                localAudioTrack = audioTrack
                localAudioTrack?.enabled = true

                peerConn.addTrack(audioTrack, mediaStream)

                Log.d(TAG, "Audio track added successfully: ${audioTrack.label}")

                val outputDevice = when {
                    audioManager?.isBluetoothScoOn == true -> "Bluetooth SCO"
                    audioManager?.isBluetoothA2dpOn == true -> "Bluetooth A2DP"
                    audioManager?.isSpeakerphoneOn == true -> "Speakerphone"
                    audioManager?.isWiredHeadsetOn == true -> "Wired Headset"
                    else -> "Earpiece/Default"
                }

                Log.d(TAG, "Current audio output device: $outputDevice")
                true
            } else {
                Log.d(TAG, "Error: No audio track found")
                false
            }
        } catch (e: Exception) {
            Log.d(TAG, "Error getting audio: ${e.stackTraceToString()}")
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
                        Log.d(TAG, "Error removing track: ${e.message}")
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
            Log.d(TAG, "Error in cleanupCall: ${e.stackTraceToString()}")
        }
    }

    // NUEVAS FUNCIONES PARA INTEGRACIÓN CON TRADUCCIÓN

    /**
     * Registra un listener para capturar audio durante llamadas
     */
    override fun addAudioCaptureListener(listener: (ByteArray) -> Unit) {
        audioCaptureListeners.add(listener)
    }

    /**
     * Remueve un listener de captura de audio
     */
    override fun removeAudioCaptureListener(listener: (ByteArray) -> Unit) {
        audioCaptureListeners.remove(listener)
    }

    /**
     * Inicia la captura de audio para traducción
     */
    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        if (audioCaptureJob?.isActive == true) return

        audioCaptureJob = coroutineScope.launch {
            try {
                val sampleRate = 8000  // Para VoIP
                val channelConfig = android.media.AudioFormat.CHANNEL_IN_MONO
                val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
                val bufferSize = android.media.AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

                val audioRecord = android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    channelConfig,
                    audioFormat,
                    bufferSize
                )

                audioRecord.startRecording()
                val buffer = ByteArray(bufferSize)

                while (isActive && getConnectionState() ==WebRtcConnectionState.CONNECTED) {
                    val bytesRead = audioRecord.read(buffer, 0, buffer.size)
                    if (bytesRead > 0) {
                        val audioData = buffer.copyOf(bytesRead)
                        // Notificar a todos los listeners
                        audioCaptureListeners.forEach { listener ->
                            listener(audioData)
                        }
                    }
                    delay(10)
                }

                audioRecord.stop()
                audioRecord.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error in audio capture", e)
            }
        }
    }

    /**
     * Detiene la captura de audio
     */
    private fun stopAudioCapture() {
        audioCaptureJob?.cancel()
        audioCaptureJob = null
    }

    /**
     * Inyecta audio traducido al stream de la llamada
     */
    override fun injectTranslatedAudio(audioData: ByteArray) {
        if (!audioInjectionEnabled.get()) return

        try {
            // Reproducir usando AudioTrack en el contexto de la llamada
            val sampleRate = 8000
            val channelConfig = android.media.AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = android.media.AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = android.media.AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

            val audioTrack = android.media.AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize,
                android.media.AudioTrack.MODE_STREAM
            )

            audioTrack.play()
            audioTrack.write(audioData, 0, audioData.size)
            audioTrack.flush()
            audioTrack.stop()
            audioTrack.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error injecting translated audio", e)
        }
    }

    /**
     * Habilita/deshabilita la inyección de audio traducido
     */
    override fun setAudioInjectionEnabled(enabled: Boolean) {
        audioInjectionEnabled.set(enabled)
    }

    /**
     * Obtiene el estado actual de la inyección de audio
     */
    override fun isAudioInjectionEnabled(): Boolean {
        return audioInjectionEnabled.get()
    }
}
