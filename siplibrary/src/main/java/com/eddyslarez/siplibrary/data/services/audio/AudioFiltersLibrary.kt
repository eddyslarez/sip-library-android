package com.eddyslarez.siplibrary.data.services.audio

import kotlin.math.*

/**
 * Librería completa de filtros de audio de alta calidad
 */
object AudioFiltersLibrary {

    /**
     * Filtro de Kalman para seguimiento de señal de audio
     */
    class KalmanAudioFilter {
        private var estimate = 0.0
        private var errorCovariance = 1.0
        private val processNoise = 0.01
        private val measurementNoise = 0.1

        fun filter(measurement: Double): Double {
            // Predict
            val predictedErrorCovariance = errorCovariance + processNoise

            // Update
            val kalmanGain = predictedErrorCovariance / (predictedErrorCovariance + measurementNoise)
            estimate = estimate + kalmanGain * (measurement - estimate)
            errorCovariance = (1 - kalmanGain) * predictedErrorCovariance

            return estimate
        }

        fun reset() {
            estimate = 0.0
            errorCovariance = 1.0
        }
    }

    /**
     * Filtro Savitzky-Golay para suavizado de señal
     */
    class SavitzkyGolayFilter(private val windowSize: Int = 5) {
        private val buffer = DoubleArray(windowSize)
        private var index = 0
        private var filled = false

        fun filter(input: Double): Double {
            buffer[index] = input
            index = (index + 1) % windowSize
            if (index == 0) filled = true

            return if (filled) {
                // Aplicar coeficientes de Savitzky-Golay para suavizado
                applyCoefficients()
            } else {
                input // No suficientes muestras aún
            }
        }

        private fun applyCoefficients(): Double {
            // Coeficientes para ventana de 5 puntos, orden 2
            val coefficients = doubleArrayOf(-3.0, 12.0, 17.0, 12.0, -3.0)
            val normalizer = 35.0

            var result = 0.0
            for (i in 0 until windowSize) {
                val bufferIndex = (index + i) % windowSize
                result += coefficients[i] * buffer[bufferIndex]
            }

            return result / normalizer
        }
    }

    /**
     * Filtro adaptativo LMS (Least Mean Squares)
     */
    class AdaptiveLMSFilter(
        private val filterOrder: Int = 32,
        private val stepSize: Double = 0.01
    ) {
        private val weights = DoubleArray(filterOrder)
        private val inputBuffer = DoubleArray(filterOrder)
        private var bufferIndex = 0

        fun adaptiveFilter(input: Double, desired: Double): Double {
            // Actualizar buffer de entrada
            inputBuffer[bufferIndex] = input
            bufferIndex = (bufferIndex + 1) % filterOrder

            // Calcular salida del filtro
            var output = 0.0
            for (i in 0 until filterOrder) {
                val idx = (bufferIndex + i) % filterOrder
                output += weights[i] * inputBuffer[idx]
            }

            // Calcular error
            val error = desired - output

            // Actualizar pesos (algoritmo LMS)
            for (i in 0 until filterOrder) {
                val idx = (bufferIndex + i) % filterOrder
                weights[i] += stepSize * error * inputBuffer[idx]
            }

            return output
        }
    }

    /**
     * Filtro IIR (Infinite Impulse Response) configurable
     */
    class IIRFilter(
        private val aCoeffs: DoubleArray, // Denominador
        private val bCoeffs: DoubleArray  // Numerador
    ) {
        private val xHistory = DoubleArray(bCoeffs.size)
        private val yHistory = DoubleArray(aCoeffs.size)
        private var index = 0

        fun filter(input: Double): Double {
            // Actualizar historia de entrada
            xHistory[index] = input

            // Calcular salida
            var output = 0.0

            // Parte FIR (numerador)
            for (i in bCoeffs.indices) {
                val idx = (index - i + xHistory.size) % xHistory.size
                output += bCoeffs[i] * xHistory[idx]
            }

            // Parte IIR (denominador, excluyendo a0)
            for (i in 1 until aCoeffs.size) {
                val idx = (index - i + 1 + yHistory.size) % yHistory.size
                output -= aCoeffs[i] * yHistory[idx]
            }

            output /= aCoeffs[0]

            // Actualizar historia de salida
            yHistory[index] = output
            index = (index + 1) % maxOf(xHistory.size, yHistory.size)

            return output
        }

        companion object {
            /**
             * Crea un filtro Butterworth paso bajo
             */
            fun butterworthLowPass(cutoffFreq: Double, sampleRate: Double, order: Int = 2): IIRFilter {
                val omega = 2.0 * PI * cutoffFreq / sampleRate
                val cosOmega = cos(omega)
                val sinOmega = sin(omega)
                val alpha = sinOmega / (2.0 * sqrt(2.0))

                val a0 = 1.0 + alpha
                val a1 = -2.0 * cosOmega
                val a2 = 1.0 - alpha
                val b0 = (1.0 - cosOmega) / 2.0
                val b1 = 1.0 - cosOmega
                val b2 = (1.0 - cosOmega) / 2.0

                return IIRFilter(
                    doubleArrayOf(a0, a1, a2),
                    doubleArrayOf(b0, b1, b2)
                )
            }

            /**
             * Crea un filtro Butterworth paso alto
             */
            fun butterworthHighPass(cutoffFreq: Double, sampleRate: Double, order: Int = 2): IIRFilter {
                val omega = 2.0 * PI * cutoffFreq / sampleRate
                val cosOmega = cos(omega)
                val sinOmega = sin(omega)
                val alpha = sinOmega / (2.0 * sqrt(2.0))

                val a0 = 1.0 + alpha
                val a1 = -2.0 * cosOmega
                val a2 = 1.0 - alpha
                val b0 = (1.0 + cosOmega) / 2.0
                val b1 = -(1.0 + cosOmega)
                val b2 = (1.0 + cosOmega) / 2.0

                return IIRFilter(
                    doubleArrayOf(a0, a1, a2),
                    doubleArrayOf(b0, b1, b2)
                )
            }
        }
    }

