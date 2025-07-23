package com.eddyslarez.siplibrary.data.services.audio.newAudio

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Procesador de audio en tiempo real para conversión de voz a texto
 */
class AudioStreamProcessor(private val context: Context) {

    // Configuración de audio
    private val sampleRate = 16000 // 16kHz recomendado para reconocimiento de voz
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

    // Estados
    private val _isRecording = MutableStateFlow(false)
    private val _transcribedText = MutableStateFlow("")
    private val _isProcessing = MutableStateFlow(false)
    private val _audioLevel = MutableStateFlow(0f)

    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()
    val transcribedText: StateFlow<String> = _transcribedText.asStateFlow()
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    // Audio recording
    private var audioRecord: AudioRecord? = null
    private val isRecordingActive = AtomicBoolean(false)

    // Procesamiento
    private val processingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordingJob: Job? = null
    private var processingJob: Job? = null

    // Callbacks
    private val transcriptionCallbacks = mutableListOf<(String) -> Unit>()
    private val audioLevelCallbacks = mutableListOf<(Float) -> Unit>()
    private val errorCallbacks = mutableListOf<(String) -> Unit>()

    // Buffer para procesamiento
    private val audioBuffer = mutableListOf<ByteArray>()
    private val maxBufferSize = 10 // Máximo 10 chunks de audio en buffer

    companion object {
        private const val TAG = "AudioStreamProcessor"
        private const val SILENCE_THRESHOLD = 0.01f
        private const val PROCESSING_INTERVAL = 1000L // Procesar cada segundo
    }

