package com.eddyslarez.siplibrary.data.services.audio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay

//
///**
// * Enhanced Android implementation of WebRtcManager interface with comprehensive audio device support
// * FIXED: Bluetooth audio routing issues
// *
// * @author Eddys Larez
// */
//class AndroidWebRtcManager(private val application: Application) : WebRtcManager {
//    private val TAG = "AndroidWebRtcManager"
//    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
//
//    private var peerConnection: PeerConnection? = null
//    private var localAudioTrack: AudioStreamTrack? = null
//    private var remoteAudioTrack: AudioStreamTrack? = null
//    private var webRtcEventListener: WebRtcEventListener? = null
//    private var isInitialized = false
//    private var isLocalAudioReady = false
//    private var context: Context = application.applicationContext
//
//    // Enhanced audio management fields
//    private var audioManager: AudioManager? = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
//    private var bluetoothAdapter: BluetoothAdapter? = null
//    private var bluetoothManager: BluetoothManager? = null
//    private var audioDeviceCallback: AudioDeviceCallback? = null
//    private var currentInputDevice: AudioDevice? = null
//    private var currentOutputDevice: AudioDevice? = null
//
//    // FIXED: Add Bluetooth SCO state tracking
//    private var isBluetoothScoRequested = false
//    private var bluetoothScoReceiver: BroadcastReceiver? = null
//
//    // Audio state management
//    private var savedAudioMode = AudioManager.MODE_NORMAL
//    private var savedIsSpeakerPhoneOn = false
//    private var savedIsMicrophoneMute = false
//    private var audioFocusRequest: AudioFocusRequest? = null
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
//    /**
//     * Initialize the WebRTC subsystem
//     */
//    override fun initialize() {
//        log.d(TAG) { "Initializing WebRTC Manager..." }
//        if (!isInitialized) {
//            initializeAudio()
//            initializePeerConnection()
//            coroutineScope.launch {
//                getAudioInputDevices()
//            }
//            isInitialized = true
//        } else {
//            log.d(TAG) { "WebRTC already initialized" }
//        }
//    }
//
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
//    /**
//     * Gets available audio input devices (microphones)
//     */
//    suspend fun getAudioInputDevices(): List<MediaDeviceInfo> {
//        return MediaDevices.enumerateDevices()
//    }
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
//        log.d(TAG) { "Disposing Enhanced WebRtcManager resources..." }
//
//        try {
//            // Clear listeners
//            deviceChangeListeners.clear()
//
//            // FIXED: Unregister Bluetooth SCO receiver
//            bluetoothScoReceiver?.let { receiver ->
//                try {
//                    context.unregisterReceiver(receiver)
//                } catch (e: Exception) {
//                    log.w(TAG) { "Error unregistering Bluetooth SCO receiver: ${e.message}" }
//                }
//            }
//
//            // FIXED: Stop Bluetooth SCO if active
//            audioManager?.let { am ->
//                if (am.isBluetoothScoOn) {
//                    am.stopBluetoothSco()
//                }
//            }
//            isBluetoothScoRequested = false
//
//            // Unregister audio device callback
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//                audioDeviceCallback?.let { callback ->
//                    audioManager?.unregisterAudioDeviceCallback(callback)
//                }
//            }
//
//            // Release audio focus and restore settings
//            releaseAudioFocus()
//
//            // Clean up WebRTC resources
//            cleanupCall()
//
//            // Reset state
//            isInitialized = false
//            isLocalAudioReady = false
//            currentInputDevice = null
//            currentOutputDevice = null
//
//        } catch (e: Exception) {
//            log.e(TAG) { "Error during disposal: ${e.message}" }
//        }
//    }
//
//    /**
//     * Create an SDP offer for starting a call
//     * @return The SDP offer string
//     */
//    override suspend fun createOffer(): String {
//        log.d(TAG) { "Creating SDP offer..." }
//
//        // Make sure WebRTC and audio is initialized
//        if (!isInitialized) {
//            log.d(TAG) { "WebRTC not initialized, initializing now" }
//            initialize()
//        } else {
//            // Reinitialize audio settings for outgoing call
//            initializeAudio()
//        }
//
//        val peerConn = peerConnection ?: run {
//            log.d(TAG) { "PeerConnection not initialized, reinitializing" }
//            initializePeerConnection()
//            peerConnection ?: throw IllegalStateException("PeerConnection initialization failed")
//        }
//
//        // Make sure local audio track is added and enabled before creating an offer
//        if (!isLocalAudioReady) {
//            log.d(TAG) { "Ensuring local audio track is ready..." }
//            isLocalAudioReady = ensureLocalAudioTrack()
//            if (!isLocalAudioReady) {
//                log.d(TAG) { "Failed to prepare local audio track!" }
//                // Continue anyway to create the offer, but log the issue
//            }
//        }
//
//        val options = OfferAnswerOptions(
//            voiceActivityDetection = true
//        )
//
//        val sessionDescription = peerConn.createOffer(options)
//        peerConn.setLocalDescription(sessionDescription)
//
//        // Ensure microphone is unmuted for outgoing call
//        audioManager?.isMicrophoneMute = false
//
//        log.d(TAG) { "Created offer SDP: ${sessionDescription.sdp}" }
//        return sessionDescription.sdp
//    }
//
//    /**
//     * Create an SDP answer in response to an offer
//     * @param accountInfo The current account information
//     * @param offerSdp The SDP offer from the remote party
//     * @return The SDP answer string
//     */
//    override suspend fun createAnswer(accountInfo: AccountInfo, offerSdp: String): String {
//        log.d(TAG) { "Creating SDP answer..." }
//
//        // Make sure WebRTC and audio is initialized
//        if (!isInitialized) {
//            log.d(TAG) { "WebRTC not initialized, initializing now" }
//            initialize()
//        } else {
//            // Reinitialize audio settings for incoming call
//            initializeAudio()
//        }
//
//        val peerConn = peerConnection ?: run {
//            log.d(TAG) { "PeerConnection not initialized, reinitializing" }
//            initializePeerConnection()
//            peerConnection ?: throw IllegalStateException("PeerConnection initialization failed")
//        }
//
//        // IMPORTANT: Make sure local audio track is added BEFORE setting remote description
//        // This is a critical fix for incoming calls
//        if (!isLocalAudioReady) {
//            log.d(TAG) { "Ensuring local audio track is ready before answering..." }
//            isLocalAudioReady = ensureLocalAudioTrack()
//            if (!isLocalAudioReady) {
//                log.d(TAG) { "Failed to prepare local audio track for answering!" }
//                // Continue anyway to create the answer, but log the issue
//            }
//        }
//
//        // Set the remote offer
//        val remoteOffer = SessionDescription(
//            type = SessionDescriptionType.Offer,
//            sdp = offerSdp
//        )
//        peerConn.setRemoteDescription(remoteOffer)
//
//        // Create answer
//        val options = OfferAnswerOptions(
//            voiceActivityDetection = true
//        )
//
//        val sessionDescription = peerConn.createAnswer(options)
//        peerConn.setLocalDescription(sessionDescription)
//
//        // Ensure audio is enabled for answering the call
//        setAudioEnabled(true)
//
//        // Explicitly ensure microphone is not muted for incoming call
//        audioManager?.isMicrophoneMute = false
//
//        log.d(TAG) { "Created answer SDP: ${sessionDescription.sdp}" }
//        return sessionDescription.sdp
//    }
//
//    /**
//     * Set the remote description (offer or answer)
//     * @param sdp The remote SDP string
//     * @param type The SDP type (offer or answer)
//     */
//    override suspend fun setRemoteDescription(sdp: String, type: SdpType) {
//        log.d(TAG) { "Setting remote description type: $type" }
//
//        // Make sure WebRTC is initialized
//        if (!isInitialized) {
//            log.d(TAG) { "WebRTC not initialized, initializing now" }
//            initialize()
//        }
//
//        val peerConn = peerConnection ?: run {
//            log.d(TAG) { "PeerConnection not initialized, reinitializing" }
//            initializePeerConnection()
//            peerConnection ?: throw IllegalStateException("PeerConnection initialization failed")
//        }
//
//        // If this is an offer, ensure we have local audio ready before proceeding
//        if (type == SdpType.OFFER && !isLocalAudioReady) {
//            log.d(TAG) { "Ensuring local audio track is ready before processing offer..." }
//            isLocalAudioReady = ensureLocalAudioTrack()
//        }
//
//        val sdpType = when (type) {
//            SdpType.OFFER -> SessionDescriptionType.Offer
//            SdpType.ANSWER -> SessionDescriptionType.Answer
//        }
//
//        val sessionDescription = SessionDescription(
//            type = sdpType,
//            sdp = sdp
//        )
//
//        peerConn.setRemoteDescription(sessionDescription)
//
//        // If this was an answer to our offer, make sure audio is enabled
//        if (type == SdpType.ANSWER) {
//            setAudioEnabled(true)
//            audioManager?.isMicrophoneMute = false
//        }
//    }
//
//    /**
//     * Add an ICE candidate received from the remote party
//     * @param candidate The ICE candidate string
//     * @param sdpMid The media ID
//     * @param sdpMLineIndex The media line index
//     */
//    override suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
//        log.d(TAG) { "Adding ICE candidate: $candidate" }
//
//        // Make sure WebRTC is initialized
//        if (!isInitialized) {
//            log.d(TAG) { "WebRTC not initialized, initializing now" }
//            initialize()
//            // If still no peer connection after initialize, return
//            if (peerConnection == null) {
//                log.d(TAG) { "Failed to initialize PeerConnection, cannot add ICE candidate" }
//                return
//            }
//        }
//
//        val peerConn = peerConnection ?: run {
//            log.d(TAG) { "PeerConnection not available, cannot add ICE candidate" }
//            return
//        }
//
//        val iceCandidate = IceCandidate(
//            sdpMid = sdpMid ?: "",
//            sdpMLineIndex = sdpMLineIndex ?: 0,
//            candidate = candidate
//        )
//
//        peerConn.addIceCandidate(iceCandidate)
//    }
//
//    /**
//     * Sets the mute state for the local microphone
//     * @param muted Whether the microphone should be muted
//     */
//    override fun setMuted(muted: Boolean) {
//        log.d(TAG) { "Setting microphone mute: $muted" }
//
//        try {
//            // Use AudioManager to mute microphone
//            audioManager?.isMicrophoneMute = muted
//
//            // Also disable the audio track if we have one
//            localAudioTrack?.enabled = !muted
//        } catch (e: Exception) {
//            log.d(TAG) { "Error setting mute state: ${e.message}" }
//        }
//    }
//
//    /**
//     * Gets the current mute state of the microphone
//     * @return true if muted, false otherwise
//     */
//    override fun isMuted(): Boolean {
//        val isAudioManagerMuted = audioManager?.isMicrophoneMute ?: false
//        val isTrackDisabled = localAudioTrack?.enabled?.not() ?: false
//
//        // If either is muted/disabled, consider it muted
//        return isAudioManagerMuted || isTrackDisabled
//    }
//
//    /**
//     * Gets the local SDP description
//     * @return The local SDP string, or null if not set
//     */
//    override fun getLocalDescription(): String? {
//        return peerConnection?.localDescription?.sdp
//    }
//
//    /**
//     * Sets the media direction (sendrecv, sendonly, recvonly, inactive)
//     * @param direction The desired media direction
//     */
//    override suspend fun setMediaDirection(direction: WebRtcManager.MediaDirection) {
//        log.d(TAG) { "Setting media direction: $direction" }
//
//        if (!isInitialized || peerConnection == null) {
//            log.d(TAG) { "Cannot set media direction: WebRTC not initialized" }
//            return
//        }
//
//        val peerConn = peerConnection ?: return
//
//        try {
//            // Get current description
//            val currentDesc = peerConn.localDescription ?: return
//
//            // Change direction in SDP
//            val modifiedSdp = updateSdpDirection(currentDesc.sdp, direction)
//
//            // Create and set the modified local description
//            val newDesc = SessionDescription(
//                type = currentDesc.type,
//                sdp = modifiedSdp
//            )
//
//            peerConn.setLocalDescription(newDesc)
//
//            // If we have an answer/offer from remote side, we need to renegotiate
//            if (peerConn.remoteDescription != null) {
//                // Create new offer/answer to apply the changes
//                val options = OfferAnswerOptions(
//                    voiceActivityDetection = true
//                )
//
//                val sessionDesc = if (currentDesc.type == SessionDescriptionType.Offer) {
//                    peerConn.createOffer(options)
//                } else {
//                    peerConn.createAnswer(options)
//                }
//
//                // Modify the new SDP to ensure our direction is applied
//                val finalSdp = updateSdpDirection(sessionDesc.sdp, direction)
//                val finalDesc = SessionDescription(
//                    type = sessionDesc.type,
//                    sdp = finalSdp
//                )
//
//                peerConn.setLocalDescription(finalDesc)
//            }
//        } catch (e: Exception) {
//            log.d(TAG) { "Error setting media direction: ${e.message}" }
//        }
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

    // Enhanced Audio Manager Integration
    private var enhancedAudioManager: EnhancedAudioManager? = null
    private var isAudioInitialized = false

    init {
        initializeEnhancedAudioManager()
    }

    /**
     * Initialize the Enhanced Audio Manager
     */
    private fun initializeEnhancedAudioManager() {
        try {
            enhancedAudioManager = EnhancedAudioManager(context)

            // Add device change listener to monitor audio device changes
            enhancedAudioManager?.addDeviceChangeListener { devices ->
                log.d(TAG) { "Audio devices changed: ${devices.size} devices available" }
                webRtcEventListener?.onAudioDevicesChanged(mapToLegacyAudioDevices(devices))
            }

            log.d(TAG) { "Enhanced Audio Manager initialized successfully" }
        } catch (e: Exception) {
            log.e(TAG) { "Failed to initialize Enhanced Audio Manager: ${e.message}" }
        }
    }

    /**
     * Map EnhancedAudioDevice to legacy AudioDevice for compatibility
     */
    private fun mapToLegacyAudioDevices(enhancedDevices: List<EnhancedAudioManager.EnhancedAudioDevice>): List<AudioDevice> {
        return enhancedDevices.map { enhancedDevice ->
            AudioDevice(
                name = enhancedDevice.name,
                descriptor = enhancedDevice.id,
                nativeDevice = enhancedDevice.nativeDevice,
                isOutput = enhancedDevice.capabilities.canPlay,
                audioUnit = mapToAudioUnit(enhancedDevice),
                connectionState = mapConnectionState(enhancedDevice),
                signalStrength = enhancedDevice.metadata.signalStrength,
                batteryLevel = enhancedDevice.metadata.batteryLevel,
                isWireless = enhancedDevice.metadata.isWireless,
                supportsHDVoice = enhancedDevice.capabilities.supportsHDVoice,
                latency = enhancedDevice.capabilities.latencyMs,
                vendorInfo = enhancedDevice.metadata.vendor
            )
        }
    }

    private fun mapToAudioUnit(enhancedDevice: EnhancedAudioManager.EnhancedAudioDevice): AudioUnit {
        val audioUnitType = when (enhancedDevice.type) {
            EnhancedAudioManager.AudioDeviceType.BUILT_IN_MIC -> AudioUnitTypes.MICROPHONE
            EnhancedAudioManager.AudioDeviceType.BUILT_IN_SPEAKER -> AudioUnitTypes.SPEAKER
            EnhancedAudioManager.AudioDeviceType.BUILT_IN_EARPIECE -> AudioUnitTypes.EARPIECE
            EnhancedAudioManager.AudioDeviceType.WIRED_HEADSET -> AudioUnitTypes.HEADSET
            EnhancedAudioManager.AudioDeviceType.BLUETOOTH_HFP -> AudioUnitTypes.BLUETOOTH
            EnhancedAudioManager.AudioDeviceType.BLUETOOTH_A2DP -> AudioUnitTypes.BLUETOOTHA2DP
            EnhancedAudioManager.AudioDeviceType.USB_HEADSET -> AudioUnitTypes.GENERICUSB
            EnhancedAudioManager.AudioDeviceType.ANDROID_AUTO -> AudioUnitTypes.BLUETOOTH // Treat as Bluetooth for compatibility
            EnhancedAudioManager.AudioDeviceType.HEARING_AID -> AudioUnitTypes.HEARINGAID
            else -> AudioUnitTypes.SPEAKER
        }

        val capability =
            if (enhancedDevice.capabilities.canPlay && enhancedDevice.capabilities.canRecord) {
                AudioUnitCompatibilities.ALL
            } else if (enhancedDevice.capabilities.canPlay) {
                AudioUnitCompatibilities.PLAY
            } else {
                AudioUnitCompatibilities.RECORD
            }

        return AudioUnit(
            type = audioUnitType,
            capability = capability,
            isCurrent = enhancedDevice.isActive,
            isDefault = enhancedDevice.route == EnhancedAudioManager.AudioRoute.EARPIECE
        )
    }

    private fun mapConnectionState(enhancedDevice: EnhancedAudioManager.EnhancedAudioDevice): DeviceConnectionState {
        return when (enhancedDevice.metadata.connectionState) {
            "CONNECTED" -> DeviceConnectionState.CONNECTED
            "AVAILABLE" -> DeviceConnectionState.AVAILABLE
            "CONNECTING" -> DeviceConnectionState.CONNECTING
            else -> DeviceConnectionState.AVAILABLE
        }
    }

    /**
     * Initialize the WebRTC subsystem with enhanced audio support
     */
    override fun initialize() {
        log.d(TAG) { "Initializing WebRTC Manager with Enhanced Audio..." }

        if (!isInitialized) {
            initializeEnhancedAudio()
            initializePeerConnection()
            isInitialized = true
        } else {
            log.d(TAG) { "WebRTC already initialized" }
        }
    }

    /**
     * Initialize enhanced audio system for calls
     */
    private fun initializeEnhancedAudio() {
        try {
            val success = enhancedAudioManager?.initializeForCall() ?: false
            if (success) {
                isAudioInitialized = true
                log.d(TAG) { "Enhanced audio system initialized successfully" }
            } else {
                log.w(TAG) { "Failed to initialize enhanced audio system" }
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error initializing enhanced audio: ${e.message}" }
        }
    }

    /**
     * Gets available audio devices using Enhanced Audio Manager
     */
    @RequiresPermission(
        allOf = [
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.RECORD_AUDIO
        ]
    )
    override fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        log.d(TAG) { "Getting all enhanced audio devices..." }

        return try {
            val enhancedDevices = enhancedAudioManager?.getConnectedDevices() ?: emptyList()
            val legacyDevices = mapToLegacyAudioDevices(enhancedDevices)

            val inputDevices = legacyDevices.filter { !it.isOutput }
            val outputDevices = legacyDevices.filter { it.isOutput }

            log.d(TAG) { "Found ${inputDevices.size} input and ${outputDevices.size} output devices" }
            Pair(inputDevices, outputDevices)
        } catch (e: Exception) {
            log.e(TAG) { "Error getting enhanced audio devices: ${e.message}" }
            getFallbackDevices()
        }
    }

    /**
     * Enhanced device change during call with improved routing
     */
    override fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean {
        log.d(TAG) { "Changing audio output to: ${device.name} (${device.descriptor})" }

        if (!isInitialized || peerConnection == null) {
            log.w(TAG) { "Cannot change audio output: WebRTC not initialized" }
            return false
        }

        return try {
            val success = enhancedAudioManager?.switchToDevice(device.descriptor) ?: false

            if (success) {
                log.d(TAG) { "Successfully changed audio output to: ${device.name}" }
                webRtcEventListener?.onAudioDeviceChanged(device)
            } else {
                log.w(TAG) { "Failed to change audio output to: ${device.name}" }
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
            val success = enhancedAudioManager?.switchToDevice(device.descriptor) ?: false

            if (success) {
                log.d(TAG) { "Successfully changed audio input to: ${device.name}" }
                webRtcEventListener?.onAudioDeviceChanged(device)
            } else {
                log.w(TAG) { "Failed to change audio input to: ${device.name}" }
            }

            success
        } catch (e: Exception) {
            log.e(TAG) { "Error changing audio input: ${e.message}" }
            false
        }
    }

    /**
     * Get current input device using Enhanced Audio Manager
     */
    override fun getCurrentInputDevice(): AudioDevice? {
        return try {
            val enhancedDevice = enhancedAudioManager?.getActiveInputDevice()
            enhancedDevice?.let {
                mapToLegacyAudioDevices(listOf(it)).firstOrNull()
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error getting current input device: ${e.message}" }
            null
        }
    }

    /**
     * Get current output device using Enhanced Audio Manager
     */
    override fun getCurrentOutputDevice(): AudioDevice? {
        return try {
            val enhancedDevice = enhancedAudioManager?.getActiveOutputDevice()
            enhancedDevice?.let {
                mapToLegacyAudioDevices(listOf(it)).firstOrNull()
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error getting current output device: ${e.message}" }
            null
        }
    }

    /**
     * Get current audio route from Enhanced Audio Manager
     */
    fun getCurrentAudioRoute(): EnhancedAudioManager.AudioRoute? {
        return enhancedAudioManager?.getCurrentAudioRoute()
    }

    /**
     * Enhanced mute functionality
     */
    override fun setMuted(muted: Boolean) {
        log.d(TAG) { "Setting microphone mute: $muted" }

        try {
            // Use Enhanced Audio Manager for muting
            enhancedAudioManager?.setMuted(muted)

            // Also disable the audio track if we have one
            localAudioTrack?.enabled = !muted
        } catch (e: Exception) {
            log.e(TAG) { "Error setting mute state: ${e.message}" }
        }
    }

    /**
     * Enhanced mute check
     */
    override fun isMuted(): Boolean {
        val enhancedManagerMuted = enhancedAudioManager?.isMuted() ?: false
        val trackDisabled = localAudioTrack?.enabled?.not() ?: false

        // If either is muted/disabled, consider it muted
        return enhancedManagerMuted || trackDisabled
    }

    /**
     * Enhanced audio diagnostics using Enhanced Audio Manager
     */
    override fun diagnoseAudioIssues(): String {
        return buildString {
            appendLine("=== ENHANCED WEBRTC AUDIO DIAGNOSIS ===")

            // Basic WebRTC state
            appendLine("WebRTC Initialized: $isInitialized")
            appendLine("Enhanced Audio Initialized: $isAudioInitialized")
            appendLine("Local Audio Ready: $isLocalAudioReady")
            appendLine("Local Audio Track: ${localAudioTrack != null}")
            appendLine("Local Audio Enabled: ${localAudioTrack?.enabled}")
            appendLine("Remote Audio Track: ${remoteAudioTrack != null}")
            appendLine("Remote Audio Enabled: ${remoteAudioTrack?.enabled}")

            // Enhanced Audio Manager diagnostics
            enhancedAudioManager?.let { manager ->
                appendLine("\n--- Enhanced Audio Manager ---")
                appendLine("Current Route: ${manager.getCurrentAudioRoute()}")
                appendLine("Is Muted: ${manager.isMuted()}")

                val inputDevice = manager.getActiveInputDevice()
                appendLine("Active Input: ${inputDevice?.name ?: "None"}")

                val outputDevice = manager.getActiveOutputDevice()
                appendLine("Active Output: ${outputDevice?.name ?: "None"}")

                val connectedDevices = manager.getConnectedDevices()
                appendLine("Connected Devices: ${connectedDevices.size}")
                connectedDevices.forEach { device ->
                    appendLine("  - ${device.name} (${device.route}, Active: ${device.isActive})")
                }

                // Get full diagnostics from Enhanced Audio Manager
                appendLine("\n${manager.getDiagnostics()}")
            } ?: run {
                appendLine("\n--- Enhanced Audio Manager ---")
                appendLine("ERROR: Enhanced Audio Manager not initialized")
            }

            // Connection state
            appendLine("\n--- Connection State ---")
            appendLine("Connection State: ${getConnectionState()}")
        }
    }

    /**
     * Prepare audio for incoming call using Enhanced Audio Manager
     */
    override fun prepareAudioForIncomingCall() {
        log.d(TAG) { "Preparing enhanced audio for incoming call" }

        try {
            val success = enhancedAudioManager?.initializeForCall() ?: false
            if (success) {
                isAudioInitialized = true
                log.d(TAG) { "Enhanced audio prepared for incoming call" }
            } else {
                log.w(TAG) { "Failed to prepare enhanced audio for incoming call" }
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error preparing audio for incoming call: ${e.message}" }
        }
    }

    /**
     * Create an SDP offer for starting a call
     */
    override suspend fun createOffer(): String {
        log.d(TAG) { "Creating SDP offer..." }

        // Make sure WebRTC and audio is initialized
        if (!isInitialized) {
            log.d(TAG) { "WebRTC not initialized, initializing now" }
            initialize()
        } else if (!isAudioInitialized) {
            // Reinitialize audio settings for outgoing call
            initializeEnhancedAudio()
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
                log.w(TAG) { "Failed to prepare local audio track!" }
            }
        }

        val options = OfferAnswerOptions(
            voiceActivityDetection = true
        )

        val sessionDescription = peerConn.createOffer(options)
        peerConn.setLocalDescription(sessionDescription)

        // Ensure microphone is unmuted for outgoing call
        setMuted(false)

        log.d(TAG) { "Created offer SDP: ${sessionDescription.sdp}" }
        return sessionDescription.sdp
    }

    /**
     * Create an SDP answer in response to an offer
     */
    override suspend fun createAnswer(accountInfo: AccountInfo, offerSdp: String): String {
        log.d(TAG) { "Creating SDP answer..." }

        // Make sure WebRTC and audio is initialized
        if (!isInitialized) {
            log.d(TAG) { "WebRTC not initialized, initializing now" }
            initialize()
        } else if (!isAudioInitialized) {
            // Reinitialize audio settings for incoming call
            initializeEnhancedAudio()
        }

        val peerConn = peerConnection ?: run {
            log.d(TAG) { "PeerConnection not initialized, reinitializing" }
            initializePeerConnection()
            peerConnection ?: throw IllegalStateException("PeerConnection initialization failed")
        }

        // Make sure local audio track is added BEFORE setting remote description
        if (!isLocalAudioReady) {
            log.d(TAG) { "Ensuring local audio track is ready before answering..." }
            isLocalAudioReady = ensureLocalAudioTrack()
            if (!isLocalAudioReady) {
                log.w(TAG) { "Failed to prepare local audio track for answering!" }
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

        // Enable audio and unmute for answering the call
        setAudioEnabled(true)
        setMuted(false)

        log.d(TAG) { "Created answer SDP: ${sessionDescription.sdp}" }
        return sessionDescription.sdp
    }

    /**
     * Set the remote description (offer or answer)
     */
    override suspend fun setRemoteDescription(sdp: String, type: SdpType) {
        log.d(TAG) { "Setting remote description type: $type" }

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
            setMuted(false)
        }
    }

    /**
     * Add an ICE candidate received from the remote party
     */
    override suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        log.d(TAG) { "Adding ICE candidate: $candidate" }

        if (!isInitialized) {
            log.d(TAG) { "WebRTC not initialized, initializing now" }
            initialize()
            if (peerConnection == null) {
                log.w(TAG) { "Failed to initialize PeerConnection, cannot add ICE candidate" }
                return
            }
        }

        val peerConn = peerConnection ?: run {
            log.w(TAG) { "PeerConnection not available, cannot add ICE candidate" }
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
     * Get the local SDP description
     */
    override fun getLocalDescription(): String? {
        return peerConnection?.localDescription?.sdp
    }

    /**
     * Set media direction (sendrecv, sendonly, recvonly, inactive)
     */
    override suspend fun setMediaDirection(direction: WebRtcManager.MediaDirection) {
        log.d(TAG) { "Setting media direction: $direction" }

        if (!isInitialized || peerConnection == null) {
            log.w(TAG) { "Cannot set media direction: WebRTC not initialized" }
            return
        }

        val peerConn = peerConnection ?: return

        try {
            val currentDesc = peerConn.localDescription ?: return
            val modifiedSdp = updateSdpDirection(currentDesc.sdp, direction)

            val newDesc = SessionDescription(
                type = currentDesc.type,
                sdp = modifiedSdp
            )

            peerConn.setLocalDescription(newDesc)

            if (peerConn.remoteDescription != null) {
                val options = OfferAnswerOptions(voiceActivityDetection = true)

                val sessionDesc = if (currentDesc.type == SessionDescriptionType.Offer) {
                    peerConn.createOffer(options)
                } else {
                    peerConn.createAnswer(options)
                }

                val finalSdp = updateSdpDirection(sessionDesc.sdp, direction)
                val finalDesc = SessionDescription(
                    type = sessionDesc.type,
                    sdp = finalSdp
                )

                peerConn.setLocalDescription(finalDesc)
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error setting media direction: ${e.message}" }
        }
    }

    /**
     * Apply modified SDP to the peer connection
     */
    override suspend fun applyModifiedSdp(modifiedSdp: String): Boolean {
        return try {
            val description = SessionDescription(SessionDescriptionType.Offer, modifiedSdp)
            peerConnection?.setLocalDescription(description)
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error applying modified SDP: ${e.message}" }
            false
        }
    }

    /**
     * Enable or disable the local audio track
     */
    override fun setAudioEnabled(enabled: Boolean) {
        log.d(TAG) { "Setting audio enabled: $enabled" }

        try {
            // Use Enhanced Audio Manager if available
            enhancedAudioManager?.setMuted(!enabled)

            if (localAudioTrack == null && isInitialized) {
                log.d(TAG) { "No local audio track but WebRTC is initialized, trying to add audio track" }
                coroutineScope.launch {
                    ensureLocalAudioTrack()
                    localAudioTrack?.enabled = enabled
                }
            } else {
                localAudioTrack?.enabled = enabled
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error setting audio enabled: ${e.message}" }
        }
    }

    /**
     * Get current connection state
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
     */
    override fun setListener(listener: Any?) {
        if (listener is WebRtcEventListener) {
            webRtcEventListener = listener
            log.d(TAG) { "WebRTC event listener set" }
        } else {
            log.w(TAG) { "Invalid listener type provided" }
        }
    }

    /**
     * Check if WebRTC is initialized
     */
    override fun isInitialized(): Boolean = isInitialized

    /**
     * Send DTMF tones via RTP (RFC 4733)
     */
    override fun sendDtmfTones(tones: String, duration: Int, gap: Int): Boolean {
        log.d(TAG) { "Sending DTMF tones: $tones (duration: $duration, gap: $gap)" }

        if (!isInitialized || peerConnection == null) {
            log.w(TAG) { "Cannot send DTMF: WebRTC not initialized" }
            return false
        }

        try {
            val audioSender = peerConnection?.getSenders()?.find { sender ->
                sender.track?.kind == MediaStreamTrackKind.Audio
            }

            if (audioSender == null) {
                log.w(TAG) { "Cannot send DTMF: No audio sender found" }
                return false
            }

            val dtmfSender = audioSender.dtmf ?: run {
                log.w(TAG) { "Cannot send DTMF: DtmfSender not available" }
                return false
            }

            val sanitizedTones = sanitizeDtmfTones(tones)
            if (sanitizedTones.isEmpty()) {
                log.w(TAG) { "Cannot send DTMF: No valid tones to send" }
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
            log.e(TAG) { "Error sending DTMF tones: ${e.message}" }
            return false
        }
    }

    /**
     * Enhanced cleanup with Enhanced Audio Manager disposal
     */
    override fun dispose() {
        log.d(TAG) { "Disposing Enhanced WebRtcManager resources..." }

        try {
            // Clean up WebRTC resources first
            cleanupCall()

            // Dispose Enhanced Audio Manager
            enhancedAudioManager?.dispose()
            enhancedAudioManager = null

            // Reset state
            isInitialized = false
            isLocalAudioReady = false
            isAudioInitialized = false

            // Cancel coroutine scope
            coroutineScope.cancel()

        } catch (e: Exception) {
            log.e(TAG) { "Error during disposal: ${e.message}" }
        }
    }

    // ===============================
    // PRIVATE HELPER METHODS
    // ===============================

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
     * Initializes the PeerConnection with ICE configuration and sets up event observers
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

            peerConnection = PeerConnection(rtcConfig).apply {
                setupPeerConnectionObservers()
            }

            log.d(TAG) { "PeerConnection created: ${peerConnection != null}" }
            isLocalAudioReady = false
        } catch (e: Exception) {
            log.e(TAG) { "Error initializing PeerConnection: ${e.message}" }
            peerConnection = null
            isInitialized = false
            isLocalAudioReady = false
        }
    }

    /**
     * Configures the observers for the PeerConnection events
     */
    private fun PeerConnection.setupPeerConnectionObservers() {
        onIceCandidate.onEach { candidate ->
            log.d(TAG) { "New ICE Candidate: ${candidate.candidate}" }
            webRtcEventListener?.onIceCandidate(
                candidate.candidate,
                candidate.sdpMid,
                candidate.sdpMLineIndex
            )
        }.launchIn(coroutineScope)

        onConnectionStateChange.onEach { state ->
            log.d(TAG) { "Connection state changed: $state" }

            when (state) {
                PeerConnectionState.Connected -> {
                    log.d(TAG) { "Call active: Connected" }
                    CallStateManager.updateCallState(CallState.CONNECTED)
                    setAudioEnabled(true)
                    setMuted(false)
                }

                PeerConnectionState.Disconnected,
                PeerConnectionState.Failed,
                PeerConnectionState.Closed -> {
                    CallStateManager.updateCallState(CallState.ENDED)
                    log.d(TAG) { "Call ended" }
                    // Enhanced Audio Manager will handle cleanup
                }

                else -> {
                    log.d(TAG) { "Other connection state: $state" }
                }
            }

            webRtcEventListener?.onConnectionStateChange(mapConnectionState(state))
        }.launchIn(coroutineScope)

        onTrack.onEach { event ->
            log.d(TAG) { "Remote track received: $event" }
            val track = event.receiver.track

            if (track is AudioStreamTrack) {
                log.d(TAG) { "Remote audio track established" }
                remoteAudioTrack = track
                remoteAudioTrack?.enabled = true
                webRtcEventListener?.onRemoteAudioTrack()
            }
        }.launchIn(coroutineScope)
    }

    /**
     * Ensures the local audio track is created and added to the PeerConnection
     */
    private suspend fun ensureLocalAudioTrack(): Boolean {
        return try {
            val peerConn = peerConnection ?: run {
                log.w(TAG) { "PeerConnection not initialized" }
                return false
            }

            if (localAudioTrack != null) {
                log.d(TAG) { "Local audio track already exists" }
                return true
            }

            // Ensure Enhanced Audio Manager is ready
            if (!isAudioInitialized) {
                initializeEnhancedAudio()
            }

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

                // Log current audio route for troubleshooting
                val currentRoute = enhancedAudioManager?.getCurrentAudioRoute()
                log.d(TAG) { "Current enhanced audio route: $currentRoute" }

                true
            } else {
                log.e(TAG) { "Error: No audio track found" }
                false
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error getting audio: ${e.message}" }
            false
        }
    }

    /**
     * Cleans up call resources
     */
    private fun cleanupCall() {
        try {
            // Stop any active media operations
            localAudioTrack?.enabled = false

            // Remove tracks from peer connection
            peerConnection?.let { pc ->
                pc.getSenders().forEach { sender ->
                    try {
                        pc.removeTrack(sender)
                    } catch (e: Exception) {
                        log.w(TAG) { "Error removing track: ${e.message}" }
                    }
                }
            }

            // Close peer connection
            peerConnection?.close()
            peerConnection = null

            // Wait for cleanup
            Thread.sleep(100)

            // Dispose media resources
            localAudioTrack = null
            remoteAudioTrack = null
            isLocalAudioReady = false

            // Force garbage collection
            System.gc()

        } catch (e: Exception) {
            log.e(TAG) { "Error in cleanupCall: ${e.message}" }
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

            if (line.startsWith("m=")) {
                inMediaSection = true
                inAudioSection = line.startsWith("m=audio")
            }

            if (inMediaSection && inAudioSection) {
                if (line.startsWith("a=sendrecv") ||
                    line.startsWith("a=sendonly") ||
                    line.startsWith("a=recvonly") ||
                    line.startsWith("a=inactive")
                ) {
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

    /**
     * Sanitizes DTMF tones to ensure only valid characters are sent
     */
    private fun sanitizeDtmfTones(tones: String): String {
        val validDtmfPattern = Regex("[0-9A-D*#,]", RegexOption.IGNORE_CASE)
        return tones.filter { tone ->
            validDtmfPattern.matches(tone.toString())
        }
    }

    /**
     * Get fallback devices when enhanced detection fails
     */
    private fun getFallbackDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        log.w(TAG) { "Using fallback audio devices" }

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

        // Add earpiece if device supports telephony
        if (context.packageManager?.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) == true) {
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
                    isCurrent = outputDevices.isEmpty(),
                    isDefault = outputDevices.isEmpty()
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

    // ===============================
    // ADDITIONAL ENHANCED FEATURES
    // ===============================

    /**
     * Get devices sorted by quality score using Enhanced Audio Manager
     */
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
    fun getAudioDevicesByQuality(): Pair<List<AudioDevice>, List<AudioDevice>> {
        return try {
            val enhancedDevices = enhancedAudioManager?.getConnectedDevices() ?: emptyList()
            val legacyDevices = mapToLegacyAudioDevices(enhancedDevices)

            val inputDevices =
                legacyDevices.filter { !it.isOutput }.sortedByDescending { it.qualityScore }
            val outputDevices =
                legacyDevices.filter { it.isOutput }.sortedByDescending { it.qualityScore }

            Pair(inputDevices, outputDevices)
        } catch (e: Exception) {
            log.e(TAG) { "Error getting devices by quality: ${e.message}" }
            getAllAudioDevices()
        }
    }

    /**
     * Get recommended device for optimal call quality
     */
    @SuppressLint("MissingPermission")
    fun getRecommendedAudioDevice(isOutput: Boolean): AudioDevice? {
        val (inputs, outputs) = getAudioDevicesByQuality()
        val devices = if (isOutput) outputs else inputs

        // Prefer current device if it has good quality
        val currentDevice = if (isOutput) getCurrentOutputDevice() else getCurrentInputDevice()
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
     * Switch to optimal audio route automatically
     */
    fun selectOptimalAudioRoute(): Boolean {
        return try {
            enhancedAudioManager?.selectOptimalAudioRoute() ?: false
        } catch (e: Exception) {
            log.e(TAG) { "Error selecting optimal audio route: ${e.message}" }
            false
        }
    }

    /**
     * Add device change listener
     */
    fun addDeviceChangeListener(listener: (List<AudioDevice>) -> Unit) {
        enhancedAudioManager?.addDeviceChangeListener { devices ->
            listener(mapToLegacyAudioDevices(devices))
        }
    }

    /**
     * Remove device change listener
     */
    fun removeDeviceChangeListener(listener: (List<AudioDevice>) -> Unit) {
        // Enhanced Audio Manager handles listener removal internally
        // This method is kept for API compatibility
    }

    /**
     * Get enhanced audio diagnostics with WebRTC integration
     */
    @SuppressLint("MissingPermission")
    fun getEnhancedDiagnostics(): String {
        return buildString {
            appendLine("=== COMPLETE AUDIO SYSTEM DIAGNOSTICS ===")
            appendLine("Timestamp: ${System.currentTimeMillis()}")
            appendLine()

            // WebRTC State
            appendLine("--- WebRTC Manager State ---")
            appendLine("Initialized: $isInitialized")
            appendLine("Enhanced Audio Initialized: $isAudioInitialized")
            appendLine("Local Audio Ready: $isLocalAudioReady")
            appendLine("Connection State: ${getConnectionState()}")
            appendLine("Is Muted: ${isMuted()}")
            appendLine()

            // Enhanced Audio Manager Diagnostics
            enhancedAudioManager?.let { manager ->
                appendLine(manager.getDiagnostics())
            } ?: run {
                appendLine("ERROR: Enhanced Audio Manager not available")
            }

            // Device Quality Rankings
            appendLine("\n--- Device Quality Rankings ---")
            try {
                val (inputs, outputs) = getAudioDevicesByQuality()

                appendLine("Input Devices (by quality):")
                inputs.forEachIndexed { index, device ->
                    appendLine("  ${index + 1}. ${device.name} - Score: ${device.qualityScore}")
                }

                appendLine("Output Devices (by quality):")
                outputs.forEachIndexed { index, device ->
                    appendLine("  ${index + 1}. ${device.name} - Score: ${device.qualityScore}")
                }

                // Recommendations
                appendLine("\n--- Recommendations ---")
                val recommendedInput = getRecommendedAudioDevice(false)
                val recommendedOutput = getRecommendedAudioDevice(true)
                appendLine("Recommended Input: ${recommendedInput?.name ?: "None"}")
                appendLine("Recommended Output: ${recommendedOutput?.name ?: "None"}")

            } catch (e: Exception) {
                appendLine("Error generating quality rankings: ${e.message}")
            }

            // Performance Metrics
            appendLine("\n--- Performance Metrics ---")
            appendLine(
                "Memory Usage: ${
                    Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                } bytes"
            )
            appendLine("Available Processors: ${Runtime.getRuntime().availableProcessors()}")
        }
    }

    /**
     * Force audio route refresh - useful when devices change
     */
    fun refreshAudioRouting(): Boolean {
        return try {
            log.d(TAG) { "Refreshing audio routing..." }

            // Re-scan devices
            val success = enhancedAudioManager?.selectOptimalAudioRoute() ?: false

            if (success) {
                log.d(TAG) { "Audio routing refreshed successfully" }
            } else {
                log.w(TAG) { "Failed to refresh audio routing" }
            }

            success
        } catch (e: Exception) {
            log.e(TAG) { "Error refreshing audio routing: ${e.message}" }
            false
        }
    }

    /**
     * Test audio path - useful for diagnostics
     */
    suspend fun testAudioPath(): Boolean {
        return try {
            log.d(TAG) { "Testing audio path..." }

            val inputDevice = getCurrentInputDevice()
            val outputDevice = getCurrentOutputDevice()

            log.d(TAG) { "Current input: ${inputDevice?.name}" }
            log.d(TAG) { "Current output: ${outputDevice?.name}" }

            // Test that we can create an audio track
            val testSuccessful = localAudioTrack != null || ensureLocalAudioTrack()

            if (testSuccessful) {
                log.d(TAG) { "Audio path test successful" }
            } else {
                log.w(TAG) { "Audio path test failed" }
            }

            testSuccessful
        } catch (e: Exception) {
            log.e(TAG) { "Error testing audio path: ${e.message}" }
            false
        }
    }
}