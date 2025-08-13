package com.eddyslarez.siplibrary.data.services.transcription

import android.app.Application
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.speech.RecognizerIntent
import com.eddyslarez.siplibrary.utils.generateId
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Servicio de transcripción de audio en tiempo real
 * Intercepta el audio WebRTC y lo convierte a texto usando Speech-to-Text
 *
 * @author Eddys Larez
 */
class AudioTranscriptionService(private val application: Application) {

    private val TAG = "AudioTranscriptionService"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Estados del servicio
    private val _isTranscribingFlow = MutableStateFlow(false)
    val isTranscribingFlow: StateFlow<Boolean> = _isTranscribingFlow.asStateFlow()

    private val _transcriptionResultFlow = MutableStateFlow<TranscriptionResult?>(null)
    val transcriptionResultFlow: StateFlow<TranscriptionResult?> =
        _transcriptionResultFlow.asStateFlow()

    private val _transcriptionHistoryFlow = MutableStateFlow<List<TranscriptionResult>>(emptyList())
    val transcriptionHistoryFlow: StateFlow<List<TranscriptionResult>> =
        _transcriptionHistoryFlow.asStateFlow()

    private val _transcriptionConfigFlow = MutableStateFlow(TranscriptionConfig())
    val transcriptionConfigFlow: StateFlow<TranscriptionConfig> =
        _transcriptionConfigFlow.asStateFlow()

    // Componentes de transcripción
    private var speechRecognizer: SpeechRecognizer? = null
    private var audioProcessor: AudioProcessor? = null
    private var transcriptionJob: Job? = null

    // Buffer de audio para procesamiento
    private val audioBuffer = ConcurrentLinkedQueue<ByteArray>()
    private val maxBufferSize = 100 // Máximo 100 chunks de audio

    // Configuración de audio
    private val sampleRate = 16000 // 16kHz para mejor reconocimiento
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize =
        AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

