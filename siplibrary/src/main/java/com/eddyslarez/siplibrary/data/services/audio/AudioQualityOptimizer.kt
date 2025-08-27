package com.eddyslarez.siplibrary.data.services.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.*

/**
 * Optimizador de calidad de audio para WebRTC y Speech-to-Text
 */
class AudioQualityOptimizer {
    companion object {
        private const val TAG = "AudioQualityOptimizer"

        // Configuraciones óptimas para máxima calidad
        const val OPTIMAL_SAMPLE_RATE = 48000 // Máxima calidad para WebRTC
        const val OPENAI_SAMPLE_RATE = 24000  // Requerido por OpenAI
        const val STT_SAMPLE_RATE = 16000     // Óptimo para STT
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val FRAME_SIZE_MS = 20          // Tamaño óptimo de frame

        // Parámetros de procesamiento
        const val NOISE_GATE_THRESHOLD = -45.0f // dB
        const val COMPRESSOR_RATIO = 4.0f
        const val COMPRESSOR_THRESHOLD = -20.0f
        const val HIGH_PASS_CUTOFF = 80.0f    // Hz - Elimina ruido de baja frecuencia
        const val LOW_PASS_CUTOFF = 8000.0f   // Hz - Anti-aliasing
    }

    /**
     * Obtiene la configuración óptima de audio para WebRTC
     */
    fun getOptimalWebRtcConfig(): AudioConfig {
        return AudioConfig(
            sampleRate = OPTIMAL_SAMPLE_RATE,
            channelConfig = CHANNEL_CONFIG,
            audioFormat = AUDIO_FORMAT,
            bufferSizeMs = FRAME_SIZE_MS,
            enableEchoCancellation = true,
            enableNoiseSuppression = true,
            enableAutoGainControl = true,
            enableHighPassFilter = true,
            enableVoiceActivityDetection = true
        )
    }

    /**
     * Obtiene la configuración óptima para OpenAI
     */
    fun getOptimalOpenAIConfig(): AudioConfig {
        return AudioConfig(
            sampleRate = OPENAI_SAMPLE_RATE,
            channelConfig = CHANNEL_CONFIG,
            audioFormat = AUDIO_FORMAT,
            bufferSizeMs = FRAME_SIZE_MS,
            enableEchoCancellation = true,
            enableNoiseSuppression = true,
            enableAutoGainControl = true,
            enableHighPassFilter = true,
            enableVoiceActivityDetection = true
        )
    }

    /**
     * Obtiene la configuración óptima para Speech-to-Text
     */
    fun getOptimalSTTConfig(): AudioConfig {
        return AudioConfig(
            sampleRate = STT_SAMPLE_RATE,
            channelConfig = CHANNEL_CONFIG,
            audioFormat = AUDIO_FORMAT,
            bufferSizeMs = FRAME_SIZE_MS,
            enableEchoCancellation = true,
            enableNoiseSuppression = true,
            enableAutoGainControl = true,
            enableHighPassFilter = true,
            enableVoiceActivityDetection = true
        )
    }

    /**
     * Calcula el tamaño de buffer óptimo
     */
    fun calculateOptimalBufferSize(sampleRate: Int, bufferSizeMs: Int = FRAME_SIZE_MS): Int {
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )

        val desiredBufferSize = (sampleRate * bufferSizeMs / 1000) * 2 // 16-bit = 2 bytes

