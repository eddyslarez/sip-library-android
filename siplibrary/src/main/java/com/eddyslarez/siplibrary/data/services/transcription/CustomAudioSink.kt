package com.eddyslarez.siplibrary.data.services.transcription

import org.webrtc.AudioTrackSink
import java.nio.ByteBuffer

// CUSTOM AUDIO SINK CORREGIDO PARA WEBRTC
class CustomAudioSink(
    private val onAudioData: (ByteArray) -> Unit
) : AudioTrackSink {

    override fun onData(
        audioData: ByteBuffer?,
        bitsPerSample: Int,
        sampleRate: Int,
        numberOfChannels: Int,
        numberOfFrames: Int,
        timestamp: Long
    ) {
        audioData?.let { buffer ->
            try {
                // Crear array del tamaño correcto
                val audioBytes = ByteArray(buffer.remaining())

                // Guardar posición actual
                val originalPosition = buffer.position()

                // Leer datos
                buffer.get(audioBytes)

                // Restaurar posición para otros consumers
                buffer.position(originalPosition)

                // Enviar datos al callback
                onAudioData(audioBytes)

            } catch (e: Exception) {
                // Log error pero no fallar
                android.util.Log.e("CustomAudioSink", "Error processing audio data: ${e.message}")
            }
        }
    }
}