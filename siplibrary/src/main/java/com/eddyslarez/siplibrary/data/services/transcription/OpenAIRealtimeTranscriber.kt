package com.eddyslarez.siplibrary.data.services.transcription

import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import com.google.gson.Gson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

class OpenAIRealtimeTranscriber(
    private val apiKey: String,
    private val onTranscription: (String, Boolean) -> Unit, // text, isComplete
    private val onError: (String) -> Unit
) {
    private var webSocket: WebSocket? = null
    private val gson = Gson()

    // Configuración para sesión de transcripción
    data class SessionUpdate(
        val type: String = "session.update",
        val session: TranscriptionSession
    )

    data class TranscriptionSession(
        val input_audio_format: String = "pcm16",
        val input_audio_transcription: AudioTranscription = AudioTranscription(),
        val turn_detection: TurnDetection? = TurnDetection(),
        val input_audio_noise_reduction: NoiseReduction? = NoiseReduction(),
        val tools: List<Any> = emptyList(),
        val tool_choice: String = "none"
    )

    data class AudioTranscription(
        val model: String = "whisper-1", // También puedes usar "gpt-4o-transcribe" o "gpt-4o-mini-transcribe"
        val prompt: String = "",
        val language: String = "es" // Cambia según el idioma esperado
    )

    data class TurnDetection(
        val type: String = "server_vad",
        val threshold: Float = 0.5f,
        val prefix_padding_ms: Int = 300,
        val silence_duration_ms: Int = 500
    )

    data class NoiseReduction(
        val type: String = "near_field" // o "far_field" según tu caso de uso
    )

    data class AudioAppend(
        val type: String = "input_audio_buffer.append",
        val audio: String // Base64 encoded PCM16
    )

    data class AudioCommit(
        val type: String = "input_audio_buffer.commit"
    )

    fun connect() {
        val client = OkHttpClient()
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=gpt-4o-realtime-preview")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("OpenAI-Beta", "realtime=v1")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("RealtimeTranscriber", "WebSocket conectado")

                // Configurar sesión específicamente para transcripción
                val sessionUpdate = SessionUpdate(
                    session = TranscriptionSession()
                )
                val json = gson.toJson(sessionUpdate)
                Log.d("RealtimeTranscriber", "Enviando configuración: $json")
                webSocket.send(json)
            }

            @RequiresApi(Build.VERSION_CODES.O)
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleRealtimeEvent(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("RealtimeTranscriber", "WebSocket error: ${t.message}")
                onError("WebSocket error: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d("RealtimeTranscriber", "WebSocket cerrado: $reason")
            }
        })
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun handleRealtimeEvent(eventJson: String) {
        Log.d("RealtimeEvent", "Evento recibido: $eventJson")
        val json = JSONObject(eventJson)
        val type = json.getString("type")

        try {
            when (type) {
                "session.created" -> {
                    Log.d("RealtimeTranscriber", "Sesión creada exitosamente")
                }
                "session.updated" -> {
                    Log.d("RealtimeTranscriber", "Sesión actualizada exitosamente")
                }
                "input_audio_buffer.committed" -> {
                    val itemId = json.optString("item_id", "unknown")
                    Log.d("RealtimeTranscriber", "Buffer de audio comprometido - item_id: $itemId")
                }
                "conversation.item.input_audio_transcription.delta" -> {
                    val delta = json.getString("delta")
                    val itemId = json.optString("item_id", "")
                    Log.d("RealtimeTranscriber", "Transcripción parcial ($itemId): $delta")
                    onTranscription(delta, false)
                }
                "conversation.item.input_audio_transcription.completed" -> {
                    val transcript = json.getString("transcript")
                    val itemId = json.optString("item_id", "")
                    Log.d("RealtimeTranscriber", "Transcripción completa ($itemId): $transcript")
                    onTranscription(transcript, true)
                }
                "error" -> {
                    val error = json.getJSONObject("error")
                    val errorMsg = "${error.getString("type")}: ${error.getString("message")}"
                    Log.e("RealtimeTranscriber", "Error del servidor: $errorMsg")
                    onError("Error del servidor: $errorMsg")
                }
                else -> {
                    Log.d("RealtimeTranscriber", "Evento no manejado: $type - $eventJson")
                }
            }
        } catch (e: Exception) {
            Log.e("RealtimeTranscriber", "Error parsing event: ${e.message}")
            onError("Error parsing event: ${e.message}")
        }
    }

    // Buffer para acumular audio antes de enviar
    private val audioBuffer = mutableListOf<ByteArray>()
    private val bufferSizeThreshold = 4800 // ~100ms de audio a 48kHz

    fun sendAudio(audioData: ByteArray) {
        try {
            // Acumular audio en buffer
            audioBuffer.add(audioData)

            // Enviar cuando tengamos suficiente audio
            if (audioBuffer.sumOf { it.size } >= bufferSizeThreshold) {
                val combinedAudio = audioBuffer.reduce { acc, bytes -> acc + bytes }
                val base64Audio = Base64.encodeToString(combinedAudio, Base64.NO_WRAP)

                val audioAppend = AudioAppend(audio = base64Audio)
                val json = gson.toJson(audioAppend)
                webSocket?.send(json)

                Log.d("RealtimeTranscriber", "Audio enviado: ${combinedAudio.size} bytes")
                audioBuffer.clear()
            }
        } catch (e: Exception) {
            Log.e("RealtimeTranscriber", "Error enviando audio: ${e.message}")
            onError("Error enviando audio: ${e.message}")
        }
    }

    // Método para forzar el procesamiento del audio actual
    fun commitAudioBuffer() {
        try {
            if (audioBuffer.isNotEmpty()) {
                val combinedAudio = audioBuffer.reduce { acc, bytes -> acc + bytes }
                val base64Audio = Base64.encodeToString(combinedAudio, Base64.NO_WRAP)

                val audioAppend = AudioAppend(audio = base64Audio)
                val appendJson = gson.toJson(audioAppend)
                webSocket?.send(appendJson)

                audioBuffer.clear()
                Log.d("RealtimeTranscriber", "Buffer de audio comprometido manualmente")
            }

            // Enviar commit
            val audioCommit = AudioCommit()
            val commitJson = gson.toJson(audioCommit)
            webSocket?.send(commitJson)

        } catch (e: Exception) {
            Log.e("RealtimeTranscriber", "Error comprometiendo buffer: ${e.message}")
            onError("Error comprometiendo buffer: ${e.message}")
        }
    }

    fun disconnect() {
        // Enviar cualquier audio restante antes de desconectar
        if (audioBuffer.isNotEmpty()) {
            commitAudioBuffer()
        }

        webSocket?.close(1000, "Normal closure")
        webSocket = null
        audioBuffer.clear()
    }
}