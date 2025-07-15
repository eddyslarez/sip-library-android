package com.eddyslarez.siplibrary.data.services.translation

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Procesador de audio para traducción en tiempo real
 * Maneja la captura, procesamiento y reproducción de audio traducido
 * 
 * @author Eddys Larez
 */
class TranslationAudioProcessor {
    companion object {
        private const val TAG = "TranslationAudioProcessor"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 2
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var isRecording = false
    private var isPlaying = false
    
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordingJob: Job? = null
    
    // Estados
    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()
    
    // Buffers de audio
    private val inputBuffer = mutableListOf<ByteArray>()
    private val outputBuffer = mutableListOf<ByteArray>()
    
    // Callbacks
    private var audioInputCallback: ((ByteArray) -> Unit)? = null
    private var audioOutputCallback: ((ByteArray) -> Unit)? = null
    
    /**
     * Inicializar el procesador de audio
     */
    fun initialize() {
        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            ) * BUFFER_SIZE_FACTOR
            
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            
            val playbackBufferSize = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AUDIO_FORMAT
            ) * BUFFER_SIZE_FACTOR
            
            audioTrack = AudioTrack(
                android.media.AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AUDIO_FORMAT,
                playbackBufferSize,
                AudioTrack.MODE_STREAM
            )
            
            log.d(tag = TAG) { "Audio processor initialized" }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error initializing audio processor: ${e.message}" }
        }
    }
    
    /**
     * Configurar callback para audio de entrada
     */
    fun setAudioInputCallback(callback: (ByteArray) -> Unit) {
        this.audioInputCallback = callback
    }
    
    /**
     * Configurar callback para audio de salida
     */
    fun setAudioOutputCallback(callback: (ByteArray) -> Unit) {
        this.audioOutputCallback = callback
    }
    
    /**
     * Iniciar captura de audio
     */
    fun startAudioCapture() {
        if (isRecording) return
        
        try {
            audioRecord?.startRecording()
            isRecording = true
            _isProcessing.value = true
            
            recordingJob = coroutineScope.launch {
                captureAudioLoop()
            }
            
            log.d(tag = TAG) { "Audio capture started" }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error starting audio capture: ${e.message}" }
        }
    }
    
    /**
     * Detener captura de audio
     */
    fun stopAudioCapture() {
        if (!isRecording) return
        
        try {
            isRecording = false
            recordingJob?.cancel()
            audioRecord?.stop()
            _isProcessing.value = false
            
            log.d(tag = TAG) { "Audio capture stopped" }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error stopping audio capture: ${e.message}" }
        }
    }
    
    /**
     * Reproducir audio traducido
     */
    fun playTranslatedAudio(audioData: ByteArray) {
        if (!isPlaying) {
            startAudioPlayback()
        }
        
        try {
            // Convertir a formato PCM16 si es necesario
            val pcmData = convertToPCM16(audioData)
            
            audioTrack?.write(pcmData, 0, pcmData.size)
            audioOutputCallback?.invoke(pcmData)
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error playing translated audio: ${e.message}" }
        }
    }
    
    /**
     * Iniciar reproducción de audio
     */
    private fun startAudioPlayback() {
        if (isPlaying) return
        
        try {
            audioTrack?.play()
            isPlaying = true
            log.d(tag = TAG) { "Audio playback started" }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error starting audio playback: ${e.message}" }
        }
    }
    
    /**
     * Detener reproducción de audio
     */
    fun stopAudioPlayback() {
        if (!isPlaying) return
        
        try {
            audioTrack?.stop()
            isPlaying = false
            log.d(tag = TAG) { "Audio playback stopped" }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error stopping audio playback: ${e.message}" }
        }
    }
    
    /**
     * Loop de captura de audio
     */
    private suspend fun captureAudioLoop() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        
        val buffer = ByteArray(bufferSize)
        
        while (isRecording && isActive) {
            try {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                
                if (bytesRead > 0) {
                    val audioChunk = buffer.copyOf(bytesRead)
                    
                    // Procesar audio (filtros, normalización, etc.)
                    val processedAudio = processAudioInput(audioChunk)
                    
                    // Enviar a callback para traducción
                    audioInputCallback?.invoke(processedAudio)
                    
                    // Agregar a buffer interno
                    synchronized(inputBuffer) {
                        inputBuffer.add(processedAudio)
                        
                        // Mantener buffer limitado
                        if (inputBuffer.size > 100) {
                            inputBuffer.removeAt(0)
                        }
                    }
                }
                
                delay(10) // Pequeña pausa para evitar uso excesivo de CPU
                
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in audio capture loop: ${e.message}" }
                break
            }
        }
    }
    
    /**
     * Procesar audio de entrada
     */
    private fun processAudioInput(audioData: ByteArray): ByteArray {
        // Aplicar filtros básicos
        return applyNoiseReduction(audioData)
    }
    
    /**
     * Aplicar reducción de ruido básica
     */
    private fun applyNoiseReduction(audioData: ByteArray): ByteArray {
        // Implementación básica de reducción de ruido
        val buffer = ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(audioData.size / 2)
        
        for (i in samples.indices) {
            samples[i] = buffer.getShort(i * 2)
        }
        
        // Aplicar filtro simple de paso alto para reducir ruido de baja frecuencia
        for (i in 1 until samples.size) {
            samples[i] = ((samples[i] * 0.95f) + (samples[i-1] * 0.05f)).toInt().toShort()
        }
        
        // Convertir de vuelta a ByteArray
        val outputBuffer = ByteBuffer.allocate(audioData.size).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            outputBuffer.putShort(sample)
        }
        
        return outputBuffer.array()
    }
    
    /**
     * Convertir audio a formato PCM16
     */
    private fun convertToPCM16(audioData: ByteArray): ByteArray {
        // Si ya está en PCM16, devolver tal como está
        if (audioData.size % 2 == 0) {
            return audioData
        }
        
        // Implementar conversión si es necesario
        return audioData
    }
    
    /**
     * Obtener estadísticas de audio
     */
    fun getAudioStats(): AudioStats {
        return AudioStats(
            isRecording = isRecording,
            isPlaying = isPlaying,
            inputBufferSize = inputBuffer.size,
            outputBufferSize = outputBuffer.size,
            sampleRate = SAMPLE_RATE
        )
    }
    
    /**
     * Limpiar buffers
     */
    fun clearBuffers() {
        synchronized(inputBuffer) {
            inputBuffer.clear()
        }
        synchronized(outputBuffer) {
            outputBuffer.clear()
        }
    }
    
    /**
     * Liberar recursos
     */
    fun dispose() {
        stopAudioCapture()
        stopAudioPlayback()
        
        coroutineScope.cancel()
        
        audioRecord?.release()
        audioTrack?.release()
        
        audioRecord = null
        audioTrack = null
        
        clearBuffers()
        
        log.d(tag = TAG) { "Audio processor disposed" }
    }
}

/**
 * Estadísticas de audio
 */
data class AudioStats(
    val isRecording: Boolean,
    val isPlaying: Boolean,
    val inputBufferSize: Int,
    val outputBufferSize: Int,
    val sampleRate: Int
)