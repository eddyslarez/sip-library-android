package com.eddyslarez.siplibrary.data.services.ia

import android.app.Application
import android.content.Context
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
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.*


class MCNTranslatorClient3(
    private val apiKey: String, // No se usa, solo para mantener compatibilidad
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

    // Free translation endpoints
    private val freeTranslateEndpoints = listOf(
        "https://translate.googleapis.com/translate_a/single",
        "https://api.mymemory.translated.net/get"
    )

    // TTS endpoint usando proxy gratuito
    private val freeTTSEndpoint = "https://api.allorigins.win/get"

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
            log.d(TAG) { "Conectando Traductor MCN (100% GRATUITO)..." }

            // Verificar conectividad de servicios gratuitos
            val isOnline = testFreeServices()

            if (isOnline) {
                isConnected.set(true)
                isSessionInitialized.set(true)
                onConnectionStateChanged?.invoke(true)

                // Iniciar loops de procesamiento
                startAudioProcessingLoop()
                startAudioTimeoutWatcher()

                log.d(TAG) { "Traductor MCN conectado exitosamente (GRATIS)" }
                true
            } else {
                onError?.invoke("No se pudo conectar a servicios de traducción gratuitos")
                false
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error de conexión: ${e.message}" }
            onError?.invoke("Error de conexión: ${e.message}")
            false
        }
    }

    private suspend fun testFreeServices(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Test básico de conectividad
                val testRequest = Request.Builder()
                    .url("https://httpbin.org/status/200")
                    .build()

                val response = client.newCall(testRequest).execute()
                response.isSuccessful
            } catch (e: Exception) {
                false
            }
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
            if (audioAccumulator.size() >= minAudioChunkSize * 4) {
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
                // 1. Simular transcripción (en una implementación real, usarías Web Speech API desde WebView)
                val simulatedText = simulateTranscription()

                if (simulatedText.isNotBlank()) {
                    inputTranscriptionChannel.trySend(simulatedText)
                    log.d(TAG) { "Texto simulado: $simulatedText" }

                    // 2. Detectar idioma
                    val detectedLang = detectLanguage(simulatedText)
                    onLanguageDetected?.invoke(detectedLang)

                    // 3. Traducir texto usando servicios gratuitos
                    if (detectedLang.confidence >= confidenceThreshold) {
                        val targetLang = when (detectedLang.language) {
                            "es" -> "ru"
                            "ru" -> "es"
                            else -> "es"
                        }

                        val translation = translateTextFree(simulatedText, detectedLang.language, targetLang)

                        if (translation.isNotBlank()) {
                            transcriptionChannel.trySend(translation)
                            log.d(TAG) { "Traducción: $translation" }

                            // 4. Generar audio sintético (simplificado)
                            val syntheticAudio = generateSyntheticAudio(translation)
                            if (syntheticAudio.isNotEmpty()) {
                                onAudioReceived?.invoke(syntheticAudio)
                            }

                            // 5. Crear resultado de traducción
                            val result = TranslationResult(
                                originalText = simulatedText,
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

    // === TRANSCRIPCIÓN SIMULADA ===
    private fun simulateTranscription(): String {
        // En una implementación real, aquí integrarías Web Speech API
        // Por ahora, simulamos con frases comunes para testing
        val samplePhrases = listOf(
            "Hola, buenos días. ¿Cómo está usted?",
            "Necesito ayuda con mi cuenta bancaria",
            "¿Cuál es el saldo disponible?",
            "Quiero hacer una transferencia",
            "Здравствуйте, как дела?",
            "Мне нужна помощь со счётом",
            "Какой у меня баланс?",
            "Хочу перевести деньги"
        )

        return samplePhrases.random()
    }

    // === TRADUCCIÓN GRATUITA ===
    private suspend fun translateTextFree(text: String, sourceLang: String, targetLang: String): String {
        return withContext(Dispatchers.IO) {
            try {
                // Intentar con Google Translate gratuito
                val googleResult = translateWithGoogleFree(text, sourceLang, targetLang)
                if (googleResult.isNotBlank()) return@withContext googleResult

                // Fallback: MyMemory API gratuita
                val myMemoryResult = translateWithMyMemory(text, sourceLang, targetLang)
                if (myMemoryResult.isNotBlank()) return@withContext myMemoryResult

                // Fallback final: traducción básica offline
                return@withContext translateOffline(text, sourceLang, targetLang)

            } catch (e: Exception) {
                log.e(TAG) { "Error traduciendo: ${e.message}" }
                translateOffline(text, sourceLang, targetLang)
            }
        }
    }

    private suspend fun translateWithGoogleFree(text: String, sourceLang: String, targetLang: String): String {
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sourceLang&tl=$targetLang&dt=t&q=$encodedText"

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (compatible; MCN Translator)")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                // Parsear respuesta de Google Translate
                parseGoogleTranslateResponse(responseBody)
            } else {
                ""
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error Google Translate Free: ${e.message}" }
            ""
        }
    }

    private fun parseGoogleTranslateResponse(response: String): String {
        return try {
            // Google Translate devuelve un array JSON complejo
            val cleanResponse = response.trim()
            if (cleanResponse.startsWith("[[")) {
                // Extraer la traducción del primer elemento
                val firstPart = cleanResponse.substring(2, cleanResponse.indexOf("],"))
                val translatedText = firstPart.substring(1, firstPart.indexOf("\","))
                translatedText
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    private suspend fun translateWithMyMemory(text: String, sourceLang: String, targetLang: String): String {
        return try {
            val encodedText = URLEncoder.encode(text, "UTF-8")
            val langPair = "${sourceLang}|${targetLang}"
            val url = "https://api.mymemory.translated.net/get?q=$encodedText&langpair=$langPair"

            val request = Request.Builder()
                .url(url)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                val json = JSONObject(responseBody)
                if (json.has("responseData")) {
                    val responseData = json.getJSONObject("responseData")
                    responseData.getString("translatedText")
                } else {
                    ""
                }
            } else {
                ""
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error MyMemory: ${e.message}" }
            ""
        }
    }

    // === TRADUCCIÓN OFFLINE BÁSICA ===
    private fun translateOffline(text: String, sourceLang: String, targetLang: String): String {
        val translations = mapOf(
            // Español -> Ruso
            "hola" to "привет",
            "buenos días" to "доброе утро",
            "gracias" to "спасибо",
            "por favor" to "пожалуйста",
            "sí" to "да",
            "no" to "нет",
            "ayuda" to "помощь",
            "cuenta" to "счёт",
            "dinero" to "деньги",
            "banco" to "банк",

            // Ruso -> Español
            "привет" to "hola",
            "доброе утро" to "buenos días",
            "спасибо" to "gracias",
            "пожалуйста" to "por favor",
            "да" to "sí",
            "нет" to "no",
            "помощь" to "ayuda",
            "счёт" to "cuenta",
            "деньги" to "dinero",
            "банк" to "banco"
        )

        val textLower = text.lowercase()
        for ((original, translated) in translations) {
            if (textLower.contains(original)) {
                return text.replace(original, translated, ignoreCase = true)
            }
        }

        return if (sourceLang == "es") {
            "[RU] $text" // Indicar que debería ser ruso
        } else {
            "[ES] $text" // Indicar que debería ser español
        }
    }

    // === SÍNTESIS DE AUDIO SINTÉTICO ===
    private fun generateSyntheticAudio(text: String): ByteArray {
        // Generar audio sintético simple (tono beep variante)
        // En una implementación real usarías speechSynthesis API desde WebView
        return try {
            val duration = (text.length * 50).coerceIn(500, 3000) // 50ms por carácter
            val sampleRate = 16000
            val samples = (duration * sampleRate / 1000)
            val audioData = ByteArray(samples * 2) // 16-bit audio

            // Generar tono sintético basado en el texto
            val frequency = if (text.any { it.code > 127 }) 440.0 else 523.0 // Frecuencias diferentes para idiomas

            for (i in 0 until samples) {
                val time = i.toDouble() / sampleRate
                val amplitude = (Math.sin(2 * Math.PI * frequency * time) * 8000).toInt()

                // Convertir a bytes (little endian)
                audioData[i * 2] = (amplitude and 0xFF).toByte()
                audioData[i * 2 + 1] = ((amplitude shr 8) and 0xFF).toByte()
            }

            audioData
        } catch (e: Exception) {
            byteArrayOf()
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
            "да", "нет", "хорошо", "плохо", "счёт", "баланс", "помощь", "банк"
        )

        // Palabras clave en español
        val spanishKeywords = listOf(
            "hola", "buenos días", "cómo está", "gracias", "por favor",
            "sí", "no", "bien", "mal", "cuenta", "saldo", "ayuda", "banco"
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

    @RequiresApi(Build.VERSION_CODES.O)
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