package com.eddyslarez.siplibrary.data.services.traductor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class AndroidVoiceToTextProcessor(
    private val context: Context
) : VoiceToTextProcessor {

    private val TAG = "VoiceToTextProcessor"
    private var speechRecognizer: SpeechRecognizer? = null
    private var isCurrentlyListening = false
    private var onResultCallback: ((String, Boolean) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var currentLanguage = "es-ES" // Español por defecto

    override fun startListening(onResult: (String, Boolean) -> Unit, onError: (String) -> Unit) {
        if (isCurrentlyListening) {
            Log.d(TAG, "Ya está escuchando")
            return
        }

        onResultCallback = onResult
        onErrorCallback = onError

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

            val recognitionListener = object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(TAG, "Listo para reconocer voz")
                    isCurrentlyListening = true
                }

                override fun onBeginningOfSpeech() {
                    Log.d(TAG, "Comenzó a detectar voz")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // Nivel de volumen (opcional para UI)
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                    // Audio buffer recibido
                }

                override fun onEndOfSpeech() {
                    Log.d(TAG, "Fin de la detección de voz")
                }

                override fun onError(error: Int) {
                    val errorMessage = when (error) {
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

                    Log.e(TAG, "Error en reconocimiento: $errorMessage")
                    isCurrentlyListening = false
                    onErrorCallback?.invoke(errorMessage)

                    // Reiniciar automáticamente después de un error (excepto permisos)
                    if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (!isCurrentlyListening) {
                                startListening(onResult, onError)
                            }
                        }, 1000)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.firstOrNull()?.let { text ->
                        Log.d(TAG, "Texto reconocido: $text")
                        onResultCallback?.invoke(text, true) // true = resultado final
                    }
                    isCurrentlyListening = false

                    // Reiniciar automáticamente para reconocimiento continuo
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!isCurrentlyListening) {
                            startListening(onResult, onError)
                        }
                    }, 100)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.firstOrNull()?.let { text ->
                        Log.d(TAG, "Resultado parcial: $text")
                        onResultCallback?.invoke(text, false) // false = resultado parcial
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            }

            speechRecognizer?.setRecognitionListener(recognitionListener)

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, currentLanguage)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                // Para reconocimiento continuo
                putExtra("android.speech.extra.DICTATION_MODE", true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            }

            speechRecognizer?.startListening(intent)

        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando reconocimiento", e)
            onErrorCallback?.invoke("Error iniciando reconocimiento: ${e.message}")
            isCurrentlyListening = false
        }
    }

    override fun stopListening() {
        Log.d(TAG, "Deteniendo reconocimiento de voz")
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        isCurrentlyListening = false
        onResultCallback = null
        onErrorCallback = null
    }

    override fun isListening(): Boolean = isCurrentlyListening

    override fun setLanguage(languageCode: String) {
        currentLanguage = languageCode
        Log.d(TAG, "Idioma cambiado a: $languageCode")
    }
}