    /**
     * Filtro de mediana para eliminar impulsos
     */
    class MedianFilter(private val windowSize: Int = 5) {
        private val buffer = DoubleArray(windowSize)
        private var index = 0
        private var count = 0

        fun filter(input: Double): Double {
            buffer[index] = input
            index = (index + 1) % windowSize
            count = minOf(count + 1, windowSize)

            // Crear array para ordenar
            val sortArray = DoubleArray(count)
            for (i in 0 until count) {
                val idx = (index - count + i + windowSize) % windowSize
                sortArray[i] = buffer[idx]
            }

            // Ordenar y devolver mediana
            sortArray.sort()
            return sortArray[count / 2]
        }
    }

    /**
     * Compresor/Limitador dinámico avanzado
     */
    class DynamicRangeCompressor(
        private val threshold: Double,      // Umbral en dB
        private val ratio: Double,          // Relación de compresión
        private val attack: Double,         // Tiempo de ataque en segundos
        private val release: Double,        // Tiempo de liberación en segundos
        private val sampleRate: Double      // Frecuencia de muestreo
    ) {
        private var envelope = 0.0
        private val thresholdLinear = 10.0.pow(threshold / 20.0)
        private val attackCoeff = exp(-1.0 / (attack * sampleRate))
        private val releaseCoeff = exp(-1.0 / (release * sampleRate))

        fun process(input: Double): Double {
            val inputLevel = abs(input)

            // Seguidor de envolvente
            val targetGain = if (inputLevel > thresholdLinear) {
                thresholdLinear / inputLevel + (inputLevel - thresholdLinear) / (inputLevel * ratio)
            } else {
                1.0
            }

            // Aplicar tiempo de ataque/liberación
            val coeff = if (targetGain < envelope) attackCoeff else releaseCoeff
            envelope = targetGain + (envelope - targetGain) * coeff

            return input * envelope
        }
    }

    /**
     * Gate de ruido avanzado con histéresis
     */
    class AdvancedNoiseGate(
        private val openThreshold: Double,   // Umbral de apertura en dB
        private val closeThreshold: Double,  // Umbral de cierre en dB
        private val attack: Double,          // Tiempo de ataque
        private val hold: Double,            // Tiempo de mantenimiento
        private val release: Double,         // Tiempo de liberación
        private val sampleRate: Double
    ) {
        private var envelope = 0.0
        private var gateState = GateState.CLOSED
        private var holdCounter = 0

        private val openThresholdLinear = 10.0.pow(openThreshold / 20.0)
        private val closeThresholdLinear = 10.0.pow(closeThreshold / 20.0)
        private val attackCoeff = exp(-1.0 / (attack * sampleRate))
        private val releaseCoeff = exp(-1.0 / (release * sampleRate))
        private val holdSamples = (hold * sampleRate).toInt()

        enum class GateState { CLOSED, OPENING, OPEN, HOLDING, CLOSING }

        fun process(input: Double): Double {
            val inputLevel = abs(input)

            when (gateState) {
                GateState.CLOSED -> {
                    if (inputLevel > openThresholdLinear) {
                        gateState = GateState.OPENING
                    }
                    envelope *= releaseCoeff
                }
                GateState.OPENING -> {
                    envelope = 1.0 + (envelope - 1.0) * attackCoeff
                    if (envelope > 0.99) {
                        gateState = GateState.OPEN
                    }
                }
                GateState.OPEN -> {
                    if (inputLevel < closeThresholdLinear) {
                        gateState = GateState.HOLDING
                        holdCounter = 0
                    }
                    envelope = 1.0
                }
                GateState.HOLDING -> {
                    holdCounter++
                    if (inputLevel > openThresholdLinear) {
                        gateState = GateState.OPEN
                    } else if (holdCounter >= holdSamples) {
                        gateState = GateState.CLOSING
                    }
                    envelope = 1.0
                }
                GateState.CLOSING -> {
                    if (inputLevel > openThresholdLinear) {
                        gateState = GateState.OPENING
                    }
                    envelope *= releaseCoeff
                    if (envelope < 0.01) {
                        gateState = GateState.CLOSED
                        envelope = 0.0
                    }
                }
            }

            return input * envelope
        }
    }

