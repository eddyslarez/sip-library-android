package com.eddyslarez.siplibrary.data.services.audio

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
//import com.google.cloud.speech.v1.*
//import com.google.protobuf.ByteString
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Gestor completo de Speech-to-Text con múltiples proveedores
 */
class SpeechToTextManager(
    private val context: Context,
    private val openAiApiKey: String? = null,
    private val googleApiKey: String? = null
) {
    companion object {
        private const val TAG = "SpeechToTextManager"
    }

//    private var currentProvider = STTProvider.ANDROID_NATIVE
//    private var isListening = false
//
//    // Providers
//    private var androidSpeechRecognizer: SpeechRecognizer? = null
//    private var googleCloudClient: SpeechClient? = null
//    private val httpClient = OkHttpClient.Builder()
//        .connectTimeout(30, TimeUnit.SECONDS)
//        .readTimeout(30, TimeUnit.SECONDS)
//        .build()
//
//    // Callbacks
//    private val resultChannels = mutableListOf<Channel<STTResult>>()
//    private var onResultCallback: ((STTResult) -> Unit)? = null
//    private var onErrorCallback: ((STTError) -> Unit)? = null
//
//    // Audio processing
//    private val audioProcessor = AdvancedAudioProcessor()
//    private val qualityOptimizer = AudioQualityOptimizer()
//
//    enum class STTProvider {
//        ANDROID_NATIVE,    // Google Speech Recognition integrado en Android
//        GOOGLE_CLOUD,      // Google Cloud Speech-to-Text API
//        OPENAI_WHISPER,    // OpenAI Whisper API
//        AZURE_SPEECH,      // Microsoft Azure Speech Service
//        AUTO_SELECT        // Selección automática basada en calidad
//    }
//
//    /**
//     * Inicializa el proveedor de STT especificado
//     */
//    suspend fun initialize(provider: STTProvider = STTProvider.AUTO_SELECT): Boolean {
//        return withContext(Dispatchers.IO) {
//            try {
//                currentProvider = if (provider == STTProvider.AUTO_SELECT) {
//                    selectBestProvider()
//                } else {
//                    provider
//                }
//
//                when (currentProvider) {
//                    STTProvider.ANDROID_NATIVE -> initializeAndroidSTT()
//                    STTProvider.GOOGLE_CLOUD -> initializeGoogleCloudSTT()
//                    STTProvider.OPENAI_WHISPER -> initializeOpenAISTT()
//                    STTProvider.AZURE_SPEECH -> initializeAzureSTT()
//                    else -> false
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "Error initializing STT: ${e.message}")
//                false
//            }
//        }
//    }
//
//    /**
//     * Procesa audio y obtiene transcripción
//     */
//    suspend fun transcribeAudio(
//        audioData: ByteArray,
//        sampleRate: Int = 16000,
//        language: String = "es-ES"
//    ): STTResult = withContext(Dispatchers.IO) {
//
//        try {
//            // Procesar audio para STT
//            val processedAudio = audioProcessor.processForSTT(audioData, sampleRate)
//
//            // Analizar calidad
//            val quality = qualityOptimizer.analyzeAudioQuality(processedAudio, sampleRate)
//
//            Log.d(TAG, "Audio quality score: ${quality.qualityScore}")
//
//            // Transcribir según el proveedor
//            val result = when (currentProvider) {
//                STTProvider.ANDROID_NATIVE -> transcribeWithAndroid(processedAudio, language)
//                STTProvider.GOOGLE_CLOUD -> transcribeWithGoogleCloud(processedAudio, sampleRate, language)
//                STTProvider.OPENAI_WHISPER -> transcribeWithOpenAI(processedAudio)
//                STTProvider.AZURE_SPEECH -> transcribeWithAzure(processedAudio, sampleRate, language)
//                else -> STTResult.error("Provider not initialized", currentProvider)
//            }
//
//            // Callback si está configurado
//            onResultCallback?.invoke(result)
//
//            // Enviar a canales suscritos
//            resultChannels.forEach { channel ->
//                try {
//                    channel.trySend(result)
//                } catch (e: Exception) {
//                    Log.e(TAG, "Error sending result to channel: ${e.message}")
//                }
//            }
//
//            result
//
//        } catch (e: Exception) {
//            Log.e(TAG, "Error transcribing audio: ${e.message}")
//            val error = STTResult.error(e.message ?: "Unknown error", currentProvider)
//            onErrorCallback?.invoke(STTError(e.message ?: "Unknown error", currentProvider))
//            error
//        }
//    }
//
//    /**
//     * Inicia transcripción en tiempo real
//     */
//    suspend fun startRealTimeTranscription(
//        audioChannel: Channel<ByteArray>,
//        sampleRate: Int = 16000,
//        language: String = "es-ES"
//    ) {
//        if (isListening) {
//            Log.w(TAG, "Real-time transcription already active")
//            return
//        }
//
//        isListening = true
//
//        CoroutineScope(Dispatchers.IO).launch {
//            try {
//                when (currentProvider) {
//                    STTProvider.GOOGLE_CLOUD -> startGoogleCloudStreaming(audioChannel, sampleRate, language)
//                    STTProvider.OPENAI_WHISPER -> startOpenAIStreaming(audioChannel, sampleRate)
//                    else -> {
//                        // Para otros proveedores, usar chunks de audio
//                        startChunkedTranscription(audioChannel, sampleRate, language)
//                    }
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "Error in real-time transcription: ${e.message}")
//                onErrorCallback?.invoke(STTError(e.message ?: "Unknown error", currentProvider))
//            } finally {
//                isListening = false
//            }
//        }
//    }
//
//    /**
//     * Detiene la transcripción en tiempo real
//     */
//    fun stopRealTimeTranscription() {
//        isListening = false
//    }
//
//    /**
//     * Selecciona el mejor proveedor disponible
//     */
//    private fun selectBestProvider(): STTProvider {
//        return when {
//            googleApiKey != null -> STTProvider.GOOGLE_CLOUD
//            openAiApiKey != null -> STTProvider.OPENAI_WHISPER
//            SpeechRecognizer.isRecognitionAvailable(context) -> STTProvider.ANDROID_NATIVE
//            else -> {
//                Log.w(TAG, "No STT provider available, using Android native")
//                STTProvider.ANDROID_NATIVE
//            }
//        }
//    }
//
//    /**
//     * Inicializa Android Speech Recognizer
//     */
//    private fun initializeAndroidSTT(): Boolean {
//        return try {
//            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
//                Log.e(TAG, "Android Speech Recognition not available")
//                return false
//            }
//
//            androidSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
//            Log.d(TAG, "Android STT initialized successfully")
//            true
//        } catch (e: Exception) {
//            Log.e(TAG, "Error initializing Android STT: ${e.message}")
//            false
//        }
//    }
//
//    /**
//     * Inicializa Google Cloud Speech-to-Text
//     */
//    private fun initializeGoogleCloudSTT(): Boolean {
//        return try {
//            if (googleApiKey == null) {
//                Log.e(TAG, "Google Cloud API key not provided")
//                return false
//            }
//
//            // Configurar cliente de Google Cloud
//            val settings = SpeechSettings.newBuilder()
//                .setCredentialsProvider { TODO("Configurar credenciales") }
//                .build()
//
//            googleCloudClient = SpeechClient.create(settings)
//            Log.d(TAG, "Google Cloud STT initialized successfully")
//            true
//        } catch (e: Exception) {
//            Log.e(TAG, "Error initializing Google Cloud STT: ${e.message}")
//            false
//        }
//    }
//
//    /**
//     * Inicializa OpenAI Whisper
//     */
//    private fun initializeOpenAISTT(): Boolean {
//        return if (openAiApiKey != null) {
//            Log.d(TAG, "OpenAI Whisper STT initialized successfully")
//            true
//        } else {
//            Log.e(TAG, "OpenAI API key not provided")
//            false
//        }
//    }
//
//    /**
//     * Inicializa Azure Speech Service
//     */
//    private fun initializeAzureSTT(): Boolean {
//        return try {
//            // Implementar inicialización de Azure
//            Log.d(TAG, "Azure STT initialized successfully")
//            true
//        } catch (e: Exception) {
//            Log.e(TAG, "Error initializing Azure STT: ${e.message}")
//            false
//        }
//    }
//
//    /**
//     * Transcribe con Android Speech Recognizer
//     */
//    private suspend fun transcribeWithAndroid(audioData: ByteArray, language: String): STTResult {
//        return suspendCancellableCoroutine { continuation ->
//            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
//                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
//                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
//                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
//                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
//            }
//
//            val listener = object : RecognitionListener {
//                override fun onResults(results: Bundle?) {
//                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
//                    if (!matches.isNullOrEmpty()) {
//                        val confidence = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.get(0) ?: 0.8f
//                        continuation.resume(
//                            STTResult.success(
//                                text = matches[0],
//                                confidence = confidence,
//                                provider = STTProvider.ANDROID_NATIVE,
//                                language = language
//                            )
//                        ) {}
//                    } else {
//                        continuation.resume(STTResult.error("No recognition results", STTProvider.ANDROID_NATIVE)) {}
//                    }
//                }
//
//                override fun onError(error: Int) {
//                    val errorMessage = getAndroidSTTErrorMessage(error)
//                    continuation.resume(STTResult.error(errorMessage, STTProvider.ANDROID_NATIVE)) {}
//                }
//
//                override fun onReadyForSpeech(params: Bundle?) {}
//                override fun onBeginningOfSpeech() {}
//                override fun onRmsChanged(rmsdB: Float) {}
//                override fun onBufferReceived(buffer: ByteArray?) {}
//                override fun onEndOfSpeech() {}
//                override fun onPartialResults(partialResults: Bundle?) {}
//                override fun onEvent(eventType: Int, params: Bundle?) {}
//            }
//
//            androidSpeechRecognizer?.setRecognitionListener(listener)
//            androidSpeechRecognizer?.startListening(intent)
//        }
//    }
//
//    /**
//     * Transcribe con Google Cloud Speech-to-Text
//     */
//    private suspend fun transcribeWithGoogleCloud(
//        audioData: ByteArray,
//        sampleRate: Int,
//        language: String
//    ): STTResult {
//        return try {
//            val audioBytes = ByteString.copyFrom(audioData)
//            val audio = RecognitionAudio.newBuilder().setContent(audioBytes).build()
//
//            val config = RecognitionConfig.newBuilder()
//                .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
//                .setSampleRateHertz(sampleRate)
//                .setLanguageCode(language)
//                .setEnableAutomaticPunctuation(true)
//                .setEnableWordConfidence(true)
//                .build()
//
//            val request = RecognizeRequest.newBuilder()
//                .setConfig(config)
//                .setAudio(audio)
//                .build()
//
//            val response = googleCloudClient?.recognize(request)
//            val result = response?.resultsList?.firstOrNull()
//            val alternative = result?.alternativesList?.firstOrNull()
//
//            if (alternative != null) {
//                STTResult.success(
//                    text = alternative.transcript,
//                    confidence = alternative.confidence,
//                    provider = STTProvider.GOOGLE_CLOUD,
//                    language = language,
//                    words = alternative.wordsList.map { word ->
//                        STTWord(
//                            word = word.word,
//                            confidence = word.confidence,
//                            startTime = word.startTime.seconds.toFloat(),
//                            endTime = word.endTime.seconds.toFloat()
//                        )
//                    }
//                )
//            } else {
//                STTResult.error("No transcription results", STTProvider.GOOGLE_CLOUD)
//            }
//        } catch (e: Exception) {
//            Log.e(TAG, "Error with Google Cloud STT: ${e.message}")
//            STTResult.error(e.message ?: "Unknown error", STTProvider.GOOGLE_CLOUD)
//        }
//    }
//
//    /**
//     * Transcribe con OpenAI Whisper
//     */
//    private suspend fun transcribeWithOpenAI(audioData: ByteArray): STTResult {
//        return try {
//            val mediaType = "audio/wav".toMediaType()
//            val requestBody = audioData.toRequestBody(mediaType)
//
//            val multipartBody = MultipartBody.Builder()
//                .setType(MultipartBody.FORM)
//                .addFormDataPart("file", "audio.wav", requestBody)
//                .addFormDataPart("model", "whisper-1")
//                .addFormDataPart("language", "es")
//                .addFormDataPart("response_format", "verbose_json")
//                .build()
//
//            val request = Request.Builder()
//                .url("https://api.openai.com/v1/audio/transcriptions")
//                .addHeader("Authorization", "Bearer $openAiApiKey")
//                .post(multipartBody)
//                .build()
//
//            val response = httpClient.newCall(request).execute()
//
//            if (response.isSuccessful) {
//                val responseBody = response.body?.string()
//                val json = JSONObject(responseBody)
//
//                val text = json.getString("text")
//                val segments = json.optJSONArray("segments")
//                val words = mutableListOf<STTWord>()
//
//                segments?.let { segmentArray ->
//                    for (i in 0 until segmentArray.length()) {
//                        val segment = segmentArray.getJSONObject(i)
//                        val segmentWords = segment.optJSONArray("words")
//                        segmentWords?.let { wordArray ->
//                            for (j in 0 until wordArray.length()) {
//                                val wordObj = wordArray.getJSONObject(j)
//                                words.add(
//                                    STTWord(
//                                        word = wordObj.getString("word"),
//                                        confidence = wordObj.optDouble("probability", 0.8).toFloat(),
//                                        startTime = wordObj.getDouble("start").toFloat(),
//                                        endTime = wordObj.getDouble("end").toFloat()
//                                    )
//                                )
//                            }
//                        }
//                    }
//                }
//
//                STTResult.success(
//                    text = text,
//                    confidence = 0.85f, // Whisper generalmente tiene alta precisión
//                    provider = STTProvider.OPENAI_WHISPER,
//                    language = "es",
//                    words = words
//                )
//            } else {
//                STTResult.error("OpenAI API error: ${response.code}", STTProvider.OPENAI_WHISPER)
//            }
//        } catch (e: Exception) {
//            Log.e(TAG, "Error with OpenAI Whisper: ${e.message}")
//            STTResult.error(e.message ?: "Unknown error", STTProvider.OPENAI_WHISPER)
//        }
//    }
//
//    /**
//     * Transcribe con Azure Speech Service
//     */
//    private suspend fun transcribeWithAzure(
//        audioData: ByteArray,
//        sampleRate: Int,
//        language: String
//    ): STTResult {
//        // Implementar Azure Speech Service
//        return STTResult.error("Azure STT not implemented yet", STTProvider.AZURE_SPEECH)
//    }
//
//    /**
//     * Streaming con Google Cloud
//     */
//    private suspend fun startGoogleCloudStreaming(
//        audioChannel: Channel<ByteArray>,
//        sampleRate: Int,
//        language: String
//    ) {
//        // Implementar streaming con Google Cloud
//        Log.d(TAG, "Google Cloud streaming not implemented yet")
//    }
//
//    /**
//     * Streaming con OpenAI (chunks)
//     */
//    private suspend fun startOpenAIStreaming(audioChannel: Channel<ByteArray>, sampleRate: Int) {
//        startChunkedTranscription(audioChannel, sampleRate, "es")
//    }
//
//    /**
//     * Transcripción por chunks para proveedores que no soportan streaming real
//     */
//    private suspend fun startChunkedTranscription(
//        audioChannel: Channel<ByteArray>,
//        sampleRate: Int,
//        language: String
//    ) {
//        val chunkBuffer = mutableListOf<ByteArray>()
//        val chunkDurationMs = 3000 // 3 segundos por chunk
//        val bytesPerChunk = (sampleRate * 2 * chunkDurationMs) / 1000 // 16-bit = 2 bytes
//        var currentChunkSize = 0
//
//        while (isListening) {
//            try {
//                val audioData = audioChannel.tryReceive()
//                if (audioData.isSuccess) {
//                    chunkBuffer.add(audioData.getOrThrow())
//                    currentChunkSize += audioData.getOrThrow().size
//
//                    if (currentChunkSize >= bytesPerChunk) {
//                        // Concatenar chunks y transcribir
//                        val combinedAudio = chunkBuffer.fold(ByteArray(0)) { acc, chunk ->
//                            acc + chunk
//                        }
//
//                        // Transcribir chunk
//                        launch {
//                            transcribeAudio(combinedAudio, sampleRate, language)
//                        }
//
//                        // Reset buffer
//                        chunkBuffer.clear()
//                        currentChunkSize = 0
//                    }
//                } else {
//                    delay(50) // Esperar más datos
//                }
//            } catch (e: Exception) {
//                Log.e(TAG, "Error in chunked transcription: ${e.message}")
//                break
//            }
//        }
//    }
//
//    /**
//     * Obtiene mensaje de error de Android STT
//     */
//    private fun getAndroidSTTErrorMessage(errorCode: Int): String {
//        return when (errorCode) {
//            SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
//            SpeechRecognizer.ERROR_CLIENT -> "Client side error"
//            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
//            SpeechRecognizer.ERROR_NETWORK -> "Network error"
//            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
//            SpeechRecognizer.ERROR_NO_MATCH -> "No recognition result matched"
//            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RecognitionService busy"
//            SpeechRecognizer.ERROR_SERVER -> "Server sends error status"
//            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
//            else -> "Unknown error: $errorCode"
//        }
//    }
//
//    /**
//     * Configura callbacks
//     */
//    fun setOnResultCallback(callback: (STTResult) -> Unit) {
//        onResultCallback = callback
//    }
//
//    fun setOnErrorCallback(callback: (STTError) -> Unit) {
//        onErrorCallback = callback
//    }
//
//    /**
//     * Crea un canal para recibir resultados
//     */
//    fun createResultChannel(): Channel<STTResult> {
//        val channel = Channel<STTResult>(Channel.UNLIMITED)
//        resultChannels.add(channel)
//        return channel
//    }
//
//    /**
//     * Libera recursos
//     */
//    fun release() {
//        try {
//            isListening = false
//            androidSpeechRecognizer?.destroy()
//            googleCloudClient?.close()
//            audioProcessor.release()
//            resultChannels.forEach { it.close() }
//            resultChannels.clear()
//        } catch (e: Exception) {
//            Log.e(TAG, "Error releasing STT resources: ${e.message}")
//        }
//    }
//
//    // Data classes
//    data class STTResult(
//        val success: Boolean,
//        val text: String,
//        val confidence: Float,
//        val provider: STTProvider,
//        val language: String,
//        val words: List<STTWord> = emptyList(),
//        val error: String? = null
//    ) {
//        companion object {
//            fun success(
//                text: String,
//                confidence: Float,
//                provider: STTProvider,
//                language: String,
//                words: List<STTWord> = emptyList()
//            ) = STTResult(true, text, confidence, provider, language, words, null)
//
//            fun error(error: String, provider: STTProvider) =
//                STTResult(false, "", 0f, provider, "", emptyList(), error)
//        }
//    }
//
//    data class STTWord(
//        val word: String,
//        val confidence: Float,
//        val startTime: Float,
//        val endTime: Float
//    )
//
//    data class STTError(
//        val message: String,
//        val provider: STTProvider
//    )
}