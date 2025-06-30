package com.eddyslarez.siplibrary.data.services.audio

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
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
 * Enhanced Android implementation of WebRtcManager interface with comprehensive audio device support
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

    // Enhanced audio management fields
    private var audioManager: AudioManager? = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothManager: BluetoothManager? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var currentInputDevice: AudioDevice? = null
    private var currentOutputDevice: AudioDevice? = null

    // Audio state management
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var savedIsSpeakerPhoneOn = false
    private var savedIsMicrophoneMute = false
    private var audioFocusRequest: AudioFocusRequest? = null

    // Device monitoring
    private var deviceChangeListeners = mutableListOf<(List<AudioDevice>) -> Unit>()

    init {
        initializeBluetoothComponents()
        setupAudioDeviceMonitoring()
    }

    /**
     * Initialize Bluetooth components for enhanced device detection
     */
    private fun initializeBluetoothComponents() {
        try {
            bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter
            log.d(TAG) { "Bluetooth components initialized" }
        } catch (e: Exception) {
            log.e(TAG) { "Error initializing Bluetooth: ${e.message}" }
        }
    }

    /**
     * Setup audio device monitoring for real-time changes
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun setupAudioDeviceMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioDeviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    log.d(TAG) { "Audio devices added: ${addedDevices.size}" }
                    notifyDeviceChange()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    log.d(TAG) { "Audio devices removed: ${removedDevices.size}" }
                    notifyDeviceChange()
                }
            }
            audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
        }
    }

    /**
     * Notify listeners about device changes
     */
    private fun notifyDeviceChange() {
        try {
            val (inputs, outputs) = getAllAudioDevices()
            val allDevices = inputs + outputs
            deviceChangeListeners.forEach { listener ->
                try {
                    listener(allDevices)
                } catch (e: Exception) {
                    log.e(TAG) { "Error in device change listener: ${e.message}" }
                }
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error notifying device change: ${e.message}" }
        }
    }

    /**
     * Initialize the WebRTC subsystem
     */
    override fun initialize() {
        log.d(TAG) { "Initializing WebRTC Manager..." }
        if (!isInitialized) {
            initializeAudio()
            initializePeerConnection()
            coroutineScope.launch {
                getAudioInputDevices()
            }
            isInitialized = true
        } else {
            log.d(TAG) { "WebRTC already initialized" }
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

            log.d(TAG) { "Saved audio state - Mode: $savedAudioMode, Speaker: $savedIsSpeakerPhoneOn, Mic muted: $savedIsMicrophoneMute" }

            // Configure audio for communication
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = false
            am.isMicrophoneMute = false

            // Request audio focus
            requestAudioFocus()

            log.d(TAG) { "Audio configured for WebRTC communication" }
        } ?: run {
            log.d(TAG) { "AudioManager not available!" }
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
                            log.d(TAG) { "Audio focus gained" }
                            setAudioEnabled(true)
                        }
                        AudioManager.AUDIOFOCUS_LOSS -> {
                            log.d(TAG) { "Audio focus lost" }
                        }
                    }
                }
                .build()

            audioFocusRequest = focusRequest
            val result = audioManager?.requestAudioFocus(focusRequest) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
            log.d(TAG) { "Audio focus request result: $result" }

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

            log.d(TAG) { "Legacy audio focus request result: $result" }
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

            log.d(TAG) { "Restored audio state" }
        }
    }

    /**
     * Gets available audio input devices (microphones)
     */
    suspend fun getAudioInputDevices(): List<MediaDeviceInfo> {
        return MediaDevices.enumerateDevices()
    }

    /**
     * Enhanced device detection with comprehensive AudioDevice mapping
     */
    @RequiresPermission(allOf = [
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.RECORD_AUDIO
    ])
    override fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        log.d(TAG) { "Getting all enhanced audio devices..." }

        val inputDevices = mutableListOf<AudioDevice>()
        val outputDevices = mutableListOf<AudioDevice>()

        try {
            val am = audioManager ?: return Pair(emptyList(), emptyList())

            // Get current device states for comparison
            val currentInputDescriptor = getCurrentAudioInputDescriptor()
            val currentOutputDescriptor = getCurrentAudioOutputDescriptor()
            val defaultInputDescriptor = getDefaultInputDescriptor()
            val defaultOutputDescriptor = getDefaultOutputDescriptor()

            // Built-in devices (always available)
            addBuiltInDevices(inputDevices, outputDevices,
                currentInputDescriptor, currentOutputDescriptor,
                defaultInputDescriptor, defaultOutputDescriptor)

            // Wired devices
            addWiredDevices(inputDevices, outputDevices,
                currentInputDescriptor, currentOutputDescriptor)

            // Bluetooth devices
            addBluetoothDevices(inputDevices, outputDevices,
                currentInputDescriptor, currentOutputDescriptor)

            // USB and other devices (API 23+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                addUsbAndOtherDevices(inputDevices, outputDevices,
                    currentInputDescriptor, currentOutputDescriptor)
            }

            log.d(TAG) { "Found ${inputDevices.size} input and ${outputDevices.size} output devices" }

        } catch (e: Exception) {
            log.e(TAG) { "Error getting audio devices: ${e.message}" }
            // Return basic fallback devices
            return getFallbackDevices()
        }

        return Pair(inputDevices, outputDevices)
    }

    /**
     * Add built-in audio devices
     */
    private fun addBuiltInDevices(
        inputDevices: MutableList<AudioDevice>,
        outputDevices: MutableList<AudioDevice>,
        currentInputDescriptor: String?,
        currentOutputDescriptor: String?,
        defaultInputDescriptor: String?,
        defaultOutputDescriptor: String?
    ) {
        // Built-in microphone
        val micDescriptor = "builtin_mic"
        val micAudioUnit = AudioUnit(
            type = AudioUnitTypes.MICROPHONE,
            capability = AudioUnitCompatibilities.RECORD,
            isCurrent = currentInputDescriptor == micDescriptor,
            isDefault = defaultInputDescriptor == micDescriptor
        )

        inputDevices.add(
            AudioDevice(
                name = "Built-in Microphone",
                descriptor = micDescriptor,
                nativeDevice = null,
                isOutput = false,
                audioUnit = micAudioUnit,
                connectionState = DeviceConnectionState.AVAILABLE,
                isWireless = false,
                supportsHDVoice = true,
                latency = 10,
                vendorInfo = "Built-in"
            )
        )

        // Earpiece (if available)
        if (audioManager?.hasEarpiece() == true) {
            val earpieceDescriptor = "earpiece"
            val earpieceAudioUnit = AudioUnit(
                type = AudioUnitTypes.EARPIECE,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = currentOutputDescriptor == earpieceDescriptor,
                isDefault = defaultOutputDescriptor == earpieceDescriptor
            )

            outputDevices.add(
                AudioDevice(
                    name = "Earpiece",
                    descriptor = earpieceDescriptor,
                    nativeDevice = null,
                    isOutput = true,
                    audioUnit = earpieceAudioUnit,
                    connectionState = DeviceConnectionState.AVAILABLE,
                    isWireless = false,
                    supportsHDVoice = true,
                    latency = 5,
                    vendorInfo = "Built-in"
                )
            )
        }

        // Built-in speaker
        val speakerDescriptor = "speaker"
        val speakerAudioUnit = AudioUnit(
            type = AudioUnitTypes.SPEAKER,
            capability = AudioUnitCompatibilities.PLAY,
            isCurrent = currentOutputDescriptor == speakerDescriptor,
            isDefault = if (audioManager?.hasEarpiece() == true) false else defaultOutputDescriptor == speakerDescriptor
        )

        outputDevices.add(
            AudioDevice(
                name = "Speaker",
                descriptor = speakerDescriptor,
                nativeDevice = null,
                isOutput = true,
                audioUnit = speakerAudioUnit,
                connectionState = DeviceConnectionState.AVAILABLE,
                isWireless = false,
                supportsHDVoice = true,
                latency = 15,
                vendorInfo = "Built-in"
            )
        )
    }

    /**
     * Add wired audio devices
     */
    private fun addWiredDevices(
        inputDevices: MutableList<AudioDevice>,
        outputDevices: MutableList<AudioDevice>,
        currentInputDescriptor: String?,
        currentOutputDescriptor: String?
    ) {
        if (audioManager?.isWiredHeadsetOn == true) {
            // Wired headset output
            val headsetOutDescriptor = "wired_headset"
            val headsetOutAudioUnit = AudioUnit(
                type = AudioUnitTypes.HEADSET,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = currentOutputDescriptor == headsetOutDescriptor,
                isDefault = false
            )

            outputDevices.add(
                AudioDevice(
                    name = "Wired Headset",
                    descriptor = headsetOutDescriptor,
                    nativeDevice = null,
                    isOutput = true,
                    audioUnit = headsetOutAudioUnit,
                    connectionState = DeviceConnectionState.CONNECTED,
                    isWireless = false,
                    supportsHDVoice = true,
                    latency = 20,
                    vendorInfo = extractVendorFromWiredDevice()
                )
            )

            // Wired headset microphone
            val headsetMicDescriptor = "wired_headset_mic"
            val headsetMicAudioUnit = AudioUnit(
                type = AudioUnitTypes.HEADSET,
                capability = AudioUnitCompatibilities.RECORD,
                isCurrent = currentInputDescriptor == headsetMicDescriptor,
                isDefault = false
            )

            inputDevices.add(
                AudioDevice(
                    name = "Wired Headset Microphone",
                    descriptor = headsetMicDescriptor,
                    nativeDevice = null,
                    isOutput = false,
                    audioUnit = headsetMicAudioUnit,
                    connectionState = DeviceConnectionState.CONNECTED,
                    isWireless = false,
                    supportsHDVoice = true,
                    latency = 20,
                    vendorInfo = extractVendorFromWiredDevice()
                )
            )
        }
    }

    /**
     * Add Bluetooth audio devices
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun addBluetoothDevices(
        inputDevices: MutableList<AudioDevice>,
        outputDevices: MutableList<AudioDevice>,
        currentInputDescriptor: String?,
        currentOutputDescriptor: String?
    ) {
        try {
            if (audioManager?.isBluetoothScoAvailableOffCall == true) {
                val connectedBluetoothDevices = getConnectedBluetoothDevices()

                connectedBluetoothDevices.forEach { bluetoothDevice ->
                    val deviceName = getBluetoothDeviceName(bluetoothDevice)
                    val deviceAddress = bluetoothDevice.address
                    val vendorInfo = extractVendorFromBluetoothDevice(bluetoothDevice)
                    val signalStrength = estimateBluetoothSignalStrength()
                    val batteryLevel = getBluetoothBatteryLevel(bluetoothDevice)

                    // Determine device type and capabilities
                    val (audioUnitType, supportsA2DP) = classifyBluetoothDevice(bluetoothDevice)

                    // Bluetooth output device
                    val btOutDescriptor = "bluetooth_${deviceAddress}"
                    val btOutAudioUnit = AudioUnit(
                        type = audioUnitType,
                        capability = AudioUnitCompatibilities.PLAY,
                        isCurrent = currentOutputDescriptor == btOutDescriptor,
                        isDefault = false
                    )

                    outputDevices.add(
                        AudioDevice(
                            name = deviceName,
                            descriptor = btOutDescriptor,
                            nativeDevice = bluetoothDevice,
                            isOutput = true,
                            audioUnit = btOutAudioUnit,
                            connectionState = getBluetoothConnectionState(bluetoothDevice),
                            signalStrength = signalStrength,
                            batteryLevel = batteryLevel,
                            isWireless = true,
                            supportsHDVoice = supportsA2DP,
                            latency = if (supportsA2DP) 200 else 150,
                            vendorInfo = vendorInfo
                        )
                    )

                    // Bluetooth input device (for HFP/HSP)
                    if (supportsHandsFreeProfile(bluetoothDevice)) {
                        val btMicDescriptor = "bluetooth_mic_${deviceAddress}"
                        val btMicAudioUnit = AudioUnit(
                            type = AudioUnitTypes.BLUETOOTH,
                            capability = AudioUnitCompatibilities.RECORD,
                            isCurrent = currentInputDescriptor == btMicDescriptor,
                            isDefault = false
                        )

                        inputDevices.add(
                            AudioDevice(
                                name = "$deviceName Microphone",
                                descriptor = btMicDescriptor,
                                nativeDevice = bluetoothDevice,
                                isOutput = false,
                                audioUnit = btMicAudioUnit,
                                connectionState = getBluetoothConnectionState(bluetoothDevice),
                                signalStrength = signalStrength,
                                batteryLevel = batteryLevel,
                                isWireless = true,
                                supportsHDVoice = false, // HFP typically doesn't support HD Voice
                                latency = 150,
                                vendorInfo = vendorInfo
                            )
                        )
                    }
                }
            }
        } catch (e: SecurityException) {
            log.w(TAG) { "Bluetooth permission not granted: ${e.message}" }
        } catch (e: Exception) {
            log.e(TAG) { "Error adding Bluetooth devices: ${e.message}" }
        }
    }

    /**
     * Add USB and other audio devices (API 23+)
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun addUsbAndOtherDevices(
        inputDevices: MutableList<AudioDevice>,
        outputDevices: MutableList<AudioDevice>,
        currentInputDescriptor: String?,
        currentOutputDescriptor: String?
    ) {
        try {
            val audioDevices = audioManager?.getDevices(AudioManager.GET_DEVICES_ALL) ?: return

            audioDevices.forEach { deviceInfo ->
                when (deviceInfo.type) {
                    AudioDeviceInfo.TYPE_USB_ACCESSORY,
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> {
                        addUsbDevice(deviceInfo, inputDevices, outputDevices,
                            currentInputDescriptor, currentOutputDescriptor)
                    }
                    AudioDeviceInfo.TYPE_DOCK -> {
                        addDockDevice(deviceInfo, inputDevices, outputDevices,
                            currentInputDescriptor, currentOutputDescriptor)
                    }
                    AudioDeviceInfo.TYPE_AUX_LINE -> {
                        addAuxDevice(deviceInfo, inputDevices, outputDevices,
                            currentInputDescriptor, currentOutputDescriptor)
                    }
                    AudioDeviceInfo.TYPE_HEARING_AID -> {
                        addHearingAidDevice(deviceInfo, inputDevices, outputDevices,
                            currentInputDescriptor, currentOutputDescriptor)
                    }
                }
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error adding USB/other devices: ${e.message}" }
        }
    }

    /**
     * Add USB audio device
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun addUsbDevice(
        deviceInfo: AudioDeviceInfo,
        inputDevices: MutableList<AudioDevice>,
        outputDevices: MutableList<AudioDevice>,
        currentInputDescriptor: String?,
        currentOutputDescriptor: String?
    ) {
        val deviceName = deviceInfo.productName?.toString() ?: "USB Audio Device"
        val deviceId = deviceInfo.id.toString()
        val isSource = deviceInfo.isSource
        val isSink = deviceInfo.isSink

        if (isSink) {
            val usbOutDescriptor = "usb_out_$deviceId"
            val usbOutAudioUnit = AudioUnit(
                type = AudioUnitTypes.GENERICUSB,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = currentOutputDescriptor == usbOutDescriptor,
                isDefault = false
            )

            outputDevices.add(
                AudioDevice(
                    name = deviceName,
                    descriptor = usbOutDescriptor,
                    nativeDevice = deviceInfo,
                    isOutput = true,
                    audioUnit = usbOutAudioUnit,
                    connectionState = DeviceConnectionState.CONNECTED,
                    isWireless = false,
                    supportsHDVoice = true,
                    latency = 30,
                    vendorInfo = extractVendorFromDeviceName(deviceName)
                )
            )
        }

        if (isSource) {
            val usbInDescriptor = "usb_in_$deviceId"
            val usbInAudioUnit = AudioUnit(
                type = AudioUnitTypes.GENERICUSB,
                capability = AudioUnitCompatibilities.RECORD,
                isCurrent = currentInputDescriptor == usbInDescriptor,
                isDefault = false
            )

            inputDevices.add(
                AudioDevice(
                    name = "$deviceName Microphone",
                    descriptor = usbInDescriptor,
                    nativeDevice = deviceInfo,
                    isOutput = false,
                    audioUnit = usbInAudioUnit,
                    connectionState = DeviceConnectionState.CONNECTED,
                    isWireless = false,
                    supportsHDVoice = true,
                    latency = 30,
                    vendorInfo = extractVendorFromDeviceName(deviceName)
                )
            )
        }
    }

    /**
     * Add dock audio device
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun addDockDevice(
        deviceInfo: AudioDeviceInfo,
        inputDevices: MutableList<AudioDevice>,
        outputDevices: MutableList<AudioDevice>,
        currentInputDescriptor: String?,
        currentOutputDescriptor: String?
    ) {
        val deviceName = "Dock Audio"
        val deviceId = deviceInfo.id.toString()

        if (deviceInfo.isSink) {
            val dockDescriptor = "dock_$deviceId"
            val dockAudioUnit = AudioUnit(
                type = AudioUnitTypes.AUXLINE,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = currentOutputDescriptor == dockDescriptor,
                isDefault = false
            )

            outputDevices.add(
                AudioDevice(
                    name = deviceName,
                    descriptor = dockDescriptor,
                    nativeDevice = deviceInfo,
                    isOutput = true,
                    audioUnit = dockAudioUnit,
                    connectionState = DeviceConnectionState.CONNECTED,
                    isWireless = false,
                    supportsHDVoice = false,
                    latency = 25,
                    vendorInfo = null
                )
            )
        }
    }

    /**
     * Add auxiliary line device
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun addAuxDevice(
        deviceInfo: AudioDeviceInfo,
        inputDevices: MutableList<AudioDevice>,
        outputDevices: MutableList<AudioDevice>,
        currentInputDescriptor: String?,
        currentOutputDescriptor: String?
    ) {
        val deviceName = "Auxiliary Audio"
        val deviceId = deviceInfo.id.toString()

        if (deviceInfo.isSink) {
            val auxDescriptor = "aux_$deviceId"
            val auxAudioUnit = AudioUnit(
                type = AudioUnitTypes.AUXLINE,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = currentOutputDescriptor == auxDescriptor,
                isDefault = false
            )

            outputDevices.add(
                AudioDevice(
                    name = deviceName,
                    descriptor = auxDescriptor,
                    nativeDevice = deviceInfo,
                    isOutput = true,
                    audioUnit = auxAudioUnit,
                    connectionState = DeviceConnectionState.CONNECTED,
                    isWireless = false,
                    supportsHDVoice = false,
                    latency = 15,
                    vendorInfo = null
                )
            )
        }
    }

    /**
     * Add hearing aid device
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun addHearingAidDevice(
        deviceInfo: AudioDeviceInfo,
        inputDevices: MutableList<AudioDevice>,
        outputDevices: MutableList<AudioDevice>,
        currentInputDescriptor: String?,
        currentOutputDescriptor: String?
    ) {
        val deviceName = "Hearing Aid"
        val deviceId = deviceInfo.id.toString()

        if (deviceInfo.isSink) {
            val hearingAidDescriptor = "hearing_aid_$deviceId"
            val hearingAidAudioUnit = AudioUnit(
                type = AudioUnitTypes.HEARINGAID,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = currentOutputDescriptor == hearingAidDescriptor,
                isDefault = false
            )

            outputDevices.add(
                AudioDevice(
                    name = deviceName,
                    descriptor = hearingAidDescriptor,
                    nativeDevice = deviceInfo,
                    isOutput = true,
                    audioUnit = hearingAidAudioUnit,
                    connectionState = DeviceConnectionState.CONNECTED,
                    isWireless = true,
                    supportsHDVoice = true,
                    latency = 50,
                    vendorInfo = extractVendorFromDeviceName(deviceInfo.productName?.toString() ?: "")
                )
            )
        }
    }

    /**
     * Get fallback devices when detection fails
     */
    private fun getFallbackDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        val inputDevices = listOf(
            AudioDevice(
                name = "Built-in Microphone",
                descriptor = "builtin_mic",
                nativeDevice = null,
                isOutput = false,
                audioUnit = AudioUnit(
                    type = AudioUnitTypes.MICROPHONE,
                    capability = AudioUnitCompatibilities.RECORD,
                    isCurrent = true,
                    isDefault = true
                ),
                connectionState = DeviceConnectionState.AVAILABLE,
                isWireless = false,
                supportsHDVoice = true,
                latency = 10,
                vendorInfo = "Built-in"
            )
        )

        val outputDevices = mutableListOf<AudioDevice>()

        // Add earpiece if available
        if (audioManager?.hasEarpiece() == true) {
            outputDevices.add(
                AudioDevice(
                    name = "Earpiece",
                    descriptor = "earpiece",
                    nativeDevice = null,
                    isOutput = true,
                    audioUnit = AudioUnit(
                        type = AudioUnitTypes.EARPIECE,
                        capability = AudioUnitCompatibilities.PLAY,
                        isCurrent = true,
                        isDefault = true
                    ),
                    connectionState = DeviceConnectionState.AVAILABLE,
                    isWireless = false,
                    supportsHDVoice = true,
                    latency = 5,
                    vendorInfo = "Built-in"
                )
            )
        }

        // Always add speaker
        outputDevices.add(
            AudioDevice(
                name = "Speaker",
                descriptor = "speaker",
                nativeDevice = null,
                isOutput = true,
                audioUnit = AudioUnit(
                    type = AudioUnitTypes.SPEAKER,
                    capability = AudioUnitCompatibilities.PLAY,
                    isCurrent = !audioManager?.hasEarpiece()!!,
                    isDefault = !audioManager?.hasEarpiece()!!
                ),
                connectionState = DeviceConnectionState.AVAILABLE,
                isWireless = false,
                supportsHDVoice = true,
                latency = 15,
                vendorInfo = "Built-in"
            )
        )

        return Pair(inputDevices, outputDevices)
    }

    // Helper methods for device detection and classification

    /**
     * Get current audio input descriptor
     */
    private fun getCurrentAudioInputDescriptor(): String? {
        return when {
            audioManager?.isBluetoothScoOn == true -> "bluetooth_mic_active"
            audioManager?.isWiredHeadsetOn == true -> "wired_headset_mic"
            else -> "builtin_mic"
        }
    }

    /**
     * Get current audio output descriptor
     */
    private fun getCurrentAudioOutputDescriptor(): String? {
        return when {
            audioManager?.isBluetoothScoOn == true -> "bluetooth_active"
            audioManager?.isSpeakerphoneOn == true -> "speaker"
            audioManager?.isWiredHeadsetOn == true -> "wired_headset"
            else -> if (audioManager?.hasEarpiece() == true) "earpiece" else "speaker"
        }
    }

    /**
     * Get default input descriptor
     */
    private fun getDefaultInputDescriptor(): String {
        return "builtin_mic"
    }

    /**
     * Get default output descriptor
     */
    private fun getDefaultOutputDescriptor(): String {
        return if (audioManager?.hasEarpiece() == true) "earpiece" else "speaker"
    }

    /**
     * Get connected Bluetooth devices
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun getConnectedBluetoothDevices(): List<BluetoothDevice> {
        return try {
            bluetoothAdapter?.bondedDevices?.filter { device ->
                isBluetoothDeviceConnected(device)
            } ?: emptyList()
        } catch (e: SecurityException) {
            log.w(TAG) { "Bluetooth permission not granted for getting connected devices" }
            emptyList()
        } catch (e: Exception) {
            log.e(TAG) { "Error getting connected Bluetooth devices: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Check if Bluetooth device is connected
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun isBluetoothDeviceConnected(device: BluetoothDevice): Boolean {
        return try {
            // Use reflection to check connection state since there's no direct API
            val isConnectedMethod = BluetoothDevice::class.java.getMethod("isConnected")
            isConnectedMethod.invoke(device) as? Boolean ?: false
        } catch (e: Exception) {
            // Fallback: assume bonded devices are connected if Bluetooth audio is available
            audioManager?.isBluetoothScoAvailableOffCall == true
        }
    }

    /**
     * Get Bluetooth device name safely
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun getBluetoothDeviceName(device: BluetoothDevice): String {
        return try {
            device.name ?: "Bluetooth Device"
        } catch (e: SecurityException) {
            "Bluetooth Device"
        }
    }

    /**
     * Classify Bluetooth device type and capabilities
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun classifyBluetoothDevice(device: BluetoothDevice): Pair<AudioUnitTypes, Boolean> {
        return try {
            val deviceClass = device.bluetoothClass
            val majorClass = deviceClass?.majorDeviceClass
            val deviceType = deviceClass?.deviceClass

            val supportsA2DP = hasBluetoothProfile(device, BluetoothProfile.A2DP)
            val supportsHFP = hasBluetoothProfile(device, BluetoothProfile.HEADSET)

            val audioUnitType = when {
                supportsA2DP && supportsHFP -> AudioUnitTypes.BLUETOOTH
                supportsA2DP -> AudioUnitTypes.BLUETOOTHA2DP
                supportsHFP -> AudioUnitTypes.BLUETOOTH
                majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO -> {
                    if (supportsA2DP) AudioUnitTypes.BLUETOOTHA2DP else AudioUnitTypes.BLUETOOTH
                }
                else -> AudioUnitTypes.BLUETOOTH
            }

            Pair(audioUnitType, supportsA2DP)
        } catch (e: Exception) {
            log.w(TAG) { "Error classifying Bluetooth device: ${e.message}" }
            Pair(AudioUnitTypes.BLUETOOTH, false)
        }
    }

    /**
     * Check if device supports hands-free profile
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun supportsHandsFreeProfile(device: BluetoothDevice): Boolean {
        return hasBluetoothProfile(device, BluetoothProfile.HEADSET) ||
                hasBluetoothProfile(device, BluetoothProfile.HID_DEVICE)
    }

    /**
     * Check if device supports specific Bluetooth profile
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun hasBluetoothProfile(device: BluetoothDevice, profile: Int): Boolean {
        return try {
            bluetoothAdapter?.getProfileConnectionState(profile) == BluetoothProfile.STATE_CONNECTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get Bluetooth connection state
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun getBluetoothConnectionState(device: BluetoothDevice): DeviceConnectionState {
        return if (isBluetoothDeviceConnected(device)) {
            DeviceConnectionState.CONNECTED
        } else {
            DeviceConnectionState.AVAILABLE
        }
    }

    /**
     * Estimate Bluetooth signal strength
     */
    private fun estimateBluetoothSignalStrength(): Int {
        // In a real implementation, you might use RSSI values if available
        return (70..95).random()
    }

    /**
     * Get Bluetooth device battery level using reflection
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun getBluetoothBatteryLevel(device: BluetoothDevice): Int? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ - Try to use reflection to access hidden getBatteryLevel method
                val getBatteryLevelMethod = BluetoothDevice::class.java.getMethod("getBatteryLevel")
                val batteryLevel = getBatteryLevelMethod.invoke(device) as? Int

                // Check if battery level is valid (not -1 which typically means unknown)
                batteryLevel?.takeIf { it >= 0 && it <= 100 }
            } else {
                null
            }
        } catch (e: Exception) {
            log.w(TAG) { "Could not get battery level via reflection: ${e.message}" }
            null
        }
    }


    /**
     * Extract vendor information from wired device
     */
    private fun extractVendorFromWiredDevice(): String? {
        // This could be enhanced with more sophisticated detection
        return null
    }

    /**
     * Extract vendor from Bluetooth device
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun extractVendorFromBluetoothDevice(device: BluetoothDevice): String? {
        return try {
            val deviceName = device.name?.lowercase() ?: return null
            extractVendorFromDeviceName(deviceName)
        } catch (e: SecurityException) {
            null
        }
    }

    /**
     * Extract vendor information from device name
     */
    private fun extractVendorFromDeviceName(deviceName: String): String? {
        val name = deviceName.lowercase()
        return when {
            name.contains("apple") || name.contains("airpods") -> "Apple"
            name.contains("samsung") -> "Samsung"
            name.contains("sony") -> "Sony"
            name.contains("bose") -> "Bose"
            name.contains("jabra") -> "Jabra"
            name.contains("plantronics") -> "Plantronics"
            name.contains("logitech") -> "Logitech"
            name.contains("sennheiser") -> "Sennheiser"
            name.contains("jbl") -> "JBL"
            name.contains("beats") -> "Beats"
            name.contains("google") -> "Google"
            name.contains("microsoft") -> "Microsoft"
            name.contains("razer") -> "Razer"
            name.contains("steelseries") -> "SteelSeries"
            else -> null
        }
    }

    /**
     * Enhanced device change during call with improved error handling
     */
    override fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean {
        log.d(TAG) { "Changing audio output to: ${device.name} (${device.descriptor})" }

        if (!isInitialized || peerConnection == null) {
            log.w(TAG) { "Cannot change audio output: WebRTC not initialized" }
            return false
        }

        return try {
            val am = audioManager ?: return false
            val success = when {
                device.descriptor.startsWith("bluetooth_") -> {
                    switchToBluetoothOutput(device, am)
                }
                device.descriptor == "speaker" -> {
                    switchToSpeaker(am)
                }
                device.descriptor == "wired_headset" -> {
                    switchToWiredHeadset(am)
                }
                device.descriptor == "earpiece" -> {
                    switchToEarpiece(am)
                }
                device.descriptor.startsWith("usb_out_") -> {
                    switchToUsbOutput(device, am)
                }
                else -> {
                    log.w(TAG) { "Unknown audio device type: ${device.descriptor}" }
                    false
                }
            }

            if (success) {
                currentOutputDevice = device
                webRtcEventListener?.onAudioDeviceChanged(device)
                log.d(TAG) { "Successfully changed audio output to: ${device.name}" }
            }

            success
        } catch (e: Exception) {
            log.e(TAG) { "Error changing audio output: ${e.message}" }
            false
        }
    }

    /**
     * Enhanced device change for input during call
     */
    override fun changeAudioInputDeviceDuringCall(device: AudioDevice): Boolean {
        log.d(TAG) { "Changing audio input to: ${device.name} (${device.descriptor})" }

        if (!isInitialized || peerConnection == null) {
            log.w(TAG) { "Cannot change audio input: WebRTC not initialized" }
            return false
        }

        return try {
            val am = audioManager ?: return false
            val success = when {
                device.descriptor.startsWith("bluetooth_mic_") -> {
                    switchToBluetoothInput(device, am)
                }
                device.descriptor == "wired_headset_mic" -> {
                    switchToWiredHeadsetMic(am)
                }
                device.descriptor == "builtin_mic" -> {
                    switchToBuiltinMic(am)
                }
                device.descriptor.startsWith("usb_in_") -> {
                    switchToUsbInput(device, am)
                }
                else -> {
                    log.w(TAG) { "Unknown audio input device: ${device.descriptor}" }
                    false
                }
            }

            if (success) {
                currentInputDevice = device
                webRtcEventListener?.onAudioDeviceChanged(device)
                log.d(TAG) { "Successfully changed audio input to: ${device.name}" }
            }

            success
        } catch (e: Exception) {
            log.e(TAG) { "Error changing audio input: ${e.message}" }
            false
        }
    }

    // Audio switching methods

    private fun switchToBluetoothOutput(device: AudioDevice, am: AudioManager): Boolean {
        return try {
            // Reset other outputs
            am.isSpeakerphoneOn = false

            // Start Bluetooth SCO
            if (!am.isBluetoothScoOn) {
                am.startBluetoothSco()
            }
            am.isBluetoothScoOn = true

            true
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to Bluetooth output: ${e.message}" }
            false
        }
    }

    private fun switchToSpeaker(am: AudioManager): Boolean {
        return try {
            // Stop Bluetooth if active
            if (am.isBluetoothScoOn) {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
            }

            // Enable speaker
            am.isSpeakerphoneOn = true
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to speaker: ${e.message}" }
            false
        }
    }

    private fun switchToWiredHeadset(am: AudioManager): Boolean {
        return try {
            // Reset other outputs
            am.isSpeakerphoneOn = false
            if (am.isBluetoothScoOn) {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
            }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to wired headset: ${e.message}" }
            false
        }
    }

    private fun switchToEarpiece(am: AudioManager): Boolean {
        return try {
            // Reset all other outputs
            am.isSpeakerphoneOn = false
            if (am.isBluetoothScoOn) {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
            }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to earpiece: ${e.message}" }
            false
        }
    }

    private fun switchToUsbOutput(device: AudioDevice, am: AudioManager): Boolean {
        return try {
            // For USB devices, we might need to use AudioDeviceInfo routing (API 23+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && device.nativeDevice is AudioDeviceInfo) {
                // Reset other outputs first
                am.isSpeakerphoneOn = false
                if (am.isBluetoothScoOn) {
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                }
                // USB routing is typically handled automatically by the system
                true
            } else {
                false
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to USB output: ${e.message}" }
            false
        }
    }

    private fun switchToBluetoothInput(device: AudioDevice, am: AudioManager): Boolean {
        return try {
            if (!am.isBluetoothScoOn) {
                am.startBluetoothSco()
            }
            am.isBluetoothScoOn = true
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to Bluetooth input: ${e.message}" }
            false
        }
    }

    private fun switchToWiredHeadsetMic(am: AudioManager): Boolean {
        return try {
            if (am.isBluetoothScoOn) {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
            }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to wired headset mic: ${e.message}" }
            false
        }
    }

    private fun switchToBuiltinMic(am: AudioManager): Boolean {
        return try {
            if (am.isBluetoothScoOn) {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
            }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to builtin mic: ${e.message}" }
            false
        }
    }

    private fun switchToUsbInput(device: AudioDevice, am: AudioManager): Boolean {
        return try {
            // Similar to USB output, routing is typically automatic
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && device.nativeDevice is AudioDeviceInfo) {
                if (am.isBluetoothScoOn) {
                    am.stopBluetoothSco()
                    am.isBluetoothScoOn = false
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to USB input: ${e.message}" }
            false
        }
    }

    /**
     * Enhanced current device detection
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun getCurrentInputDevice(): AudioDevice? {
        if (currentInputDevice != null) {
            return currentInputDevice
        }

        return try {
            val am = audioManager ?: return null
            val descriptor = getCurrentAudioInputDescriptor()

            when {
                descriptor?.startsWith("bluetooth_mic_") == true -> {
                    findBluetoothInputDevice()
                }
                descriptor == "wired_headset_mic" -> {
                    createWiredHeadsetMicDevice()
                }
                else -> {
                    createBuiltinMicDevice()
                }
            }.also {
                currentInputDevice = it
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error getting current input device: ${e.message}" }
            createBuiltinMicDevice()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun getCurrentOutputDevice(): AudioDevice? {
        if (currentOutputDevice != null) {
            return currentOutputDevice
        }

        return try {
            val am = audioManager ?: return null
            val descriptor = getCurrentAudioOutputDescriptor()

            when {
                descriptor?.startsWith("bluetooth_") == true -> {
                    findBluetoothOutputDevice()
                }
                descriptor == "speaker" -> {
                    createSpeakerDevice()
                }
                descriptor == "wired_headset" -> {
                    createWiredHeadsetDevice()
                }
                descriptor == "earpiece" -> {
                    createEarpieceDevice()
                }
                else -> {
                    if (am.hasEarpiece()) createEarpieceDevice() else createSpeakerDevice()
                }
            }.also {
                currentOutputDevice = it
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error getting current output device: ${e.message}" }
            if (audioManager?.hasEarpiece() == true) createEarpieceDevice() else createSpeakerDevice()
        }
    }

    // Helper methods for creating device objects

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun findBluetoothInputDevice(): AudioDevice? {
        val connectedDevices = getConnectedBluetoothDevices()
        val firstDevice = connectedDevices.firstOrNull() ?: return null

        return AudioDevice(
            name = "${getBluetoothDeviceName(firstDevice)} Microphone",
            descriptor = "bluetooth_mic_${firstDevice.address}",
            nativeDevice = firstDevice,
            isOutput = false,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.BLUETOOTH,
                capability = AudioUnitCompatibilities.RECORD,
                isCurrent = true,
                isDefault = false
            ),
            connectionState = DeviceConnectionState.CONNECTED,
            signalStrength = estimateBluetoothSignalStrength(),
            batteryLevel = getBluetoothBatteryLevel(firstDevice),
            isWireless = true,
            supportsHDVoice = false,
            latency = 150,
            vendorInfo = extractVendorFromBluetoothDevice(firstDevice)
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun findBluetoothOutputDevice(): AudioDevice? {
        val connectedDevices = getConnectedBluetoothDevices()
        val firstDevice = connectedDevices.firstOrNull() ?: return null

        val (audioUnitType, supportsA2DP) = classifyBluetoothDevice(firstDevice)

        return AudioDevice(
            name = getBluetoothDeviceName(firstDevice),
            descriptor = "bluetooth_${firstDevice.address}",
            nativeDevice = firstDevice,
            isOutput = true,
            audioUnit = AudioUnit(
                type = audioUnitType,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = true,
                isDefault = false
            ),
            connectionState = DeviceConnectionState.CONNECTED,
            signalStrength = estimateBluetoothSignalStrength(),
            batteryLevel = getBluetoothBatteryLevel(firstDevice),
            isWireless = true,
            supportsHDVoice = supportsA2DP,
            latency = if (supportsA2DP) 200 else 150,
            vendorInfo = extractVendorFromBluetoothDevice(firstDevice)
        )
    }

    private fun createWiredHeadsetMicDevice(): AudioDevice {
        return AudioDevice(
            name = "Wired Headset Microphone",
            descriptor = "wired_headset_mic",
            nativeDevice = null,
            isOutput = false,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.HEADSET,
                capability = AudioUnitCompatibilities.RECORD,
                isCurrent = true,
                isDefault = false
            ),
            connectionState = DeviceConnectionState.CONNECTED,
            isWireless = false,
            supportsHDVoice = true,
            latency = 20,
            vendorInfo = extractVendorFromWiredDevice()
        )
    }

    private fun createBuiltinMicDevice(): AudioDevice {
        return AudioDevice(
            name = "Built-in Microphone",
            descriptor = "builtin_mic",
            nativeDevice = null,
            isOutput = false,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.MICROPHONE,
                capability = AudioUnitCompatibilities.RECORD,
                isCurrent = true,
                isDefault = true
            ),
            connectionState = DeviceConnectionState.AVAILABLE,
            isWireless = false,
            supportsHDVoice = true,
            latency = 10,
            vendorInfo = "Built-in"
        )
    }

    private fun createWiredHeadsetDevice(): AudioDevice {
        return AudioDevice(
            name = "Wired Headset",
            descriptor = "wired_headset",
            nativeDevice = null,
            isOutput = true,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.HEADSET,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = true,
                isDefault = false
            ),
            connectionState = DeviceConnectionState.CONNECTED,
            isWireless = false,
            supportsHDVoice = true,
            latency = 20,
            vendorInfo = extractVendorFromWiredDevice()
        )
    }

    private fun createSpeakerDevice(): AudioDevice {
        return AudioDevice(
            name = "Speaker",
            descriptor = "speaker",
            nativeDevice = null,
            isOutput = true,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.SPEAKER,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = true,
                isDefault = !audioManager?.hasEarpiece()!!
            ),
            connectionState = DeviceConnectionState.AVAILABLE,
            isWireless = false,
            supportsHDVoice = true,
            latency = 15,
            vendorInfo = "Built-in"
        )
    }

    private fun createEarpieceDevice(): AudioDevice {
        return AudioDevice(
            name = "Earpiece",
            descriptor = "earpiece",
            nativeDevice = null,
            isOutput = true,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.EARPIECE,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = true,
                isDefault = true
            ),
            connectionState = DeviceConnectionState.AVAILABLE,
            isWireless = false,
            supportsHDVoice = true,
            latency = 5,
            vendorInfo = "Built-in"
        )
    }

    /**
     * Clean up and release WebRTC resources
     */
    override fun dispose() {
        log.d(TAG) { "Disposing Enhanced WebRtcManager resources..." }

        try {
            // Clear listeners
            deviceChangeListeners.clear()

            // Unregister audio device callback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioDeviceCallback?.let { callback ->
                    audioManager?.unregisterAudioDeviceCallback(callback)
                }
            }

            // Release audio focus and restore settings
            releaseAudioFocus()

            // Clean up WebRTC resources
            cleanupCall()

            // Reset state
            isInitialized = false
            isLocalAudioReady = false
            currentInputDevice = null
            currentOutputDevice = null

        } catch (e: Exception) {
            log.e(TAG) { "Error during disposal: ${e.message}" }
        }
    }

    /**
     * Create an SDP offer for starting a call
     * @return The SDP offer string
     */
    override suspend fun createOffer(): String {
        log.d(TAG) { "Creating SDP offer..." }

        // Make sure WebRTC and audio is initialized
        if (!isInitialized) {
            log.d(TAG) { "WebRTC not initialized, initializing now" }
            initialize()
        } else {
            // Reinitialize audio settings for outgoing call
            initializeAudio()
        }

        val peerConn = peerConnection ?: run {
            log.d(TAG) { "PeerConnection not initialized, reinitializing" }
            initializePeerConnection()
            peerConnection ?: throw IllegalStateException("PeerConnection initialization failed")
        }

        // Make sure local audio track is added and enabled before creating an offer
        if (!isLocalAudioReady) {
            log.d(TAG) { "Ensuring local audio track is ready..." }
            isLocalAudioReady = ensureLocalAudioTrack()
            if (!isLocalAudioReady) {
                log.d(TAG) { "Failed to prepare local audio track!" }
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

        log.d(TAG) { "Created offer SDP: ${sessionDescription.sdp}" }
        return sessionDescription.sdp
    }

    /**
     * Create an SDP answer in response to an offer
     * @param accountInfo The current account information
     * @param offerSdp The SDP offer from the remote party
     * @return The SDP answer string
     */
    override suspend fun createAnswer(accountInfo: AccountInfo, offerSdp: String): String {
        log.d(TAG) { "Creating SDP answer..." }

        // Make sure WebRTC and audio is initialized
        if (!isInitialized) {
            log.d(TAG) { "WebRTC not initialized, initializing now" }
            initialize()
        } else {
            // Reinitialize audio settings for incoming call
            initializeAudio()
        }

        val peerConn = peerConnection ?: run {
            log.d(TAG) { "PeerConnection not initialized, reinitializing" }
            initializePeerConnection()
            peerConnection ?: throw IllegalStateException("PeerConnection initialization failed")
        }

        // IMPORTANT: Make sure local audio track is added BEFORE setting remote description
        // This is a critical fix for incoming calls
        if (!isLocalAudioReady) {
            log.d(TAG) { "Ensuring local audio track is ready before answering..." }
            isLocalAudioReady = ensureLocalAudioTrack()
            if (!isLocalAudioReady) {
                log.d(TAG) { "Failed to prepare local audio track for answering!" }
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

        log.d(TAG) { "Created answer SDP: ${sessionDescription.sdp}" }
        return sessionDescription.sdp
    }

    /**
     * Set the remote description (offer or answer)
     * @param sdp The remote SDP string
     * @param type The SDP type (offer or answer)
     */
    override suspend fun setRemoteDescription(sdp: String, type: SdpType) {
        log.d(TAG) { "Setting remote description type: $type" }

        // Make sure WebRTC is initialized
        if (!isInitialized) {
            log.d(TAG) { "WebRTC not initialized, initializing now" }
            initialize()
        }

        val peerConn = peerConnection ?: run {
            log.d(TAG) { "PeerConnection not initialized, reinitializing" }
            initializePeerConnection()
            peerConnection ?: throw IllegalStateException("PeerConnection initialization failed")
        }

        // If this is an offer, ensure we have local audio ready before proceeding
        if (type == SdpType.OFFER && !isLocalAudioReady) {
            log.d(TAG) { "Ensuring local audio track is ready before processing offer..." }
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
        log.d(TAG) { "Adding ICE candidate: $candidate" }

        // Make sure WebRTC is initialized
        if (!isInitialized) {
            log.d(TAG) { "WebRTC not initialized, initializing now" }
            initialize()
            // If still no peer connection after initialize, return
            if (peerConnection == null) {
                log.d(TAG) { "Failed to initialize PeerConnection, cannot add ICE candidate" }
                return
            }
        }

        val peerConn = peerConnection ?: run {
            log.d(TAG) { "PeerConnection not available, cannot add ICE candidate" }
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
     * Sets the mute state for the local microphone
     * @param muted Whether the microphone should be muted
     */
    override fun setMuted(muted: Boolean) {
        log.d(TAG) { "Setting microphone mute: $muted" }

        try {
            // Use AudioManager to mute microphone
            audioManager?.isMicrophoneMute = muted

            // Also disable the audio track if we have one
            localAudioTrack?.enabled = !muted
        } catch (e: Exception) {
            log.d(TAG) { "Error setting mute state: ${e.message}" }
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
        log.d(TAG) { "Setting media direction: $direction" }

        if (!isInitialized || peerConnection == null) {
            log.d(TAG) { "Cannot set media direction: WebRTC not initialized" }
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
            log.d(TAG) { "Error setting media direction: ${e.message}" }
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
            log.d(TAG) { "Error applying modified SDP: ${e.message}" }
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
        log.d(TAG) { "Setting audio enabled: $enabled" }

        // Use AudioManager to ensure microphone state
        audioManager?.isMicrophoneMute = !enabled

        if (localAudioTrack == null && isInitialized) {
            log.d(TAG) { "No local audio track but WebRTC is initialized, trying to add audio track" }
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
            log.d(TAG) { "WebRTC event listener set" }
        } else {
            log.d(TAG) { "Invalid listener type provided" }
        }
    }

    override fun prepareAudioForIncomingCall() {
        log.d(TAG) { "Preparing audio for incoming call" }
        initializeAudio()
    }

    /**
     * Enhanced audio diagnostics
     */
    override fun diagnoseAudioIssues(): String {
        return buildString {
            appendLine("=== ENHANCED AUDIO DIAGNOSIS ===")

            // Basic WebRTC state
            appendLine("WebRTC Initialized: $isInitialized")
            appendLine("Local Audio Ready: $isLocalAudioReady")
            appendLine("Local Audio Track: ${localAudioTrack != null}")
            appendLine("Local Audio Enabled: ${localAudioTrack?.enabled}")
            appendLine("Remote Audio Track: ${remoteAudioTrack != null}")
            appendLine("Remote Audio Enabled: ${remoteAudioTrack?.enabled}")

            // AudioManager state
            audioManager?.let { am ->
                appendLine("\n--- AudioManager State ---")
                appendLine("Audio Mode: ${am.mode}")
                appendLine("Speaker On: ${am.isSpeakerphoneOn}")
                appendLine("Mic Muted: ${am.isMicrophoneMute}")
                appendLine("Bluetooth SCO On: ${am.isBluetoothScoOn}")
                appendLine("Bluetooth SCO Available: ${am.isBluetoothScoAvailableOffCall}")
                appendLine("Wired Headset On: ${am.isWiredHeadsetOn}")
                appendLine("Music Active: ${am.isMusicActive}")
                appendLine("Has Earpiece: ${am.hasEarpiece()}")
            }

            // Current devices
            appendLine("\n--- Current Devices ---")
            appendLine("Current Input: ${currentInputDevice?.name ?: "Not set"}")
            appendLine("Current Output: ${currentOutputDevice?.name ?: "Not set"}")

            // Device counts
            try {
                val (inputs, outputs) = getAllAudioDevices()
                appendLine("\n--- Available Devices ---")
                appendLine("Input Devices: ${inputs.size}")
                inputs.forEach { device ->
                    appendLine("  - ${device.name} (${device.audioUnit.type}, ${device.connectionState})")
                }
                appendLine("Output Devices: ${outputs.size}")
                outputs.forEach { device ->
                    appendLine("  - ${device.name} (${device.audioUnit.type}, ${device.connectionState})")
                }
            } catch (e: Exception) {
                appendLine("Error getting devices: ${e.message}")
            }

            // Bluetooth state
            appendLine("\n--- Bluetooth State ---")
            appendLine("Bluetooth Adapter: ${bluetoothAdapter != null}")
            appendLine("Bluetooth Enabled: ${bluetoothAdapter?.isEnabled}")
            try {
                val connectedBt = getConnectedBluetoothDevices()
                appendLine("Connected BT Devices: ${connectedBt.size}")
            } catch (e: Exception) {
                appendLine("BT Device Check Error: ${e.message}")
            }

            // Permission status
            appendLine("\n--- Permissions ---")
            appendLine("Record Audio: ${hasPermission(Manifest.permission.RECORD_AUDIO)}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                appendLine("Bluetooth Connect: ${hasPermission(Manifest.permission.BLUETOOTH_CONNECT)}")
            }
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
        log.d(TAG) { "Sending DTMF tones: $tones (duration: $duration, gap: $gap)" }

        // Check if WebRTC is initialized and connection is established
        if (!isInitialized || peerConnection == null) {
            log.d(TAG) { "Cannot send DTMF: WebRTC not initialized" }
            return false
        }

        try {
            // Get audio sender
            val audioSender = peerConnection?.getSenders()?.find { sender ->
                sender.track?.kind == MediaStreamTrackKind.Audio
            }

            if (audioSender == null) {
                log.d(TAG) { "Cannot send DTMF: No audio sender found" }
                return false
            }

            // Get the DTMF sender for this audio track
            val dtmfSender = audioSender.dtmf ?: run {
                log.d(TAG) { "Cannot send DTMF: DtmfSender not available" }
                return false
            }

            // Send the DTMF tones
            val sanitizedTones = sanitizeDtmfTones(tones)
            if (sanitizedTones.isEmpty()) {
                log.d(TAG) { "Cannot send DTMF: No valid tones to send" }
                return false
            }

            val result = dtmfSender.insertDtmf(
                tones = sanitizedTones,
                durationMs = duration,
                interToneGapMs = gap
            )

            log.d(TAG) { "DTMF tone sending result: $result" }
            return result
        } catch (e: Exception) {
            log.d(TAG) { "Error sending DTMF tones: ${e.message}" }
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
     * Check if permission is granted
     */
    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Add device change listener
     */
    fun addDeviceChangeListener(listener: (List<AudioDevice>) -> Unit) {
        deviceChangeListeners.add(listener)
    }

    /**
     * Remove device change listener
     */
    fun removeDeviceChangeListener(listener: (List<AudioDevice>) -> Unit) {
        deviceChangeListeners.remove(listener)
    }

    /**
     * Get devices by quality score
     */
    fun getAudioDevicesByQuality(): Pair<List<AudioDevice>, List<AudioDevice>> {
        val (inputs, outputs) = getAllAudioDevices()
        return Pair(
            inputs.sortedByDescending { it.qualityScore },
            outputs.sortedByDescending { it.qualityScore }
        )
    }

    /**
     * Get recommended device for optimal call quality
     */
    fun getRecommendedAudioDevice(isOutput: Boolean): AudioDevice? {
        val (inputs, outputs) = getAudioDevicesByQuality()
        val devices = if (isOutput) outputs else inputs

        // Prefer current device if it has good quality
        val currentDevice = if (isOutput) currentOutputDevice else currentInputDevice
        if (currentDevice != null && currentDevice.qualityScore >= 70) {
            return currentDevice
        }

        // Otherwise, return the highest quality available device
        return devices.firstOrNull {
            it.connectionState == DeviceConnectionState.AVAILABLE ||
                    it.connectionState == DeviceConnectionState.CONNECTED
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
        log.d(TAG) { "Initializing PeerConnection..." }
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

            log.d(TAG) { "RTC Configuration: $rtcConfig" }

            peerConnection = PeerConnection(rtcConfig).apply {
                setupPeerConnectionObservers()
            }

            log.d(TAG) { "PeerConnection created: ${peerConnection != null}" }

            // Don't add local audio track here - will be done when needed
            // This prevents requesting microphone permission unnecessarily
            isLocalAudioReady = false
        } catch (e: Exception) {
            log.d(TAG) { "Error initializing PeerConnection: ${e.message}" }
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
            log.d(TAG) { "New ICE Candidate: ${candidate.candidate}" }

            // Notify the listener
            webRtcEventListener?.onIceCandidate(
                candidate.candidate,
                candidate.sdpMid,
                candidate.sdpMLineIndex
            )
        }.launchIn(coroutineScope)

        onConnectionStateChange.onEach { state ->
            log.d(TAG) { "Connection state changed: $state" }

            // Update call state based on connection state
            when (state) {
                PeerConnectionState.Connected -> {
                    log.d(TAG) { "Call active: Connected" }
                    CallStateManager.updateCallState(CallState.CONNECTED)
                    // Ensure audio is enabled when connected and microphone is not muted
                    setAudioEnabled(true)
                    audioManager?.isMicrophoneMute = false
                }

                PeerConnectionState.Disconnected,
                PeerConnectionState.Failed,
                PeerConnectionState.Closed -> {
                    CallStateManager.updateCallState(CallState.ENDED)
                    log.d(TAG) { "Call ended" }
                    // Release audio focus when call ends
                    releaseAudioFocus()
                }

                else -> {
                    log.d(TAG) { "Other connection state: $state" }
                }
            }

            // Notify the listener
            webRtcEventListener?.onConnectionStateChange(mapConnectionState(state))
        }.launchIn(coroutineScope)

        onTrack.onEach { event ->
            log.d(TAG) { "Remote track received: $event" }
            val track = event.receiver.track

            if (track is AudioStreamTrack) {
                log.d(TAG) { "Remote audio track established" }
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
                log.d(TAG) { "PeerConnection not initialized" }
                return false
            }

            // Check if we already have a track
            if (localAudioTrack != null) {
                log.d(TAG) { "Local audio track already exists" }
                return true
            }

            // Make sure audio mode is set for communication
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager?.isMicrophoneMute = false

            log.d(TAG) { "Getting local audio stream..." }

            val mediaStream = MediaDevices.getUserMedia(
                audio = true,
                video = false
            )

            val audioTrack = mediaStream.audioTracks.firstOrNull()
            if (audioTrack != null) {
                log.d(TAG) { "Audio track obtained successfully!" }

                localAudioTrack = audioTrack
                localAudioTrack?.enabled = true

                peerConn.addTrack(audioTrack, mediaStream)

                log.d(TAG) { "Audio track added successfully: ${audioTrack.label}" }

                // Additional troubleshooting for audio routing
                val outputDevice = when {
                    audioManager?.isBluetoothScoOn == true -> "Bluetooth SCO"
                    audioManager?.isBluetoothA2dpOn == true -> "Bluetooth A2DP"
                    audioManager?.isSpeakerphoneOn == true -> "Speakerphone"
                    audioManager?.isWiredHeadsetOn == true -> "Wired Headset"
                    else -> "Earpiece/Default"
                }

                log.d(TAG) { "Current audio output device: $outputDevice" }

                true
            } else {
                log.d(TAG) { "Error: No audio track found" }
                false
            }
        } catch (e: Exception) {
            log.d(TAG) { "Error getting audio: ${e.message}" }
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
                        log.d(TAG) { "Error removing track: ${e.message}" }
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
            log.d(TAG) { "Error in cleanupCall: ${e.message}" }
        }
    }
}