    /**
     * Filtro de ventana deslizante para análisis espectral
     */
    class SlidingWindowFilter(private val windowSize: Int) {
        private val buffer = DoubleArray(windowSize)
        private var index = 0
        private var count = 0

        fun addSample(sample: Double) {
            buffer[index] = sample
            index = (index + 1) % windowSize
            count = minOf(count + 1, windowSize)
        }

        fun getRMS(): Double {
            if (count == 0) return 0.0

            var sum = 0.0
            for (i in 0 until count) {
                val value = buffer[i]
                sum += value * value
            }
            return sqrt(sum / count)
        }

        fun getMean(): Double {
            if (count == 0) return 0.0

            var sum = 0.0
            for (i in 0 until count) {
                sum += buffer[i]
            }
            return sum / count
        }

        fun getVariance(): Double {
            if (count < 2) return 0.0

            val mean = getMean()
            var sum = 0.0
            for (i in 0 until count) {
                val diff = buffer[i] - mean
                sum += diff * diff
            }
            return sum / (count - 1)
        }

        fun getStandardDeviation(): Double {
            return sqrt(getVariance())
        }
    }

    /**
     * Filtro de detección de actividad de voz (VAD)
     */
    class VoiceActivityDetector(
        private val sampleRate: Double,
        private val frameSize: Int = 320  // 20ms a 16kHz
    ) {
        private val energyFilter = SlidingWindowFilter(50)
        private val zeroCrossingFilter = SlidingWindowFilter(50)
        private val spectralCentroidFilter = SlidingWindowFilter(30)

        private var frameBuffer = DoubleArray(frameSize)
        private var frameIndex = 0

        fun processSample(sample: Double): VoiceActivity {
            frameBuffer[frameIndex++] = sample

            if (frameIndex >= frameSize) {
                val activity = processFrame(frameBuffer.copyOf())
                frameIndex = 0
                return activity
            }

            return VoiceActivity(false, 0.0)
        }

        private fun processFrame(frame: DoubleArray): VoiceActivity {
            // Calcular características
            val energy = calculateEnergy(frame)
            val zeroCrossingRate = calculateZeroCrossingRate(frame)
            val spectralCentroid = calculateSpectralCentroid(frame)

            // Actualizar filtros deslizantes
            energyFilter.addSample(energy)
            zeroCrossingFilter.addSample(zeroCrossingRate)
            spectralCentroidFilter.addSample(spectralCentroid)

            // Detectar actividad de voz
            val isVoice = detectVoiceActivity(energy, zeroCrossingRate, spectralCentroid)
            val confidence = calculateConfidence(energy, zeroCrossingRate, spectralCentroid)

            return VoiceActivity(isVoice, confidence)
        }

        private fun calculateEnergy(frame: DoubleArray): Double {
            var energy = 0.0
            for (sample in frame) {
                energy += sample * sample
            }
            return energy / frame.size
        }

        private fun calculateZeroCrossingRate(frame: DoubleArray): Double {
            var crossings = 0
            for (i in 1 until frame.size) {
                if ((frame[i] >= 0 && frame[i-1] < 0) || (frame[i] < 0 && frame[i-1] >= 0)) {
                    crossings++
                }
            }
            return crossings.toDouble() / frame.size
        }

        private fun calculateSpectralCentroid(frame: DoubleArray): Double {
            // Implementación simplificada sin FFT
            var weightedSum = 0.0
            var magnitudeSum = 0.0

            for (i in frame.indices) {
                val magnitude = abs(frame[i])
                val frequency = i.toDouble() * sampleRate / frame.size
                weightedSum += frequency * magnitude
                magnitudeSum += magnitude
            }

            return if (magnitudeSum > 0) weightedSum / magnitudeSum else 0.0
        }

        private fun detectVoiceActivity(energy: Double, zcr: Double, centroid: Double): Boolean {
            val energyThreshold = energyFilter.getMean() + 2 * energyFilter.getStandardDeviation()
            val zcrRange = zcr in 0.1..0.4
            val centroidRange = centroid > 300.0 && centroid < 4000.0

            return energy > energyThreshold && zcrRange && centroidRange
        }

        private fun calculateConfidence(energy: Double, zcr: Double, centroid: Double): Double {
            var confidence = 0.0

            // Contribución de la energía
            val energyMean = energyFilter.getMean()
            if (energyMean > 0) {
                confidence += minOf(1.0, energy / energyMean) * 0.4
            }

            // Contribución del zero crossing rate
            if (zcr in 0.1..0.4) {
                confidence += 0.3
            }

            // Contribución del centroide espectral
            if (centroid > 300.0 && centroid < 4000.0) {
                confidence += 0.3
            }

            return minOf(1.0, confidence)
        }

        data class VoiceActivity(val isVoice: Boolean, val confidence: Double)
    }
}