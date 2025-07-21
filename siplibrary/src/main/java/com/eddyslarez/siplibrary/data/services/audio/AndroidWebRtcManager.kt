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
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.AudioFormat
import android.media.MediaPlayer
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.eddyslarez.siplibrary.data.models.AccountInfo
import com.eddyslarez.siplibrary.data.models.CallState
import com.eddyslarez.siplibrary.data.services.audio.WebRtcManager.TranslationQuality
import com.eddyslarez.siplibrary.data.services.audio.WebRtcManager.TranslationStats
import com.eddyslarez.siplibrary.data.services.realTime.OpenAIRealtimeManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable.isActive
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WAV file recorder for audio recording
 */
open class WavRecorder(
    private val filePath: String,
    private val sampleRate: Int = 16000,
    private val channelCount: Int = 1,
    private val bitRate: Int = 16
) {
    private val TAG = "WavRecorder"
    private var audioRecord: AudioRecord? = null
    private var isRecording = AtomicBoolean(false)
    private var recordingJob: Job? = null
    private var fileOutputStream: FileOutputStream? = null
    private var dataSize = 0L

    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    /**
    +     * NUEVO: Método virtual para interceptar datos de audio
    +     */
    protected open fun onAudioDataReceived(audioData: ByteArray) {
        // Método base vacío - las subclases pueden sobrescribirlo
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecording(coroutineScope: CoroutineScope): Boolean {
        if (isRecording.get()) {
            log.w(TAG) { "Recording already in progress" }
            return false
        }

        return try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                if (channelCount == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                log.e(TAG) { "AudioRecord initialization failed" }
                return false
            }

            fileOutputStream = FileOutputStream(filePath)
            writeWavHeader()

            audioRecord?.startRecording()
            isRecording.set(true)

            recordingJob = coroutineScope.launch(Dispatchers.IO) {
                recordAudio()
            }

            log.d(TAG) { "WAV recording started: $filePath" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error starting recording: ${e.message}" }
            cleanup()
            false
        }
    }

    fun stopRecording() {
        if (!isRecording.get()) return

        isRecording.set(false)
        recordingJob?.cancel()

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        // Update WAV header with final size
        updateWavHeader()

        fileOutputStream?.close()
        fileOutputStream = null

        log.d(TAG) { "WAV recording stopped: $filePath" }
    }

    private fun recordAudio() {
        val buffer = ByteArray(bufferSize)

        while (isRecording.get()) {
            try {
                val bytesRead = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                if (bytesRead > 0) {
                    // NUEVO: Llamar al método de interceptación antes de escribir
                    val audioData = buffer.copyOfRange(0, bytesRead)
                    onAudioDataReceived(audioData)
                    fileOutputStream?.write(buffer, 0, bytesRead)
                    dataSize += bytesRead
                }
            } catch (e: Exception) {
                log.e(TAG) { "Error recording audio: ${e.message}" }
                break
            }
        }
    }

    private fun writeWavHeader() {
        val header = ByteArray(44)
        val byteBuffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        byteBuffer.put("RIFF".toByteArray())
        byteBuffer.putInt(36) // Placeholder for file size
        byteBuffer.put("WAVE".toByteArray())

        // fmt chunk
        byteBuffer.put("fmt ".toByteArray())
        byteBuffer.putInt(16) // fmt chunk size
        byteBuffer.putShort(1) // Audio format (PCM)
        byteBuffer.putShort(channelCount.toShort())
        byteBuffer.putInt(sampleRate)
        byteBuffer.putInt(sampleRate * channelCount * bitRate / 8) // Byte rate
        byteBuffer.putShort((channelCount * bitRate / 8).toShort()) // Block align
        byteBuffer.putShort(bitRate.toShort()) // Bits per sample

        // data chunk
        byteBuffer.put("data".toByteArray())
        byteBuffer.putInt(0) // Placeholder for data size

        fileOutputStream?.write(header)
    }

    private fun updateWavHeader() {
        try {
            val file = File(filePath)
            val randomAccessFile = java.io.RandomAccessFile(file, "rw")

            // Update file size
            randomAccessFile.seek(4)
            randomAccessFile.writeInt(Integer.reverseBytes((dataSize + 36).toInt()))

            // Update data size
            randomAccessFile.seek(40)
            randomAccessFile.writeInt(Integer.reverseBytes(dataSize.toInt()))

            randomAccessFile.close()
        } catch (e: Exception) {
            log.e(TAG) { "Error updating WAV header: ${e.message}" }
        }
    }

    private fun cleanup() {
        isRecording.set(false)
        recordingJob?.cancel()
        audioRecord?.release()
        audioRecord = null
        fileOutputStream?.close()
        fileOutputStream = null
    }
}

/**
 * Audio file player for playing files instead of microphone
 */
class AudioFilePlayer(
    private val filePath: String,
    private val sampleRate: Int = 16000
) {
    private val TAG = "AudioFilePlayer"
    private var audioTrack: AudioTrack? = null
    private var isPlaying = AtomicBoolean(false)
    private var playingJob: Job? = null
    private var fileInputStream: FileInputStream? = null
    private var isLooping = false

    fun startPlaying(coroutineScope: CoroutineScope, loop: Boolean = false): Boolean {
        if (isPlaying.get()) {
            log.w(TAG) { "Already playing" }
            return false
        }

        return try {
            val file = File(filePath)
            if (!file.exists()) {
                log.e(TAG) { "Audio file not found: $filePath" }
                return false
            }

            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
                bufferSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )

            fileInputStream = FileInputStream(file)
            skipWavHeader()

            audioTrack?.play()
            isPlaying.set(true)
            isLooping = loop

            playingJob = coroutineScope.launch(Dispatchers.IO) {
                playAudio()
            }

            log.d(TAG) { "Audio file playing started: $filePath" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error starting audio playback: ${e.message}" }
            cleanup()
            false
        }
    }

    fun stopPlaying() {
        if (!isPlaying.get()) return

        isPlaying.set(false)
        playingJob?.cancel()

        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null

        fileInputStream?.close()
        fileInputStream = null

        log.d(TAG) { "Audio file playing stopped: $filePath" }
    }

    private fun playAudio() {
        val buffer = ByteArray(4096)

        do {
            try {
                fileInputStream?.let { inputStream ->
                    var bytesRead = inputStream.read(buffer)
                    while (isPlaying.get() && bytesRead > 0) {
                        audioTrack?.write(buffer, 0, bytesRead)
                        bytesRead = inputStream.read(buffer)
                    }

                    if (isLooping && isPlaying.get()) {
                        inputStream.close()
                        fileInputStream = FileInputStream(filePath)
                        skipWavHeader()
                    }
                }
            } catch (e: Exception) {
                log.e(TAG) { "Error playing audio: ${e.message}" }
                break
            }
        } while (isLooping && isPlaying.get())
    }


    private fun skipWavHeader() {
        try {
            // Skip standard WAV header (44 bytes)
            fileInputStream?.skip(44)
        } catch (e: Exception) {
            log.w(TAG) { "Error skipping WAV header: ${e.message}" }
        }
    }

    private fun cleanup() {
        isPlaying.set(false)
        playingJob?.cancel()
        audioTrack?.release()
        audioTrack = null
        fileInputStream?.close()
        fileInputStream = null
    }
}

/**
 * Audio file manager for handling recordings and playback
 */
class AudioFileManager(private val context: Context) {
    private val TAG = "AudioFileManager"

    private val audioDirectory: File by lazy {
        File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "CallRecordings").apply {
            if (!exists()) mkdirs()
        }
    }

    fun createRecordingPath(prefix: String): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(audioDirectory, "${prefix}_${timestamp}.wav").absolutePath
    }

    fun getAudioFiles(): List<File> {
        return audioDirectory.listFiles { file ->
            file.isFile && file.extension.equals("wav", ignoreCase = true)
        }?.toList() ?: emptyList()
    }

    fun deleteAudioFile(filePath: String): Boolean {
        return try {
            File(filePath).delete()
        } catch (e: Exception) {
            log.e(TAG) { "Error deleting audio file: ${e.message}" }
            false
        }
    }

    fun getAudioDuration(filePath: String): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(filePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            duration?.toLong() ?: 0L
        } catch (e: Exception) {
            log.e(TAG) { "Error getting audio duration: ${e.message}" }
            0L
        }
    }
}

/**
 * Enhanced Android implementation of WebRtcManager interface with comprehensive audio device support
 * FIXED: Bluetooth audio routing issues
 * ADDED: WAV recording and audio file playback capabilities
 *
 * @author Eddys Larez
 */
class AndroidWebRtcManager(private val application: Application) : WebRtcManager {
    private val TAG = "AndroidWebRtcManager"
    private val scope = CoroutineScope(Dispatchers.IO)

    // Audio configuration - OPTIMIZADO para mejor calidad
    private val SAMPLE_RATE = 24000 // Cambiado a 24kHz para mejor calidad
    private val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
    private val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE_FACTOR = 4 // Aumentado para mejor estabilidad

    // Audio components
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var audioManager: AudioManager? = null

    // State management
    private var isInitialized = AtomicBoolean(false)
    private var isMuted = AtomicBoolean(false)
    private var isAudioEnabled = AtomicBoolean(true)
    private var listener: WebRtcEventListener? = null

    // Audio processing
    private var audioProcessingJob: Job? = null
    private var playbackJob: Job? = null
    private val isProcessingAudio = AtomicBoolean(false)

    // AI Translation - NUEVO: Configuración anti-bucle
    private var openAIManager: OpenAIRealtimeManager? = null
    private var isTranslationEnabled = AtomicBoolean(false)
    private var currentTargetLanguage: String? = null
    private var translationQuality = TranslationQuality.MEDIUM