    // Callbacks
    private var onTranscriptionCallback: ((TranscriptionResult) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null

    /**
     * Configuración de transcripción
     */
    data class TranscriptionConfig(
        val isEnabled: Boolean = false,
        val language: String = "es-ES", // Español por defecto
        val enablePartialResults: Boolean = true,
        val enableProfanityFilter: Boolean = false,
        val enablePunctuation: Boolean = true,
        val confidenceThreshold: Float = 0.5f,
        val maxSilenceDurationMs: Long = 3000L,
        val audioSource: AudioSource = AudioSource.WEBRTC_REMOTE,
        val transcriptionProvider: TranscriptionProvider = TranscriptionProvider.ANDROID_SPEECH
    )

    enum class AudioSource {
        WEBRTC_REMOTE,    // Audio del remoto (lo que escuchamos)
        WEBRTC_LOCAL,     // Audio local (lo que hablamos)
        WEBRTC_BOTH       // Ambos audios
    }

    enum class TranscriptionProvider {
        ANDROID_SPEECH,   // SpeechRecognizer de Android
        GOOGLE_CLOUD,     // Google Cloud Speech-to-Text
        AZURE_COGNITIVE,  // Azure Cognitive Services
        AWS_TRANSCRIBE    // AWS Transcribe
    }

    /**
     * Resultado de transcripción
     */
    data class TranscriptionResult(
        val id: String,
        val text: String,
        val confidence: Float,
        val isFinal: Boolean,
        val timestamp: Long,
        val duration: Long,
        val audioSource: AudioSource,
        val language: String,
        val speakerLabel: String? = null, // "local" o "remote"
        val wordTimestamps: List<WordTimestamp> = emptyList()
    )

    data class WordTimestamp(
        val word: String,
        val startTime: Long,
        val endTime: Long,
        val confidence: Float
    )

    /**
     * Inicializa el servicio de transcripción
     */
    fun initialize() {
        log.d(tag = TAG) { "Initializing AudioTranscriptionService" }

        try {
            // Verificar disponibilidad de SpeechRecognizer
            if (!SpeechRecognizer.isRecognitionAvailable(application)) {
                log.e(tag = TAG) { "Speech recognition not available on this device" }
                onErrorCallback?.invoke("Speech recognition not available")
                return
            }

            // Crear procesador de audio
            audioProcessor = AudioProcessor()

            log.d(tag = TAG) { "AudioTranscriptionService initialized successfully" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error initializing transcription service: ${e.message}" }
            onErrorCallback?.invoke("Initialization error: ${e.message}")
        }
    }

    /**
     * Inicia la transcripción
     */
    fun startTranscription(config: TranscriptionConfig = TranscriptionConfig()) {
        if (_isTranscribingFlow.value) {
            log.d(tag = TAG) { "Transcription already running" }
            return
        }

        log.d(tag = TAG) { "Starting audio transcription with config: $config" }

        _transcriptionConfigFlow.value = config
        _isTranscribingFlow.value = true

        try {
            when (config.transcriptionProvider) {
                TranscriptionProvider.ANDROID_SPEECH -> startAndroidSpeechRecognition(config)
                else -> {
                    log.w(tag = TAG) { "Provider ${config.transcriptionProvider} not implemented yet, using Android Speech" }
                    startAndroidSpeechRecognition(config)
                }
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error starting transcription: ${e.message}" }
            _isTranscribingFlow.value = false
            onErrorCallback?.invoke("Start error: ${e.message}")
        }
    }

    /**
     * Detiene la transcripción
     */
    fun stopTranscription() {
        if (!_isTranscribingFlow.value) {
            log.d(tag = TAG) { "Transcription not running" }
            return
        }

        log.d(tag = TAG) { "Stopping audio transcription" }

        try {
            // Detener reconocimiento de voz
            speechRecognizer?.stopListening()
            speechRecognizer?.destroy()
            speechRecognizer = null

            // Detener procesamiento de audio
            transcriptionJob?.cancel()
            transcriptionJob = null

            // Limpiar buffer
            audioBuffer.clear()

            _isTranscribingFlow.value = false

            log.d(tag = TAG) { "Audio transcription stopped successfully" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error stopping transcription: ${e.message}" }
        }
    }

    /**
     * Inicia reconocimiento con Android Speech
     */
    private fun startAndroidSpeechRecognition(config: TranscriptionConfig) {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(application)

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                log.d(tag = TAG) { "Speech recognizer ready" }
            }

            override fun onBeginningOfSpeech() {
                log.d(tag = TAG) { "Speech detected" }
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Audio level monitoring
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // Raw audio buffer (no siempre disponible)
            }

            override fun onEndOfSpeech() {
                log.d(tag = TAG) { "End of speech detected" }
            }

            override fun onError(error: Int) {
                val errorMessage = getSpeechErrorMessage(error)
                log.e(tag = TAG) { "Speech recognition error: $errorMessage" }

                // Reintentar automáticamente en ciertos errores
                if (shouldRetryOnError(error) && _isTranscribingFlow.value) {
                    scope.launch {
                        delay(1000)
                        if (_isTranscribingFlow.value) {
                            restartSpeechRecognition(config)
                        }
                    }
                } else {
                    onErrorCallback?.invoke(errorMessage)
                }
            }

            override fun onResults(results: Bundle?) {
                handleSpeechResults(results, isFinal = true, config)

                // Reiniciar reconocimiento para transcripción continua
                if (_isTranscribingFlow.value) {
                    scope.launch {
                        delay(100)
                        if (_isTranscribingFlow.value) {
                            restartSpeechRecognition(config)
                        }
                    }
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (config.enablePartialResults) {
                    handleSpeechResults(partialResults, isFinal = false, config)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                log.d(tag = TAG) { "Speech event: $eventType" }
            }
        })

        // Configurar intent de reconocimiento
        val intent = createRecognitionIntent(config)
        speechRecognizer?.startListening(intent)

        log.d(tag = TAG) { "Android Speech Recognition started" }
    }

    /**
     * Crea intent de reconocimiento
     */
    private fun createRecognitionIntent(config: TranscriptionConfig): android.content.Intent {
        return android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, config.language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, config.enablePartialResults)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, application.packageName)

            // Configuraciones adicionales
            if (config.enableProfanityFilter) {
                putExtra(
                    "android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES",
                    arrayOf(config.language)
                )
            }
        }
    }

