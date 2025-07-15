package com.eddyslarez.siplibrary.data.services.translation

import android.Manifest
import android.app.Application
import androidx.annotation.RequiresPermission
import com.eddyslarez.siplibrary.data.services.audio.AndroidWebRtcManager
import com.eddyslarez.siplibrary.data.models.AccountInfo
import com.eddyslarez.siplibrary.data.models.CallData
import com.eddyslarez.siplibrary.data.models.CallDirections
import com.eddyslarez.siplibrary.data.services.sip.SipMessageBuilder
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Integración principal de traducción con la librería SIP
 *
 * @author Eddys Larez
 */
class TranslationIntegration(
    private val application: Application
) {
    companion object {
        private const val TAG = "TranslationIntegration"
    }

    internal val realtimeTranslationManager = RealtimeTranslationManager(application)
    private val audioProcessor = TranslationAudioProcessor()

    // Referencia al WebRTC manager para integración de audio
    private var webRtcManager: AndroidWebRtcManager? = null

    // Estados
    private val _isTranslationActive = MutableStateFlow(false)
    val isTranslationActive: StateFlow<Boolean> = _isTranslationActive.asStateFlow()

    private val _currentCallTranslationInfo = MutableStateFlow<CallTranslationInfo?>(null)
    val currentCallTranslationInfo: StateFlow<CallTranslationInfo?> = _currentCallTranslationInfo.asStateFlow()

    // Configuración
    private var openAiApiKey: String? = null
    private var localLanguage: String = "es" // Español por defecto
    private var isTranslationEnabled: Boolean = false

    // Callbacks
    private var translationStatusCallback: ((Boolean, String?) -> Unit)? = null

    /**
     * Inicializar la integración de traducción
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun initialize(openAiApiKey: String, defaultLanguage: String = "es", webRtcManager: AndroidWebRtcManager? = null) {
        this.openAiApiKey = openAiApiKey
        this.localLanguage = defaultLanguage
        this.webRtcManager = webRtcManager

        realtimeTranslationManager.initialize(openAiApiKey)
        audioProcessor.initialize()

        setupCallbacks()

        log.d(tag = TAG) { "Translation integration initialized with language: $defaultLanguage" }
    }

    /**
     * Configurar callbacks internos
     */
    private fun setupCallbacks() {
        // Configurar integración con WebRTC si está disponible
        webRtcManager?.let { manager ->
            // Habilitar captura de audio desde WebRTC
            manager.enableTranslation { audioData ->
                if (_isTranslationActive.value) {
                    realtimeTranslationManager.sendAudioForTranslation(audioData)
                }
            }
        } ?: run {
            // Fallback: usar procesador de audio independiente
            audioProcessor.setAudioInputCallback { audioData ->
                if (_isTranslationActive.value) {
                    realtimeTranslationManager.sendAudioForTranslation(audioData)
                }
            }
        }

        // Callback para audio traducido recibido
        realtimeTranslationManager.setTranslationListener(object : TranslationEventListener {
            override fun onTranslationSessionStarted() {
                log.d(tag = TAG) { "Translation session started" }
                translationStatusCallback?.invoke(true, null)
            }

            override fun onSpeechDetected() {
                log.d(tag = TAG) { "Speech detected for translation" }
            }

            override fun onTranscriptionReceived(text: String) {
                log.d(tag = TAG) { "Transcription: $text" }
            }

            override fun onTranslatedAudioReceived(audioData: ByteArray) {
                // Reproducir audio traducido a través de WebRTC o procesador independiente
                webRtcManager?.playTranslatedAudio(audioData)
                    ?: audioProcessor.playTranslatedAudio(audioData)
            }

            override fun onTranslationCompleted() {
                log.d(tag = TAG) { "Translation completed" }
            }

            override fun onTranslationError(error: String) {
                log.e(tag = TAG) { "Translation error: $error" }
                translationStatusCallback?.invoke(false, error)
            }
        })
    }

    /**
     * Configurar idioma local
     */
    fun setLocalLanguage(language: String) {
        this.localLanguage = language
        log.d(tag = TAG) { "Local language set to: $language" }
    }

    /**
     * Habilitar/deshabilitar traducción
     */
    fun setTranslationEnabled(enabled: Boolean) {
        this.isTranslationEnabled = enabled
        realtimeTranslationManager.setTranslationEnabled(enabled)
        log.d(tag = TAG) { "Translation ${if (enabled) "enabled" else "disabled"}" }
    }

    /**
     * Configurar callback de estado de traducción
     */
    fun setTranslationStatusCallback(callback: (Boolean, String?) -> Unit) {
        this.translationStatusCallback = callback
    }

    /**
     * Modificar mensaje INVITE para incluir capacidades de traducción
     */
    fun enhanceInviteMessage(
        originalMessage: String,
        accountInfo: AccountInfo,
        callData: CallData
    ): String {
        if (!isTranslationEnabled) return originalMessage

        // Agregar headers de traducción
        val enhancedMessage = SipTranslationExtensions.addTranslationHeaders(
            sipMessage = originalMessage,
            supportsTranslation = true,
            preferredLanguage = localLanguage,
            translationEnabled = isTranslationEnabled
        )

        // Modificar SDP para incluir información de traducción
        val sdpStartIndex = enhancedMessage.indexOf("\r\n\r\n")
        if (sdpStartIndex != -1) {
            val headers = enhancedMessage.substring(0, sdpStartIndex + 4)
            val originalSdp = enhancedMessage.substring(sdpStartIndex + 4)

            val translationCapability = TranslationCapability(
                supportsTranslation = true,
                preferredLanguage = localLanguage,
                translationEnabled = isTranslationEnabled
            )

            val enhancedSdp = SipTranslationExtensions.addTranslationToSdp(originalSdp, translationCapability)

            return headers + enhancedSdp
        }

        return enhancedMessage
    }

    /**
     * Procesar mensaje INVITE recibido para detectar capacidades de traducción
     */
    fun processIncomingInvite(
        sipMessage: String,
        callData: CallData
    ): CallTranslationInfo? {
        // Extraer información de traducción de headers SIP
        val translationCapability = SipTranslationExtensions.extractTranslationInfo(sipMessage)

        // Extraer información de traducción del SDP
        val sdpStartIndex = sipMessage.indexOf("\r\n\r\n")
        val remoteLanguage = if (sdpStartIndex != -1) {
            val sdp = sipMessage.substring(sdpStartIndex + 4)
            SipTranslationExtensions.extractTranslationFromSdp(sdp) ?: translationCapability.preferredLanguage
        } else {
            translationCapability.preferredLanguage
        }

        // Determinar si se puede habilitar traducción
        val localCapability = TranslationCapability(
            supportsTranslation = true,
            preferredLanguage = localLanguage,
            translationEnabled = isTranslationEnabled
        )

        val compatibilityResult = SipTranslationExtensions.validateTranslationCompatibility(
            localCapability, translationCapability
        )

        val canTranslate = compatibilityResult == TranslationCompatibilityResult.FULLY_SUPPORTED

        if (canTranslate) {
            val (sourceLanguage, targetLanguage) = SipTranslationExtensions.determineTranslationLanguages(
                localLanguage, remoteLanguage
            )

            val translationInfo = CallTranslationInfo(
                isTranslationEnabled = true,
                localLanguage = sourceLanguage,
                remoteLanguage = targetLanguage,
                translationDirection = if (sourceLanguage != targetLanguage) {
                    TranslationDirection.BIDIRECTIONAL
                } else {
                    TranslationDirection.NONE
                }
            )

            _currentCallTranslationInfo.value = translationInfo

            log.d(tag = TAG) { "Translation enabled for call: $sourceLanguage -> $targetLanguage" }

            return translationInfo
        }

        log.d(tag = TAG) { "Translation not available for this call. Compatibility: $compatibilityResult" }
        return null
    }

    /**
     * Iniciar traducción para una llamada
     */
    fun startTranslationForCall(callData: CallData, translationInfo: CallTranslationInfo) {
        if (!translationInfo.isTranslationEnabled ||
            translationInfo.translationDirection == TranslationDirection.NONE) {
            return
        }

        // Configurar idiomas en el gestor de traducción
        realtimeTranslationManager.setLanguages(
            sourceLanguage = translationInfo.localLanguage,
            targetLanguage = translationInfo.remoteLanguage
        )

        // Habilitar traducción
        realtimeTranslationManager.setTranslationEnabled(true)

        // Iniciar captura de audio según el método disponible
        if (webRtcManager?.isTranslationEnabled() == false) {
            webRtcManager?.enableTranslation { audioData ->
                realtimeTranslationManager.sendAudioForTranslation(audioData)
            }
        } else {
            audioProcessor.startAudioCapture()
        }

        _isTranslationActive.value = true

        log.d(tag = TAG) { "Translation started for call ${callData.callId}" }
    }

    /**
     * Detener traducción para una llamada
     */
    fun stopTranslationForCall() {
        _isTranslationActive.value = false

        // Detener captura de audio según el método usado
        webRtcManager?.disableTranslation() ?: run {
            audioProcessor.stopAudioCapture()
            audioProcessor.stopAudioPlayback()
        }

        // Deshabilitar traducción
        realtimeTranslationManager.setTranslationEnabled(false)

        _currentCallTranslationInfo.value = null

        log.d(tag = TAG) { "Translation stopped" }
    }

    /**
     * Verificar si la traducción está disponible para una llamada
     */
    fun isTranslationAvailableForCall(callData: CallData): Boolean {
        return _currentCallTranslationInfo.value?.isTranslationEnabled == true
    }

    /**
     * Obtener información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        val audioStats = audioProcessor.getAudioStats()
        val translationState = realtimeTranslationManager.translationState.value
        val currentInfo = _currentCallTranslationInfo.value

        return buildString {
            appendLine("=== TRANSLATION INTEGRATION DIAGNOSTIC ===")
            appendLine("Translation Active: ${_isTranslationActive.value}")
            appendLine("Translation Enabled: $isTranslationEnabled")
            appendLine("Local Language: $localLanguage")
            appendLine("Translation State: $translationState")
            appendLine("OpenAI API Key Set: ${openAiApiKey != null}")
            appendLine("WebRTC Integration: ${webRtcManager != null}")
            appendLine("WebRTC Translation Enabled: ${webRtcManager?.isTranslationEnabled() ?: false}")

            appendLine("\n--- Current Call Translation ---")
            if (currentInfo != null) {
                appendLine("Enabled: ${currentInfo.isTranslationEnabled}")
                appendLine("Local Language: ${currentInfo.localLanguage}")
                appendLine("Remote Language: ${currentInfo.remoteLanguage}")
                appendLine("Direction: ${currentInfo.translationDirection}")
            } else {
                appendLine("No active translation")
            }

            appendLine("\n--- Audio Processor ---")
            appendLine("Recording: ${audioStats.isRecording}")
            appendLine("Playing: ${audioStats.isPlaying}")
            appendLine("Sample Rate: ${audioStats.sampleRate}")
            appendLine("Input Buffer: ${audioStats.inputBufferSize}")
            appendLine("Output Buffer: ${audioStats.outputBufferSize}")

            // Información de WebRTC si está disponible
            webRtcManager?.let { manager ->
                appendLine("\n--- WebRTC Audio Integration ---")
                appendLine(manager.diagnoseAudioIssues())
            }
        }
    }

    /**
     * Liberar recursos
     */
    fun dispose() {
        stopTranslationForCall()
        webRtcManager?.disableTranslation()
        audioProcessor.dispose()
        realtimeTranslationManager.dispose()

        _currentCallTranslationInfo.value = null
        translationStatusCallback = null
        webRtcManager = null

        log.d(tag = TAG) { "Translation integration disposed" }
    }
}