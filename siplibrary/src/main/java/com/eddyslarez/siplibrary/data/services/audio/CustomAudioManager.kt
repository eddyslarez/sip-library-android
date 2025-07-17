package com.eddyslarez.siplibrary.data.services.audio
import android.Manifest
import androidx.annotation.RequiresPermission
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

/**
 * Gestor principal para audio personalizado que integra intercepción, grabación y conversión
 *
 * @author Eddys Larez
 */
class CustomAudioManager(
    private val recordingsDir: File
) {
    private val TAG = "CustomAudioManager"
    private val scope = CoroutineScope(Dispatchers.IO)

    // Componentes
    private val audioInterception = AudioInterceptionManager()
    private val audioRecording = AudioRecordingManager(recordingsDir)
    private val formatConverter = AudioFormatConverter
    private val webRtcInterceptor = WebRtcAudioInterceptor()

    // Estado
    private val isActive = AtomicBoolean(false)
    private val activeCallSessions = ConcurrentHashMap<String, AudioSession>()
    private var monitoringJob: Job? = null

    /**
     * Sesión de audio para una llamada
     */
    data class AudioSession(
        val callId: String,
        val recordingSessionId: String?,
        val startTime: Long,
        var customInputEnabled: Boolean = false,
        var customOutputEnabled: Boolean = false,
        var recordingEnabled: Boolean = false
    )

    /**
     * Configuración de audio personalizado
     */
    data class CustomAudioConfig(
        val enableInterception: Boolean = true,
        val enableRecording: Boolean = false,
        val recordReceived: Boolean = true,
        val recordSent: Boolean = true,
        val customInputFile: File? = null,
        val customOutputFile: File? = null,
        val maxRecordingSize: Long = 100 * 1024 * 1024, // 100MB
        val maxRecordingDuration: Long = 3600 * 1000L // 1 hour
    )

    /**
     * Inicia el gestor de audio personalizado
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(config: CustomAudioConfig = CustomAudioConfig()) {
        if (isActive.get()) {
            log.w(TAG) { "CustomAudioManager already active" }
            return
        }

        log.d(TAG) { "Starting CustomAudioManager" }

        try {
            // Configurar grabación
            audioRecording.setRecordingEnabled(config.enableRecording)
            audioRecording.setRecordingTypes(config.recordReceived, config.recordSent)
            audioRecording.setMaxRecordingSize(config.maxRecordingSize)
            audioRecording.setMaxRecordingDuration(config.maxRecordingDuration)

            // Configurar intercepción
            if (config.enableInterception) {
                setupAudioInterception()
                setupWebRtcInterception()
                audioInterception.startInterception()
                webRtcInterceptor.start()
            }

            // Cargar archivos de audio personalizado
            config.customInputFile?.let { loadCustomInputAudio(it) }
            config.customOutputFile?.let { loadCustomOutputAudio(it) }

            // Iniciar monitoreo
            startMonitoring()

            isActive.set(true)
            log.d(TAG) { "CustomAudioManager started successfully" }

        } catch (e: Exception) {
            log.e(TAG) { "Error starting CustomAudioManager: ${e.message}" }
            stop()
        }
    }

    /**
     * Detiene el gestor de audio personalizado
     */
    fun stop() {
        if (!isActive.get()) {
            return
        }

        log.d(TAG) { "Stopping CustomAudioManager" }

        isActive.set(false)

        // Detener monitoreo
        monitoringJob?.cancel()
        monitoringJob = null

        // Detener intercepción
        audioInterception.stopInterception()
        webRtcInterceptor.stop()

        // Detener todas las sesiones activas
        activeCallSessions.values.forEach { session ->
            session.recordingSessionId?.let { sessionId ->
                scope.launch {
                    audioRecording.stopRecording(sessionId)
                }
            }
        }
        activeCallSessions.clear()

        log.d(TAG) { "CustomAudioManager stopped" }
    }

    /**
     * Configura la intercepción de audio
     */
    private fun setupAudioInterception() {
        audioInterception.setAudioListeners(
            onReceived = { audioData ->
                handleReceivedAudio(audioData)
            },
            onSent = { audioData ->
                handleSentAudio(audioData)
            }
        )
    }

    /**
     * Configura la intercepción WebRTC
     */
    private fun setupWebRtcInterception() {
        webRtcInterceptor.setAudioListeners(
            onOriginalReceived = { audioData ->
                handleOriginalReceivedAudio(audioData)
            },
            onOriginalSent = { audioData ->
                handleOriginalSentAudio(audioData)
            },
            onProcessedReceived = { audioData ->
                handleProcessedReceivedAudio(audioData)
            },
            onProcessedSent = { audioData ->
                handleProcessedSentAudio(audioData)
            }
        )
    }

    /**
     * Maneja audio original recibido (antes del reemplazo)
     */
    private fun handleOriginalReceivedAudio(audioData: ByteArray) {
        activeCallSessions.values.forEach { session ->
            if (session.recordingEnabled && session.recordingSessionId != null) {
                scope.launch {
                    // Convertir a PCMA para grabación
                    val pcmaData = formatConverter.convertToPCMA(audioData)
                    audioRecording.saveReceivedAudio(session.recordingSessionId, pcmaData)
                }
            }
        }
    }

    /**
     * Maneja audio original enviado (antes del reemplazo)
     */
    private fun handleOriginalSentAudio(audioData: ByteArray) {
        activeCallSessions.values.forEach { session ->
            if (session.recordingEnabled && session.recordingSessionId != null) {
                scope.launch {
                    // Convertir a PCMA para grabación
                    val pcmaData = formatConverter.convertToPCMA(audioData)
                    audioRecording.saveSentAudio(session.recordingSessionId, pcmaData)
                }
            }
        }
    }

    /**
     * Maneja audio procesado recibido (después del reemplazo)
     */
    private fun handleProcessedReceivedAudio(audioData: ByteArray) {
        // Este es el audio que realmente se reproduce
        log.d(TAG) { "Processed received audio: ${audioData.size} bytes" }
    }

    /**
     * Maneja audio procesado enviado (después del reemplazo)
     */
    private fun handleProcessedSentAudio(audioData: ByteArray) {
        // Este es el audio que realmente se envía
        log.d(TAG) { "Processed sent audio: ${audioData.size} bytes" }
    }

    /**
     * Maneja audio recibido
     */
    private fun handleReceivedAudio(audioData: ByteArray) {
        activeCallSessions.values.forEach { session ->
            if (session.recordingEnabled && session.recordingSessionId != null) {
                scope.launch {
                    audioRecording.saveReceivedAudio(session.recordingSessionId, audioData)
                }
            }
        }
    }

    /**
     * Maneja audio enviado
     */
    private fun handleSentAudio(audioData: ByteArray) {
        activeCallSessions.values.forEach { session ->
            if (session.recordingEnabled && session.recordingSessionId != null) {
                scope.launch {
                    audioRecording.saveSentAudio(session.recordingSessionId, audioData)
                }
            }
        }
    }

    /**
     * Inicia el monitoreo de sesiones
     */
    private fun startMonitoring() {
        monitoringJob = scope.launch {
            while (isActive.get()) {
                try {
                    // Limpiar sesiones inactivas
                    cleanupInactiveSessions()

                    // Esperar antes del próximo ciclo
                    kotlinx.coroutines.delay(5000) // 5 segundos

                } catch (e: Exception) {
                    log.e(TAG) { "Error in monitoring loop: ${e.message}" }
                }
            }
        }
    }

    /**
     * Limpia sesiones inactivas
     */
    private suspend fun cleanupInactiveSessions() {
        val currentTime = System.currentTimeMillis()
        val inactiveSessions = activeCallSessions.values.filter { session ->
            currentTime - session.startTime > 60000 // 1 minuto de inactividad
        }

        inactiveSessions.forEach { session ->
            log.d(TAG) { "Cleaning up inactive session: ${session.callId}" }
            endCallSession(session.callId)
        }
    }

    /**
     * Inicia una sesión de audio para una llamada
     */
    suspend fun startCallSession(
        callId: String,
        enableRecording: Boolean = false,
        enableCustomInput: Boolean = false,
        enableCustomOutput: Boolean = false
    ): AudioSession {
        log.d(TAG) { "Starting audio session for call: $callId" }

        val recordingSessionId = if (enableRecording) {
            audioRecording.startRecording(callId, true, true)
        } else null

        val session = AudioSession(
            callId = callId,
            recordingSessionId = recordingSessionId,
            startTime = System.currentTimeMillis(),
            customInputEnabled = enableCustomInput,
            customOutputEnabled = enableCustomOutput,
            recordingEnabled = enableRecording
        )

        activeCallSessions[callId] = session

        log.d(TAG) { "Audio session started for call: $callId" }
        return session
    }

    /**
     * Finaliza una sesión de audio
     */
    suspend fun endCallSession(callId: String): AudioRecordingManager.RecordingInfo? {
        log.d(TAG) { "Ending audio session for call: $callId" }

        val session = activeCallSessions.remove(callId) ?: return null

        val recordingInfo = session.recordingSessionId?.let { sessionId ->
            audioRecording.stopRecording(sessionId)
        }

        log.d(TAG) { "Audio session ended for call: $callId" }
        return recordingInfo
    }

    /**
     * Carga audio personalizado para entrada desde archivo
     */
    fun loadCustomInputAudio(file: File): Boolean {
        return try {
            if (!file.exists()) {
                log.w(TAG) { "Custom input audio file not found: ${file.absolutePath}" }
                return false
            }

            val audioData = file.readBytes()
            val convertedData = when (file.extension.lowercase()) {
                "pcma" -> audioData
                "wav" -> convertWavToPCMA(audioData)
                else -> {
                    log.w(TAG) { "Unsupported audio format: ${file.extension}" }
                    return false
                }
            }

            audioInterception.setCustomInputAudio(convertedData)
            log.d(TAG) { "Custom input audio loaded: ${file.name} (${convertedData.size} bytes)" }
            true

        } catch (e: Exception) {
            log.e(TAG) { "Error loading custom input audio: ${e.message}" }
            false
        }
    }

    /**
     * Carga audio personalizado para salida desde archivo
     */
    fun loadCustomOutputAudio(file: File): Boolean {
        return try {
            if (!file.exists()) {
                log.w(TAG) { "Custom output audio file not found: ${file.absolutePath}" }
                return false
            }

            val audioData = file.readBytes()
            val convertedData = when (file.extension.lowercase()) {
                "pcma" -> audioData
                "wav" -> convertWavToPCMA(audioData)
                else -> {
                    log.w(TAG) { "Unsupported audio format: ${file.extension}" }
                    return false
                }
            }

            audioInterception.setCustomOutputAudio(convertedData)
            log.d(TAG) { "Custom output audio loaded: ${file.name} (${convertedData.size} bytes)" }
            true

        } catch (e: Exception) {
            log.e(TAG) { "Error loading custom output audio: ${e.message}" }
            false
        }
    }

    /**
     * Configura audio personalizado desde ByteArray
     */
    fun setCustomInputAudio(audioData: ByteArray, format: AudioFormatConverter.AudioFormat = AudioFormatConverter.AudioFormat.PCMA) {
        val convertedData = when (format) {
            AudioFormatConverter.AudioFormat.PCMA -> audioData
            AudioFormatConverter.AudioFormat.PCM_16BIT -> formatConverter.convertToPCMA(audioData)
            else -> {
                log.w(TAG) { "Unsupported audio format: $format" }
                return
            }
        }

        audioInterception.setCustomInputAudio(convertedData)
        log.d(TAG) { "Custom input audio set: ${convertedData.size} bytes" }
    }

    /**
     * Habilita el reemplazo de audio de entrada durante la llamada
     */
    fun enableInputReplacement(callId: String, enable: Boolean) {
        val session = activeCallSessions[callId]
        if (session != null) {
            session.customInputEnabled = enable
            webRtcInterceptor.setInterceptInput(enable)
            log.d(TAG) { "Input replacement ${if (enable) "enabled" else "disabled"} for call $callId" }
        }
    }

    /**
     * Habilita el reemplazo de audio de salida durante la llamada
     */
    fun enableOutputReplacement(callId: String, enable: Boolean) {
        val session = activeCallSessions[callId]
        if (session != null) {
            session.customOutputEnabled = enable
            webRtcInterceptor.setInterceptOutput(enable)
            log.d(TAG) { "Output replacement ${if (enable) "enabled" else "disabled"} for call $callId" }
        }
    }

    /**
     * Actualiza el audio personalizado de entrada en tiempo real
     */
    fun updateCustomInputAudioRealTime(audioData: ByteArray, format: AudioFormatConverter.AudioFormat = AudioFormatConverter.AudioFormat.PCMA) {
        val convertedData = when (format) {
            AudioFormatConverter.AudioFormat.PCMA -> audioData
            AudioFormatConverter.AudioFormat.PCM_16BIT -> formatConverter.convertToPCMA(audioData)
            else -> {
                log.w(TAG) { "Unsupported audio format: $format" }
                return
            }
        }

        // Actualizar tanto el interceptor WebRTC como el manager de intercepción
        webRtcInterceptor.setCustomInputAudio(convertedData)
        audioInterception.setCustomInputAudio(convertedData)
        log.d(TAG) { "Custom input audio updated in real-time: ${convertedData.size} bytes" }
    }

    /**
     * Actualiza el audio personalizado de salida en tiempo real
     */
    fun updateCustomOutputAudioRealTime(audioData: ByteArray, format: AudioFormatConverter.AudioFormat = AudioFormatConverter.AudioFormat.PCMA) {
        val convertedData = when (format) {
            AudioFormatConverter.AudioFormat.PCMA -> audioData
            AudioFormatConverter.AudioFormat.PCM_16BIT -> formatConverter.convertToPCMA(audioData)
            else -> {
                log.w(TAG) { "Unsupported audio format: $format" }
                return
            }
        }

        // Actualizar tanto el interceptor WebRTC como el manager de intercepción
        webRtcInterceptor.setCustomOutputAudio(convertedData)
        audioInterception.setCustomOutputAudio(convertedData)
        log.d(TAG) { "Custom output audio updated in real-time: ${convertedData.size} bytes" }
    }

    /**
     * Configura audio personalizado para salida desde ByteArray
     */
    fun setCustomOutputAudio(audioData: ByteArray, format: AudioFormatConverter.AudioFormat = AudioFormatConverter.AudioFormat.PCMA) {
        val convertedData = when (format) {
            AudioFormatConverter.AudioFormat.PCMA -> audioData
            AudioFormatConverter.AudioFormat.PCM_16BIT -> formatConverter.convertToPCMA(audioData)
            else -> {
                log.w(TAG) { "Unsupported audio format: $format" }
                return
            }
        }

        audioInterception.setCustomOutputAudio(convertedData)
        log.d(TAG) { "Custom output audio set: ${convertedData.size} bytes" }
    }

    /**
     * Obtiene el audio original recibido (sin reemplazo)
     */
    fun getOriginalReceivedAudio(): ByteArray {
        return webRtcInterceptor.getOriginalReceivedAudio()
    }

    /**
     * Obtiene el audio original enviado (sin reemplazo)
     */
    fun getOriginalSentAudio(): ByteArray {
        return webRtcInterceptor.getOriginalSentAudio()
    }

    /**
     * Limpia los buffers de audio
     */
    fun clearAudioBuffers() {
        webRtcInterceptor.clearAudioBuffers()
        audioInterception.clearSavedAudio()
    }

    /**
     * Convierte WAV a PCMA
     */
    private fun convertWavToPCMA(wavData: ByteArray): ByteArray {
        return try {
            // Saltar el header WAV (44 bytes típicamente)
            val headerSize = 44
            if (wavData.size < headerSize) {
                log.w(TAG) { "WAV file too small" }
                return byteArrayOf()
            }

            val pcmData = wavData.copyOfRange(headerSize, wavData.size)
            formatConverter.convertToPCMA(pcmData)

        } catch (e: Exception) {
            log.e(TAG) { "Error converting WAV to PCMA: ${e.message}" }
            byteArrayOf()
        }
    }

    /**
     * Obtiene todas las grabaciones
     */
    fun getAllRecordings(): List<File> {
        return audioRecording.getAllRecordings()
    }

    /**
     * Obtiene grabaciones por call ID
     */
    fun getRecordingsByCallId(callId: String): List<File> {
        return audioRecording.getRecordingsByCallId(callId)
    }

    /**
     * Convierte una grabación a WAV
     */
    fun convertRecordingToWav(pcmaFile: File): File? {
        return audioRecording.convertToWav(pcmaFile)
    }

    /**
     * Elimina una grabación
     */
    fun deleteRecording(file: File): Boolean {
        return audioRecording.deleteRecording(file)
    }

    /**
     * Elimina todas las grabaciones
     */
    fun deleteAllRecordings(): Int {
        return audioRecording.deleteAllRecordings()
    }

    /**
     * Elimina grabaciones antiguas
     */
    fun deleteOldRecordings(olderThanDays: Int): Int {
        return audioRecording.deleteOldRecordings(olderThanDays)
    }

    /**
     * Habilita/deshabilita el guardado de audio
     */
    fun setRecordingEnabled(enabled: Boolean) {
        audioRecording.setRecordingEnabled(enabled)
        audioInterception.setSaveReceivedAudio(enabled)
        audioInterception.setSaveSentAudio(enabled)
    }

    /**
     * Obtiene las sesiones activas
     */
    fun getActiveSessions(): List<AudioSession> {
        return activeCallSessions.values.toList()
    }

    /**
     * Verifica si hay sesiones activas
     */
    fun hasActiveSessions(): Boolean {
        return activeCallSessions.isNotEmpty()
    }

    /**
     * Obtiene información de una sesión específica
     */
    fun getSessionInfo(callId: String): AudioSession? {
        return activeCallSessions[callId]
    }

    /**
     * Información de diagnóstico completa
     */
    fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== CUSTOM AUDIO MANAGER DIAGNOSTIC ===")
            appendLine("Manager active: ${isActive.get()}")
            appendLine("Active sessions: ${activeCallSessions.size}")
            appendLine("Monitoring job active: ${monitoringJob?.isActive ?: false}")

            appendLine("\n${audioInterception.getDiagnosticInfo()}")
            appendLine("\n${audioRecording.getDiagnosticInfo()}")
            appendLine("\n${formatConverter.getDiagnosticInfo()}")
            appendLine("\n${webRtcInterceptor.getDiagnosticInfo()}")

            if (activeCallSessions.isNotEmpty()) {
                appendLine("\n--- Active Sessions ---")
                activeCallSessions.values.forEach { session ->
                    appendLine("Call ID: ${session.callId}")
                    appendLine("  Recording ID: ${session.recordingSessionId}")
                    appendLine("  Start time: ${java.util.Date(session.startTime)}")
                    appendLine("  Custom input: ${session.customInputEnabled}")
                    appendLine("  Custom output: ${session.customOutputEnabled}")
                    appendLine("  Recording: ${session.recordingEnabled}")
                }
            }
        }
    }
}