    /**
     * Inicia la grabación y procesamiento de audio
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecording(): Boolean {
        if (_isRecording.value) {
            log.w(TAG) { "Ya está grabando" }
            return true
        }

        return try {
            // Inicializar AudioRecord
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                log.e(TAG) { "Error inicializando AudioRecord" }
                return false
            }

            // Iniciar grabación
            audioRecord?.startRecording()
            isRecordingActive.set(true)
            _isRecording.value = true

            // Iniciar jobs de procesamiento
            startRecordingJob()
            startProcessingJob()

            log.d(TAG) { "Grabación iniciada" }
            true

        } catch (e: Exception) {
            log.e(TAG) { "Error iniciando grabación: ${e.message}" }
            notifyError("Error iniciando grabación: ${e.message}")
            false
        }
    }

    /**
     * Detiene la grabación y procesamiento
     */
    fun stopRecording() {
        log.d(TAG) { "Deteniendo grabación" }

        try {
            // Detener grabación
            isRecordingActive.set(false)
            _isRecording.value = false

            // Cancelar jobs
            recordingJob?.cancel()
            processingJob?.cancel()

            // Detener y liberar AudioRecord
            audioRecord?.let { record ->
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    record.stop()
                }
                record.release()
            }
            audioRecord = null

            // Procesar audio restante en buffer
            processingScope.launch {
                processRemainingAudio()
            }

            log.d(TAG) { "Grabación detenida" }

        } catch (e: Exception) {
            log.e(TAG) { "Error deteniendo grabación: ${e.message}" }
            notifyError("Error deteniendo grabación: ${e.message}")
        }
    }

    /**
     * Job para grabación continua de audio
     */
    private fun startRecordingJob() {
        recordingJob = processingScope.launch {
            val buffer = ByteArray(bufferSize)

            while (isRecordingActive.get() && isActive) {
                try {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                    if (bytesRead > 0) {
                        // Calcular nivel de audio
                        val audioLevel = calculateAudioLevel(buffer, bytesRead)
                        _audioLevel.value = audioLevel
                        notifyAudioLevel(audioLevel)

                        // Agregar al buffer si no es silencio
                        if (audioLevel > SILENCE_THRESHOLD) {
                            synchronized(audioBuffer) {
                                // Limitar tamaño del buffer
                                if (audioBuffer.size >= maxBufferSize) {
                                    audioBuffer.removeAt(0)
                                }
                                audioBuffer.add(buffer.copyOf(bytesRead))
                            }
                        }
                    }

                    yield() // Permitir cancelación

                } catch (e: Exception) {
                    log.e(TAG) { "Error en grabación: ${e.message}" }
                    notifyError("Error en grabación: ${e.message}")
                    break
                }
            }
        }
    }

    /**
     * Job para procesamiento periódico del audio
     */
    private fun startProcessingJob() {
        processingJob = processingScope.launch {
            while (isRecordingActive.get() && isActive) {
                try {
                    delay(PROCESSING_INTERVAL)

                    if (audioBuffer.isNotEmpty()) {
                        _isProcessing.value = true
                        processAudioBuffer()
                        _isProcessing.value = false
                    }

                } catch (e: Exception) {
                    log.e(TAG) { "Error en procesamiento: ${e.message}" }
                    notifyError("Error en procesamiento: ${e.message}")
                    _isProcessing.value = false
                }
            }
        }
    }

    /**
     * Procesa el buffer de audio acumulado
     */
    private suspend fun processAudioBuffer() = withContext(Dispatchers.IO) {
        try {
            val audioData: List<ByteArray>
            synchronized(audioBuffer) {
                audioData = audioBuffer.toList()
                audioBuffer.clear()
            }

            if (audioData.isNotEmpty()) {
                // Combinar todos los chunks de audio
                val combinedAudio = combineAudioChunks(audioData)

                // Procesar con reconocimiento de voz
                val transcription = performSpeechRecognition(combinedAudio)

                if (transcription.isNotEmpty()) {
                    _transcribedText.value = transcription
                    notifyTranscription(transcription)
                }
            }

        } catch (e: Exception) {
            log.e(TAG) { "Error procesando buffer de audio: ${e.message}" }
            notifyError("Error procesando audio: ${e.message}")
        }
    }

    /**
     * Procesa audio restante cuando se detiene la grabación
     */
    private suspend fun processRemainingAudio() = withContext(Dispatchers.IO) {
        try {
            if (audioBuffer.isNotEmpty()) {
                _isProcessing.value = true
                processAudioBuffer()
                _isProcessing.value = false
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error procesando audio restante: ${e.message}" }
            _isProcessing.value = false
        }
    }

    /**
     * Calcula el nivel de audio (volumen) del buffer
     */
    private fun calculateAudioLevel(buffer: ByteArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length step 2) {
            if (i + 1 < length) {
                val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                sum += sample * sample
            }
        }

        val rms = kotlin.math.sqrt(sum / (length / 2))
        return (rms / Short.MAX_VALUE).toFloat()
    }

    /**
     * Combina múltiples chunks de audio en uno solo
     */
    private fun combineAudioChunks(chunks: List<ByteArray>): ByteArray {
        val totalSize = chunks.sumOf { it.size }
        val combinedBuffer = ByteArray(totalSize)

        var offset = 0
        chunks.forEach { chunk ->
            System.arraycopy(chunk, 0, combinedBuffer, offset, chunk.size)
            offset += chunk.size
        }

        return combinedBuffer
    }

    /**
     * Realiza el reconocimiento de voz
     * NOTA: Esta es una implementación de ejemplo.
     * En producción, usarías Google Speech-to-Text, Azure Speech, etc.
     */
    private suspend fun performSpeechRecognition(audioData: ByteArray): String = withContext(Dispatchers.IO) {
        try {
            // Aquí integrarías tu servicio de reconocimiento de voz preferido
            // Por ejemplo: Google Speech-to-Text, Azure Cognitive Services, etc.

            // Simulación de procesamiento
            delay(100)

            // Por ahora, devolvemos un texto de ejemplo basado en el nivel de audio
            val audioLevel = calculateAudioLevel(audioData, audioData.size)
            when {
                audioLevel > 0.1f -> "Audio fuerte detectado"
                audioLevel > 0.05f -> "Audio medio detectado"
                audioLevel > 0.01f -> "Audio bajo detectado"
                else -> ""
            }

            // En producción, aquí harías algo como:
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
     * Limpia el texto transcrito
     */
    fun clearTranscription() {
        _transcribedText.value = ""
    }

    /**
     * Obtiene estadísticas de audio
     */
    fun getAudioStats(): AudioStats {
        return AudioStats(
            sampleRate = sampleRate,
            bufferSize = bufferSize,
            currentLevel = _audioLevel.value,
            isRecording = _isRecording.value,
            isProcessing = _isProcessing.value,
            bufferItems = audioBuffer.size
        )
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
            appendLine("=== AUDIO STREAM PROCESSOR ===")
            appendLine("Grabando: ${_isRecording.value}")
            appendLine("Procesando: ${_isProcessing.value}")
            appendLine("Nivel de audio: ${_audioLevel.value}")
            appendLine("Texto actual: ${_transcribedText.value}")
            appendLine("Buffer items: ${audioBuffer.size}")
            appendLine("Sample rate: $sampleRate Hz")
            appendLine("Buffer size: $bufferSize bytes")
            appendLine("Callbacks transcripción: ${transcriptionCallbacks.size}")
            appendLine("Callbacks nivel: ${audioLevelCallbacks.size}")
            appendLine("Callbacks error: ${errorCallbacks.size}")
        }
    }

    /**
     * Limpieza de recursos
     */
    fun dispose() {
        try {
            stopRecording()

            // Cancelar scope de procesamiento
            processingScope.cancel()

            // Limpiar callbacks
            transcriptionCallbacks.clear()
            audioLevelCallbacks.clear()
            errorCallbacks.clear()

            // Limpiar buffer
            synchronized(audioBuffer) {
                audioBuffer.clear()
            }

            log.d(TAG) { "AudioStreamProcessor disposed" }

        } catch (e: Exception) {
            log.e(TAG) { "Error disposing AudioStreamProcessor: ${e.message}" }
        }
    }
}

/**
 * Clase de datos para estadísticas de audio
 */
data class AudioStats(
    val sampleRate: Int,
    val bufferSize: Int,
    val currentLevel: Float,
    val isRecording: Boolean,
    val isProcessing: Boolean,
    val bufferItems: Int
)