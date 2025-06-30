package com.eddyslarez.siplibrary.data.services.traductor

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.eddyslarez.siplibrary.data.services.audio.AndroidWebRtcManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CallVoiceToTextManager(
    private val context: Context,
    private val webRtcManager: AndroidWebRtcManager
) {
    private val TAG = "CallVoiceToTextManager"

    private val localVoiceProcessor = AndroidVoiceToTextProcessor(context)
    private val audioInterceptor = AudioInterceptor()
    private var isProcessingCall = false

    // Callbacks para resultados
    var onLocalSpeechResult: ((text: String, isFinal: Boolean) -> Unit)? = null
    var onRemoteSpeechResult: ((text: String, isFinal: Boolean) -> Unit)? = null
    var onError: ((source: String, error: String) -> Unit)? = null

    // Para procesar audio remoto con Google Speech API (más efectivo para audio comprimido)
    private val speechClient by lazy {
        // Aquí inicializarías el cliente de Google Speech API si lo usas
        // SpeechClient.create()
    }

    fun startProcessing() {
        if (isProcessingCall) {
            Log.d(TAG, "Ya está procesando la llamada")
            return
        }

        Log.d(TAG, "Iniciando procesamiento de voz a texto para la llamada")
        isProcessingCall = true

        // Procesar voz local (lo que habla el usuario)
        startLocalSpeechProcessing()

        // Procesar voz remota (lo que dice la otra persona)
        startRemoteSpeechProcessing()

        // Registrar listener para captura de audio de WebRTC
        webRtcManager.addAudioCaptureListener { audioData ->
            processRemoteAudioData(audioData)
        }
    }

    fun stopProcessing() {
        if (!isProcessingCall) return

        Log.d(TAG, "Deteniendo procesamiento de voz a texto")
        isProcessingCall = false

        localVoiceProcessor.stopListening()
        audioInterceptor.stopIntercepting()

        // Remover listener de WebRTC
        webRtcManager.removeAudioCaptureListener { }
    }

    private fun startLocalSpeechProcessing() {
        Log.d(TAG, "Iniciando procesamiento de voz local")

        localVoiceProcessor.startListening(
            onResult = { text, isFinal ->
                Log.d(TAG, "Voz local: $text (final: $isFinal)")
                onLocalSpeechResult?.invoke(text, isFinal)
            },
            onError = { error ->
                Log.e(TAG, "Error en voz local: $error")
                onError?.invoke("local", error)
            }
        )
    }

    private fun startRemoteSpeechProcessing() {
        Log.d(TAG, "Iniciando procesamiento de voz remota")

        audioInterceptor.startIntercepting { audioData ->
            processRemoteAudioData(audioData)
        }
    }

    private fun processRemoteAudioData(audioData: ByteArray) {
        // Aquí procesas el audio remoto
        // Opción 1: Usar Google Speech API para mayor precisión
        // Opción 2: Usar SpeechRecognizer con audio file
        // Opción 3: Usar bibliotecas como Vosk para procesamiento offline

        // Ejemplo básico usando archivos temporales y SpeechRecognizer
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tempFile = createTempAudioFile(audioData)
                processAudioFile(tempFile)
                tempFile.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando audio remoto", e)
                onError?.invoke("remote", "Error procesando audio remoto: ${e.message}")
            }
        }
    }

    private fun createTempAudioFile(audioData: ByteArray): File {
        val tempFile = File.createTempFile("remote_audio", ".wav", context.cacheDir)

        // Convertir PCM a WAV
        val wavData = convertPcmToWav(audioData, 16000, 1, 16)
        tempFile.writeBytes(wavData)

        return tempFile
    }

    private fun convertPcmToWav(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val wavHeader = ByteArray(44)
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * channels * bitsPerSample / 8

        // WAV Header
        wavHeader[0] = 'R'.toByte()
        wavHeader[1] = 'I'.toByte()
        wavHeader[2] = 'F'.toByte()
        wavHeader[3] = 'F'.toByte()

        wavHeader[4] = (totalDataLen and 0xff).toByte()
        wavHeader[5] = ((totalDataLen shr 8) and 0xff).toByte()
        wavHeader[6] = ((totalDataLen shr 16) and 0xff).toByte()
        wavHeader[7] = ((totalDataLen shr 24) and 0xff).toByte()

        wavHeader[8] = 'W'.toByte()
        wavHeader[9] = 'A'.toByte()
        wavHeader[10] = 'V'.toByte()
        wavHeader[11] = 'E'.toByte()

        wavHeader[12] = 'f'.toByte()
        wavHeader[13] = 'm'.toByte()
        wavHeader[14] = 't'.toByte()
        wavHeader[15] = ' '.toByte()

        wavHeader[16] = 16
        wavHeader[17] = 0
        wavHeader[18] = 0
        wavHeader[19] = 0

        wavHeader[20] = 1
        wavHeader[21] = 0

        wavHeader[22] = channels.toByte()
        wavHeader[23] = 0

        wavHeader[24] = (sampleRate and 0xff).toByte()
        wavHeader[25] = ((sampleRate shr 8) and 0xff).toByte()
        wavHeader[26] = ((sampleRate shr 16) and 0xff).toByte()
        wavHeader[27] = ((sampleRate shr 24) and 0xff).toByte()

        wavHeader[28] = (byteRate and 0xff).toByte()
        wavHeader[29] = ((byteRate shr 8) and 0xff).toByte()
        wavHeader[30] = ((byteRate shr 16) and 0xff).toByte()
        wavHeader[31] = ((byteRate shr 24) and 0xff).toByte()

        wavHeader[32] = (channels * bitsPerSample / 8).toByte()
        wavHeader[33] = 0

        wavHeader[34] = bitsPerSample.toByte()
        wavHeader[35] = 0

        wavHeader[36] = 'd'.toByte()
        wavHeader[37] = 'a'.toByte()
        wavHeader[38] = 't'.toByte()
        wavHeader[39] = 'a'.toByte()

        wavHeader[40] = (pcmData.size and 0xff).toByte()
        wavHeader[41] = ((pcmData.size shr 8) and 0xff).toByte()
        wavHeader[42] = ((pcmData.size shr 16) and 0xff).toByte()
        wavHeader[43] = ((pcmData.size shr 24) and 0xff).toByte()

        return wavHeader + pcmData
    }

    private suspend fun processAudioFile(audioFile: File) {
        // Usar MediaPlayer y MediaRecorder para procesar el archivo
        // Esta es una implementación simplificada
        withContext(Dispatchers.Main) {
            try {
                val recognizer = SpeechRecognizer.createSpeechRecognizer(context)

                val listener = object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        matches?.firstOrNull()?.let { text ->
                            Log.d(TAG, "Voz remota: $text")
                            onRemoteSpeechResult?.invoke(text, true)
                        }
                        recognizer.destroy()
                    }

                    override fun onError(error: Int) {
                        Log.e(TAG, "Error reconociendo audio remoto: $error")
                        recognizer.destroy()
                    }

                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                }

                recognizer.setRecognitionListener(listener)

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                }

                // Nota: SpeechRecognizer no puede procesar archivos directamente
                // Necesitarías usar Google Speech API o similar para esto
                // recognizer.startListening(intent)

            } catch (e: Exception) {
                Log.e(TAG, "Error procesando archivo de audio", e)
            }
        }
    }

    fun setLanguage(languageCode: String) {
        localVoiceProcessor.setLanguage(languageCode)
    }

    fun isProcessing(): Boolean = isProcessingCall
}