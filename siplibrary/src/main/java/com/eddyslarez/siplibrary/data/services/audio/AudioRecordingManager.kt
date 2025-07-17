package com.eddyslarez.siplibrary.data.services.audio

import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Gestor de grabación de audio para guardar audios recibidos y enviados
 * Maneja el almacenamiento en archivos con formato PCMA/8000
 *
 * @author Eddys Larez
 */
class AudioRecordingManager(
    private val recordingsDir: File
) {
    private val TAG = "AudioRecordingManager"
    private val scope = CoroutineScope(Dispatchers.IO)

    // Configuración
    private var isRecordingEnabled = false
    private var recordReceived = false
    private var recordSent = false
    private var maxRecordingSize: Long = 100 * 1024 * 1024 // 100MB default
    private var maxRecordingDuration = 3600 * 1000L // 1 hour default

    // Grabaciones activas
    private val activeRecordings = ConcurrentHashMap<String, RecordingSession>()
    private val recordingMutex = Mutex()

    // Estadísticas
    private var totalRecordings = 0
    private var totalSize = 0L

    init {
        // Crear directorio si no existe
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs()
        }
    }

    /**
     * Sesión de grabación activa
     */
    data class RecordingSession(
        val sessionId: String,
        val callId: String,
        val startTime: Long,
        val receivedFile: File?,
        val sentFile: File?,
        var receivedSize: Long = 0,
        var sentSize: Long = 0,
        var duration: Long = 0
    )

    /**
     * Información de grabación
     */
    data class RecordingInfo(
        val sessionId: String,
        val callId: String,
        val startTime: Long,
        val endTime: Long,
        val duration: Long,
        val receivedFile: File?,
        val sentFile: File?,
        val receivedSize: Long,
        val sentSize: Long,
        val totalSize: Long
    )

    /**
     * Inicia una nueva sesión de grabación
     */
    suspend fun startRecording(
        callId: String,
        recordReceived: Boolean = true,
        recordSent: Boolean = true
    ): String {
        return recordingMutex.withLock {
            if (!isRecordingEnabled) {
                throw IllegalStateException("Recording is disabled")
            }

            val sessionId = generateSessionId()
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

            val receivedFile = if (recordReceived) {
                File(recordingsDir, "${sessionId}_${timestamp}_received.pcma")
            } else null

            val sentFile = if (recordSent) {
                File(recordingsDir, "${sessionId}_${timestamp}_sent.pcma")
            } else null

            val session = RecordingSession(
                sessionId = sessionId,
                callId = callId,
                startTime = System.currentTimeMillis(),
                receivedFile = receivedFile,
                sentFile = sentFile
            )

            activeRecordings[sessionId] = session

            log.d(TAG) { "Started recording session: $sessionId for call: $callId" }
            log.d(TAG) { "Recording files - Received: ${receivedFile?.name}, Sent: ${sentFile?.name}" }

            sessionId
        }
    }

    /**
     * Detiene una sesión de grabación
     */
    suspend fun stopRecording(sessionId: String): RecordingInfo? {
        return recordingMutex.withLock {
            val session = activeRecordings.remove(sessionId) ?: return null

            val endTime = System.currentTimeMillis()
            session.duration = endTime - session.startTime

            val recordingInfo = RecordingInfo(
                sessionId = session.sessionId,
                callId = session.callId,
                startTime = session.startTime,
                endTime = endTime,
                duration = session.duration,
                receivedFile = session.receivedFile,
                sentFile = session.sentFile,
                receivedSize = session.receivedSize,
                sentSize = session.sentSize,
                totalSize = session.receivedSize + session.sentSize
            )

            totalRecordings++
            totalSize += recordingInfo.totalSize

            log.d(TAG) { "Stopped recording session: $sessionId" }
            log.d(TAG) { "Duration: ${session.duration}ms, Total size: ${recordingInfo.totalSize} bytes" }

            recordingInfo
        }
    }

    /**
     * Guarda audio recibido
     */
    suspend fun saveReceivedAudio(sessionId: String, audioData: ByteArray) {
        val session = activeRecordings[sessionId] ?: return
        val file = session.receivedFile ?: return

        scope.launch {
            try {
                // Verificar límites
                if (session.receivedSize + audioData.size > maxRecordingSize) {
                    log.w(TAG) { "Recording size limit exceeded for session: $sessionId" }
                    return@launch
                }

                if (System.currentTimeMillis() - session.startTime > maxRecordingDuration) {
                    log.w(TAG) { "Recording duration limit exceeded for session: $sessionId" }
                    return@launch
                }

                // Escribir datos
                FileOutputStream(file, true).use { fos ->
                    fos.write(audioData)
                    fos.flush()
                }

                session.receivedSize += audioData.size

            } catch (e: Exception) {
                log.e(TAG) { "Error saving received audio: ${e.message}" }
            }
        }
    }

    /**
     * Guarda audio enviado
     */
    suspend fun saveSentAudio(sessionId: String, audioData: ByteArray) {
        val session = activeRecordings[sessionId] ?: return
        val file = session.sentFile ?: return

        scope.launch {
            try {
                // Verificar límites
                if (session.sentSize + audioData.size > maxRecordingSize) {
                    log.w(TAG) { "Recording size limit exceeded for session: $sessionId" }
                    return@launch
                }

                if (System.currentTimeMillis() - session.startTime > maxRecordingDuration) {
                    log.w(TAG) { "Recording duration limit exceeded for session: $sessionId" }
                    return@launch
                }

                // Escribir datos
                FileOutputStream(file, true).use { fos ->
                    fos.write(audioData)
                    fos.flush()
                }

                session.sentSize += audioData.size

            } catch (e: Exception) {
                log.e(TAG) { "Error saving sent audio: ${e.message}" }
            }
        }
    }

    /**
     * Obtiene todas las grabaciones
     */
    fun getAllRecordings(): List<File> {
        return recordingsDir.listFiles()?.filter {
            it.isFile && it.name.endsWith(".pcma")
        } ?: emptyList()
    }

    /**
     * Obtiene grabaciones por call ID
     */
    fun getRecordingsByCallId(callId: String): List<File> {
        return getAllRecordings().filter { file ->
            file.name.contains(callId)
        }
    }

    /**
     * Elimina una grabación
     */
    fun deleteRecording(file: File): Boolean {
        return try {
            if (file.exists()) {
                totalSize -= file.length()
                file.delete()
            } else {
                false
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error deleting recording: ${e.message}" }
            false
        }
    }

    /**
     * Elimina todas las grabaciones
     */
    fun deleteAllRecordings(): Int {
        val recordings = getAllRecordings()
        var deleted = 0

        recordings.forEach { file ->
            if (deleteRecording(file)) {
                deleted++
            }
        }

        totalSize = 0
        totalRecordings = 0

        log.d(TAG) { "Deleted $deleted recordings" }
        return deleted
    }

    /**
     * Elimina grabaciones antiguas
     */
    fun deleteOldRecordings(olderThanDays: Int): Int {
        val cutoffTime = System.currentTimeMillis() - (olderThanDays * 24 * 60 * 60 * 1000L)
        val recordings = getAllRecordings()
        var deleted = 0

        recordings.forEach { file ->
            if (file.lastModified() < cutoffTime) {
                if (deleteRecording(file)) {
                    deleted++
                }
            }
        }

        log.d(TAG) { "Deleted $deleted old recordings (older than $olderThanDays days)" }
        return deleted
    }

    /**
     * Convierte una grabación PCMA a WAV
     */
    fun convertToWav(pcmaFile: File): File? {
        return try {
            val wavFile = File(pcmaFile.parent, pcmaFile.nameWithoutExtension + ".wav")

            // Leer datos PCMA
            val pcmaData = pcmaFile.readBytes()

            // Convertir a PCM
            val pcmData = AudioFormatConverter.convertFromPCMA(pcmaData)

            // Crear archivo WAV
            createWavFile(wavFile, pcmData)

            log.d(TAG) { "Converted ${pcmaFile.name} to ${wavFile.name}" }
            wavFile

        } catch (e: Exception) {
            log.e(TAG) { "Error converting to WAV: ${e.message}" }
            null
        }
    }

    /**
     * Crea un archivo WAV con datos PCM
     */
    private fun createWavFile(file: File, pcmData: ByteArray) {
        FileOutputStream(file).use { fos ->
            val sampleRate = 8000
            val channels = 1
            val bitsPerSample = 16
            val byteRate = sampleRate * channels * bitsPerSample / 8
            val blockAlign = channels * bitsPerSample / 8
            val dataSize = pcmData.size
            val chunkSize = 36 + dataSize

            // WAV header
            fos.write("RIFF".toByteArray())
            fos.write(intToByteArray(chunkSize))
            fos.write("WAVE".toByteArray())
            fos.write("fmt ".toByteArray())
            fos.write(intToByteArray(16)) // Subchunk1Size
            fos.write(shortToByteArray(1)) // AudioFormat (PCM)
            fos.write(shortToByteArray(channels))
            fos.write(intToByteArray(sampleRate))
            fos.write(intToByteArray(byteRate))
            fos.write(shortToByteArray(blockAlign))
            fos.write(shortToByteArray(bitsPerSample))
            fos.write("data".toByteArray())
            fos.write(intToByteArray(dataSize))

            // PCM data
            fos.write(pcmData)
        }
    }

    /**
     * Convierte int a byte array (little endian)
     */
    private fun intToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    /**
     * Convierte short a byte array (little endian)
     */
    private fun shortToByteArray(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        )
    }

    /**
     * Genera un ID único para la sesión
     */
    private fun generateSessionId(): String {
        return "rec_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }

    // === MÉTODOS DE CONFIGURACIÓN ===

    /**
     * Habilita/deshabilita la grabación
     */
    fun setRecordingEnabled(enabled: Boolean) {
        isRecordingEnabled = enabled
        log.d(TAG) { "Recording enabled: $enabled" }
    }

    /**
     * Configura qué tipos de audio grabar
     */
    fun setRecordingTypes(recordReceived: Boolean, recordSent: Boolean) {
        this.recordReceived = recordReceived
        this.recordSent = recordSent
        log.d(TAG) { "Recording types - Received: $recordReceived, Sent: $recordSent" }
    }

    /**
     * Configura el tamaño máximo de grabación
     */
    fun setMaxRecordingSize(maxSize: Long) {
        maxRecordingSize = maxSize
        log.d(TAG) { "Max recording size set to: $maxSize bytes" }
    }

    /**
     * Configura la duración máxima de grabación
     */
    fun setMaxRecordingDuration(maxDuration: Long) {
        maxRecordingDuration = maxDuration
        log.d(TAG) { "Max recording duration set to: $maxDuration ms" }
    }

    // === MÉTODOS DE CONSULTA ===

    /**
     * Obtiene el espacio total usado por grabaciones
     */
    fun getTotalSize(): Long {
        return getAllRecordings().sumOf { it.length() }
    }

    /**
     * Obtiene el número total de grabaciones
     */
    fun getTotalRecordings(): Int {
        return getAllRecordings().size
    }

    /**
     * Obtiene las sesiones activas
     */
    fun getActiveSessions(): List<RecordingSession> {
        return activeRecordings.values.toList()
    }

    /**
     * Verifica si hay grabaciones activas
     */
    fun hasActiveRecordings(): Boolean {
        return activeRecordings.isNotEmpty()
    }

    /**
     * Información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== AUDIO RECORDING MANAGER DIAGNOSTIC ===")
            appendLine("Recording enabled: $isRecordingEnabled")
            appendLine("Record received: $recordReceived")
            appendLine("Record sent: $recordSent")
            appendLine("Max recording size: ${maxRecordingSize / (1024 * 1024)} MB")
            appendLine("Max recording duration: ${maxRecordingDuration / 1000} seconds")
            appendLine("Total recordings: ${getTotalRecordings()}")
            appendLine("Total size: ${getTotalSize() / (1024 * 1024)} MB")
            appendLine("Active sessions: ${activeRecordings.size}")
            appendLine("Recordings directory: ${recordingsDir.absolutePath}")
            appendLine("Directory exists: ${recordingsDir.exists()}")
            appendLine("Directory writable: ${recordingsDir.canWrite()}")

            if (activeRecordings.isNotEmpty()) {
                appendLine("\n--- Active Sessions ---")
                activeRecordings.values.forEach { session ->
                    appendLine("${session.sessionId}: Call ${session.callId}")
                    appendLine("  Start time: ${Date(session.startTime)}")
                    appendLine("  Duration: ${System.currentTimeMillis() - session.startTime} ms")
                    appendLine("  Received size: ${session.receivedSize} bytes")
                    appendLine("  Sent size: ${session.sentSize} bytes")
                }
            }
        }
    }
}