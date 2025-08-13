package com.eddyslarez.siplibrary.data.services.transcription

import android.app.Application
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Gestor principal de transcripción que coordina el interceptor de audio
 * y el servicio de transcripción
 * 
 * @author Eddys Larez
 */
class TranscriptionManager(private val application: Application) {
    
    private val TAG = "TranscriptionManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Componentes
    private val audioInterceptor = WebRtcAudioInterceptor()
    private val transcriptionService = AudioTranscriptionService(application)
    
    // Estados combinados
    private val _isActiveFlow = MutableStateFlow(false)
    val isActiveFlow: StateFlow<Boolean> = _isActiveFlow.asStateFlow()
    
    private val _currentSessionFlow = MutableStateFlow<TranscriptionSession?>(null)
    val currentSessionFlow: StateFlow<TranscriptionSession?> = _currentSessionFlow.asStateFlow()
    
    // Configuración actual
    private var currentConfig = AudioTranscriptionService.TranscriptionConfig()
    
    // Callbacks principales
    private var onTranscriptionResultCallback: ((AudioTranscriptionService.TranscriptionResult) -> Unit)? = null
    private var onSessionStateChangeCallback: ((TranscriptionSession) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    
    /**
     * Sesión de transcripción
     */
    data class TranscriptionSession(
        val id: String,
        val startTime: Long,
        val endTime: Long? = null,
        val callId: String,
        val config: AudioTranscriptionService.TranscriptionConfig,
        val results: List<AudioTranscriptionService.TranscriptionResult> = emptyList(),
        val totalDuration: Long = 0L,
        val audioQuality: WebRtcAudioInterceptor.AudioQuality = WebRtcAudioInterceptor.AudioQuality(),
        val statistics: SessionStatistics = SessionStatistics()
    ) {
        fun isActive(): Boolean = endTime == null
        
        fun getDuration(): Long {
            return (endTime ?: System.currentTimeMillis()) - startTime
        }
        
        fun getFinalResults(): List<AudioTranscriptionService.TranscriptionResult> {
            return results.filter { it.isFinal }
        }
        
        fun getPartialResults(): List<AudioTranscriptionService.TranscriptionResult> {
            return results.filter { !it.isFinal }
        }
    }
    
    data class SessionStatistics(
        val totalWords: Int = 0,
        val averageConfidence: Float = 0f,
        val speechDuration: Long = 0L,
        val silenceDuration: Long = 0L,
        val audioFramesProcessed: Long = 0L,
        val errorsCount: Int = 0
    )
    
    /**
     * Inicializa el gestor de transcripción
     */
    fun initialize() {
        log.d(tag = TAG) { "Initializing TranscriptionManager" }
        
        try {
            // Inicializar componentes
            transcriptionService.initialize()
            
            // Configurar callbacks entre componentes
            setupCallbacks()
            
            log.d(tag = TAG) { "TranscriptionManager initialized successfully" }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error initializing TranscriptionManager: ${e.message}" }
            onErrorCallback?.invoke("Initialization error: ${e.message}")
        }
    }
    
    /**
     * Configura callbacks entre componentes
     */
    private fun setupCallbacks() {
        // Callback del interceptor de audio al servicio de transcripción
        audioInterceptor.setCallbacks(
            onRemoteAudio = { audioData ->
                transcriptionService.processWebRtcAudio(
                    audioData, 
                    AudioTranscriptionService.AudioSource.WEBRTC_REMOTE
                )
            },
            onLocalAudio = { audioData ->
                transcriptionService.processWebRtcAudio(
                    audioData, 
                    AudioTranscriptionService.AudioSource.WEBRTC_LOCAL
                )
            },
            onAudioLevel = { level, source ->
                updateSessionAudioLevel(level, source)
            }
        )
        
        // Callback del servicio de transcripción
        transcriptionService.setCallbacks(
            onTranscription = { result ->
                handleTranscriptionResult(result)
            },
            onError = { error ->
                log.e(tag = TAG) { "Transcription service error: $error" }
                onErrorCallback?.invoke(error)
            }
        )
    }
    
    /**
     * Inicia una sesión de transcripción para una llamada
     */
    fun startTranscriptionSession(
        callId: String,
        config: AudioTranscriptionService.TranscriptionConfig = AudioTranscriptionService.TranscriptionConfig()
    ) {
        if (_isActiveFlow.value) {
            log.w(tag = TAG) { "Transcription session already active" }
            return
        }
        
        log.d(tag = TAG) { "Starting transcription session for call: $callId" }
        
        try {
            currentConfig = config.copy(isEnabled = true)
            
            // Crear nueva sesión
            val session = TranscriptionSession(
                id = com.eddyslarez.siplibrary.utils.generateId(),
                startTime = System.currentTimeMillis(),
                callId = callId,
                config = currentConfig
            )
            
            _currentSessionFlow.value = session
            _isActiveFlow.value = true
            
            // Iniciar componentes
            audioInterceptor.startIntercepting()
            transcriptionService.startTranscription(currentConfig)
            
            // Notificar cambio de sesión
            onSessionStateChangeCallback?.invoke(session)
            
            log.d(tag = TAG) { "Transcription session started: ${session.id}" }
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error starting transcription session: ${e.message}" }
            _isActiveFlow.value = false
            onErrorCallback?.invoke("Session start error: ${e.message}")
        }
    }
    
    /**
     * Detiene la sesión de transcripción actual
     */
    fun stopTranscriptionSession() {
        if (!_isActiveFlow.value) {
            log.d(tag = TAG) { "No active transcription session to stop" }
            return
        }
        
        log.d(tag = TAG) { "Stopping transcription session" }
        
        try {
            val currentSession = _currentSessionFlow.value
            
            // Detener componentes
            transcriptionService.stopTranscription()
            audioInterceptor.stopIntercepting()
            
            // Finalizar sesión
            if (currentSession != null) {
                val finalSession = currentSession.copy(
                    endTime = System.currentTimeMillis(),
                    results = transcriptionService.getTranscriptionHistory(),
                    statistics = calculateSessionStatistics(currentSession)
                )
                
                _currentSessionFlow.value = finalSession
                onSessionStateChangeCallback?.invoke(finalSession)
                
                log.d(tag = TAG) { 
                    "Transcription session ended: ${finalSession.id}, duration: ${finalSession.getDuration()}ms" 
                }
            }
            
            _isActiveFlow.value = false
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error stopping transcription session: ${e.message}" }
        }
    }
    
    /**
     * Intercepta audio WebRTC remoto
     */
    fun interceptRemoteAudio(audioData: ByteArray, timestamp: Long = System.currentTimeMillis()) {
        if (_isActiveFlow.value && currentConfig.audioSource != AudioTranscriptionService.AudioSource.WEBRTC_LOCAL) {
            audioInterceptor.interceptRemoteAudio(audioData, timestamp)
        }
    }
    
    /**
     * Intercepta audio WebRTC local
     */
    fun interceptLocalAudio(audioData: ByteArray, timestamp: Long = System.currentTimeMillis()) {
        if (_isActiveFlow.value && currentConfig.audioSource != AudioTranscriptionService.AudioSource.WEBRTC_REMOTE) {
            audioInterceptor.interceptLocalAudio(audioData, timestamp)
        }
    }
    
    /**
     * Maneja resultados de transcripción
     */
    private fun handleTranscriptionResult(result: AudioTranscriptionService.TranscriptionResult) {
        val currentSession = _currentSessionFlow.value ?: return
        
        // Añadir resultado a la sesión
        val updatedResults = currentSession.results.toMutableList()
        updatedResults.add(result)
        
        val updatedSession = currentSession.copy(
            results = updatedResults,
            statistics = calculateSessionStatistics(currentSession)
        )
        
        _currentSessionFlow.value = updatedSession
        
        // Notificar callbacks
        onTranscriptionResultCallback?.invoke(result)
        onSessionStateChangeCallback?.invoke(updatedSession)
        
        log.d(tag = TAG) { 
            "Transcription result processed: '${result.text}' (confidence: ${result.confidence})" 
        }
    }
    
    /**
     * Actualiza nivel de audio en la sesión
     */
    private fun updateSessionAudioLevel(level: Float, source: AudioTranscriptionService.AudioSource) {
        val currentSession = _currentSessionFlow.value ?: return
        
        // Actualizar calidad de audio en la sesión
        val audioQuality = audioInterceptor.audioQualityFlow.value
        val updatedSession = currentSession.copy(audioQuality = audioQuality)
        
        _currentSessionFlow.value = updatedSession
    }
    
    /**
     * Calcula estadísticas de la sesión
     */
    private fun calculateSessionStatistics(session: TranscriptionSession): SessionStatistics {
        val finalResults = session.getFinalResults()
        
        val totalWords = finalResults.sumOf { result ->
            result.text.split("\\s+".toRegex()).size
        }
        
        val averageConfidence = if (finalResults.isNotEmpty()) {
            finalResults.map { it.confidence }.average().toFloat()
        } else 0f
        
        val speechDuration = finalResults.sumOf { it.duration }
        val totalDuration = session.getDuration()
        val silenceDuration = totalDuration - speechDuration
        
        return SessionStatistics(
            totalWords = totalWords,
            averageConfidence = averageConfidence,
            speechDuration = speechDuration,
            silenceDuration = silenceDuration,
            audioFramesProcessed = audioInterceptor.getProcessingStatistics().totalFramesProcessed,
            errorsCount = 0 // Se podría trackear errores específicos
        )
    }
    
    /**
     * Actualiza configuración de transcripción
     */
    fun updateTranscriptionConfig(config: AudioTranscriptionService.TranscriptionConfig) {
        currentConfig = config
        transcriptionService.updateConfig(config)
        
        log.d(tag = TAG) { "Transcription config updated" }
    }
    
    /**
     * Configura callbacks principales
     */
    fun setCallbacks(
        onTranscriptionResult: ((AudioTranscriptionService.TranscriptionResult) -> Unit)? = null,
        onSessionStateChange: ((TranscriptionSession) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        this.onTranscriptionResultCallback = onTranscriptionResult
        this.onSessionStateChangeCallback = onSessionStateChange
        this.onErrorCallback = onError
    }
    
    // === MÉTODOS DE CONSULTA ===
    
    fun isActive(): Boolean = _isActiveFlow.value
    fun getCurrentSession(): TranscriptionSession? = _currentSessionFlow.value
    fun getCurrentConfig(): AudioTranscriptionService.TranscriptionConfig = currentConfig
    
    fun getTranscriptionHistory(): List<AudioTranscriptionService.TranscriptionResult> {
        return transcriptionService.getTranscriptionHistory()
    }
    
    fun getSessionHistory(): List<TranscriptionSession> {
        // En una implementación completa, esto vendría de una base de datos
        val currentSession = _currentSessionFlow.value
        return if (currentSession != null) listOf(currentSession) else emptyList()
    }
    
    /**
     * Exporta sesión de transcripción
     */
    fun exportSession(sessionId: String, format: ExportFormat = ExportFormat.TEXT): String? {
        val session = if (_currentSessionFlow.value?.id == sessionId) {
            _currentSessionFlow.value
        } else {
            // Buscar en historial (implementar según necesidades)
            null
        }
        
        return session?.let { exportSessionToFormat(it, format) }
    }
    
    enum class ExportFormat {
        TEXT,
        JSON,
        SRT, // Subtítulos
        VTT  // WebVTT
    }
    
    /**
     * Exporta sesión al formato especificado
     */
    private fun exportSessionToFormat(session: TranscriptionSession, format: ExportFormat): String {
        return when (format) {
            ExportFormat.TEXT -> exportAsText(session)
            ExportFormat.JSON -> exportAsJson(session)
            ExportFormat.SRT -> exportAsSrt(session)
            ExportFormat.VTT -> exportAsVtt(session)
        }
    }
    
    private fun exportAsText(session: TranscriptionSession): String {
        return buildString {
            appendLine("=== TRANSCRIPCIÓN DE LLAMADA ===")
            appendLine("ID de Sesión: ${session.id}")
            appendLine("ID de Llamada: ${session.callId}")
            appendLine("Inicio: ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm:ss").format(java.util.Date(session.startTime))}")
            appendLine("Duración: ${session.getDuration() / 1000}s")
            appendLine("Idioma: ${session.config.language}")
            appendLine("Fuente de Audio: ${session.config.audioSource}")
            appendLine()
            
            session.getFinalResults().forEach { result ->
                val timestamp = java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(result.timestamp))
                val speaker = result.speakerLabel ?: "Desconocido"
                appendLine("[$timestamp] $speaker: ${result.text}")
            }
        }
    }
    
