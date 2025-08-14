package com.eddyslarez.siplibrary.data.services.ia

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.eddyslarez.siplibrary.utils.log

class AudioCapture(private val sampleRate: Int) {
    private var audioRecord: android.media.AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(onAudioData: (FloatArray) -> Unit) {
        try {
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            isRecording = true
            recordingThread = Thread {
                val buffer = ShortArray(bufferSize)
                audioRecord?.startRecording()

                while (isRecording) {
                    val samplesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (samplesRead > 0) {
                        // Convert to float array
                        val floatBuffer = FloatArray(samplesRead)
                        for (i in 0 until samplesRead) {
                            floatBuffer[i] = buffer[i].toFloat() / Short.MAX_VALUE
                        }
                        onAudioData(floatBuffer)
                    }
                }
            }

            recordingThread?.start()
        } catch (e: Exception) {
            log.e("AudioCapture") { "Error starting audio capture: ${e.message}" }
        }
    }

    fun stop() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        recordingThread?.interrupt()
    }
}