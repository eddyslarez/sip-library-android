package com.eddyslarez.siplibrary.data.services.audiov2


import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.eddyslarez.siplibrary.data.services.audio.AudioDevice
import com.eddyslarez.siplibrary.data.services.audio.AudioUnit
import com.eddyslarez.siplibrary.data.services.audio.AudioUnitCompatibilities
import com.eddyslarez.siplibrary.data.services.audio.AudioUnitTypes
import com.eddyslarez.siplibrary.data.services.audio.DeviceConnectionState
import com.eddyslarez.siplibrary.utils.log
//
//class SystemAudioManager(private val context: Context) {
//    private val TAG = "SystemAudioManager"
//
//    private var audioManager: AudioManager? = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
//    private var audioDeviceCallback: AudioDeviceCallback? = null
//
//    // Enhanced audio state management
//    private var savedAudioMode = AudioManager.MODE_NORMAL
//    private var savedIsSpeakerPhoneOn = false
//    private var savedIsMicrophoneMute = false
//    private var audioFocusRequest: AudioFocusRequest? = null
//
//    // Device change listeners
//    private var deviceChangeListeners = mutableListOf<() -> Unit>()
//
//    init {
//        setupAudioDeviceMonitoring()
//    }
//
//    @RequiresApi(Build.VERSION_CODES.M)
//    private fun setupAudioDeviceMonitoring() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//            audioDeviceCallback = object : AudioDeviceCallback() {
//                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
//                    log.d(TAG) { "Audio devices added: ${addedDevices.size}" }
//                    notifyDeviceChange()
//                }
//
//                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
//                    log.d(TAG) { "Audio devices removed: ${removedDevices.size}" }
//                    notifyDeviceChange()
//                }
//            }
//            audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
//        }
//    }
//
//    private fun notifyDeviceChange() {
//        deviceChangeListeners.forEach { listener ->
//            try {
//                listener()
//            } catch (e: Exception) {
//                log.e(TAG) { "Error in device change listener: ${e.message}" }
//            }
//        }
//    }
//
//    fun initializeAudioForCall() {
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
//        }
//    }
//
//    private fun requestAudioFocus() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//            val audioAttributes = AudioAttributes.Builder()
//                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
//                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
//                .build()
//
//            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
//                .setAudioAttributes(audioAttributes)
//                .setAcceptsDelayedFocusGain(false)
//                .setWillPauseWhenDucked(true)
//                .build()
//
//            audioFocusRequest = focusRequest
//            val result = audioManager?.requestAudioFocus(focusRequest) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
//            log.d(TAG) { "Audio focus request result: $result" }
//
//        } else {
//            @Suppress("DEPRECATION")
//            val result = audioManager?.requestAudioFocus(
//                null,
//                AudioManager.STREAM_VOICE_CALL,
//                AudioManager.AUDIOFOCUS_GAIN
//            ) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
//
//            log.d(TAG) { "Legacy audio focus request result: $result" }
//        }
//    }
//
//    fun releaseAudioFocus() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
//    fun switchToSpeaker(): Boolean {
//        return try {
//            audioManager?.let { am ->
//                am.isSpeakerphoneOn = true
//                am.mode = AudioManager.MODE_IN_COMMUNICATION
//                log.d(TAG) { "Switched to speaker successfully" }
//                true
//            } ?: false
//        } catch (e: Exception) {
//            log.e(TAG) { "Error switching to speaker: ${e.message}" }
//            false
//        }
//    }
//
//    fun switchToEarpiece(): Boolean {
//        return try {
//            audioManager?.let { am ->
//                am.isSpeakerphoneOn = false
//                am.mode = AudioManager.MODE_IN_COMMUNICATION
//                log.d(TAG) { "Switched to earpiece successfully" }
//                true
//            } ?: false
//        } catch (e: Exception) {
//            log.e(TAG) { "Error switching to earpiece: ${e.message}" }
//            false
//        }
//    }
//
//    fun switchToWiredHeadset(): Boolean {
//        return try {
//            audioManager?.let { am ->
//                am.isSpeakerphoneOn = false
//                am.mode = AudioManager.MODE_IN_COMMUNICATION
//                log.d(TAG) { "Switched to wired headset successfully" }
//                true
//            } ?: false
//        } catch (e: Exception) {
//            log.e(TAG) { "Error switching to wired headset: ${e.message}" }
//            false
//        }
//    }
//
//    @RequiresApi(Build.VERSION_CODES.M)
//    fun getSystemAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
//        val inputDevices = mutableListOf<AudioDevice>()
//        val outputDevices = mutableListOf<AudioDevice>()
//
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//            val audioDevices = audioManager?.getDevices(
//                AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS
//            ) ?: return Pair(emptyList(), emptyList())
//
//            audioDevices.forEach { deviceInfo ->
//                val audioDevice = createAudioDeviceFromInfo(deviceInfo)
//                if (deviceInfo.isSource) {
//                    inputDevices.add(audioDevice.copy(isOutput = false))
//                }
//                if (deviceInfo.isSink) {
//                    outputDevices.add(audioDevice.copy(isOutput = true))
//                }
//            }
//        }
//
//        return Pair(inputDevices, outputDevices)
//    }
//
//    @RequiresApi(Build.VERSION_CODES.M)
//    private fun createAudioDeviceFromInfo(deviceInfo: AudioDeviceInfo): AudioDevice {
//        val deviceName = deviceInfo.productName?.toString() ?: getDeviceTypeName(deviceInfo.type)
//        val deviceType = mapToAudioUnitType(deviceInfo.type)
//
//        return AudioDevice(
//            name = deviceName,
//            descriptor = "system_${deviceInfo.id}",
//            nativeDevice = deviceInfo,
//            isOutput = deviceInfo.isSink,
//            audioUnit = AudioUnit(
//                type = deviceType,
//                capability = when {
//                    deviceInfo.isSource && deviceInfo.isSink -> AudioUnitCompatibilities.ALL
//                    deviceInfo.isSource -> AudioUnitCompatibilities.RECORD
//                    deviceInfo.isSink -> AudioUnitCompatibilities.PLAY
//                    else -> AudioUnitCompatibilities.UNKNOWN
//                },
//                isCurrent = false,
//                isDefault = false
//            ),
//            connectionState = DeviceConnectionState.AVAILABLE,
//            isWireless = isWirelessDevice(deviceInfo.type),
//            supportsHDVoice = supportsHighQuality(deviceInfo.type),
//            latency = estimateLatency(deviceInfo.type)
//        )
//    }
//
//    @RequiresApi(Build.VERSION_CODES.M)
//    private fun getDeviceTypeName(type: Int): String {
//        return when (type) {
//            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Built-in Earpiece"
//            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Microphone"
//            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in Speaker"
//            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
//            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
//            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Headset"
//            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
//            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
//            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio Device"
//            else -> "Unknown Audio Device"
//        }
//    }
//
//    @RequiresApi(Build.VERSION_CODES.M)
//    private fun mapToAudioUnitType(type: Int): AudioUnitTypes {
//        return when (type) {
//            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioUnitTypes.EARPIECE
//            AudioDeviceInfo.TYPE_BUILTIN_MIC -> AudioUnitTypes.MICROPHONE
//            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioUnitTypes.SPEAKER
//            AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioUnitTypes.HEADSET
//            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioUnitTypes.HEADPHONES
//            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioUnitTypes.BLUETOOTH
//            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> AudioUnitTypes.BLUETOOTHA2DP
//            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> AudioUnitTypes.GENERICUSB
//            else -> AudioUnitTypes.UNKNOWN
//        }
//    }
//
//    @RequiresApi(Build.VERSION_CODES.M)
//    private fun isWirelessDevice(type: Int): Boolean {
//        return when (type) {
//            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
//            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> true
//            else -> false
//        }
//    }
//
//    @RequiresApi(Build.VERSION_CODES.M)
//    private fun supportsHighQuality(type: Int): Boolean {
//        return when (type) {
//            AudioDeviceInfo.TYPE_WIRED_HEADSET,
//            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
//            AudioDeviceInfo.TYPE_USB_HEADSET,
//            AudioDeviceInfo.TYPE_USB_DEVICE,
//            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> true
//            else -> false
//        }
//    }
//
//    @RequiresApi(Build.VERSION_CODES.M)
//    private fun estimateLatency(type: Int): Int {
//        return when (type) {
//            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> 5
//            AudioDeviceInfo.TYPE_BUILTIN_MIC -> 10
//            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 15
//            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 20
//            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> 30
//            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 150
//            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 200
//            else -> 50
//        }
//    }
//
//    fun addDeviceChangeListener(listener: () -> Unit) {
//        deviceChangeListeners.add(listener)
//    }
//
//    fun removeDeviceChangeListener(listener: () -> Unit) {
//        deviceChangeListeners.remove(listener)
//    }
//
//    fun dispose() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
//            audioDeviceCallback?.let { callback ->
//                audioManager?.unregisterAudioDeviceCallback(callback)
//            }
//        }
//        releaseAudioFocus()
//        deviceChangeListeners.clear()
//    }
//
//    // Utility functions
//    fun hasEarpiece(): Boolean {
//        return context.packageManager?.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ?: false
//    }
//
//    fun getCurrentAudioMode(): Int = audioManager?.mode ?: AudioManager.MODE_NORMAL
//
//    fun isBluetoothScoOn(): Boolean = audioManager?.isBluetoothScoOn ?: false
//
//    fun isSpeakerphoneOn(): Boolean = audioManager?.isSpeakerphoneOn ?: false
//
//    fun isWiredHeadsetOn(): Boolean = audioManager?.isWiredHeadsetOn ?: false
//
//    fun isMicrophoneMute(): Boolean = audioManager?.isMicrophoneMute ?: false
//}