    private fun exportAsJson(session: TranscriptionSession): String {
        // Implementación básica de JSON
        return """
        {
            "sessionId": "${session.id}",
            "callId": "${session.callId}",
            "startTime": ${session.startTime},
            "endTime": ${session.endTime},
            "duration": ${session.getDuration()},
            "config": {
                "language": "${session.config.language}",
                "audioSource": "${session.config.audioSource}",
                "provider": "${session.config.transcriptionProvider}"
            },
            "results": [
                ${session.getFinalResults().joinToString(",") { result ->
                    """
                    {
                        "id": "${result.id}",
                        "text": "${result.text.replace("\"", "\\\"")}",
                        "confidence": ${result.confidence},
                        "timestamp": ${result.timestamp},
                        "speaker": "${result.speakerLabel ?: "unknown"}"
                    }
                    """.trimIndent()
                }}
            ],
            "statistics": {
                "totalWords": ${session.statistics.totalWords},
                "averageConfidence": ${session.statistics.averageConfidence},
                "speechDuration": ${session.statistics.speechDuration},
                "silenceDuration": ${session.statistics.silenceDuration}
            }
        }
        """.trimIndent()
    }
    
    private fun exportAsSrt(session: TranscriptionSession): String {
        return buildString {
            session.getFinalResults().forEachIndexed { index, result ->
                val startTime = formatSrtTime(result.timestamp - session.startTime)
                val endTime = formatSrtTime(result.timestamp - session.startTime + result.duration)
                
                appendLine("${index + 1}")
                appendLine("$startTime --> $endTime")
                appendLine(result.text)
                appendLine()
            }
        }
    }
    
    private fun exportAsVtt(session: TranscriptionSession): String {
        return buildString {
            appendLine("WEBVTT")
            appendLine()
            
            session.getFinalResults().forEach { result ->
                val startTime = formatVttTime(result.timestamp - session.startTime)
                val endTime = formatVttTime(result.timestamp - session.startTime + result.duration)
                
                appendLine("$startTime --> $endTime")
                appendLine(result.text)
                appendLine()
            }
        }
    }
    
    private fun formatSrtTime(milliseconds: Long): String {
        val hours = milliseconds / 3600000
        val minutes = (milliseconds % 3600000) / 60000
        val seconds = (milliseconds % 60000) / 1000
        val millis = milliseconds % 1000
        
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }
    
    private fun formatVttTime(milliseconds: Long): String {
        val hours = milliseconds / 3600000
        val minutes = (milliseconds % 3600000) / 60000
        val seconds = (milliseconds % 60000) / 1000
        val millis = milliseconds % 1000
        
        return String.format("%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    }
    
    // === MÉTODOS PÚBLICOS PARA INTEGRACIÓN CON WEBRTC ===
    
    /**
     * Método principal para interceptar audio WebRTC remoto
     * Este método debe ser llamado desde AndroidWebRtcManager
     */
    fun onRemoteAudioFrame(audioData: ByteArray, timestamp: Long = System.currentTimeMillis()) {
        if (_isActiveFlow.value) {
            audioInterceptor.interceptRemoteAudio(audioData, timestamp)
        }
    }
    
    /**
     * Método principal para interceptar audio WebRTC local
     * Este método debe ser llamado desde AndroidWebRtcManager
     */
    fun onLocalAudioFrame(audioData: ByteArray, timestamp: Long = System.currentTimeMillis()) {
        if (_isActiveFlow.value) {
            audioInterceptor.interceptLocalAudio(audioData, timestamp)
        }
    }
    
    /**
     * Configura idioma de transcripción
     */
    fun setTranscriptionLanguage(language: String) {
        val updatedConfig = currentConfig.copy(language = language)
        updateTranscriptionConfig(updatedConfig)
    }
    
    /**
     * Habilita/deshabilita resultados parciales
     */
    fun setPartialResultsEnabled(enabled: Boolean) {
        val updatedConfig = currentConfig.copy(enablePartialResults = enabled)
        updateTranscriptionConfig(updatedConfig)
    }
    
    /**
     * Configura fuente de audio
     */
    fun setAudioSource(audioSource: AudioTranscriptionService.AudioSource) {
        val updatedConfig = currentConfig.copy(audioSource = audioSource)
        updateTranscriptionConfig(updatedConfig)
    }
    
    /**
     * Configura umbral de confianza
     */
    fun setConfidenceThreshold(threshold: Float) {
        val updatedConfig = currentConfig.copy(confidenceThreshold = threshold.coerceIn(0f, 1f))
        updateTranscriptionConfig(updatedConfig)
    }
    
    // === MÉTODOS DE CONSULTA ===
    
    fun getAudioLevel(): Float = audioInterceptor.audioLevelFlow.value
    fun getAudioQuality(): WebRtcAudioInterceptor.AudioQuality = audioInterceptor.audioQualityFlow.value
    
    fun getTranscriptionStatistics(): AudioTranscriptionService.TranscriptionStatistics {
        return transcriptionService.getTranscriptionStatistics()
    }
    
    fun getProcessingStatistics(): WebRtcAudioInterceptor.ProcessingStatistics {
        return audioInterceptor.getProcessingStatistics()
    }
    
    /**
     * Información de diagnóstico completa
     */
    fun getDiagnosticInfo(): String {
        val session = _currentSessionFlow.value
        val transcriptionStats = getTranscriptionStatistics()
        val processingStats = getProcessingStatistics()
        
        return buildString {
            appendLine("=== TRANSCRIPTION MANAGER DIAGNOSTIC ===")
            appendLine("Is Active: ${_isActiveFlow.value}")
            appendLine("Current Session: ${session?.id ?: "None"}")
            
            if (session != null) {
                appendLine("\n--- Current Session ---")
                appendLine("Session ID: ${session.id}")
                appendLine("Call ID: ${session.callId}")
                appendLine("Start Time: ${session.startTime}")
                appendLine("Duration: ${session.getDuration()}ms")
                appendLine("Results Count: ${session.results.size}")
                appendLine("Final Results: ${session.getFinalResults().size}")
                appendLine("Partial Results: ${session.getPartialResults().size}")
                
                appendLine("\n--- Session Statistics ---")
                appendLine("Total Words: ${session.statistics.totalWords}")
                appendLine("Average Confidence: ${session.statistics.averageConfidence}")
                appendLine("Speech Duration: ${session.statistics.speechDuration}ms")
                appendLine("Silence Duration: ${session.statistics.silenceDuration}ms")
            }
            
            appendLine("\n--- Configuration ---")
            appendLine("Language: ${currentConfig.language}")
            appendLine("Audio Source: ${currentConfig.audioSource}")
            appendLine("Provider: ${currentConfig.transcriptionProvider}")
            appendLine("Partial Results: ${currentConfig.enablePartialResults}")
            appendLine("Confidence Threshold: ${currentConfig.confidenceThreshold}")
            
            appendLine("\n${transcriptionService.getDiagnosticInfo()}")
            appendLine("\n${audioInterceptor.getDiagnosticInfo()}")
        }
    }
    
    /**
     * Limpieza de recursos
     */
    fun dispose() {
        stopTranscriptionSession()
        transcriptionService.dispose()
        audioInterceptor.dispose()
        
        onTranscriptionResultCallback = null
        onSessionStateChangeCallback = null
        onErrorCallback = null
        
        log.d(tag = TAG) { "TranscriptionManager disposed" }
    }
}