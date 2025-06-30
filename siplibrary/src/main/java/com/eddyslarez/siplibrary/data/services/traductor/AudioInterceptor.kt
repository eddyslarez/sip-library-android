package com.eddyslarez.siplibrary.data.services.traductor

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AudioInterceptor {
    private val TAG = "AudioInterceptor"
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    @SuppressLint("MissingPermission")
    fun startIntercepting(onAudioData: (ByteArray) -> Unit) {
        if (isRecording) return

        try {
            val sampleRate = 16000 // Mejor calidad para reconocimiento
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

            // Intentar diferentes fuentes de audio para capturar audio remoto
            val audioSources = arrayOf(
                MediaRecorder.AudioSource.VOICE_DOWNLINK, // Audio que llega (remoto)
                MediaRecorder.AudioSource.VOICE_CALL,     // Audio de llamada completo
                MediaRecorder.AudioSource.MIC,            // Micrófono (local)
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            )

            for (audioSource in audioSources) {
                try {
                    audioRecord = AudioRecord(
                        audioSource,
                        sampleRate,
                        channelConfig,
                        audioFormat,
                        bufferSize
                    )

                    if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                        Log.d(TAG, "AudioRecord inicializado con fuente: $audioSource")
                        break
                    } else {
                        audioRecord?.release()
                        audioRecord = null
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Error con fuente $audioSource: ${e.message}")
                    audioRecord?.release()
                    audioRecord = null
                }
            }

            val record = audioRecord ?: throw IllegalStateException("No se pudo inicializar AudioRecord")

            record.startRecording()
            isRecording = true

            recordingJob = scope.launch {
                val buffer = ByteArray(bufferSize)

                while (isActive && isRecording) {
                    try {
                        val bytesRead = record.read(buffer, 0, buffer.size)
                        if (bytesRead > 0) {
                            val audioData = buffer.copyOf(bytesRead)
                            onAudioData(audioData)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error leyendo audio", e)
                        break
                    }
                    delay(20) // 50 FPS de procesamiento de audio
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando interceptación de audio", e)
            stopIntercepting()
        }
    }

    fun stopIntercepting() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            Log.e(TAG, "Error deteniendo interceptación", e)
        }
    }
}