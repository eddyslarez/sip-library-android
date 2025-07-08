package com.eddyslarez.siplibrary.data.services.translation

import android.app.Application
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.eddyslarez.siplibrary.EddysSipLibrary
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Gestor de traducción simultánea de audio en tiempo real
 * 
 * @author Eddys Larez
 */
class RealtimeTranslationManager(
    private val application: Application,
    private val config: EddysSipLibrary.SipConfig
) {
    companion object {
        private const val TAG = "RealtimeTranslationManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 2
        private const val CHUNK_DURATION_MS = 1000 // 1 segundo de audio por chunk
    }

    private val translationService: TranslationService = when (config.translationProvider) {
        TranslationProvider.OPENAI -> OpenAITranslationService(config.openAiApiKey)
        TranslationProvider.GOOGLE -> GoogleTranslationService(config.googleApiKey)
        TranslationProvider.AZURE -> AzureTranslationService(config.azureApiKey)
    }

    private val audioProcessor = AudioProcessor()
    private val languageDetector = LanguageDetector()
    
    // Estados
    private val _isTranslationActive = MutableStateFlow(false)
    val isTranslationActive: StateFlow<Boolean> = _isTranslationActive.asStateFlow()
    
    private val _detectedLanguage = MutableStateFlow<String?>(null)
    val detectedLanguage: StateFlow<String?> = _detectedLanguage.asStateFlow()
    
    private val _translationStatus = MutableStateFlow(TranslationStatus.IDLE)
    val translationStatus: StateFlow<TranslationStatus> = _translationStatus.asStateFlow()
    
    private val _lastTranslatedText = MutableStateFlow("")
    val lastTranslatedText: StateFlow<String> = _lastTranslatedText.asStateFlow()

    // Jobs para procesamiento
    private var incomingAudioJob: Job? = null
    private var outgoingAudioJob: Job? = null
    private var translationJob: Job? = null
    
    // Buffers de audio
    private val incomingAudioBuffer = AudioBuffer()
    private val outgoingAudioBuffer = AudioBuffer()
    
    // Configuración de idiomas
    private var preferredLanguage: String = config.preferredTranslationLanguage
    private var detectedRemoteLanguage: String? = null
    private var fallbackLanguage: String = "en" // Inglés como fallback

    /**
     * Inicia la traducción simultánea
     */
    fun startTranslation() {
        if (_isTranslationActive.value) {
            log.d(tag = TAG) { "Translation already active" }
            return
        }

        log.d(tag = TAG) { "Starting realtime translation - Preferred language: $preferredLanguage" }
        
        _isTranslationActive.value = true
        _translationStatus.value = TranslationStatus.INITIALIZING
        
        startAudioProcessing()
        startTranslationProcessing()
        
        _translationStatus.value = TranslationStatus.ACTIVE
        
        log.d(tag = TAG) { "Realtime translation started successfully" }
    }

    /**
     * Detiene la traducción simultánea
     */
    fun stopTranslation() {
        if (!_isTranslationActive.value) {
            return
        }

        log.d(tag = TAG) { "Stopping realtime translation" }
        
        _isTranslationActive.value = false
        _translationStatus.value = TranslationStatus.STOPPING
        
        // Cancelar jobs
        incomingAudioJob?.cancel()
        outgoingAudioJob?.cancel()
        translationJob?.cancel()
        
        // Limpiar buffers
        incomingAudioBuffer.clear()
        outgoingAudioBuffer.clear()
        
        _translationStatus.value = TranslationStatus.IDLE
        _detectedLanguage.value = null
        detectedRemoteLanguage = null
        
        log.d(tag = TAG) { "Realtime translation stopped" }
    }

    /**
     * Alterna el estado de la traducción
     */
    fun toggleTranslation() {
        if (_isTranslationActive.value) {
            stopTranslation()
        } else {
            startTranslation()
        }
    }

    /**
     * Cambia el idioma preferido durante la llamada
     */
    fun changePreferredLanguage(languageCode: String) {
        log.d(tag = TAG) { "Changing preferred language from $preferredLanguage to $languageCode" }
        preferredLanguage = languageCode
        
        // Si la traducción está activa, reiniciar para aplicar el nuevo idioma
        if (_isTranslationActive.value) {
            CoroutineScope(Dispatchers.IO).launch {
                stopTranslation()
                delay(500) // Pequeña pausa para limpiar
                startTranslation()
            }
        }
    }

    /**
     * Procesa audio entrante (de la otra persona)
     */
    fun processIncomingAudio(audioData: ByteArray) {
        if (!_isTranslationActive.value) return
        
        incomingAudioBuffer.addAudio(audioData)
    }

    /**
     * Procesa audio saliente (del usuario)
     */
    fun processOutgoingAudio(audioData: ByteArray) {
        if (!_isTranslationActive.value) return
        
        outgoingAudioBuffer.addAudio(audioData)
    }

    /**
     * Inicia el procesamiento de audio
     */
    private fun startAudioProcessing() {
        // Procesar audio entrante (traducir a idioma preferido)
        incomingAudioJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && _isTranslationActive.value) {
                try {
                    val audioChunk = incomingAudioBuffer.getChunk(CHUNK_DURATION_MS)
                    if (audioChunk.isNotEmpty()) {
                        processIncomingAudioChunk(audioChunk)
                    }
                    delay(100) // Pequeña pausa para evitar uso excesivo de CPU
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error processing incoming audio: ${e.message}" }
                }
            }
        }

        // Procesar audio saliente (traducir al idioma detectado o fallback)
        outgoingAudioJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && _isTranslationActive.value) {
                try {
                    val audioChunk = outgoingAudioBuffer.getChunk(CHUNK_DURATION_MS)
                    if (audioChunk.isNotEmpty()) {
                        processOutgoingAudioChunk(audioChunk)
                    }
                    delay(100)
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error processing outgoing audio: ${e.message}" }
                }
            }
        }
    }

    /**
     * Procesa un chunk de audio entrante
     */
    private suspend fun processIncomingAudioChunk(audioData: ByteArray) {
        try {
            // 1. Detectar idioma si aún no se ha detectado
            if (detectedRemoteLanguage == null) {
                val detectedLang = languageDetector.detectLanguage(audioData)
                if (detectedLang != null) {
                    detectedRemoteLanguage = detectedLang
                    _detectedLanguage.value = detectedLang
                    log.d(tag = TAG) { "Detected remote language: $detectedLang" }
                }
            }

            // 2. Transcribir audio a texto
            val sourceLanguage = detectedRemoteLanguage ?: fallbackLanguage
            val transcription = translationService.transcribeAudio(audioData, sourceLanguage)
            
            if (transcription.isNotEmpty()) {
                log.d(tag = TAG) { "Transcribed ($sourceLanguage): $transcription" }
                
                // 3. Traducir texto al idioma preferido
                if (sourceLanguage != preferredLanguage) {
                    val translatedText = translationService.translateText(
                        text = transcription,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = preferredLanguage
                    )
                    
                    if (translatedText.isNotEmpty()) {
                        log.d(tag = TAG) { "Translated to $preferredLanguage: $translatedText" }
                        _lastTranslatedText.value = translatedText
                        
                        // 4. Generar audio traducido
                        val translatedAudio = translationService.generateSpeech(
                            text = translatedText,
                            language = preferredLanguage,
                            voice = config.preferredVoice
                        )
                        
                        // 5. Reproducir audio traducido (reemplazar el original)
                        if (translatedAudio.isNotEmpty()) {
                            replaceIncomingAudio(translatedAudio)
                        }
                    }
                } else {
                    // El idioma ya es el preferido, no necesita traducción
                    _lastTranslatedText.value = transcription
                }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error processing incoming audio chunk: ${e.message}" }
        }
    }

    /**
     * Procesa un chunk de audio saliente
     */
    private suspend fun processOutgoingAudioChunk(audioData: ByteArray) {
        try {
            // 1. Transcribir audio del usuario
            val transcription = translationService.transcribeAudio(audioData, preferredLanguage)
            
            if (transcription.isNotEmpty()) {
                log.d(tag = TAG) { "User said ($preferredLanguage): $transcription" }
                
                // 2. Determinar idioma de destino
                val targetLanguage = detectedRemoteLanguage ?: fallbackLanguage
                
                // 3. Traducir si es necesario
                if (preferredLanguage != targetLanguage) {
                    val translatedText = translationService.translateText(
                        text = transcription,
                        sourceLanguage = preferredLanguage,
                        targetLanguage = targetLanguage
                    )
                    
                    if (translatedText.isNotEmpty()) {
                        log.d(tag = TAG) { "Translated to $targetLanguage: $translatedText" }
                        
                        // 4. Generar audio traducido
                        val translatedAudio = translationService.generateSpeech(
                            text = translatedText,
                            language = targetLanguage,
                            voice = config.getVoiceForLanguage(targetLanguage)
                        )
                        
                        // 5. Reemplazar audio saliente
                        if (translatedAudio.isNotEmpty()) {
                            replaceOutgoingAudio(translatedAudio)
                        }
                    }
                }
                // Si el idioma es el mismo, no se necesita traducción
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error processing outgoing audio chunk: ${e.message}" }
        }
    }

    /**
     * Inicia el procesamiento de traducción en background
     */
    private fun startTranslationProcessing() {
        translationJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && _isTranslationActive.value) {
                try {
                    // Monitorear estado de la traducción
                    delay(1000)
                    
                    // Aquí se pueden agregar métricas, limpieza de buffers, etc.
                    
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error in translation processing: ${e.message}" }
                }
            }
        }
    }

    /**
     * Reemplaza el audio entrante con el audio traducido
     */
    private fun replaceIncomingAudio(translatedAudio: ByteArray) {
        // Esta función debe integrarse con el WebRTC manager
        // para reemplazar el stream de audio entrante
        log.d(tag = TAG) { "Replacing incoming audio with translated version (${translatedAudio.size} bytes)" }
        
        // TODO: Integrar con WebRTC para reemplazar el audio stream
        // webRtcManager.replaceIncomingAudioStream(translatedAudio)
    }

    /**
     * Reemplaza el audio saliente con el audio traducido
     */
    private fun replaceOutgoingAudio(translatedAudio: ByteArray) {
        // Esta función debe integrarse con el WebRTC manager
        // para reemplazar el stream de audio saliente
        log.d(tag = TAG) { "Replacing outgoing audio with translated version (${translatedAudio.size} bytes)" }
        
        // TODO: Integrar con WebRTC para reemplazar el audio stream
        // webRtcManager.replaceOutgoingAudioStream(translatedAudio)
    }

    /**
     * Obtiene información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== REALTIME TRANSLATION DIAGNOSTIC ===")
            appendLine("Translation active: ${_isTranslationActive.value}")
            appendLine("Translation status: ${_translationStatus.value}")
            appendLine("Preferred language: $preferredLanguage")
            appendLine("Detected remote language: $detectedRemoteLanguage")
            appendLine("Fallback language: $fallbackLanguage")
            appendLine("Translation provider: ${config.translationProvider}")
            appendLine("Last translated text: ${_lastTranslatedText.value}")
            appendLine("Incoming buffer size: ${incomingAudioBuffer.size()}")
            appendLine("Outgoing buffer size: ${outgoingAudioBuffer.size()}")
            appendLine("Jobs active: incoming=${incomingAudioJob?.isActive}, outgoing=${outgoingAudioJob?.isActive}, translation=${translationJob?.isActive}")
        }
    }

    /**
     * Limpia recursos
     */
    fun dispose() {
        stopTranslation()
        translationService.dispose()
        audioProcessor.dispose()
        languageDetector.dispose()
    }
}

