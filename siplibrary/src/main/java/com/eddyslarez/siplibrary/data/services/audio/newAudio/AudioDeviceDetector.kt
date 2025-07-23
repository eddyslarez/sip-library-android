package com.eddyslarez.siplibrary.data.services.audio.newAudio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.eddyslarez.siplibrary.data.services.audio.AudioDevice
import com.eddyslarez.siplibrary.data.services.audio.AudioUnit
import com.eddyslarez.siplibrary.data.services.audio.AudioUnitCompatibilities
import com.eddyslarez.siplibrary.data.services.audio.AudioUnitTypes
import com.eddyslarez.siplibrary.data.services.audio.DeviceConnectionState
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.collections.forEach

/**
 * Detector especializado para dispositivos de audio
 * Maneja la detección y monitoreo de todos los tipos de dispositivos de audio
 */
class AudioDeviceDetector(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter = bluetoothManager?.adapter

    private var audioDeviceCallback: AudioDeviceCallback? = null

    // StateFlows para observar cambios en los dispositivos
    private val _availableInputDevices = MutableStateFlow<List<AudioDevice>>(emptyList())
    private val _availableOutputDevices = MutableStateFlow<List<AudioDevice>>(emptyList())

    val availableInputDevices: StateFlow<List<AudioDevice>> = _availableInputDevices.asStateFlow()
    val availableOutputDevices: StateFlow<List<AudioDevice>> = _availableOutputDevices.asStateFlow()

    private val deviceChangeListeners = mutableListOf<(List<AudioDevice>, List<AudioDevice>) -> Unit>()

    companion object {
        private const val TAG = "AudioDeviceDetector"
    }

    init {
        setupAudioDeviceMonitoring()
    }

    /**
     * Detecta todos los dispositivos de audio disponibles
     */
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
    fun detectAllDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        val inputDevices = mutableListOf<AudioDevice>()
        val outputDevices = mutableListOf<AudioDevice>()

        try {
            // Dispositivos built-in (siempre disponibles)
            addBuiltInDevices(inputDevices, outputDevices)

            // Dispositivos con cable
            if (audioManager.isWiredHeadsetOn) {
                addWiredDevices(inputDevices, outputDevices)
            }

            // Dispositivos Bluetooth
            addBluetoothDevices(inputDevices, outputDevices)

            // Dispositivos USB y otros (API 23+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                addUsbAndOtherDevices(inputDevices, outputDevices)
            }

        } catch (e: Exception) {
            log.e(TAG) { "Error detecting devices: ${e.message}" }
            return getFallbackDevices()
        }

        // Actualizar StateFlows
        _availableInputDevices.value = inputDevices
        _availableOutputDevices.value = outputDevices

        return Pair(inputDevices, outputDevices)
    }

    /**
     * Obtiene el dispositivo de entrada actual
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun getCurrentInputDevice(): AudioDevice? {
        val descriptor = getCurrentAudioInputDescriptor()
        return findDeviceByDescriptor(descriptor, false)
    }

    /**
     * Obtiene el dispositivo de salida actual
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun getCurrentOutputDevice(): AudioDevice? {
        val descriptor = getCurrentAudioOutputDescriptor()
        return findDeviceByDescriptor(descriptor, true)
    }

    /**
     * Añade dispositivos built-in
     */
    private fun addBuiltInDevices(inputDevices: MutableList<AudioDevice>, outputDevices: MutableList<AudioDevice>) {
        // Micrófono integrado
        inputDevices.add(createBuiltInMicDevice())

        // Auricular (si está disponible)
        if (hasEarpiece()) {
            outputDevices.add(createEarpieceDevice())
        }

        // Altavoz
        outputDevices.add(createSpeakerDevice())
    }

    /**
     * Añade dispositivos con cable
     */
    private fun addWiredDevices(inputDevices: MutableList<AudioDevice>, outputDevices: MutableList<AudioDevice>) {
        outputDevices.add(createWiredHeadsetDevice())
        inputDevices.add(createWiredHeadsetMicDevice())
    }

    /**
     * Añade dispositivos Bluetooth
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun addBluetoothDevices(inputDevices: MutableList<AudioDevice>, outputDevices: MutableList<AudioDevice>) {
        if (!audioManager.isBluetoothScoAvailableOffCall) return

        try {
            val connectedDevices = getConnectedBluetoothAudioDevices()
            connectedDevices.forEach { device ->
                val deviceName = getBluetoothDeviceName(device)
                val address = device.address

                // Dispositivo de salida Bluetooth
                outputDevices.add(createBluetoothOutputDevice(device, deviceName, address))

                // Dispositivo de entrada Bluetooth (si soporta HFP)
                if (supportsHandsFreeProfile(device)) {
                    inputDevices.add(createBluetoothInputDevice(device, deviceName, address))
                }
            }
        } catch (e: SecurityException) {
            log.w(TAG) { "Bluetooth permission not granted: ${e.message}" }
        }
    }

    /**
     * Añade dispositivos USB y otros
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun addUsbAndOtherDevices(inputDevices: MutableList<AudioDevice>, outputDevices: MutableList<AudioDevice>) {
        try {
            val audioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS)

            audioDevices.forEach { deviceInfo ->
                when (deviceInfo.type) {
                    AudioDeviceInfo.TYPE_USB_ACCESSORY,
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> {
                        addUsbDevice(deviceInfo, inputDevices, outputDevices)
                    }
                    AudioDeviceInfo.TYPE_HEARING_AID -> {
                        addHearingAidDevice(deviceInfo, outputDevices)
                    }
                }
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error adding USB/other devices: ${e.message}" }
        }
    }

    /**
     * Crea dispositivos de audio específicos
     */
    private fun createBuiltInMicDevice(): AudioDevice {
        return AudioDevice(
            name = "Built-in Microphone",
            descriptor = "builtin_mic",
            nativeDevice = null,
            isOutput = false,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.MICROPHONE,
                capability = AudioUnitCompatibilities.RECORD,
                isCurrent = getCurrentAudioInputDescriptor() == "builtin_mic",
                isDefault = true
            ),
            connectionState = DeviceConnectionState.AVAILABLE,
            isWireless = false,
            supportsHDVoice = true,
            latency = 10,
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
                isCurrent = getCurrentAudioOutputDescriptor() == "earpiece",
                isDefault = true
            ),
            connectionState = DeviceConnectionState.AVAILABLE,
            isWireless = false,
            supportsHDVoice = true,
            latency = 5,
            vendorInfo = "Built-in"
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
                isCurrent = getCurrentAudioOutputDescriptor() == "speaker",
                isDefault = !hasEarpiece()
            ),
            connectionState = DeviceConnectionState.AVAILABLE,
            isWireless = false,
            supportsHDVoice = true,
            latency = 15,
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
                isCurrent = getCurrentAudioOutputDescriptor() == "wired_headset",
                isDefault = false
            ),
            connectionState = DeviceConnectionState.CONNECTED,
            isWireless = false,
            supportsHDVoice = true,
            latency = 20,
            vendorInfo = null
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
                isCurrent = getCurrentAudioInputDescriptor() == "wired_headset_mic",
                isDefault = false
            ),
            connectionState = DeviceConnectionState.CONNECTED,
            isWireless = false,
            supportsHDVoice = true,
            latency = 20,
            vendorInfo = null
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun createBluetoothOutputDevice(device: BluetoothDevice, name: String, address: String): AudioDevice {
        return AudioDevice(
            name = name,
            descriptor = "bluetooth_$address",
            nativeDevice = device,
            isOutput = true,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.BLUETOOTH,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = getCurrentAudioOutputDescriptor()?.startsWith("bluetooth_") == true,
                isDefault = false
            ),
            connectionState = DeviceConnectionState.CONNECTED,
            signalStrength = estimateBluetoothSignalStrength(),
            batteryLevel = getBluetoothBatteryLevel(device),
            isWireless = true,
            supportsHDVoice = supportsA2DP(device),
            latency = 150,
            vendorInfo = extractVendorFromBluetoothDevice(device)
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun createBluetoothInputDevice(device: BluetoothDevice, name: String, address: String): AudioDevice {
        return AudioDevice(
            name = "$name Microphone",
            descriptor = "bluetooth_mic_$address",
            nativeDevice = device,
            isOutput = false,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.BLUETOOTH,
                capability = AudioUnitCompatibilities.RECORD,
                isCurrent = getCurrentAudioInputDescriptor()?.startsWith("bluetooth_mic_") == true,
                isDefault = false
            ),
            connectionState = DeviceConnectionState.CONNECTED,
            signalStrength = estimateBluetoothSignalStrength(),
            batteryLevel = getBluetoothBatteryLevel(device),
            isWireless = true,
            supportsHDVoice = false,
            latency = 150,
            vendorInfo = extractVendorFromBluetoothDevice(device)
        )
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun addUsbDevice(deviceInfo: AudioDeviceInfo, inputDevices: MutableList<AudioDevice>, outputDevices: MutableList<AudioDevice>) {
        val deviceName = deviceInfo.productName?.toString() ?: "USB Audio Device"
        val deviceId = deviceInfo.id.toString()

        if (deviceInfo.isSink) {
            outputDevices.add(createUsbOutputDevice(deviceInfo, deviceName, deviceId))
        }

        if (deviceInfo.isSource) {
            inputDevices.add(createUsbInputDevice(deviceInfo, deviceName, deviceId))
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun createUsbOutputDevice(deviceInfo: AudioDeviceInfo, name: String, id: String): AudioDevice {
        return AudioDevice(
            name = name,
            descriptor = "usb_out_$id",
            nativeDevice = deviceInfo,
            isOutput = true,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.GENERICUSB,
                capability = AudioUnitCompatibilities.PLAY,
                isCurrent = false,
                isDefault = false
            ),
            connectionState = DeviceConnectionState.CONNECTED,
            isWireless = false,
            supportsHDVoice = true,
            latency = 30,
            vendorInfo = extractVendorFromDeviceName(name)
        )
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun createUsbInputDevice(deviceInfo: AudioDeviceInfo, name: String, id: String): AudioDevice {
        return AudioDevice(
            name = "$name Microphone",
            descriptor = "usb_in_$id",
            nativeDevice = deviceInfo,
            isOutput = false,
            audioUnit = AudioUnit(
                type = AudioUnitTypes.GENERICUSB,
                capability = AudioUnitCompatibilities.RECORD,
                isCurrent = false,
                isDefault = false
            ),
            connectionState = DeviceConnectionState.CONNECTED,
            isWireless = false,
            supportsHDVoice = true,
            latency = 30,
            vendorInfo = extractVendorFromDeviceName(name)
        )
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun addHearingAidDevice(deviceInfo: AudioDeviceInfo, outputDevices: MutableList<AudioDevice>) {
        outputDevices.add(
            AudioDevice(
                name = "Hearing Aid",
                descriptor = "hearing_aid_${deviceInfo.id}",
                nativeDevice = deviceInfo,
                isOutput = true,
                audioUnit = AudioUnit(
                    type = AudioUnitTypes.HEARINGAID,
                    capability = AudioUnitCompatibilities.PLAY,
                    isCurrent = false,
                    isDefault = false
                ),
                connectionState = DeviceConnectionState.CONNECTED,
                isWireless = true,
                supportsHDVoice = true,
                latency = 50,
                vendorInfo = extractVendorFromDeviceName(deviceInfo.productName?.toString() ?: "")
            )
        )
    }

    /**
     * Métodos de configuración y monitoreo
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun setupAudioDeviceMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioDeviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    notifyDeviceChange()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    notifyDeviceChange()
                }
            }
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifyDeviceChange() {
        try {
            val (inputs, outputs) = detectAllDevices()
            deviceChangeListeners.forEach { listener ->
                try {
                    listener(inputs, outputs)
                } catch (e: Exception) {
                    log.e(TAG) { "Error in device change listener: ${e.message}" }
                }
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error notifying device change: ${e.message}" }
        }
    }

    /**
     * Métodos helper
     */
    private fun getCurrentAudioInputDescriptor(): String? {
        return when {
            audioManager.isBluetoothScoOn -> "bluetooth_mic_active"
            audioManager.isWiredHeadsetOn -> "wired_headset_mic"
            else -> "builtin_mic"
        }
    }

    private fun getCurrentAudioOutputDescriptor(): String? {
        return when {
            audioManager.isBluetoothScoOn -> "bluetooth_active"
            audioManager.isSpeakerphoneOn -> "speaker"
            audioManager.isWiredHeadsetOn -> "wired_headset"
            else -> if (hasEarpiece()) "earpiece" else "speaker"
        }
    }

    private fun hasEarpiece(): Boolean {
        return context.packageManager?.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ?: false
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun getConnectedBluetoothAudioDevices(): List<BluetoothDevice> {
        return try {
            bluetoothAdapter?.bondedDevices?.filter { device ->
                isBluetoothAudioDevice(device) && isBluetoothDeviceConnected(device)
            } ?: emptyList()
        } catch (e: SecurityException) {
            log.w(TAG) { "Bluetooth permission not granted" }
            emptyList()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun isBluetoothAudioDevice(device: BluetoothDevice): Boolean {
        return try {
            device.bluetoothClass?.hasService(BluetoothClass.Service.AUDIO) == true
        } catch (e: Exception) {
            false
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun isBluetoothDeviceConnected(device: BluetoothDevice): Boolean {
        return try {
            bluetoothManager?.getConnectionState(device, BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
        } catch (e: Exception) {
            device.bondState == BluetoothDevice.BOND_BONDED
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun getBluetoothDeviceName(device: BluetoothDevice): String {
        return try {
            device.name ?: "Bluetooth Device"
        } catch (e: SecurityException) {
            "Bluetooth Device"
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun supportsHandsFreeProfile(device: BluetoothDevice): Boolean {
        return try {
            bluetoothAdapter?.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothAdapter.STATE_CONNECTED
        } catch (e: Exception) {
            false
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun supportsA2DP(device: BluetoothDevice): Boolean {
        return try {
            bluetoothAdapter?.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothAdapter.STATE_CONNECTED
        } catch (e: Exception) {
            false
        }
    }

    private fun estimateBluetoothSignalStrength(): Int {
        return (70..95).random()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun getBluetoothBatteryLevel(device: BluetoothDevice): Int? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val getBatteryLevelMethod = BluetoothDevice::class.java.getMethod("getBatteryLevel")
                val batteryLevel = getBatteryLevelMethod.invoke(device) as? Int
                batteryLevel?.takeIf { it >= 0 && it <= 100 }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun extractVendorFromBluetoothDevice(device: BluetoothDevice): String? {
        return try {
            val deviceName = device.name?.lowercase() ?: return null
            extractVendorFromDeviceName(deviceName)
        } catch (e: SecurityException) {
            null
        }
    }

    private fun extractVendorFromDeviceName(deviceName: String): String? {
        val name = deviceName.lowercase()
        return when {
            name.contains("apple") || name.contains("airpods") -> "Apple"
            name.contains("samsung") -> "Samsung"
            name.contains("sony") -> "Sony"
            name.contains("bose") -> "Bose"
            name.contains("jabra") -> "Jabra"
            name.contains("google") -> "Google"
            else -> null
        }
    }

    private fun findDeviceByDescriptor(descriptor: String?, isOutput: Boolean): AudioDevice? {
        val devices = if (isOutput) _availableOutputDevices.value else _availableInputDevices.value
        return devices.find { it.descriptor == descriptor }
    }

    private fun getFallbackDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        val inputDevices = listOf(createBuiltInMicDevice())
        val outputDevices = mutableListOf<AudioDevice>()

        if (hasEarpiece()) {
            outputDevices.add(createEarpieceDevice())
        }
        outputDevices.add(createSpeakerDevice())

        return Pair(inputDevices, outputDevices)
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Añadir listener para cambios de dispositivos
     */
    fun addDeviceChangeListener(listener: (List<AudioDevice>, List<AudioDevice>) -> Unit) {
        deviceChangeListeners.add(listener)
    }

    /**
     * Remover listener
     */
    fun removeDeviceChangeListener(listener: (List<AudioDevice>, List<AudioDevice>) -> Unit) {
        deviceChangeListeners.remove(listener)
    }

    /**
     * Limpieza de recursos
     */
    fun dispose() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioDeviceCallback?.let { callback ->
                    audioManager.unregisterAudioDeviceCallback(callback)
                }
            }
            deviceChangeListeners.clear()
        } catch (e: Exception) {
            log.e(TAG) { "Error disposing AudioDeviceDetector: ${e.message}" }
        }
    }
}