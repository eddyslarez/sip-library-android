package com.eddyslarez.siplibrary.data.services.transcription

import com.eddyslarez.siplibrary.EddysSipLibrary
import com.eddyslarez.siplibrary.data.services.audio.AndroidWebRtcManager

/**
 * Extensiones para integrar transcripción con la librería SIP principal
 * 
 * @author Eddys Larez
 */

/**
 * Extensiones para EddysSipLibrary
 */

/**
 * Habilita transcripción de audio para llamadas
 */
fun EddysSipLibrary.enableAudioTranscription(
    config: AudioTranscriptionService.TranscriptionConfig = AudioTranscriptionService.TranscriptionConfig(
        isEnabled = true,
        language = "es-ES",
        enablePartialResults = true,
        audioSource = AudioTranscriptionService.AudioSource.WEBRTC_REMOTE
    )
): Boolean {
    return try {
        val webRtcManager = getWebRtcManager() as? AndroidWebRtcManager
        if (webRtcManager != null) {
            webRtcManager.enableTranscription(config)
            true
        } else {
            false
        }
    } catch (e: Exception) {
        false
    }
}

/**
 * Deshabilita transcripción de audio
 */
fun EddysSipLibrary.disableAudioTranscription() {
    try {
        val webRtcManager = getWebRtcManager() as? AndroidWebRtcManager
        webRtcManager?.disableTranscription()
    } catch (e: Exception) {
        // Manejar error silenciosamente
    }
}

/**
 * Obtiene el gestor de transcripción
 */
fun EddysSipLibrary.getTranscriptionManager(): TranscriptionManager? {
    return try {
        val webRtcManager = getWebRtcManager() as? AndroidWebRtcManager
        webRtcManager?.getTranscriptionManager()
    } catch (e: Exception) {
        null
    }
}

/**
 * Configura callbacks de transcripción
 */
fun EddysSipLibrary.setTranscriptionCallbacks(
    onTranscriptionResult: ((AudioTranscriptionService.TranscriptionResult) -> Unit)? = null,
    onSessionStateChange: ((TranscriptionManager.TranscriptionSession) -> Unit)? = null,
    onTranscriptionError: ((String) -> Unit)? = null
) {
    getTranscriptionManager()?.setCallbacks(
        onTranscriptionResult = onTranscriptionResult,
        onSessionStateChange = onSessionStateChange,
        onError = onTranscriptionError
    )
}

/**
 * Inicia transcripción para la llamada actual
 */
fun EddysSipLibrary.startTranscriptionForCurrentCall(
    config: AudioTranscriptionService.TranscriptionConfig = AudioTranscriptionService.TranscriptionConfig(isEnabled = true)
): Boolean {
    return try {
        val currentCallId = getCurrentCallId()
        if (currentCallId.isNotEmpty()) {
            val webRtcManager = getWebRtcManager() as? AndroidWebRtcManager
            webRtcManager?.startTranscriptionForCall(currentCallId, config)
            true
        } else {
            false
        }
    } catch (e: Exception) {
        false
    }
}

/**
 * Detiene transcripción para la llamada actual
 */
fun EddysSipLibrary.stopTranscriptionForCurrentCall() {
    try {
        val webRtcManager = getWebRtcManager() as? AndroidWebRtcManager
        webRtcManager?.stopTranscriptionForCall()
    } catch (e: Exception) {
        // Manejar error silenciosamente
    }
}

/**
 * Obtiene historial de transcripción
 */
fun EddysSipLibrary.getTranscriptionHistory(): List<AudioTranscriptionService.TranscriptionResult> {
    return getTranscriptionManager()?.getTranscriptionHistory() ?: emptyList()
}

/**
 * Exporta sesión de transcripción
 */
fun EddysSipLibrary.exportTranscriptionSession(
    sessionId: String,
    format: TranscriptionManager.ExportFormat = TranscriptionManager.ExportFormat.TEXT
): String? {
    return getTranscriptionManager()?.exportSession(sessionId, format)
}

/**
 * Configura idioma de transcripción
 */
fun EddysSipLibrary.setTranscriptionLanguage(language: String) {
    getTranscriptionManager()?.setTranscriptionLanguage(language)
}

/**
 * Configura fuente de audio para transcripción
 */
fun EddysSipLibrary.setTranscriptionAudioSource(audioSource: AudioTranscriptionService.AudioSource) {
    getTranscriptionManager()?.setAudioSource(audioSource)
}

/**
 * Verifica si la transcripción está activa
 */
fun EddysSipLibrary.isTranscriptionActive(): Boolean {
    return getTranscriptionManager()?.isActive() ?: false
}

/**
 * Obtiene sesión de transcripción actual
 */
fun EddysSipLibrary.getCurrentTranscriptionSession(): TranscriptionManager.TranscriptionSession? {
    return getTranscriptionManager()?.getCurrentSession()
}

/**
 * Obtiene estadísticas de transcripción
 */
fun EddysSipLibrary.getTranscriptionStatistics(): AudioTranscriptionService.TranscriptionStatistics? {
    return getTranscriptionManager()?.getTranscriptionStatistics()
}

/**
 * Obtiene nivel de audio actual
 */
fun EddysSipLibrary.getCurrentAudioLevel(): Float {
    return getTranscriptionManager()?.getAudioLevel() ?: 0f
}

/**
 * Obtiene calidad de audio actual
 */
fun EddysSipLibrary.getCurrentAudioQuality(): com.eddyslarez.siplibrary.data.services.transcription.WebRtcAudioInterceptor.AudioQuality? {
    return getTranscriptionManager()?.getAudioQuality()
}

/**
 * Limpia historial de transcripción
 */
fun EddysSipLibrary.clearTranscriptionHistory() {
    getTranscriptionManager()?.let { manager ->
        val transcriptionService = manager.getTranscriptionStatistics()
        // Acceder al servicio interno para limpiar historial
        // Esto requeriría exponer el método en TranscriptionManager
    }
}

// === MÉTODOS INTERNOS PARA ACCESO A COMPONENTES ===

/**
 * Obtiene el WebRTC Manager (método interno)
 */
private fun EddysSipLibrary.getWebRtcManager(): Any? {
    // Este método necesitaría ser implementado en EddysSipLibrary
    // para exponer el WebRtcManager interno
    return null // Placeholder
}

/**
 * Obtiene el ID de llamada actual (método interno)
 */
private fun EddysSipLibrary.getCurrentCallId(): String {
    // Este método necesitaría ser implementado en EddysSipLibrary
    // para obtener el ID de la llamada actual
    return "" // Placeholder
}