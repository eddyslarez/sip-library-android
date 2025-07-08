package com.eddyslarez.siplibrary.data.services.translation

import com.eddyslarez.siplibrary.utils.log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Detector de idioma basado en características del audio
 * 
 * @author Eddys Larez
 */
class LanguageDetector {
    
    companion object {
        private const val TAG = "LanguageDetector"
    }
    
    private val languageProfiles = mutableMapOf<String, LanguageProfile>()
    private var detectionHistory = mutableListOf<DetectionResult>()
    
    init {
        initializeLanguageProfiles()
    }

    /**
     * Detecta el idioma del audio basado en características acústicas
     */
    suspend fun detectLanguage(audioData: ByteArray): String? {
        try {
            log.d(tag = TAG) { "Detecting language from audio (${audioData.size} bytes)" }
            
            // Extraer características del audio
            val features = extractAudioFeatures(audioData)
            
            // Comparar con perfiles de idiomas conocidos
            var bestMatch: String? = null
            var bestScore = 0.0
            
            for ((language, profile) in languageProfiles) {
                val score = calculateSimilarity(features, profile)
                if (score > bestScore && score > 0.6) { // Umbral de confianza
                    bestScore = score
                    bestMatch = language
                }
            }
            
            // Agregar al historial para mejorar la detección
            if (bestMatch != null) {
                val result = DetectionResult(bestMatch, bestScore, System.currentTimeMillis())
                detectionHistory.add(result)
                
                // Mantener solo los últimos 10 resultados
                if (detectionHistory.size > 10) {
                    detectionHistory.removeAt(0)
                }
                
                // Usar consenso de los últimos resultados
                val consensusLanguage = getConsensusLanguage()
                if (consensusLanguage != null) {
                    log.d(tag = TAG) { "Language detected: $consensusLanguage (confidence: $bestScore)" }
                    return consensusLanguage
                }
            }
            
            log.d(tag = TAG) { "No language detected with sufficient confidence" }
            return null
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error detecting language: ${e.message}" }
            return null
        }
    }

    /**
     * Extrae características acústicas del audio
     */
    private fun extractAudioFeatures(audioData: ByteArray): AudioFeatures {
        val buffer = ByteBuffer.wrap(audioData).order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(audioData.size / 2)
        
        // Leer samples
        for (i in samples.indices) {
            samples[i] = buffer.getShort(i * 2)
        }
        
        // Calcular características básicas
        val energy = calculateEnergy(samples)
        val zeroCrossingRate = calculateZeroCrossingRate(samples)
        val spectralCentroid = calculateSpectralCentroid(samples)
        val pitch = estimatePitch(samples)
        val formants = estimateFormants(samples)
        
        return AudioFeatures(
            energy = energy,
            zeroCrossingRate = zeroCrossingRate,
            spectralCentroid = spectralCentroid,
            pitch = pitch,
            formants = formants
        )
    }

    /**
     * Calcula la energía del audio
     */
    private fun calculateEnergy(samples: ShortArray): Double {
        var energy = 0.0
        for (sample in samples) {
            energy += (sample * sample).toDouble()
        }
        return energy / samples.size
    }

