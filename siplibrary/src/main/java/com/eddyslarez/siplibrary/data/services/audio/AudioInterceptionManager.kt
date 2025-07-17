package com.eddyslarez.siplibrary.data.services.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gestor de intercepción de audio para capturar y reproducir audio personalizado
 * Maneja el formato PCMA/8000 (G.711 A-law, 8kHz)
 *
 * @author Eddys Larez
 */
class AudioInterceptionManager {
    private val TAG = "AudioInterceptionManager"

    // Configuración de audio para PCMA/8000
    companion object {
        const val SAMPLE_RATE = 8000
        const val CHANNEL_CONFIG_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_CONFIG_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val BUFFER_SIZE_FACTOR = 2
    }

    private val scope = CoroutineScope(Dispatchers.IO)
    private val isIntercepting = AtomicBoolean(false)
    private val isRecording = AtomicBoolean(false)
    private val isPlaying = AtomicBoolean(false)

    // Audio Recording/Playback
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var recordingJob: Job? = null
    private var playbackJob: Job? = null

    // Buffer sizes
    private val recordBufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        CHANNEL_CONFIG_IN,
        AUDIO_FORMAT
    ) * BUFFER_SIZE_FACTOR

    private val playBufferSize = AudioTrack.getMinBufferSize(
        SAMPLE_RATE,
        CHANNEL_CONFIG_OUT,
        AUDIO_FORMAT
    ) * BUFFER_SIZE_FACTOR

    // Audio data storage
    private val receivedAudioBuffer = ByteArrayOutputStream()
    private val sentAudioBuffer = ByteArrayOutputStream()

    // Custom audio sources
    private var customInputAudio: ByteArray? = null
    private var customOutputAudio: ByteArray? = null
    private var customInputPosition = 0
    private var customOutputPosition = 0

    // Listeners
    private var onAudioReceived: ((ByteArray) -> Unit)? = null
    private var onAudioSent: ((ByteArray) -> Unit)? = null
    private var onAudioRecorded: ((ByteArray) -> Unit)? = null

    // Settings
    private var saveReceivedAudio = false
    private var saveSentAudio = false
    private var useCustomInput = false
    private var useCustomOutput = false

    /**
     * Inicia la intercepción de audio
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startInterception() {
        if (isIntercepting.get()) {
            log.w(TAG) { "Audio interception already started" }
            return
        }

        log.d(TAG) { "Starting audio interception" }

        try {
            setupAudioRecord()
            setupAudioTrack()
            startRecording()
            startPlayback()

            isIntercepting.set(true)
            log.d(TAG) { "Audio interception started successfully" }

        } catch (e: Exception) {
            log.e(TAG) { "Error starting audio interception: ${e.message}" }
            stopInterception()
        }
    }

    /**
     * Detiene la intercepción de audio
     */
    fun stopInterception() {
        if (!isIntercepting.get()) {
            return
        }

        log.d(TAG) { "Stopping audio interception" }

        isIntercepting.set(false)

        // Detener recording
        stopRecording()

        // Detener playback
        stopPlayback()

        // Cleanup
        cleanupAudioRecord()
        cleanupAudioTrack()

        log.d(TAG) { "Audio interception stopped" }
    }

    /**
     * Configura el AudioRecord para capturar audio del micrófono
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun setupAudioRecord() {
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG_IN,
                AUDIO_FORMAT,
                recordBufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw RuntimeException("AudioRecord initialization failed")
            }

            log.d(TAG) { "AudioRecord setup successful" }

        } catch (e: Exception) {
            log.e(TAG) { "Error setting up AudioRecord: ${e.message}" }
            throw e
        }
    }

    /**
     * Configura el AudioTrack para reproducir audio
     */
    private fun setupAudioTrack() {
        try {
            audioTrack = AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                SAMPLE_RATE,
                CHANNEL_CONFIG_OUT,
                AUDIO_FORMAT,
                playBufferSize,
                AudioTrack.MODE_STREAM
            )

            if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                throw RuntimeException("AudioTrack initialization failed")
            }

            log.d(TAG) { "AudioTrack setup successful" }

        } catch (e: Exception) {
            log.e(TAG) { "Error setting up AudioTrack: ${e.message}" }
            throw e
        }
    }

    /**
     * Inicia la grabación de audio
     */
    private fun startRecording() {
        audioRecord?.let { record ->
            recordingJob = scope.launch {
                isRecording.set(true)
                record.startRecording()

                val buffer = ByteArray(recordBufferSize)

                while (isActive && isRecording.get()) {
                    try {
                        val audioData = if (useCustomInput && customInputAudio != null) {
                            // Usar audio personalizado como entrada
                            getCustomInputAudio(buffer.size)
                        } else {
                            // Leer del micrófono
                            val bytesRead = record.read(buffer, 0, buffer.size)
                            if (bytesRead > 0) {
                                buffer.copyOfRange(0, bytesRead)
                            } else {
                                byteArrayOf()
                            }
                        }

                        if (audioData.isNotEmpty()) {
                            // Convertir a PCMA si es necesario
                            val pcmaData = AudioFormatConverter.convertToPCMA(audioData)

                            // Guardar audio enviado si está habilitado
                            if (saveSentAudio) {
                                synchronized(sentAudioBuffer) {
                                    sentAudioBuffer.write(pcmaData)
                                }
                            }

                            // Notificar audio enviado
                            onAudioSent?.invoke(pcmaData)
                        }

                    } catch (e: Exception) {
                        log.e(TAG) { "Error in recording loop: ${e.message}" }
                        break
                    }
                }

                record.stop()
                isRecording.set(false)
                log.d(TAG) { "Recording stopped" }
            }
        }
    }

    /**
     * Inicia la reproducción de audio
     */
    private fun startPlayback() {
        audioTrack?.let { track ->
            playbackJob = scope.launch {
                isPlaying.set(true)
                track.play()

                while (isActive && isPlaying.get()) {
                    try {
                        // Simular audio recibido (en una implementación real esto vendría de WebRTC)
                        val receivedAudio = simulateReceivedAudio()

                        if (receivedAudio.isNotEmpty()) {
                            val audioToPlay = if (useCustomOutput && customOutputAudio != null) {
                                // Usar audio personalizado para reproducción
                                getCustomOutputAudio(receivedAudio.size)
                            } else {
                                // Convertir de PCMA a PCM para reproducción
                                AudioFormatConverter.convertFromPCMA(receivedAudio)
                            }

                            if (audioToPlay.isNotEmpty()) {
                                // Reproducir audio
                                track.write(audioToPlay, 0, audioToPlay.size)

                                // Guardar audio recibido si está habilitado
                                if (saveReceivedAudio) {
                                    synchronized(receivedAudioBuffer) {
                                        receivedAudioBuffer.write(receivedAudio)
                                    }
                                }

                                // Notificar audio recibido
                                onAudioReceived?.invoke(receivedAudio)
                            }
                        }

                        Thread.sleep(20) // 20ms delay similar al RTP

                    } catch (e: Exception) {
                        log.e(TAG) { "Error in playback loop: ${e.message}" }
                        break
                    }
                }

                track.stop()
                isPlaying.set(false)
                log.d(TAG) { "Playback stopped" }
            }
        }
    }

    /**
     * Simula audio recibido (en implementación real esto vendría de WebRTC)
     */
    private fun simulateReceivedAudio(): ByteArray {
        // Esta función sería reemplazada por la integración real con WebRTC
        // Por ahora retorna un buffer vacío
        return byteArrayOf()
    }

    /**
     * Obtiene audio personalizado para entrada
     */
    private fun getCustomInputAudio(size: Int): ByteArray {
        val customAudio = customInputAudio ?: return byteArrayOf()

        if (customInputPosition >= customAudio.size) {
            customInputPosition = 0 // Loop
        }

        val availableBytes = minOf(size, customAudio.size - customInputPosition)
        val result = customAudio.copyOfRange(customInputPosition, customInputPosition + availableBytes)
        customInputPosition += availableBytes

        return result
    }

    /**
     * Obtiene audio personalizado para salida
     */
    private fun getCustomOutputAudio(size: Int): ByteArray {
        val customAudio = customOutputAudio ?: return byteArrayOf()

        if (customOutputPosition >= customAudio.size) {
            customOutputPosition = 0 // Loop
        }

        val availableBytes = minOf(size, customAudio.size - customOutputPosition)
        val result = customAudio.copyOfRange(customOutputPosition, customOutputPosition + availableBytes)
        customOutputPosition += availableBytes

        return result
    }

    /**
     * Detiene la grabación
     */
    private fun stopRecording() {
        isRecording.set(false)
        recordingJob?.cancel()
        recordingJob = null
    }

    /**
     * Detiene la reproducción
     */
    private fun stopPlayback() {
        isPlaying.set(false)
        playbackJob?.cancel()
        playbackJob = null
    }

    /**
     * Limpia el AudioRecord
     */
    private fun cleanupAudioRecord() {
        audioRecord?.let { record ->
            try {
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    record.stop()
                }
                record.release()
            } catch (e: Exception) {
                log.e(TAG) { "Error cleaning up AudioRecord: ${e.message}" }
            }
        }
        audioRecord = null
    }

    /**
     * Limpia el AudioTrack
     */
    private fun cleanupAudioTrack() {
        audioTrack?.let { track ->
            try {
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    track.stop()
                }
                track.release()
            } catch (e: Exception) {
                log.e(TAG) { "Error cleaning up AudioTrack: ${e.message}" }
            }
        }
        audioTrack = null
    }

    // === MÉTODOS PÚBLICOS PARA CONFIGURACIÓN ===

    /**
     * Configura el audio personalizado para entrada (micrófono)
     */
    fun setCustomInputAudio(audioData: ByteArray?) {
        customInputAudio = audioData
        customInputPosition = 0
        useCustomInput = audioData != null
        log.d(TAG) { "Custom input audio set: ${audioData?.size ?: 0} bytes" }
    }

    /**
     * Configura el audio personalizado para salida (altavoz)
     */
    fun setCustomOutputAudio(audioData: ByteArray?) {
        customOutputAudio = audioData
        customOutputPosition = 0
        useCustomOutput = audioData != null
        log.d(TAG) { "Custom output audio set: ${audioData?.size ?: 0} bytes" }
    }

    /**
     * Habilita/deshabilita el guardado de audio recibido
     */
    fun setSaveReceivedAudio(enabled: Boolean) {
        saveReceivedAudio = enabled
        if (!enabled) {
            synchronized(receivedAudioBuffer) {
                receivedAudioBuffer.reset()
            }
        }
        log.d(TAG) { "Save received audio: $enabled" }
    }

    /**
     * Habilita/deshabilita el guardado de audio enviado
     */
    fun setSaveSentAudio(enabled: Boolean) {
        saveSentAudio = enabled
        if (!enabled) {
            synchronized(sentAudioBuffer) {
                sentAudioBuffer.reset()
            }
        }
        log.d(TAG) { "Save sent audio: $enabled" }
    }

    /**
     * Obtiene el audio recibido guardado
     */
    fun getSavedReceivedAudio(): ByteArray {
        return synchronized(receivedAudioBuffer) {
            receivedAudioBuffer.toByteArray()
        }
    }

    /**
     * Obtiene el audio enviado guardado
     */
    fun getSavedSentAudio(): ByteArray {
        return synchronized(sentAudioBuffer) {
            sentAudioBuffer.toByteArray()
        }
    }

    /**
     * Limpia el audio guardado
     */
    fun clearSavedAudio() {
        synchronized(receivedAudioBuffer) {
            receivedAudioBuffer.reset()
        }
        synchronized(sentAudioBuffer) {
            sentAudioBuffer.reset()
        }
        log.d(TAG) { "Saved audio cleared" }
    }

    /**
     * Configura los listeners de audio
     */
    fun setAudioListeners(
        onReceived: ((ByteArray) -> Unit)? = null,
        onSent: ((ByteArray) -> Unit)? = null,
        onRecorded: ((ByteArray) -> Unit)? = null
    ) {
        onAudioReceived = onReceived
        onAudioSent = onSent
        onAudioRecorded = onRecorded
        log.d(TAG) { "Audio listeners configured" }
    }

    /**
     * Verifica si la intercepción está activa
     */
    fun isIntercepting(): Boolean = isIntercepting.get()

    /**
     * Información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== AUDIO INTERCEPTION DIAGNOSTIC ===")
            appendLine("Is intercepting: ${isIntercepting.get()}")
            appendLine("Is recording: ${isRecording.get()}")
            appendLine("Is playing: ${isPlaying.get()}")
            appendLine("Use custom input: $useCustomInput")
            appendLine("Use custom output: $useCustomOutput")
            appendLine("Save received audio: $saveReceivedAudio")
            appendLine("Save sent audio: $saveSentAudio")
            appendLine("Custom input size: ${customInputAudio?.size ?: 0} bytes")
            appendLine("Custom output size: ${customOutputAudio?.size ?: 0} bytes")
            appendLine("Saved received size: ${getSavedReceivedAudio().size} bytes")
            appendLine("Saved sent size: ${getSavedSentAudio().size} bytes")
            appendLine("Record buffer size: $recordBufferSize")
            appendLine("Play buffer size: $playBufferSize")
            appendLine("Sample rate: $SAMPLE_RATE Hz")
            appendLine("Audio format: ${if (AUDIO_FORMAT == AudioFormat.ENCODING_PCM_16BIT) "PCM 16-bit" else "Unknown"}")
        }
    }
}