package com.eddyslarez.siplibrary.data.services.audiov2

import android.Manifest
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
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresPermission
import com.eddyslarez.siplibrary.data.services.audio.AudioDevice
import com.eddyslarez.siplibrary.data.services.audio.AudioUnit
import com.eddyslarez.siplibrary.data.services.audio.AudioUnitCompatibilities
import com.eddyslarez.siplibrary.data.services.audio.AudioUnitTypes
import com.eddyslarez.siplibrary.data.services.audio.DeviceConnectionState
import com.eddyslarez.siplibrary.utils.log
//
//class BluetoothAudioManager(private val context: Context) {
//    private val TAG = "BluetoothAudioManager"
//
//    private var bluetoothAdapter: BluetoothAdapter? = null
//    private var bluetoothManager: BluetoothManager? = null
//    private var bluetoothScoReceiver: BroadcastReceiver? = null
//    private var bluetoothA2dpProxy: BluetoothA2dp? = null
//    private var bluetoothHeadsetProxy: BluetoothHeadset? = null
//
//    // Enhanced state tracking
//    private var isBluetoothScoRequested = false
//    private var isA2dpConnected = false
//    private var connectedA2dpDevices = mutableSetOf<BluetoothDevice>()
//    private var connectedHfpDevices = mutableSetOf<BluetoothDevice>()
//
//    // Android Auto detection
//    private var androidAutoDevices = mutableSetOf<BluetoothDevice>()
//
//    init {
//        initializeBluetoothComponents()
//        setupBluetoothScoReceiver()
//        setupProfileListeners()
//    }
//
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
//    private fun setupBluetoothScoReceiver() {
//        bluetoothScoReceiver = object : BroadcastReceiver() {
//            override fun onReceive(context: Context?, intent: Intent?) {
//                when (intent?.action) {
//                    AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
//                        val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
//                        handleBluetoothScoStateChange(state)
//                    }
//                    BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
//                        handleA2dpConnectionChange(intent)
//                    }
//                    BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
//                        handleHfpConnectionChange(intent)
//                    }
//                }
//            }
//        }
//
//        val filter = IntentFilter().apply {
//            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
//            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
//            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
//        }
//        context.registerReceiver(bluetoothScoReceiver, filter)
//    }
//
//    private fun setupProfileListeners() {
//        // Setup A2DP profile listener
//        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
//            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
//                if (profile == BluetoothProfile.A2DP) {
//                    bluetoothA2dpProxy = proxy as BluetoothA2dp
//                    updateA2dpDevices()
//                }
//            }
//
//            override fun onServiceDisconnected(profile: Int) {
//                if (profile == BluetoothProfile.A2DP) {
//                    bluetoothA2dpProxy = null
//                }
//            }
//        }, BluetoothProfile.A2DP)
//
//        // Setup HFP profile listener
//        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
//            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
//                if (profile == BluetoothProfile.HEADSET) {
//                    bluetoothHeadsetProxy = proxy as BluetoothHeadset
//                    updateHfpDevices()
//                }
//            }
//
//            override fun onServiceDisconnected(profile: Int) {
//                if (profile == BluetoothProfile.HEADSET) {
//                    bluetoothHeadsetProxy = null
//                }
//            }
//        }, BluetoothProfile.HEADSET)
//    }
//
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    private fun updateA2dpDevices() {
//        bluetoothA2dpProxy?.let { proxy ->
//            connectedA2dpDevices.clear()
//            connectedA2dpDevices.addAll(proxy.connectedDevices)
//
//            // Detect Android Auto devices
//            detectAndroidAutoDevices()
//        }
//    }
//
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    private fun updateHfpDevices() {
//        bluetoothHeadsetProxy?.let { proxy ->
//            connectedHfpDevices.clear()
//            connectedHfpDevices.addAll(proxy.connectedDevices)
//        }
//    }
//
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    private fun detectAndroidAutoDevices() {
//        androidAutoDevices.clear()
//
//        connectedA2dpDevices.forEach { device ->
//            if (isAndroidAutoDevice(device)) {
//                androidAutoDevices.add(device)
//                log.d(TAG) { "Android Auto device detected: ${device.name}" }
//            }
//        }
//    }
//
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    private fun isAndroidAutoDevice(device: BluetoothDevice): Boolean {
//        val deviceName = device.name?.lowercase() ?: return false
//
//        // Enhanced Android Auto detection patterns
//        val androidAutoPatterns = listOf(
//            "android auto",
//            "aa mirror",
//            "carplay",
//            "car audio",
//            "auto connect",
//            "android audio"
//        )
//
//        // Check device name patterns
//        if (androidAutoPatterns.any { pattern -> deviceName.contains(pattern) }) {
//            return true
//        }
//
//        // Check device class for automotive
//        device.bluetoothClass?.let { bluetoothClass ->
//            val majorDeviceClass = bluetoothClass.majorDeviceClass
//            val deviceClass = bluetoothClass.deviceClass
//
//            if (majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO) {
//                return deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO
//            }
//        }
//
//        return false
//    }
//
//    private fun handleBluetoothScoStateChange(state: Int) {
//        when (state) {
//            AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
//                log.d(TAG) { "Bluetooth SCO connected" }
//                isBluetoothScoRequested = false
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
//    private fun handleA2dpConnectionChange(intent: Intent) {
//        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
//        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
//
//        device?.let {
//            when (state) {
//                BluetoothProfile.STATE_CONNECTED -> {
//                    connectedA2dpDevices.add(it)
//                    if (isAndroidAutoDevice(it)) {
//                        androidAutoDevices.add(it)
//                    }
//                }
//                BluetoothProfile.STATE_DISCONNECTED -> {
//                    connectedA2dpDevices.remove(it)
//                    androidAutoDevices.remove(it)
//                }
//            }
//        }
//    }
//
//    private fun handleHfpConnectionChange(intent: Intent) {
//        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
//        val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
//
//        device?.let {
//            when (state) {
//                BluetoothProfile.STATE_CONNECTED -> connectedHfpDevices.add(it)
//                BluetoothProfile.STATE_DISCONNECTED -> connectedHfpDevices.remove(it)
//            }
//        }
//    }
//
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    fun getConnectedBluetoothDevices(): List<AudioDevice> {
//        val devices = mutableListOf<AudioDevice>()
//
//        // A2DP devices (output)
//        connectedA2dpDevices.forEach { device ->
//            devices.add(createBluetoothAudioDevice(device, isOutput = true))
//        }
//
//        // HFP devices (input/output)
//        connectedHfpDevices.forEach { device ->
//            // Add output device
//            devices.add(createBluetoothAudioDevice(device, isOutput = true))
//            // Add input device
//            devices.add(createBluetoothAudioDevice(device, isOutput = false))
//        }
//
//        return devices
//    }
//
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    private fun createBluetoothAudioDevice(device: BluetoothDevice, isOutput: Boolean): AudioDevice {
//        val deviceName = device.name ?: "Bluetooth Device"
//        val isAndroidAuto = androidAutoDevices.contains(device)
//        val supportsA2DP = connectedA2dpDevices.contains(device)
//        val supportsHFP = connectedHfpDevices.contains(device)
//
//        val audioUnitType = when {
//            isAndroidAuto -> AudioUnitTypes.ANDROID_AUTO
//            supportsA2DP && supportsHFP -> AudioUnitTypes.BLUETOOTH
//            supportsA2DP -> AudioUnitTypes.BLUETOOTHA2DP
//            else -> AudioUnitTypes.BLUETOOTH
//        }
//
//        val capability = when {
//            !isOutput -> AudioUnitCompatibilities.RECORD
//            else -> AudioUnitCompatibilities.PLAY
//        }
//
//        return AudioDevice(
//            name = if (isOutput) deviceName else "$deviceName Microphone",
//            descriptor = if (isOutput) "bluetooth_${device.address}" else "bluetooth_mic_${device.address}",
//            nativeDevice = device,
//            isOutput = isOutput,
//            audioUnit = AudioUnit(
//                type = audioUnitType,
//                capability = capability,
//                isCurrent = false,
//                isDefault = false
//            ),
//            connectionState = DeviceConnectionState.CONNECTED,
//            signalStrength = estimateBluetoothSignalStrength(),
//            batteryLevel = getBluetoothBatteryLevel(device),
//            isWireless = true,
//            supportsHDVoice = supportsA2DP,
//            latency = if (isAndroidAuto) 100 else if (supportsA2DP) 200 else 150,
//            vendorInfo = extractVendorFromDeviceName(deviceName),
//            isAndroidAuto = isAndroidAuto
//        )
//    }
//
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    fun startBluetoothSco(audioManager: AudioManager): Boolean {
//        if (!audioManager.isBluetoothScoAvailableOffCall) {
//            log.w(TAG) { "Bluetooth SCO not available for off-call use" }
//            return false
//        }
//
//        if (audioManager.isBluetoothScoOn) {
//            log.d(TAG) { "Bluetooth SCO already connected" }
//            return true
//        }
//
//        if (!isBluetoothScoRequested) {
//            log.d(TAG) { "Starting Bluetooth SCO..." }
//            isBluetoothScoRequested = true
//            audioManager.startBluetoothSco()
//            return true
//        }
//
//        return false
//    }
//
//    fun stopBluetoothSco(audioManager: AudioManager) {
//        if (audioManager.isBluetoothScoOn) {
//            log.d(TAG) { "Stopping Bluetooth SCO" }
//            audioManager.stopBluetoothSco()
//        }
//        isBluetoothScoRequested = false
//    }
//
//    private fun estimateBluetoothSignalStrength(): Int {
//        return (70..95).random()
//    }
//
//    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
//    private fun getBluetoothBatteryLevel(device: BluetoothDevice): Int? {
//        return try {
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//                val getBatteryLevelMethod = BluetoothDevice::class.java.getMethod("getBatteryLevel")
//                val batteryLevel = getBatteryLevelMethod.invoke(device) as? Int
//                batteryLevel?.takeIf { it >= 0 && it <= 100 }
//            } else {
//                null
//            }
//        } catch (e: Exception) {
//            null
//        }
//    }
//
//    private fun extractVendorFromDeviceName(deviceName: String): String? {
//        val name = deviceName.lowercase()
//        return when {
//            name.contains("apple") || name.contains("airpods") -> "Apple"
//            name.contains("samsung") -> "Samsung"
//            name.contains("sony") -> "Sony"
//            name.contains("bose") -> "Bose"
//            name.contains("jabra") -> "Jabra"
//            name.contains("plantronics") -> "Plantronics"
//            name.contains("android auto") -> "Android Auto"
//            else -> null
//        }
//    }
//
//    fun dispose() {
//        bluetoothScoReceiver?.let { receiver ->
//            try {
//                context.unregisterReceiver(receiver)
//            } catch (e: Exception) {
//                log.w(TAG) { "Error unregistering Bluetooth receiver: ${e.message}" }
//            }
//        }
//
//        bluetoothAdapter?.closeProfileProxy(BluetoothProfile.A2DP, bluetoothA2dpProxy)
//        bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HEADSET, bluetoothHeadsetProxy)
//    }
//
//    fun isAndroidAutoConnected(): Boolean = androidAutoDevices.isNotEmpty()
//
//    fun getAndroidAutoDevices(): Set<BluetoothDevice> = androidAutoDevices.toSet()
//}
