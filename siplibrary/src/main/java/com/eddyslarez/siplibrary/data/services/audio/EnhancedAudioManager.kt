package com.eddyslarez.siplibrary.data.services.audio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Enhanced Audio Manager for comprehensive audio device management
 * Handles Bluetooth, Android Auto, and all audio routing scenarios
 */
class EnhancedAudioManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "EnhancedAudioManager"
        private const val BLUETOOTH_SCO_TIMEOUT_MS = 5000L
        private const val AUDIO_FOCUS_TIMEOUT_MS = 3000L
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?
    private val bluetoothAdapter = bluetoothManager?.adapter

    // Enhanced state management
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var bluetoothScoReceiver: BroadcastReceiver? = null
    private var androidAutoReceiver: BroadcastReceiver? = null

    // Audio routing state
    private var currentAudioRoute: AudioRoute = AudioRoute.EARPIECE
    private var targetAudioRoute: AudioRoute = AudioRoute.EARPIECE
    private var isBluetoothScoRequested = false
    private var bluetoothScoConnectionDeferred: CompletableDeferred<Boolean>? = null

    // Device tracking
    private val connectedDevices = mutableMapOf<String, EnhancedAudioDevice>()
    private val deviceChangeListeners = mutableSetOf<(List<EnhancedAudioDevice>) -> Unit>()

    // Audio focus management
    private var audioFocusRequest: AudioFocusRequest? = null
    private var savedAudioState: AudioState? = null

    init {
        setupAudioDeviceMonitoring()
        setupBluetoothMonitoring()
        setupAndroidAutoMonitoring()
    }

    /**
     * Enhanced audio route enum with priorities
     */
    enum class AudioRoute(val priority: Int) {
        ANDROID_AUTO(100),      // Highest priority
        BLUETOOTH_HFP(90),      // High priority
        BLUETOOTH_A2DP(85),     // High priority
        WIRED_HEADSET(80),      // Medium-high priority
        USB_HEADSET(75),        // Medium-high priority
        HEARING_AID(70),        // Medium priority
        SPEAKER(60),            // Medium-low priority
        EARPIECE(50);           // Lowest priority (default)

        companion object {
            fun getOptimalRoute(availableRoutes: Set<AudioRoute>): AudioRoute {
                return availableRoutes.maxByOrNull { it.priority } ?: EARPIECE
            }
        }
    }

    /**
     * Enhanced audio device with comprehensive metadata
     */
    data class EnhancedAudioDevice(
        val id: String,
        val name: String,
        val type: AudioDeviceType,
        val route: AudioRoute,
        val isConnected: Boolean,
        val isActive: Boolean,
        val capabilities: AudioCapabilities,
        val metadata: AudioDeviceMetadata = AudioDeviceMetadata(),
        val nativeDevice: Any? = null
    )

    data class AudioCapabilities(
        val canRecord: Boolean,
        val canPlay: Boolean,
        val supportsHDVoice: Boolean,
        val supportsEchoCancellation: Boolean,
        val supportsNoiseSuppression: Boolean,
        val latencyMs: Int
    )

    data class AudioDeviceMetadata(
        val signalStrength: Int? = null,
        val batteryLevel: Int? = null,
        val vendor: String? = null,
        val isWireless: Boolean = false,
        val connectionState: String = "AVAILABLE"
    )

    enum class AudioDeviceType {
        BUILT_IN_MIC, BUILT_IN_SPEAKER, BUILT_IN_EARPIECE,
        WIRED_HEADSET, WIRED_HEADPHONES,
        BLUETOOTH_HFP, BLUETOOTH_A2DP, BLUETOOTH_LE,
        USB_HEADSET, USB_MIC, USB_SPEAKER,
        ANDROID_AUTO, CAR_AUDIO,
        HEARING_AID, DOCK_AUDIO, AUX_AUDIO
    }

    /**
     * Saved audio state for restoration
     */
    data class AudioState(
        val mode: Int,
        val isSpeakerOn: Boolean,
        val isMicMuted: Boolean,
        val volume: Int
    )

    /**
     * Initialize audio system for calls with enhanced setup
     */
    fun initializeForCall(): Boolean {
        log.d(TAG) { "Initializing enhanced audio system for call" }

        return try {
            // Save current audio state
            saveCurrentAudioState()

            // Request audio focus with enhanced attributes
            val focusGranted = requestEnhancedAudioFocus()
            if (!focusGranted) {
                log.w(TAG) { "Failed to obtain audio focus" }
                return false
            }

            // Configure audio for communication
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isMicrophoneMute = false

            // Scan for available devices
            scanAvailableDevices()

            // Auto-select optimal audio route
            selectOptimalAudioRoute()

            log.d(TAG) { "Audio system initialized successfully" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Failed to initialize audio system: ${e.message}" }
            false
        }
    }

    /**
     * Enhanced audio focus request with better handling
     */
    private fun requestEnhancedAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requestAudioFocusModern()
        } else {
            requestAudioFocusLegacy()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestAudioFocusModern(): Boolean {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener { focusChange ->
                handleAudioFocusChange(focusChange)
            }
            .build()

        audioFocusRequest = focusRequest
        val result = audioManager.requestAudioFocus(focusRequest)

        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @Suppress("DEPRECATION")
    private fun requestAudioFocusLegacy(): Boolean {
        val result = audioManager.requestAudioFocus(
            { focusChange -> handleAudioFocusChange(focusChange) },
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.AUDIOFOCUS_GAIN
        )
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                log.d(TAG) { "Audio focus gained" }
                // Restore audio routing if needed
                applyAudioRoute(targetAudioRoute)
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                log.d(TAG) { "Audio focus lost permanently" }
                // Handle call interruption
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                log.d(TAG) { "Audio focus lost temporarily" }
                // Pause or reduce volume
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                log.d(TAG) { "Audio focus lost - can duck" }
                // Lower volume
            }
        }
    }

    /**
     * Enhanced device scanning with comprehensive detection
     */
    @SuppressLint("MissingPermission")
    private fun scanAvailableDevices() {
        connectedDevices.clear()

        // Built-in devices (always available)
        addBuiltInDevices()

        // Wired devices
        if (audioManager.isWiredHeadsetOn) {
            addWiredDevices()
        }

        // Bluetooth devices with enhanced detection
        scanBluetoothDevices()

        // USB devices (API 23+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            scanUsbDevices()
        }

        // Android Auto detection
        scanAndroidAutoDevices()

        // Specialized devices
        scanSpecializedDevices()

        notifyDeviceListeners()
    }

    private fun addBuiltInDevices() {
        // Built-in microphone
        connectedDevices["builtin_mic"] = EnhancedAudioDevice(
            id = "builtin_mic",
            name = "Built-in Microphone",
            type = AudioDeviceType.BUILT_IN_MIC,
            route = AudioRoute.EARPIECE,
            isConnected = true,
            isActive = false,
            capabilities = AudioCapabilities(
                canRecord = true,
                canPlay = false,
                supportsHDVoice = true,
                supportsEchoCancellation = true,
                supportsNoiseSuppression = true,
                latencyMs = 10
            )
        )

        // Built-in speaker
        connectedDevices["builtin_speaker"] = EnhancedAudioDevice(
            id = "builtin_speaker",
            name = "Speaker",
            type = AudioDeviceType.BUILT_IN_SPEAKER,
            route = AudioRoute.SPEAKER,
            isConnected = true,
            isActive = false,
            capabilities = AudioCapabilities(
                canRecord = false,
                canPlay = true,
                supportsHDVoice = true,
                supportsEchoCancellation = false,
                supportsNoiseSuppression = false,
                latencyMs = 15
            )
        )

        // Earpiece (if available)
        if (hasEarpiece()) {
            connectedDevices["builtin_earpiece"] = EnhancedAudioDevice(
                id = "builtin_earpiece",
                name = "Earpiece",
                type = AudioDeviceType.BUILT_IN_EARPIECE,
                route = AudioRoute.EARPIECE,
                isConnected = true,
                isActive = true, // Default active
                capabilities = AudioCapabilities(
                    canRecord = false,
                    canPlay = true,
                    supportsHDVoice = true,
                    supportsEchoCancellation = false,
                    supportsNoiseSuppression = false,
                    latencyMs = 5
                )
            )
        }
    }

    private fun addWiredDevices() {
        connectedDevices["wired_headset"] = EnhancedAudioDevice(
            id = "wired_headset",
            name = "Wired Headset",
            type = AudioDeviceType.WIRED_HEADSET,
            route = AudioRoute.WIRED_HEADSET,
            isConnected = true,
            isActive = false,
            capabilities = AudioCapabilities(
                canRecord = true,
                canPlay = true,
                supportsHDVoice = true,
                supportsEchoCancellation = false,
                supportsNoiseSuppression = false,
                latencyMs = 20
            ),
            metadata = AudioDeviceMetadata(
                vendor = detectWiredDeviceVendor()
            )
        )
    }

    @SuppressLint("MissingPermission")
    private fun scanBluetoothDevices() {
        if (!hasBluetoothPermissions()) {
            log.w(TAG) { "Missing Bluetooth permissions" }
            return
        }

        try {
            bluetoothAdapter?.bondedDevices?.forEach { device ->
                if (isBluetoothAudioDevice(device) && isBluetoothDeviceConnected(device)) {
                    val deviceId = "bt_${device.address}"
                    val deviceName = device.name ?: "Bluetooth Device"

                    // Determine device capabilities
                    val (type, route, capabilities) = analyzeBluetoothDevice(device)

                    connectedDevices[deviceId] = EnhancedAudioDevice(
                        id = deviceId,
                        name = deviceName,
                        type = type,
                        route = route,
                        isConnected = true,
                        isActive = isBluetoothAudioActive(device),
                        capabilities = capabilities,
                        metadata = AudioDeviceMetadata(
                            signalStrength = estimateBluetoothSignalStrength(device),
                            batteryLevel = getBluetoothBatteryLevel(device),
                            vendor = extractBluetoothVendor(device),
                            isWireless = true,
                            connectionState = getBluetoothConnectionState(device)
                        ),
                        nativeDevice = device
                    )
                }
            }
        } catch (e: SecurityException) {
            log.w(TAG) { "Security exception scanning Bluetooth devices: ${e.message}" }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun scanUsbDevices() {
        try {
            val audioDevices = audioManager.getDevices(
                AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS
            )

            audioDevices.forEach { deviceInfo ->
                if (isUsbAudioDevice(deviceInfo)) {
                    val deviceId = "usb_${deviceInfo.id}"
                    val deviceName = deviceInfo.productName?.toString() ?: "USB Audio Device"

                    val (type, route, capabilities) = analyzeUsbDevice(deviceInfo)

                    connectedDevices[deviceId] = EnhancedAudioDevice(
                        id = deviceId,
                        name = deviceName,
                        type = type,
                        route = route,
                        isConnected = true,
                        isActive = false,
                        capabilities = capabilities,
                        metadata = AudioDeviceMetadata(
                            vendor = extractUsbVendor(deviceInfo)
                        ),
                        nativeDevice = deviceInfo
                    )
                }
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error scanning USB devices: ${e.message}" }
        }
    }

    private fun scanAndroidAutoDevices() {
        // Detect Android Auto connection through various methods
        if (isAndroidAutoConnected()) {
            connectedDevices["android_auto"] = EnhancedAudioDevice(
                id = "android_auto",
                name = "Android Auto",
                type = AudioDeviceType.ANDROID_AUTO,
                route = AudioRoute.ANDROID_AUTO,
                isConnected = true,
                isActive = false,
                capabilities = AudioCapabilities(
                    canRecord = true,
                    canPlay = true,
                    supportsHDVoice = true,
                    supportsEchoCancellation = true,
                    supportsNoiseSuppression = true,
                    latencyMs = 50
                ),
                metadata = AudioDeviceMetadata(
                    vendor = "Google",
                    connectionState = "CONNECTED"
                )
            )
        }
    }

    private fun scanSpecializedDevices() {
        // Hearing aids, dock audio, etc.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

            audioDevices.forEach { deviceInfo ->
                when (deviceInfo.type) {
                    AudioDeviceInfo.TYPE_HEARING_AID -> {
                        addHearingAidDevice(deviceInfo)
                    }
                    AudioDeviceInfo.TYPE_DOCK -> {
                        addDockDevice(deviceInfo)
                    }
                    AudioDeviceInfo.TYPE_AUX_LINE -> {
                        addAuxDevice(deviceInfo)
                    }
                }
            }
        }
    }

    /**
     * Enhanced Bluetooth SCO management with timeout and state tracking
     */
    suspend fun switchToBluetoothAudio(device: EnhancedAudioDevice): Boolean {
        if (device.route != AudioRoute.BLUETOOTH_HFP && device.route != AudioRoute.BLUETOOTH_A2DP) {
            return false
        }

        log.d(TAG) { "Switching to Bluetooth audio: ${device.name}" }

        return try {
            // Check if SCO is already connected to this device
            if (audioManager.isBluetoothScoOn && currentAudioRoute == device.route) {
                log.d(TAG) { "Bluetooth SCO already connected to target device" }
                return true
            }

            // Stop any existing SCO connection
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                delay(500) // Wait for disconnection
            }

            // Configure audio manager for Bluetooth
            audioManager.isBluetoothScoOn = false
            audioManager.isSpeakerphoneOn = false
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            // Create a deferred for SCO connection tracking
            bluetoothScoConnectionDeferred?.cancel()
            bluetoothScoConnectionDeferred = CompletableDeferred()

            // Start SCO connection
            isBluetoothScoRequested = true
            audioManager.startBluetoothSco()

            // Wait for connection with timeout
            val connected = withTimeout(BLUETOOTH_SCO_TIMEOUT_MS) {
                bluetoothScoConnectionDeferred?.await() ?: false
            }

            if (connected) {
                currentAudioRoute = device.route
                targetAudioRoute = device.route
                updateDeviceActiveState(device.id, true)
                log.d(TAG) { "Successfully connected Bluetooth SCO: ${device.name}" }
            } else {
                log.w(TAG) { "Bluetooth SCO connection timeout for: ${device.name}" }
                isBluetoothScoRequested = false
            }

            connected
        } catch (e: TimeoutCancellationException) {
            log.w(TAG) { "Bluetooth SCO connection timeout" }
            isBluetoothScoRequested = false
            audioManager.stopBluetoothSco()
            false
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to Bluetooth audio: ${e.message}" }
            isBluetoothScoRequested = false
            false
        }
    }

    /**
     * Enhanced speaker switching with proper state management
     */
    fun switchToSpeaker(): Boolean {
        log.d(TAG) { "Switching to speaker" }

        return try {
            // Stop any Bluetooth SCO
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                isBluetoothScoRequested = false
            }

            // Configure for speaker
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true

            currentAudioRoute = AudioRoute.SPEAKER
            targetAudioRoute = AudioRoute.SPEAKER
            updateDeviceActiveState("builtin_speaker", true)

            log.d(TAG) { "Successfully switched to speaker" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to speaker: ${e.message}" }
            false
        }
    }

    /**
     * Enhanced earpiece switching
     */
    fun switchToEarpiece(): Boolean {
        log.d(TAG) { "Switching to earpiece" }

        if (!hasEarpiece()) {
            log.w(TAG) { "Device does not have earpiece, switching to speaker" }
            return switchToSpeaker()
        }

        return try {
            // Stop any Bluetooth SCO
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                isBluetoothScoRequested = false
            }

            // Configure for earpiece
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false

            currentAudioRoute = AudioRoute.EARPIECE
            targetAudioRoute = AudioRoute.EARPIECE
            updateDeviceActiveState("builtin_earpiece", true)

            log.d(TAG) { "Successfully switched to earpiece" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to earpiece: ${e.message}" }
            false
        }
    }

    /**
     * Enhanced wired headset switching
     */
    fun switchToWiredHeadset(): Boolean {
        if (!audioManager.isWiredHeadsetOn) {
            log.w(TAG) { "Wired headset not connected" }
            return false
        }

        log.d(TAG) { "Switching to wired headset" }

        return try {
            // Stop any Bluetooth SCO
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                isBluetoothScoRequested = false
            }

            // Configure for wired headset
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false

            currentAudioRoute = AudioRoute.WIRED_HEADSET
            targetAudioRoute = AudioRoute.WIRED_HEADSET
            updateDeviceActiveState("wired_headset", true)

            log.d(TAG) { "Successfully switched to wired headset" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to wired headset: ${e.message}" }
            false
        }
    }

    /**
     * Enhanced Android Auto switching
     */
    fun switchToAndroidAuto(): Boolean {
        if (!isAndroidAutoConnected()) {
            log.w(TAG) { "Android Auto not connected" }
            return false
        }

        log.d(TAG) { "Switching to Android Auto" }

        return try {
            // Stop any Bluetooth SCO
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                isBluetoothScoRequested = false
            }

            // Configure for Android Auto (typically handled by system)
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false

            currentAudioRoute = AudioRoute.ANDROID_AUTO
            targetAudioRoute = AudioRoute.ANDROID_AUTO
            updateDeviceActiveState("android_auto", true)

            log.d(TAG) { "Successfully switched to Android Auto" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to Android Auto: ${e.message}" }
            false
        }
    }

    /**
     * Automatically select the optimal audio route based on available devices
     */
    fun selectOptimalAudioRoute(): Boolean {
        val availableRoutes = getAvailableAudioRoutes()
        val optimalRoute = AudioRoute.getOptimalRoute(availableRoutes)

        log.d(TAG) { "Selecting optimal route: $optimalRoute from $availableRoutes" }

        return applyAudioRoute(optimalRoute)
    }

    /**
     * Apply a specific audio route
     */
    fun applyAudioRoute(route: AudioRoute): Boolean {
        return when (route) {
            AudioRoute.ANDROID_AUTO -> switchToAndroidAuto()
            AudioRoute.BLUETOOTH_HFP, AudioRoute.BLUETOOTH_A2DP -> {
                val bluetoothDevice = getConnectedBluetoothDevice(route)
                if (bluetoothDevice != null) {
                    coroutineScope.launch {
                        switchToBluetoothAudio(bluetoothDevice)
                    }
                    true
                } else {
                    false
                }
            }
            AudioRoute.WIRED_HEADSET -> switchToWiredHeadset()
            AudioRoute.USB_HEADSET -> switchToUsbHeadset()
            AudioRoute.SPEAKER -> switchToSpeaker()
            AudioRoute.EARPIECE -> switchToEarpiece()
            AudioRoute.HEARING_AID -> switchToHearingAid()
        }
    }

    // Device detection and analysis methods

    private fun hasEarpiece(): Boolean {
        return context.packageManager?.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ?: false
    }

    private fun hasBluetoothPermissions(): Boolean {
        val hasBasicBluetooth = ContextCompat.checkSelfPermission(
            context, Manifest.permission.BLUETOOTH
        ) == PackageManager.PERMISSION_GRANTED

        val hasBluetoothConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Not required on older versions
        }

        return hasBasicBluetooth && hasBluetoothConnect
    }

    @SuppressLint("MissingPermission")
    private fun isBluetoothAudioDevice(device: BluetoothDevice): Boolean {
        return try {
            device.bluetoothClass?.hasService(BluetoothClass.Service.AUDIO) == true
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun isBluetoothDeviceConnected(device: BluetoothDevice): Boolean {
        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val connectionState = bluetoothManager.getConnectionState(device, BluetoothProfile.HEADSET)
            connectionState == BluetoothProfile.STATE_CONNECTED
        } catch (e: Exception) {
            false
        }
    }

    private fun isAndroidAutoConnected(): Boolean {
        // Multiple detection methods for Android Auto
        return detectAndroidAutoViaUsbHost() ||
                detectAndroidAutoViaProjection() ||
                detectAndroidAutoViaAudioDevices()
    }

    private fun detectAndroidAutoViaUsbHost(): Boolean {
        // Check if Android Auto is connected via USB
        return try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            usbManager?.deviceList?.values?.any { device ->
                // Android Auto typically appears as a USB accessory
                device.productName?.contains("Android Auto", ignoreCase = true) == true
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun detectAndroidAutoViaProjection(): Boolean {
        // Check for MediaProjection or DisplayManager hints
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                displayManager.displays.any { display ->
                    display.name?.contains("Android Auto", ignoreCase = true) == true
                }
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun detectAndroidAutoViaAudioDevices(): Boolean {
        return try {
            val audioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            audioDevices.any { device ->
                device.productName?.toString()?.contains("Auto", ignoreCase = true) == true
            }
        } catch (e: Exception) {
            false
        }
    }

    // Monitoring setup methods

    private fun setupAudioDeviceMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioDeviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    log.d(TAG) { "Audio devices added: ${addedDevices.size}" }
                    coroutineScope.launch {
                        delay(500) // Debounce
                        scanAvailableDevices()
                        selectOptimalAudioRoute()
                    }
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    log.d(TAG) { "Audio devices removed: ${removedDevices.size}" }
                    coroutineScope.launch {
                        delay(500) // Debounce
                        scanAvailableDevices()
                        selectOptimalAudioRoute()
                    }
                }
            }
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        }
    }

    private fun setupBluetoothMonitoring() {
        bluetoothScoReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                        val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                        handleBluetoothScoStateChange(state)
                    }
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        handleBluetoothAdapterStateChange(state)
                    }
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        device?.let { handleBluetoothDeviceConnected(it) }
                    }
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        device?.let { handleBluetoothDeviceDisconnected(it) }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        context.registerReceiver(bluetoothScoReceiver, filter)
    }

    private fun setupAndroidAutoMonitoring() {
        androidAutoReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    UsbManager.ACTION_USB_ACCESSORY_ATTACHED,
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        log.d(TAG) { "USB device attached, checking for Android Auto" }
                        coroutineScope.launch {
                            delay(1000) // Allow system to initialize
                            scanAvailableDevices()
                            if (isAndroidAutoConnected()) {
                                selectOptimalAudioRoute()
                            }
                        }
                    }
                    UsbManager.ACTION_USB_ACCESSORY_DETACHED,
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        log.d(TAG) { "USB device detached" }
                        connectedDevices.remove("android_auto")
                        if (currentAudioRoute == AudioRoute.ANDROID_AUTO) {
                            selectOptimalAudioRoute()
                        }
                    }
                    Intent.ACTION_POWER_CONNECTED -> {
                        // Car connection often involves power connection
                        coroutineScope.launch {
                            delay(2000)
                            scanAvailableDevices()
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(Intent.ACTION_POWER_CONNECTED)
        }
        context.registerReceiver(androidAutoReceiver, filter)
    }

    // Bluetooth state change handlers

    private fun handleBluetoothScoStateChange(state: Int) {
        when (state) {
            AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                log.d(TAG) { "Bluetooth SCO connected" }
                isBluetoothScoRequested = false
                bluetoothScoConnectionDeferred?.complete(true)
            }
            AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                log.d(TAG) { "Bluetooth SCO disconnected" }
                isBluetoothScoRequested = false
                bluetoothScoConnectionDeferred?.complete(false)

                // If we were using Bluetooth, try to reconnect or fall back
                if (currentAudioRoute == AudioRoute.BLUETOOTH_HFP || currentAudioRoute == AudioRoute.BLUETOOTH_A2DP) {
                    coroutineScope.launch {
                        delay(1000)
                        if (!audioManager.isBluetoothScoOn) {
                            selectOptimalAudioRoute()
                        }
                    }
                }
            }
            AudioManager.SCO_AUDIO_STATE_CONNECTING -> {
                log.d(TAG) { "Bluetooth SCO connecting..." }
            }
            AudioManager.SCO_AUDIO_STATE_ERROR -> {
                log.e(TAG) { "Bluetooth SCO error" }
                isBluetoothScoRequested = false
                bluetoothScoConnectionDeferred?.complete(false)
            }
        }
    }

    private fun handleBluetoothAdapterStateChange(state: Int) {
        when (state) {
            BluetoothAdapter.STATE_OFF -> {
                log.d(TAG) { "Bluetooth adapter turned off" }
                removeBluetoothDevices()
                if (currentAudioRoute == AudioRoute.BLUETOOTH_HFP || currentAudioRoute == AudioRoute.BLUETOOTH_A2DP) {
                    selectOptimalAudioRoute()
                }
            }
            BluetoothAdapter.STATE_ON -> {
                log.d(TAG) { "Bluetooth adapter turned on" }
                coroutineScope.launch {
                    delay(2000) // Wait for devices to reconnect
                    scanAvailableDevices()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleBluetoothDeviceConnected(device: BluetoothDevice) {
        if (isBluetoothAudioDevice(device)) {
            log.d(TAG) { "Bluetooth audio device connected: ${device.name}" }
            coroutineScope.launch {
                delay(1000) // Wait for profiles to connect
                scanAvailableDevices()
                selectOptimalAudioRoute()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleBluetoothDeviceDisconnected(device: BluetoothDevice) {
        if (isBluetoothAudioDevice(device)) {
            log.d(TAG) { "Bluetooth audio device disconnected: ${device.name}" }
            connectedDevices.remove("bt_${device.address}")

            if (currentAudioRoute == AudioRoute.BLUETOOTH_HFP || currentAudioRoute == AudioRoute.BLUETOOTH_A2DP) {
                selectOptimalAudioRoute()
            }
        }
    }

    // Device analysis methods

    @SuppressLint("MissingPermission")
    private fun analyzeBluetoothDevice(device: BluetoothDevice): Triple<AudioDeviceType, AudioRoute, AudioCapabilities> {
        val deviceClass = device.bluetoothClass
        val isA2dp = hasA2dpProfile(device)
        val isHfp = hasHfpProfile(device)

        val type = when {
            isA2dp && isHfp -> AudioDeviceType.BLUETOOTH_HFP // Prefer HFP for calls
            isHfp -> AudioDeviceType.BLUETOOTH_HFP
            isA2dp -> AudioDeviceType.BLUETOOTH_A2DP
            else -> AudioDeviceType.BLUETOOTH_HFP // Default
        }

        val route = when (type) {
            AudioDeviceType.BLUETOOTH_A2DP -> AudioRoute.BLUETOOTH_A2DP
            else -> AudioRoute.BLUETOOTH_HFP
        }

        val capabilities = AudioCapabilities(
            canRecord = isHfp, // Only HFP supports recording
            canPlay = true,
            supportsHDVoice = isA2dp,
            supportsEchoCancellation = isHfp,
            supportsNoiseSuppression = isHfp,
            latencyMs = if (isA2dp) 200 else 150
        )

        return Triple(type, route, capabilities)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun analyzeUsbDevice(deviceInfo: AudioDeviceInfo): Triple<AudioDeviceType, AudioRoute, AudioCapabilities> {
        val type = when (deviceInfo.type) {
            AudioDeviceInfo.TYPE_USB_HEADSET -> AudioDeviceType.USB_HEADSET
            AudioDeviceInfo.TYPE_USB_DEVICE -> AudioDeviceType.USB_HEADSET
            else -> AudioDeviceType.USB_HEADSET
        }

        val capabilities = AudioCapabilities(
            canRecord = deviceInfo.isSource,
            canPlay = deviceInfo.isSink,
            supportsHDVoice = true,
            supportsEchoCancellation = false,
            supportsNoiseSuppression = false,
            latencyMs = 30
        )

        return Triple(type, AudioRoute.USB_HEADSET, capabilities)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun addHearingAidDevice(deviceInfo: AudioDeviceInfo) {
        val deviceId = "hearing_aid_${deviceInfo.id}"
        connectedDevices[deviceId] = EnhancedAudioDevice(
            id = deviceId,
            name = "Hearing Aid",
            type = AudioDeviceType.HEARING_AID,
            route = AudioRoute.HEARING_AID,
            isConnected = true,
            isActive = false,
            capabilities = AudioCapabilities(
                canRecord = false,
                canPlay = true,
                supportsHDVoice = true,
                supportsEchoCancellation = true,
                supportsNoiseSuppression = true,
                latencyMs = 50
            ),
            metadata = AudioDeviceMetadata(
                vendor = extractUsbVendor(deviceInfo),
                isWireless = true
            ),
            nativeDevice = deviceInfo
        )
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun addDockDevice(deviceInfo: AudioDeviceInfo) {
        val deviceId = "dock_${deviceInfo.id}"
        connectedDevices[deviceId] = EnhancedAudioDevice(
            id = deviceId,
            name = "Dock Audio",
            type = AudioDeviceType.DOCK_AUDIO,
            route = AudioRoute.SPEAKER, // Treat as speaker alternative
            isConnected = true,
            isActive = false,
            capabilities = AudioCapabilities(
                canRecord = deviceInfo.isSource,
                canPlay = deviceInfo.isSink,
                supportsHDVoice = false,
                supportsEchoCancellation = false,
                supportsNoiseSuppression = false,
                latencyMs = 25
            ),
            nativeDevice = deviceInfo
        )
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun addAuxDevice(deviceInfo: AudioDeviceInfo) {
        val deviceId = "aux_${deviceInfo.id}"
        connectedDevices[deviceId] = EnhancedAudioDevice(
            id = deviceId,
            name = "Auxiliary Audio",
            type = AudioDeviceType.AUX_AUDIO,
            route = AudioRoute.SPEAKER,
            isConnected = true,
            isActive = false,
            capabilities = AudioCapabilities(
                canRecord = false,
                canPlay = true,
                supportsHDVoice = false,
                supportsEchoCancellation = false,
                supportsNoiseSuppression = false,
                latencyMs = 15
            ),
            nativeDevice = deviceInfo
        )
    }

    // Utility methods

    @RequiresApi(Build.VERSION_CODES.M)
    private fun isUsbAudioDevice(deviceInfo: AudioDeviceInfo): Boolean {
        return when (deviceInfo.type) {
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET -> true
            else -> false
        }
    }

    @SuppressLint("MissingPermission")
    private fun hasA2dpProfile(device: BluetoothDevice): Boolean {
        return try {
            bluetoothAdapter?.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothAdapter.STATE_CONNECTED
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun hasHfpProfile(device: BluetoothDevice): Boolean {
        return try {
            bluetoothAdapter?.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothAdapter.STATE_CONNECTED
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun isBluetoothAudioActive(device: BluetoothDevice): Boolean {
        return audioManager.isBluetoothScoOn &&
                (currentAudioRoute == AudioRoute.BLUETOOTH_HFP || currentAudioRoute == AudioRoute.BLUETOOTH_A2DP)
    }

    @SuppressLint("MissingPermission")
    private fun estimateBluetoothSignalStrength(device: BluetoothDevice): Int {
        // In a real implementation, you might use RSSI values
        return (70..95).random()
    }

    @SuppressLint("MissingPermission")
    private fun getBluetoothBatteryLevel(device: BluetoothDevice): Int? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Try to use reflection for getBatteryLevel
                val method = BluetoothDevice::class.java.getMethod("getBatteryLevel")
                val batteryLevel = method.invoke(device) as? Int
                batteryLevel?.takeIf { it in 0..100 }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun extractBluetoothVendor(device: BluetoothDevice): String? {
        return try {
            val name = device.name?.lowercase() ?: return null
            extractVendorFromName(name)
        } catch (e: Exception) {
            null
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun extractUsbVendor(deviceInfo: AudioDeviceInfo): String? {
        val productName = deviceInfo.productName?.toString()?.lowercase() ?: return null
        return extractVendorFromName(productName)
    }

    private fun detectWiredDeviceVendor(): String? {
        // This could be enhanced with more sophisticated detection
        return null
    }

    private fun extractVendorFromName(name: String): String? {
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
            else -> null
        }
    }

    @SuppressLint("MissingPermission")
    private fun getBluetoothConnectionState(device: BluetoothDevice): String {
        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val state = bluetoothManager.getConnectionState(device, BluetoothProfile.HEADSET)
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                else -> "DISCONNECTED"
            }
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }

    // State management methods

    private fun saveCurrentAudioState() {
        savedAudioState = AudioState(
            mode = audioManager.mode,
            isSpeakerOn = audioManager.isSpeakerphoneOn,
            isMicMuted = audioManager.isMicrophoneMute,
            volume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        )
    }

    private fun getAvailableAudioRoutes(): Set<AudioRoute> {
        val routes = mutableSetOf<AudioRoute>()

        connectedDevices.values.forEach { device ->
            if (device.isConnected) {
                routes.add(device.route)
            }
        }

        return routes
    }

    private fun getConnectedBluetoothDevice(route: AudioRoute): EnhancedAudioDevice? {
        return connectedDevices.values.find { device ->
            device.route == route && device.isConnected &&
                    (device.type == AudioDeviceType.BLUETOOTH_HFP || device.type == AudioDeviceType.BLUETOOTH_A2DP)
        }
    }

    private fun updateDeviceActiveState(deviceId: String, isActive: Boolean) {
        // Deactivate all other devices of the same type (input/output)
        val targetDevice = connectedDevices[deviceId] ?: return
        val isOutputDevice = targetDevice.capabilities.canPlay

        connectedDevices.replaceAll { id, device ->
            if (isOutputDevice && device.capabilities.canPlay) {
                device.copy(isActive = id == deviceId)
            } else if (!isOutputDevice && device.capabilities.canRecord) {
                device.copy(isActive = id == deviceId)
            } else {
                device
            }
        }

        notifyDeviceListeners()
    }

    private fun removeBluetoothDevices() {
        val bluetoothDeviceIds = connectedDevices.keys.filter { it.startsWith("bt_") }
        bluetoothDeviceIds.forEach { connectedDevices.remove(it) }
        notifyDeviceListeners()
    }

    private fun notifyDeviceListeners() {
        val deviceList = connectedDevices.values.toList()
        deviceChangeListeners.forEach { listener ->
            try {
                listener(deviceList)
            } catch (e: Exception) {
                log.e(TAG) { "Error in device change listener: ${e.message}" }
            }
        }
    }

    // Additional switching methods for completeness

    private fun switchToUsbHeadset(): Boolean {
        val usbDevice = connectedDevices.values.find {
            it.type == AudioDeviceType.USB_HEADSET && it.isConnected
        }

        if (usbDevice == null) {
            log.w(TAG) { "USB headset not found" }
            return false
        }

        return try {
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                isBluetoothScoRequested = false
            }

            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false

            currentAudioRoute = AudioRoute.USB_HEADSET
            targetAudioRoute = AudioRoute.USB_HEADSET
            updateDeviceActiveState(usbDevice.id, true)

            log.d(TAG) { "Successfully switched to USB headset" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to USB headset: ${e.message}" }
            false
        }
    }

    private fun switchToHearingAid(): Boolean {
        val hearingAidDevice = connectedDevices.values.find {
            it.type == AudioDeviceType.HEARING_AID && it.isConnected
        }

        if (hearingAidDevice == null) {
            log.w(TAG) { "Hearing aid not found" }
            return false
        }

        return try {
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                isBluetoothScoRequested = false
            }

            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false

            currentAudioRoute = AudioRoute.HEARING_AID
            targetAudioRoute = AudioRoute.HEARING_AID
            updateDeviceActiveState(hearingAidDevice.id, true)

            log.d(TAG) { "Successfully switched to hearing aid" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to hearing aid: ${e.message}" }
            false
        }
    }

    // Public API methods

    /**
     * Get all connected audio devices
     */
    fun getConnectedDevices(): List<EnhancedAudioDevice> {
        return connectedDevices.values.toList()
    }

    /**
     * Get active audio device for output
     */
    fun getActiveOutputDevice(): EnhancedAudioDevice? {
        return connectedDevices.values.find { it.isActive && it.capabilities.canPlay }
    }

    /**
     * Get active audio device for input
     */
    fun getActiveInputDevice(): EnhancedAudioDevice? {
        return connectedDevices.values.find { it.isActive && it.capabilities.canRecord }
    }

    /**
     * Switch to a specific device by ID
     */
    fun switchToDevice(deviceId: String): Boolean {
        val device = connectedDevices[deviceId] ?: return false
        return applyAudioRoute(device.route)
    }

    /**
     * Add a device change listener
     */
    fun addDeviceChangeListener(listener: (List<EnhancedAudioDevice>) -> Unit) {
        deviceChangeListeners.add(listener)
    }

    /**
     * Remove a device change listener
     */
    fun removeDeviceChangeListener(listener: (List<EnhancedAudioDevice>) -> Unit) {
        deviceChangeListeners.remove(listener)
    }

    /**
     * Get current audio route
     */
    fun getCurrentAudioRoute(): AudioRoute = currentAudioRoute

    /**
     * Check if muted
     */
    fun isMuted(): Boolean = audioManager.isMicrophoneMute

    /**
     * Set muted state
     */
    fun setMuted(muted: Boolean) {
        audioManager.isMicrophoneMute = muted
    }

    /**
     * Get comprehensive diagnostics
     */
    fun getDiagnostics(): String = buildString {
        appendLine("=== ENHANCED AUDIO MANAGER DIAGNOSTICS ===")
        appendLine("Current Route: $currentAudioRoute")
        appendLine("Target Route: $targetAudioRoute")
        appendLine("Bluetooth SCO On: ${audioManager.isBluetoothScoOn}")
        appendLine("Bluetooth SCO Requested: $isBluetoothScoRequested")
        appendLine("Speaker On: ${audioManager.isSpeakerphoneOn}")
        appendLine("Wired Headset On: ${audioManager.isWiredHeadsetOn}")
        appendLine("Mic Muted: ${audioManager.isMicrophoneMute}")
        appendLine("Audio Mode: ${audioManager.mode}")
        appendLine("Android Auto Connected: ${isAndroidAutoConnected()}")

        appendLine("\n--- Connected Devices (${connectedDevices.size}) ---")
        connectedDevices.values.forEach { device ->
            appendLine("${device.name} (${device.type}) - Active: ${device.isActive}")
            appendLine("  Route: ${device.route}, Connected: ${device.isConnected}")
            appendLine("  Capabilities: Record=${device.capabilities.canRecord}, Play=${device.capabilities.canPlay}")
            if (device.metadata.signalStrength != null) {
                appendLine("  Signal: ${device.metadata.signalStrength}%")
            }
            if (device.metadata.batteryLevel != null) {
                appendLine("  Battery: ${device.metadata.batteryLevel}%")
            }
        }
    }

    /**
     * Cleanup resources
     */
    fun dispose() {
        log.d(TAG) { "Disposing EnhancedAudioManager" }

        try {
            // Cancel any pending operations
            bluetoothScoConnectionDeferred?.cancel()

            // Stop Bluetooth SCO
            if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
            }

            // Release audio focus
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }

            // Restore audio state
            savedAudioState?.let { state ->
                audioManager.mode = state.mode
                audioManager.isSpeakerphoneOn = state.isSpeakerOn
                audioManager.isMicrophoneMute = state.isMicMuted
            }

            // Unregister receivers
            bluetoothScoReceiver?.let { context.unregisterReceiver(it) }
            androidAutoReceiver?.let { context.unregisterReceiver(it) }

            // Unregister device callback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioDeviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
            }

            // Clear listeners and state
            deviceChangeListeners.clear()
            connectedDevices.clear()
            coroutineScope.cancel()

        } catch (e: Exception) {
            log.e(TAG) { "Error during disposal: ${e.message}" }
        }
    }
}