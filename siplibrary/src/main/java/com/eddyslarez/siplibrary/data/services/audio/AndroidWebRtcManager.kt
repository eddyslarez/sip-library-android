package com.eddyslarez.siplibrary.data.services.audio

import android.Manifest
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
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.eddyslarez.siplibrary.data.models.AccountInfo
import com.eddyslarez.siplibrary.data.models.CallState
import com.eddyslarez.siplibrary.utils.CallStateManager
import com.eddyslarez.siplibrary.utils.log
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
    
    // Audio management fields
    private var audioManager: AudioManager? = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var savedIsSpeakerPhoneOn = false
    private var savedIsMicrophoneMute = false
    private var audioFocusRequest: AudioFocusRequest? = null

    /**
     * Initialize the WebRTC subsystem
     */
    override fun initialize() {
        log.d(tag = TAG) { "Initializing WebRTC Manager..." }
        if (!isInitialized) {
            initializeAudio()
            initializePeerConnection()
            coroutineScope.launch {
                getAudioInputDevices()
            }
            isInitialized = true
        } else {
            log.d(tag = TAG) { "WebRTC already initialized" }
        }
    }

    /**
     * Initialize audio system for calls
     */
    private fun initializeAudio() {
        audioManager?.let { am ->
            // Save current audio state
            savedAudioMode = am.mode
            savedIsSpeakerPhoneOn = am.isSpeakerphoneOn
            savedIsMicrophoneMute = am.isMicrophoneMute

            log.d(tag = TAG) { "Saved audio state - Mode: $savedAudioMode, Speaker: $savedIsSpeakerPhoneOn, Mic muted: $savedIsMicrophoneMute" }

            // Configure audio for communication
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = false
            am.isMicrophoneMute = false

            // Request audio focus
            requestAudioFocus()

            log.d(tag = TAG) { "Audio configured for WebRTC communication" }
        } ?: run {
            log.d(tag = TAG) { "AudioManager not available!" }
        }
    }

    /**
     * Request audio focus for the call
     */
    private fun requestAudioFocus() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    // Handle audio focus changes
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            log.d(tag = TAG) { "Audio focus gained" }
                            setAudioEnabled(true)
                        }
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            log.d(tag = TAG) { "Audio focus lost" }
                        }
                    }
                }
                .build()

            audioFocusRequest = focusRequest
            val result = audioManager?.requestAudioFocus(focusRequest) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
            log.d(tag = TAG) { "Audio focus request result: $result" }

        } else {
            // Legacy audio focus request for older Android versions
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

            log.d(tag = TAG) { "Legacy audio focus request result: $result" }
        }
    }

    /**
     * Releases audio focus and restores previous audio settings
     */
    private fun releaseAudioFocus() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
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

            log.d(tag = TAG) { "Restored audio state" }
        }
    }

    /**
     * Gets available audio input devices (microphones)
     */
    suspend fun getAudioInputDevices(): List<MediaDeviceInfo> {
        return MediaDevices.enumerateDevices()
    }

    /**
     * Clean up and release WebRTC resources
     */
    override fun dispose() {
        log.d(tag = TAG) { "Disposing WebRTC resources..." }
        releaseAudioFocus()
        cleanupCall()
        isInitialized = false
        isLocalAudioReady = false
    }

    /**
     * Create an SDP offer for starting a call
     * @return The SDP offer string
     */
    override suspend fun createOffer(): String {
        log.d(tag = TAG) { "Creating SDP offer..." }

        // Make sure WebRTC and audio is initialized
        if (!isInitialized) {
            log.d(tag = TAG) { "WebRTC not initialized, initializing now" }
            initialize()
        } else {
            // Reinitialize audio settings for outgoing call
            initializeAudio()
        }

        val peerConn = peerConnection ?: run {
            log.d(tag = TAG) { "PeerConnection not initialized, reinitializing" }
            initializePeerConnection()
            peerConnection ?: throw IllegalStateException("PeerConnection initialization failed")
        }

        // Make sure local audio track is added and enabled before creating an offer
        if (!isLocalAudioReady) {
            log.d(tag = TAG) { "Ensuring local audio track is ready..." }
            isLocalAudioReady = ensureLocalAudioTrack()
            if (!isLocalAudioReady) {
                log.d(tag = TAG) { "Failed to prepare local audio track!" }
                // Continue anyway to create the offer, but log the issue
            }
        }

        val options = OfferAnswerOptions(
            voiceActivityDetection = true
        )

        val sessionDescription = peerConn.createOffer(options)
        peerConn.setLocalDescription(sessionDescription)

        // Ensure microphone is unmuted for outgoing call
        audioManager?.isMicrophoneMute = false

        log.d(tag = TAG) { "Created offer SDP: ${sessionDescription.sdp}" }
        return sessionDescription.sdp
    }

    /**
     * Create an SDP answer in response to an offer
     * @param accountInfo The current account information
     * @param offerSdp The SDP offer from the remote party
     * @return The SDP answer string
     */
    override suspend fun createAnswer(accountInfo: AccountInfo, offerSdp: String): String {
        log.d(tag = TAG) { "Creating SDP answer..." }

        // Make sure WebRTC and audio is initialized
        if (!isInitialized) {
            log.d(tag = TAG) { "WebRTC not initialized, initializing now" }
            initialize()
        } else {
            // Reinitialize audio settings for incoming call
            initializeAudio()
        }

        val peerConn = peerConnection ?: run {
            log.d(tag = TAG) { "PeerConnection not initialized, reinitializing" }
            initializePeerConnection()
            peerConnection ?: throw IllegalStateException("PeerConnection initialization failed")
        }

        // IMPORTANT: Make sure local audio track is added BEFORE setting remote description
        // This is a critical fix for incoming calls
        if (!isLocalAudioReady) {
            log.d(tag = TAG) { "Ensuring local audio track is ready before answering..." }
            isLocalAudioReady = ensureLocalAudioTrack()
            if (!isLocalAudioReady) {
                log.d(tag = TAG) { "Failed to prepare local audio track for answering!" }
                // Continue anyway to create the answer, but log the issue
            }
        }

        // Set the remote offer
        val remoteOffer = SessionDescription(
            type = SessionDescriptionType.Offer,
            sdp = offerSdp
        )
        peerConn.setRemoteDescription(remoteOffer)

        // Create answer
        val options = OfferAnswerOptions(
            voiceActivityDetection = true
        )

        val sessionDescription = peerConn.createAnswer(options)
        peerConn.setLocalDescription(sessionDescription)

        // Ensure audio is enabled for answering the call
        setAudioEnabled(true)

        // Explicitly ensure microphone is not muted for incoming call
        audioManager?.isMicrophoneMute = false

        log.d(tag = TAG) { "Created answer SDP: ${sessionDescription.sdp}" }
        return sessionDescription.sdp
    }

    /**
     * Set the remote description (offer or answer)
     * @param sdp The remote SDP string
     * @param type The SDP type (offer or answer)
     */
    override suspend fun setRemoteDescription(sdp: String, type: SdpType) {
        log.d(tag = TAG) { "Setting remote description type: $type" }

        // Make sure WebRTC is initialized
        if (!isInitialized) {
            log.d(tag = TAG) { "WebRTC not initialized, initializing now" }
            initialize()
        }

        val peerConn = peerConnection ?: run {
            log.d(tag = TAG) { "PeerConnection not initialized, reinitializing" }
            initializePeerConnection()
            peerConnection ?: throw IllegalStateException("PeerConnection initialization failed")
        }

        // If this is an offer, ensure we have local audio ready before proceeding
        if (type == SdpType.OFFER && !isLocalAudioReady) {
            log.d(tag = TAG) { "Ensuring local audio track is ready before processing offer..." }
            isLocalAudioReady = ensureLocalAudioTrack()
        }

        val sdpType = when (type) {
            SdpType.OFFER -> SessionDescriptionType.Offer
            SdpType.ANSWER -> SessionDescriptionType.Answer
        }

        val sessionDescription = SessionDescription(
            type = sdpType,
            sdp = sdp
        )

        peerConn.setRemoteDescription(sessionDescription)

        // If this was an answer to our offer, make sure audio is enabled
        if (type == SdpType.ANSWER) {
            setAudioEnabled(true)
            audioManager?.isMicrophoneMute = false
        }
    }

    /**
     * Add an ICE candidate received from the remote party
     * @param candidate The ICE candidate string
     * @param sdpMid The media ID
     * @param sdpMLineIndex The media line index
     */
    override suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        log.d(tag = TAG) { "Adding ICE candidate: $candidate" }

        // Make sure WebRTC is initialized
        if (!isInitialized) {
            log.d(tag = TAG) { "WebRTC not initialized, initializing now" }
            initialize()
            // If still no peer connection after initialize, return
            if (peerConnection == null) {
                log.d(tag = TAG) { "Failed to initialize PeerConnection, cannot add ICE candidate" }
                return
            }
        }

        val peerConn = peerConnection ?: run {
            log.d(tag = TAG) { "PeerConnection not available, cannot add ICE candidate" }
            return
        }

        val iceCandidate = IceCandidate(
            sdpMid = sdpMid ?: "",
            sdpMLineIndex = sdpMLineIndex ?: 0,
            candidate = candidate
        )

        peerConn.addIceCandidate(iceCandidate)
    }

    /**
     * Gets all available audio input and output devices
     * @return Pair of (input devices list, output devices list)
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        log.d(tag = TAG) { "Getting all audio devices..." }

        val inputDevices = mutableListOf<AudioDevice>()
        val outputDevices = mutableListOf<AudioDevice>()

        try {
            // Get the audio manager if it's not already initialized
            val am = audioManager ?: context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

            // Check for built-in earpiece (exists on phones, not on tablets/some devices)
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

                // Wired headsets typically have a microphone too
                inputDevices.add(AudioDevice(
                    name = "Wired Headset Microphone",
                    descriptor = "wired_headset_mic",
                    isOutput = false
                ))
            }

            // Check for Bluetooth SCO (Hands-free profile) devices
            if (am?.isBluetoothScoAvailableOffCall == true) {
                // Get connected Bluetooth devices if possible
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

                        // For hands-free/headset profiles, they have microphones
                        inputDevices.add(AudioDevice(
                            name = "$deviceName Microphone (Bluetooth)",
                            descriptor = "bluetooth_mic_$deviceAddress",
                            nativeDevice = device,
                            isOutput = false
                        ))
                    }
                } else {
                    // Fallback for older Android versions without device details
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

            log.d(tag = TAG) { "Found ${inputDevices.size} input and ${outputDevices.size} output devices" }
        } catch (e: Exception) {
            log.d(tag = TAG) { "Error getting audio devices: ${e.stackTraceToString()}" }
        }

        return Pair(inputDevices, outputDevices)
    }

    /**
     * Changes the audio output device during an active call
     * @param device The audio device to switch to
     * @return true if successful, false otherwise
     */
    override fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean {
        log.d(tag = TAG) { "Changing audio output to: ${device.name}" }

        if (!isInitialized || peerConnection == null) {
            log.d(tag = TAG) { "Cannot change audio output: WebRTC not initialized" }
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
                    // Start Bluetooth SCO for routing audio to Bluetooth headset
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                    log.d(tag = TAG) { "Audio routed to Bluetooth device" }
                }
                device.descriptor == "speaker" -> {
                    // Speaker mode
                    am.isSpeakerphoneOn = true
                    log.d(tag = TAG) { "Audio routed to speaker" }
                }
                device.descriptor == "wired_headset" -> {
                    // Wired headset (default routing with speaker off and bluetooth off)
                    log.d(tag = TAG) { "Audio routed to wired headset" }
                }
                device.descriptor == "earpiece" -> {
                    // Earpiece (default routing with all other options off)
                    log.d(tag = TAG) { "Audio routed to earpiece" }
                }
                else -> {
                    log.d(tag = TAG) { "Unknown audio device type: ${device.descriptor}" }
                    return false
                }
            }

            // Update state tracking
            webRtcEventListener?.onAudioDeviceChanged(device)
            return true
        } catch (e: Exception) {
            log.d(tag = TAG) { "Error changing audio output: ${e.stackTraceToString()}" }
            return false
        }
    }

    /**
     * Changes the audio input device (microphone) during an active call
     * @param device The audio input device to switch to
     * @return true if successful, false otherwise
     */
    override fun changeAudioInputDeviceDuringCall(device: AudioDevice): Boolean {
        log.d(tag = TAG) { "Changing audio input to: ${device.name}" }

        if (!isInitialized || peerConnection == null) {
            log.d(tag = TAG) { "Cannot change audio input: WebRTC not initialized" }
            return false
        }

        try {
            val am = audioManager ?: return false

            // For Bluetooth microphone
            if (device.descriptor.startsWith("bluetooth_mic_")) {
                if (!am.isBluetoothScoOn) {
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                }
                log.d(tag = TAG) { "Audio input set to Bluetooth microphone" }
                webRtcEventListener?.onAudioDeviceChanged(device)
                return true
            }

            // For wired headset microphone
            if (device.descriptor == "wired_headset_mic") {
                if (am.isBluetoothScoOn) {
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                }
                log.d(tag = TAG) { "Audio input set to wired headset microphone" }
                webRtcEventListener?.onAudioDeviceChanged(device)
                return true
            }

            // For built-in microphone
            if (device.descriptor == "builtin_mic") {
                if (am.isBluetoothScoOn) {
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                }
                log.d(tag = TAG) { "Audio input set to built-in microphone" }
                webRtcEventListener?.onAudioDeviceChanged(device)
                return true
            }

            log.d(tag = TAG) { "Unknown audio input device: ${device.descriptor}" }
            return false
        } catch (e: Exception) {
            log.d(tag = TAG) { "Error changing audio input: ${e.stackTraceToString()}" }
            return false
        }
    }

    /**
     * Gets the currently active input device (microphone)
     * @return The current audio input device or null if not determined
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun getCurrentInputDevice(): AudioDevice? {
        if (!isInitialized) {
            return null
        }

        try {
            val am = audioManager ?: return null

            // Determine current active input device
            return when {
                am.isBluetoothScoOn -> {
                    // Get paired Bluetooth device details if available on Android S+
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
            log.d(tag = TAG) { "Error getting current input device: ${e.stackTraceToString()}" }
            return null
        }
    }

    /**
     * Gets the currently active output device (speaker)
     * @return The current audio output device or null if not determined
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun getCurrentOutputDevice(): AudioDevice? {
        if (!isInitialized) {
            return null
        }

        try {
            val am = audioManager ?: return null

            // Determine current active output device
            return when {
                am.isBluetoothScoOn -> {
                    // Get paired Bluetooth device details if available on Android S+
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
                    // Default to earpiece on phones, or speaker on devices without earpiece
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
            log.d(tag = TAG) { "Error getting current output device: ${e.stackTraceToString()}" }
            return null
        }
    }

    /**
     * Sets the mute state for the local microphone
     * @param muted Whether the microphone should be muted
     */
    override fun setMuted(muted: Boolean) {
        log.d(tag = TAG) { "Setting microphone mute: $muted" }

        try {
            // Use AudioManager to mute microphone
            audioManager?.isMicrophoneMute = muted

            // Also disable the audio track if we have one
            localAudioTrack?.enabled = !muted
        } catch (e: Exception) {
            log.d(tag = TAG) { "Error setting mute state: ${e.stackTraceToString()}" }
        }
    }

    /**
     * Gets the current mute state of the microphone
     * @return true if muted, false otherwise
     */
    override fun isMuted(): Boolean {
        val isAudioManagerMuted = audioManager?.isMicrophoneMute ?: false
        val isTrackDisabled = localAudioTrack?.enabled?.not() ?: false

        // If either is muted/disabled, consider it muted
        return isAudioManagerMuted || isTrackDisabled
    }

    /**
     * Gets the local SDP description
     * @return The local SDP string, or null if not set
     */
    override fun getLocalDescription(): String? {
        return peerConnection?.localDescription?.sdp
    }

    /**
     * Sets the media direction (sendrecv, sendonly, recvonly, inactive)
     * @param direction The desired media direction
     */
    override suspend fun setMediaDirection(direction: WebRtcManager.MediaDirection) {
        log.d(tag = TAG) { "Setting media direction: $direction" }

        if (!isInitialized || peerConnection == null) {
            log.d(tag = TAG) { "Cannot set media direction: WebRTC not initialized" }
            return
        }

        val peerConn = peerConnection ?: return

        try {
            // Get current description
            val currentDesc = peerConn.localDescription ?: return

            // Change direction in SDP
            val modifiedSdp = updateSdpDirection(currentDesc.sdp, direction)

            // Create and set the modified local description
            val newDesc = SessionDescription(
                type = currentDesc.type,
                sdp = modifiedSdp
            )

            peerConn.setLocalDescription(newDesc)

            // If we have an answer/offer from remote side, we need to renegotiate
            if (peerConn.remoteDescription != null) {
                // Create new offer/answer to apply the changes
                val options = OfferAnswerOptions(
                    voiceActivityDetection = true
                )

                val sessionDesc = if (currentDesc.type == SessionDescriptionType.Offer) {
                    peerConn.createOffer(options)
                } else {
                    peerConn.createAnswer(options)
                }

                // Modify the new SDP to ensure our direction is applied
                val finalSdp = updateSdpDirection(sessionDesc.sdp, direction)
                val finalDesc = SessionDescription(
                    type = sessionDesc.type,
                    sdp = finalSdp
                )

                peerConn.setLocalDescription(finalDesc)
            }
        } catch (e: Exception) {
            log.d(tag = TAG) { "Error setting media direction: ${e.stackTraceToString()}" }
        }
    }

    /**
     * Modifies the SDP to update the media direction attribute
     */
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

            // Track media sections
            if (line.startsWith("m=")) {
                inMediaSection = true
                inAudioSection = line.startsWith("m=audio")
            }

            // Update direction in audio section
            if (inMediaSection && inAudioSection) {
                if (line.startsWith("a=sendrecv") ||
                    line.startsWith("a=sendonly") ||
                    line.startsWith("a=recvonly") ||
                    line.startsWith("a=inactive")) {
                    lines[i] = "a=$directionStr"
                }
            }

            // End of section
            if (inMediaSection && line.trim().isEmpty()) {
                inMediaSection = false
                inAudioSection = false
            }
        }

        return lines.joinToString("\r\n")
    }

    /**
     * Applies modified SDP to the peer connection
     * @param modifiedSdp The modified SDP string
     * @return true if successful, false otherwise
     */
    override suspend fun applyModifiedSdp(modifiedSdp: String): Boolean {
        return try {
            val description = SessionDescription(SessionDescriptionType.Offer, modifiedSdp)
            peerConnection?.setLocalDescription(description)
            true
        } catch (e: Exception) {
            log.d(tag = TAG) { "Error applying modified SDP: ${e.stackTraceToString()}" }
            false
        }
    }

    /**
     * Extension function to check if a device has an earpiece
     * Most phones have an earpiece, but tablets and some devices don't
     */
    private fun AudioManager.hasEarpiece(): Boolean {
        // This is a heuristic approach - we can't directly detect earpiece
        // Most phones support MODE_IN_COMMUNICATION, tablets typically don't
        return context.packageManager?.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ?: false
    }

    /**
     * Extension function to check if a Bluetooth device is connected (Android S+ only)
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @RequiresApi(Build.VERSION_CODES.S)
    private fun BluetoothDevice.isConnected(): Boolean {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
        return connectedDevices.contains(this)
    }

    /**
     * Enable or disable the local audio track
     * @param enabled Whether audio should be enabled
     */
    override fun setAudioEnabled(enabled: Boolean) {
        log.d(tag = TAG) { "Setting audio enabled: $enabled" }

        // Use AudioManager to ensure microphone state
        audioManager?.isMicrophoneMute = !enabled

        if (localAudioTrack == null && isInitialized) {
            log.d(tag = TAG) { "No local audio track but WebRTC is initialized, trying to add audio track" }
            coroutineScope.launch {
                ensureLocalAudioTrack()
                localAudioTrack?.enabled = enabled
            }
        } else {
            localAudioTrack?.enabled = enabled
        }
    }

    /**
     * Get current connection state
     * @return The connection state
     */
    override fun getConnectionState(): WebRtcConnectionState {
        if (!isInitialized || peerConnection == null) {
            return WebRtcConnectionState.NEW
        }

        val state = peerConnection?.connectionState ?: return WebRtcConnectionState.NEW
        return mapConnectionState(state)
    }

    /**
     * Set a listener for WebRTC events
     * @param listener The WebRTC event listener
     */
    override fun setListener(listener: Any) {
        if (listener is WebRtcEventListener) {
            webRtcEventListener = listener
            log.d(tag = TAG) { "WebRTC event listener set" }
        } else {
            log.d(tag = TAG) { "Invalid listener type provided" }
        }
    }

    override fun prepareAudioForIncomingCall() {
        log.d(tag = TAG) { "Preparing audio for incoming call" }
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

    /**
     * Send DTMF tones via RTP (RFC 4733)
     * @param tones The DTMF tones to send (0-9, *, #, A-D)
     * @param duration Duration in milliseconds for each tone (optional, default 100ms)
     * @param gap Gap between tones in milliseconds (optional, default 70ms)
     * @return true if successfully started sending tones, false otherwise
     */
    override fun sendDtmfTones(tones: String, duration: Int, gap: Int): Boolean {
        log.d(tag = TAG) { "Sending DTMF tones: $tones (duration: $duration, gap: $gap)" }

        // Check if WebRTC is initialized and connection is established
        if (!isInitialized || peerConnection == null) {
            log.d(tag = TAG) { "Cannot send DTMF: WebRTC not initialized" }
            return false
        }

        try {
            // Get audio sender
            val audioSender = peerConnection?.getSenders()?.find { sender ->
                sender.track?.kind == MediaStreamTrackKind.Audio
            }

            if (audioSender == null) {
                log.d(tag = TAG) { "Cannot send DTMF: No audio sender found" }
                return false
            }

            // Get the DTMF sender for this audio track
            val dtmfSender = audioSender.dtmf ?: run {
                log.d(tag = TAG) { "Cannot send DTMF: DtmfSender not available" }
                return false
            }

            // Send the DTMF tones
            val sanitizedTones = sanitizeDtmfTones(tones)
            if (sanitizedTones.isEmpty()) {
                log.d(tag = TAG) { "Cannot send DTMF: No valid tones to send" }
                return false
            }

            val result = dtmfSender.insertDtmf(
                tones = sanitizedTones,
                durationMs = duration,
                interToneGapMs = gap
            )

            log.d(tag = TAG) { "DTMF tone sending result: $result" }
            return result
        } catch (e: Exception) {
            log.d(tag = TAG) { "Error sending DTMF tones: ${e.stackTraceToString()}" }
            return false
        }
    }

    /**
     * Sanitizes DTMF tones to ensure only valid characters are sent
     * Valid DTMF characters are 0-9, *, #, A-D, and comma (,) for pause
     */
    private fun sanitizeDtmfTones(tones: String): String {
        // WebRTC supports 0-9, *, #, A-D and comma (,) for pause
        val validDtmfPattern = Regex("[0-9A-D*#,]", RegexOption.IGNORE_CASE)

        return tones.filter { tone ->
            validDtmfPattern.matches(tone.toString())
        }
    }

    /**
     * Maps WebRTC's PeerConnectionState to our WebRtcConnectionState enum
     */
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

    /**
     * Initializes the PeerConnection with ICE configuration and sets up event observers.
     */
    private fun initializePeerConnection() {
        log.d(tag = TAG) { "Initializing PeerConnection..." }
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

            log.d(tag = TAG) { "RTC Configuration: $rtcConfig" }

            peerConnection = PeerConnection(rtcConfig).apply {
                setupPeerConnectionObservers()
            }

            log.d(tag = TAG) { "PeerConnection created: ${peerConnection != null}" }

            // Don't add local audio track here - will be done when needed
            // This prevents requesting microphone permission unnecessarily
            isLocalAudioReady = false
        } catch (e: Exception) {
            log.d(tag = TAG) { "Error initializing PeerConnection: ${e.stackTraceToString()}" }
            peerConnection = null
            isInitialized = false
            isLocalAudioReady = false
        }
    }

    /**
     * Configures the observers for the PeerConnection events.
     */
    private fun PeerConnection.setupPeerConnectionObservers() {
        onIceCandidate.onEach { candidate ->
            log.d(tag = TAG) { "New ICE Candidate: ${candidate.candidate}" }

            // Notify the listener
            webRtcEventListener?.onIceCandidate(
                candidate.candidate,
                candidate.sdpMid,
                candidate.sdpMLineIndex
            )
        }.launchIn(coroutineScope)

        onConnectionStateChange.onEach { state ->
            log.d(tag = TAG) { "Connection state changed: $state" }

            // Update call state based on connection state
            when (state) {
                PeerConnectionState.Connected -> {
                    log.d(tag = TAG) { "Call active: Connected" }
                    CallStateManager.updateCallState(CallState.CONNECTED)
                    // Ensure audio is enabled when connected and microphone is not muted
                    setAudioEnabled(true)
                    audioManager?.isMicrophoneMute = false
                }

                PeerConnectionState.Disconnected,
                PeerConnectionState.Failed,
                PeerConnectionState.Closed -> {
                    CallStateManager.updateCallState(CallState.ENDED)
                    log.d(tag = TAG) { "Call ended" }
                    // Release audio focus when call ends
                    releaseAudioFocus()
                }

                else -> {
                    log.d(tag = TAG) { "Other connection state: $state" }
                }
            }

            // Notify the listener
            webRtcEventListener?.onConnectionStateChange(mapConnectionState(state))
        }.launchIn(coroutineScope)

        onTrack.onEach { event ->
            log.d(tag = TAG) { "Remote track received: $event" }
            val track = event.receiver.track

            if (track is AudioStreamTrack) {
                log.d(tag = TAG) { "Remote audio track established" }
                remoteAudioTrack = track
                remoteAudioTrack?.enabled = true

                // Notify the listener
                webRtcEventListener?.onRemoteAudioTrack()
            }
        }.launchIn(coroutineScope)
    }

    /**
     * Ensures the local audio track is created and added to the PeerConnection.
     * Returns true if successful, false otherwise.
     */
    private suspend fun ensureLocalAudioTrack(): Boolean {
        return try {
            val peerConn = peerConnection ?: run {
                log.d(tag = TAG) { "PeerConnection not initialized" }
                return false
            }

            // Check if we already have a track
            if (localAudioTrack != null) {
                log.d(tag = TAG) { "Local audio track already exists" }
                return true
            }

            // Make sure audio mode is set for communication
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager?.isMicrophoneMute = false

            log.d(tag = TAG) { "Getting local audio stream..." }

            val mediaStream = MediaDevices.getUserMedia(
                audio = true,
                video = false
            )

            val audioTrack = mediaStream.audioTracks.firstOrNull()
            if (audioTrack != null) {
                log.d(tag = TAG) { "Audio track obtained successfully!" }

                localAudioTrack = audioTrack
                localAudioTrack?.enabled = true

                peerConn.addTrack(audioTrack, mediaStream)

                log.d(tag = TAG) { "Audio track added successfully: ${audioTrack.label}" }

                // Additional troubleshooting for audio routing
                val outputDevice = when {
                    audioManager?.isBluetoothScoOn == true -> "Bluetooth SCO"
                    audioManager?.isBluetoothA2dpOn == true -> "Bluetooth A2DP"
                    audioManager?.isSpeakerphoneOn == true -> "Speakerphone"
                    audioManager?.isWiredHeadsetOn == true -> "Wired Headset"
                    else -> "Earpiece/Default"
                }

                log.d(tag = TAG) { "Current audio output device: $outputDevice" }

                true
            } else {
                log.d(tag = TAG) { "Error: No audio track found" }
                false
            }
        } catch (e: Exception) {
            log.d(tag = TAG) { "Error getting audio: ${e.stackTraceToString()}" }
            false
        }
    }

    /**
     * Cleans up call resources
     */
    private fun cleanupCall() {
        try {
            // First, stop any active media operations
            localAudioTrack?.enabled = false

            // Remove tracks from peer connection
            peerConnection?.let { pc ->
                pc.getSenders().forEach { sender ->
                    try {
                        pc.removeTrack(sender)
                    } catch (e: Exception) {
                        log.d(tag = TAG) { "Error removing track: ${e.message}" }
                    }
                }
            }

            // Close peer connection first
            peerConnection?.close()
            peerConnection = null

            // Wait a short moment to ensure connections are closed
            Thread.sleep(100)

            // Dispose of media resources
            localAudioTrack = null
            remoteAudioTrack = null
            isLocalAudioReady = false

            // Force garbage collection to ensure native objects are released
            System.gc()

        } catch (e: Exception) {
            log.d(tag = TAG) { "Error in cleanupCall: ${e.stackTraceToString()}" }
        }
    }
}