        return maxOf(minBufferSize, desiredBufferSize)
    }

    /**
     * Analiza la calidad del audio capturado
     */
    fun analyzeAudioQuality(audioData: ByteArray, sampleRate: Int): AudioQualityMetrics {
        val samples = audioData.toShortArray()

        val rms = calculateRMS(samples)
        val snr = calculateSNR(samples)
        val dynamicRange = calculateDynamicRange(samples)
        val spectralCentroid = calculateSpectralCentroid(samples, sampleRate)
        val voiceActivityProbability = calculateVoiceActivityProbability(samples)

        return AudioQualityMetrics(
            rms = rms,
            snr = snr,
            dynamicRange = dynamicRange,
            spectralCentroid = spectralCentroid,
            voiceActivityProbability = voiceActivityProbability,
            qualityScore = calculateQualityScore(rms, snr, dynamicRange, voiceActivityProbability)
        )
    }

    private fun calculateRMS(samples: ShortArray): Float {
        var sum = 0.0
        for (sample in samples) {
            sum += (sample * sample).toDouble()
        }
        return sqrt(sum / samples.size).toFloat()
    }

    private fun calculateSNR(samples: ShortArray): Float {
        val rms = calculateRMS(samples)
        val noise = calculateNoiseFloor(samples)
        return if (noise > 0) 20 * log10(rms / noise) else Float.MAX_VALUE
    }

    private fun calculateNoiseFloor(samples: ShortArray): Float {
        val sortedSamples = samples.map { abs(it.toInt()) }.sorted()
        val percentile10 = sortedSamples[sortedSamples.size / 10]
        return percentile10.toFloat()
    }

    private fun calculateDynamicRange(samples: ShortArray): Float {
        val max = samples.maxOrNull()?.toFloat() ?: 0f
        val noise = calculateNoiseFloor(samples)
        return 20 * log10(max / noise)
    }

    private fun calculateSpectralCentroid(samples: ShortArray, sampleRate: Int): Float {
        // Implementación simplificada del centroide espectral
        val fft = performFFT(samples)
        var weightedSum = 0.0
        var magnitudeSum = 0.0

        for (i in fft.indices) {
            val frequency = i.toFloat() * sampleRate / fft.size
            val magnitude = sqrt(fft[i].real * fft[i].real + fft[i].imaginary * fft[i].imaginary)
            weightedSum += frequency * magnitude
            magnitudeSum += magnitude
        }

        return if (magnitudeSum > 0) (weightedSum / magnitudeSum).toFloat() else 0f
    }

    private fun calculateVoiceActivityProbability(samples: ShortArray): Float {
        val rms = calculateRMS(samples)
        val zeroCrossingRate = calculateZeroCrossingRate(samples)
        val spectralRolloff = calculateSpectralRolloff(samples)

        // Heurística simple para detectar actividad de voz
        var score = 0f

        // RMS en rango de voz
        if (rms > 1000 && rms < 20000) score += 0.3f

        // Zero crossing rate típico de voz
        if (zeroCrossingRate > 0.1 && zeroCrossingRate < 0.4) score += 0.3f

        // Rolloff espectral típico de voz
        if (spectralRolloff > 2000 && spectralRolloff < 6000) score += 0.4f

        return minOf(1.0f, score)
    }

    private fun calculateZeroCrossingRate(samples: ShortArray): Float {
        var crossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i] >= 0 && samples[i-1] < 0) || (samples[i] < 0 && samples[i-1] >= 0)) {
                crossings++
            }
        }
        return crossings.toFloat() / samples.size
    }

    private fun calculateSpectralRolloff(samples: ShortArray): Float {
        val fft = performFFT(samples)
        val magnitudes = fft.map { sqrt(it.real * it.real + it.imaginary * it.imaginary) }
        val totalEnergy = magnitudes.sum()
        val threshold = 0.85 * totalEnergy

        var cumulativeEnergy = 0.0
        for (i in magnitudes.indices) {
            cumulativeEnergy += magnitudes[i]
            if (cumulativeEnergy >= threshold) {
                return i.toFloat()
            }
        }
        return magnitudes.size.toFloat()
    }

    private fun calculateQualityScore(rms: Float, snr: Float, dynamicRange: Float, voiceActivity: Float): Float {
        val rmsScore = minOf(1.0f, rms / 10000f) * 0.25f
        val snrScore = minOf(1.0f, maxOf(0f, snr / 30f)) * 0.35f
        val dynamicScore = minOf(1.0f, maxOf(0f, dynamicRange / 60f)) * 0.2f
        val voiceScore = voiceActivity * 0.2f

        return (rmsScore + snrScore + dynamicScore + voiceScore) * 100f
    }

    private fun performFFT(samples: ShortArray): Array<Complex> {
        // Implementación simplificada de FFT
        val n = samples.size
        val result = Array(n) { i -> Complex(samples[i].toDouble(), 0.0) }

        // Para esta implementación simplificada, retornamos los valores como están
        // En producción, usarías una librería como FFTW o implementación nativa
        return result
    }

    /**
     * Convierte ByteArray a ShortArray para procesamiento
     */
    private fun ByteArray.toShortArray(): ShortArray {
        val shortArray = ShortArray(this.size / 2)
        for (i in shortArray.indices) {
            val byte1 = this[i * 2].toInt() and 0xFF
            val byte2 = this[i * 2 + 1].toInt() and 0xFF
            shortArray[i] = (byte2 shl 8 or byte1).toShort()
        }
        return shortArray
    }

    data class Complex(val real: Double, val imaginary: Double)

    data class AudioConfig(
        val sampleRate: Int,
        val channelConfig: Int,
        val audioFormat: Int,
        val bufferSizeMs: Int,
        val enableEchoCancellation: Boolean,
        val enableNoiseSuppression: Boolean,
        val enableAutoGainControl: Boolean,
        val enableHighPassFilter: Boolean,
        val enableVoiceActivityDetection: Boolean
    )

    data class AudioQualityMetrics(
        val rms: Float,
        val snr: Float,
        val dynamicRange: Float,
        val spectralCentroid: Float,
        val voiceActivityProbability: Float,
        val qualityScore: Float
    )
}