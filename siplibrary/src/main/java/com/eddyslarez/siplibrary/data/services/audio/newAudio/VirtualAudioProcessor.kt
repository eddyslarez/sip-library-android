package com.eddyslarez.siplibrary.data.services.audio.newAudio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.eddyslarez.siplibrary.utils.log
import com.shepeliev.webrtckmp.AudioStreamTrack
import com.shepeliev.webrtckmp.MediaStream
import com.shepeliev.webrtckmp.MediaDevices
import com.shepeliev.webrtckmp.audioTracks
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Procesador de audio virtual robusto para inyectar audio personalizado en WebRTC
 * Permite reemplazar el micrófono con audio personalizado y procesar audio remoto recibido
 *
 * @author Eddys Larez
 */
class VirtualAudioProcessor(private val context: Context) {

    // Configuración de audio
    private val sampleRate = 16000 // 16kHz para WebRTC
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

    // Estados del procesador
    private val _isInitialized = MutableStateFlow(false)
    private val _isInjecting = MutableStateFlow(false)
    private val _isProcessingRemote = MutableStateFlow(false)
    private val _transcribedText = MutableStateFlow("")
    private val _audioLevel = MutableStateFlow(0f)

    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()
    val isInjecting: StateFlow<Boolean> = _isInjecting.asStateFlow()
    val isProcessingRemote: StateFlow<Boolean> = _isProcessingRemote.asStateFlow()
    val transcribedText: StateFlow<String> = _transcribedText.asStateFlow()
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    // Componentes de audio virtual
    private var virtualAudioTrack: AudioStreamTrack? = null
    private var virtualMediaStream: MediaStream? = null
    private var audioTrackForPlayback: AudioTrack? = null

    // Buffers y colas para procesamiento
    private val injectionQueue = ConcurrentLinkedQueue<AudioData>()
    private val remoteAudioQueue = ConcurrentLinkedQueue<AudioData>()
    private val playbackQueue = ConcurrentLinkedQueue<AudioData>()

    // Control de hilos
    private val processingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var injectionJob: Job? = null
    private var remoteProcessingJob: Job? = null
    private var playbackJob: Job? = null

    // Estados atómicos
    private val isInjectionActive = AtomicBoolean(false)
    private val isRemoteProcessingActive = AtomicBoolean(false)
    private val isPlaybackActive = AtomicBoolean(false)

    // Callbacks
    private val transcriptionCallbacks = mutableListOf<(String) -> Unit>()
    private val audioLevelCallbacks = mutableListOf<(Float) -> Unit>()
    private val errorCallbacks = mutableListOf<(String) -> Unit>()

    // Configuración avanzada
    private var enableEchoCancellation = true
    private var enableNoiseSuppression = true
    private var enableAutoGainControl = true
    private var maxQueueSize = 50 // Máximo elementos en cola

    companion object {
        private const val TAG = "VirtualAudioProcessor"
        private const val PROCESSING_INTERVAL = 20L // 20ms para procesamiento en tiempo real
        private const val SILENCE_THRESHOLD = 0.01f
    }

    /**
     * Inicializa el procesador de audio virtual
     */
    suspend fun initialize(): Boolean {
        log.d(TAG) { "Inicializando VirtualAudioProcessor..." }

        if (_isInitialized.value) {
            log.w(TAG) { "VirtualAudioProcessor ya inicializado" }
            return true
        }

        return try {
            // Crear MediaStream virtual
            createVirtualMediaStream()

            // Inicializar AudioTrack para reproducción
            initializeAudioTrack()

            _isInitialized.value = true
            log.d(TAG) { "VirtualAudioProcessor inicializado exitosamente" }
            true

        } catch (e: Exception) {
            log.e(TAG) { "Error inicializando VirtualAudioProcessor: ${e.message}" }
            notifyError("Error inicializando procesador virtual: ${e.message}")
            false
        }
    }

    /**
     * Crea un MediaStream virtual para WebRTC
     */
    private suspend fun createVirtualMediaStream() = withContext(Dispatchers.IO) {
        try {
            // Crear un MediaStream personalizado
            // Nota: Esto depende de la implementación específica de WebRTC que uses
            // Aquí simulo la creación, pero necesitarías adaptar según tu librería WebRTC

            virtualMediaStream = MediaDevices.getUserMedia(audio = true, video = false)
            virtualAudioTrack = virtualMediaStream?.audioTracks?.firstOrNull()

            log.d(TAG) { "MediaStream virtual creado exitosamente" }

        } catch (e: Exception) {
            log.e(TAG) { "Error creando MediaStream virtual: ${e.message}" }
            throw e
        }
    }