    // CRÍTICO: Control de bucle de audio
    private val isPlayingTranslatedAudio = AtomicBoolean(false)
    private val translatedAudioQueue = mutableListOf<ByteArray>()
    private val audioQueueLock = Any()
    private var lastTranslationTime = 0L
    private val MIN_TRANSLATION_INTERVAL = 2000L // Mínimo 2 segundos entre traducciones

    // Audio file management
    private val recordedFiles = mutableListOf<File>()
    private var currentInputAudioFile: String? = null
    private var currentOutputAudioFile: String? = null
    private var isRecordingSent = AtomicBoolean(false)
    private var isRecordingReceived = AtomicBoolean(false)
    private var isPlayingInputFile = AtomicBoolean(false)
    private var isPlayingOutputFile = AtomicBoolean(false)

    // Audio devices
    private val availableInputDevices = mutableListOf<AudioDevice>()
    private val availableOutputDevices = mutableListOf<AudioDevice>()
    private var currentInputDevice: AudioDevice? = null
    private var currentOutputDevice: AudioDevice? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioStreamTrack? = null
    private var remoteAudioTrack: AudioStreamTrack? = null
    private var webRtcEventListener: WebRtcEventListener? = null
    private var isLocalAudioReady = false
    private var context: Context = application.applicationContext

    private var isRemoteAudioMuted = false
    private var originalRemoteAudioVolume = 1.0f
    // NUEVO: Grabador especial para traducción (usa el mismo sistema que ya tienes)
    private var translationAudioRecorder: WavRecorder? = null
    private var isRecordingForTranslation = false
    private var currentTranslationRecordingPath: String? = null
    private var translationProcessingJob: Job? = null

    // MODIFICADO: Solo capturar audio remoto para traducción
    // MODIFICADO: Buffer para audio remoto interceptado
    private var remoteAudioBuffer = mutableListOf<ByteArray>()
    private var remoteAudioProcessingJob: Job? = null
    private var lastTranslationSent = 0L
    private val translationIntervalMs = 1000L

    // Buffer para acumular audio antes de enviar a traducción
    private val translationAudioBuffer = mutableListOf<ByteArray>()

    // MODIFICADO: Solo capturar audio remoto para traducción
    private var remoteAudioProcessor: Job? = null
    private var isProcessingRemoteAudio = false
    private var lastRemoteAudioProcessed = 0L
    private val remoteAudioProcessingInterval = 1500L // Reducido para menor latencia


    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothManager: BluetoothManager? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var translatedAudioPlayer: AudioFilePlayer? = null

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

    // NUEVO: Audio recording and playback components
    private var audioFileManager: AudioFileManager = AudioFileManager(context)
    private var sentAudioRecorder: WavRecorder? = null
    private var receivedAudioRecorder: WavRecorder? = null
    private var inputAudioPlayer: AudioFilePlayer? = null
    private var outputAudioPlayer: AudioFilePlayer? = null

    // NUEVO: Recording state
    private var isRecordingSentAudio = false
    private var isRecordingReceivedAudio = false
    private var currentSentRecordingPath: String? = null
    private var currentReceivedRecordingPath: String? = null

    // NUEVO: Audio file paths for playback
    private var inputAudioFilePath: String? = null
    private var outputAudioFilePath: String? = null

    init {
        initializeBluetoothComponents()
        setupAudioDeviceMonitoring()
        setupBluetoothScoReceiver() // FIXED: Add Bluetooth SCO monitoring
    }

    /**
     * NUEVO: Start recording sent audio (microphone input)
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun startRecordingSentAudio(): Boolean {
        isRecordingSent.set(true)
        Log.d(TAG, "Started recording sent audio")
        return true
    }
// ========== IMPLEMENTACIÓN DE FUNCIONES DE IA ==========

    /**
     * Enable AI audio translation for incoming audio
     */
    /**
     * MODIFICADO: Enable AI translation with complete remote audio muting
     */
//    override fun enableAudioTranslation(
//        apiKey: String,
//        targetLanguage: String,
//        model: String
//    ): Boolean {
//        log.d(TAG) { "Enabling AI audio translation with remote audio muting to $targetLanguage" }
//
//        return try {
//            openAIManager = OpenAIRealtimeManager(apiKey, model)
//
//            openAIManager?.setTranslationListener(object : OpenAIRealtimeManager.TranslationListener {
//                override fun onTranslationCompleted(
//                    originalAudio: ByteArray,
//                    translatedAudio: ByteArray,
//                    latency: Long
//                ) {
//                    log.d(TAG) { "Translation completed with latency: ${latency}ms, audio size: ${translatedAudio.size} bytes" }
//
//                    // Reproducir SOLO el audio traducido
//                    playTranslatedAudioOnly(translatedAudio)
//                    webRtcEventListener?.onTranslationCompleted(true, latency, null, null)
//                }
//
//                override fun onTranslationFailed(error: String) {
//                    log.e(TAG) { "Translation failed: $error" }
//                    webRtcEventListener?.onTranslationCompleted(false, 0, null, error)
//                }
//
//                override fun onTranslationStateChanged(isEnabled: Boolean, targetLanguage: String?) {
//                    webRtcEventListener?.onTranslationStateChanged(isEnabled, targetLanguage)
//                }
//
//                override fun onProcessingStateChanged(isProcessing: Boolean) {
//                    webRtcEventListener?.onTranslationProcessingChanged(isProcessing)
//                }
//            })
//
//            if (openAIManager?.initialize() == true) {
//                isTranslationEnabled = openAIManager?.enable(targetLanguage) == true
//                currentTargetLanguage = if (isTranslationEnabled) targetLanguage else null
//
//                if (isTranslationEnabled) {
//                    // CRÍTICO: Silenciar completamente el audio remoto
//                    muteRemoteAudioCompletely()
//
//                    // Iniciar procesamiento de audio remoto interceptado
//                    startRemoteAudioTranslationProcessing()
//                }
//
//                log.d(TAG) { "AI translation enabled with remote audio muted: $isTranslationEnabled" }
//                isTranslationEnabled
//            } else {
//                log.e(TAG) { "Failed to initialize OpenAI manager" }
//                false
//            }
//
//        } catch (e: Exception) {
//            log.e(TAG) { "Error enabling AI translation: ${e.message}" }
//            false
//        }
//    }

    override fun enableAudioTranslation(apiKey: String, targetLanguage: String, model: String): Boolean {
        if (isTranslationEnabled.get()) {
            Log.d(TAG, "Translation already enabled")
            return true
        }

        return try {
            Log.d(TAG, "Enabling AI audio translation with anti-loop configuration")

            // Crear manager de OpenAI con configuración optimizada
            openAIManager = OpenAIRealtimeManager(apiKey, model).apply {
                setTranslationListener(object : OpenAIRealtimeManager.TranslationListener {
                    override fun onTranslationCompleted(
                        originalAudio: ByteArray,
                        translatedAudio: ByteArray,
                        latency: Long
                    ) {
                        handleTranslatedAudio(translatedAudio)
                    }

                    override fun onTranslationFailed(error: String) {
                        Log.e(TAG, "Translation failed: $error")
                        listener?.onTranslationCompleted(false, 0, null, error)
                    }

                    override fun onTranslationStateChanged(isEnabled: Boolean, targetLanguage: String?) {
                        listener?.onTranslationStateChanged(isEnabled, targetLanguage)
                    }

                    override fun onProcessingStateChanged(isProcessing: Boolean) {
                        listener?.onTranslationProcessingChanged(isProcessing)
                    }
                })
            }

            // Inicializar y habilitar
            if (openAIManager?.initialize() == true) {
                if (openAIManager?.enable(targetLanguage) == true) {
                    isTranslationEnabled.set(true)
                    currentTargetLanguage = targetLanguage

                    // CRÍTICO: Configurar audio para evitar bucle
                    setupAntiLoopAudioConfiguration()

                    Log.d(TAG, "AI translation enabled successfully with anti-loop protection")
                    listener?.onTranslationStateChanged(true, targetLanguage)
                    return true
                }
            }

            Log.e(TAG, "Failed to enable AI translation")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling AI translation", e)
            false
        }
    }
//    private fun playTranslatedAudioOnly(translatedAudio: ByteArray) {
//        try {
//            // Crear archivo temporal para el audio traducido
//            val tempFile = File.createTempFile("translated_audio", ".wav", context.cacheDir)
//
//            // Crear header WAV para el audio traducido
//            val wavFile = createWavFile(translatedAudio, tempFile)
//
//            // Detener cualquier reproducción anterior
//            stopTranslatedAudioPlayback()
//
//            // Reproducir el audio traducido usando AudioTrack directamente
//            playTranslatedAudioDirectly(translatedAudio)
//
//            log.d(TAG) { "Playing translated audio only: ${translatedAudio.size} bytes" }
//
//            // Limpiar archivo temporal después de un tiempo
//            scope.launch {
//                delay(calculateAudioDuration(translatedAudio) + 1000)
//                tempFile.delete()
//            }
//
//        } catch (e: Exception) {
//            log.e(TAG) { "Error playing translated audio only: ${e.message}" }
//        }
//    }
    /**
     * MODIFICADO: Disable AI translation and restore remote audio
     */
    override fun disableAudioTranslation(): Boolean {
        return try {
            isTranslationEnabled.set(false)
            currentTargetLanguage = null

            openAIManager?.disable()
            openAIManager?.dispose()
            openAIManager = null

            // Limpiar cola de audio traducido
            synchronized(audioQueueLock) {
                translatedAudioQueue.clear()
            }

            isPlayingTranslatedAudio.set(false)

            Log.d(TAG, "AI translation disabled")
            listener?.onTranslationStateChanged(false, null)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling AI translation", e)
            false
        }
    }
    /**
     * CORREGIDO: Silenciar completamente el audio remoto sin usar volume
     */
    private fun muteRemoteAudioCompletely() {
        try {
            // Método principal: Deshabilitar el track de audio remoto
            remoteAudioTrack?.enabled = false
            isRemoteAudioMuted = true

            // Configurar AudioManager para minimizar volumen de llamada
            audioManager?.let { manager ->
                try {
                    originalRemoteAudioVolume = manager.getStreamVolume(AudioManager.STREAM_VOICE_CALL).toFloat()
                    manager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 0, 0)
                } catch (e: Exception) {
                    log.e(TAG) { "Error setting stream volume: ${e.message}" }
                }
            }

            log.d(TAG) { "Remote audio completely muted for translation" }

        } catch (e: Exception) {
            log.e(TAG) { "Error muting remote audio: ${e.message}" }
        }
    }


    /**
     * CORREGIDO: Restaurar audio remoto sin usar volume
     */
    private fun unmuteRemoteAudio() {
        try {
            // Restaurar track de audio remoto
            remoteAudioTrack?.enabled = true
            isRemoteAudioMuted = false

            // Restaurar volumen original
            audioManager?.let { manager ->
                try {
                    manager.setStreamVolume(
                        AudioManager.STREAM_VOICE_CALL,
                        originalRemoteAudioVolume.toInt(),
                        0
                    )
                } catch (e: Exception) {
                    log.e(TAG) { "Error restoring stream volume: ${e.message}" }
                }
            }

            log.d(TAG) { "Remote audio restored" }

        } catch (e: Exception) {
            log.e(TAG) { "Error restoring remote audio: ${e.message}" }
        }
    }
