package com.eddyslarez.siplibrary.data.services.transcription

import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Interceptor de audio WebRTC para capturar audio en tiempo real
 * Se integra directamente con el pipeline de WebRTC para capturar audio
 * antes de que llegue al altavoz
 * 
 * @author Eddys Larez
 */
class WebRtcAudioInterceptor {
    
    private val TAG = "WebRtcAudioInterceptor"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Estados del interceptor
    private val _isInterceptingFlow = MutableStateFlow(false)
    val isInterceptingFlow: StateFlow<Boolean> = _isInterceptingFlow.asStateFlow()
    
    private val _audioLevelFlow = MutableStateFlow(0f)
    val audioLevelFlow: StateFlow<Float> = _audioLevelFlow.asStateFlow()
    
    private val _audioQualityFlow = MutableStateFlow(AudioQuality())
    val audioQualityFlow: StateFlow<AudioQuality> = _audioQualityFlow.asStateFlow()
    
    // Buffers de audio por fuente
    private val remoteAudioBuffer = ConcurrentLinkedQueue<AudioFrame>()
    private val localAudioBuffer = ConcurrentLinkedQueue<AudioFrame>()
    
    // Configuración de audio
    private val sampleRate = 16000 // 16kHz estándar para STT
    private val channels = 1 // Mono
    private val bitsPerSample = 16
    private val frameSize = 320 // 20ms a 16kHz
    
    // Callbacks para audio interceptado
    private var onRemoteAudioCallback: ((ByteArray) -> Unit)? = null
    private var onLocalAudioCallback: ((ByteArray) -> Unit)? = null
    private var onAudioLevelCallback: ((Float, AudioTranscriptionService.AudioSource) -> Unit)? = null
    
    // Estadísticas
    private var totalFramesProcessed = 0L
    private var totalBytesProcessed = 0L
    private var lastProcessingTime = 0L
    
    /**
     * Frame de audio con metadatos
     */
    data class AudioFrame(
        val data: ByteArray,
        val timestamp: Long,
        val sampleRate: Int,
        val channels: Int,
        val source: AudioTranscriptionService.AudioSource,
        val sequenceNumber: Long = 0L
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            
            other as AudioFrame
            
            if (!data.contentEquals(other.data)) return false
            if (timestamp != other.timestamp) return false
            if (sampleRate != other.sampleRate) return false
            if (channels != other.channels) return false
            if (source != other.source) return false
            if (sequenceNumber != other.sequenceNumber) return false
            
            return true
        }
        
