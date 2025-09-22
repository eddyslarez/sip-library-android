package com.eddyslarez.siplibrary.data.services.audiov2

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresPermission
import com.eddyslarez.siplibrary.data.models.AccountInfo
import com.eddyslarez.siplibrary.data.services.audio.AudioDevice
import com.eddyslarez.siplibrary.data.services.audio.AudioDeviceManager
import com.eddyslarez.siplibrary.data.services.audio.AudioUnit
import com.eddyslarez.siplibrary.data.services.audio.AudioUnitCompatibilities
import com.eddyslarez.siplibrary.data.services.audio.AudioUnitTypes
import com.eddyslarez.siplibrary.data.services.audio.DeviceConnectionState
import com.eddyslarez.siplibrary.data.services.audio.WebRtcEventListener
import com.eddyslarez.siplibrary.data.services.audio.WebRtcManager
import com.eddyslarez.siplibrary.utils.log
import com.shepeliev.webrtckmp.AudioStreamTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.webrtc.PeerConnection
//
//class AndroidWebRtcManager(private val application: Application) : WebRtcManager {
//    private val TAG = "AndroidWebRtcManager"
//    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
//    private val context: Context = application.applicationContext
//
//    private var peerConnection: PeerConnection? = null
//    private var localAudioTrack: AudioStreamTrack? = null
//    private var remoteAudioTrack: AudioStreamTrack? = null
//    private var webRtcEventListener: WebRtcEventListener? = null
//    private var isInitialized = false
//    private var isLocalAudioReady = false
//
//    // Enhanced audio management components
//    private val systemAudioManager = SystemAudioManager(context)
//    private val bluetoothAudioManager = BluetoothAudioManager(context)
//    private val audioDeviceManager = AudioDeviceManager()
//
//    private var currentInputDevice: AudioDevice? = null
//    private var currentOutputDevice: AudioDevice? = null
//
//    init {
//        setupAudioManagerListeners()
//    }
//
//    private fun setupAudioManagerListeners() {
//        systemAudioManager.addDeviceChangeListener {
//            coroutineScope.launch {
//                updateAvailableDevices()
//            }
//        }
//    }
//
//    override fun initialize() {
//        log.d(TAG) { "Initializing WebRTC Manager..." }
//        if (!isInitialized) {
//            systemAudioManager.initializeAudioForCall()
//            initializePeerConnection()
//            updateAvailableDevices()
//            isInitialized = true
//        } else {
//            log.d(TAG) { "WebRTC already initialized" }
//        }
//    }
//
//    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
//    override fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
//        log.d(TAG) { "Getting all enhanced audio devices..." }
//
//        val inputDevices = mutableListOf<AudioDevice>()
//        val outputDevices = mutableListOf<AudioDevice>()
//
//        try {
//            // Add built-in devices
//            addBuiltInDevices(inputDevices, outputDevices)
//
//            // Add wired devices
//            addWiredDevices(inputDevices, outputDevices)
//
//            // Add Bluetooth devices (including Android Auto)
//            val bluetoothDevices = bluetoothAudioManager.getConnectedBluetoothDevices()
//            bluetoothDevices.forEach { device ->
//                if (device.isOutput) {
//                    outputDevices.add(device)
//                } else {
//                    inputDevices.add(device)
//                }
//            }
//
//            // Add system devices (API 23+)
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//                val (systemInputs, systemOutputs) = systemAudioManager.getSystemAudioDevices()
//                inputDevices.addAll(systemInputs)
//                outputDevices.addAll(systemOutputs)
//            }
//
//            log.d(TAG) { "Found ${inputDevices.size} input and ${outputDevices.size} output devices" }
//
//        } catch (e: Exception) {
//            log.e(TAG) { "Error getting audio devices: ${e.message}" }
//            return getFallbackDevices()
//        }
//
//        return Pair(inputDevices.distinctBy { it.descriptor }, outputDevices.distinctBy { it.descriptor })
//    }
//
//    private fun addBuiltInDevices(
//        inputDevices: MutableList<AudioDevice>,
//        outputDevices: MutableList<AudioDevice>
//    ) {
//        // Built-in microphone
//        inputDevices.add(createBuiltinMicDevice())
//
//        // Earpiece (if available)
//        if (systemAudioManager.hasEarpiece()) {
//            outputDevices.add(createEarpieceDevice())
//        }
//
//        // Built-in speaker
//        outputDevices.add(createSpeakerDevice())
//    }
//
//    private fun addWiredDevices(
//        inputDevices: MutableList<AudioDevice>,
//        outputDevices: MutableList<AudioDevice>
//    ) {
//        if (systemAudioManager.isWiredHeadsetOn()) {
//            outputDevices.add(createWiredHeadsetDevice())
//            inputDevices.add(createWiredHeadsetMicDevice())
//        }
//    }
//
//    override fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean {
//        log.d(TAG) { "Changing audio output to: ${device.name} (${device.descriptor})" }
//
//        if (!isInitialized || peerConnection == null) {
//            log.w(TAG) { "Cannot change audio output: WebRTC not initialized" }
//            return false
//        }
//
//        return try {
//            val success = when {
//                device.isAndroidAuto -> {
//                    switchToAndroidAuto(device)
//                }
//                device.descriptor.startsWith("bluetooth_") -> {
//                    switchToBluetoothOutput(device)
//                }
//                device.descriptor == "speaker" -> {
//                    systemAudioManager.switchToSpeaker()
//                }
//                device.descriptor == "wired_headset" -> {
//                    systemAudioManager.switchToWiredHeadset()
//                }
//                device.descriptor == "earpiece" -> {
//                    systemAudioManager.switchToEarpiece()
//                }
//                else -> {
//                    log.w(TAG) { "Unknown audio device type: ${device.descriptor}" }
//                    false
//                }
//            }
//
//            if (success) {
//                currentOutputDevice = device
//                webRtcEventListener?.onAudioDeviceChanged(device)
//                log.d(TAG) { "Successfully changed audio output to: ${device.name}" }
//            }
//
//            success
//        } catch (e: Exception) {
//            log.e(TAG) { "Error changing audio output: ${e.message}" }
//            false
//        }
//    }
//
//    private fun switchToAndroidAuto(device: AudioDevice): Boolean {
//        return try {
//            log.d(TAG) { "Switching to Android Auto: ${device.name}" }
//
//            // Android Auto typically uses A2DP for high-quality audio
//            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
//            audioManager?.let { am ->
//                // Ensure we're not using other outputs
//                am.isSpeakerphoneOn = false
//
//                // Start Bluetooth SCO for Android Auto
//                bluetoothAudioManager.startBluetoothSco(am)
//            } ?: false
//        } catch (e: Exception) {
//            log.e(TAG) { "Error switching to Android Auto: ${e.message}" }
//            false
//        }
//    }
//
//    private fun switchToBluetoothOutput(device: AudioDevice): Boolean {
//        return try {
//            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
//            audioManager?.let { am ->
//                // Stop speaker mode
//                am.isSpeakerphoneOn = false
//                am.mode = AudioManager.MODE_IN_COMMUNICATION
//
//                // Start Bluetooth SCO
//                bluetoothAudioManager.startBluetoothSco(am)
//            } ?: false
//        } catch (e: Exception) {
//            log.e(TAG) { "Error switching to Bluetooth output: ${e.message}" }
//            false
//        }
//    }
//
//    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
//    private fun updateAvailableDevices() {
//        val (inputs, outputs) = getAllAudioDevices()
//        audioDeviceManager.updateDevices(inputs, outputs)
//
//        // Set preferences for Android Auto and high-quality devices
//        val preferredTypes = setOf(
//            AudioUnitTypes.ANDROID_AUTO,
//            AudioUnitTypes.HEADSET,
//            AudioUnitTypes.BLUETOOTHA2DP,
//            AudioUnitTypes.BLUETOOTH
//        )
//        audioDeviceManager.setPreferredDeviceTypes(preferredTypes)
//    }
//
//    override fun changeAudioInputDeviceDuringCall(device: AudioDevice): Boolean {
//        log.d(TAG) { "Changing audio input to: ${device.name} (${device.descriptor})" }
//
//        if (!isInitialized || peerConnection == null) {
//            log.w(TAG) { "Cannot change audio input: WebRTC not initialized" }
//            return false
//        }
//
//        return try {
//            val success = when {
//                device.descriptor.startsWith("bluetooth_mic_") -> {
//                    switchToBluetoothInput(device)
//                }
//                device.descriptor == "wired_headset_mic" -> {
//                    switchToWiredHeadsetMic()
//                }
//                device.descriptor == "builtin_mic" -> {
//                    switchToBuiltinMic()
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
//    private fun switchToBluetoothInput(device: AudioDevice): Boolean {
//        return try {
//            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
//            audioManager?.let { am ->
//                am.mode = AudioManager.MODE_IN_COMMUNICATION
//                bluetoothAudioManager.startBluetoothSco(am)
//            } ?: false
//        } catch (e: Exception) {
//            log.e(TAG) { "Error switching to Bluetooth input: ${e.message}" }
//            false
//        }
//    }
//
//    private fun switchToWiredHeadsetMic(): Boolean {
//        return try {
//            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
//            audioManager?.let { am ->
//                bluetoothAudioManager.stopBluetoothSco(am)
//                true
//            } ?: false
//        } catch (e: Exception) {
//            log.e(TAG) { "Error switching to wired headset mic: ${e.message}" }
//            false
//        }
//    }
//
//    private fun switchToBuiltinMic(): Boolean {
//        return try {
//            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
//            audioManager?.let { am ->
//                bluetoothAudioManager.stopBluetoothSco(am)
//                true
//            } ?: false
//        } catch (e: Exception) {
//            log.e(TAG) { "Error switching to builtin mic: ${e.message}" }
//            false
//        }
//    }
//
//    override fun getCurrentInputDevice(): AudioDevice? {
//        return currentInputDevice ?: audioDeviceManager.selectedInputDevice.value
//    }
//
//    override fun getCurrentOutputDevice(): AudioDevice? {
//        return currentOutputDevice ?: audioDeviceManager.selectedOutputDevice.value
//    }
//
//    // Device creation helpers
//    private fun createBuiltinMicDevice(): AudioDevice {
//        return AudioDevice(
//            name = "Built-in Microphone",
//            descriptor = "builtin_mic",
//            nativeDevice = null,
//            isOutput = false,
//            audioUnit = AudioUnit(
//                type = AudioUnitTypes.MICROPHONE,
//                capability = AudioUnitCompatibilities.RECORD,
//                isCurrent = currentInputDevice?.descriptor == "builtin_mic",
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
//    private fun createEarpieceDevice(): AudioDevice {
//        return AudioDevice(
//            name = "Earpiece",
//            descriptor = "earpiece",
//            nativeDevice = null,
//            isOutput = true,
//            audioUnit = AudioUnit(
//                type = AudioUnitTypes.EARPIECE,
//                capability = AudioUnitCompatibilities.PLAY,
//                isCurrent = currentOutputDevice?.descriptor == "earpiece",
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
//    private fun createSpeakerDevice(): AudioDevice {
//        return AudioDevice(
//            name = "Speaker",
//            descriptor = "speaker",
//            nativeDevice = null,
//            isOutput = true,
//            audioUnit = AudioUnit(
//                type = AudioUnitTypes.SPEAKER,
//                capability = AudioUnitCompatibilities.PLAY,
//                isCurrent = currentOutputDevice?.descriptor == "speaker",
//                isDefault = !systemAudioManager.hasEarpiece()
//            ),
//            connectionState = DeviceConnectionState.AVAILABLE,
//            isWireless = false,
//            supportsHDVoice = true,
//            latency = 15,
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
//                isCurrent = currentOutputDevice?.descriptor == "wired_headset",
//                isDefault = false
//            ),
//            connectionState = DeviceConnectionState.CONNECTED,
//            isWireless = false,
//            supportsHDVoice = true,
//            latency = 20,
//            vendorInfo = null
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
//                isCurrent = currentInputDevice?.descriptor == "wired_headset_mic",
//                isDefault = false
//            ),
//            connectionState = DeviceConnectionState.CONNECTED,
//            isWireless = false,
//            supportsHDVoice = true,
//            latency = 20,
//            vendorInfo = null
//        )
//    }
//
//    private fun getFallbackDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
//        val inputDevices = listOf(createBuiltinMicDevice())
//        val outputDevices = mutableListOf<AudioDevice>()
//
//        if (systemAudioManager.hasEarpiece()) {
//            outputDevices.add(createEarpieceDevice())
//        }
//        outputDevices.add(createSpeakerDevice())
//
//        return Pair(inputDevices, outputDevices)
//    }
//
//    override fun dispose() {
//        log.d(TAG) { "Disposing Enhanced WebRtcManager resources..." }
//
//        try {
//            // Dispose audio managers
//            bluetoothAudioManager.dispose()
//            systemAudioManager.dispose()
//            audioDeviceManager.reset()
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
//    // Implement remaining WebRtcManager methods...
//    // (The rest of the methods like createOffer, createAnswer, etc. remain the same from original code)
//
//    override suspend fun createOffer(): String {
//        // Implementation remains the same...
//        // (Copy from original code)
//    }
//
//    override suspend fun createAnswer(accountInfo: AccountInfo, offerSdp: String): String {
//        // Implementation remains the same...
//        // (Copy from original code)
//    }
//
//    // ... (continue with other methods)
//
//    override fun diagnoseAudioIssues(): String {
//        return buildString {
//            appendLine("=== ENHANCED AUDIO DIAGNOSIS ===")
//            appendLine("WebRTC Initialized: $isInitialized")
//            appendLine("Local Audio Ready: $isLocalAudioReady")
//            appendLine("Android Auto Connected: ${bluetoothAudioManager.isAndroidAutoConnected()}")
//
//            append(systemAudioManager.toString())
//            append(audioDeviceManager.getDiagnosticInfo())
//        }
//    }
//
//    private fun initializePeerConnection() {
//        // Implementation remains the same...
//        // (Copy from original code)
//    }
//
//    private fun cleanupCall() {
//        // Implementation remains the same...
//        // (Copy from original code)
//    }
//
//    // ... (implement all other required methods)
//}