    /**
     * Maneja resultados de reconocimiento de voz
     */
    private fun handleSpeechResults(
        results: Bundle?,
        isFinal: Boolean,
        config: TranscriptionConfig
    ) {
        if (results == null) return

        val matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val confidences = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

        if (matches != null && matches.isNotEmpty()) {
            val text = matches[0]
            val confidence = confidences?.get(0) ?: 0.5f

            // Filtrar por umbral de confianza
            if (confidence >= config.confidenceThreshold) {
                val result = TranscriptionResult(
                    id = generateId(),
                    text = text,
                    confidence = confidence,
                    isFinal = isFinal,
                    timestamp = System.currentTimeMillis(),
                    duration = 0L, // Se calculará después
                    audioSource = config.audioSource,
                    language = config.language,
                    speakerLabel = if (config.audioSource == AudioSource.WEBRTC_REMOTE) "remote" else "local"
                )

                // Emitir resultado
                _transcriptionResultFlow.value = result

                // Añadir al historial solo si es final
                if (isFinal) {
                    addToHistory(result)
                }

                // Notificar callback
                onTranscriptionCallback?.invoke(result)

                log.d(tag = TAG) {
                    "Transcription result: '$text' (confidence: $confidence, final: $isFinal)"
                }
            }
        }
    }

    /**
     * Reinicia el reconocimiento de voz
     */
    private fun restartSpeechRecognition(config: TranscriptionConfig) {
        try {
            scope.launch {
                speechRecognizer?.stopListening()
                delay(200)

                if (_isTranscribingFlow.value) {
                    val intent = createRecognitionIntent(config)
                    speechRecognizer?.startListening(intent)
                }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error restarting speech recognition: ${e.message}" }
        }
    }

    /**
     * Procesa audio WebRTC interceptado
     */
    fun processWebRtcAudio(audioData: ByteArray, audioSource: AudioSource) {
        if (!_isTranscribingFlow.value) return

        val config = _transcriptionConfigFlow.value

        // Filtrar por fuente de audio configurada
        if (config.audioSource != AudioSource.WEBRTC_BOTH && config.audioSource != audioSource) {
            return
        }

        try {
            // Añadir al buffer con control de tamaño
            if (audioBuffer.size < maxBufferSize) {
                audioBuffer.offer(audioData)
            } else {
                // Remover el más antiguo si el buffer está lleno
                audioBuffer.poll()
                audioBuffer.offer(audioData)
            }

            // Procesar audio acumulado
            audioProcessor?.processAudioChunk(audioData, audioSource)

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error processing WebRTC audio: ${e.message}" }
        }
    }

    /**
     * Añade resultado al historial
     */
    private fun addToHistory(result: TranscriptionResult) {
        val currentHistory = _transcriptionHistoryFlow.value.toMutableList()
        currentHistory.add(result)

        // Mantener solo los últimos 100 resultados
        if (currentHistory.size > 100) {
            currentHistory.removeAt(0)
        }

        _transcriptionHistoryFlow.value = currentHistory
    }

    /**
     * Actualiza configuración de transcripción
     */
    fun updateConfig(config: TranscriptionConfig) {
        val wasTranscribing = _isTranscribingFlow.value

        if (wasTranscribing) {
            stopTranscription()
        }

        _transcriptionConfigFlow.value = config

        if (wasTranscribing && config.isEnabled) {
            startTranscription(config)
        }

        log.d(tag = TAG) { "Transcription config updated: $config" }
    }

    /**
     * Obtiene mensaje de error de reconocimiento de voz
     */
    private fun getSpeechErrorMessage(error: Int): String {
        return when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Error de audio"
            SpeechRecognizer.ERROR_CLIENT -> "Error del cliente"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permisos insuficientes"
            SpeechRecognizer.ERROR_NETWORK -> "Error de red"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Timeout de red"
            SpeechRecognizer.ERROR_NO_MATCH -> "No se encontró coincidencia"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconocedor ocupado"
            SpeechRecognizer.ERROR_SERVER -> "Error del servidor"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Timeout de voz"
            else -> "Error desconocido: $error"
        }
    }