        override fun hashCode(): Int {
            var result = data.contentHashCode()
            result = 31 * result + timestamp.hashCode()
            result = 31 * result + sampleRate
            result = 31 * result + channels
            result = 31 * result + source.hashCode()
            result = 31 * result + sequenceNumber.hashCode()
            return result
        }
    }
    
    /**
     * Información de calidad de audio
     */
    data class AudioQuality(
        val averageLevel: Float = 0f,
        val peakLevel: Float = 0f,
        val signalToNoiseRatio: Float = 0f,
        val clippingDetected: Boolean = false,
        val silenceDetected: Boolean = false,
        val lastUpdateTime: Long = 0L
    )
    
    /**
     * Inicia la interceptación de audio
     */
    fun startIntercepting() {
        if (_isInterceptingFlow.value) {
            log.d(tag = TAG) { "Audio interception already active" }
            return
        }
        
        log.d(tag = TAG) { "Starting WebRTC audio interception" }
        
        try {
            _isInterceptingFlow.value = true
            
            // Limpiar buffers
            remoteAudioBuffer.clear()
            localAudioBuffer.clear()
            
            // Resetear estadísticas
            totalFramesProcessed = 0L
            totalBytesProcessed = 0L
            lastProcessingTime = System.currentTimeMillis()
            
            log.d(tag = TAG) { "WebRTC audio interception started successfully" }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error starting audio interception: ${e.message}" }
            _isInterceptingFlow.value = false
        }
    }
    
    /**
     * Detiene la interceptación de audio
     */
    fun stopIntercepting() {
        if (!_isInterceptingFlow.value) {
            log.d(tag = TAG) { "Audio interception not active" }
            return
        }
        
        log.d(tag = TAG) { "Stopping WebRTC audio interception" }
        
        try {
            _isInterceptingFlow.value = false
            
            // Limpiar buffers
            remoteAudioBuffer.clear()
            localAudioBuffer.clear()
            
            log.d(tag = TAG) { "WebRTC audio interception stopped successfully" }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error stopping audio interception: ${e.message}" }
        }
    }
    
    /**
     * Intercepta audio remoto (lo que escuchamos)
     */
    fun interceptRemoteAudio(audioData: ByteArray, timestamp: Long = System.currentTimeMillis()) {
        if (!_isInterceptingFlow.value) return
        
        try {
            val audioFrame = AudioFrame(
                data = audioData,
                timestamp = timestamp,
                sampleRate = sampleRate,
                channels = channels,
                source = AudioTranscriptionService.AudioSource.WEBRTC_REMOTE,
                sequenceNumber = totalFramesProcessed
            )
            
            // Añadir al buffer
            if (remoteAudioBuffer.size < 50) { // Límite de buffer
                remoteAudioBuffer.offer(audioFrame)
            } else {
                remoteAudioBuffer.poll() // Remover el más antiguo
                remoteAudioBuffer.offer(audioFrame)
            }
            
            // Procesar audio
            processAudioFrame(audioFrame)
            
            // Notificar callback
            onRemoteAudioCallback?.invoke(audioData)
            
            // Actualizar estadísticas
            updateStatistics(audioData, AudioTranscriptionService.AudioSource.WEBRTC_REMOTE)
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error intercepting remote audio: ${e.message}" }
        }
    }
    
    /**
     * Intercepta audio local (lo que hablamos)
     */
    fun interceptLocalAudio(audioData: ByteArray, timestamp: Long = System.currentTimeMillis()) {
        if (!_isInterceptingFlow.value) return
        
        try {
            val audioFrame = AudioFrame(
                data = audioData,
                timestamp = timestamp,
                sampleRate = sampleRate,
                channels = channels,
                source = AudioTranscriptionService.AudioSource.WEBRTC_LOCAL,
                sequenceNumber = totalFramesProcessed
            )
            
            // Añadir al buffer
            if (localAudioBuffer.size < 50) { // Límite de buffer
                localAudioBuffer.offer(audioFrame)
            } else {
                localAudioBuffer.poll() // Remover el más antiguo
                localAudioBuffer.offer(audioFrame)
            }
            
            // Procesar audio
            processAudioFrame(audioFrame)
            
            // Notificar callback
            onLocalAudioCallback?.invoke(audioData)
            
            // Actualizar estadísticas
            updateStatistics(audioData, AudioTranscriptionService.AudioSource.WEBRTC_LOCAL)
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error intercepting local audio: ${e.message}" }
        }
    }
    
    /**
     * Procesa un frame de audio
     */
    private fun processAudioFrame(audioFrame: AudioFrame) {
        scope.launch {
            try {
                // Convertir audio a formato adecuado para STT
                val processedAudio = convertAudioFormat(audioFrame.data)
                
                // Calcular nivel de audio
                val audioLevel = calculateAudioLevel(audioFrame.data)
                _audioLevelFlow.value = audioLevel
                
                // Detectar calidad de audio
                val quality = analyzeAudioQuality(audioFrame.data, audioLevel)
                _audioQualityFlow.value = quality
                
                // Notificar nivel de audio
                onAudioLevelCallback?.invoke(audioLevel, audioFrame.source)
                
                totalFramesProcessed++
                
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error processing audio frame: ${e.message}" }
            }
        }
    }
    
    /**
     * Convierte formato de audio para STT
     */
    private fun convertAudioFormat(audioData: ByteArray): ByteArray {
        try {
            // Convertir de formato WebRTC a formato PCM 16-bit mono
            val buffer = ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN)
            val samples = ShortArray(audioData.size / 2)
            
            for (i in samples.indices) {
                if (buffer.remaining() >= 2) {
                    samples[i] = buffer.short
                }
            }
            
            // Convertir de vuelta a ByteArray
            val outputBuffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) {
                outputBuffer.putShort(sample)
            }
            
            return outputBuffer.array()
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error converting audio format: ${e.message}" }
            return audioData // Retornar original en caso de error
        }
    }
    
    /**
     * Calcula el nivel de audio (RMS)
     */
    private fun calculateAudioLevel(audioData: ByteArray): Float {
        try {
            if (audioData.size < 2) return 0f
            
            val buffer = ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN)
            var sum = 0.0
            var sampleCount = 0
            
            while (buffer.remaining() >= 2) {
                val sample = buffer.short.toDouble()
                sum += sample * sample
                sampleCount++
            }
            
            if (sampleCount == 0) return 0f
            
            val rms = sqrt(sum / sampleCount)
            return (rms / 32768.0).toFloat() // Normalizar a 0-1
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error calculating audio level: ${e.message}" }
            return 0f
        }
    }
    
    /**
     * Analiza la calidad del audio
     */
    private fun analyzeAudioQuality(audioData: ByteArray, audioLevel: Float): AudioQuality {
        try {
            val currentTime = System.currentTimeMillis()
            val currentQuality = _audioQualityFlow.value
            
            // Detectar clipping (saturación)
            val clippingDetected = detectClipping(audioData)
            
            // Detectar silencio
            val silenceDetected = audioLevel < 0.01f
            
            // Calcular SNR aproximado
            val snr = calculateApproximateSnr(audioData, audioLevel)
            
            // Actualizar promedios
            val newAverageLevel = if (currentQuality.lastUpdateTime > 0) {
                (currentQuality.averageLevel * 0.9f) + (audioLevel * 0.1f)
            } else {
                audioLevel
            }
            
            val newPeakLevel = maxOf(currentQuality.peakLevel, audioLevel)
            
            return AudioQuality(
                averageLevel = newAverageLevel,
                peakLevel = newPeakLevel,
                signalToNoiseRatio = snr,
                clippingDetected = clippingDetected,
                silenceDetected = silenceDetected,
                lastUpdateTime = currentTime
            )
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error analyzing audio quality: ${e.message}" }
            return AudioQuality()
        }
    }
    
    /**
     * Detecta clipping en el audio
     */
    private fun detectClipping(audioData: ByteArray): Boolean {
        try {
            val buffer = ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN)
            val threshold = 32000 // Cerca del máximo para 16-bit
            
            while (buffer.remaining() >= 2) {
                val sample = abs(buffer.short.toInt())
                if (sample >= threshold) {
                    return true
                }
            }
            
            return false
            
        } catch (e: Exception) {
            return false
        }
    }
    
    /**
     * Calcula SNR aproximado
     */
    private fun calculateApproximateSnr(audioData: ByteArray, signalLevel: Float): Float {
        try {
            // Implementación simplificada de SNR
            // En una implementación real, se haría análisis espectral
            
            if (signalLevel < 0.01f) return 0f
            
            val buffer = ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN)
            var variance = 0.0
            var sampleCount = 0
            val mean = signalLevel.toDouble()
            
            while (buffer.remaining() >= 2) {
                val sample = (buffer.short.toDouble() / 32768.0)
                variance += (sample - mean) * (sample - mean)
                sampleCount++
            }
            
            if (sampleCount == 0) return 0f
            
            val noiseLevel = sqrt(variance / sampleCount)
            return if (noiseLevel > 0) {
                (20 * kotlin.math.log10(signalLevel / noiseLevel.toFloat())).toFloat()
            } else {
                60f // SNR muy alto si no hay ruido detectable
            }
            
        } catch (e: Exception) {
            return 0f
        }
    }
    
    /**
     * Actualiza estadísticas de procesamiento
     */
    private fun updateStatistics(audioData: ByteArray, source: AudioTranscriptionService.AudioSource) {
        totalBytesProcessed += audioData.size
        lastProcessingTime = System.currentTimeMillis()
    }
    
    /**
     * Obtiene audio acumulado para transcripción
     */
    fun getAccumulatedAudio(source: AudioTranscriptionService.AudioSource, durationMs: Long = 1000): ByteArray? {
        try {
            val buffer = when (source) {
                AudioTranscriptionService.AudioSource.WEBRTC_REMOTE -> remoteAudioBuffer
                AudioTranscriptionService.AudioSource.WEBRTC_LOCAL -> localAudioBuffer
                AudioTranscriptionService.AudioSource.WEBRTC_BOTH -> {
                    // Mezclar ambos buffers
                    return getMixedAudio(durationMs)
                }
            }
            
            if (buffer.isEmpty()) return null
            
            val targetFrames = (durationMs * sampleRate / 1000 / frameSize).toInt()
            val framesToProcess = minOf(targetFrames, buffer.size)
            
            if (framesToProcess == 0) return null
            
            val totalSize = framesToProcess * frameSize * 2 // 2 bytes per sample
            val result = ByteArray(totalSize)
            var offset = 0
            
            repeat(framesToProcess) {
                val frame = buffer.poll()
                if (frame != null && offset + frame.data.size <= result.size) {
                    System.arraycopy(frame.data, 0, result, offset, frame.data.size)
                    offset += frame.data.size
                }
            }
            
            return if (offset > 0) result.copyOf(offset) else null
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error getting accumulated audio: ${e.message}" }
            return null
        }
    }
    
    /**
     * Obtiene audio mezclado de ambas fuentes
     */
    private fun getMixedAudio(durationMs: Long): ByteArray? {
        try {
            val remoteAudio = getAccumulatedAudio(AudioTranscriptionService.AudioSource.WEBRTC_REMOTE, durationMs)
            val localAudio = getAccumulatedAudio(AudioTranscriptionService.AudioSource.WEBRTC_LOCAL, durationMs)
            
            if (remoteAudio == null && localAudio == null) return null
            if (remoteAudio == null) return localAudio
            if (localAudio == null) return remoteAudio
            
            // Mezclar ambos audios
            val minSize = minOf(remoteAudio.size, localAudio.size)
            val mixed = ByteArray(minSize)
            
            val remoteBuffer = ByteBuffer.wrap(remoteAudio).order(ByteOrder.LITTLE_ENDIAN)
            val localBuffer = ByteBuffer.wrap(localAudio).order(ByteOrder.LITTLE_ENDIAN)
            val mixedBuffer = ByteBuffer.wrap(mixed).order(ByteOrder.LITTLE_ENDIAN)
            
            while (remoteBuffer.remaining() >= 2 && localBuffer.remaining() >= 2 && mixedBuffer.remaining() >= 2) {
                val remoteSample = remoteBuffer.short.toInt()
                val localSample = localBuffer.short.toInt()
                
                // Mezclar con atenuación para evitar clipping
                val mixedSample = ((remoteSample + localSample) / 2).coerceIn(-32768, 32767)
                mixedBuffer.putShort(mixedSample.toShort())
            }
            
            return mixed
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error mixing audio: ${e.message}" }
            return null
        }
    }
    
    /**
     * Configura callbacks para audio interceptado
     */
    fun setCallbacks(
        onRemoteAudio: ((ByteArray) -> Unit)? = null,
        onLocalAudio: ((ByteArray) -> Unit)? = null,
        onAudioLevel: ((Float, AudioTranscriptionService.AudioSource) -> Unit)? = null
    ) {
        this.onRemoteAudioCallback = onRemoteAudio
        this.onLocalAudioCallback = onLocalAudio
        this.onAudioLevelCallback = onAudioLevel
    }
    
    /**
     * Limpia buffers de audio
     */
    fun clearBuffers() {
        remoteAudioBuffer.clear()
        localAudioBuffer.clear()
        log.d(tag = TAG) { "Audio buffers cleared" }
    }
    
    /**
     * Obtiene estadísticas de procesamiento
     */
    fun getProcessingStatistics(): ProcessingStatistics {
        val currentTime = System.currentTimeMillis()
        val processingDuration = currentTime - lastProcessingTime
        
        return ProcessingStatistics(
            totalFramesProcessed = totalFramesProcessed,
            totalBytesProcessed = totalBytesProcessed,
            remoteBufferSize = remoteAudioBuffer.size,
            localBufferSize = localAudioBuffer.size,
            processingDuration = processingDuration,
            averageBytesPerSecond = if (processingDuration > 0) {
                (totalBytesProcessed * 1000 / processingDuration).toFloat()
            } else 0f,
            currentAudioLevel = _audioLevelFlow.value,
            audioQuality = _audioQualityFlow.value
        )
    }
    
    data class ProcessingStatistics(
        val totalFramesProcessed: Long,
        val totalBytesProcessed: Long,
        val remoteBufferSize: Int,
        val localBufferSize: Int,
        val processingDuration: Long,
        val averageBytesPerSecond: Float,
        val currentAudioLevel: Float,
        val audioQuality: AudioQuality
    )
    
    /**
     * Información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        val stats = getProcessingStatistics()
        
        return buildString {
            appendLine("=== WEBRTC AUDIO INTERCEPTOR DIAGNOSTIC ===")
            appendLine("Is Intercepting: ${_isInterceptingFlow.value}")
            appendLine("Sample Rate: ${sampleRate}Hz")
            appendLine("Channels: $channels")
            appendLine("Bits Per Sample: $bitsPerSample")
            appendLine("Frame Size: $frameSize samples")
            
            appendLine("\n--- Processing Statistics ---")
            appendLine("Total Frames: ${stats.totalFramesProcessed}")
            appendLine("Total Bytes: ${stats.totalBytesProcessed}")
            appendLine("Remote Buffer: ${stats.remoteBufferSize} frames")
            appendLine("Local Buffer: ${stats.localBufferSize} frames")
            appendLine("Processing Duration: ${stats.processingDuration}ms")
            appendLine("Avg Bytes/sec: ${stats.averageBytesPerSecond}")
            appendLine("Current Audio Level: ${stats.currentAudioLevel}")
            
            appendLine("\n--- Audio Quality ---")
            appendLine("Average Level: ${stats.audioQuality.averageLevel}")
            appendLine("Peak Level: ${stats.audioQuality.peakLevel}")
            appendLine("SNR: ${stats.audioQuality.signalToNoiseRatio}dB")
            appendLine("Clipping Detected: ${stats.audioQuality.clippingDetected}")
            appendLine("Silence Detected: ${stats.audioQuality.silenceDetected}")
        }
    }
    
    /**
     * Limpieza de recursos
     */
    fun dispose() {
        stopIntercepting()
        clearBuffers()
        onRemoteAudioCallback = null
        onLocalAudioCallback = null
        onAudioLevelCallback = null
        log.d(tag = TAG) { "WebRtcAudioInterceptor disposed" }
    }
}