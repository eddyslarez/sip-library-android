package com.eddyslarez.siplibrary.data.services.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Interceptor de audio WebRTC que permite reemplazar audio en tiempo real
 * Se integra directamente con el flujo de audio de WebRTC
 *
 * @author Eddys Larez
 */
class WebRtcAudioInterceptor {
    private val TAG = "WebRtcAudioInterceptor"

    // Configuración de audio para PCMA/8000
    companion object {
        const val SAMPLE_RATE = 8000
        const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BUFFER_SIZE_FACTOR = 2
        const val FRAME_SIZE_MS = 20 // 20ms frames
        const val FRAME_SIZE_BYTES = (SAMPLE_RATE * FRAME_SIZE_MS / 1000) * 2 // 16-bit = 2 bytes per sample
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val isActive = AtomicBoolean(false)

    // Audio replacement data
    private var customInputAudio: ByteArray? = null
    private var customOutputAudio: ByteArray? = null
    private var customInputPosition = 0
    private var customOutputPosition = 0

    // Audio interception flags
    private var interceptInput = false
    private var interceptOutput = false

    // Audio data queues for real-time processing
    private val inputAudioQueue = ConcurrentLinkedQueue<ByteArray>()
    private val outputAudioQueue = ConcurrentLinkedQueue<ByteArray>()

    // Original audio storage (for recording)
    private val originalReceivedAudio = ConcurrentLinkedQueue<ByteArray>()
    private val originalSentAudio = ConcurrentLinkedQueue<ByteArray>()

    // Listeners
    private var onOriginalAudioReceived: ((ByteArray) -> Unit)? = null
    private var onOriginalAudioSent: ((ByteArray) -> Unit)? = null
    private var onProcessedAudioReceived: ((ByteArray) -> Unit)? = null
    private var onProcessedAudioSent: ((ByteArray) -> Unit)? = null

    // Processing jobs
    private var inputProcessingJob: Job? = null
    private var outputProcessingJob: Job? = null

    /**
     * Inicia la intercepción de audio
     */
    fun start() {
        if (isActive.get()) {
            log.w(TAG) { "Audio interceptor already active" }
            return
        }

        log.d(TAG) { "Starting WebRTC audio interceptor" }

        isActive.set(true)
        startInputProcessing()
        startOutputProcessing()

        log.d(TAG) { "WebRTC audio interceptor started" }
    }

    /**
     * Detiene la intercepción de audio
     */
    fun stop() {
        if (!isActive.get()) {
            return
        }

        log.d(TAG) { "Stopping WebRTC audio interceptor" }

        isActive.set(false)

        inputProcessingJob?.cancel()
        outputProcessingJob?.cancel()

        inputAudioQueue.clear()
        outputAudioQueue.clear()
        originalReceivedAudio.clear()
        originalSentAudio.clear()

        log.d(TAG) { "WebRTC audio interceptor stopped" }
    }

    /**
     * Procesa audio de entrada (micrófono -> WebRTC)
     */
    private fun startInputProcessing() {
        inputProcessingJob = scope.launch {
            while ( isActive) {
                try {
                    // Simular captura de audio del micrófono
                    val originalAudio = captureInputAudio()

                    if (originalAudio.isNotEmpty()) {
                        // Guardar audio original para grabación
                        originalSentAudio.offer(originalAudio)
                        onOriginalAudioSent?.invoke(originalAudio)

                        // Decidir qué audio enviar
                        val audioToSend = if (interceptInput && customInputAudio != null) {
                            getCustomInputFrame()
                        } else {
                            originalAudio
                        }

                        // Enviar audio procesado
                        if (audioToSend.isNotEmpty()) {
                            inputAudioQueue.offer(audioToSend)
                            onProcessedAudioSent?.invoke(audioToSend)
                        }
                    }

                    // Esperar el siguiente frame (20ms)
                    kotlinx.coroutines.delay(FRAME_SIZE_MS.toLong())

                } catch (e: Exception) {
                    log.e(TAG) { "Error in input processing: ${e.message}" }
                }
            }
        }
    }

    /**
     * Procesa audio de salida (WebRTC -> altavoz)
     */
    private fun startOutputProcessing() {
        outputProcessingJob = scope.launch {
            while (isActive) {
                try {
                    // Simular recepción de audio de WebRTC
                    val originalAudio = receiveOutputAudio()

                    if (originalAudio.isNotEmpty()) {
                        // Guardar audio original para grabación
                        originalReceivedAudio.offer(originalAudio)
                        onOriginalAudioReceived?.invoke(originalAudio)

                        // Decidir qué audio reproducir
                        val audioToPlay = if (interceptOutput && customOutputAudio != null) {
                            getCustomOutputFrame()
                        } else {
                            originalAudio
                        }

                        // Reproducir audio procesado
                        if (audioToPlay.isNotEmpty()) {
                            outputAudioQueue.offer(audioToPlay)
                            onProcessedAudioReceived?.invoke(audioToPlay)
                            playOutputAudio(audioToPlay)
                        }
                    }

                    // Esperar el siguiente frame (20ms)
                    kotlinx.coroutines.delay(FRAME_SIZE_MS.toLong())

                } catch (e: Exception) {
                    log.e(TAG) { "Error in output processing: ${e.message}" }
                }
            }
        }
    }

    /**
     * Simula la captura de audio del micrófono
     * En una implementación real, esto se integraría con WebRTC
     */
    private fun captureInputAudio(): ByteArray {
        // En una implementación real, esto capturaría audio del micrófono
        // Por ahora, retornamos un frame de silencio
        return ByteArray(FRAME_SIZE_BYTES) // Silencio
    }

    /**
     * Simula la recepción de audio de WebRTC
     * En una implementación real, esto interceptaría el audio recibido
     */
    private fun receiveOutputAudio(): ByteArray {
        // En una implementación real, esto interceptaría el audio de WebRTC
        // Por ahora, retornamos un frame de silencio
        return ByteArray(FRAME_SIZE_BYTES) // Silencio
    }

    /**
     * Reproduce audio en el altavoz
     */
    private fun playOutputAudio(audioData: ByteArray) {
        // En una implementación real, esto enviaría el audio al altavoz
        // Por ahora, solo loggeamos
        log.d(TAG) { "Playing audio frame: ${audioData.size} bytes" }
    }

    /**
     * Obtiene un frame de audio personalizado para entrada
     */
    private fun getCustomInputFrame(): ByteArray {
        val customAudio = customInputAudio ?: return ByteArray(FRAME_SIZE_BYTES)

        if (customInputPosition >= customAudio.size) {
            customInputPosition = 0 // Loop
        }

        val availableBytes = minOf(FRAME_SIZE_BYTES, customAudio.size - customInputPosition)
        val frame = ByteArray(FRAME_SIZE_BYTES)

        // Copiar datos disponibles
        System.arraycopy(customAudio, customInputPosition, frame, 0, availableBytes)
        customInputPosition += availableBytes

        // Rellenar con silencio si es necesario
        if (availableBytes < FRAME_SIZE_BYTES) {
            // El resto ya está lleno de ceros (silencio)
        }

        return frame
    }

    /**
     * Obtiene un frame de audio personalizado para salida
     */
    private fun getCustomOutputFrame(): ByteArray {
        val customAudio = customOutputAudio ?: return ByteArray(FRAME_SIZE_BYTES)

        if (customOutputPosition >= customAudio.size) {
            customOutputPosition = 0 // Loop
        }

        val availableBytes = minOf(FRAME_SIZE_BYTES, customAudio.size - customOutputPosition)
        val frame = ByteArray(FRAME_SIZE_BYTES)

        // Copiar datos disponibles
        System.arraycopy(customAudio, customOutputPosition, frame, 0, availableBytes)
        customOutputPosition += availableBytes

        // Rellenar con silencio si es necesario
        if (availableBytes < FRAME_SIZE_BYTES) {
            // El resto ya está lleno de ceros (silencio)
        }

        return frame
    }

    // === MÉTODOS PÚBLICOS DE CONFIGURACIÓN ===

    /**
     * Configura audio personalizado para entrada (reemplaza micrófono)
     */
    fun setCustomInputAudio(audioData: ByteArray?) {
        customInputAudio = audioData
        customInputPosition = 0
        log.d(TAG) { "Custom input audio set: ${audioData?.size ?: 0} bytes" }
    }

    /**
     * Configura audio personalizado para salida (reemplaza altavoz)
     */
    fun setCustomOutputAudio(audioData: ByteArray?) {
        customOutputAudio = audioData
        customOutputPosition = 0
        log.d(TAG) { "Custom output audio set: ${audioData?.size ?: 0} bytes" }
    }

    /**
     * Habilita/deshabilita intercepción de entrada
     */
    fun setInterceptInput(enabled: Boolean) {
        interceptInput = enabled
        log.d(TAG) { "Input interception: $enabled" }
    }

    /**
     * Habilita/deshabilita intercepción de salida
     */
    fun setInterceptOutput(enabled: Boolean) {
        interceptOutput = enabled
        log.d(TAG) { "Output interception: $enabled" }
    }

    /**
     * Configura listeners para audio original y procesado
     */
    fun setAudioListeners(
        onOriginalReceived: ((ByteArray) -> Unit)? = null,
        onOriginalSent: ((ByteArray) -> Unit)? = null,
        onProcessedReceived: ((ByteArray) -> Unit)? = null,
        onProcessedSent: ((ByteArray) -> Unit)? = null
    ) {
        onOriginalAudioReceived = onOriginalReceived
        onOriginalAudioSent = onOriginalSent
        onProcessedAudioReceived = onProcessedReceived
        onProcessedAudioSent = onProcessedSent
        log.d(TAG) { "Audio listeners configured" }
    }

    /**
     * Obtiene audio original recibido acumulado
     */
    fun getOriginalReceivedAudio(): ByteArray {
        val result = mutableListOf<Byte>()
        while (originalReceivedAudio.isNotEmpty()) {
            originalReceivedAudio.poll()?.let { frame ->
                result.addAll(frame.toList())
            }
        }
        return result.toByteArray()
    }

    /**
     * Obtiene audio original enviado acumulado
     */
    fun getOriginalSentAudio(): ByteArray {
        val result = mutableListOf<Byte>()
        while (originalSentAudio.isNotEmpty()) {
            originalSentAudio.poll()?.let { frame ->
                result.addAll(frame.toList())
            }
        }
        return result.toByteArray()
    }

    /**
     * Limpia buffers de audio
     */
    fun clearAudioBuffers() {
        inputAudioQueue.clear()
        outputAudioQueue.clear()
        originalReceivedAudio.clear()
        originalSentAudio.clear()
        log.d(TAG) { "Audio buffers cleared" }
    }

    /**
     * Verifica si está activo
     */
    fun isActive(): Boolean = isActive.get()

    /**
     * Información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== WEBRTC AUDIO INTERCEPTOR DIAGNOSTIC ===")
            appendLine("Is active: ${isActive.get()}")
            appendLine("Intercept input: $interceptInput")
            appendLine("Intercept output: $interceptOutput")
            appendLine("Custom input audio: ${customInputAudio?.size ?: 0} bytes")
            appendLine("Custom output audio: ${customOutputAudio?.size ?: 0} bytes")
            appendLine("Input position: $customInputPosition")
            appendLine("Output position: $customOutputPosition")
            appendLine("Input queue size: ${inputAudioQueue.size}")
            appendLine("Output queue size: ${outputAudioQueue.size}")
            appendLine("Original received queue: ${originalReceivedAudio.size}")
            appendLine("Original sent queue: ${originalSentAudio.size}")
            appendLine("Sample rate: $SAMPLE_RATE Hz")
            appendLine("Frame size: $FRAME_SIZE_BYTES bytes ($FRAME_SIZE_MS ms)")
        }
    }
}