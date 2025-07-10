package com.eddyslarez.siplibrary.data.services.translation

/**
 * Gestor para guardar audios de conversaciones en el dispositivo
 * Almacena tanto el audio original como las traducciones
 *
 * @author Eddys Larez
 */
import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

class AudioRecordingManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "AudioRecordingManager"
        private const val CONVERSATIONS_FOLDER = "Conversaciones"
        private const val ORIGINAL_AUDIO_FOLDER = "Original"
        private const val TRANSLATED_AUDIO_FOLDER = "Traducido"
        private const val METADATA_FILE = "metadata.json"
        private const val AUDIO_FORMAT = ".wav"
        private const val MAX_AUDIO_DURATION_MS = 300000L // 5 minutos máximo por archivo
    }

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val audioQueue = ConcurrentLinkedQueue<AudioRecordingTask>()
    private val json = Json { prettyPrint = true }

    // Sesión de grabación actual
    private var currentRecordingSession: ConversationRecordingSession? = null
    private var isRecording = false
    private var recordingJob: Job? = null

    // Configuración de grabación
    private var recordingConfig = AudioRecordingConfig()

    /**
     * Inicia la grabación de una conversación
     */
    fun startRecording(callId: String, participantName: String? = null): Boolean {
        return try {
            if (isRecording) {
                log.w(tag = TAG) { "Recording already in progress" }
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

            // Crear directorios
            createRecordingDirectories(sessionId)

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
     * Detiene la grabación y guarda los metadatos
     */
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

    /**
     * Guarda audio original de la conversación
     */
    fun saveOriginalAudio(
        audioData: ByteArray,
        direction: TranslationDirection,
        language: String? = null
    ) {
        if (!isRecording) return

        val session = currentRecordingSession ?: return

        val task = AudioRecordingTask(
            sessionId = session.sessionId,
            audioData = audioData,
            audioType = AudioType.ORIGINAL,
            direction = direction,
            language = language,
            timestamp = System.currentTimeMillis()
        )

        audioQueue.offer(task)
    }

    /**
     * Guarda audio traducido
     */
    fun saveTranslatedAudio(
        audioData: ByteArray,
        direction: TranslationDirection,
        originalLanguage: String,
        translatedLanguage: String,
        originalText: String? = null,
        translatedText: String? = null
    ) {
        if (!isRecording) return

        val session = currentRecordingSession ?: return

        val task = AudioRecordingTask(
            sessionId = session.sessionId,
            audioData = audioData,
            audioType = AudioType.TRANSLATED,
            direction = direction,
            language = translatedLanguage,
            originalLanguage = originalLanguage,
            originalText = originalText,
            translatedText = translatedText,
            timestamp = System.currentTimeMillis()
        )

        audioQueue.offer(task)
    }

    /**
     * Configura las opciones de grabación
     */
    fun configure(config: AudioRecordingConfig) {
        recordingConfig = config
        log.d(tag = TAG) { "Recording configuration updated" }
    }

    /**
     * Obtiene la lista de conversaciones guardadas
     */
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

    /**
     * Obtiene los archivos de audio de una sesión específica
     */
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

    /**
     * Elimina una conversación completa
     */
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

    /**
     * Exporta una conversación a un archivo ZIP
     */
    fun exportConversation(sessionId: String): File? {
        return try {
            val sessionDir = getSessionDirectory(sessionId)
            if (!sessionDir.exists()) return null

            val exportFile = File(getConversationsDirectory(), "${sessionId}_export.zip")

            // Crear ZIP con todos los archivos de la sesión
            createZipFile(sessionDir, exportFile)

            log.d(tag = TAG) { "Conversation exported: $sessionId" }
            exportFile

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error exporting conversation: ${e.message}" }
            null
        }
    }

    // Métodos privados

    private fun startAudioProcessing() {
        recordingJob = coroutineScope.launch {
            while (isRecording) {
                try {
                    val task = audioQueue.poll()
                    if (task != null) {
                        processAudioTask(task)
                    } else {
                        delay(100) // Pequeña pausa si no hay tareas
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

    private suspend fun processAudioTask(task: AudioRecordingTask) {
        try {
            val sessionDir = getSessionDirectory(task.sessionId)
            val audioDir = when (task.audioType) {
                AudioType.ORIGINAL -> File(sessionDir, ORIGINAL_AUDIO_FOLDER)
                AudioType.TRANSLATED -> File(sessionDir, TRANSLATED_AUDIO_FOLDER)
            }

            // Crear nombre de archivo
            val filename = createAudioFilename(task)
            val audioFile = File(audioDir, filename)

            // Guardar archivo de audio
            withContext(Dispatchers.IO) {
                FileOutputStream(audioFile).use { fos ->
                    // Convertir PCM a WAV si es necesario
                    val wavData = convertPcmToWav(task.audioData)
                    fos.write(wavData)
                }
            }

            // Actualizar metadatos de la sesión
            updateSessionMetadata(task)

            log.d(tag = TAG) { "Audio saved: $filename" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error processing audio task: ${e.message}" }
        }
    }

    private fun createRecordingDirectories(sessionId: String) {
        val sessionDir = getSessionDirectory(sessionId)
        val originalDir = File(sessionDir, ORIGINAL_AUDIO_FOLDER)
        val translatedDir = File(sessionDir, TRANSLATED_AUDIO_FOLDER)

        sessionDir.mkdirs()
        originalDir.mkdirs()
        translatedDir.mkdirs()
    }

    private fun getConversationsDirectory(): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(downloadsDir, CONVERSATIONS_FOLDER)
    }

    private fun getSessionDirectory(sessionId: String): File {
        return File(getConversationsDirectory(), sessionId)
    }

    private fun createAudioFilename(task: AudioRecordingTask): String {
        val timestamp = SimpleDateFormat("HHmmss_SSS", Locale.getDefault()).format(Date(task.timestamp))
        val direction = task.direction.name.lowercase()
        val type = task.audioType.name.lowercase()
        val language = task.language ?: "unknown"

        return "${direction}_${type}_${language}_${timestamp}$AUDIO_FORMAT"
    }

    private fun convertPcmToWav(pcmData: ByteArray): ByteArray {
        // Parámetros de audio (ajustar según tu configuración)
        val sampleRate = 24000
        val channels = 1
        val bitsPerSample = 16

        val wavHeader = createWavHeader(pcmData.size, sampleRate, channels, bitsPerSample)
        return wavHeader + pcmData
    }

    private fun createWavHeader(dataSize: Int, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val totalSize = dataSize + 36

        return ByteArray(44).apply {
            // ChunkID "RIFF"
            System.arraycopy("RIFF".toByteArray(), 0, this, 0, 4)
            // ChunkSize
            writeInt(totalSize, 4)
            // Format "WAVE"
            System.arraycopy("WAVE".toByteArray(), 0, this, 8, 4)
            // Subchunk1ID "fmt "
            System.arraycopy("fmt ".toByteArray(), 0, this, 12, 4)
            // Subchunk1Size
            writeInt(16, 16)
            // AudioFormat
            writeShort(1, 20)
            // NumChannels
            writeShort(channels, 22)
            // SampleRate
            writeInt(sampleRate, 24)
            // ByteRate
            writeInt(byteRate, 28)
            // BlockAlign
            writeShort(blockAlign, 32)
            // BitsPerSample
            writeShort(bitsPerSample, 34)
            // Subchunk2ID "data"
            System.arraycopy("data".toByteArray(), 0, this, 36, 4)
            // Subchunk2Size
            writeInt(dataSize, 40)
        }
    }

    private fun ByteArray.writeInt(value: Int, offset: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value shr 8) and 0xFF).toByte()
        this[offset + 2] = ((value shr 16) and 0xFF).toByte()
        this[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun ByteArray.writeShort(value: Int, offset: Int) {
        this[offset] = (value and 0xFF).toByte()
        this[offset + 1] = ((value shr 8) and 0xFF).toByte()
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

        // Incrementar contador de archivos
        val updatedSession = session.copy(
            audioFileCount = session.audioFileCount + 1,
            lastActivityTime = System.currentTimeMillis()
        )

        currentRecordingSession = updatedSession
    }

    private fun generateSessionId(): String {
        return "conv_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
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

    private fun createZipFile(sourceDir: File, zipFile: File) {
        // Implementar creación de ZIP
        // Por simplicidad, aquí solo se indica la estructura
        // Necesitarías usar java.util.zip.ZipOutputStream
    }

    fun dispose() {
        log.d(tag = TAG) { "Disposing AudioRecordingManager" }
        if (isRecording) {
            stopRecording()
        }
        coroutineScope.cancel()
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