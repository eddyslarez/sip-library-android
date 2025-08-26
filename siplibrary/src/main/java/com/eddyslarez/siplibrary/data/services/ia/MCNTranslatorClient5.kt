package com.eddyslarez.siplibrary.data.services.ia

import android.app.Application
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.annotation.RequiresApi
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Locale

class MCNTranslatorClient5(
    private val apiKey: String, // Mantenemos para compatibilidad, pero no lo usamos
    private val application: Application
) {

    // HTTP client para APIs de traducción
    private val client = OkHttpClient.Builder()
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()
    private var context: Context = application.applicationContext

    // Estados de conexión
    private val isConnected = AtomicBoolean(false)
    private val isSessionInitialized = AtomicBoolean(false)
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Audio recording
    private var audioRecord: AudioRecord? = null
    private val audioBuffer = mutableListOf<ByteArray>()
    private val audioBufferMutex = Mutex()
    private val minAudioChunkSize = 3200
    private val maxAudioChunkSize = 15 * 1024 * 1024
    private val audioSendInterval = 80L

    // Speech Recognition
    private var speechRecognizer: SpeechRecognizer? = null
    private var isRecording = false
    private var lastAudioReceiveTime = 0L
    private val audioTimeoutMs = 2000L

    // Text to Speech
    private var textToSpeech: TextToSpeech? = null
    private var ttsInitialized = false

    // Response channels (mantiene compatibilidad)
    private val audioResponseChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private val transcriptionChannel = Channel<String>(Channel.UNLIMITED)
    private val inputTranscriptionChannel = Channel<String>(Channel.UNLIMITED)
    private val translationChannel = Channel<TranslationResult>(Channel.UNLIMITED)

    // Callbacks (mismos que el original)
    private var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var onAudioReceived: ((ByteArray) -> Unit)? = null
    private var onTranslationReceived: ((TranslationResult) -> Unit)? = null
    private var onLanguageDetected: ((DetectedLanguage) -> Unit)? = null

    // Configuración del traductor
    private var currentSourceLanguage: String = "auto"
    private var isTranslationMode = true
    private var confidenceThreshold = 0.7f

    // Buffer para audio
    private val playbackBuffer = ByteArrayOutputStream()
    private val playbackMutex = Mutex()

    // APIs gratuitas para traducción
    private val LIBRE_TRANSLATE_URL = "https://libretranslate.de/translate"
    private val MYMEMORY_URL = "https://api.mymemory.translated.net/get"

    companion object {
        private const val TAG = "MCNTranslatorClient"
    }

    // Data classes (iguales al original)
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

    // === MÉTODOS DE CONFIGURACIÓN (iguales al original) ===
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
        android.util.Log.d(TAG, "Modo traducción: ${if (enabled) "ACTIVADO" else "DESACTIVADO"}")
    }

    fun setSourceLanguage(language: String) {
        currentSourceLanguage = language
        android.util.Log.d(TAG, "Idioma fuente establecido: $language")
    }

    fun setConfidenceThreshold(threshold: Float) {
        confidenceThreshold = threshold.coerceIn(0.1f, 1.0f)
        android.util.Log.d(TAG, "Umbral de confianza: $confidenceThreshold")
    }

    // === CONEXIÓN (usando servicios locales) ===
    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun connect(): Boolean {
        return try {
            android.util.Log.d(TAG, "Conectando Traductor MCN Local...")

            // Inicializar Text-to-Speech
            initializeTextToSpeech()

            // Inicializar reconocimiento de voz
            initializeSpeechRecognizer()

            // Esperar a que TTS se inicialice
            var attempts = 0
            while (!ttsInitialized && attempts < 50) {
                delay(100)
                attempts++
            }

            if (ttsInitialized) {
                isConnected.set(true)
                isSessionInitialized.set(true)
                onConnectionStateChanged?.invoke(true)

                startAudioStreamingLoop()
                startAudioTimeoutWatcher()
                android.util.Log.d(TAG, "Traductor MCN Local conectado exitosamente")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error de conexión: ${e.message}")
            onError?.invoke("Error de conexión: ${e.message}")
            false
        }
    }

    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsInitialized = true
                android.util.Log.d(TAG, "TextToSpeech inicializado")

                // Configurar idiomas disponibles
                textToSpeech?.language = Locale.getDefault()

                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        android.util.Log.d(TAG, "Iniciando síntesis de voz")
                    }

                    override fun onDone(utteranceId: String?) {
                        android.util.Log.d(TAG, "Síntesis de voz completada")
                    }

                    override fun onError(utteranceId: String?) {
                        android.util.Log.e(TAG, "Error en síntesis de voz")
                    }
                })
            } else {
                android.util.Log.e(TAG, "Error inicializando TextToSpeech")
                onError?.invoke("Error inicializando síntesis de voz")
            }
        }
    }

    private fun initializeSpeechRecognizer() {
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(createRecognitionListener())
            android.util.Log.d(TAG, "SpeechRecognizer inicializado")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error inicializando SpeechRecognizer: ${e.message}")
            onError?.invoke("Error inicializando reconocimiento de voz: ${e.message}")
        }
    }

    private fun createRecognitionListener(): RecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            android.util.Log.d(TAG, "Listo para capturar voz")
        }

        override fun onBeginningOfSpeech() {
            android.util.Log.d(TAG, "Detectando idioma y preparando traducción...")
            lastAudioReceiveTime = System.currentTimeMillis()
        }

        override fun onRmsChanged(rmsdB: Float) {
            // Monitorear nivel de audio
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            buffer?.let { audioData ->
                coroutineScope.launch {
                    addAudioData(audioData)
                }
            }
        }

        override fun onEndOfSpeech() {
            android.util.Log.d(TAG, "Procesando traducción...")
        }

        override fun onError(error: Int) {
            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Error de red - timeout"
                SpeechRecognizer.ERROR_NETWORK -> "Error de red"
                SpeechRecognizer.ERROR_AUDIO -> "Error de audio"
                SpeechRecognizer.ERROR_SERVER -> "Error del servidor"
                SpeechRecognizer.ERROR_CLIENT -> "Error del cliente"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Timeout de voz"
                SpeechRecognizer.ERROR_NO_MATCH -> "No se encontraron coincidencias"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconocedor ocupado"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permisos insuficientes"
                else -> "Error desconocido: $error"
            }
            android.util.Log.e(TAG, "Error en reconocimiento: $errorMessage")

            // Reintentar reconocimiento automáticamente
            if (isConnected.get() && isTranslationMode) {
                coroutineScope.launch {
                    delay(1000)
                    startListening()
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val confidenceScores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)

            if (!matches.isNullOrEmpty()) {
                val transcript = matches[0]
                val confidence = confidenceScores?.get(0) ?: 0.8f

                android.util.Log.d(TAG, "Texto original: $transcript")

                // Enviar transcripción al canal
                inputTranscriptionChannel.trySend(transcript)

                if (isTranslationMode && transcript.isNotBlank()) {
                    coroutineScope.launch {
                        processTranscriptionForTranslation(transcript, confidence)
                    }
                }
            }

            // Continuar escuchando si está en modo traducción
            if (isConnected.get() && isTranslationMode) {
                coroutineScope.launch {
                    delay(500)
                    startListening()
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                android.util.Log.d(TAG, "Transcripción parcial: ${matches[0]}")
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            // Eventos adicionales
        }
    }

    private fun startListening() {
        try {
            if (!isRecording) {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES") // Español por defecto
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                }

                speechRecognizer?.startListening(intent)
                isRecording = true
                android.util.Log.d(TAG, "Iniciando escucha...")
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error iniciando escucha: ${e.message}")
            isRecording = false
        }
    }

    private fun stopListening() {
        try {
            speechRecognizer?.stopListening()
            isRecording = false
            android.util.Log.d(TAG, "Deteniendo escucha...")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error deteniendo escucha: ${e.message}")
        }
    }

    // === PROCESAMIENTO DE TRADUCCIÓN ===
    private suspend fun processTranscriptionForTranslation(transcript: String, confidence: Float) {
        if (transcript.isBlank()) return

        try {
            // Detectar idioma
            val detectedLanguage = detectLanguage(transcript)
            onLanguageDetected?.invoke(detectedLanguage)

            // Determinar idioma objetivo
            val targetLanguage = when (detectedLanguage.language) {
                "es" -> "ru"
                "ru" -> "es"
                else -> "es"
            }

            android.util.Log.d(TAG, "Idioma detectado: ${detectedLanguage.language} → Traduciendo a: $targetLanguage")

            // Realizar traducción
            val translatedText = translateText(transcript, detectedLanguage.language, targetLanguage)

            if (translatedText.isNotEmpty()) {
                // Crear resultado de traducción
                val translationResult = TranslationResult(
                    originalText = transcript,
                    translatedText = translatedText,
                    sourceLanguage = detectedLanguage.language,
                    targetLanguage = targetLanguage,
                    confidence = confidence,
                    timestamp = System.currentTimeMillis()
                )

                // Enviar traducción a los canales
                transcriptionChannel.trySend(translatedText)
                translationChannel.trySend(translationResult)
                onTranslationReceived?.invoke(translationResult)

                android.util.Log.d(TAG, "Traducción: $translatedText")

                // Generar audio de la traducción
                generateAudioFromText(translatedText, targetLanguage)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error procesando traducción: ${e.message}")
            onError?.invoke("Error en traducción: ${e.message}")
        }
    }

    // === DETECCIÓN DE IDIOMA (igual al original) ===
    private fun detectLanguage(text: String): DetectedLanguage {
        val cyrillicPattern = Regex("[а-яё]", RegexOption.IGNORE_CASE)
        val latinPattern = Regex("[a-záéíóúñü]", RegexOption.IGNORE_CASE)

        val hasCyrillic = cyrillicPattern.containsMatchIn(text)
        val hasLatinSpanish = latinPattern.containsMatchIn(text)

        val russianKeywords = listOf(
            "здравствуйте", "привет", "как дела", "спасибо", "пожалуйста",
            "да", "нет", "хорошо", "плохо", "счёт", "баланс"
        )

        val spanishKeywords = listOf(
            "hola", "buenos días", "cómo está", "gracias", "por favor",
            "sí", "no", "bien", "mal", "cuenta", "saldo"
        )

        val textLower = text.toLowerCase()
        val russianMatches = russianKeywords.count { textLower.contains(it) }
        val spanishMatches = spanishKeywords.count { textLower.contains(it) }

        return when {
            hasCyrillic || russianMatches > 0 -> {
                val confidence = if (hasCyrillic) 0.9f else (russianMatches * 0.3f).coerceAtMost(0.8f)
                DetectedLanguage("ru", confidence, text)
            }
            hasLatinSpanish || spanishMatches > 0 -> {
                val confidence = if (hasLatinSpanish) 0.8f else (spanishMatches * 0.3f).coerceAtMost(0.7f)
                DetectedLanguage("es", confidence, text)
            }
            else -> DetectedLanguage("es", 0.5f, text)
        }
    }

    // === TRADUCCIÓN USANDO APIs GRATUITAS ===
    private suspend fun translateText(text: String, fromLang: String, toLang: String): String {
        return try {
            // Intentar con LibreTranslate primero
            translateWithLibreTranslate(text, fromLang, toLang)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "LibreTranslate falló, intentando MyMemory: ${e.message}")
            try {
                // Fallback a MyMemory
                translateWithMyMemory(text, fromLang, toLang)
            } catch (e2: Exception) {
                android.util.Log.e(TAG, "Error en todas las APIs de traducción: ${e2.message}")
                "Error en traducción"
            }
        }
    }

    private suspend fun translateWithLibreTranslate(text: String, fromLang: String, toLang: String): String {
        return withContext(Dispatchers.IO) {
            val json = JSONObject().apply {
                put("q", text)
                put("source", fromLang)
                put("target", toLang)
                put("format", "text")
            }

            val requestBody = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(LIBRE_TRANSLATE_URL)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: ""
                val jsonResponse = JSONObject(responseBody)
                jsonResponse.getString("translatedText")
            } else {
                throw Exception("LibreTranslate error: ${response.code}")
            }
        }
    }

    private suspend fun translateWithMyMemory(text: String, fromLang: String, toLang: String): String {
        return withContext(Dispatchers.IO) {
            val url = "$MYMEMORY_URL?q=${java.net.URLEncoder.encode(text, "UTF-8")}&langpair=$fromLang|$toLang"

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: ""
                val jsonResponse = JSONObject(responseBody)
                val responseData = jsonResponse.getJSONObject("responseData")
                responseData.getString("translatedText")
            } else {
                throw Exception("MyMemory error: ${response.code}")
            }
        }
    }

    // === GENERACIÓN DE AUDIO ===
    private fun generateAudioFromText(text: String, language: String) {
        try {
            val locale = when (language) {
                "es" -> Locale("es", "ES")
                "ru" -> Locale("ru", "RU")
                else -> Locale.getDefault()
            }

            textToSpeech?.language = locale

            val utteranceId = "translate_${System.currentTimeMillis()}"
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)

            // Simular audio data para mantener compatibilidad con la interfaz original
            coroutineScope.launch {
                delay(100) // Pequeño delay para simular procesamiento
                val dummyAudioData = ByteArray(1024) // Audio dummy para compatibilidad
                onAudioReceived?.invoke(dummyAudioData)
            }

        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error generando audio: ${e.message}")
        }
    }

    // === MÉTODOS DE AUDIO (compatibilidad con interfaz original) ===
    private fun startAudioTimeoutWatcher() {
        coroutineScope.launch {
            while (isConnected.get()) {
                val currentTime = System.currentTimeMillis()
                if (lastAudioReceiveTime > 0 &&
                    currentTime - lastAudioReceiveTime > audioTimeoutMs) {

                    playbackMutex.withLock {
                        if (playbackBuffer.size() > 0) {
                            android.util.Log.d(TAG, "Procesando audio restante por timeout")
                            val remainingData = playbackBuffer.toByteArray()
                            if (remainingData.size % 2 == 0) {
                                onAudioReceived?.invoke(remainingData)
                            }
                            playbackBuffer.reset()
                        }
                    }
                    lastAudioReceiveTime = 0L
                }
                delay(500)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startAudioStreamingLoop() {
        // Iniciar reconocimiento continuo
        coroutineScope.launch {
            delay(1000) // Esperar a que todo se inicialice
            if (isConnected.get() && isTranslationMode) {
                startListening()
            }
        }

        // Loop de monitoreo
        coroutineScope.launch {
            while (isConnected.get()) {
                try {
                    if (isTranslationMode && !isRecording) {
                        startListening()
                    }
                    delay(audioSendInterval)
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Error en loop de audio: ${e.message}")
                    delay(1000)
                }
            }
        }
    }

    // === MÉTODOS PÚBLICOS (iguales al original) ===
    suspend fun addAudioData(audioData: ByteArray) {
        if (!isConnected.get() || !isTranslationMode) return
        audioBufferMutex.withLock {
            audioBuffer.add(audioData)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun sendAudioToOpenAI(audioData: ByteArray) {
        // Para compatibilidad - el procesamiento real se hace en el reconocimiento de voz
        android.util.Log.d(TAG, "Procesando audio para traducción (${audioData.size} bytes)")
    }

    suspend fun generateTranslation() {
        try {
            android.util.Log.d(TAG, "Solicitando nueva traducción")
            // Reiniciar escucha para nueva traducción
            if (!isRecording) {
                startListening()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error solicitando traducción: ${e.message}")
        }
    }

    // Getters (iguales al original)
    fun getAudioResponseChannel(): ReceiveChannel<ByteArray> = audioResponseChannel
    fun getTranscriptionChannel(): ReceiveChannel<String> = transcriptionChannel
    fun getInputTranscriptionChannel(): ReceiveChannel<String> = inputTranscriptionChannel
    fun getTranslationChannel(): ReceiveChannel<TranslationResult> = translationChannel

    fun isConnected(): Boolean = isConnected.get()
    fun isSessionReady(): Boolean = isSessionInitialized.get()
    fun isTranslating(): Boolean = isTranslationMode

    // === DESCONEXIÓN ===
    suspend fun disconnect() {
        android.util.Log.d(TAG, "Desconectando Traductor MCN Local...")
        isConnected.set(false)

        try {
            stopListening()
            speechRecognizer?.destroy()
            textToSpeech?.stop()
            textToSpeech?.shutdown()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Error en desconexión: ${e.message}")
        }

        coroutineScope.cancel()
        audioResponseChannel.close()
        transcriptionChannel.close()
        translationChannel.close()
        inputTranscriptionChannel.close()

        audioBufferMutex.withLock {
            audioBuffer.clear()
        }

        playbackMutex.withLock {
            playbackBuffer.reset()
        }

        android.util.Log.d(TAG, "Traductor MCN Local desconectado")
    }
}
