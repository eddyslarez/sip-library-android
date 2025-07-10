package com.eddyslarez.siplibrary.data.services.translation

/**
 * Gestor principal para traducción en tiempo real durante llamadas VoIP
 * Intercepta el audio de WebRTC y lo reemplaza con traducciones
 *
 * @author Eddys Larez
 */
import android.app.Application
import android.media.AudioFormat
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import androidx.annotation.RequiresApi
import com.eddyslarez.siplibrary.data.services.audio.WebRtcManager
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.HashMap
import java.util.Timer
import java.util.TimerTask
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.webrtc.*
import java.util.Base64

class RealtimeTranslationManager(
    private val application: Application,
    private val webRtcManager: WebRtcManager
) {
    companion object {
        private const val TAG = "RealtimeTranslationManager"
        private const val AUDIO_BUFFER_SIZE = 1024
        private const val TRANSLATION_TIMEOUT_MS = 5000L
        private const val AUDIO_SAMPLE_RATE = 24000
        private const val AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
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

    // Componentes de audio - CORREGIDO: Usar tipos correctos
    private var originalLocalAudioTrack: org.webrtc.AudioTrack? = null
    private var originalRemoteAudioTrack: org.webrtc.AudioTrack? = null
    private var translatedAudioPlayer: android.media.AudioTrack? = null
    private var audioMixer: AudioMixer? = null
    private var webRtcAudioProcessor: WebRtcAudioProcessor? = null

    // Cache para audio traducido más reciente
    private val latestTranslatedAudio = HashMap<TranslationDirection, ByteArray>()

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

                    // Si hay traducción disponible, mezclar con el original
                    val translatedData = getLatestTranslatedAudio(TranslationDirection.INCOMING)
                    if (translatedData != null && _config.value.mixRatio > 0f) {
                        return audioMixer?.mix(audioData, translatedData, _config.value.mixRatio) ?: audioData
                    }

                    return audioData
                }
                return audioData // Sin traducción, retornar original
            }

            override fun onOutgoingAudio(audioData: ByteArray): ByteArray {
                if (_translationSession.value?.isActive == true) {
                    // Agregar audio saliente al buffer para traducción
                    outgoingAudioBuffer.offer(audioData)

                    // Retornar el audio original - la traducción se enviará por separado
                    return audioData
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

    /**
     * Integra con WebRTC para interceptar y procesar audio
     */
    private fun integrateWithWebRtc() {
        log.d(tag = TAG) { "Integrating with WebRTC for real-time audio translation" }

        try {
            // Crear el procesador de audio WebRTC
            webRtcAudioProcessor = WebRtcAudioProcessor(webRtcManager)

            // Configurar interceptor de audio
            webRtcAudioProcessor?.setAudioInterceptor(object : WebRtcAudioProcessor.AudioInterceptorCallback {
                override fun onIncomingAudioData(audioData: ByteArray): ByteArray {
                    return processIncomingAudioData(audioData)
                }

                override fun onOutgoingAudioData(audioData: ByteArray): ByteArray {
                    return processOutgoingAudioData(audioData)
                }
            })

            // Crear mixer de audio para combinar original + traducido
            audioMixer = AudioMixer()

            // Configurar reproductor de audio traducido
            setupTranslatedAudioPlayer()

            // Iniciar interceptor
            webRtcAudioProcessor?.start()

            log.d(tag = TAG) { "WebRTC integration completed successfully" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error integrating with WebRTC: ${e.message}" }
            throw Exception("Failed to integrate with WebRTC: ${e.message}")
        }
    }

    /**
     * Configura el reproductor de audio traducido
     */
    private fun setupTranslatedAudioPlayer() {
        try {
            val bufferSize = android.media.AudioTrack.getMinBufferSize(
                AUDIO_SAMPLE_RATE,
                AUDIO_CHANNEL_CONFIG,
                AUDIO_FORMAT
            )

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val audioFormatObj = AudioFormat.Builder()
                .setSampleRate(AUDIO_SAMPLE_RATE)
                .setChannelMask(AUDIO_CHANNEL_CONFIG)
                .setEncoding(AUDIO_FORMAT)
                .build()

            translatedAudioPlayer = android.media.AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormatObj)
                .setBufferSizeInBytes(bufferSize * 2)
                .setTransferMode(android.media.AudioTrack.MODE_STREAM)
                .build()

            log.d(tag = TAG) { "Translated audio player configured" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error setting up translated audio player: ${e.message}" }
        }
    }

    /**
     * Procesa datos de audio entrante para traducción
     */
    private fun processIncomingAudioData(audioData: ByteArray): ByteArray {
        val session = _translationSession.value
        if (session?.isActive != true) {
            return audioData // Sin traducción activa
        }

        try {
            // Agregar al buffer para traducción
            incomingAudioBuffer.offer(audioData)

            // Si hay traducción disponible, mezclar con el original
            val translatedData = getLatestTranslatedAudio(TranslationDirection.INCOMING)
            if (translatedData != null && _config.value.mixRatio > 0f) {
                // Mezclar audio original con traducido según configuración
                return audioMixer?.mix(audioData, translatedData, _config.value.mixRatio) ?: audioData
            }

            return audioData

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error processing incoming audio data: ${e.message}" }
            return audioData
        }
    }

    /**
     * Procesa datos de audio saliente para traducción
     */
    private fun processOutgoingAudioData(audioData: ByteArray): ByteArray {
        val session = _translationSession.value
        if (session?.isActive != true) {
            return audioData // Sin traducción activa
        }

        try {
            // Agregar al buffer para traducción
            outgoingAudioBuffer.offer(audioData)

            // Retornar el audio original - la traducción se enviará por separado
            return audioData

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error processing outgoing audio data: ${e.message}" }
            return audioData
        }
    }

    /**
     * Inyecta audio traducido en el stream de WebRTC
     */
    private fun injectTranslatedAudio(audioData: ByteArray, sessionId: String) {
        try {
            log.d(tag = TAG) { "Injecting translated audio (${audioData.size} bytes) for session $sessionId" }

            val session = _translationSession.value
            if (session?.sessionId != sessionId || session.isActive != true) {
                log.w(tag = TAG) { "Session mismatch or inactive, skipping audio injection" }
                return
            }

            // Determinar la dirección de la traducción
            val direction = determineTranslationDirection(sessionId)

            when (direction) {
                TranslationDirection.INCOMING -> {
                    // Audio traducido del remoto para el usuario
                    injectIncomingTranslatedAudio(audioData)
                }
                TranslationDirection.OUTGOING -> {
                    // Audio traducido del usuario para el remoto
                    injectOutgoingTranslatedAudio(audioData)
                }
            }

            // Actualizar estadísticas
            updateTranslationStats()

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error injecting translated audio: ${e.message}" }
        }
    }

    /**
     * Inyecta audio entrante traducido
     */
    private fun injectIncomingTranslatedAudio(audioData: ByteArray) {
        try {
            // Reproducir audio traducido directamente
            playTranslatedAudio(audioData)

            // Guardar para mezcla con audio original si está configurado
            storeLatestTranslatedAudio(TranslationDirection.INCOMING, audioData)

            log.d(tag = TAG) { "Incoming translated audio injected successfully" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error injecting incoming translated audio: ${e.message}" }
        }
    }

    /**
     * Inyecta audio saliente traducido
     */
    private fun injectOutgoingTranslatedAudio(audioData: ByteArray) {
        try {
            // Para audio saliente, necesitamos enviarlo a través del WebRTC
            sendTranslatedAudioToPeer(audioData)

            log.d(tag = TAG) { "Outgoing translated audio injected successfully" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error injecting outgoing translated audio: ${e.message}" }
        }
    }

    /**
     * Reproduce audio traducido en el dispositivo
     */
    private fun playTranslatedAudio(audioData: ByteArray) {
        try {
            val audioPlayer = translatedAudioPlayer
            if (audioPlayer != null) {
                if (audioPlayer.playState != android.media.AudioTrack.PLAYSTATE_PLAYING) {
                    audioPlayer.play()
                }

                // Escribir datos de audio
                val bytesWritten = audioPlayer.write(audioData, 0, audioData.size)
                if (bytesWritten < 0) {
                    log.e(tag = TAG) { "Error writing audio data: $bytesWritten" }
                }
            } else {
                log.w(tag = TAG) { "Translated audio player not available" }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error playing translated audio: ${e.message}" }
        }
    }

    /**
     * Envía audio traducido al peer a través de WebRTC
     */
    private fun sendTranslatedAudioToPeer(audioData: ByteArray) {
        try {
            // Obtener el peer connection del WebRtcManager usando reflexión
            val peerConnection = getPeerConnectionFromWebRtcManager()

            if (peerConnection != null) {
                // Buscar el sender de audio
                val audioSender = peerConnection.senders.find { sender ->
                    sender.track()?.kind() == "audio"
                }

                if (audioSender != null) {
                    // Crear un track temporal con el audio traducido
                    val translatedTrack = createAudioTrackFromData(audioData)

                    if (translatedTrack != null) {
                        // Reemplazar temporalmente el track - CORREGIDO: usar MediaStreamTrack
                        audioSender.setTrack(translatedTrack, false)

                        // Restaurar track original después de un breve período
                        coroutineScope.launch {
                            delay(100) // Duración del audio traducido
                            // Restaurar track original si aún existe
                            val originalTrack = getOriginalLocalAudioTrack()
                            if (originalTrack != null) {
                                audioSender.setTrack(originalTrack, false)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending translated audio to peer: ${e.message}" }
        }
    }

    /**
     * Crea un AudioTrack temporal desde datos de audio - CORREGIDO
     */
    private fun createAudioTrackFromData(audioData: ByteArray): org.webrtc.AudioTrack? {
        return try {
            // Obtener PeerConnectionFactory del WebRtcManager
            val factory = getPeerConnectionFactoryFromWebRtcManager()

            if (factory != null) {
                // Crear AudioSource
                val audioSource = factory.createAudioSource(MediaConstraints())

                // Crear AudioTrack usando el factory, no el source
                val audioTrack = factory.createAudioTrack("translated_audio_${System.currentTimeMillis()}", audioSource)

                // NOTA: Para inyectar datos de audio reales, necesitarías usar un AudioSource personalizado
                // o un mecanismo más complejo que permita alimentar datos de audio directamente
                audioTrack
            } else {
                log.e(tag = TAG) { "PeerConnectionFactory not available" }
                null
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating audio track from data: ${e.message}" }
            null
        }
    }

    /**
     * Crea un AudioSource para WebRTC
     */
    private fun createWebRtcAudioSource(): AudioSource? {
        return try {
            // Obtener PeerConnectionFactory del WebRtcManager
            val factory = getPeerConnectionFactoryFromWebRtcManager()
            factory?.createAudioSource(MediaConstraints())
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating WebRTC audio source: ${e.message}" }
            null
        }
    }

    /**
     * Obtiene el PeerConnection del WebRtcManager usando reflexión
     */
    private fun getPeerConnectionFromWebRtcManager(): PeerConnection? {
        return try {
            val webRtcManagerClass = webRtcManager::class.java
            val peerConnectionField = webRtcManagerClass.getDeclaredField("peerConnection")
            peerConnectionField.isAccessible = true
            peerConnectionField.get(webRtcManager) as? PeerConnection
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error accessing peer connection: ${e.message}" }
            null
        }
    }

    /**
     * Obtiene el PeerConnectionFactory del WebRtcManager
     */
    private fun getPeerConnectionFactoryFromWebRtcManager(): PeerConnectionFactory? {
        return try {
            // Crear una nueva factory (en una implementación real, reutilizarías la existente)
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(application)
                    .createInitializationOptions()
            )

            PeerConnectionFactory.builder()
                .createPeerConnectionFactory()
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error getting peer connection factory: ${e.message}" }
            null
        }
    }

    /**
     * Obtiene el track de audio local original - CORREGIDO
     */
    private fun getOriginalLocalAudioTrack(): org.webrtc.AudioTrack? {
        return try {
            val webRtcManagerClass = webRtcManager::class.java
            val localAudioField = webRtcManagerClass.getDeclaredField("localAudioTrack")
            localAudioField.isAccessible = true
            localAudioField.get(webRtcManager) as? org.webrtc.AudioTrack
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error getting original local audio track: ${e.message}" }
            null
        }
    }

    /**
     * Restaura el audio normal de WebRTC
     */
    private fun restoreWebRtcAudio() {
        try {
            log.d(tag = TAG) { "Restoring normal WebRTC audio" }

            // Detener procesador de audio
            webRtcAudioProcessor?.stop()
            webRtcAudioProcessor = null

            // Limpiar mixer
            audioMixer?.dispose()
            audioMixer = null

            // Detener y liberar reproductor de audio traducido
            translatedAudioPlayer?.let { player ->
                if (player.playState == android.media.AudioTrack.PLAYSTATE_PLAYING) {
                    player.stop()
                }
                player.release()
            }
            translatedAudioPlayer = null

            // Limpiar referencias
            originalLocalAudioTrack = null
            originalRemoteAudioTrack = null

            log.d(tag = TAG) { "WebRTC audio restored successfully" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error restoring WebRTC audio: ${e.message}" }
        }
    }

    // Funciones auxiliares

    private fun determineTranslationDirection(sessionId: String): TranslationDirection {
        // Determinar dirección basada en el sessionId o contexto
        return if (sessionId.contains("incoming")) {
            TranslationDirection.INCOMING
        } else {
            TranslationDirection.OUTGOING
        }
    }

    private fun storeLatestTranslatedAudio(direction: TranslationDirection, audioData: ByteArray) {
        latestTranslatedAudio[direction] = audioData
    }

    private fun getLatestTranslatedAudio(direction: TranslationDirection): ByteArray? {
        return latestTranslatedAudio[direction]
    }

    private fun updateTranslationStats() {
        val session = _translationSession.value
        if (session != null) {
            _translationSession.value = session.copy(
                translatedMessages = session.translatedMessages + 1
            )
        }
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



/**
 * Configuración extendida para traducción
 */
data class TranslationConfig(
    val isEnabled: Boolean = false,
    val openAiApiKey: String = "",
    val preferredLanguage: String = "es",
    val autoDetectLanguage: Boolean = true,
    val voiceGender: VoiceGender = VoiceGender.NEUTRAL,
    val mixRatio: Float = 0.7f, // Ratio de mezcla (0.0 = solo original, 1.0 = solo traducido)
    val realTimeMode: Boolean = true,
    val qualityMode: AudioQuality = AudioQuality.STANDARD
)

enum class VoiceGender { MALE, FEMALE, NEUTRAL }
enum class AudioQuality { LOW, STANDARD, HIGH }

/**
 * Datos adicionales necesarios para traducción
 */
data class AudioTranslationRequest(
    val audioData: ByteArray,
    val direction: TranslationDirection,
    val sourceLanguage: String?,
    val targetLanguage: String,
    val sessionId: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioTranslationRequest
        return audioData.contentEquals(other.audioData) &&
                direction == other.direction &&
                sourceLanguage == other.sourceLanguage &&
                targetLanguage == other.targetLanguage &&
                sessionId == other.sessionId
    }

    override fun hashCode(): Int {
        var result = audioData.contentHashCode()
        result = 31 * result + direction.hashCode()
        result = 31 * result + (sourceLanguage?.hashCode() ?: 0)
        result = 31 * result + targetLanguage.hashCode()
        result = 31 * result + sessionId.hashCode()
        return result
    }
}

data class AudioTranslationResponse(
    val translatedAudio: ByteArray,
    val originalText: String?,
    val translatedText: String?,
    val detectedLanguage: String?,
    val sessionId: String,
    val success: Boolean,
    val errorMessage: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioTranslationResponse
        return translatedAudio.contentEquals(other.translatedAudio) &&
                originalText == other.originalText &&
                translatedText == other.translatedText &&
                detectedLanguage == other.detectedLanguage &&
                sessionId == other.sessionId &&
                success == other.success &&
                errorMessage == other.errorMessage
    }

    override fun hashCode(): Int {
        var result = translatedAudio.contentHashCode()
        result = 31 * result + (originalText?.hashCode() ?: 0)
        result = 31 * result + (translatedText?.hashCode() ?: 0)
        result = 31 * result + (detectedLanguage?.hashCode() ?: 0)
        result = 31 * result + sessionId.hashCode()
        result = 31 * result + success.hashCode()
        result = 31 * result + (errorMessage?.hashCode() ?: 0)
        return result
    }
}

data class TranslationSession(
    val sessionId: String,
    val callId: String,
    val userLanguage: String,
    val detectedRemoteLanguage: String?,
    val isActive: Boolean,
    val startTime: Long,
    val translatedMessages: Int = 0
)

data class LanguageDetectionResult(
    val detectedLanguage: String,
    val confidence: Float,
    val timestamp: Long
)

enum class TranslationDirection { INCOMING, OUTGOING }

/**
 * Idiomas soportados
 */
enum class SupportedLanguage(val code: String, val displayName: String) {
    SPANISH("es", "Español"),
    ENGLISH("en", "English"),
    FRENCH("fr", "Français"),
    GERMAN("de", "Deutsch"),
    ITALIAN("it", "Italiano"),
    PORTUGUESE("pt", "Português"),
    CHINESE("zh", "中文"),
    JAPANESE("ja", "日本語"),
    KOREAN("ko", "한국어"),
    RUSSIAN("ru", "Русский");

    companion object {
        fun fromCode(code: String?): SupportedLanguage? {
            return values().find { it.code == code }
        }
    }

    fun getVoiceForGender(gender: VoiceGender): String {
        return when (this) {
            SPANISH -> when (gender) {
                VoiceGender.MALE -> "echo"
                VoiceGender.FEMALE -> "nova"
                VoiceGender.NEUTRAL -> "alloy"
            }
            ENGLISH -> when (gender) {
                VoiceGender.MALE -> "onyx"
                VoiceGender.FEMALE -> "shimmer"
                VoiceGender.NEUTRAL -> "alloy"
            }
            else -> "alloy" // Voz por defecto
        }
    }
}