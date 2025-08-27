package com.eddyslarez.siplibrary.data.services.audio

import android.media.audiofx.NoiseSuppressor
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import kotlin.math.*

/**
 * Procesador avanzado de audio con filtros de alta calidad
 */
class AdvancedAudioProcessor {
    companion object {
        private const val TAG = "AdvancedAudioProcessor"
    }

    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null

    // Filtros personalizados
    private val highPassFilter = ButterworthFilter(FilterType.HIGH_PASS, 80.0, 48000.0)
    private val lowPassFilter = ButterworthFilter(FilterType.LOW_PASS, 8000.0, 48000.0)
    private val noiseGate = NoiseGate(-45.0f)
    private val compressor = Compressor(-20.0f, 4.0f)
    private val spectralSubtraction = SpectralSubtractionFilter()

    /**
     * Inicializa los efectos de audio del sistema
     */
    fun initializeSystemEffects(audioSessionId: Int): Boolean {
        return try {
            // Noise Suppressor
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                }
                Log.d(TAG, "NoiseSuppressor initialized")
            }

            // Automatic Gain Control
            if (AutomaticGainControl.isAvailable()) {
                automaticGainControl = AutomaticGainControl.create(audioSessionId)?.apply {
                    enabled = true
                }
                Log.d(TAG, "AutomaticGainControl initialized")
            }