//    /**
//     * CORREGIDO: Iniciar procesamiento de audio remoto con validaciones mejoradas
//     */
//    private fun startRemoteAudioTranslationProcessing() {
//        if (remoteAudioProcessingJob?.isActive == true) return
//
//        remoteAudioProcessingJob = s.launch(Dispatchers.IO) {
//            while (isActive && isTranslationEnabled) {
//                try {
//                    delay(translationIntervalMs)
//
//                    synchronized(remoteAudioBuffer) {
//                        if (remoteAudioBuffer.isNotEmpty()) {
//                            // Combinar todo el audio acumulado
//                            val totalSize = remoteAudioBuffer.sumOf { it.size }
//
//                            // CORREGIDO: Validar tamaño mínimo más estricto
//                            val minAudioSize = (16000 * 2 * 0.5).toInt() // 500ms de audio PCM 16-bit 16kHz
//
//                            if (totalSize < minAudioSize) {
//                                log.d(TAG) { "Audio buffer too small: $totalSize bytes, need at least $minAudioSize (500ms)" }
//                                // No limpiar el buffer, mantener para próxima iteración
//                                return@synchronized
//                            }
//
//                            val combinedAudio = ByteArray(totalSize)
//                            var offset = 0
//
//                            remoteAudioBuffer.forEach { chunk ->
//                                System.arraycopy(chunk, 0, combinedAudio, offset, chunk.size)
//                                offset += chunk.size
//                            }
//
//                            // Limpiar buffer después de procesar
//                            remoteAudioBuffer.clear()
//
//                            log.d(TAG) { "Sending ${combinedAudio.size} bytes of remote audio to translation" }
//
//                            // CORREGIDO: Verificar que OpenAI no esté procesando antes de enviar
//                            if (openAIManager?.isProcessing() != true) {
//                                openAIManager?.processAudioForTranslation(combinedAudio)
//                                lastTranslationSent = System.currentTimeMillis()
//                            } else {
//                                log.d(TAG) { "OpenAI is busy, queuing audio for next cycle" }
//                                // Devolver el audio al buffer para próximo ciclo
//                                remoteAudioBuffer.add(0, combinedAudio)
//                            }
//                        }
//                    }
//
//                } catch (e: Exception) {
//                    log.e(TAG) { "Error in remote audio translation processing: ${e.message}" }
//                    delay(2000) // Esperar más tiempo en caso de error
//                }
//            }
//        }
//    }

//    /**
//     * NUEVO: Iniciar procesamiento solo de audio remoto
//     */
//    private fun startRemoteAudioTranslationProcessing() {
//        if (remoteAudioProcessingJob?.isActive == true) return
//
//        remoteAudioProcessingJob = coroutineScope.launch(Dispatchers.IO) {
//            while (isActive && isTranslationEnabled) {
//                try {
//                    delay(translationIntervalMs)
//                    synchronized(remoteAudioBuffer) {
//                        if (remoteAudioBuffer.isNotEmpty()) {
//                            // Combinar todo el audio acumulado
//                            val totalSize = remoteAudioBuffer.sumOf { it.size }
//                            val combinedAudio = ByteArray(totalSize)
//                            var offset = 0
//
//                            remoteAudioBuffer.forEach { chunk ->
//                                System.arraycopy(chunk, 0, combinedAudio, offset, chunk.size)
//                                offset += chunk.size
//                            }
//
//                            remoteAudioBuffer.clear()
//                            // NUEVO: Verificar que tenemos suficiente audio (mínimo 100ms)
//                            val minAudioSize =
//                                (16000 * 2 * 0.1).toInt() // 100ms de audio PCM 16-bit 16kHz
//                            if (totalSize < minAudioSize) {
//                                log.d(TAG) { "Audio buffer too small: $totalSize bytes, need at least $minAudioSize" }
//                                return@synchronized
//                            }
//                            if (combinedAudio.isNotEmpty()) {
//                                log.d(TAG) { "Sending ${combinedAudio.size} bytes of remote audio to translation" }
//                                openAIManager?.processAudioForTranslation(combinedAudio)
//                                lastTranslationSent = System.currentTimeMillis()
//                            }
//                        }
//                    }
//
//                } catch (e: Exception) {
//                    log.e(TAG) { "Error in remote audio translation processing: ${e.message}" }
//
//
//                    delay(1000)
//                }
//            }
//        }
//    }

    /**
     * NUEVO: Detener procesamiento de audio remoto
     */
    private fun stopRemoteAudioTranslationProcessing() {
        remoteAudioProcessingJob?.cancel()
        remoteAudioProcessingJob = null

        synchronized(remoteAudioBuffer) {
            remoteAudioBuffer.clear()
        }

        log.d(TAG) { "Stopped remote audio translation processing" }
    }

//    private fun stopRemoteAudioTranslationProcessing() {
//        remoteAudioProcessingJob?.cancel()
//        remoteAudioProcessingJob = null
//
//        synchronized(remoteAudioBuffer) {
//            remoteAudioBuffer.clear()
//        }
//    }
    /**
     * CORREGIDO: Interceptar audio remoto con validación de datos
     */
//    private fun interceptRemoteAudioForTranslation(audioData: ByteArray) {
//        if (!isTranslationEnabled) return
//
//        // CORREGIDO: Validar que los datos de audio no estén vacíos
//        if (audioData.isEmpty()) {
//            log.w(TAG) { "Received empty audio data for translation" }
//            return
//        }
//
//        try {
//            synchronized(remoteAudioBuffer) {
//                // CORREGIDO: Limitar el tamaño total del buffer para evitar acumulación excesiva
//                val maxBufferSize = 16000 * 2 * 10 // 10 segundos máximo
//                val currentBufferSize = remoteAudioBuffer.sumOf { it.size }
//
//                if (currentBufferSize + audioData.size > maxBufferSize) {
//                    log.w(TAG) { "Audio buffer full, removing oldest chunks" }
//                    // Remover chunks más antiguos
//                    while (remoteAudioBuffer.isNotEmpty() &&
//                        remoteAudioBuffer.sumOf { it.size } + audioData.size > maxBufferSize) {
//                        remoteAudioBuffer.removeAt(0)
//                    }
//                }
//
//                remoteAudioBuffer.add(audioData.copyOf())
//            }
//
//            log.d(TAG) { "Intercepted ${audioData.size} bytes of remote audio for translation" }
//        } catch (e: Exception) {
//            log.e(TAG) { "Error intercepting remote audio: ${e.message}" }
//        }
//    }
//    /**
//     * NUEVO: Combinar buffers de audio
//     */
//    private fun interceptRemoteAudioForTranslation(audioData: ByteArray) {
//        if (!isTranslationEnabled) return
//
//        try {
//            synchronized(remoteAudioBuffer) {
//                remoteAudioBuffer.add(audioData.copyOf())
//            }
//
//            log.d(TAG) { "Intercepted ${audioData.size} bytes of remote audio for translation" }
//        } catch (e: Exception) {
//            log.e(TAG) { "Error intercepting remote audio: ${e.message}" }
//        }
//    }

    /**
     * NUEVO: Reemplazar audio remoto con traducción
     */
