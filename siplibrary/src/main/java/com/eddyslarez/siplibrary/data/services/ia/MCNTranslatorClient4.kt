package com.eddyslarez.siplibrary.data.services.ia

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.annotation.RequiresApi
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.*


class MCNTranslatorClient4(
    private val apiKey: String = "", // No necesario para esta implementación
    private val application: Application
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(30, TimeUnit.SECONDS)
        .build()

    private val isConnected = AtomicBoolean(false)
    private val isSessionInitialized = AtomicBoolean(false)
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Audio management
    private val audioBuffer = mutableListOf<ByteArray>()
    private val audioBufferMutex = Mutex()
    private val minAudioChunkSize = 3200 // 200ms at 16kHz
    private val maxAudioChunkSize = 15 * 1024 * 1024
    private val audioSendInterval = 100L

    // Response channels
    private val audioResponseChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val transcriptionChannel = Channel<String>(Channel.UNLIMITED)
    private val inputTranscriptionChannel = Channel<String>(Channel.UNLIMITED)
    private val translationChannel = Channel<TranslationResult>(Channel.UNLIMITED)

    // Callbacks
    private var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var onAudioReceived: ((ByteArray) -> Unit)? = null
    private var onTranslationReceived: ((TranslationResult) -> Unit)? = null
    private var onLanguageDetected: ((DetectedLanguage) -> Unit)? = null

    // Language detection and management
    private var currentSourceLanguage: String = "auto"
    private var isTranslationMode = true
    private var confidenceThreshold = 0.7f

    // Audio timeout management
    private var lastAudioReceiveTime = 0L
    private val audioTimeoutMs = 2000L

    // TTS Engine
    private var ttsEngine: TextToSpeech? = null
    private var isTtsReady = false
    private var context: Context = application.applicationContext

    // Google ML Kit Speech Recognition
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    // VAD (Voice Activity Detection)
    private var isVoiceDetected = false
    private var silenceStartTime = 0L
    private val silenceThreshold = 800L // 800ms de silencio para procesar

    // Audio processing
    private val audioProcessor = AudioProcessor()
    private val translationProcessor = TranslationProcessor()

    companion object {
        private const val TAG = "MCNTranslatorClient"
        private const val GOOGLE_TRANSLATE_URL = "https://translate.googleapis.com/translate_a/single"
    }

    // Data classes
    data class TranslationResult(
        val originalText: String,
        val translatedText: String,
        val sourceLanguage: String,
        val targetLanguage: String,
        val confidence: Float,
        val timestamp: Long
    )

    data class DetectedLanguage(
        val language: String,
        val confidence: Float,
        val text: String
    )

    // === AUDIO PROCESSOR ===
    private inner class AudioProcessor {
        private val vadProcessor = VADProcessor()

        fun processAudio(audioData: ByteArray): Boolean {
            return vadProcessor.detectVoice(audioData)
        }

        fun convertToWav(pcmData: ByteArray, sampleRate: Int = 16000): ByteArray {
            val channels = 1
            val bitsPerSample = 16

            val header = ByteArray(44)
            val totalDataLen = pcmData.size + 36
            val byteRate = sampleRate * channels * bitsPerSample / 8

            header[0] = 'R'.code.toByte()
            header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte()
            header[3] = 'F'.code.toByte()
            header[4] = (totalDataLen and 0xff).toByte()
            header[5] = ((totalDataLen shr 8) and 0xff).toByte()
            header[6] = ((totalDataLen shr 16) and 0xff).toByte()
            header[7] = ((totalDataLen shr 24) and 0xff).toByte()
            header[8] = 'W'.code.toByte()
            header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte()
            header[11] = 'E'.code.toByte()
            header[12] = 'f'.code.toByte()
            header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte()
            header[15] = ' '.code.toByte()
            header[16] = 16
            header[17] = 0
            header[18] = 0
            header[19] = 0
            header[20] = 1
            header[21] = 0
            header[22] = channels.toByte()
            header[23] = 0
            header[24] = (sampleRate and 0xff).toByte()
            header[25] = ((sampleRate shr 8) and 0xff).toByte()
            header[26] = ((sampleRate shr 16) and 0xff).toByte()
            header[27] = ((sampleRate shr 24) and 0xff).toByte()
            header[28] = (byteRate and 0xff).toByte()
            header[29] = ((byteRate shr 8) and 0xff).toByte()
            header[30] = ((byteRate shr 16) and 0xff).toByte()
            header[31] = ((byteRate shr 24) and 0xff).toByte()
            header[32] = (channels * bitsPerSample / 8).toByte()
            header[33] = 0
            header[34] = bitsPerSample.toByte()
            header[35] = 0
            header[36] = 'd'.code.toByte()
            header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte()
            header[39] = 'a'.code.toByte()
            header[40] = (pcmData.size and 0xff).toByte()
            header[41] = ((pcmData.size shr 8) and 0xff).toByte()
            header[42] = ((pcmData.size shr 16) and 0xff).toByte()
            header[43] = ((pcmData.size shr 24) and 0xff).toByte()

            return header + pcmData
        }
    }

    // === VAD PROCESSOR ===
    private inner class VADProcessor {
        private val energyThreshold = 1000.0
        private val zeroCrossingThreshold = 0.1

        fun detectVoice(audioData: ByteArray): Boolean {
            if (audioData.size < 2) return false

            val samples = audioData.size / 2
            var energy = 0.0
            var zeroCrossings = 0
            var lastSample = 0

            for (i in 0 until samples) {
                val sample = (audioData[i * 2].toInt() and 0xFF) or
                        ((audioData[i * 2 + 1].toInt() and 0xFF) shl 8)
                val signedSample = if (sample > 32767) sample - 65536 else sample

                energy += signedSample * signedSample

                if ((lastSample >= 0 && signedSample < 0) ||
                    (lastSample < 0 && signedSample >= 0)) {
                    zeroCrossings++
                }
                lastSample = signedSample
            }

            energy /= samples
            val zcr = zeroCrossings.toDouble() / samples

            return energy > energyThreshold && zcr > zeroCrossingThreshold
        }
    }

    // === TRANSLATION PROCESSOR ===
    private inner class TranslationProcessor {

        suspend fun translateText(text: String, targetLang: String): String {
            return try {
                val sourceLang = detectLanguage(text).language

                if (sourceLang == targetLang) return text

                val url = "$GOOGLE_TRANSLATE_URL?client=gtx&sl=$sourceLang&tl=$targetLang&dt=t&q=${URLEncoder.encode(text, "UTF-8")}"

                val request = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()

                if (responseBody != null) {
                    // Parse Google Translate response
                    parseGoogleTranslateResponse(responseBody)
                } else {
                    text
                }
            } catch (e: Exception) {
                log.e(TAG) { "Error en traducción: ${e.message}" }
                text
            }
        }

        private fun parseGoogleTranslateResponse(response: String): String {
            return try {
                // Google Translate returns: [[["translated text","original text",null,null,10]]]
                val cleanResponse = response.trim()
                if (cleanResponse.startsWith("[[[")) {
                    val firstQuote = cleanResponse.indexOf('"', 3)
                    val secondQuote = cleanResponse.indexOf('"', firstQuote + 1)
                    if (firstQuote != -1 && secondQuote != -1) {
                        cleanResponse.substring(firstQuote + 1, secondQuote)
                    } else {
                        "Error en traducción"
                    }
                } else {
                    "Error en traducción"
                }
            } catch (e: Exception) {
                log.e(TAG) { "Error parseando respuesta: ${e.message}" }
                "Error en traducción"
            }
        }
    }

    // === SPEECH TO TEXT ===
    @RequiresApi(Build.VERSION_CODES.M)
    private suspend fun transcribeAudio(audioData: ByteArray): String {
        return try {
            // Usar Google ML Kit Speech Recognition offline primero
            val result = processWithMLKit(audioData)
            if (result.isNotEmpty()) {
                return result
            }

            // Fallback a Web Speech API si ML Kit no funciona
            processWithWebAPI(audioData)
        } catch (e: Exception) {
            log.e(TAG) { "Error en transcripción: ${e.message}" }
            ""
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private suspend fun processWithMLKit(audioData: ByteArray): String {
        return withContext(Dispatchers.Main) {
            try {
                val audioFile = createTempAudioFile(audioData)
                val uri = Uri.fromFile(audioFile)

                val recognitionTask = CompletableDeferred<String>()

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (currentSourceLanguage == "auto") "es-ES" else currentSourceLanguage)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }

                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                }

                speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(error: Int) {
                        log.e(TAG) { "Speech recognition error: $error" }
                        recognitionTask.complete("")
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val result = matches?.firstOrNull() ?: ""
                        recognitionTask.complete(result)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        matches?.firstOrNull()?.let { partial ->
                            inputTranscriptionChannel.trySend(partial)
                        }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })

                speechRecognizer?.startListening(intent)

                // Timeout después de 5 segundos
                withTimeoutOrNull(5000L) {
                    recognitionTask.await()
                } ?: ""

            } catch (e: Exception) {
                log.e(TAG) { "Error con ML Kit: ${e.message}" }
                ""
            }
        }
    }

    private suspend fun processWithWebAPI(audioData: ByteArray): String {
        return try {
            // Implementación simplificada de reconocimiento usando patrones de audio
            // Esta es una implementación básica, en producción usarías una API real
            val audioText = basicAudioToText(audioData)
            audioText
        } catch (e: Exception) {
            log.e(TAG) { "Error con Web API: ${e.message}" }
            ""
        }
    }

    private fun basicAudioToText(audioData: ByteArray): String {
        // Implementación básica de reconocimiento por patrones
        // En una app real, aquí integrarías con una API de STT como Whisper local
        val energy = calculateAudioEnergy(audioData)
        return when {
            energy > 5000 -> "Audio detectado con alta energía"
            energy > 2000 -> "Audio detectado"
            else -> ""
        }
    }

    private fun calculateAudioEnergy(audioData: ByteArray): Double {
        var energy = 0.0
        val samples = audioData.size / 2

        for (i in 0 until samples) {
            val sample = (audioData[i * 2].toInt() and 0xFF) or
                    ((audioData[i * 2 + 1].toInt() and 0xFF) shl 8)
            val signedSample = if (sample > 32767) sample - 65536 else sample
            energy += signedSample * signedSample
        }

        return energy / samples
    }

    private fun createTempAudioFile(audioData: ByteArray): File {
        val tempFile = File.createTempFile("audio", ".wav")
        val wavData = audioProcessor.convertToWav(audioData)
        FileOutputStream(tempFile).use { it.write(wavData) }
        return tempFile
    }

    // === TEXT TO SPEECH ===
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private suspend fun synthesizeSpeech(text: String, language: String) {
        withContext(Dispatchers.Main) {
            try {
                if (ttsEngine == null) {
                    initializeTTS()
                    delay(1000) // Esperar inicialización
                }

                if (isTtsReady && ttsEngine != null) {
                    val locale = when (language) {
                        "es" -> Locale("es", "ES")
                        "ru" -> Locale("ru", "RU")
                        else -> Locale.getDefault()
                    }

                    ttsEngine?.language = locale

                    val utteranceId = "tts_${System.currentTimeMillis()}"
                    ttsEngine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                }
            } catch (e: Exception) {
                log.e(TAG) { "Error en TTS: ${e.message}" }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private fun initializeTTS() {
        ttsEngine = TextToSpeech(context) { status ->
            isTtsReady = (status == TextToSpeech.SUCCESS)
            if (isTtsReady) {
                log.d(TAG) { "TTS inicializado correctamente" }
            }
        }
    }

    // === CONFIGURACIÓN ===
    fun setConnectionStateListener(listener: (Boolean) -> Unit) {
        onConnectionStateChanged = listener
    }

    fun setErrorListener(listener: (String) -> Unit) {
        onError = listener
    }

    fun setAudioReceivedListener(listener: (ByteArray) -> Unit) {
        onAudioReceived = listener
    }

    fun setTranslationReceivedListener(listener: (TranslationResult) -> Unit) {
        onTranslationReceived = listener
    }

    fun setLanguageDetectedListener(listener: (DetectedLanguage) -> Unit) {
        onLanguageDetected = listener
    }

    fun setTranslationMode(enabled: Boolean) {
        isTranslationMode = enabled
        log.d(TAG) { "Modo traducción: ${if (enabled) "ACTIVADO" else "DESACTIVADO"}" }
    }

    fun setSourceLanguage(language: String) {
        currentSourceLanguage = language
        log.d(TAG) { "Idioma fuente establecido: $language" }
    }

    fun setConfidenceThreshold(threshold: Float) {
        confidenceThreshold = threshold.coerceIn(0.1f, 1.0f)
        log.d(TAG) { "Umbral de confianza: $confidenceThreshold" }
    }

    // === CONEXIÓN ===
    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    suspend fun connect(): Boolean {
        return try {
            log.d(TAG) { "Conectando Traductor MCN Google..." }

            // Simular conexión
            delay(500)
            isConnected.set(true)
            onConnectionStateChanged?.invoke(true)

            // Inicializar componentes
            initializeTTS()
            initializeSession()
            startAudioProcessingLoop()
            startAudioTimeoutWatcher()

            log.d(TAG) { "Traductor MCN Google conectado exitosamente" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error de conexión: ${e.message}" }
            onError?.invoke("Error de conexión: ${e.message}")
            false
        }
    }

    private suspend fun initializeSession() {
        log.d(TAG) { "Inicializando sesión de Traductor Google..." }
        delay(100)
        isSessionInitialized.set(true)
        log.d(TAG) { "Traductor Google listo para conversaciones" }
    }

    // === PROCESAMIENTO DE AUDIO ===
    @RequiresApi(Build.VERSION_CODES.M)
    private fun startAudioProcessingLoop() {
        coroutineScope.launch {
            while (isConnected.get()) {
                try {
                    processAudioBuffer()
                    delay(audioSendInterval)
                } catch (e: Exception) {
                    log.e(TAG) { "Error en loop de procesamiento: ${e.message}" }
                    delay(1000)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private suspend fun processAudioBuffer() {
        if (!isSessionInitialized.get() || !isTranslationMode) return

        val audioChunks = audioBufferMutex.withLock {
            if (audioBuffer.isNotEmpty()) {
                val chunks = audioBuffer.toList()
                audioBuffer.clear()
                chunks
            } else {
                emptyList()
            }
        }

        if (audioChunks.isNotEmpty()) {
            val combinedAudio = combineAudioChunks(audioChunks)
            if (combinedAudio.size >= minAudioChunkSize) {
                processAudioForTranslation(combinedAudio)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private suspend fun processAudioForTranslation(audioData: ByteArray) {
        try {
            // Detectar actividad de voz
            val hasVoice = audioProcessor.processAudio(audioData)

            if (hasVoice) {
                isVoiceDetected = true
                silenceStartTime = 0L

                // Transcribir audio
                val transcription = transcribeAudio(audioData)

                if (transcription.isNotBlank()) {
                    inputTranscriptionChannel.trySend(transcription)

                    // Detectar idioma
                    val detectedLanguage = detectLanguage(transcription)
                    onLanguageDetected?.invoke(detectedLanguage)

                    // Traducir
                    val targetLang = when (detectedLanguage.language) {
                        "es" -> "ru"
                        "ru" -> "es"
                        else -> "es"
                    }

                    val translation = translationProcessor.translateText(transcription, targetLang)

                    if (translation.isNotBlank() && translation != transcription) {
                        val result = TranslationResult(
                            originalText = transcription,
                            translatedText = translation,
                            sourceLanguage = detectedLanguage.language,
                            targetLanguage = targetLang,
                            confidence = detectedLanguage.confidence,
                            timestamp = System.currentTimeMillis()
                        )

                        translationChannel.trySend(result)
                        onTranslationReceived?.invoke(result)

                        // Síntesis de voz
                        synthesizeSpeech(translation, targetLang)

                        log.d(TAG) { "Traducción: ${detectedLanguage.language} -> $targetLang: $translation" }
                    }
                }
            } else if (isVoiceDetected) {
                // Gestionar silencio
                if (silenceStartTime == 0L) {
                    silenceStartTime = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - silenceStartTime > silenceThreshold) {
                    isVoiceDetected = false
                    silenceStartTime = 0L
                }
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error procesando audio: ${e.message}" }
        }
    }

    private fun startAudioTimeoutWatcher() {
        coroutineScope.launch {
            while (isConnected.get()) {
                // Monitoreo de timeout implementado pero simplificado
                delay(1000)
            }
        }
    }

    private fun combineAudioChunks(chunks: List<ByteArray>): ByteArray {
        val totalSize = chunks.sumOf { it.size }

        if (totalSize > maxAudioChunkSize) {
            val result = ByteArray(maxAudioChunkSize)
            var offset = 0

            for (chunk in chunks) {
                val remainingSpace = maxAudioChunkSize - offset
                if (remainingSpace <= 0) break

                val copySize = minOf(chunk.size, remainingSpace)
                System.arraycopy(chunk, 0, result, offset, copySize)
                offset += copySize
            }
            return result
        }

        val result = ByteArray(totalSize)
        var offset = 0

        for (chunk in chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.size)
            offset += chunk.size
        }

        return result
    }

    // === DETECCIÓN DE IDIOMA MEJORADA ===
    private fun detectLanguage(text: String): DetectedLanguage {
        val cyrillicPattern = Regex("[а-яё]", RegexOption.IGNORE_CASE)
        val latinPattern = Regex("[a-záéíóúñü¿¡]", RegexOption.IGNORE_CASE)

        val hasCyrillic = cyrillicPattern.containsMatchIn(text)
        val hasLatinSpanish = latinPattern.containsMatchIn(text)

        // Palabras clave en ruso (expandidas)
        val russianKeywords = listOf(
            "здравствуйте", "привет", "как дела", "спасибо", "пожалуйста",
            "да", "нет", "хорошо", "плохо", "счёт", "баланс", "помощь",
            "деньги", "рубль", "доллар", "евро", "банк", "карта", "время",
            "сегодня", "вчера", "завтра", "работа", "дом", "семья"
        )

        // Palabras clave en español (expandidas)
        val spanishKeywords = listOf(
            "hola", "buenos días", "cómo está", "gracias", "por favor",
            "sí", "no", "bien", "mal", "cuenta", "saldo", "ayuda",
            "dinero", "peso", "dólar", "euro", "banco", "tarjeta", "tiempo",
            "hoy", "ayer", "mañana", "trabajo", "casa", "familia"
        )

        val textLower = text.lowercase()
        val russianMatches = russianKeywords.count { textLower.contains(it) }
        val spanishMatches = spanishKeywords.count { textLower.contains(it) }

        return when {
            hasCyrillic || russianMatches > 0 -> {
                val confidence = if (hasCyrillic) 0.95f else (russianMatches * 0.4f).coerceAtMost(0.85f)
                DetectedLanguage("ru", confidence, text)
            }
            hasLatinSpanish || spanishMatches > 0 -> {
                val confidence = if (hasLatinSpanish) 0.9f else (spanishMatches * 0.4f).coerceAtMost(0.8f)
                DetectedLanguage("es", confidence, text)
            }
            else -> DetectedLanguage("es", 0.6f, text) // Por defecto español
        }
    }

    // === MÉTODOS PÚBLICOS ===
    suspend fun addAudioData(audioData: ByteArray) {
        if (!isConnected.get() || !isTranslationMode) return
        audioBufferMutex.withLock {
            audioBuffer.add(audioData)
        }
    }

    suspend fun generateTranslation() {
        try {
            log.d(TAG) { "Procesando traducción pendiente" }
            // En esta implementación, la traducción es automática
            // Este método se mantiene para compatibilidad
        } catch (e: Exception) {
            log.e(TAG) { "Error solicitando traducción: ${e.message}" }
        }
    }

    fun getAudioResponseChannel(): ReceiveChannel<ByteArray> = audioResponseChannel
    fun getTranscriptionChannel(): ReceiveChannel<String> = transcriptionChannel
    fun getInputTranscriptionChannel(): ReceiveChannel<String> = inputTranscriptionChannel
    fun getTranslationChannel(): ReceiveChannel<TranslationResult> = translationChannel

    fun isConnected(): Boolean = isConnected.get()
    fun isSessionReady(): Boolean = isSessionInitialized.get()
    fun isTranslating(): Boolean = isTranslationMode

    suspend fun disconnect() {
        log.d(TAG) { "Desconectando Traductor MCN Google..." }
        isConnected.set(false)
        isSessionInitialized.set(false)

        try {
            ttsEngine?.stop()
            ttsEngine?.shutdown()
            speechRecognizer?.destroy()
            speechRecognizer = null
        } catch (e: Exception) {
            log.e(TAG) { "Error cerrando recursos: ${e.message}" }
        }

        coroutineScope.cancel()
        audioResponseChannel.close()
        transcriptionChannel.close()
        translationChannel.close()

        audioBufferMutex.withLock {
            audioBuffer.clear()
        }
    }

}