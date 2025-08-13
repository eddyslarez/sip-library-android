package com.eddyslarez.siplibrary.data.services.Assistant

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.eddyslarez.siplibrary.utils.log
import android.content.Context
import android.media.*
import com.eddyslarez.siplibrary.utils.log

/**
 * Capturador de audio para el asistente de tiempo real
 */
class AudioCapture(
    private val context: Context,  // AGREGAR CONTEXT COMO PARÁMETRO
    private val onAudioData: (ByteArray) -> Unit
) {
    private val TAG = "AudioCapture"

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    // Configuración de audio
    private val sampleRate = 24000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

    fun startCapturing() {
        if (isRecording) return

        try {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED) {
                log.e(TAG) { "Audio recording permission not granted" }
                return
            }

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            audioRecord?.startRecording()
            isRecording = true

            recordingThread = Thread {
                captureAudioLoop()
            }
            recordingThread?.start()

            log.d(TAG) { "Audio capture started" }
        } catch (e: Exception) {
            log.e(TAG) { "Error starting audio capture: ${e.message}" }
        }
    }

    private fun captureAudioLoop() {
        val buffer = ByteArray(bufferSize)

        while (isRecording) {
            try {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (bytesRead > 0) {
                    onAudioData(buffer.copyOf(bytesRead))
                }
            } catch (e: Exception) {
                log.e(TAG) { "Error reading audio data: ${e.message}" }
                break
            }
        }
    }

    fun stop() {
        isRecording = false
        recordingThread?.join()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        log.d(TAG) { "Audio capture stopped" }
    }
}