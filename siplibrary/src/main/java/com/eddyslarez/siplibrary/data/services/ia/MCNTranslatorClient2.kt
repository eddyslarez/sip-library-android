package com.eddyslarez.siplibrary.data.services.ia

import android.os.Build
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

class MCNTranslatorClient2(
    private val apiKey: String, // Google Cloud API Key
    private val model: String = "google-cloud-translation"
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
    private val maxAudioChunkSize = 15 * 1024 * 1024 // 15MB limit
    private val audioSendInterval = 80L

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
    private var currentSourceLanguage: String = "auto" // "es", "ru", "auto"
    private var isTranslationMode = true
    private var confidenceThreshold = 0.7f

    // Audio timeout handling
    private var lastAudioReceiveTime = 0L
    private val audioTimeoutMs = 2000L

    // Audio processing
    private val audioAccumulator = ByteArrayOutputStream()
    private val audioMutex = Mutex()
    private var isProcessingAudio = AtomicBoolean(false)

    // Google Cloud endpoints
    private val speechEndpoint = "https://speech.googleapis.com/v1/speech:recognize"
    private val translateEndpoint = "https://translation.googleapis.com/language/translate/v2"
    private val ttsEndpoint = "https://texttospeech.googleapis.com/v1/text:synthesize"

    companion object {
        private const val TAG = "MCNTranslatorClient"
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

    // === MÉTODOS DE CONFIGURACIÓN DEL TRADUCTOR ===
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
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun connect(): Boolean {
        return try {
            log.d(TAG) { "Conectando Traductor MCN con Google Cloud..." }

            // Simular conexión (ya que Google Cloud usa REST APIs, no WebSocket)
            isConnected.set(true)
            isSessionInitialized.set(true)
            onConnectionStateChanged?.invoke(true)

            // Iniciar loops de procesamiento
            startAudioProcessingLoop()
            startAudioTimeoutWatcher()

            log.d(TAG) { "Traductor MCN conectado exitosamente" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error de conexión: ${e.message}" }
            onError?.invoke("Error de conexión: ${e.message}")
            false
        }
    }

    // === PROCESAMIENTO DE AUDIO ===
    @RequiresApi(Build.VERSION_CODES.O)
    private fun startAudioTimeoutWatcher() {
        coroutineScope.launch {
            while (isConnected.get()) {
                val currentTime = System.currentTimeMillis()
                if (lastAudioReceiveTime > 0 &&
                    currentTime - lastAudioReceiveTime > audioTimeoutMs) {

                    // Procesar audio acumulado si hay
                    processAccumulatedAudio()
                    lastAudioReceiveTime = 0L
                }
                delay(500)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startAudioProcessingLoop() {
        coroutineScope.launch {
            while (isConnected.get()) {
                try {
                    processAndSendAudioBuffer()
                    delay(audioSendInterval)
                } catch (e: Exception) {
                    log.e(TAG) { "Error en loop de audio traducción: ${e.message}" }
                    delay(1000)
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun processAndSendAudioBuffer() {
        if (!isSessionInitialized.get()) return

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

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun processAudioForTranslation(audioData: ByteArray) {
        if (!isTranslationMode || isProcessingAudio.get()) return

        isProcessingAudio.set(true)
        lastAudioReceiveTime = System.currentTimeMillis()

        try {
            // Acumular audio para procesamiento posterior
            audioMutex.withLock {
                audioAccumulator.write(audioData)
            }

            // Procesar cada cierto tamaño o tiempo
            if (audioAccumulator.size() >= minAudioChunkSize * 3) {
                processAccumulatedAudio()
            }

        } catch (e: Exception) {
            log.e(TAG) { "Error procesando audio: ${e.message}" }
        } finally {
            isProcessingAudio.set(false)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun processAccumulatedAudio() {
        val audioData = audioMutex.withLock {
            if (audioAccumulator.size() > 0) {
                val data = audioAccumulator.toByteArray()
                audioAccumulator.reset()
                data
            } else null
        }

        audioData?.let { audio ->
            try {
                // 1. Transcribir audio usando Google Speech-to-Text
                val transcription = transcribeAudio(audio)

                if (transcription.isNotBlank()) {
                    inputTranscriptionChannel.trySend(transcription)
                    log.d(TAG) { "Texto original: $transcription" }

                    // 2. Detectar idioma
                    val detectedLang = detectLanguage(transcription)
                    onLanguageDetected?.invoke(detectedLang)

                    // 3. Traducir texto
                    if (detectedLang.confidence >= confidenceThreshold) {
                        val targetLang = when (detectedLang.language) {
                            "es" -> "ru"
                            "ru" -> "es"
                            else -> "es"
                        }

                        val translation = translateText(transcription, detectedLang.language, targetLang)

                        if (translation.isNotBlank()) {
                            transcriptionChannel.trySend(translation)
                            log.d(TAG) { "Traducción: $translation" }

                            // 4. Generar audio de la traducción
                            val translatedAudio = synthesizeSpeech(translation, targetLang)
                            if (translatedAudio.isNotEmpty()) {
                                onAudioReceived?.invoke(translatedAudio)
                            }

                            // 5. Crear resultado de traducción
                            val result = TranslationResult(
                                originalText = transcription,
                                translatedText = translation,
                                sourceLanguage = detectedLang.language,
                                targetLanguage = targetLang,
                                confidence = detectedLang.confidence,
                                timestamp = System.currentTimeMillis()
                            )

                            onTranslationReceived?.invoke(result)
                            translationChannel.trySend(result)
                        }
                    }
                }
            } catch (e: Exception) {
                log.e(TAG) { "Error en pipeline de traducción: ${e.message}" }
                onError?.invoke("Error de traducción: ${e.message}")
            }
        }
    }

    // === GOOGLE CLOUD SPEECH-TO-TEXT ===
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun transcribeAudio(audioData: ByteArray): String {
        return withContext(Dispatchers.IO) {
            try {
                val base64Audio = Base64.getEncoder().encodeToString(audioData)

                val requestBody = JSONObject().apply {
                    put("config", JSONObject().apply {
                        put("encoding", "LINEAR16")
                        put("sampleRateHertz", 16000)
                        put("languageCode", if (currentSourceLanguage == "auto") "es-ES" else "$currentSourceLanguage-${currentSourceLanguage.uppercase()}")
                        put("alternativeLanguageCodes", JSONArray().apply {
                            put("ru-RU")
                            put("es-ES")
                        })
                        put("enableAutomaticPunctuation", true)
                    })
                    put("audio", JSONObject().apply {
                        put("content", base64Audio)
                    })
                }

                val request = Request.Builder()
                    .url("$speechEndpoint?key=$apiKey")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    if (json.has("results")) {
                        val results = json.getJSONArray("results")
                        if (results.length() > 0) {
                            val alternatives = results.getJSONObject(0).getJSONArray("alternatives")
                            if (alternatives.length() > 0) {
                                return@withContext alternatives.getJSONObject(0).getString("transcript")
                            }
                        }
                    }
                } else {
                    log.e(TAG) { "Error transcripción: ${response.code} - $responseBody" }
                }

                ""
            } catch (e: Exception) {
                log.e(TAG) { "Error transcribiendo audio: ${e.message}" }
                ""
            }
        }
    }

    // === GOOGLE TRANSLATE ===
    private suspend fun translateText(text: String, sourceLang: String, targetLang: String): String {
        return withContext(Dispatchers.IO) {
            try {
                val requestBody = JSONObject().apply {
                    put("q", text)
                    put("source", sourceLang)
                    put("target", targetLang)
                    put("format", "text")
                }

                val request = Request.Builder()
                    .url("$translateEndpoint?key=$apiKey")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    if (json.has("data")) {
                        val translations = json.getJSONObject("data").getJSONArray("translations")
                        if (translations.length() > 0) {
                            return@withContext translations.getJSONObject(0).getString("translatedText")
                        }
                    }
                } else {
                    log.e(TAG) { "Error traducción: ${response.code} - $responseBody" }
                }

                ""
            } catch (e: Exception) {
                log.e(TAG) { "Error traduciendo texto: ${e.message}" }
                ""
            }
        }
    }

    // === GOOGLE TEXT-TO-SPEECH ===
    @RequiresApi(Build.VERSION_CODES.O)
    private suspend fun synthesizeSpeech(text: String, language: String): ByteArray {
        return withContext(Dispatchers.IO) {
            try {
                val voiceName = when (language) {
                    "es" -> "es-ES-Standard-A"
                    "ru" -> "ru-RU-Standard-A"
                    else -> "es-ES-Standard-A"
                }

                val requestBody = JSONObject().apply {
                    put("input", JSONObject().apply {
                        put("text", text)
                    })
                    put("voice", JSONObject().apply {
                        put("languageCode", if (language == "es") "es-ES" else "ru-RU")
                        put("name", voiceName)
                        put("ssmlGender", "FEMALE")
                    })
                    put("audioConfig", JSONObject().apply {
                        put("audioEncoding", "LINEAR16")
                        put("sampleRateHertz", 16000)
                    })
                }

                val request = Request.Builder()
                    .url("$ttsEndpoint?key=$apiKey")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val json = JSONObject(responseBody)
                    if (json.has("audioContent")) {
                        val audioContent = json.getString("audioContent")
                        return@withContext Base64.getDecoder().decode(audioContent)
                    }
                } else {
                    log.e(TAG) { "Error síntesis: ${response.code} - $responseBody" }
                }

                byteArrayOf()
            } catch (e: Exception) {
                log.e(TAG) { "Error sintetizando voz: ${e.message}" }
                byteArrayOf()
            }
        }
    }

    // === DETECCIÓN DE IDIOMA ===
    private fun detectLanguage(text: String): DetectedLanguage {
        // Detectar caracteres cirílicos
        val cyrillicPattern = Regex("[а-яё]", RegexOption.IGNORE_CASE)
        val latinPattern = Regex("[a-záéíóúñü]", RegexOption.IGNORE_CASE)

        val hasCyrillic = cyrillicPattern.containsMatchIn(text)
        val hasLatinSpanish = latinPattern.containsMatchIn(text)

        // Palabras clave en ruso
        val russianKeywords = listOf(
            "здравствуйте", "привет", "как дела", "спасибо", "пожалуйста",
            "да", "нет", "хорошо", "плохо", "счёт", "баланс", "помощь"
        )

        // Palabras clave en español
        val spanishKeywords = listOf(
            "hola", "buenos días", "cómo está", "gracias", "por favor",
            "sí", "no", "bien", "mal", "cuenta", "saldo", "ayuda"
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
                val confidence = if (hasLatinSpanish) 0.90f else (spanishMatches * 0.4f).coerceAtMost(0.80f)
                DetectedLanguage("es", confidence, text)
            }
            else -> DetectedLanguage("es", 0.6f, text) // Por defecto español
        }
    }

    // === UTILIDADES ===
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

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun sendAudioToOpenAI(audioData: ByteArray) {
        // Compatibilidad con la interfaz original
        addAudioData(audioData)
    }

    suspend fun generateTranslation() {
        // Forzar procesamiento del audio acumulado
        if (isConnected.get() && isTranslationMode) {
            processAccumulatedAudio()
            log.d(TAG) { "Solicitando traducción" }
        }
    }

    // === MÉTODOS PÚBLICOS ===
    suspend fun addAudioData(audioData: ByteArray) {
        if (!isConnected.get() || !isTranslationMode) return
        audioBufferMutex.withLock {
            audioBuffer.add(audioData)
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
        log.d(TAG) { "Desconectando Traductor MCN..." }
        isConnected.set(false)
        isSessionInitialized.set(false)

        coroutineScope.cancel()
        audioResponseChannel.close()
        transcriptionChannel.close()
        translationChannel.close()

        audioBufferMutex.withLock {
            audioBuffer.clear()
        }

        audioMutex.withLock {
            audioAccumulator.reset()
        }

        onConnectionStateChanged?.invoke(false)
        log.d(TAG) { "Traductor MCN desconectado" }
    }

    // Mock object para logging (añade tu sistema de logging aquí)
    private val log = object {
        fun d(tag: String, message: () -> String) {
            println("DEBUG $tag: ${message()}")
        }
        fun e(tag: String, message: () -> String) {
            println("ERROR $tag: ${message()}")
        }
    }
}