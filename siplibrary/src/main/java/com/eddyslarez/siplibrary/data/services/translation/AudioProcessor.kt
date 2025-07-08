package com.eddyslarez.siplibrary.data.services.translation

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.eddyslarez.siplibrary.utils.log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Procesador de audio para traducción en tiempo real
 * 
 * @author Eddys Larez
 */
class AudioProcessor {
    
    companion object {
        private const val TAG = "AudioProcessor"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    /**
     * Convierte audio PCM a formato WAV
     */
    fun convertToWav(pcmData: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val outputStream = ByteArrayOutputStream()
        
        try {
            // WAV header
            val channels = 1 // Mono
            val bitsPerSample = 16
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val dataSize = pcmData.size
            val fileSize = 36 + dataSize
            
            // RIFF header
            outputStream.write("RIFF".toByteArray())
            outputStream.write(intToByteArray(fileSize))
            outputStream.write("WAVE".toByteArray())
            
            // fmt chunk
            outputStream.write("fmt ".toByteArray())
            outputStream.write(intToByteArray(16)) // chunk size
            outputStream.write(shortToByteArray(1)) // audio format (PCM)
            outputStream.write(shortToByteArray(channels.toShort()))
            outputStream.write(intToByteArray(sampleRate))
            outputStream.write(intToByteArray(byteRate))
            outputStream.write(shortToByteArray(blockAlign.toShort()))
            outputStream.write(shortToByteArray(bitsPerSample.toShort()))
            
            // data chunk
            outputStream.write("data".toByteArray())
            outputStream.write(intToByteArray(dataSize))
            outputStream.write(pcmData)
            
            return outputStream.toByteArray()
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error converting to WAV: ${e.message}" }
            return byteArrayOf()
        } finally {
            outputStream.close()
        }
    }

    /**
     * Normaliza el volumen del audio
     */
    fun normalizeAudio(audioData: ByteArray): ByteArray {
        try {
            val buffer = ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN)
            val samples = ShortArray(audioData.size / 2)
            
            // Leer samples
            for (i in samples.indices) {
                samples[i] = buffer.getShort(i * 2)
            }
            
            // Encontrar el valor máximo
            var maxValue = 0
            for (sample in samples) {
                val absValue = kotlin.math.abs(sample.toInt())
                if (absValue > maxValue) {
                    maxValue = absValue
                }
            }
            
            // Normalizar si es necesario
            if (maxValue > 0 && maxValue < Short.MAX_VALUE) {
                val scaleFactor = Short.MAX_VALUE.toFloat() / maxValue.toFloat() * 0.8f // 80% del máximo
                
                for (i in samples.indices) {
                    samples[i] = (samples[i] * scaleFactor).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }
            
            // Convertir de vuelta a bytes
            val outputBuffer = ByteBuffer.allocate(audioData.size).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in samples) {
                outputBuffer.putShort(sample)
            }
            
            return outputBuffer.array()
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error normalizing audio: ${e.message}" }
            return audioData
        }
    }

    /**
     * Aplica filtro de ruido básico
     */
    fun applyNoiseFilter(audioData: ByteArray): ByteArray {
        try {
            val buffer = ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN)
            val samples = ShortArray(audioData.size / 2)
            
            // Leer samples
            for (i in samples.indices) {
                samples[i] = buffer.getShort(i * 2)
            }
            
            // Aplicar filtro simple de media móvil
            val windowSize = 3
            val filteredSamples = ShortArray(samples.size)
            
            for (i in samples.indices) {
                var sum = 0L
                var count = 0
                
                for (j in -windowSize/2..windowSize/2) {
                    val index = i + j
                    if (index >= 0 && index < samples.size) {
                        sum += samples[index]
                        count++
                    }
                }
                
                filteredSamples[i] = (sum / count).toShort()
            }
            
            // Convertir de vuelta a bytes
            val outputBuffer = ByteBuffer.allocate(audioData.size).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in filteredSamples) {
                outputBuffer.putShort(sample)
            }
            
            return outputBuffer.array()
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error applying noise filter: ${e.message}" }
            return audioData
        }
    }

    /**
     * Detecta si hay actividad de voz en el audio
     */
    fun detectVoiceActivity(audioData: ByteArray, threshold: Float = 0.1f): Boolean {
        try {
            val buffer = ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN)
            val samples = ShortArray(audioData.size / 2)
            
            // Leer samples
            for (i in samples.indices) {
                samples[i] = buffer.getShort(i * 2)
            }
            
            // Calcular RMS (Root Mean Square)
            var sumSquares = 0.0
            for (sample in samples) {
                sumSquares += (sample * sample).toDouble()
            }
            
            val rms = kotlin.math.sqrt(sumSquares / samples.size)
            val normalizedRms = rms / Short.MAX_VALUE
            
            return normalizedRms > threshold
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error detecting voice activity: ${e.message}" }
            return false
        }
    }

    /**
     * Convierte MP3 a PCM (simplificado)
     */
    fun convertMp3ToPcm(mp3Data: ByteArray): ByteArray {
        // Esta es una implementación simplificada
        // En una implementación real, usarías una biblioteca como FFmpeg
        log.w(tag = TAG) { "MP3 to PCM conversion not fully implemented" }
        return mp3Data
    }

    /**
     * Redimensiona el audio a la duración especificada
     */
    fun resizeAudio(audioData: ByteArray, targetDurationMs: Int, sampleRate: Int = SAMPLE_RATE): ByteArray {
        try {
            val targetSamples = (targetDurationMs * sampleRate) / 1000
            val targetBytes = targetSamples * 2 // 16-bit audio
            
            if (audioData.size == targetBytes) {
                return audioData
            }
            
            val buffer = ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN)
            val samples = ShortArray(audioData.size / 2)
            
            // Leer samples originales
            for (i in samples.indices) {
                samples[i] = buffer.getShort(i * 2)
            }
            
            // Redimensionar usando interpolación lineal simple
            val resizedSamples = ShortArray(targetSamples)
            val ratio = samples.size.toFloat() / targetSamples.toFloat()
            
            for (i in resizedSamples.indices) {
                val sourceIndex = (i * ratio).toInt()
                if (sourceIndex < samples.size) {
                    resizedSamples[i] = samples[sourceIndex]
                }
            }
            
            // Convertir de vuelta a bytes
            val outputBuffer = ByteBuffer.allocate(targetBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in resizedSamples) {
                outputBuffer.putShort(sample)
            }
            
            return outputBuffer.array()
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error resizing audio: ${e.message}" }
            return audioData
        }
    }

    fun dispose() {
        // Limpiar recursos si es necesario
    }

    // Funciones auxiliares
    private fun intToByteArray(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    private fun shortToByteArray(value: Short): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()
    }
}