package com.eddyslarez.siplibrary.data.services.translation

/**
 * Gestor para guardar audios de conversaciones - CORREGIDO
 * Almacena tanto el audio original como las traducciones
 */
import android.content.Context
import android.os.Environment
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry
import java.io.FileInputStream

class AudioRecordingManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "AudioRecordingManager"
        private const val CONVERSATIONS_FOLDER = "SipConversations"
        private const val ORIGINAL_AUDIO_FOLDER = "Original"
        private const val TRANSLATED_AUDIO_FOLDER = "Traducido"
        private const val METADATA_FILE = "metadata.json"
        private const val AUDIO_FORMAT = ".wav"
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val audioQueue = ConcurrentLinkedQueue<AudioRecordingTask>()
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    // Sesión de grabación actual
    private var currentRecordingSession: ConversationRecordingSession? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private var recordingConfig = AudioRecordingConfig()

    /**
     * CORREGIDO: Verificar permisos antes de iniciar grabación
     */
    fun startRecording(callId: String, participantName: String? = null): Boolean {
        return try {
            if (isRecording) {
                log.w(tag = TAG) { "Recording already in progress" }
                return false
            }

            // CRÍTICO: Verificar permisos
            if (!hasRequiredPermissions()) {
                log.e(tag = TAG) { "Missing required permissions for recording" }
                return false
            }

            // Crear sesión de grabación
            val sessionId = generateSessionId()
            val timestamp = System.currentTimeMillis()
            val date = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date(timestamp))

            currentRecordingSession = ConversationRecordingSession(
                sessionId = sessionId,
                callId = callId,
                participantName = participantName,
                startTime = timestamp,
                date = date,
                isActive = true
            )

            // CORREGIDO: Crear directorios con verificación
            if (!createRecordingDirectories(sessionId)) {
                log.e(tag = TAG) { "Failed to create recording directories" }
                return false
            }

            // Iniciar procesamiento de audio
            startAudioProcessing()

            isRecording = true
            log.d(tag = TAG) { "Recording started for call: $callId, session: $sessionId" }
            true

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error starting recording: ${e.message}" }
            false
        }
    }

    /**
     * CORREGIDO: Mejorado el guardado de audio
     */
    fun saveOriginalAudio(
        audioData: ByteArray,
        direction: TranslationDirection,
        language: String? = null
    ) {
        if (!isRecording || audioData.isEmpty()) return

        val session = currentRecordingSession ?: return

        val task = AudioRecordingTask(
            sessionId = session.sessionId,
            audioData = audioData.copyOf(), // IMPORTANTE: Copiar los datos
            audioType = AudioType.ORIGINAL,
            direction = direction,
            language = language,
            timestamp = System.currentTimeMillis()
        )

        audioQueue.offer(task)
        log.d(tag = TAG) { "Queued original audio: ${audioData.size} bytes, direction: $direction" }
    }

    /**
     * CORREGIDO: Mejorado el guardado de audio traducido
     */
    fun saveTranslatedAudio(
        audioData: ByteArray,
        direction: TranslationDirection,
        originalLanguage: String,
        translatedLanguage: String,
        originalText: String? = null,
        translatedText: String? = null
    ) {
        if (!isRecording || audioData.isEmpty()) return

        val session = currentRecordingSession ?: return

        val task = AudioRecordingTask(
            sessionId = session.sessionId,
            audioData = audioData.copyOf(), // IMPORTANTE: Copiar los datos
            audioType = AudioType.TRANSLATED,
            direction = direction,
            language = translatedLanguage,
            originalLanguage = originalLanguage,
            originalText = originalText,
            translatedText = translatedText,
            timestamp = System.currentTimeMillis()
        )

        audioQueue.offer(task)
        log.d(tag = TAG) { "Queued translated audio: ${audioData.size} bytes, direction: $direction" }
    }

    /**
     * CORREGIDO: Verificación de permisos
     */
    private fun hasRequiredPermissions(): Boolean {
        val permissions = arrayOf(
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )

        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * CORREGIDO: Creación de directorios con mejor manejo de errores
     */
    private fun createRecordingDirectories(sessionId: String): Boolean {
        return try {
            val sessionDir = getSessionDirectory(sessionId)
            val originalDir = File(sessionDir, ORIGINAL_AUDIO_FOLDER)
            val translatedDir = File(sessionDir, TRANSLATED_AUDIO_FOLDER)

            // Crear directorios
            val sessionCreated = sessionDir.mkdirs() || sessionDir.exists()
            val originalCreated = originalDir.mkdirs() || originalDir.exists()
            val translatedCreated = translatedDir.mkdirs() || translatedDir.exists()

            val success = sessionCreated && originalCreated && translatedCreated

            if (success) {
                log.d(tag = TAG) { "Recording directories created: ${sessionDir.absolutePath}" }
                // Verificar que realmente se pueden escribir archivos
                val testFile = File(sessionDir, "test.tmp")
                try {
                    testFile.writeText("test")
                    testFile.delete()
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Cannot write to recording directory: ${e.message}" }
                    return false
                }
            } else {
                log.e(tag = TAG) { "Failed to create recording directories" }
            }

            success
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating recording directories: ${e.message}" }
            false
        }
    }

    /**
     * CORREGIDO: Directorio usando el almacenamiento de la app
     */
    private fun getConversationsDirectory(): File {
        // Usar el directorio de la aplicación en lugar del público
        val appDirectory = File(context.getExternalFilesDir(null), CONVERSATIONS_FOLDER)
        if (!appDirectory.exists()) {
            appDirectory.mkdirs()
        }
        return appDirectory
    }

    private fun getSessionDirectory(sessionId: String): File {
        return File(getConversationsDirectory(), sessionId)
    }

    /**
     * CORREGIDO: Procesamiento de tareas de audio con mejor manejo de errores
     */
    private suspend fun processAudioTask(task: AudioRecordingTask) {
        try {
            log.d(tag = TAG) { "Processing audio task: ${task.audioType}, ${task.audioData.size} bytes" }

            val sessionDir = getSessionDirectory(task.sessionId)
            if (!sessionDir.exists()) {
                log.e(tag = TAG) { "Session directory does not exist: ${sessionDir.absolutePath}" }
                return
            }

            val audioDir = when (task.audioType) {
                AudioType.ORIGINAL -> File(sessionDir, ORIGINAL_AUDIO_FOLDER)
                AudioType.TRANSLATED -> File(sessionDir, TRANSLATED_AUDIO_FOLDER)
            }

            if (!audioDir.exists()) {
                log.e(tag = TAG) { "Audio directory does not exist: ${audioDir.absolutePath}" }
                return
            }

            // Crear nombre de archivo
            val filename = createAudioFilename(task)
            val audioFile = File(audioDir, filename)

            // CORREGIDO: Guardar archivo de audio con manejo de errores
            withContext(Dispatchers.IO) {
                try {
                    audioFile.parentFile?.mkdirs()

                    FileOutputStream(audioFile).use { fos ->
                        val wavData = convertPcmToWav(task.audioData)
                        fos.write(wavData)
                        fos.flush()
                    }

                    log.d(tag = TAG) { "Audio saved successfully: ${audioFile.absolutePath} (${audioFile.length()} bytes)" }

                    // Verificar que el archivo se guardó correctamente
                    if (!audioFile.exists() || audioFile.length() == 0L) {
                        log.e(tag = TAG) { "Audio file was not saved correctly: ${audioFile.absolutePath}" }
                        return@withContext
                    }

                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error writing audio file: ${e.message}" }
                    return@withContext
                }
            }

            // Actualizar metadatos de la sesión
            updateSessionMetadata(task)

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error processing audio task: ${e.message}" }
        }
    }

    /**
     * CORREGIDO: Conversión PCM a WAV con header correcto
     */
    private fun convertPcmToWav(pcmData: ByteArray): ByteArray {
        // Parámetros de audio
        val sampleRate = 24000
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        // Crear header WAV
        val header = ByteArray(44)

        // ChunkID "RIFF"
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()

        // ChunkSize
        val fileSize = pcmData.size + 36
        header[4] = (fileSize and 0xff).toByte()
        header[5] = ((fileSize shr 8) and 0xff).toByte()
        header[6] = ((fileSize shr 16) and 0xff).toByte()
        header[7] = ((fileSize shr 24) and 0xff).toByte()

        // Format "WAVE"
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()

        // Subchunk1ID "fmt "
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()

        // Subchunk1Size (16 for PCM)
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0

        // AudioFormat (1 for PCM)
        header[20] = 1
        header[21] = 0

        // NumChannels
        header[22] = channels.toByte()
        header[23] = 0

        // SampleRate
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()

        // ByteRate
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()

        // BlockAlign
        header[32] = blockAlign.toByte()
        header[33] = 0

        // BitsPerSample
        header[34] = bitsPerSample.toByte()
        header[35] = 0

        // Subchunk2ID "data"
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()

        // Subchunk2Size
        header[40] = (pcmData.size and 0xff).toByte()
        header[41] = ((pcmData.size shr 8) and 0xff).toByte()
        header[42] = ((pcmData.size shr 16) and 0xff).toByte()
        header[43] = ((pcmData.size shr 24) and 0xff).toByte()

        return header + pcmData
    }

    /**
     * CORREGIDO: Implementación real del ZIP
     */
    private fun createZipFile(sourceDir: File, zipFile: File) {
        try {
            ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
                addDirectoryToZip(sourceDir, sourceDir.name, zipOut)
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating ZIP file: ${e.message}" }
        }
    }

    private fun addDirectoryToZip(sourceDir: File, parentPath: String, zipOut: ZipOutputStream) {
        sourceDir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                addDirectoryToZip(file, "$parentPath/${file.name}", zipOut)
            } else {
                val entry = ZipEntry("$parentPath/${file.name}")
                zipOut.putNextEntry(entry)

                FileInputStream(file).use { fis ->
                    fis.copyTo(zipOut)
                }
                zipOut.closeEntry()
            }
        }
    }

    // Resto de métodos sin cambios...
    private fun startAudioProcessing() {
        recordingJob = coroutineScope.launch {
            while (isRecording) {
                try {
                    val task = audioQueue.poll()
                    if (task != null) {
                        processAudioTask(task)
                    } else {
                        delay(100)
                    }
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error processing audio task: ${e.message}" }
                }
            }
        }
    }

    private fun stopAudioProcessing() {
        recordingJob?.cancel()
        recordingJob = null

        // Procesar tareas pendientes
        coroutineScope.launch {
            while (audioQueue.isNotEmpty()) {
                val task = audioQueue.poll()
                if (task != null) {
                    processAudioTask(task)
                }
            }
        }
    }

    fun stopRecording(): String? {
        return try {
            if (!isRecording) {
                log.w(tag = TAG) { "No recording in progress" }
                return null
            }

            val session = currentRecordingSession ?: return null

            // Detener procesamiento
            stopAudioProcessing()

            // Finalizar sesión
            val finalSession = session.copy(
                isActive = false,
                endTime = System.currentTimeMillis(),
                totalDurationMs = System.currentTimeMillis() - session.startTime
            )

            // Guardar metadatos
            saveSessionMetadata(finalSession)

            // Limpiar
            currentRecordingSession = null
            isRecording = false

            log.d(tag = TAG) { "Recording stopped for session: ${session.sessionId}" }
            session.sessionId

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error stopping recording: ${e.message}" }
            null
        }
    }

    private fun createAudioFilename(task: AudioRecordingTask): String {
        val timestamp = SimpleDateFormat("HHmmss_SSS", Locale.getDefault()).format(Date(task.timestamp))
        val direction = task.direction.name.lowercase()
        val type = task.audioType.name.lowercase()
        val language = task.language ?: "unknown"

        return "${direction}_${type}_${language}_${timestamp}$AUDIO_FORMAT"
    }

    private fun saveSessionMetadata(session: ConversationRecordingSession) {
        try {
            val sessionDir = getSessionDirectory(session.sessionId)
            val metadataFile = File(sessionDir, METADATA_FILE)

            val metadata = json.encodeToString(session)
            metadataFile.writeText(metadata)

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error saving session metadata: ${e.message}" }
        }
    }

    private fun updateSessionMetadata(task: AudioRecordingTask) {
        val session = currentRecordingSession ?: return

        val updatedSession = session.copy(
            audioFileCount = session.audioFileCount + 1,
            lastActivityTime = System.currentTimeMillis()
        )

        currentRecordingSession = updatedSession
    }

    private fun generateSessionId(): String {
        return "conv_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
    }

    fun configure(config: AudioRecordingConfig) {
        recordingConfig = config
        log.d(tag = TAG) { "Recording configuration updated" }
    }

    fun dispose() {
        log.d(tag = TAG) { "Disposing AudioRecordingManager" }
        if (isRecording) {
            stopRecording()
        }
        coroutineScope.cancel()
    }

    // Métodos de lectura sin cambios...
    fun getSavedConversations(): List<ConversationRecordingSession> {
        return try {
            val conversationsDir = getConversationsDirectory()
            if (!conversationsDir.exists()) return emptyList()

            val sessions = mutableListOf<ConversationRecordingSession>()

            conversationsDir.listFiles()?.forEach { sessionDir ->
                if (sessionDir.isDirectory) {
                    val metadataFile = File(sessionDir, METADATA_FILE)
                    if (metadataFile.exists()) {
                        try {
                            val metadata = metadataFile.readText()
                            val session = json.decodeFromString<ConversationRecordingSession>(metadata)
                            sessions.add(session)
                        } catch (e: Exception) {
                            log.e(tag = TAG) { "Error reading metadata for ${sessionDir.name}: ${e.message}" }
                        }
                    }
                }
            }

            sessions.sortedByDescending { it.startTime }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error getting saved conversations: ${e.message}" }
            emptyList()
        }
    }

    fun getSessionAudioFiles(sessionId: String): List<AudioFileInfo> {
        return try {
            val sessionDir = getSessionDirectory(sessionId)
            if (!sessionDir.exists()) return emptyList()

            val audioFiles = mutableListOf<AudioFileInfo>()

            // Buscar archivos de audio original
            val originalDir = File(sessionDir, ORIGINAL_AUDIO_FOLDER)
            if (originalDir.exists()) {
                originalDir.listFiles()?.forEach { file ->
                    if (file.extension == "wav") {
                        audioFiles.add(
                            AudioFileInfo(
                                file = file,
                                audioType = AudioType.ORIGINAL,
                                direction = parseDirectionFromFilename(file.name),
                                language = parseLanguageFromFilename(file.name),
                                timestamp = parseTimestampFromFilename(file.name)
                            )
                        )
                    }
                }
            }

            // Buscar archivos de audio traducido
            val translatedDir = File(sessionDir, TRANSLATED_AUDIO_FOLDER)
            if (translatedDir.exists()) {
                translatedDir.listFiles()?.forEach { file ->
                    if (file.extension == "wav") {
                        audioFiles.add(
                            AudioFileInfo(
                                file = file,
                                audioType = AudioType.TRANSLATED,
                                direction = parseDirectionFromFilename(file.name),
                                language = parseLanguageFromFilename(file.name),
                                timestamp = parseTimestampFromFilename(file.name)
                            )
                        )
                    }
                }
            }

            audioFiles.sortedBy { it.timestamp }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error getting session audio files: ${e.message}" }
            emptyList()
        }
    }

    fun deleteConversation(sessionId: String): Boolean {
        return try {
            val sessionDir = getSessionDirectory(sessionId)
            if (sessionDir.exists()) {
                sessionDir.deleteRecursively()
                log.d(tag = TAG) { "Conversation deleted: $sessionId" }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error deleting conversation: ${e.message}" }
            false
        }
    }

    fun exportConversation(sessionId: String): File? {
        return try {
            val sessionDir = getSessionDirectory(sessionId)
            if (!sessionDir.exists()) return null

            val exportFile = File(getConversationsDirectory(), "${sessionId}_export.zip")
            createZipFile(sessionDir, exportFile)

            log.d(tag = TAG) { "Conversation exported: $sessionId" }
            exportFile

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error exporting conversation: ${e.message}" }
            null
        }
    }

    private fun parseDirectionFromFilename(filename: String): TranslationDirection {
        return when {
            filename.startsWith("incoming") -> TranslationDirection.INCOMING
            filename.startsWith("outgoing") -> TranslationDirection.OUTGOING
            else -> TranslationDirection.INCOMING
        }
    }

    private fun parseLanguageFromFilename(filename: String): String? {
        val parts = filename.split("_")
        return if (parts.size >= 3) parts[2] else null
    }

    private fun parseTimestampFromFilename(filename: String): Long {
        val parts = filename.split("_")
        return try {
            if (parts.size >= 4) {
                val timeStr = parts[3].replace(".wav", "")
                SimpleDateFormat("HHmmss_SSS", Locale.getDefault()).parse(timeStr)?.time ?: 0L
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }
}

// Clases de datos

@Serializable
data class ConversationRecordingSession(
    val sessionId: String,
    val callId: String,
    val participantName: String? = null,
    val startTime: Long,
    val endTime: Long? = null,
    val date: String,
    val totalDurationMs: Long = 0,
    val audioFileCount: Int = 0,
    val lastActivityTime: Long = startTime,
    val isActive: Boolean = true
)

data class AudioRecordingTask(
    val sessionId: String,
    val audioData: ByteArray,
    val audioType: AudioType,
    val direction: TranslationDirection,
    val language: String? = null,
    val originalLanguage: String? = null,
    val originalText: String? = null,
    val translatedText: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class AudioFileInfo(
    val file: File,
    val audioType: AudioType,
    val direction: TranslationDirection,
    val language: String?,
    val timestamp: Long
)

data class AudioRecordingConfig(
    val isEnabled: Boolean = true,
    val saveOriginalAudio: Boolean = true,
    val saveTranslatedAudio: Boolean = true,
    val audioQuality: AudioQuality = AudioQuality.STANDARD,
    val maxSessionDurationMs: Long = 3600000L, // 1 hora
    val autoDeleteAfterDays: Int = 30
)

enum class AudioType {
    ORIGINAL,
    TRANSLATED
}