/**
 * Estados de la traducción
 */
enum class TranslationStatus {
    IDLE,
    INITIALIZING,
    ACTIVE,
    PAUSED,
    STOPPING,
    ERROR
}

/**
 * Proveedores de traducción soportados
 */
enum class TranslationProvider {
    OPENAI,
    GOOGLE,
    AZURE
}

/**
 * Buffer de audio para acumular chunks
 */
private class AudioBuffer {
    private val buffer = ByteArrayOutputStream()
    private val lock = Any()
    
    fun addAudio(audioData: ByteArray) {
        synchronized(lock) {
            buffer.write(audioData)
        }
    }
    
    fun getChunk(durationMs: Int): ByteArray {
        synchronized(lock) {
            val bytesPerMs = (SAMPLE_RATE * 2) / 1000 // 16-bit audio
            val chunkSize = bytesPerMs * durationMs
            
            val availableBytes = buffer.size()
            if (availableBytes >= chunkSize) {
                val chunk = buffer.toByteArray().sliceArray(0 until chunkSize)
                
                // Remover el chunk del buffer
                val remaining = buffer.toByteArray().sliceArray(chunkSize until availableBytes)
                buffer.reset()
                buffer.write(remaining)
                
                return chunk
            }
            return byteArrayOf()
        }
    }
    
    fun clear() {
        synchronized(lock) {
            buffer.reset()
        }
    }
    
    fun size(): Int {
        synchronized(lock) {
            return buffer.size()
        }
    }
}