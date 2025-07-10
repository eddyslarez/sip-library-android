package com.eddyslarez.siplibrary.data.services.translation

import android.app.Application
import com.eddyslarez.siplibrary.data.models.*
import com.eddyslarez.siplibrary.data.services.audio.AudioDevice
import com.eddyslarez.siplibrary.data.services.audio.WebRtcManager
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Gestor principal para traducción en tiempo real durante llamadas VoIP
 * Intercepta el audio de WebRTC y lo reemplaza con traducciones
 * 
 * @author Eddys Larez
 */
class RealtimeTranslationManager(
    private val application: Application,
    private val webRtcManager: WebRtcManager
) {
    companion object {
        private const val TAG = "RealtimeTranslationManager"
        private const val AUDIO_BUFFER_SIZE = 1024
        private const val TRANSLATION_TIMEOUT_MS = 5000L
    }

    private var openAiClient: OpenAiRealtimeClient? = null
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Configuración actual
    private val _config = MutableStateFlow(TranslationConfig())
    val config: StateFlow<TranslationConfig> = _config.asStateFlow()
    
    // Estado de la sesión de traducción
    private val _translationSession = MutableStateFlow<TranslationSession?>(null)
    val translationSession: StateFlow<TranslationSession?> = _translationSession.asStateFlow()
    
    // Idiomas detectados
    private val _detectedLanguages = MutableStateFlow<Map<TranslationDirection, String>>(emptyMap())
    val detectedLanguages: StateFlow<Map<TranslationDirection, String>> = _detectedLanguages.asStateFlow()
    
    // Buffers de audio para procesamiento
    private val incomingAudioBuffer = ConcurrentLinkedQueue<ByteArray>()
    private val outgoingAudioBuffer = ConcurrentLinkedQueue<ByteArray>()
    
    // Control de procesamiento
    private var isProcessingAudio = false
    private var audioProcessingJob: Job? = null
    
    // Callbacks para audio interceptado
    private var originalAudioInterceptor: AudioInterceptor? = null
    
    interface AudioInterceptor {
        fun onIncomingAudio(audioData: ByteArray): ByteArray // Retorna audio modificado
        fun onOutgoingAudio(audioData: ByteArray): ByteArray // Retorna audio modificado
    }

    /**
     * Configura el sistema de traducción
     */
    fun configure(config: TranslationConfig) {
        log.d(tag = TAG) { "Configuring translation system: enabled=${config.isEnabled}" }
        
        val oldConfig = _config.value
        _config.value = config
        
        if (config.isEnabled && !oldConfig.isEnabled) {
            startTranslationSystem()
        } else if (!config.isEnabled && oldConfig.isEnabled) {
            stopTranslationSystem()
        } else if (config.isEnabled && config.openAiApiKey != oldConfig.openAiApiKey) {
            // Reiniciar con nueva API key
            stopTranslationSystem()
            startTranslationSystem()
        }
    }

    /**
     * Inicia una sesión de traducción para una llamada
     */
    suspend fun startTranslationForCall(callId: String): Boolean {
        if (!_config.value.isEnabled) {
            log.w(tag = TAG) { "Translation not enabled" }
            return false
        }

        if (_config.value.openAiApiKey.isEmpty()) {
            log.e(tag = TAG) { "OpenAI API key not configured" }
            return false
        }

        try {
            log.d(tag = TAG) { "Starting translation for call: $callId" }

            // Crear cliente OpenAI si no existe
            if (openAiClient == null) {
                openAiClient = OpenAiRealtimeClient(_config.value.openAiApiKey, _config.value)
                setupOpenAiCallbacks()
            }

            // Conectar a OpenAI
            val connected = openAiClient?.connect() ?: false
            if (!connected) {
                log.e(tag = TAG) { "Failed to connect to OpenAI Realtime API" }
                return false
            }

            // Crear sesión de traducción
            val session = TranslationSession(
                sessionId = "trans_${System.currentTimeMillis()}",
                callId = callId,
                userLanguage = _config.value.preferredLanguage,
                detectedRemoteLanguage = null,
                isActive = true,
                startTime = System.currentTimeMillis()
            )

            _translationSession.value = session

            // Configurar interceptor de audio
            setupAudioInterception()

            // Iniciar procesamiento de audio
            startAudioProcessing()

            log.d(tag = TAG) { "Translation session started successfully" }
            return true

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error starting translation: ${e.message}" }
            return false
        }
    }

    /**
     * Detiene la sesión de traducción
     */
    fun stopTranslationForCall() {
        log.d(tag = TAG) { "Stopping translation session" }

        val session = _translationSession.value
        if (session != null) {
            _translationSession.value = session.copy(isActive = false)
        }

        stopAudioProcessing()
        removeAudioInterception()
        
        _detectedLanguages.value = emptyMap()
        
        log.d(tag = TAG) { "Translation session stopped" }
    }

    /**
     * Actualiza el idioma detectado para una dirección
     */
    fun updateDetectedLanguage(direction: TranslationDirection, language: String) {
        val currentLanguages = _detectedLanguages.value.toMutableMap()
        currentLanguages[direction] = language
        _detectedLanguages.value = currentLanguages

        val session = _translationSession.value
        if (session != null && direction == TranslationDirection.INCOMING) {
            _translationSession.value = session.copy(detectedRemoteLanguage = language)
        }

        log.d(tag = TAG) { "Language detected for $direction: $language" }
    }

    private fun startTranslationSystem() {
        log.d(tag = TAG) { "Starting translation system" }
        // El sistema se inicia cuando se inicia una llamada
    }

    private fun stopTranslationSystem() {
        log.d(tag = TAG) { "Stopping translation system" }
        
        stopTranslationForCall()
        
        openAiClient?.disconnect()
        openAiClient?.dispose()
        openAiClient = null
    }

    private fun setupOpenAiCallbacks() {
        openAiClient?.setCallbacks(
            onTranslatedAudio = { response ->
                handleTranslatedAudio(response)
            },
            onLanguageDetected = { result ->
                handleLanguageDetection(result)
            },
            onError = { error ->
                log.e(tag = TAG) { "OpenAI error: $error" }
            }
        )
    }

    private fun setupAudioInterception() {
        log.d(tag = TAG) { "Setting up audio interception" }

        originalAudioInterceptor = object : AudioInterceptor {
            override fun onIncomingAudio(audioData: ByteArray): ByteArray {
                if (_translationSession.value?.isActive == true) {
                    // Agregar audio entrante al buffer para traducción
                    incomingAudioBuffer.offer(audioData)
                    
                    // Por ahora retornamos silencio, el audio traducido llegará por callback
                    return ByteArray(audioData.size) // Silencio
                }
                return audioData // Sin traducción, retornar original
            }

            override fun onOutgoingAudio(audioData: ByteArray): ByteArray {
                if (_translationSession.value?.isActive == true) {
                    // Agregar audio saliente al buffer para traducción
                    outgoingAudioBuffer.offer(audioData)
                    
                    // Por ahora retornamos silencio, el audio traducido llegará por callback
                    return ByteArray(audioData.size) // Silencio
                }
                return audioData // Sin traducción, retornar original
            }
        }

        // Integrar con WebRTC Manager
        integrateWithWebRtc()
    }

    private fun removeAudioInterception() {
        log.d(tag = TAG) { "Removing audio interception" }
        originalAudioInterceptor = null
        // Restaurar audio normal en WebRTC
        restoreWebRtcAudio()
    }

    private fun startAudioProcessing() {
        if (isProcessingAudio) return

        isProcessingAudio = true
        audioProcessingJob = coroutineScope.launch {
            log.d(tag = TAG) { "Starting audio processing loops" }

            // Procesar audio entrante (del remoto)
            launch {
                while (isProcessingAudio) {
                    val audioData = incomingAudioBuffer.poll()
                    if (audioData != null) {
                        processIncomingAudio(audioData)
                    } else {
                        delay(10) // Pequeña pausa si no hay datos
                    }
                }
            }

            // Procesar audio saliente (del usuario)
            launch {
                while (isProcessingAudio) {
                    val audioData = outgoingAudioBuffer.poll()
                    if (audioData != null) {
                        processOutgoingAudio(audioData)
                    } else {
                        delay(10) // Pequeña pausa si no hay datos
                    }
                }
            }
        }
    }

    private fun stopAudioProcessing() {
        isProcessingAudio = false
        audioProcessingJob?.cancel()
        audioProcessingJob = null
        
        incomingAudioBuffer.clear()
        outgoingAudioBuffer.clear()
        
        log.d(tag = TAG) { "Audio processing stopped" }
    }

    private suspend fun processIncomingAudio(audioData: ByteArray) {
        try {
            val session = _translationSession.value ?: return
            if (!session.isActive) return

            val detectedRemoteLanguage = _detectedLanguages.value[TranslationDirection.INCOMING]
            val targetLanguage = session.userLanguage

            val request = AudioTranslationRequest(
                audioData = audioData,
                direction = TranslationDirection.INCOMING,
                sourceLanguage = detectedRemoteLanguage,
                targetLanguage = targetLanguage,
                sessionId = session.sessionId
            )

            openAiClient?.translateAudio(request)

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error processing incoming audio: ${e.message}" }
        }
    }

    private suspend fun processOutgoingAudio(audioData: ByteArray) {
        try {
            val session = _translationSession.value ?: return
            if (!session.isActive) return

            val sourceLanguage = session.userLanguage
            val detectedRemoteLanguage = _detectedLanguages.value[TranslationDirection.INCOMING]
            val targetLanguage = detectedRemoteLanguage ?: "en" // Default a inglés si no se detectó

            val request = AudioTranslationRequest(
                audioData = audioData,
                direction = TranslationDirection.OUTGOING,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                sessionId = session.sessionId
            )

            openAiClient?.translateAudio(request)

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error processing outgoing audio: ${e.message}" }
        }
    }

    private fun handleTranslatedAudio(response: AudioTranslationResponse) {
        if (!response.success) {
            log.e(tag = TAG) { "Translation failed: ${response.errorMessage}" }
            return
        }

        try {
            // Determinar la dirección y enviar el audio traducido
            val session = _translationSession.value ?: return
            
            // Inyectar el audio traducido en el stream de WebRTC
            injectTranslatedAudio(response.translatedAudio, response.sessionId)
            
            // Actualizar contador de mensajes traducidos
            _translationSession.value = session.copy(
                translatedMessages = session.translatedMessages + 1
            )

            log.d(tag = TAG) { "Translated audio injected successfully" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error handling translated audio: ${e.message}" }
        }
    }

    private fun handleLanguageDetection(result: LanguageDetectionResult) {
        log.d(tag = TAG) { "Language detected: ${result.detectedLanguage} (confidence: ${result.confidence})" }
        
        // Asumir que es idioma del remoto (incoming)
        updateDetectedLanguage(TranslationDirection.INCOMING, result.detectedLanguage)
        
        // Configurar sesiones de traducción con el idioma detectado
        coroutineScope.launch {
            val session = _translationSession.value ?: return@launch
            
            openAiClient?.setupTranslationSession(
                TranslationDirection.INCOMING,
                result.detectedLanguage,
                session.userLanguage
            )
            
            openAiClient?.setupTranslationSession(
                TranslationDirection.OUTGOING,
                session.userLanguage,
                result.detectedLanguage
            )
        }
    }

    private fun integrateWithWebRtc() {
        // Aquí integraríamos con el WebRtcManager para interceptar audio
        // Esta es una implementación conceptual - necesitaría acceso a los streams internos
        log.d(tag = TAG) { "Integrating with WebRTC for audio interception" }
        
        // En una implementación real, esto requeriría:
        // 1. Acceso a los MediaStreams de WebRTC
        // 2. Interceptar los AudioTracks
        // 3. Procesar el audio en tiempo real
        // 4. Reemplazar el audio con las traducciones
    }

    private fun restoreWebRtcAudio() {
        // Restaurar el audio normal de WebRTC
        log.d(tag = TAG) { "Restoring normal WebRTC audio" }
    }

    private fun injectTranslatedAudio(audioData: ByteArray, sessionId: String) {
        // Inyectar el audio traducido en el stream de WebRTC
        // Esta es una implementación conceptual
        log.d(tag = TAG) { "Injecting translated audio (${audioData.size} bytes) for session $sessionId" }
        
        // En una implementación real, esto requeriría:
        // 1. Convertir el audio a formato compatible con WebRTC
        // 2. Inyectarlo en el MediaStream apropiado
        // 3. Sincronizar con el timing de la llamada
    }

    /**
     * Obtiene estadísticas de la sesión de traducción actual
     */
    fun getTranslationStats(): TranslationSession? {
        return _translationSession.value
    }

    /**
     * Verifica si la traducción está activa
     */
    fun isTranslationActive(): Boolean {
        return _translationSession.value?.isActive == true
    }

    /**
     * Obtiene los idiomas soportados
     */
    fun getSupportedLanguages(): List<SupportedLanguage> {
        return SupportedLanguage.values().toList()
    }

    fun dispose() {
        log.d(tag = TAG) { "Disposing RealtimeTranslationManager" }
        stopTranslationSystem()
        coroutineScope.cancel()
    }
}