            // Acoustic Echo Canceler
            if (AcousticEchoCanceler.isAvailable()) {
                acousticEchoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                }
                Log.d(TAG, "AcousticEchoCanceler initialized")
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing system effects: ${e.message}")
            false
        }
    }

    /**
     * Procesa audio con máxima calidad para OpenAI
     */
    suspend fun processForOpenAI(
        audioData: ByteArray,
        inputSampleRate: Int,
        qualityOptimizer: AudioQualityOptimizer
    ): ByteArray = withContext(Dispatchers.Default) {

        try {
            var processedData = audioData

            // 1. Análisis de calidad inicial
            val initialQuality = qualityOptimizer.analyzeAudioQuality(processedData, inputSampleRate)
            Log.d(TAG, "Initial quality score: ${initialQuality.qualityScore}")

            // 2. Aplicar filtros solo si es necesario
            if (initialQuality.qualityScore < 70) {
                processedData = applyEnhancementFilters(processedData, inputSampleRate)
            }

            // 3. Resample a 24kHz con alta calidad para OpenAI
            if (inputSampleRate != AudioQualityOptimizer.OPENAI_SAMPLE_RATE) {
                processedData = highQualityResample(
                    processedData,
                    inputSampleRate,
                    AudioQualityOptimizer.OPENAI_SAMPLE_RATE
                )
            }

            // 4. Normalización final
            processedData = normalizeAudio(processedData)

            // 5. Análisis de calidad final
            val finalQuality = qualityOptimizer.analyzeAudioQuality(
                processedData,
                AudioQualityOptimizer.OPENAI_SAMPLE_RATE
            )
            Log.d(TAG, "Final quality score: ${finalQuality.qualityScore}")

            processedData

        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio for OpenAI: ${e.message}")
            audioData
        }
    }

    /**
     * Procesa audio para Speech-to-Text con optimizaciones específicas
     */
    suspend fun processForSTT(
        audioData: ByteArray,
        inputSampleRate: Int,
        targetSampleRate: Int = AudioQualityOptimizer.STT_SAMPLE_RATE
    ): ByteArray = withContext(Dispatchers.Default) {

        try {
            var processedData = audioData

            // 1. Pre-enfasis para mejorar consonantes
            processedData = applyPreEmphasis(processedData, 0.97f)

            // 2. Filtros específicos para voz
            processedData = applyVoiceEnhancementFilters(processedData, inputSampleRate)

            // 3. Resample a frecuencia objetivo
            if (inputSampleRate != targetSampleRate) {
                processedData = highQualityResample(processedData, inputSampleRate, targetSampleRate)
            }

            // 4. Compresión dinámica para STT
            processedData = applySTTCompression(processedData)

            processedData

        } catch (e: Exception) {
            Log.e(TAG, "Error processing audio for STT: ${e.message}")
            audioData
        }
    }

    /**
     * Aplica filtros de mejora general
     */
    private fun applyEnhancementFilters(audioData: ByteArray, sampleRate: Int): ByteArray {
        var processedData = audioData

        // Convertir a float para procesamiento
        val samples = audioData.toFloatArray()

        // 1. High-pass filter para eliminar ruido de baja frecuencia
        highPassFilter.setSampleRate(sampleRate.toDouble())
        for (i in samples.indices) {
            samples[i] = highPassFilter.process(samples[i])
        }

        // 2. Noise gate
        for (i in samples.indices) {
            samples[i] = noiseGate.process(samples[i])
        }

        // 3. Spectral subtraction para reducción avanzada de ruido
        val enhancedSamples = spectralSubtraction.process(samples, sampleRate)

        // 4. Compressor
        for (i in enhancedSamples.indices) {
            enhancedSamples[i] = compressor.process(enhancedSamples[i])
        }

        // 5. Low-pass filter anti-aliasing
        lowPassFilter.setSampleRate(sampleRate.toDouble())
        for (i in enhancedSamples.indices) {
            enhancedSamples[i] = lowPassFilter.process(enhancedSamples[i])
        }

        return enhancedSamples.toByteArray()
    }

    /**
     * Aplica filtros específicos para mejorar voz
     */
    private fun applyVoiceEnhancementFilters(audioData: ByteArray, sampleRate: Int): ByteArray {
        val samples = audioData.toFloatArray()

        // Filtro band-pass centrado en frecuencias de voz (300-3400 Hz)
        val voiceBandPass = BandPassFilter(300.0, 3400.0, sampleRate.toDouble())
        for (i in samples.indices) {
            samples[i] = voiceBandPass.process(samples[i])
        }

        // Expansor para mejorar dinámica de voz
        val expander = Expander(-40.0f, 2.0f)
        for (i in samples.indices) {
            samples[i] = expander.process(samples[i])
        }

        return samples.toByteArray()
    }

    /**
     * Aplica pre-énfasis para mejorar consonantes
     */
    private fun applyPreEmphasis(audioData: ByteArray, alpha: Float): ByteArray {
        val samples = audioData.toFloatArray()
        val output = FloatArray(samples.size)

        output[0] = samples[0]
        for (i in 1 until samples.size) {
            output[i] = samples[i] - alpha * samples[i-1]
        }

        return output.toByteArray()
    }

    /**
     * Aplica compresión específica para STT
     */
    private fun applySTTCompression(audioData: ByteArray): ByteArray {
        val samples = audioData.toFloatArray()
        val sttCompressor = Compressor(-25.0f, 3.0f, 0.003f, 0.1f)

        for (i in samples.indices) {
            samples[i] = sttCompressor.process(samples[i])
        }

        return samples.toByteArray()
    }

    /**
     * Resampling de alta calidad usando interpolación cúbica
     */
    private fun highQualityResample(audioData: ByteArray, fromRate: Int, toRate: Int): ByteArray {
        if (fromRate == toRate) return audioData

        val inputSamples = audioData.toFloatArray()
        val ratio = toRate.toDouble() / fromRate.toDouble()
        val outputLength = (inputSamples.size * ratio).toInt()
        val outputSamples = FloatArray(outputLength)

        // Interpolación cúbica para mejor calidad
        for (i in outputSamples.indices) {
            val srcIndex = i / ratio
            val srcIndexInt = srcIndex.toInt()
            val fraction = srcIndex - srcIndexInt

            outputSamples[i] = cubicInterpolate(
                getSample(inputSamples, srcIndexInt - 1),
                getSample(inputSamples, srcIndexInt),
                getSample(inputSamples, srcIndexInt + 1),
                getSample(inputSamples, srcIndexInt + 2),
                fraction.toFloat()
            )
        }

        return outputSamples.toByteArray()
    }

    /**
     * Interpolación cúbica para resampling de alta calidad
     */
    private fun cubicInterpolate(y0: Float, y1: Float, y2: Float, y3: Float, mu: Float): Float {
        val mu2 = mu * mu
        val a0 = y3 - y2 - y0 + y1
        val a1 = y0 - y1 - a0
        val a2 = y2 - y0
        val a3 = y1

        return a0 * mu * mu2 + a1 * mu2 + a2 * mu + a3
    }

    private fun getSample(samples: FloatArray, index: Int): Float {
        return when {
            index < 0 -> samples[0]
            index >= samples.size -> samples[samples.size - 1]
            else -> samples[index]
        }
    }

    /**
     * Normaliza el audio para evitar clipping y optimizar SNR
     */
    private fun normalizeAudio(audioData: ByteArray): ByteArray {
        val samples = audioData.toFloatArray()
        val maxSample = samples.maxByOrNull { abs(it) } ?: 0f

        if (maxSample > 0) {
            val normalizeGain = 0.95f / abs(maxSample) // Deja un poco de headroom
            for (i in samples.indices) {
                samples[i] *= normalizeGain
            }
        }

        return samples.toByteArray()
    }

    /**
     * Convierte ByteArray a FloatArray para procesamiento
     */
    private fun ByteArray.toFloatArray(): FloatArray {
        val floatArray = FloatArray(this.size / 2)
        for (i in floatArray.indices) {
            val byte1 = this[i * 2].toInt() and 0xFF
            val byte2 = this[i * 2 + 1].toInt() and 0xFF
            val shortValue = (byte2 shl 8 or byte1).toShort()
            floatArray[i] = shortValue.toFloat() / 32768.0f
        }
        return floatArray
    }

    /**
     * Convierte FloatArray a ByteArray
     */
    private fun FloatArray.toByteArray(): ByteArray {
        val byteArray = ByteArray(this.size * 2)
        for (i in this.indices) {
            val sample = (this[i] * 32767.0f).coerceIn(-32768f, 32767f).toInt().toShort()
            byteArray[i * 2] = (sample.toInt() and 0xFF).toByte()
            byteArray[i * 2 + 1] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return byteArray
    }

    /**
     * Libera recursos
     */
    fun release() {
        try {
            noiseSuppressor?.release()
            automaticGainControl?.release()
            acousticEchoCanceler?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio effects: ${e.message}")
        }
    }

    // Clases de filtros internos
    enum class FilterType { LOW_PASS, HIGH_PASS, BAND_PASS, BAND_STOP }

    private class ButterworthFilter(
        private val type: FilterType,
        private var cutoffFreq: Double,
        private var sampleRate: Double
    ) {
        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        private var a0 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0
        private var b1 = 0.0
        private var b2 = 0.0

        init {
            calculateCoefficients()
        }

        fun setSampleRate(sampleRate: Double) {
            this.sampleRate = sampleRate
            calculateCoefficients()
        }

        private fun calculateCoefficients() {
            val omega = 2.0 * PI * cutoffFreq / sampleRate
            val cosOmega = cos(omega)
            val sinOmega = sin(omega)
            val alpha = sinOmega / sqrt(2.0)

            when (type) {
                FilterType.LOW_PASS -> {
                    b1 = 1.0 - cosOmega
                    b2 = (1.0 - cosOmega) / 2.0
                    a0 = 1.0 + alpha
                    a1 = -2.0 * cosOmega
                    a2 = 1.0 - alpha
                }
                FilterType.HIGH_PASS -> {
                    b1 = -(1.0 + cosOmega)
                    b2 = (1.0 + cosOmega) / 2.0
                    a0 = 1.0 + alpha
                    a1 = -2.0 * cosOmega
                    a2 = 1.0 - alpha
                }
                else -> {
                    // Implementar otros tipos según necesidad
                }
            }
        }

        fun process(input: Float): Float {
            val x0 = input.toDouble()
            val y0 = (b2 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2) / a0

            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0

            return y0.toFloat()
        }
    }

    private class BandPassFilter(
        private val lowCutoff: Double,
        private val highCutoff: Double,
        private val sampleRate: Double
    ) {
        private val highPassFilter = ButterworthFilter(FilterType.HIGH_PASS, lowCutoff, sampleRate)
        private val lowPassFilter = ButterworthFilter(FilterType.LOW_PASS, highCutoff, sampleRate)

        fun process(input: Float): Float {
            return lowPassFilter.process(highPassFilter.process(input))
        }
    }

    private class NoiseGate(private val thresholdDb: Float) {
        private val threshold = 10.0.pow(thresholdDb / 20.0).toFloat()

        fun process(input: Float): Float {
            return if (abs(input) < threshold) 0f else input
        }
    }

    private class Compressor(
        private val thresholdDb: Float,
        private val ratio: Float,
        private val attackTime: Float = 0.003f,
        private val releaseTime: Float = 0.1f
    ) {
        private val threshold = 10.0.pow(thresholdDb / 20.0).toFloat()
        private var envelope = 0f

        fun process(input: Float): Float {
            val inputLevel = abs(input)
            val targetGain = if (inputLevel > threshold) {
                threshold + (inputLevel - threshold) / ratio
            } else {
                inputLevel
            }

            val gain = targetGain / (inputLevel + 1e-10f)

            // Envelope follower
            val alpha = if (gain < envelope) attackTime else releaseTime
            envelope = alpha * gain + (1 - alpha) * envelope

            return input * envelope
        }
    }

    private class Expander(
        private val thresholdDb: Float,
        private val ratio: Float
    ) {
        private val threshold = 10.0.pow(thresholdDb / 20.0).toFloat()

        fun process(input: Float): Float {
            val inputLevel = abs(input)
            return if (inputLevel < threshold) {
                input * (inputLevel / threshold).pow(ratio - 1)
            } else {
                input
            }
        }
    }

    private class SpectralSubtractionFilter {
        fun process(samples: FloatArray, sampleRate: Int): FloatArray {
            // Implementación simplificada de sustracción espectral
            // En producción, usarías FFT para análisis/síntesis en dominio de frecuencia
            return samples.map { sample ->
                // Aplicar reducción de ruido conservativa
                if (abs(sample) < 0.01f) {
                    sample * 0.5f
                } else {
                    sample
                }
            }.toFloatArray()
        }
    }
}