    /**
     * Determina si se debe reintentar en caso de error
     */
    private fun shouldRetryOnError(error: Int): Boolean {
        return when (error) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> true

            else -> false
        }
    }

    // === MÉTODOS PÚBLICOS ===

    /**
     * Configura callbacks
     */
    fun setCallbacks(
        onTranscription: ((TranscriptionResult) -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        this.onTranscriptionCallback = onTranscription
        this.onErrorCallback = onError
    }

    /**
     * Verifica si la transcripción está activa
     */
    fun isTranscribing(): Boolean = _isTranscribingFlow.value

    /**
     * Obtiene configuración actual
     */
    fun getCurrentConfig(): TranscriptionConfig = _transcriptionConfigFlow.value

    /**
     * Obtiene último resultado
     */
    fun getLastResult(): TranscriptionResult? = _transcriptionResultFlow.value

    /**
     * Obtiene historial completo
     */
    fun getTranscriptionHistory(): List<TranscriptionResult> = _transcriptionHistoryFlow.value

    /**
     * Limpia historial
     */
    fun clearHistory() {
        _transcriptionHistoryFlow.value = emptyList()
        log.d(tag = TAG) { "Transcription history cleared" }
    }

    /**
     * Obtiene estadísticas de transcripción
     */
    fun getTranscriptionStatistics(): TranscriptionStatistics {
        val history = _transcriptionHistoryFlow.value

        return TranscriptionStatistics(
            totalTranscriptions = history.size,
            finalTranscriptions = history.count { it.isFinal },
            partialTranscriptions = history.count { !it.isFinal },
            averageConfidence = if (history.isNotEmpty()) {
                history.map { it.confidence }.average().toFloat()
            } else 0f,
            totalDuration = history.sumOf { it.duration },
            languagesUsed = history.map { it.language }.distinct(),
            audioSourcesUsed = history.map { it.audioSource }.distinct()
        )
    }

    data class TranscriptionStatistics(
        val totalTranscriptions: Int,
        val finalTranscriptions: Int,
        val partialTranscriptions: Int,
        val averageConfidence: Float,
        val totalDuration: Long,
        val languagesUsed: List<String>,
        val audioSourcesUsed: List<AudioSource>
    )

    /**
     * Información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        val config = _transcriptionConfigFlow.value
        val stats = getTranscriptionStatistics()

        return buildString {
            appendLine("=== AUDIO TRANSCRIPTION SERVICE DIAGNOSTIC ===")
            appendLine("Is Transcribing: ${_isTranscribingFlow.value}")
            appendLine(
                "Speech Recognizer Available: ${
                    SpeechRecognizer.isRecognitionAvailable(
                        application
                    )
                }"
            )
            appendLine("Audio Buffer Size: ${audioBuffer.size}/$maxBufferSize")
            appendLine("Transcription Job Active: ${transcriptionJob?.isActive}")

            appendLine("\n--- Configuration ---")
            appendLine("Enabled: ${config.isEnabled}")
            appendLine("Language: ${config.language}")
            appendLine("Provider: ${config.transcriptionProvider}")
            appendLine("Audio Source: ${config.audioSource}")
            appendLine("Partial Results: ${config.enablePartialResults}")
            appendLine("Confidence Threshold: ${config.confidenceThreshold}")
            appendLine("Max Silence: ${config.maxSilenceDurationMs}ms")

            appendLine("\n--- Statistics ---")
            appendLine("Total Transcriptions: ${stats.totalTranscriptions}")
            appendLine("Final Results: ${stats.finalTranscriptions}")
            appendLine("Partial Results: ${stats.partialTranscriptions}")
            appendLine("Average Confidence: ${stats.averageConfidence}")
            appendLine("Total Duration: ${stats.totalDuration}ms")
            appendLine("Languages Used: ${stats.languagesUsed}")
            appendLine("Audio Sources: ${stats.audioSourcesUsed}")
        }
    }

    /**
     * Limpieza de recursos
     */
    fun dispose() {
        stopTranscription()
        audioProcessor?.dispose()
        audioProcessor = null
        log.d(tag = TAG) { "AudioTranscriptionService disposed" }
    }

    /**
     * Procesador de audio interno
     */
    private inner class AudioProcessor {
        private var processingJob: Job? = null

        fun processAudioChunk(audioData: ByteArray, audioSource: AudioSource) {
            // Aquí se procesaría el audio para enviarlo al reconocedor
            // Por ahora, el reconocimiento se hace a través del micrófono del sistema

            // En una implementación más avanzada, aquí se convertiría
            // el audio WebRTC a un formato que el SpeechRecognizer pueda usar
        }

        fun dispose() {
            processingJob?.cancel()
            processingJob = null
        }
    }
}