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
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.eddyslarez.siplibrary.data.models.AccountInfo
import com.eddyslarez.siplibrary.data.models.CallState
import com.eddyslarez.siplibrary.data.services.asistente.RealtimeSession
import com.eddyslarez.siplibrary.data.services.ia.AudioCapture
import com.eddyslarez.siplibrary.data.services.ia.AudioProcessor
import com.eddyslarez.siplibrary.data.services.ia.MCNAssistantClient
import com.eddyslarez.siplibrary.data.services.ia.MCNSampleData
import com.eddyslarez.siplibrary.data.services.ia.MCNTranslatorClient
import com.eddyslarez.siplibrary.data.services.ia.MCNTranslatorClient3
import com.eddyslarez.siplibrary.data.services.ia.MCNTranslatorClient4
import com.eddyslarez.siplibrary.data.services.ia.MCNTranslatorClient5
import com.eddyslarez.siplibrary.data.services.ia.MCNTranslatorClient6
import com.eddyslarez.siplibrary.data.services.ia.OpenAIRealtimeClient
import com.eddyslarez.siplibrary.utils.CallStateManager
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Stream WebRTC imports
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import kotlin.coroutines.resumeWithException


class AndroidWebRtcManager(
    private val application: Application,
    private val openAiApiKey: String? = ""
) : WebRtcManager {
    private val TAG = "AndroidWebRtcManager"
    private val TAG1 = "AndroidWebTraduccion"
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
    private val realtimeSession= RealtimeSession(openAiApiKey ?: "")

    companion object {
        private const val SAMPLE_RATE = 24000
        private const val CHANNEL_CONFIG = android.media.AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_MS = 100
    }

    // OpenAI integration
    private val openAiClient = openAiApiKey?.let { MCNTranslatorClient6(it) }

    //    private val openAiClient = openAiApiKey?.let { MCNAssistantClient(it) }
//    private val openAiClient = openAiApiKey?.let { MCNTranslatorClient5(it, application) }
    private var isOpenAiEnabled = false

    // Audio playback para OpenAI responses
    private var audioTrack: android.media.AudioTrack? = null
    private var isPlaybackActive = false


    private var audioBuffer = mutableListOf<ByteArray>()
    private val bufferLock = Mutex()

    // Audio playback for OpenAI responses
    private var playbackThread: Thread? = null

    // Stream WebRTC components
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null
    private var localMediaStream: MediaStream? = null

    private var webRtcEventListener: WebRtcEventListener? = null
    private var isInitialized = false
    private var isLocalAudioReady = false
    private var context: Context = application.applicationContext

    // Enhanced audio management fields
    private var audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothManager: BluetoothManager? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var currentInputDevice: AudioDevice? = null
    private var currentOutputDevice: AudioDevice? = null

    // FIXED: Add Bluetooth SCO state tracking
    private var isBluetoothScoRequested = false
    private var bluetoothScoReceiver: BroadcastReceiver? = null

    // Audio state management
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var savedIsSpeakerPhoneOn = false
    private var savedIsMicrophoneMute = false
    private var audioFocusRequest: AudioFocusRequest? = null

    // Device monitoring
    private var deviceChangeListeners = mutableListOf<(List<AudioDevice>) -> Unit>()

    init {
        initializeWebRTC()
        initializeBluetoothComponents()
        setupAudioDeviceMonitoring()
        setupBluetoothScoReceiver()
        setupOpenAIClient()
        setupAudioPlayback()
//        openAiClient?.setTranslationMode(true)
    }

    /**
     * Initialize Stream WebRTC components
     */
    private fun initializeWebRTC() {
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(application)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )

            val options = PeerConnectionFactory.Options()
            val eglBase = EglBase.create()
            val eglBaseContext = eglBase.eglBaseContext

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setAudioDeviceModule(
                    JavaAudioDeviceModule.builder(application).createAudioDeviceModule()
                )
                .setVideoEncoderFactory(
                    DefaultVideoEncoderFactory(eglBaseContext, true, true)
                )
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBaseContext))
                .createPeerConnectionFactory()

            log.d(TAG) { "WebRTC inicializado correctamente" }
        } catch (e: Exception) {
            log.e(TAG) { "Error inicializando WebRTC: ${e.message}" }
        }
    }


    private fun setupOpenAIClient() {
        CoroutineScope(Dispatchers.IO).launch {
            realtimeSession?.initialize()
        }
//
//        openAiClient?.setTranslationReceivedListener { translation ->
//            log.e(TAG1) { "${translation.sourceLanguage} → ${translation.targetLanguage}" }
//            log.e(TAG1) { "Original: ${translation.originalText}" }
//            log.e(TAG1) { "Traducción: ${translation.translatedText}" }
//
//        }
//
//        openAiClient?.setLanguageDetectedListener { detected ->
//            log.e(TAG1) { "Idioma detectado: ${detected.language} (${detected.confidence})" }
//
//        }
//
//        openAiClient?.setConnectionStateListener { connected ->
//            log.d(TAG) { "OpenAI connection: $connected" }
//            if (connected) {
//
//                startAudioResponsePlayback()
//            }
//        }
//
//        openAiClient?.setErrorListener { error ->
//            log.e(TAG) { "OpenAI error: $error" }
//        }
//
//        openAiClient?.setAudioReceivedListener { audioData ->
//            playAudioData(audioData)
//        }
    }

    private fun setupAudioPlayback() {
        try {
            val bufferSize = android.media.AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            ) * 2

            audioTrack = android.media.AudioTrack(
                android.media.AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize,
                android.media.AudioTrack.MODE_STREAM
            )
        } catch (e: Exception) {
            log.e(TAG) { "Error configurando playback: ${e.message}" }
        }
    }


    /**
     * Enable/disable OpenAI audio processing
     */
    @RequiresApi(Build.VERSION_CODES.O)
    override fun setOpenAIEnabled(enabled: Boolean) {
        coroutineScope.launch {
            isOpenAiEnabled = enabled

            if (enabled && !openAiClient?.isConnected()!!) {
                val connected = openAiClient.connect()
//                MCNSampleData.loadTestDataToAssistant(openAiClient, "empresarial")
                if (!connected) {
                    log.e(TAG) { "Failed to connect to OpenAI" }
                    isOpenAiEnabled = false
                }
            } else if (!enabled) {
                openAiClient?.disconnect()
                stopAudioPlayback()
            }
        }
    }

    private fun startAudioResponsePlayback() {
        if (isPlaybackActive) return

        isPlaybackActive = true
        coroutineScope.launch {
            try {
                audioTrack?.play()
                val audioChannel = openAiClient?.getAudioResponseChannel()

                while (isPlaybackActive && audioChannel != null) {
                    try {
                        val audioData = audioChannel.tryReceive()
                        if (audioData.isSuccess) {
                            playAudioData(audioData.getOrThrow())
                        } else {
                            delay(10)
                        }
                    } catch (e: Exception) {
                        log.e(TAG) { "Error en playback loop: ${e.message}" }
                        break
                    }
                }
            } catch (e: Exception) {
                log.e(TAG) { "Error iniciando playback: ${e.message}" }
            }
        }
    }

    fun playAudioData(data: ByteArray) {

        if (data.isEmpty() || data.size % 2 != 0) {
            Log.e("Audio", "Datos de audio inválidos, descartando")
            return
        }

        audioTrack!!.write(data, 0, data.size)
    }

    private fun stopAudioPlayback() {
        isPlaybackActive = false
        try {
            audioTrack?.stop()
        } catch (e: Exception) {
            log.e(TAG) { "Error parando playback: ${e.message}" }
        }
    }

    /**
     * FIXED: Setup Bluetooth SCO state monitoring
     */
    private fun setupBluetoothScoReceiver() {
        bluetoothScoReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                        val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                        handleBluetoothScoStateChange(state)
                    }
                }
            }
        }

        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        context.registerReceiver(bluetoothScoReceiver, filter)
        log.d(TAG) { "Bluetooth SCO receiver registered" }
    }

    private fun handleBluetoothScoStateChange(state: Int) {
        when (state) {
            AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                log.d(TAG) { "Bluetooth SCO connected" }
                isBluetoothScoRequested = false
                webRtcEventListener?.onAudioDeviceChanged(currentOutputDevice)
            }

            AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                log.d(TAG) { "Bluetooth SCO disconnected" }
                isBluetoothScoRequested = false
            }

            AudioManager.SCO_AUDIO_STATE_CONNECTING -> {
                log.d(TAG) { "Bluetooth SCO connecting..." }
            }

            AudioManager.SCO_AUDIO_STATE_ERROR -> {
                log.e(TAG) { "Bluetooth SCO error" }
                isBluetoothScoRequested = false
            }
        }
    }

    private fun initializeBluetoothComponents() {
        try {
            bluetoothManager =
                context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothAdapter = bluetoothManager?.adapter
            log.d(TAG) { "Bluetooth components initialized" }
        } catch (e: Exception) {
            log.e(TAG) { "Error initializing Bluetooth: ${e.message}" }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun setupAudioDeviceMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioDeviceCallback = object : AudioDeviceCallback() {
                @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    log.d(TAG) { "Audio devices added: ${addedDevices.size}" }
                    notifyDeviceChange()
                }

                @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    log.d(TAG) { "Audio devices removed: ${removedDevices.size}" }
                    notifyDeviceChange()
                }
            }
            audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
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
    @RequiresApi(Build.VERSION_CODES.O)
    override fun initialize() {
        log.d(TAG) { "Initializing WebRTC Manager..." }
        if (!isInitialized) {
            initializeWebRTC()
            initializePeerConnection()
            isInitialized = true
        } else {
            log.d(TAG) { "WebRTC already initialized" }
        }
    }

    private fun initializeAudio() {
        audioManager?.let { am ->
            savedAudioMode = am.mode
            savedIsSpeakerPhoneOn = am.isSpeakerphoneOn
            savedIsMicrophoneMute = am.isMicrophoneMute

            log.d(TAG) { "Saved audio state - Mode: $savedAudioMode, Speaker: $savedIsSpeakerPhoneOn, Mic muted: $savedIsMicrophoneMute" }

            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = true
            am.isMicrophoneMute = false

            requestAudioFocus()

            log.d(TAG) { "Audio configured for WebRTC communication" }
        } ?: run {
            log.d(TAG) { "AudioManager not available!" }
        }
    }

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
            val result = audioManager?.requestAudioFocus(focusRequest)
                ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
            log.d(TAG) { "Audio focus request result: $result" }

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

            log.d(TAG) { "Legacy audio focus request result: $result" }
        }
    }

    private fun releaseAudioFocus() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            audioFocusRequest?.let { request ->
                audioManager?.abandonAudioFocusRequest(request)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(null)
        }

        audioManager?.let { am ->
            am.mode = savedAudioMode
            am.isSpeakerphoneOn = savedIsSpeakerPhoneOn
            am.isMicrophoneMute = savedIsMicrophoneMute

            log.d(TAG) { "Restored audio state" }
        }
    }

    /**
     * Enhanced device detection with comprehensive AudioDevice mapping
     */
    @RequiresPermission(
        allOf = [
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.RECORD_AUDIO
        ]
    )
    override fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        log.d(TAG) { "Getting all enhanced audio devices..." }

        val inputDevices = mutableListOf<AudioDevice>()
        val outputDevices = mutableListOf<AudioDevice>()

        try {
            val am = audioManager ?: return Pair(emptyList(), emptyList())

            val currentInputDescriptor = getCurrentAudioInputDescriptor()
            val currentOutputDescriptor = getCurrentAudioOutputDescriptor()
            val defaultInputDescriptor = getDefaultInputDescriptor()
            val defaultOutputDescriptor = getDefaultOutputDescriptor()

            addBuiltInDevices(
                inputDevices, outputDevices,
                currentInputDescriptor, currentOutputDescriptor,
                defaultInputDescriptor, defaultOutputDescriptor
            )

            addWiredDevices(
                inputDevices, outputDevices,
                currentInputDescriptor, currentOutputDescriptor
            )

//            addBluetoothDevices(
//                inputDevices, outputDevices,
//                currentInputDescriptor, currentOutputDescriptor
//            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                addUsbAndOtherDevices(
                    inputDevices, outputDevices,
                    currentInputDescriptor, currentOutputDescriptor
                )
            }

            log.d(TAG) { "Found ${inputDevices.size} input and ${outputDevices.size} output devices" }

        } catch (e: Exception) {
            log.e(TAG) { "Error getting audio devices: ${e.message}" }
            return getFallbackDevices()
        }

        return Pair(inputDevices, outputDevices)
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
        if (audioManager?.isWiredHeadsetOn == true) {
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
                    isCurrent = !audioManager?.isWiredHeadsetOn()!!,
                    isDefault = !audioManager?.isWiredHeadsetOn()!!
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
    // [Los métodos de detección de dispositivos se mantienen igual que en el código original]
    // addBuiltInDevices, addWiredDevices, addBluetoothDevices, etc.

    /**
     * FIXED: Enhanced device change during call with improved error handling
     */
    override fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean {
        log.d(TAG) { "Changing audio output to: ${device.name} (${device.descriptor})" }

        if (!isInitialized || peerConnection == null) {
            log.w(TAG) { "Cannot change audio output: WebRTC not initialized" }
            return false
        }

        return try {
            val am = audioManager ?: return false

            if (!device.descriptor.startsWith("bluetooth_") && am.isBluetoothScoOn) {
                log.d(TAG) { "Stopping Bluetooth SCO for non-Bluetooth device" }
                am.stopBluetoothSco()
                isBluetoothScoRequested = false
            }

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
                log.d(TAG) { "Successfully changed audio output to: ${device.name}" }

                if (device.descriptor.startsWith("bluetooth_")) {
                    coroutineScope.launch {
                        var attempts = 0
                        while (attempts < 10 && !am.isBluetoothScoOn) {
                            delay(200)
                            attempts++
                        }
                        if (am.isBluetoothScoOn) {
                            webRtcEventListener?.onAudioDeviceChanged(device)
                        }
                    }
                } else {
                    webRtcEventListener?.onAudioDeviceChanged(device)
                }
            }

            success
        } catch (e: Exception) {
            log.e(TAG) { "Error changing audio output: ${e.message}" }
            false
        }
    }

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

    // [Los métodos de switching de audio se mantienen iguales]
    // switchToBluetoothOutput, switchToSpeaker, etc.

    /**
     * FIXED: Enhanced cleanup with Bluetooth SCO handling
     */
    @RequiresApi(Build.VERSION_CODES.O)
    override fun dispose() {
        log.d(TAG) { "Disposing Enhanced WebRtcManager resources..." }

        try {
            deviceChangeListeners.clear()

            bluetoothScoReceiver?.let { receiver ->
                try {
                    context.unregisterReceiver(receiver)
                } catch (e: Exception) {
                    log.w(TAG) { "Error unregistering Bluetooth SCO receiver: ${e.message}" }
                }
            }

            audioManager?.let { am ->
                if (am.isBluetoothScoOn) {
                    am.stopBluetoothSco()
                }
            }
            isBluetoothScoRequested = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioDeviceCallback?.let { callback ->
                    audioManager?.unregisterAudioDeviceCallback(callback)
                }
            }

            coroutineScope.launch {
                setOpenAIEnabled(false)
                openAiClient?.disconnect()
            }

            releaseAudioFocus()
            cleanupCall()

            // Dispose Stream WebRTC factory
            peerConnectionFactory?.dispose()
            peerConnectionFactory = null

            isInitialized = false
            isLocalAudioReady = false
            currentInputDevice = null
            currentOutputDevice = null

        } catch (e: Exception) {
            log.e(TAG) { "Error during disposal: ${e.message}" }
        }
    }

    /**
     * Create an SDP offer for starting a call - Stream WebRTC version
     */
    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun createOffer(): String {
        log.d(TAG) { "Creating SDP offer..." }

        if (!isInitialized) {
            log.d(TAG) { "WebRTC not initialized, initializing now" }
            initialize()
        } else {
            initializeAudio()
        }

        val peerConn = peerConnection ?: run {
            log.d(TAG) { "PeerConnection not initialized, reinitializing" }
            initializePeerConnection()
            peerConnection ?: throw IllegalStateException("PeerConnection initialization failed")
        }

        if (!isLocalAudioReady) {
            log.d(TAG) { "Ensuring local audio track is ready..." }
            realtimeSession?.let {
                isLocalAudioReady = setupDirectRealtimeConnection(it
                )
            }
        }

        return coroutineScope.async {
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("VoiceActivityDetection", "true"))
            }

            val sdpObserver = object : SdpObserver {
                override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                    sessionDescription?.let {
                        peerConn.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() {
                                log.d(TAG) { "Local description set successfully" }
                            }

                            override fun onCreateFailure(p0: String?) {}
                            override fun onSetFailure(p0: String?) {}
                        }, it)
                    }
                }

                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {
                    log.e(TAG) { "Create offer failed: $error" }
                }

                override fun onSetFailure(error: String?) {}
            }

            peerConn.createOffer(sdpObserver, constraints)
            audioManager?.isMicrophoneMute = false

            // Return SDP when ready (this is simplified - you'd need proper async handling)
            peerConn.localDescription?.description ?: ""
        }.await()
    }

    /**
     * Create an SDP answer - Stream WebRTC version
     */
    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun createAnswer(accountInfo: AccountInfo, offerSdp: String): String {
        log.d(TAG) { "Creating SDP answer..." }

        if (!isInitialized) {
            initialize()
        } else {
            initializeAudio()
        }

        val peerConn = peerConnection ?: run {
            initializePeerConnection()
            peerConnection ?: throw IllegalStateException("PeerConnection initialization failed")
        }

        if (!isLocalAudioReady) {
            isLocalAudioReady = ensureLocalAudioTrack()
        }

        return coroutineScope.async {
            try {
                // Set remote offer
                val remoteDescription = SessionDescription(SessionDescription.Type.OFFER, offerSdp)

                val setRemoteSuccess = suspendCancellableCoroutine<Boolean> { continuation ->
                    peerConn.setRemoteDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            log.d(TAG) { "Remote description set successfully" }
                            continuation.resume(true) {}
                        }

                        override fun onSetFailure(error: String?) {
                            log.e(TAG) { "Set remote description failed: $error" }
                            continuation.resume(false) {}
                        }

                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, remoteDescription)
                }

                if (!setRemoteSuccess) {
                    throw Exception("Failed to set remote description")
                }

                // Media constraints
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("VoiceActivityDetection", "true"))
                    optional.add(MediaConstraints.KeyValuePair("googUseRtpMUX", "true"))
                }

                // Create answer
                val answerSdp = suspendCancellableCoroutine<String> { continuation ->
                    peerConn.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                            sessionDescription?.let { answer ->
                                peerConn.setLocalDescription(object : SdpObserver {
                                    override fun onSetSuccess() {
                                        log.d(TAG) { "Local answer description set successfully" }
                                        continuation.resume(answer.description) {}
                                    }

                                    override fun onSetFailure(error: String?) {
                                        log.e(TAG) { "Set local description failed: $error" }
                                        continuation.resumeWithException(
                                            Exception("Failed to set local description: $error")
                                        )
                                    }

                                    override fun onCreateSuccess(p0: SessionDescription?) {}
                                    override fun onCreateFailure(p0: String?) {}
                                }, answer)
                            } ?: continuation.resumeWithException(Exception("Answer SDP is null"))
                        }

                        override fun onCreateFailure(error: String?) {
                            log.e(TAG) { "Create answer failed: $error" }
                            continuation.resumeWithException(Exception("Failed to create answer: $error"))
                        }

                        override fun onSetSuccess() {}
                        override fun onSetFailure(error: String?) {}
                    }, constraints)
                }

                setAudioEnabled(true)
                audioManager?.isMicrophoneMute = false

                return@async answerSdp

            } catch (e: Exception) {
                log.e(TAG) { "Error in createAnswer: ${e.message}" }
                throw e
            }
        }.await()
    }


    /**
     * Set remote description - Stream WebRTC version
     */
    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun setRemoteDescription(sdp: String, type: SdpType) {
        log.d(TAG) { "Setting remote description type: $type" }

        if (!isInitialized) {
            initialize()
        }

        val peerConn = peerConnection ?: run {
            initializePeerConnection()
            peerConnection ?: throw IllegalStateException("PeerConnection initialization failed")
        }

        if (type == SdpType.OFFER && !isLocalAudioReady) {
            isLocalAudioReady = ensureLocalAudioTrack()
        }

        val sdpType = when (type) {
            SdpType.OFFER -> SessionDescription.Type.OFFER
            SdpType.ANSWER -> SessionDescription.Type.ANSWER
        }

        val sessionDescription = SessionDescription(sdpType, sdp)

        peerConn.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                log.d(TAG) { "Remote description set successfully" }
            }

            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(error: String?) {
                log.e(TAG) { "Set remote description failed: $error" }
            }
        }, sessionDescription)

        if (type == SdpType.ANSWER) {
            setAudioEnabled(true)
            audioManager?.isMicrophoneMute = false
        }
    }

    /**
     * Add ICE candidate - Stream WebRTC version
     */
    @RequiresApi(Build.VERSION_CODES.O)
    override suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        log.d(TAG) { "Adding ICE candidate: $candidate" }

        if (!isInitialized) {
            initialize()
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
            sdpMid ?: "",
            sdpMLineIndex ?: 0,
            candidate
        )

        peerConn.addIceCandidate(iceCandidate)
    }

    /**
     * Set mute state
     */
    override fun setMuted(muted: Boolean) {
        log.d(TAG) { "Setting microphone mute: $muted" }

        try {
            audioManager?.isMicrophoneMute = muted
            localAudioTrack?.setEnabled(!muted)
        } catch (e: Exception) {
            log.d(TAG) { "Error setting mute state: ${e.message}" }
        }
    }

    override fun isMuted(): Boolean {
        val isAudioManagerMuted = audioManager?.isMicrophoneMute ?: false
        val isTrackDisabled = localAudioTrack?.enabled()?.not() ?: false
        return isAudioManagerMuted || isTrackDisabled
    }

    override fun getLocalDescription(): String? {
        return peerConnection?.localDescription?.description
    }

    override fun setAudioEnabled(enabled: Boolean) {
        log.d(TAG) { "Setting audio enabled: $enabled" }

        audioManager?.isMicrophoneMute = !enabled

        if (localAudioTrack == null && isInitialized) {
            log.d(TAG) { "No local audio track but WebRTC is initialized, trying to add audio track" }
            coroutineScope.launch {
                ensureLocalAudioTrack()
                localAudioTrack?.setEnabled(enabled)
            }
        } else {
            localAudioTrack?.setEnabled(enabled)
        }
    }

    override fun getConnectionState(): WebRtcConnectionState {
        if (!isInitialized || peerConnection == null) {
            return WebRtcConnectionState.NEW
        }

        val state = peerConnection?.connectionState() ?: return WebRtcConnectionState.NEW
        return mapConnectionState(state)
    }

    override fun setListener(listener: Any?) {
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

    @SuppressLint("MissingPermission")
    override fun diagnoseAudioIssues(): String {
        return buildString {
            appendLine("=== ENHANCED AUDIO DIAGNOSIS (Stream WebRTC) ===")
            appendLine("WebRTC Initialized: $isInitialized")
            appendLine("Local Audio Ready: $isLocalAudioReady")
            appendLine("Local Audio Track: ${localAudioTrack != null}")
            appendLine("Local Audio Enabled: ${localAudioTrack?.enabled()}")
            appendLine("Remote Audio Track: ${remoteAudioTrack != null}")
            appendLine("Remote Audio Enabled: ${remoteAudioTrack?.enabled()}")

            audioManager?.let { am ->
                appendLine("\n--- AudioManager State ---")
                appendLine("Audio Mode: ${am.mode}")
                appendLine("Speaker On: ${am.isSpeakerphoneOn}")
                appendLine("Mic Muted: ${am.isMicrophoneMute}")
                appendLine("Bluetooth SCO On: ${am.isBluetoothScoOn}")
                appendLine("Bluetooth SCO Available: ${am.isBluetoothScoAvailableOffCall}")
                appendLine("Wired Headset On: ${am.isWiredHeadsetOn}")
                appendLine("Music Active: ${am.isMusicActive}")
            }

            appendLine("\n--- Current Devices ---")
            appendLine("Current Input: ${currentInputDevice?.name ?: "Not set"}")
            appendLine("Current Output: ${currentOutputDevice?.name ?: "Not set"}")

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
        }
    }

    override fun isInitialized(): Boolean = isInitialized

    /**
     * Send DTMF tones - Stream WebRTC version
     */
    override fun sendDtmfTones(tones: String, duration: Int, gap: Int): Boolean {
        log.d(TAG) { "Sending DTMF tones: $tones (duration: $duration, gap: $gap)" }

        if (!isInitialized || peerConnection == null) {
            log.d(TAG) { "Cannot send DTMF: WebRTC not initialized" }
            return false
        }

        try {
            val senders = peerConnection?.senders ?: return false

            val audioSender = senders.find { sender ->
                sender.track()?.kind().equals(MediaStreamTrack.AUDIO_TRACK_KIND, ignoreCase = true)
            }

            if (audioSender == null) {
                log.d(TAG) { "Cannot send DTMF: No audio sender found" }
                return false
            }

            val dtmfSender = audioSender.dtmf() ?: run {
                log.d(TAG) { "Cannot send DTMF: DtmfSender not available" }
                return false
            }

            val sanitizedTones = sanitizeDtmfTones(tones)
            if (sanitizedTones.isEmpty()) {
                log.d(TAG) { "Cannot send DTMF: No valid tones to send" }
                return false
            }

            val result = dtmfSender.insertDtmf(sanitizedTones, duration, gap)
            log.d(TAG) { "DTMF tone sending result: $result" }
            return result

        } catch (e: Exception) {
            log.d(TAG) { "Error sending DTMF tones: ${e.message}" }
            return false
        }
    }

    private fun sanitizeDtmfTones(tones: String): String {
        val validDtmfPattern = Regex("[0-9A-D*#,]", RegexOption.IGNORE_CASE)
        return tones.filter { tone ->
            validDtmfPattern.matches(tone.toString())
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun mapConnectionState(state: PeerConnection.PeerConnectionState): WebRtcConnectionState {
        return when (state) {
            PeerConnection.PeerConnectionState.NEW -> WebRtcConnectionState.NEW
            PeerConnection.PeerConnectionState.CONNECTING -> WebRtcConnectionState.CONNECTING
            PeerConnection.PeerConnectionState.CONNECTED -> WebRtcConnectionState.CONNECTED
            PeerConnection.PeerConnectionState.DISCONNECTED -> WebRtcConnectionState.DISCONNECTED
            PeerConnection.PeerConnectionState.FAILED -> WebRtcConnectionState.FAILED
            PeerConnection.PeerConnectionState.CLOSED -> WebRtcConnectionState.CLOSED
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun initializePeerConnection() {
        try {
            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                bundlePolicy = PeerConnection.BundlePolicy.BALANCED
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }

            peerConnection = peerConnectionFactory?.createPeerConnection(
                rtcConfig,
                object : PeerConnection.Observer {
                    override fun onSignalingChange(signalingState: PeerConnection.SignalingState?) {
                        log.d(TAG) { "Signaling state: $signalingState" }
                    }

                    override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState?) {
                        log.d(TAG) { "ICE connection state: $iceConnectionState" }
                    }

                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                    override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState?) {}
                    override fun onIceCandidate(iceCandidate: IceCandidate?) {}
                    override fun onIceCandidatesRemoved(iceCandidates: Array<out IceCandidate>?) {}
                    override fun onAddStream(mediaStream: MediaStream?) {}
                    override fun onRemoveStream(mediaStream: MediaStream?) {}
                    override fun onDataChannel(dataChannel: DataChannel?) {}
                    override fun onRenegotiationNeeded() {}

                    override fun onAddTrack(
                        receiver: RtpReceiver?,
                        streams: Array<out MediaStream>?
                    ) {
                        log.d(TAG) { "Track added: ${receiver?.track()?.kind()}" }

                        receiver?.track()?.let { track ->
                            if (track.kind()
                                    .equals(MediaStreamTrack.AUDIO_TRACK_KIND, ignoreCase = true)
                            ) {
                                val audioTrack = track as AudioTrack
                                remoteAudioTrack = audioTrack

                                if (isOpenAiEnabled) {
                                    // Interceptar audio remoto para OpenAI
                                    setupAudioInterceptionWithSink(audioTrack)
                                    audioTrack.setEnabled(false) // Desactivar reproducción directa
                                } else {
                                    audioTrack.setEnabled(true) // Reproducción normal
                                }
                            }
                        }
                    }
                }
            )
        } catch (e: Exception) {
            log.e(TAG) { "Error inicializando PeerConnection: ${e.message}" }
        }
    }

//    private fun initializePeerConnection() {
//        log.d(TAG) { "Initializing PeerConnection..." }
//        cleanupCall()
//
//        try {
//            val iceServers = listOf(
//                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
//                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
//            )
//
//            val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
//            rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
//            rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
//
//            peerConnection = peerConnectionFactory?.createPeerConnection(
//                rtcConfig,
//                object : PeerConnection.Observer {
//                    override fun onSignalingChange(signalingState: PeerConnection.SignalingState?) {
//                        log.d(TAG) { "Signaling state changed: $signalingState" }
//                    }
//
//                    override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState?) {
//                        log.d(TAG) { "ICE connection state changed: $iceConnectionState" }
//                    }
//
//                    override fun onIceConnectionReceivingChange(receiving: Boolean) {
//                        log.d(TAG) { "ICE connection receiving change: $receiving" }
//                    }
//
//                    override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState?) {
//                        log.d(TAG) { "ICE gathering state changed: $iceGatheringState" }
//                    }
//
//                    override fun onIceCandidate(iceCandidate: IceCandidate?) {
//                        iceCandidate?.let { candidate ->
//                            log.d(TAG) { "New ICE Candidate: ${candidate.sdp}" }
//                            webRtcEventListener?.onIceCandidate(
//                                candidate.sdp,
//                                candidate.sdpMid,
//                                candidate.sdpMLineIndex
//                            )
//                        }
//                    }
//
//                    override fun onIceCandidatesRemoved(iceCandidates: Array<out IceCandidate>?) {
//                        log.d(TAG) { "ICE candidates removed" }
//                    }
//
//                    override fun onAddStream(mediaStream: MediaStream?) {
//                        log.d(TAG) { "Remote stream added" }
//                        mediaStream?.audioTracks?.firstOrNull()?.let { audioTrack ->
//                            remoteAudioTrack = audioTrack
//
//                            if (isOpenAiEnabled) {
//                                // Set up audio interception with Stream WebRTC AudioTrackSink
//                                setupAudioInterceptionWithSink(audioTrack)
//                                audioTrack.setEnabled(false) // Disable direct playback
//                            } else {
//                                audioTrack.setEnabled(true) // Normal playback
//                            }
//
//                            webRtcEventListener?.onRemoteAudioTrack()
//                        }
//                    }
//
//                    override fun onRemoveStream(mediaStream: MediaStream?) {
//                        log.d(TAG) { "Remote stream removed" }
//                    }
//
//                    override fun onDataChannel(dataChannel: DataChannel?) {
//                        log.d(TAG) { "Data channel received" }
//                    }
//
//                    override fun onRenegotiationNeeded() {
//                        log.d(TAG) { "Renegotiation needed" }
//                    }
//
//                    override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
//                        log.d(TAG) { "Track added: ${receiver?.track()?.kind()}" }
//                    }
//                }
//            )
//
//            log.d(TAG) { "PeerConnection created: ${peerConnection != null}" }
//            isLocalAudioReady = false
//
//        } catch (e: Exception) {
//            log.e(TAG) { "Error initializing PeerConnection: ${e.message}" }
//            peerConnection = null
//            isInitialized = false
//            isLocalAudioReady = false
//        }
//    }

    /**
     * Setup audio interception using Stream WebRTC's AudioTrackSink
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun setupAudioInterceptionWithSink(audioTrack: AudioTrack) {
        val audioSink = object : AudioTrackSink {
            override fun onData(
                audioData: ByteBuffer,
                bitsPerSample: Int,
                sampleRate: Int,
                numberOfChannels: Int,
                numberOfFrames: Int,
                absoluteCaptureTimestampMs: Long
            ) {
                if (isOpenAiEnabled) {
                    // Convertir ByteBuffer a ByteArray
                    val data = ByteArray(audioData.remaining())
                    audioData.get(data)

                    // Enviar a OpenAI
                    coroutineScope.launch {
                        processAudioForOpenAI(data, sampleRate)
                    }
                }
            }
        }

        audioTrack.addSink(audioSink)
        log.d(TAG) { "Audio sink configurado para OpenAI" }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun processAudioForOpenAI(audioData: ByteArray, sampleRate: Int) {
        try {
            // Convertir sample rate si es necesario
            val processedAudio = if (sampleRate != 24000) {
                resampleAudio(audioData, sampleRate, 24000)
            } else {
                audioData
            }

            // Enviar a OpenAI
            openAiClient?.addAudioData(processedAudio)
        } catch (e: Exception) {
            log.e(TAG) { "Error procesando audio para OpenAI: ${e.message}" }
        }
    }

    private fun resampleAudio(
        audioData: ByteArray,
        fromSampleRate: Int,
        toSampleRate: Int
    ): ByteArray {
        if (fromSampleRate == toSampleRate) return audioData

        // Implementación básica de resampling
        // En producción podrías usar una librería más sofisticada
        val ratio = fromSampleRate.toDouble() / toSampleRate.toDouble()
        val newLength = (audioData.size / ratio).toInt()
        val resampled = ByteArray(newLength)

        for (i in 0 until newLength step 2) {
            val sourceIndex = ((i / 2) * ratio).toInt() * 2
            if (sourceIndex + 1 < audioData.size) {
                resampled[i] = audioData[sourceIndex]
                resampled[i + 1] = audioData[sourceIndex + 1]
            }
        }

        return resampled
    }

    private suspend fun ensureLocalAudioTrack(): Boolean {
        return try {
            val peerConn = peerConnection ?: return false

            if (localAudioTrack != null) {
                log.d(TAG) { "Local audio track already exists" }
                return true
            }

            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager?.isMicrophoneMute = false

            // CORREGIDO: Constraints más específicos y compatibles
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                // CORREGIDO: Configuraciones adicionales para estabilidad
                mandatory.add(MediaConstraints.KeyValuePair("googAudioMirroring", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googDAEchoCancellation", "false"))
            }

            // Create audio source
            audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)

            // Create local audio track
            localAudioTrack = peerConnectionFactory?.createAudioTrack(
                "local_audio_${System.currentTimeMillis()}",
                audioSource
            )
            localAudioTrack?.setEnabled(true)

            // CORREGIDO: Usar addTrack con transceiver direction más específica
            localAudioTrack?.let { track ->
                val rtpSender =
                    peerConn.addTrack(track, listOf("local_stream_${System.currentTimeMillis()}"))

                // CORREGIDO: Configurar dirección del transceiver si es necesario
                peerConn.transceivers.find { it.sender == rtpSender }?.let { transceiver ->
                    transceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                }

                log.d(TAG) { "Local audio track added successfully using addTrack: ${rtpSender != null}" }
            }

            log.d(TAG) { "Local audio track created and added successfully with Unified Plan" }
            true

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating local audio track: ${e.message}" }
            false
        }
    }

    //    private suspend fun ensureLocalAudioTrack(): Boolean {
//        return try {
//            val peerConn = peerConnection ?: return false
//
//            if (localAudioTrack != null) {
//                log.d(TAG) { "Local audio track already exists" }
//                return true
//            }
//
//            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
//            audioManager?.isMicrophoneMute = false
//
//            // Create audio constraints
//            val audioConstraints = MediaConstraints().apply {
//                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
//                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
//                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
//                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
//            }
//
//            // Create audio source
//            audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
//
//            // Create local audio track
//            localAudioTrack = peerConnectionFactory?.createAudioTrack("local_audio", audioSource)
//            localAudioTrack?.setEnabled(true)
//
//            // Create local media stream and add track
//            localMediaStream = peerConnectionFactory?.createLocalMediaStream("local_stream")
//            localAudioTrack?.let { track ->
//                localMediaStream?.addTrack(track)
//            }
//
//            // Add stream to peer connection
//            localMediaStream?.let { stream ->
//                peerConn.addStream(stream)
//            }
//
//            log.d(TAG) { "Local audio track created and added successfully" }
//            true
//
//        } catch (e: Exception) {
//            log.e(TAG) { "Error creating local audio track: ${e.message}" }
//            false
//        }
//    }
    private fun cleanupCall() {
        try {
            localAudioTrack?.setEnabled(false)

            peerConnection?.let { pc ->
                // CORREGIDO: Remover senders correctamente
                pc.senders.forEach { sender ->
                    try {
                        pc.removeTrack(sender)
                        log.d(TAG) { "Removed sender: ${sender.track()?.kind()}" }
                    } catch (e: Exception) {
                        log.e(TAG) { "Error removing sender: ${e.message}" }
                    }
                }
            }

            peerConnection?.close()
            peerConnection = null

            Thread.sleep(100)

            // No necesitamos limpiar localMediaStream ya que no lo usamos
            localMediaStream?.dispose()
            localMediaStream = null

            localAudioTrack?.dispose()
            localAudioTrack = null

            audioSource?.dispose()
            audioSource = null

            remoteAudioTrack = null
            isLocalAudioReady = false

            System.gc()

        } catch (e: Exception) {
            log.e(TAG) { "Error in cleanupCall: ${e.message}" }
        }
    }
//    private fun cleanupCall() {
//        try {
//            localAudioTrack?.setEnabled(false)
//
//            peerConnection?.let { pc ->
//                pc.senders.forEach { sender ->
//                    try {
//                        pc.removeTrack(sender)
//                    } catch (e: Exception) {
//                        log.d(TAG) { "Error removing sender: ${e.message}" }
//                    }
//                }
//            }
//
//            peerConnection?.close()
//            peerConnection = null
//
//            Thread.sleep(100)
//
//            localMediaStream?.dispose()
//            localMediaStream = null
//
//            localAudioTrack?.dispose()
//            localAudioTrack = null
//
//            audioSource?.dispose()
//            audioSource = null
//
//            remoteAudioTrack = null
//            isLocalAudioReady = false
//
//            System.gc()
//
//        } catch (e: Exception) {
//            log.e(TAG) { "Error in cleanupCall: ${e.message}" }
//        }
//    }

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
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
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
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
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
    //     * Get current audio input descriptor
    //     */
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
            else -> if (audioManager?.isWiredHeadsetOn() == true) "earpiece" else "speaker"
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
        return if (audioManager?.isWiredHeadsetOn() == true) "earpiece" else "speaker"
    }

    /**
    //     * FIXED: Enhanced Bluetooth input switching
    //     */
    private fun switchToBluetoothInput(device: AudioDevice, am: AudioManager): Boolean {
        return try {
            log.d(TAG) { "Switching to Bluetooth input: ${device.name}" }

            // Verify Bluetooth SCO availability
            if (!am.isBluetoothScoAvailableOffCall) {
                log.w(TAG) { "Bluetooth SCO not available for input" }
                return false
            }

            // Check permissions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    log.w(TAG) { "BLUETOOTH_CONNECT permission not granted" }
                    return false
                }
            }

            // FIXED: Proper Bluetooth input routing
            am.mode = AudioManager.MODE_IN_COMMUNICATION

            if (!am.isBluetoothScoOn && !isBluetoothScoRequested) {
                log.d(TAG) { "Starting Bluetooth SCO for input..." }
                isBluetoothScoRequested = true
                am.startBluetoothSco()

                coroutineScope.launch {
                    delay(1000)
                    if (!am.isBluetoothScoOn) {
                        log.w(TAG) { "Bluetooth SCO failed to connect for input" }
                        isBluetoothScoRequested = false
                    }
                }
            }

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
        if (audioManager?.isWiredHeadsetOn == true) {
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
            isDefault = if (audioManager?.isWiredHeadsetOn == true) false else defaultOutputDescriptor == speakerDescriptor
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
            val audioDevices = audioManager?.getDevices(
                AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS
            ) ?: return

            audioDevices.forEach { deviceInfo ->
                when (deviceInfo.type) {
                    AudioDeviceInfo.TYPE_USB_ACCESSORY,
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET -> {
                        addUsbDevice(
                            deviceInfo, inputDevices, outputDevices,
                            currentInputDescriptor, currentOutputDescriptor
                        )
                    }

                    AudioDeviceInfo.TYPE_DOCK -> {
                        addDockDevice(
                            deviceInfo, inputDevices, outputDevices,
                            currentInputDescriptor, currentOutputDescriptor
                        )
                    }

                    AudioDeviceInfo.TYPE_AUX_LINE -> {
                        addAuxDevice(
                            deviceInfo, inputDevices, outputDevices,
                            currentInputDescriptor, currentOutputDescriptor
                        )
                    }

                    AudioDeviceInfo.TYPE_HEARING_AID -> {
                        addHearingAidDevice(
                            deviceInfo, inputDevices, outputDevices,
                            currentInputDescriptor, currentOutputDescriptor
                        )
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
                    vendorInfo = extractVendorFromDeviceName(
                        deviceInfo.productName?.toString() ?: ""
                    )
                )
            )
        }
    }

    /**
     * Enhanced current device detection
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun getCurrentInputDevice(): AudioDevice? {
        // if (currentInputDevice != null) {
        return currentInputDevice
        //  }

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
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun getCurrentOutputDevice(): AudioDevice? {
        //if (currentOutputDevice != null) {
        return currentOutputDevice
        // }

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
            val currentDesc = peerConn.localDescription ?: return

            // Cambiar dirección en el SDP actual
            val modifiedSdp = updateSdpDirection(currentDesc.description, direction)
            val newDesc = SessionDescription(currentDesc.type, modifiedSdp)

            peerConn.setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {}
                override fun onSetSuccess() {
                    log.d(TAG) { "Local description updated" }
                }

                override fun onCreateFailure(error: String) {
                    log.d(TAG) { "Create failed: $error" }
                }

                override fun onSetFailure(error: String) {
                    log.d(TAG) { "Set failed: $error" }
                }
            }, newDesc)

            // Si ya hay remoteDescription, renegociar
            if (peerConn.remoteDescription != null) {
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                }

                val observer = object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        val finalSdp = updateSdpDirection(desc.description, direction)
                        val finalDesc = SessionDescription(desc.type, finalSdp)
                        peerConn.setLocalDescription(this, finalDesc)
                    }

                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String) {
                        log.d(TAG) { "Offer/Answer creation failed: $error" }
                    }

                    override fun onSetFailure(error: String) {
                        log.d(TAG) { "SetLocalDescription failed: $error" }
                    }
                }

                if (currentDesc.type == SessionDescription.Type.OFFER) {
                    peerConn.createOffer(observer, constraints)
                } else {
                    peerConn.createAnswer(observer, constraints)
                }
            }
        } catch (e: Exception) {
            log.d(TAG) { "Error setting media direction: ${e.message}" }
        }
    }


    /**
     * Applies modified SDP to the peer connection
     * @param modifiedSdp The modified SDP string
     * @return true if successful, false otherwise
     */
    override suspend fun applyModifiedSdp(modifiedSdp: String): Boolean {
        return try {
            val description = SessionDescription(SessionDescription.Type.OFFER, modifiedSdp)
            peerConnection?.setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription) {}
                override fun onSetSuccess() {
                    log.d(TAG) { "Modified SDP applied successfully" }
                }

                override fun onCreateFailure(error: String) {}
                override fun onSetFailure(error: String) {
                    log.d(TAG) { "Error applying modified SDP: $error" }
                }
            }, description)
            true
        } catch (e: Exception) {
            log.d(TAG) { "Error applying modified SDP: ${e.message}" }
            false
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
     * FIXED: Enhanced Bluetooth output switching with proper SCO handling
     */
    private fun switchToBluetoothOutput(device: AudioDevice, am: AudioManager): Boolean {
        return try {
            log.d(TAG) { "Switching to Bluetooth output: ${device.name}" }

            // Verify if Bluetooth SCO is available
            if (!am.isBluetoothScoAvailableOffCall) {
                log.w(TAG) { "Bluetooth SCO not available for off-call use" }
                return false
            }

            // Check permissions
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                    log.w(TAG) { "BLUETOOTH_CONNECT permission not granted" }
                    return false
                }
            }

            // FIXED: Proper sequence for Bluetooth audio routing

            // 1. First, stop other audio modes
            am.isSpeakerphoneOn = false

            // 2. Ensure we're in communication mode
            am.mode = AudioManager.MODE_IN_COMMUNICATION

            // 3. Check if SCO is already connected
            if (am.isBluetoothScoOn) {
                log.d(TAG) { "Bluetooth SCO already connected" }
                return true
            }

            // 4. Verify that the specific Bluetooth device is connected
            val bluetoothDevice = device.nativeDevice as? BluetoothDevice
            if (bluetoothDevice != null) {
                log.w(TAG) { "Bluetooth device not connected: ${device.name}" }
                return false
            }

            // 5. Start Bluetooth SCO if not already requested
            if (!isBluetoothScoRequested && !am.isBluetoothScoOn) {
                log.d(TAG) { "Starting Bluetooth SCO..." }
                isBluetoothScoRequested = true
                am.startBluetoothSco()

                // FIXED: Wait a bit for SCO connection to establish
                // In a real environment, this should be handled asynchronously
                coroutineScope.launch {
                    delay(1000) // Wait 1 second
                    if (!am.isBluetoothScoOn) {
                        log.w(TAG) { "Bluetooth SCO failed to connect after timeout" }
                        isBluetoothScoRequested = false
                    }
                }
            }

            true
        } catch (e: SecurityException) {
            log.e(TAG) { "Security error switching to Bluetooth: ${e.message}" }
            false
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to Bluetooth output: ${e.message}" }
            false
        }
    }

    /**
     * FIXED: Enhanced speaker switching
     */
    private fun switchToSpeaker(am: AudioManager): Boolean {
        return try {
            // FIXED: Stop Bluetooth SCO if active
            if (am.isBluetoothScoOn) {
                log.d(TAG) { "Stopping Bluetooth SCO for speaker" }
                am.stopBluetoothSco()
                isBluetoothScoRequested = false
            }

            // Enable speaker
            am.isSpeakerphoneOn = true
            am.mode = AudioManager.MODE_IN_COMMUNICATION

            log.d(TAG) { "Switched to speaker successfully" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to speaker: ${e.message}" }
            false
        }
    }

    /**
     * FIXED: Enhanced wired headset switching
     */
    private fun switchToWiredHeadset(am: AudioManager): Boolean {
        return try {
            // FIXED: Stop other audio modes
            am.isSpeakerphoneOn = false
            if (am.isBluetoothScoOn) {
                log.d(TAG) { "Stopping Bluetooth SCO for wired headset" }
                am.stopBluetoothSco()
                isBluetoothScoRequested = false
            }

            am.mode = AudioManager.MODE_IN_COMMUNICATION
            log.d(TAG) { "Switched to wired headset successfully" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error switching to wired headset: ${e.message}" }
            false
        }
    }

    /**
     * FIXED: Enhanced earpiece switching
     */
    private fun switchToEarpiece(am: AudioManager): Boolean {
        return try {
            // FIXED: Reset all other output modes
            am.isSpeakerphoneOn = false
            if (am.isBluetoothScoOn) {
                log.d(TAG) { "Stopping Bluetooth SCO for earpiece" }
                am.stopBluetoothSco()
                isBluetoothScoRequested = false
            }

            am.mode = AudioManager.MODE_IN_COMMUNICATION
            log.d(TAG) { "Switched to earpiece successfully" }
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

    /**
    //     * Modifies the SDP to update the media direction attribute
    //     */
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
                    line.startsWith("a=inactive")
                ) {
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

    // ✅ AÑADIR - Conexión directa sin interceptación
    fun setupDirectRealtimeConnection(realtimeSession: RealtimeSession): Boolean {
        return try {
            // Deshabilitar interceptación existente
            isOpenAiEnabled = false

            // Configurar conexión directa con OpenAI Realtime
            localAudioTrack?.let { track ->
                // Enrutar directamente a Peer B del RealtimeSession
                val peerB = realtimeSession.getPeerBForAgentAudio()
                peerB?.addTrack(track, listOf("agent_direct_stream"))
            }

            remoteAudioTrack?.let { track ->
                // Enrutar directamente a Peer A del RealtimeSession
                val peerA = realtimeSession.getPeerAForClientAudio()
                // El audio del cliente ya llegará al Peer A automáticamente
                track.setEnabled(false) // No reproducir directamente
            }

            log.d(TAG) { "Direct Realtime connection established" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error setting up direct Realtime connection: ${e.message}" }
            false
        }
    }

    // ✅ AÑADIR - Obtener tracks para AudioBridge
    fun getCurrentCallAudioTracks(): Pair<AudioTrack?, AudioTrack?> {
        return Pair(remoteAudioTrack, localAudioTrack)
    }
}
/**
 * Enhanced Android implementation of WebRtcManager interface with comprehensive audio device support
 * MIGRATED TO: Stream WebRTC Android
 * FIXED: Bluetooth audio routing issues with real-time audio interception
 *
 * @author Eddys Larez
 */

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