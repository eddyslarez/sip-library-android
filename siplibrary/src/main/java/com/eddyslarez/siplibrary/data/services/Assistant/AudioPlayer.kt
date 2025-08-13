package com.eddyslarez.siplibrary.data.services.Assistant

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.eddyslarez.siplibrary.utils.log

/**
 * Reproductor de audio para respuestas del asistente
 */
class AudioPlayer {
    private val TAG = "AudioPlayer"

    private var audioTrack: AudioTrack? = null
    private var isPlaying = false

    // Configuración de audio
    private val sampleRate = 24000
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    fun playAudio(audioData: ByteArray) {
        try {
            if (audioTrack == null) {
                setupAudioTrack()
            }

            audioTrack?.write(audioData, 0, audioData.size)

            if (!isPlaying) {
                audioTrack?.play()
                isPlaying = true
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error playing audio: ${e.message}" }
        }
    }

    private fun setupAudioTrack() {
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

    fun stop() {
        isPlaying = false
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null

        log.d(TAG) { "Audio player stopped" }
    }
}
