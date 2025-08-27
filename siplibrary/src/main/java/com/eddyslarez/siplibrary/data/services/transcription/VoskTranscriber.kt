package com.eddyslarez.siplibrary.data.services.transcription

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import java.io.File
import org.vosk.Recognizer

import kotlinx.coroutines.*
class VoskTranscriber(
    private val context: Context,
    private val onTranscription: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private var model: Model? = null
    private var recognizer: Recognizer? = null

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun initialize() {
        try {
            // Descargar modelo desde assets o almacenamiento
            val modelPath = extractModel()
            model = Model(modelPath)
            recognizer = Recognizer(model, 16000.0f)
            recognizer?.setWords(true) // Para obtener palabras individuales con timestamps
        } catch (e: Exception) {
            onError("Error initializing Vosk: ${e.message}")
        }
    }

    private suspend fun extractModel(): String = withContext(Dispatchers.IO) {
        // Aquí extraerías el modelo de assets a almacenamiento interno
        // Los modelos se descargan de: https://alphacephei.com/vosk/models
        val modelDir = File(context.filesDir, "vosk-model")
        if (!modelDir.exists()) {
            // Extraer modelo desde assets o descargar
            extractModelFromAssets(modelDir)
        }
        modelDir.absolutePath
    }

    private fun extractModelFromAssets(targetDir: File) {
        // Implementar extracción del modelo desde assets
        // El modelo debe estar en assets/vosk-model/
    }

    fun transcribeAudioShort(audioData: ShortArray) {
        recognizer?.let { rec ->
            if (rec.acceptWaveForm(audioData, audioData.size)) {
                val result = rec.getResult()
                parseVoskResult(result)?.let { text ->
                    onTranscription(text)
                }
            } else {
                val partialResult = rec.getPartialResult()
                parseVoskPartialResult(partialResult)?.let { text ->
                    onTranscription("[$text]")
                }
            }
        }
    }

    private fun parseVoskResult(jsonResult: String): String? {
        return try {
            val json = JSONObject(jsonResult)
            json.getString("text").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseVoskPartialResult(jsonResult: String): String? {
        return try {
            val json = JSONObject(jsonResult)
            json.getString("partial").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    fun cleanup() {
        recognizer?.close()
        model?.close()
    }
}