//    private fun replaceRemoteAudioWithTranslation(translatedAudio: ByteArray) {
//        try {
//            // CRÍTICO: Detener temporalmente el track de audio remoto
//            remoteAudioTrack?.enabled = false
//
//            // Crear archivo temporal para audio traducido
//            val tempFile = File.createTempFile("translated_", ".wav", context.cacheDir)
//            createWavFileFromPcm(translatedAudio, tempFile)
//
//            // Reproducir audio traducido en lugar del remoto
////            playTranslatedAudioDirectly(tempFile.absolutePath)
//
//            // Re-habilitar audio remoto después de la traducción
//            coroutineScope.launch {
//                delay(calculateAudioDuration(translatedAudio))
//                remoteAudioTrack?.enabled = true
//                tempFile.delete()
//            }
//
//        } catch (e: Exception) {
//            log.e(TAG) { "Error replacing remote audio with translation: ${e.message}" }
//            // Re-habilitar audio remoto en caso de error
//            remoteAudioTrack?.enabled = true
//        }
//    }
//    private fun playTranslatedAudioDirectly(translatedAudio: ByteArray) {
//        try {
//            stopTranslatedAudioPlayback()
//
//            coroutineScope.launch(Dispatchers.IO) {
//                try {
//                    val sampleRate = 16000
//                    val channelConfig = AudioFormat.CHANNEL_OUT_MONO
//                    val audioFormat = AudioFormat.ENCODING_PCM_16BIT
//
//                    val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
//
//                    val audioTrack = AudioTrack(
//                        AudioManager.STREAM_VOICE_CALL,
//                        sampleRate,
//                        channelConfig,
//                        audioFormat,
//                        maxOf(bufferSize, translatedAudio.size),
//                        AudioTrack.MODE_STREAM
//                    )
//
//                    audioTrack.play()
//
//                    // Escribir audio en chunks
//                    val chunkSize = 1024
//                    var offset = 0
//
//                    while (offset < translatedAudio.size && isTranslationEnabled) {
//                        val remainingBytes = translatedAudio.size - offset
//                        val bytesToWrite = minOf(chunkSize, remainingBytes)
//
//                        val written = audioTrack.write(translatedAudio, offset, bytesToWrite)
//                        if (written > 0) {
//                            offset += written
//                        } else {
//                            delay(10) // Esperar un poco si no se pudo escribir
//                        }
//                    }
//
//                    // Esperar a que termine la reproducción
//                    delay(calculateAudioDuration(translatedAudio))
//
//                    audioTrack.stop()
//                    audioTrack.release()
//
//                    isPlayingTranslatedAudio = false
//
//                    log.d(TAG) { "Finished playing translated audio directly" }
//
//                } catch (e: Exception) {
//                    log.e(TAG) { "Error in direct audio playback: ${e.message}" }
//                }
//            }
//
//            isPlayingTranslatedAudio = true
//
//        } catch (e: Exception) {
//            log.e(TAG) { "Error starting direct audio playback: ${e.message}" }
//        }
//    }

//    /**
//     * NUEVO: Reproducir audio traducido directamente
//     */
//    private fun playTranslatedAudioDirectly(audioPath: String) {
//        try {
//            stopTranslatedAudioPlayback()
//
//            translatedAudioPlayer = AudioFilePlayer(audioPath, 16000)
//            isPlayingTranslatedAudio =
//                translatedAudioPlayer?.startPlaying(coroutineScope, false) == true
//
//            if (isPlayingTranslatedAudio) {
//                log.d(TAG) { "Playing translated audio directly" }
//            }
//
//        } catch (e: Exception) {
//            log.e(TAG) { "Error playing translated audio: ${e.message}" }
//        }
//    }

    /**
     * NUEVO: Detener reproducción de audio traducido
     */