    /**
     * Calcula la tasa de cruces por cero
     */
    private fun calculateZeroCrossingRate(samples: ShortArray): Double {
        var crossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i] >= 0) != (samples[i-1] >= 0)) {
                crossings++
            }
        }
        return crossings.toDouble() / samples.size
    }

    /**
     * Calcula el centroide espectral (simplificado)
     */
    private fun calculateSpectralCentroid(samples: ShortArray): Double {
        // Implementación simplificada del centroide espectral
        var weightedSum = 0.0
        var magnitudeSum = 0.0
        
        for (i in samples.indices) {
            val magnitude = kotlin.math.abs(samples[i].toDouble())
            weightedSum += i * magnitude
            magnitudeSum += magnitude
        }
        
        return if (magnitudeSum > 0) weightedSum / magnitudeSum else 0.0
    }

    /**
     * Estima el pitch fundamental (simplificado)
     */
    private fun estimatePitch(samples: ShortArray): Double {
        // Implementación simplificada usando autocorrelación
        val minPeriod = 20 // ~800 Hz
        val maxPeriod = 200 // ~80 Hz
        
        var bestPeriod = 0
        var maxCorrelation = 0.0
        
        for (period in minPeriod..maxPeriod) {
            var correlation = 0.0
            var count = 0
            
            for (i in 0 until samples.size - period) {
                correlation += samples[i] * samples[i + period]
                count++
            }
            
            if (count > 0) {
                correlation /= count
                if (correlation > maxCorrelation) {
                    maxCorrelation = correlation
                    bestPeriod = period
                }
            }
        }
        
        return if (bestPeriod > 0) 16000.0 / bestPeriod else 0.0 // Convertir a Hz
    }

    /**
     * Estima los formantes (simplificado)
     */
    private fun estimateFormants(samples: ShortArray): List<Double> {
        // Implementación muy simplificada de estimación de formantes
        // En una implementación real, usarías LPC (Linear Predictive Coding)
        
        val formants = mutableListOf<Double>()
        
        // Estimar F1 y F2 basado en características espectrales
        val spectralPeaks = findSpectralPeaks(samples)
        
        if (spectralPeaks.size >= 2) {
            formants.add(spectralPeaks[0])
            formants.add(spectralPeaks[1])
        }
        
        return formants
    }

    /**
     * Encuentra picos espectrales (simplificado)
     */
    private fun findSpectralPeaks(samples: ShortArray): List<Double> {
        // Implementación muy simplificada
        val peaks = mutableListOf<Double>()
        
        // Dividir en bandas de frecuencia y encontrar picos
        val bandSize = samples.size / 8
        
        for (band in 0 until 8) {
            val startIndex = band * bandSize
            val endIndex = minOf((band + 1) * bandSize, samples.size)
            
            var maxEnergy = 0.0
            for (i in startIndex until endIndex) {
                val energy = (samples[i] * samples[i]).toDouble()
                if (energy > maxEnergy) {
                    maxEnergy = energy
                }
            }
            
            if (maxEnergy > 1000) { // Umbral arbitrario
                val frequency = (band + 1) * 1000.0 // Frecuencia aproximada
                peaks.add(frequency)
            }
        }
        
        return peaks.sorted()
    }

    /**
     * Calcula la similitud entre características y un perfil de idioma
     */
    private fun calculateSimilarity(features: AudioFeatures, profile: LanguageProfile): Double {
        var similarity = 0.0
        var weights = 0.0
        
        // Comparar energía
        val energyDiff = kotlin.math.abs(features.energy - profile.averageEnergy) / profile.averageEnergy
        similarity += (1.0 - energyDiff.coerceAtMost(1.0)) * 0.2
        weights += 0.2
        
        // Comparar tasa de cruces por cero
        val zcrDiff = kotlin.math.abs(features.zeroCrossingRate - profile.averageZeroCrossingRate) / profile.averageZeroCrossingRate
        similarity += (1.0 - zcrDiff.coerceAtMost(1.0)) * 0.3
        weights += 0.3
        
        // Comparar centroide espectral
        val centroidDiff = kotlin.math.abs(features.spectralCentroid - profile.averageSpectralCentroid) / profile.averageSpectralCentroid
        similarity += (1.0 - centroidDiff.coerceAtMost(1.0)) * 0.2
        weights += 0.2
        
        // Comparar pitch
        if (features.pitch > 0 && profile.averagePitch > 0) {
            val pitchDiff = kotlin.math.abs(features.pitch - profile.averagePitch) / profile.averagePitch
            similarity += (1.0 - pitchDiff.coerceAtMost(1.0)) * 0.3
            weights += 0.3
        }
        
        return if (weights > 0) similarity / weights else 0.0
    }

    /**
     * Obtiene el idioma por consenso de las últimas detecciones
     */
    private fun getConsensusLanguage(): String? {
        if (detectionHistory.size < 3) return null
        
        val recentResults = detectionHistory.takeLast(5)
        val languageCounts = mutableMapOf<String, Int>()
        
        for (result in recentResults) {
            languageCounts[result.language] = languageCounts.getOrDefault(result.language, 0) + 1
        }
        
        val mostFrequent = languageCounts.maxByOrNull { it.value }
        return if (mostFrequent != null && mostFrequent.value >= 2) {
            mostFrequent.key
        } else {
            null
        }
    }

    /**
     * Inicializa perfiles de idiomas con características típicas
     */
    private fun initializeLanguageProfiles() {
        // Perfiles basados en características acústicas típicas de cada idioma
        // Estos valores son aproximados y deberían ser entrenados con datos reales
        
        languageProfiles["en"] = LanguageProfile(
            language = "en",
            averageEnergy = 15000.0,
            averageZeroCrossingRate = 0.08,
            averageSpectralCentroid = 2500.0,
            averagePitch = 180.0,
            typicalFormants = listOf(700.0, 1220.0, 2600.0)
        )
        
        languageProfiles["es"] = LanguageProfile(
            language = "es",
            averageEnergy = 18000.0,
            averageZeroCrossingRate = 0.09,
            averageSpectralCentroid = 2800.0,
            averagePitch = 200.0,
            typicalFormants = listOf(750.0, 1300.0, 2700.0)
        )
        
        languageProfiles["fr"] = LanguageProfile(
            language = "fr",
            averageEnergy = 16000.0,
            averageZeroCrossingRate = 0.07,
            averageSpectralCentroid = 2600.0,
            averagePitch = 190.0,
            typicalFormants = listOf(720.0, 1250.0, 2650.0)
        )
        
        languageProfiles["de"] = LanguageProfile(
            language = "de",
            averageEnergy = 17000.0,
            averageZeroCrossingRate = 0.075,
            averageSpectralCentroid = 2400.0,
            averagePitch = 170.0,
            typicalFormants = listOf(680.0, 1200.0, 2550.0)
        )
        
        languageProfiles["it"] = LanguageProfile(
            language = "it",
            averageEnergy = 17500.0,
            averageZeroCrossingRate = 0.085,
            averageSpectralCentroid = 2750.0,
            averagePitch = 195.0,
            typicalFormants = listOf(740.0, 1280.0, 2680.0)
        )
        
        languageProfiles["pt"] = LanguageProfile(
            language = "pt",
            averageEnergy = 16500.0,
            averageZeroCrossingRate = 0.08,
            averageSpectralCentroid = 2650.0,
            averagePitch = 185.0,
            typicalFormants = listOf(730.0, 1260.0, 2620.0)
        )
    }

    fun dispose() {
        detectionHistory.clear()
        languageProfiles.clear()
    }

    // Clases de datos
    private data class AudioFeatures(
        val energy: Double,
        val zeroCrossingRate: Double,
        val spectralCentroid: Double,
        val pitch: Double,
        val formants: List<Double>
    )

    private data class LanguageProfile(
        val language: String,
        val averageEnergy: Double,
        val averageZeroCrossingRate: Double,
        val averageSpectralCentroid: Double,
        val averagePitch: Double,
        val typicalFormants: List<Double>
    )

    private data class DetectionResult(
        val language: String,
        val confidence: Double,
        val timestamp: Long
    )
}