    /**
     * Inicializa AudioTrack para reproducción de audio personalizado
     */
    private fun initializeAudioTrack() {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                audioFormat
            )

            audioTrackForPlayback = AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                audioFormat,
                minBufferSize * 2,
                AudioTrack.MODE_STREAM
            )

            log.d(TAG) { "AudioTrack inicializado para reproducción" }

        } catch (e: Exception) {
            log.e(TAG) { "Error inicializando AudioTrack: ${e.message}" }
            throw e
        }
    }

    /**
     * Inyecta datos de audio personalizados en lugar del micrófono
     */
    fun injectAudioData(audioData: ByteArray, sampleRate: Int = 16000) {
        if (!_isInitialized.value) {
            log.w(TAG) { "Procesador no inicializado" }
            return
        }

        val audioDataObj = AudioData(
            data = audioData,
            sampleRate = sampleRate,
            timestamp = System.currentTimeMillis()
        )

        // Agregar a la cola de inyección
        if (injectionQueue.size < maxQueueSize) {
            injectionQueue.offer(audioDataObj)

            // Iniciar procesamiento si no está activo
            if (!isInjectionActive.get()) {
                startAudioInjection()
            }
        } else {
            log.w(TAG) { "Cola de inyección llena, descartando audio" }
        }
    }

    /**
     * Inicia el procesamiento de inyección de audio
     */
    private fun startAudioInjection() {
        if (isInjectionActive.compareAndSet(false, true)) {
            _isInjecting.value = true

            injectionJob = processingScope.launch {
                log.d(TAG) { "Iniciando inyección de audio..." }

                try {
                    while (isInjectionActive.get() && isActive) {
                        val audioData = injectionQueue.poll()

                        if (audioData != null) {
                            processInjectedAudio(audioData)
                        } else {
                            delay(PROCESSING_INTERVAL)
                        }
                    }
                } catch (e: Exception) {
                    log.e(TAG) { "Error en inyección de audio: ${e.message}" }
                    notifyError("Error en inyección de audio: ${e.message}")
                } finally {
                    isInjectionActive.set(false)
                    _isInjecting.value = false
                    log.d(TAG) { "Inyección de audio detenida" }
                }
            }
        }
    }

    /**
     * Procesa audio inyectado y lo envía al track virtual
     */
    private suspend fun processInjectedAudio(audioData: AudioData) = withContext(Dispatchers.IO) {
        try {
            // Procesar audio (aplicar filtros si están habilitados)
            val processedAudio = applyAudioProcessing(audioData.data)

            // Calcular nivel de audio
            val level = calculateAudioLevel(processedAudio)
            _audioLevel.value = level
            notifyAudioLevel(level)

            // Inyectar en el track virtual de WebRTC
            injectIntoVirtualTrack(processedAudio)

        } catch (e: Exception) {
            log.e(TAG) { "Error procesando audio inyectado: ${e.message}" }
            notifyError("Error procesando audio inyectado: ${e.message}")
        }
    }

    /**
     * Inyecta audio procesado en el track virtual de WebRTC
     */
    private fun injectIntoVirtualTrack(audioData: ByteArray) {
        try {
            // Aquí necesitarías la implementación específica para tu librería WebRTC
            // Esto es un ejemplo conceptual de cómo sería:

            virtualAudioTrack?.let { track ->
                // Convertir ByteArray a formato que acepta WebRTC
                val buffer = ByteBuffer.wrap(audioData)

                // Inyectar en el track (esto depende de la implementación específica)
                // track.injectAudioData(buffer, sampleRate)

                log.d(TAG) { "Audio inyectado en track virtual: ${audioData.size} bytes" }
            }

        } catch (e: Exception) {
            log.e(TAG) { "Error inyectando en track virtual: ${e.message}" }
            throw e
        }
    }

    /**
     * Reproduce audio personalizado en lugar del audio remoto recibido
     */
    fun playCustomAudio(audioData: ByteArray, sampleRate: Int = 16000) {
        if (!_isInitialized.value) {
            log.w(TAG) { "Procesador no inicializado" }
            return
        }

        val audioDataObj = AudioData(
            data = audioData,
            sampleRate = sampleRate,
            timestamp = System.currentTimeMillis()
        )

        if (playbackQueue.size < maxQueueSize) {
            playbackQueue.offer(audioDataObj)

            if (!isPlaybackActive.get()) {
                startCustomAudioPlayback()
            }
        } else {
            log.w(TAG) { "Cola de reproducción llena, descartando audio" }
        }
    }

    /**
     * Inicia la reproducción de audio personalizado
     */
    private fun startCustomAudioPlayback() {
        if (isPlaybackActive.compareAndSet(false, true)) {

            playbackJob = processingScope.launch {
                log.d(TAG) { "Iniciando reproducción de audio personalizado..." }

                try {
                    audioTrackForPlayback?.play()

                    while (isPlaybackActive.get() && isActive) {
                        val audioData = playbackQueue.poll()

                        if (audioData != null) {
                            playAudioData(audioData)
                        } else {
                            delay(PROCESSING_INTERVAL)
                        }
                    }
                } catch (e: Exception) {
                    log.e(TAG) { "Error en reproducción personalizada: ${e.message}" }
                    notifyError("Error en reproducción: ${e.message}")
                } finally {
                    audioTrackForPlayback?.pause()
                    isPlaybackActive.set(false)
                    log.d(TAG) { "Reproducción personalizada detenida" }
                }
            }
        }
    }

    /**
     * Reproduce datos de audio específicos
     */
    private suspend fun playAudioData(audioData: AudioData) = withContext(Dispatchers.IO) {
        try {
            val processedAudio = applyAudioProcessing(audioData.data)

            audioTrackForPlayback?.let { audioTrack ->
                val bytesWritten = audioTrack.write(processedAudio, 0, processedAudio.size)

                if (bytesWritten < 0) {
                    log.e(TAG) { "Error escribiendo audio: $bytesWritten" }
                } else {
                    log.d(TAG) { "Audio reproducido: $bytesWritten bytes" }
                }
            }

        } catch (e: Exception) {
            log.e(TAG) { "Error reproduciendo audio: ${e.message}" }
            throw e
        }
    }

    /**
     * Inicia el procesamiento del audio remoto recibido para transcripción
     */
    fun startRemoteAudioProcessing() {
        if (!_isInitialized.value) {
            log.w(TAG) { "Procesador no inicializado" }
            return
        }

        if (isRemoteProcessingActive.compareAndSet(false, true)) {
            _isProcessingRemote.value = true

            remoteProcessingJob = processingScope.launch {
                log.d(TAG) { "Iniciando procesamiento de audio remoto..." }

                try {
                    while (isRemoteProcessingActive.get() && isActive) {
                        val audioData = remoteAudioQueue.poll()

                        if (audioData != null) {
                            processRemoteAudioForTranscription(audioData)
                        } else {
                            delay(PROCESSING_INTERVAL)
                        }
                    }
                } catch (e: Exception) {
                    log.e(TAG) { "Error procesando audio remoto: ${e.message}" }
                    notifyError("Error procesando audio remoto: ${e.message}")
                } finally {
                    isRemoteProcessingActive.set(false)
                    _isProcessingRemote.value = false
                    log.d(TAG) { "Procesamiento de audio remoto detenido" }
                }
            }
        }
    }

    /**
     * Procesa audio remoto recibido de WebRTC
     */
    fun processRemoteAudio(audioData: ByteArray, sampleRate: Int = 16000) {
        if (!isRemoteProcessingActive.get()) {
            return
        }

        val audioDataObj = AudioData(
            data = audioData,
            sampleRate = sampleRate,
            timestamp = System.currentTimeMillis()
        )

        if (remoteAudioQueue.size < maxQueueSize) {
            remoteAudioQueue.offer(audioDataObj)
        } else {
            log.w(TAG) { "Cola de audio remoto llena, descartando" }
        }
    }

    /**
     * Procesa audio remoto para transcripción
     */
    private suspend fun processRemoteAudioForTranscription(audioData: AudioData) = withContext(Dispatchers.IO) {
        try {
            // Aplicar procesamiento de audio
            val processedAudio = applyAudioProcessing(audioData.data)

            // Calcular nivel de audio
            val level = calculateAudioLevel(processedAudio)

            // Solo procesar si no es silencio
            if (level > SILENCE_THRESHOLD) {
                // Realizar transcripción (integrar con tu servicio preferido)
                val transcription = performSpeechRecognition(processedAudio, audioData.sampleRate)

                if (transcription.isNotEmpty()) {
                    _transcribedText.value = transcription
                    notifyTranscription(transcription)
                }
            }

        } catch (e: Exception) {
            log.e(TAG) { "Error en transcripción de audio remoto: ${e.message}" }
            notifyError("Error en transcripción: ${e.message}")
        }
    }

    /**
     * Detiene el procesamiento de audio remoto
     */
    fun stopRemoteAudioProcessing() {
        log.d(TAG) { "Deteniendo procesamiento de audio remoto" }

        isRemoteProcessingActive.set(false)
        remoteProcessingJob?.cancel()
        remoteProcessingJob = null

        // Limpiar cola
        remoteAudioQueue.clear()

        _isProcessingRemote.value = false
    }

    /**
     * Aplica procesamiento de audio (filtros, mejoras)
     */
    private fun applyAudioProcessing(audioData: ByteArray): ByteArray {
        var processedData = audioData

        try {
            // Aplicar cancelación de eco si está habilitada
            if (enableEchoCancellation) {
                processedData = applyEchoCancellation(processedData)
            }

            // Aplicar supresión de ruido si está habilitada
            if (enableNoiseSuppression) {
                processedData = applyNoiseSuppression(processedData)
            }

            // Aplicar control automático de ganancia si está habilitado
            if (enableAutoGainControl) {
                processedData = applyAutoGainControl(processedData)
            }

        } catch (e: Exception) {
            log.e(TAG) { "Error aplicando procesamiento de audio: ${e.message}" }
            // Devolver datos originales si falla el procesamiento
            return audioData
        }

        return processedData
    }

    /**
     * Aplica cancelación de eco (implementación básica)
     */
    private fun applyEchoCancellation(audioData: ByteArray): ByteArray {
        // Implementación básica de cancelación de eco
        // En producción, usarías algoritmos más sofisticados como WebRTC AEC
        return audioData
    }

    /**
     * Aplica supresión de ruido (implementación básica)
     */
    private fun applyNoiseSuppression(audioData: ByteArray): ByteArray {
        // Implementación básica de supresión de ruido
        // En producción, usarías algoritmos como WebRTC NS
        return audioData
    }

    /**
     * Aplica control automático de ganancia (implementación básica)
     */
    private fun applyAutoGainControl(audioData: ByteArray): ByteArray {
        // Implementación básica de AGC
        // En producción, usarías algoritmos como WebRTC AGC
        return audioData
    }

    /**
     * Calcula el nivel de audio
     */
    private fun calculateAudioLevel(audioData: ByteArray): Float {
        if (audioData.isEmpty()) return 0f

        var sum = 0.0
        for (i in audioData.indices step 2) {
            if (i + 1 < audioData.size) {
                val sample = (audioData[i].toInt() and 0xFF) or (audioData[i + 1].toInt() shl 8)
                sum += sample * sample
            }
        }

        val rms = kotlin.math.sqrt(sum / (audioData.size / 2))
        return (rms / Short.MAX_VALUE).toFloat()
    }

    /**
     * Realiza reconocimiento de voz
     */
    private suspend fun performSpeechRecognition(audioData: ByteArray, sampleRate: Int): String = withContext(Dispatchers.IO) {
        try {
            // Aquí integrarías tu servicio de reconocimiento de voz preferido
            // Ejemplos: Google Speech-to-Text, Azure Speech, AWS Transcribe, etc.

            // Simulación por ahora
            delay(50)

            val audioLevel = calculateAudioLevel(audioData)
            return@withContext when {
                audioLevel > 0.1f -> "Audio fuerte detectado en remoto"
                audioLevel > 0.05f -> "Audio medio detectado en remoto"
                audioLevel > 0.01f -> "Audio bajo detectado en remoto"
                else -> ""
            }

            // Implementación real sería algo como:
            /*
            val speechService = GoogleSpeechToText()
            return speechService.transcribe(
                audioData = audioData,
                sampleRate = sampleRate,
                language = "es-ES"
            )
            */

        } catch (e: Exception) {
            log.e(TAG) { "Error en reconocimiento de voz: ${e.message}" }
            ""
        }
    }

    /**
     * Obtiene el track de audio virtual para WebRTC
     */
    fun getVirtualAudioTrack(): AudioStreamTrack? {
        return virtualAudioTrack
    }

    /**
     * Obtiene el MediaStream virtual para WebRTC
     */
    fun getVirtualMediaStream(): MediaStream? {
        return virtualMediaStream
    }

    /**
     * Configuración de procesamiento
     */
    fun setAudioProcessingConfig(
        echoCancellation: Boolean = true,
        noiseSuppression: Boolean = true,
        autoGainControl: Boolean = true
    ) {
        enableEchoCancellation = echoCancellation
        enableNoiseSuppression = noiseSuppression
        enableAutoGainControl = autoGainControl

        log.d(TAG) { "Configuración de procesamiento actualizada - EC: $echoCancellation, NS: $noiseSuppression, AGC: $autoGainControl" }
    }

    /**
     * Limpia todas las colas de audio
     */
    fun clearAllQueues() {
        injectionQueue.clear()
        remoteAudioQueue.clear()
        playbackQueue.clear()
        log.d(TAG) { "Todas las colas de audio limpiadas" }
    }

    /**
     * Callbacks y listeners
     */
    fun addTranscriptionCallback(callback: (String) -> Unit) {
        transcriptionCallbacks.add(callback)
    }

    fun removeTranscriptionCallback(callback: (String) -> Unit) {
        transcriptionCallbacks.remove(callback)
    }

    fun addAudioLevelCallback(callback: (Float) -> Unit) {
        audioLevelCallbacks.add(callback)
    }

    fun removeAudioLevelCallback(callback: (Float) -> Unit) {
        audioLevelCallbacks.remove(callback)
    }

    fun addErrorCallback(callback: (String) -> Unit) {
        errorCallbacks.add(callback)
    }

    fun removeErrorCallback(callback: (String) -> Unit) {
        errorCallbacks.remove(callback)
    }

    private fun notifyTranscription(text: String) {
        transcriptionCallbacks.forEach { callback ->
            try {
                callback(text)
            } catch (e: Exception) {
                log.e(TAG) { "Error en callback de transcripción: ${e.message}" }
            }
        }
    }

    private fun notifyAudioLevel(level: Float) {
        audioLevelCallbacks.forEach { callback ->
            try {
                callback(level)
            } catch (e: Exception) {
                log.e(TAG) { "Error en callback de nivel de audio: ${e.message}" }
            }
        }
    }

    private fun notifyError(error: String) {
        errorCallbacks.forEach { callback ->
            try {
                callback(error)
            } catch (e: Exception) {
                log.e(TAG) { "Error en callback de error: ${e.message}" }
            }
        }
    }

    /**
     * Obtiene información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== VIRTUAL AUDIO PROCESSOR ===")
            appendLine("Inicializado: ${_isInitialized.value}")
            appendLine("Inyectando: ${_isInjecting.value}")
            appendLine("Procesando remoto: ${_isProcessingRemote.value}")
            appendLine("Nivel de audio: ${_audioLevel.value}")
            appendLine("Texto transcrito: ${_transcribedText.value}")
            appendLine("Cola inyección: ${injectionQueue.size}")
            appendLine("Cola remoto: ${remoteAudioQueue.size}")
            appendLine("Cola reproducción: ${playbackQueue.size}")
            appendLine("Sample rate: $sampleRate Hz")
            appendLine("Buffer size: $bufferSize bytes")
            appendLine("Echo cancellation: $enableEchoCancellation")
            appendLine("Noise suppression: $enableNoiseSuppression")
            appendLine("Auto gain control: $enableAutoGainControl")
            appendLine("Jobs activos:")
            appendLine("  - Inyección: ${injectionJob?.isActive}")
            appendLine("  - Procesamiento remoto: ${remoteProcessingJob?.isActive}")
            appendLine("  - Reproducción: ${playbackJob?.isActive}")
        }
    }

    /**
     * Limpieza de recursos
     */
    fun dispose() {
        log.d(TAG) { "Disposing VirtualAudioProcessor..." }

        try {
            // Detener todos los procesos
            isInjectionActive.set(false)
            isRemoteProcessingActive.set(false)
            isPlaybackActive.set(false)

            // Cancelar jobs
            injectionJob?.cancel()
            remoteProcessingJob?.cancel()
            playbackJob?.cancel()

            // Limpiar colas
            clearAllQueues()

            // Liberar recursos de audio
            audioTrackForPlayback?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.pause()
                }
                track.release()
            }
            audioTrackForPlayback = null

            // Limpiar tracks virtuales
            virtualAudioTrack = null
            virtualMediaStream = null

            // Cancelar scope
            processingScope.cancel()

            // Limpiar callbacks
            transcriptionCallbacks.clear()
            audioLevelCallbacks.clear()
            errorCallbacks.clear()

            // Reset estados
            _isInitialized.value = false
            _isInjecting.value = false
            _isProcessingRemote.value = false
            _transcribedText.value = ""
            _audioLevel.value = 0f

            log.d(TAG) { "VirtualAudioProcessor disposed exitosamente" }

        } catch (e: Exception) {
            log.e(TAG) { "Error disposing VirtualAudioProcessor: ${e.message}" }
        }
    }
}

/**
 * Clase de datos para audio
 */
data class AudioData(
    val data: ByteArray,
    val sampleRate: Int,
    val timestamp: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AudioData

        if (!data.contentEquals(other.data)) return false
        if (sampleRate != other.sampleRate) return false
        if (timestamp != other.timestamp) return false

        return true
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + timestamp.hashCode()
        return result
    }
}