//    private fun stopTranslatedAudioPlayback() {
//        if (isPlayingTranslatedAudio) {
//            translatedAudioPlayer?.stopPlaying()
//            translatedAudioPlayer = null
//            isPlayingTranslatedAudio = false
//        }
//    }
//    private fun stopTranslatedAudioPlayback() {
//        if (isPlayingTranslatedAudio) {
//            translatedAudioPlayer?.stopPlaying()
//            translatedAudioPlayer = null
//            isPlayingTranslatedAudio = false
//            log.d(TAG) { "Stopped translated audio playback" }
//        }
//    }
    /**
     * NUEVO: Calcular duración de audio PCM
     */
    private fun calculateAudioDuration(pcmData: ByteArray): Long {
        val sampleRate = 16000
        val bytesPerSample = 2 // 16-bit
        val channels = 1
        val samples = pcmData.size / (bytesPerSample * channels)
        return (samples * 1000L) / sampleRate
    }

    /**
     * NUEVO: Crear archivo WAV desde datos PCM
     */
    private fun createWavFileFromPcm(pcmData: ByteArray, outputFile: File) {
        try {
            FileOutputStream(outputFile).use { output ->
                val header = createWavHeader(pcmData.size, 16000, 1, 16)
                output.write(header)
                output.write(pcmData)
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error creating WAV file: ${e.message}" }
        }
    }
    // ========== MÉTODOS PARA GRABACIÓN Y TRADUCCIÓN ==========

//    /**
//     * Iniciar grabación específica para traducción (usa tu sistema existente)
//     */
//    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
//    private fun startRecordingForTranslation(): Boolean {
//        if (isRecordingForTranslation) {
//            log.w(TAG) { "Already recording for translation" }
//            return false
//        }
//
//        return try {
//            currentTranslationRecordingPath =
//                audioFileManager.createRecordingPath("translation_audio")
//            translationAudioRecorder = WavRecorder(currentTranslationRecordingPath!!)
//
//            if (translationAudioRecorder?.startRecording(scope) == true) {
//                isRecordingForTranslation = true
//                log.d(TAG) { "Started recording for translation: $currentTranslationRecordingPath" }
//
//                // Iniciar procesamiento periódico del audio grabado
//                startTranslationProcessing()
//
//                true
//            } else {
//                translationAudioRecorder = null
//                currentTranslationRecordingPath = null
//                false
//            }
//        } catch (e: Exception) {
//            log.e(TAG) { "Error starting translation recording: ${e.message}" }
//            false
//        }
//    }

    /**
     * Detener grabación para traducción
     */
    private fun stopRecordingForTranslation(): String? {
        if (!isRecordingForTranslation) {
            return null
        }

        return try {
            // Detener procesamiento
            translationProcessingJob?.cancel()
            translationProcessingJob = null

            // Detener grabación
            translationAudioRecorder?.stopRecording()
            translationAudioRecorder = null
            isRecordingForTranslation = false

            val recordingPath = currentTranslationRecordingPath
            currentTranslationRecordingPath = null

            log.d(TAG) { "Stopped recording for translation: $recordingPath" }
            recordingPath
        } catch (e: Exception) {
            log.e(TAG) { "Error stopping translation recording: ${e.message}" }
            null
        }
    }

//    /**
//     * Iniciar procesamiento periódico para traducción
//     */
//    private fun startTranslationProcessing() {
//        translationProcessingJob?.cancel()
//        translationProcessingJob = scope.launch(Dispatchers.IO) {
//            while (isActive && isTranslationEnabled && isRecordingForTranslation) {
//                try {
//                    delay(translationIntervalMs) // Esperar intervalo de traducción
//
//                    // Leer el audio grabado hasta ahora
//                    currentTranslationRecordingPath?.let { path ->
//                        val audioData = readAudioFileForTranslation(path)
//                        if (audioData != null && audioData.isNotEmpty()) {
//                            // Enviar a OpenAI para traducción
//                            openAIManager?.processAudioForTranslation(audioData)
//                        }
//                    }
//
//                } catch (e: Exception) {
//                    log.e(TAG) { "Error in translation processing: ${e.message}" }
//                    delay(1000) // Esperar antes de reintentar
//                }
//            }
//        }
//    }

    /**
     * Leer archivo de audio para traducción
     */
    private fun readAudioFileForTranslation(filePath: String): ByteArray? {
        return try {
            val file = File(filePath)
            if (!file.exists()) {
                log.w(TAG) { "Translation audio file does not exist: $filePath" }
                return null
            }

            // Leer solo los datos nuevos desde la última traducción
            val fileSize = file.length()
            if (fileSize <= 44) { // Solo header WAV
                return null
            }

            // Leer el archivo completo (en una implementación más sofisticada,
            // podrías leer solo la parte nueva)
            val audioBytes = file.readBytes()

            // Saltar el header WAV (44 bytes) y devolver solo los datos PCM
            if (audioBytes.size > 44) {
                audioBytes.copyOfRange(44, audioBytes.size)
            } else {
                null
            }

        } catch (e: Exception) {
            log.e(TAG) { "Error reading audio file for translation: ${e.message}" }
            null
        }
    }

//    /**
//     * Reproducir audio traducido (modificado para usar tu sistema)
//     */
//    private fun playTranslatedAudio(translatedAudio: ByteArray) {
//        try {
//            // Crear archivo temporal para el audio traducido
//            val tempFile = File.createTempFile("translated_audio", ".wav", context.cacheDir)
//
//            // Crear header WAV para el audio traducido
//            val wavFile = createWavFile(translatedAudio, tempFile)
//
//            // CRÍTICO: Detener el audio original y reproducir el traducido
//            if (isPlayingOutputFile) {
//                stopPlayingOutputAudioFile()
//            }
//
//            // Reproducir el audio traducido usando tu sistema existente
//            if (startPlayingOutputAudioFile(wavFile.absolutePath, false)) {
//                log.d(TAG) { "Playing translated audio: ${translatedAudio.size} bytes" }
//            } else {
//                log.e(TAG) { "Failed to play translated audio" }
//            }
//
//        } catch (e: Exception) {
//            log.e(TAG) { "Error playing translated audio: ${e.message}" }
//        }
//    }

    /**
     * Crear archivo WAV con header correcto
     */
    private fun createWavFile(pcmData: ByteArray, outputFile: File): File {
        try {
            FileOutputStream(outputFile).use { output ->
                // Escribir header WAV
                val header = createWavHeader(pcmData.size, 16000, 1, 16)
                output.write(header)

                // Escribir datos PCM
                output.write(pcmData)
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error creating WAV file: ${e.message}" }
        }

        return outputFile
    }

    /**
     * Crear header WAV
     */
    private fun createWavHeader(
        dataSize: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ): ByteArray {
        val header = ByteArray(44)
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        // RIFF header
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()

        // File size
        val fileSize = dataSize + 36
        header[4] = (fileSize and 0xff).toByte()
        header[5] = ((fileSize shr 8) and 0xff).toByte()
        header[6] = ((fileSize shr 16) and 0xff).toByte()
        header[7] = ((fileSize shr 24) and 0xff).toByte()

        // WAVE header
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        // fmt chunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()

        // fmt chunk size (16)
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0

        // Audio format (PCM = 1)
        header[20] = 1
        header[21] = 0

        // Number of channels
        header[22] = channels.toByte()
        header[23] = 0

        // Sample rate
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()

        // Byte rate
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()

        // Block align
        header[32] = blockAlign.toByte()
        header[33] = 0

        // Bits per sample
        header[34] = bitsPerSample.toByte()
        header[35] = 0

        // data chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()

        // Data size
        header[40] = (dataSize and 0xff).toByte()
        header[41] = ((dataSize shr 8) and 0xff).toByte()
        header[42] = ((dataSize shr 16) and 0xff).toByte()
        header[43] = ((dataSize shr 24) and 0xff).toByte()

        return header
    }

    /**
     * NUEVO: Stop recording sent audio
     */
    override fun stopRecordingSentAudio(): String? {
        isRecordingSent.set(false)
        Log.d(TAG, "Stopped recording sent audio")
        return null
    }

    /**
     * Modificar startRecordingReceivedAudio para también iniciar traducción si está habilitada
     */
    override fun startRecordingReceivedAudio(): Boolean {
        isRecordingReceived.set(true)
        Log.d(TAG, "Started recording received audio")
        return true
    }


//    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
//    override fun startRecordingReceivedAudio(): Boolean {
//        if (isRecordingReceivedAudio) {
//            log.w(TAG) { "Already recording received audio" }
//            return false
//        }
//
//        return try {
//            currentReceivedRecordingPath = audioFileManager.createRecordingPath("received_audio")
//            // MODIFICADO: Crear grabador personalizado que también intercepte para traducción
//            receivedAudioRecorder = object : WavRecorder(currentReceivedRecordingPath!!) {
//
//                override fun onAudioDataReceived(audioData: ByteArray) {
//                    // Llamar al método padre para grabación normal
//                    super.onAudioDataReceived(audioData)
//
//                    // NUEVO: También interceptar para traducción
//                    interceptRemoteAudioForTranslation(audioData)
//                }
//            }
//            if (receivedAudioRecorder?.startRecording(coroutineScope) == true) {
//                isRecordingReceivedAudio = true
//                log.d(TAG) { "Started recording received audio: $currentReceivedRecordingPath" }
//
//                // NUEVO: Si la traducción está habilitada, iniciar procesamiento
//                if (isTranslationEnabled) {
//                    startRemoteAudioTranslationProcessing()
//                }
//
//                true
//            } else {
//                receivedAudioRecorder = null
//                currentReceivedRecordingPath = null
//                false
//            }
//        } catch (e: Exception) {
//            log.e(TAG) { "Error starting received audio recording: ${e.message}" }
//            false
//        }
//    }

    /**
     * Modificar stopRecordingReceivedAudio para también detener traducción
     */
    override fun stopRecordingReceivedAudio(): String? {
        isRecordingReceived.set(false)
        Log.d(TAG, "Stopped recording received audio")
        return null
    }

    /**
     * NUEVO: Start playing audio file instead of microphone input
     */

    override fun startPlayingInputAudioFile(filePath: String, loop: Boolean): Boolean {
        currentInputAudioFile = filePath
        isPlayingInputFile.set(true)
        Log.d(TAG, "Started playing input audio file: $filePath")
        return true
    }

    /**
     * NUEVO: Stop playing audio file and return to microphone input
     */
    override fun stopPlayingInputAudioFile(): Boolean {
        currentInputAudioFile = null
        isPlayingInputFile.set(false)
        Log.d(TAG, "Stopped playing input audio file")
        return true
    }

    /**
     * NUEVO: Start playing audio file instead of received audio
     */
    override fun startPlayingOutputAudioFile(filePath: String, loop: Boolean): Boolean {
        currentOutputAudioFile = filePath
        isPlayingOutputFile.set(true)
        Log.d(TAG, "Started playing output audio file: $filePath")
        return true
    }

    /**
     * NUEVO: Stop playing audio file and return to received audio
     */
    override fun stopPlayingOutputAudioFile(): Boolean {
        currentOutputAudioFile = null
        isPlayingOutputFile.set(false)
        Log.d(TAG, "Stopped playing output audio file")
        return true
    }

    /**
     * NUEVO: Get list of recorded audio files
     */
    override fun getRecordedAudioFiles(): List<File> = recordedFiles.toList()


    /**
     * NUEVO: Delete recorded audio file
     */
    override fun deleteRecordedAudioFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            val deleted = file.delete()
            if (deleted) {
                recordedFiles.removeAll { it.absolutePath == filePath }
            }
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting audio file", e)
            false
        }
    }

    /**
     * NUEVO: Get audio file duration
     */
    override fun getAudioFileDuration(filePath: String): Long {
        return try {
            val file = File(filePath)
            if (file.exists()) {
                // Estimación básica basada en tamaño de archivo
                val sizeInBytes = file.length()
                val bytesPerSecond = SAMPLE_RATE * 2 // 16-bit = 2 bytes per sample
                (sizeInBytes / bytesPerSecond * 1000).toLong()
            } else {
                0L
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting audio file duration", e)
            0L
        }
    }

    override fun isRecordingSentAudio(): Boolean = isRecordingSent.get()
    override fun isRecordingReceivedAudio(): Boolean = isRecordingReceived.get()
    override fun isPlayingInputAudioFile(): Boolean = isPlayingInputFile.get()
    override fun isPlayingOutputAudioFile(): Boolean = isPlayingOutputFile.get()
    override fun getCurrentInputAudioFilePath(): String? = currentInputAudioFile
    override fun getCurrentOutputAudioFilePath(): String? = currentOutputAudioFile
    private fun generateRandomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..length)
            .map { chars.random() }
            .joinToString("")
    }

    private fun generateRandomNumber(): Long {
        return (100000000L..999999999L).random()
    }

    private fun generateFingerprint(): String {
        return (1..32)
            .map { "0123456789ABCDEF".random() }
            .joinToString("")
            .chunked(2)
            .joinToString(":")
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

    /**
     * FIXED: Handle Bluetooth SCO state changes
     */
    private fun handleBluetoothScoStateChange(state: Int) {
        when (state) {
            AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                log.d(TAG) { "Bluetooth SCO connected" }
                isBluetoothScoRequested = false
                // Notify success if we were trying to connect
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

    /**
     * Initialize Bluetooth components for enhanced device detection
     */
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

    /**
     * Setup audio device monitoring for real-time changes
     */
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

    /**
     * Notify listeners about device changes
     */
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
    override fun initialize() {
        if (isInitialized.get()) {
            Log.d(TAG, "Already initialized")
            return
        }

        try {
            audioManager = application.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            setupAudioDevices()
            isInitialized.set(true)
            Log.d(TAG, "AndroidWebRtcManager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AndroidWebRtcManager", e)
            throw e
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
            val result = audioManager?.requestAudioFocus(focusRequest)
                ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
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
    @RequiresPermission(
        allOf = [
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.RECORD_AUDIO
        ]
    )
    override fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        return Pair(availableInputDevices.toList(), availableOutputDevices.toList())
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
     * Check if Bluetooth device is connected - FIXED VERSION
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun isBluetoothDeviceConnected(device: BluetoothDevice): Boolean {
        return try {
            // Method 1: Using BluetoothManager (API 18+)
            val bluetoothManager =
                context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val connectionState =
                bluetoothManager?.getConnectionState(device, BluetoothProfile.HEADSET)

            when (connectionState) {
                BluetoothProfile.STATE_CONNECTED -> return true
                BluetoothProfile.STATE_CONNECTING -> return false
                BluetoothProfile.STATE_DISCONNECTED -> return false
                BluetoothProfile.STATE_DISCONNECTING -> return false
                else -> {
                    // Method 2: Alternative check using reflection (fallback)
                    return checkBluetoothConnectionViaReflection(device)
                }
            }
        } catch (e: Exception) {
            log.w(TAG) { "Error checking Bluetooth connection state: ${e.message}" }
            // Method 3: Fallback - assume bonded devices are connected if Bluetooth audio is available
            device.bondState == BluetoothDevice.BOND_BONDED &&
                    audioManager?.isBluetoothScoAvailableOffCall == true
        }
    }

    /**
     * Alternative method using BluetoothProfile service listener (more reliable)
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun checkBluetoothConnectionWithProfile(
        device: BluetoothDevice,
        callback: (Boolean) -> Unit
    ) {
        val profileListener = object : BluetoothProfile.ServiceListener {
            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                try {
                    val isConnected = when (profile) {
                        BluetoothProfile.HEADSET -> {
                            val headsetProxy = proxy as? BluetoothHeadset
                            headsetProxy?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
                        }

                        BluetoothProfile.A2DP -> {
                            val a2dpProxy = proxy as? BluetoothA2dp
                            a2dpProxy?.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED
                        }

                        else -> false
                    }
                    callback(isConnected)
                    bluetoothAdapter?.closeProfileProxy(profile, proxy)
                } catch (e: Exception) {
                    log.e(TAG) { "Error in profile service connected: ${e.message}" }
                    callback(false)
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                callback(false)
            }
        }

        // Try to get headset profile first, then A2DP
        val headsetConnected = bluetoothAdapter?.getProfileProxy(
            context,
            profileListener,
            BluetoothProfile.HEADSET
        ) ?: false

        if (!headsetConnected) {
            bluetoothAdapter?.getProfileProxy(
                context,
                profileListener,
                BluetoothProfile.A2DP
            )
        }
    }

    /**
     * Fallback method using reflection (use with caution)
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun checkBluetoothConnectionViaReflection(device: BluetoothDevice): Boolean {
        return try {
            val method = device.javaClass.getMethod("isConnected")
            method.invoke(device) as Boolean
        } catch (e: Exception) {
            log.w(TAG) { "Reflection method failed: ${e.message}" }
            // Final fallback
            device.bondState == BluetoothDevice.BOND_BONDED
        }
    }

    /**
     * Enhanced method to get all connected Bluetooth devices with proper state checking
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun getConnectedBluetoothDevicesEnhanced(): List<BluetoothDevice> {
        val connectedDevices = mutableListOf<BluetoothDevice>()

        try {
            // Get bonded devices first
            val bondedDevices = bluetoothAdapter?.bondedDevices ?: emptySet()

            // Filter for audio-capable devices
            val audioDevices = bondedDevices.filter { device ->
                device.bluetoothClass?.hasService(BluetoothClass.Service.AUDIO) == true ||
                        device.bluetoothClass?.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES ||
                        device.bluetoothClass?.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET ||
                        device.bluetoothClass?.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE ||
                        device.bluetoothClass?.deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO
            }

            // Check connection state for each audio device
            audioDevices.forEach { device ->
                if (isBluetoothDeviceConnected(device)) {
                    connectedDevices.add(device)
                }
            }

            log.d(TAG) { "Found ${connectedDevices.size} connected Bluetooth audio devices" }

        } catch (e: SecurityException) {
            log.w(TAG) { "Bluetooth permission not granted for getting connected devices" }
        } catch (e: Exception) {
            log.e(TAG) { "Error getting connected Bluetooth devices: ${e.message}" }
        }

        return connectedDevices
    }

    /**
     * Simplified synchronous check for immediate use
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun isBluetoothAudioDeviceConnected(): Boolean {
        return try {
            // Quick check using AudioManager
            audioManager?.isBluetoothScoOn == true ||
                    audioManager?.isBluetoothA2dpOn == true ||
                    (audioManager?.isBluetoothScoAvailableOffCall == true && hasConnectedBluetoothAudioDevice())
        } catch (e: Exception) {
            log.w(TAG) { "Error checking Bluetooth audio connection: ${e.message}" }
            false
        }
    }

    /**
     * Check if there's any connected Bluetooth audio device
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun hasConnectedBluetoothAudioDevice(): Boolean {
        return try {
            bluetoothAdapter?.bondedDevices?.any { device ->
                device.bluetoothClass?.hasService(BluetoothClass.Service.AUDIO) == true &&
                        device.bondState == BluetoothDevice.BOND_BONDED
            } ?: false
        } catch (e: Exception) {
            false
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
            bluetoothAdapter?.getProfileConnectionState(profile) == BluetoothAdapter.STATE_CONNECTED
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
                val getBatteryLevelMethod =
                    BluetoothDevice::class.java.getMethod("getBatteryLevel")
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
     * FIXED: Enhanced device change during call with improved error handling
     */

    override fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean {
        currentOutputDevice = device
        Log.d(TAG, "Changed output device to: ${device.name}")
        return true
    }
    /**
     * Enhanced device change for input during call
     */

    override fun changeAudioInputDeviceDuringCall(device: AudioDevice): Boolean {
        currentInputDevice = device
        Log.d(TAG, "Changed input device to: ${device.name}")
        return true
    }


    // Audio switching methods

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
    @RequiresPermission(
        allOf = [
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.RECORD_AUDIO
        ]
    )
    override fun getCurrentInputDevice(): AudioDevice? = currentInputDevice


    @RequiresPermission(
        allOf = [
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.RECORD_AUDIO
        ]
    )
    override fun getCurrentOutputDevice(): AudioDevice? = currentOutputDevice


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

    override fun dispose() {
        Log.d(TAG, "Disposing AndroidWebRtcManager")

        // Detener traducción
        disableAudioTranslation()

        // Detener procesamiento de audio
        stopAudioProcessing()

        // Limpiar recursos de audio
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null

        isInitialized.set(false)
        Log.d(TAG, "AndroidWebRtcManager disposed")
    }

    /**
     * Create an SDP offer for starting a call
     * @return The SDP offer string
     */
    override suspend fun createOffer(): String {
        // Implementación básica de SDP offer
        return """v=0
o=- ${System.currentTimeMillis()} 2 IN IP4 127.0.0.1
s=-
t=0 0
a=group:BUNDLE 0
a=msid-semantic: WMS
m=audio 9 UDP/TLS/RTP/SAVPF 111 103 104 9 0 8 106 105 13 110 112 113 126
c=IN IP4 0.0.0.0
a=rtcp:9 IN IP4 0.0.0.0
a=ice-ufrag:${generateRandomString(4)}
a=ice-pwd:${generateRandomString(22)}
a=ice-options:trickle
a=fingerprint:sha-256 ${generateFingerprint()}
a=setup:actpass
a=mid:0
a=extmap:1 urn:ietf:params:rtp-hdrext:ssrc-audio-level
a=extmap:2 http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time
a=extmap:3 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01
a=extmap:4 urn:ietf:params:rtp-hdrext:sdes:mid
a=extmap:5 urn:ietf:params:rtp-hdrext:sdes:rtp-stream-id
a=extmap:6 urn:ietf:params:rtp-hdrext:sdes:repaired-rtp-stream-id
a=sendrecv
a=msid:- ${generateRandomString(36)}
a=rtcp-mux
a=rtpmap:111 opus/48000/2
a=rtcp-fb:111 transport-cc
a=fmtp:111 minptime=10;useinbandfec=1
a=rtpmap:103 ISAC/16000
a=rtpmap:104 ISAC/32000
a=rtpmap:9 G722/8000
a=rtpmap:0 PCMU/8000
a=rtpmap:8 PCMA/8000
a=rtpmap:106 CN/32000
a=rtpmap:105 CN/16000
a=rtpmap:13 CN/8000
a=rtpmap:110 telephone-event/48000
a=rtpmap:112 telephone-event/32000
a=rtpmap:113 telephone-event/16000
a=rtpmap:126 telephone-event/8000
a=ssrc:${generateRandomNumber()} cname:${generateRandomString(16)}
a=ssrc:${generateRandomNumber()} msid:- ${generateRandomString(36)}
a=ssrc:${generateRandomNumber()} mslabel:-
a=ssrc:${generateRandomNumber()} label:${generateRandomString(36)}
"""
    }
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
//            peerConnection
//                ?: throw IllegalStateException("PeerConnection initialization failed")
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

    /**
     * Create an SDP answer in response to an offer
     * @param accountInfo The current account information
     * @param offerSdp The SDP offer from the remote party
     * @return The SDP answer string
     */
    override suspend fun createAnswer(accountInfo: AccountInfo, offerSdp: String): String {
        // Implementación básica de SDP answer
        return """v=0
o=- ${System.currentTimeMillis()} 2 IN IP4 127.0.0.1
s=-
t=0 0
a=group:BUNDLE 0
a=msid-semantic: WMS
m=audio 9 UDP/TLS/RTP/SAVPF 111 103 104 9 0 8 106 105 13 110 112 113 126
c=IN IP4 0.0.0.0
a=rtcp:9 IN IP4 0.0.0.0
a=ice-ufrag:${generateRandomString(4)}
a=ice-pwd:${generateRandomString(22)}
a=ice-options:trickle
a=fingerprint:sha-256 ${generateFingerprint()}
a=setup:active
a=mid:0
a=extmap:1 urn:ietf:params:rtp-hdrext:ssrc-audio-level
a=extmap:2 http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time
a=extmap:3 http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01
a=extmap:4 urn:ietf:params:rtp-hdrext:sdes:mid
a=extmap:5 urn:ietf:params:rtp-hdrext:sdes:rtp-stream-id
a=extmap:6 urn:ietf:params:rtp-hdrext:sdes:repaired-rtp-stream-id
a=sendrecv
a=msid:- ${generateRandomString(36)}
a=rtcp-mux
a=rtpmap:111 opus/48000/2
a=rtcp-fb:111 transport-cc
a=fmtp:111 minptime=10;useinbandfec=1
a=rtpmap:103 ISAC/16000
a=rtpmap:104 ISAC/32000
a=rtpmap:9 G722/8000
a=rtpmap:0 PCMU/8000
a=rtpmap:8 PCMA/8000
a=rtpmap:106 CN/32000
a=rtpmap:105 CN/16000
a=rtpmap:13 CN/8000
a=rtpmap:110 telephone-event/48000
a=rtpmap:112 telephone-event/32000
a=rtpmap:113 telephone-event/16000
a=rtpmap:126 telephone-event/8000
a=ssrc:${generateRandomNumber()} cname:${generateRandomString(16)}
a=ssrc:${generateRandomNumber()} msid:- ${generateRandomString(36)}
a=ssrc:${generateRandomNumber()} mslabel:-
a=ssrc:${generateRandomNumber()} label:${generateRandomString(36)}
"""
    }
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
//            peerConnection
//                ?: throw IllegalStateException("PeerConnection initialization failed")
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

    /**
     * Set the remote description (offer or answer)
     * @param sdp The remote SDP string
     * @param type The SDP type (offer or answer)
     */
    override suspend fun setRemoteDescription(sdp: String, type: SdpType) {
        Log.d(TAG, "Setting remote description: $type")

        // CRÍTICO: Iniciar procesamiento de audio solo después de establecer conexión
        if (type == SdpType.ANSWER || type == SdpType.OFFER) {
            startAudioProcessing()
        }
    }

    /**
     * Add an ICE candidate received from the remote party
     * @param candidate The ICE candidate string
     * @param sdpMid The media ID
     * @param sdpMLineIndex The media line index
     */
    override suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?) {
        Log.d(TAG, "Adding ICE candidate: $candidate")
    }

    /**
     * Sets the mute state for the local microphone
     * @param muted Whether the microphone should be muted
     */
    override fun setMuted(muted: Boolean) {
        isMuted.set(muted)
        Log.d(TAG, "Muted: $muted")
    }

    /**
     * Gets the current mute state of the microphone
     * @return true if muted, false otherwise
     */
    override fun isMuted(): Boolean = isMuted.get()


    /**
     * Gets the local SDP description
     * @return The local SDP string, or null if not set
     */
    override fun getLocalDescription(): String? {
        return "Local SDP description"
    }

    /**
     * Sets the media direction (sendrecv, sendonly, recvonly, inactive)
     * @param direction The desired media direction
     */
    override suspend fun setMediaDirection(direction: WebRtcManager.MediaDirection) {
        Log.d(TAG, "Setting media direction: $direction")
    }

    /**
     * Modifies the SDP to update the media direction attribute
     */
    private fun updateSdpDirection(
        sdp: String,
        direction: WebRtcManager.MediaDirection
    ): String {
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

    /**
     * Applies modified SDP to the peer connection
     * @param modifiedSdp The modified SDP string
     * @return true if successful, false otherwise
     */
    override suspend fun applyModifiedSdp(modifiedSdp: String): Boolean {
        Log.d(TAG, "Applying modified SDP")
        return true
    }

    /**
     * Extension function to check if a device has an earpiece
     * Most phones have an earpiece, but tablets and some devices don't
     */
    private fun AudioManager.hasEarpiece(): Boolean {
        // This is a heuristic approach - we can't directly detect earpiece
        // Most phones support MODE_IN_COMMUNICATION, tablets typically don't
        return context.packageManager?.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
            ?: false
    }

    /**
     * Extension function to check if a Bluetooth device is connected (Android S+ only)
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    @RequiresApi(Build.VERSION_CODES.S)
    private fun BluetoothDevice.isConnected(): Boolean {
        val bluetoothManager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
        return connectedDevices.contains(this)
    }

    /**
     * Enable or disable the local audio track
     * @param enabled Whether audio should be enabled
     */
    override fun setAudioEnabled(enabled: Boolean) {
        isAudioEnabled.set(enabled)
        Log.d(TAG, "Audio enabled: $enabled")

        if (enabled) {
            startAudioProcessing()
        } else {
            stopAudioProcessing()
        }
    }

    private fun stopAudioProcessing() {
        Log.d(TAG, "Stopping audio processing")

        isProcessingAudio.set(false)
        audioProcessingJob?.cancel()
        audioProcessingJob = null

        audioRecord?.stop()
        audioTrack?.stop()
    }
    /**
     * NUEVO: Procesar audio específicamente para traducción
     */
    private fun processAudioForTranslation(audioData: ByteArray) {
        if (!isTranslationEnabled.get() || isPlayingTranslatedAudio.get()) {
            return
        }

        scope.launch {
            try {
                // Convertir a formato requerido por OpenAI (16kHz)
                val convertedAudio = if (SAMPLE_RATE != 16000) {
                    resampleAudio(audioData, SAMPLE_RATE, 16000)
                } else {
                    audioData
                }

                // Enviar a OpenAI para traducción
                openAIManager?.processAudioForTranslation(convertedAudio)

            } catch (e: Exception) {
                Log.e(TAG, "Error processing audio for translation", e)
            }
        }
    }
    /**
     * Get current connection state
     * @return The connection state
     */
    override fun getConnectionState(): WebRtcConnectionState {
        return if (isInitialized.get()) {
            WebRtcConnectionState.CONNECTED
        } else {
            WebRtcConnectionState.NEW
        }
    }
    @SuppressLint("MissingPermission")
    private fun startAudioProcessing() {
        if (isProcessingAudio.get()) {
            Log.d(TAG, "Audio processing already active")
            return
        }

        if (!isAudioEnabled.get()) {
            Log.d(TAG, "Audio not enabled, skipping processing")
            return
        }

        try {
            Log.d(TAG, "Starting audio processing with anti-loop protection")

            setupAudioRecord()
            setupAudioTrack()

            isProcessingAudio.set(true)

            // Iniciar captura de audio (solo para traducción, NO para reproducción local)
            audioProcessingJob = scope.launch {
                processAudioLoop()
            }

            Log.d(TAG, "Audio processing started successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio processing", e)
            isProcessingAudio.set(false)
        }
    }
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun setupAudioRecord() {
        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT
            ) * BUFFER_SIZE_FACTOR

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // Optimizado para llamadas
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw RuntimeException("AudioRecord initialization failed")
            }

            Log.d(TAG, "AudioRecord setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up AudioRecord", e)
            throw e
        }
    }

    private fun setupAudioTrack() {
        try {
            val bufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG_OUT,
                AUDIO_FORMAT
            ) * BUFFER_SIZE_FACTOR

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AUDIO_FORMAT)
                        .setChannelMask(CHANNEL_CONFIG_OUT)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            Log.d(TAG, "AudioTrack setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up AudioTrack", e)
            throw e
        }
    }

    private suspend fun processAudioLoop() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG_IN,
            AUDIO_FORMAT
        ) * BUFFER_SIZE_FACTOR

        val audioBuffer = ByteArray(bufferSize)

        try {
            audioRecord?.startRecording()
            Log.d(TAG, "Audio recording started")

            while (isActive && isProcessingAudio.get()) {
                if (isMuted.get() || isPlayingTranslatedAudio.get()) {
                    // CRÍTICO: No capturar audio mientras reproducimos traducción
                    delay(50)
                    continue
                }

                val bytesRead = audioRecord?.read(audioBuffer, 0, bufferSize) ?: 0

                if (bytesRead > 0) {
                    // SOLO enviar a traducción si está habilitada
                    if (isTranslationEnabled.get() && !isPlayingTranslatedAudio.get()) {
                        val currentTime = System.currentTimeMillis()

                        // Control de frecuencia de traducción
                        if (currentTime - lastTranslationTime >= MIN_TRANSLATION_INTERVAL) {
                            lastTranslationTime = currentTime

                            // Procesar para traducción
                            val audioToTranslate = audioBuffer.copyOf(bytesRead)
                            processAudioForTranslation(audioToTranslate)
                        }
                    }
                }

                delay(20) // 20ms delay para 50fps
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in audio processing loop", e)
        } finally {
            audioRecord?.stop()
            Log.d(TAG, "Audio recording stopped")
        }
    }

    /**
     * Set a listener for WebRTC events
     * @param listener The WebRTC event listener
     */
    override fun setListener(listener: Any?) {
        this.listener = listener as? WebRtcEventListener
        Log.d(TAG, "WebRTC listener set")
    }

    override fun prepareAudioForIncomingCall() {
        Log.d(TAG, "Preparing audio for incoming call")
        setupAudioDevices()
    }
    private fun setupAudioDevices() {
        try {
            availableInputDevices.clear()
            availableOutputDevices.clear()

            // Dispositivo de entrada por defecto (micrófono)
            val defaultInput = AudioDevice(
                name = "Micrófono",
                descriptor = "default_microphone",
                isOutput = false,
                audioUnit = AudioUnit(
                    type = AudioUnitTypes.MICROPHONE,
                    capability = AudioUnitCompatibilities.RECORD,
                    isCurrent = true,
                    isDefault = true
                )
            )
            availableInputDevices.add(defaultInput)
            currentInputDevice = defaultInput

            // Dispositivo de salida por defecto (auricular)
            val defaultOutput = AudioDevice(
                name = "Auricular",
                descriptor = "default_earpiece",
                isOutput = true,
                audioUnit = AudioUnit(
                    type = AudioUnitTypes.EARPIECE,
                    capability = AudioUnitCompatibilities.PLAY,
                    isCurrent = true,
                    isDefault = true
                )
            )
            availableOutputDevices.add(defaultOutput)
            currentOutputDevice = defaultOutput

            Log.d(TAG, "Audio devices setup completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up audio devices", e)
        }
    }

    /**
     * Enhanced audio diagnostics
     */
    @SuppressLint("MissingPermission")
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

            // NUEVO: Audio recording and playback state
            appendLine("\n--- Audio Recording/Playback ---")
            appendLine("Recording Sent Audio: $isRecordingSentAudio")
            appendLine("Recording Received Audio: $isRecordingReceivedAudio")
            appendLine("Playing Input File: $isPlayingInputFile")
            appendLine("Playing Output File: $isPlayingOutputFile")
            appendLine("Current Input File: $inputAudioFilePath")
            appendLine("Current Output File: $outputAudioFilePath")

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

            // NUEVO: Recorded files info
            appendLine("\n--- Recorded Files ---")
            try {
                val recordedFiles = getRecordedAudioFiles()
                appendLine("Total Recorded Files: ${recordedFiles.size}")
                recordedFiles.take(5).forEach { file ->
                    val duration = getAudioFileDuration(file.absolutePath)
                    appendLine("  - ${file.name} (${duration}ms)")
                }
            } catch (e: Exception) {
                appendLine("Error getting recorded files: ${e.message}")
            }

            // Permission status
            appendLine("\n--- Permissions ---")
            appendLine("Record Audio: ${hasPermission(Manifest.permission.RECORD_AUDIO)}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                appendLine("Bluetooth Connect: ${hasPermission(Manifest.permission.BLUETOOTH_CONNECT)}")
            }
        }
    }

    override fun isInitialized(): Boolean = isInitialized.get()

    /**
     * Send DTMF tones via RTP (RFC 4733)
     * @param tones The DTMF tones to send (0-9, *, #, A-D)
     * @param duration Duration in milliseconds for each tone (optional, default 100ms)
     * @param gap Gap between tones in milliseconds (optional, default 70ms)
     * @return true if successfully started sending tones, false otherwise
     */
    override fun sendDtmfTones(tones: String, duration: Int, gap: Int): Boolean {
        Log.d(TAG, "Sending DTMF tones: $tones")
        return true
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
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
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
//            isInitialized.get() = false
//            isLocalAudioReady = false
//        }
//    }

    /**
     * NUEVO: Iniciar captura de audio remoto para traducción
     */
    private fun startCapturingRemoteAudio(audioTrack: AudioStreamTrack) {
        scope.launch(Dispatchers.IO) {
            try {
                // Aquí necesitarías interceptar el audio del track remoto
                // Esto es una aproximación - la implementación exacta depende de la librería WebRTC
                log.d(TAG) { "Started capturing remote audio for translation" }

                // NOTA: La captura real del audio remoto requiere acceso a los datos del AudioTrack
                // Esto podría requerir modificaciones en la capa nativa de WebRTC
                // Por ahora, usamos un enfoque alternativo capturando desde el output

            } catch (e: Exception) {
                log.e(TAG) { "Error starting remote audio capture: ${e.message}" }
            }
        }
    }

    /**
     * Modificar setupPeerConnectionObservers para iniciar grabación automáticamente
     */
    @SuppressLint("MissingPermission")
    private fun PeerConnection.setupPeerConnectionObservers() {

        onTrack.onEach { event ->
            log.d(TAG) { "Remote track received: $event" }
            val track = event.receiver.track

            if (track is AudioStreamTrack) {
                log.d(TAG) { "Remote audio track established" }
                remoteAudioTrack = track
                remoteAudioTrack?.enabled = true

                // NUEVO: Si la traducción está habilitada, iniciar captura de audio remoto
                if (isTranslationEnabled.get()) {
                    startCapturingRemoteAudio(track)
                }

                webRtcEventListener?.onRemoteAudioTrack()
            }
        }.launchIn(scope)

        onIceCandidate.onEach { candidate ->
            log.d(TAG) { "New ICE Candidate: ${candidate.candidate}" }
            webRtcEventListener?.onIceCandidate(
                candidate.candidate,
                candidate.sdpMid,
                candidate.sdpMLineIndex
            )
        }.launchIn(scope)

        onConnectionStateChange.onEach { state ->
            log.d(TAG) { "Connection state changed: $state" }
            when (state) {
                PeerConnectionState.Connected -> {
                    log.d(TAG) { "Call active: Connected" }
                    CallStateManager.updateCallState(CallState.CONNECTED)
                    setAudioEnabled(true)
                    audioManager?.isMicrophoneMute = false

                    // NUEVO: Iniciar grabación automáticamente si la traducción está habilitada
                    if (isTranslationEnabled.get() && !isRecordingReceivedAudio) {
                        log.d(TAG) { "Auto-starting audio recording for translation" }
                        startRecordingReceivedAudio()
                    }
                }

                PeerConnectionState.Disconnected,
                PeerConnectionState.Failed,
                PeerConnectionState.Closed -> {
                    CallStateManager.updateCallState(CallState.ENDED)
                    log.d(TAG) { "Call ended" }
                    releaseAudioFocus()

                    // Detener todas las grabaciones y reproducciones
                    if (isRecordingSentAudio) stopRecordingSentAudio()
                    if (isRecordingReceivedAudio) stopRecordingReceivedAudio()
                    if (isPlayingInputFile.get()) stopPlayingInputAudioFile()
                    if (isPlayingOutputFile.get()) stopPlayingOutputAudioFile()

                    // NUEVO: Detener traducción
                    if (isRecordingForTranslation) {
                        stopRecordingForTranslation()
                    }
                }

                else -> {
                    log.d(TAG) { "Other connection state: $state" }
                }
            }
            webRtcEventListener?.onConnectionStateChange(mapConnectionState(state))
        }.launchIn(scope)

        onTrack.onEach { event ->
            log.d(TAG) { "Remote track received: $event" }
            val track = event.receiver.track

            if (track is AudioStreamTrack) {
                log.d(TAG) { "Remote audio track established" }
                remoteAudioTrack = track
                remoteAudioTrack?.enabled = true

                webRtcEventListener?.onRemoteAudioTrack()
            }
        }.launchIn(scope)
    }

    override fun isAudioTranslationEnabled(): Boolean = isTranslationEnabled.get()

    override fun getCurrentTargetLanguage(): String? = currentTargetLanguage

    override fun setTargetLanguage(targetLanguage: String): Boolean {
        if (!isTranslationEnabled.get()) return false

        currentTargetLanguage = targetLanguage
        return openAIManager?.enable(targetLanguage) ?: false
    }

    override fun getSupportedLanguages(): List<String> {
        return openAIManager?.getSupportedLanguages() ?: listOf(
            "es", "en", "fr", "de", "it", "pt", "ru", "ja", "ko", "zh",
            "ar", "hi", "th", "vi", "tr", "pl", "nl", "sv", "da", "no"
        )
    }

    override fun isTranslationProcessing(): Boolean {
        return openAIManager?.isProcessing() ?: false
    }

    override fun getTranslationStats(): TranslationStats? {
        return openAIManager?.getStats()
    }

    override fun setTranslationQuality(quality: TranslationQuality): Boolean {
        translationQuality = quality
        return openAIManager?.setTranslationQuality(quality) ?: false
    }
    /**
     * NUEVO: Configuración anti-bucle crítica
     */
    private fun setupAntiLoopAudioConfiguration() {
        try {
            Log.d(TAG, "Setting up anti-loop audio configuration")

            // CRÍTICO: Configurar AudioManager para evitar bucle
            audioManager?.let { am ->
                // Usar modo de comunicación para llamadas
                am.mode = AudioManager.MODE_IN_COMMUNICATION

                // Deshabilitar altavoz por defecto
                am.isSpeakerphoneOn = false

                // Habilitar cancelación de eco si está disponible
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                    // El sistema manejará automáticamente la cancelación de eco
                }
            }

            Log.d(TAG, "Anti-loop audio configuration completed")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up anti-loop configuration", e)
        }
    }

    /**
     * NUEVO: Manejar audio traducido con control de bucle
     */
    private fun handleTranslatedAudio(translatedAudio: ByteArray) {
        if (translatedAudio.isEmpty()) {
            Log.w(TAG, "Received empty translated audio")
            return
        }

        try {
            Log.d(TAG, "Received translated audio: ${translatedAudio.size} bytes")

            // CRÍTICO: Marcar que estamos reproduciendo audio traducido
            isPlayingTranslatedAudio.set(true)

            // Convertir audio a formato compatible si es necesario
            val compatibleAudio = convertToCompatibleFormat(translatedAudio)

            // Reproducir audio traducido inmediatamente
            playTranslatedAudioDirectly(compatibleAudio)

            // Notificar éxito
            listener?.onTranslationCompleted(true, System.currentTimeMillis() - lastTranslationTime, null, null)

        } catch (e: Exception) {
            Log.e(TAG, "Error handling translated audio", e)
            isPlayingTranslatedAudio.set(false)
            listener?.onTranslationCompleted(false, 0, null, e.message)
        }
    }

    /**
     * NUEVO: Reproducir audio traducido directamente sin interferir con el micrófono
     */
    private fun playTranslatedAudioDirectly(audioData: ByteArray) {
        scope.launch {
            try {
                Log.d(TAG, "Playing translated audio directly: ${audioData.size} bytes")

                // CRÍTICO: Crear AudioTrack dedicado para traducción
                val bufferSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG_OUT,
                    AUDIO_FORMAT
                ) * BUFFER_SIZE_FACTOR

                val translationAudioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AUDIO_FORMAT)
                            .setChannelMask(CHANNEL_CONFIG_OUT)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                translationAudioTrack.play()

                // Escribir audio en chunks para mejor rendimiento
                val chunkSize = 1024
                var offset = 0

                while (offset < audioData.size && isActive) {
                    val remainingBytes = audioData.size - offset
                    val bytesToWrite = minOf(chunkSize, remainingBytes)

                    val written = translationAudioTrack.write(
                        audioData,
                        offset,
                        bytesToWrite
                    )

                    if (written > 0) {
                        offset += written
                    } else {
                        break
                    }

                    // Pequeña pausa para evitar saturación
                    delay(10)
                }

                // Esperar a que termine la reproducción
                delay(100)

                translationAudioTrack.stop()
                translationAudioTrack.release()

                // CRÍTICO: Marcar que terminamos de reproducir
                isPlayingTranslatedAudio.set(false)

                Log.d(TAG, "Translated audio playback completed")

            } catch (e: Exception) {
                Log.e(TAG, "Error playing translated audio", e)
                isPlayingTranslatedAudio.set(false)
            }
        }
    }

    /**
     * NUEVO: Convertir audio a formato compatible
     */
    private fun convertToCompatibleFormat(audioData: ByteArray): ByteArray {
        return try {
            // Si el audio ya está en el formato correcto, devolverlo tal como está
            if (isCompatibleFormat(audioData)) {
                audioData
            } else {
                // Convertir de 16kHz a 24kHz si es necesario
                resampleAudio(audioData, 16000, SAMPLE_RATE)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error converting audio format", e)
            audioData // Devolver original en caso de error
        }
    }

    /**
     * NUEVO: Verificar si el audio está en formato compatible
     */
    private fun isCompatibleFormat(audioData: ByteArray): Boolean {
        // Verificación básica de tamaño (debe ser par para 16-bit)
        return audioData.size % 2 == 0 && audioData.isNotEmpty()
    }

    /**
     * NUEVO: Resamplear audio de una frecuencia a otra
     */
    private fun resampleAudio(audioData: ByteArray, fromSampleRate: Int, toSampleRate: Int): ByteArray {
        if (fromSampleRate == toSampleRate) return audioData

        try {
            // Convertir bytes a samples
            val samples = ByteArray(audioData.size)
            System.arraycopy(audioData, 0, samples, 0, audioData.size)

            // Calcular ratio de resampling
            val ratio = toSampleRate.toDouble() / fromSampleRate.toDouble()
            val outputSize = (audioData.size * ratio).toInt()
            val outputData = ByteArray(outputSize)

            // Resampling simple (interpolación lineal)
            for (i in 0 until outputSize step 2) {
                val sourceIndex = (i / ratio).toInt()
                if (sourceIndex < audioData.size - 1) {
                    outputData[i] = audioData[sourceIndex]
                    outputData[i + 1] = if (sourceIndex + 1 < audioData.size) audioData[sourceIndex + 1] else 0
                }
            }

            return outputData
        } catch (e: Exception) {
            Log.e(TAG, "Error resampling audio", e)
            return audioData
        }
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
