package com.eddyslarez.siplibrary.data.services.transcription

import com.google.gson.Gson
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException

class OpenAIWhisperTranscriber(
    private val apiKey: String,
    private val onTranscription: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    private val client = OkHttpClient()
    private val gson = Gson()

    data class WhisperRequest(
        val model: String = "whisper-1",
        val response_format: String = "json",
        val language: String? = null
    )

    data class WhisperResponse(
        val text: String
    )

    fun transcribeAudio(audioFile: File, language: String? = null) {
        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audioFile.name, audioFile.asRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("response_format", "json")
            .apply {
                language?.let { addFormDataPart("language", it) }
            }
            .build()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("Network error: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (resp.isSuccessful) {
                        val responseBody = resp.body?.string()
                        responseBody?.let { body ->
                            try {
                                val whisperResponse = gson.fromJson(body, WhisperResponse::class.java)
                                onTranscription(whisperResponse.text)
                            } catch (e: Exception) {
                                onError("Parse error: ${e.message}")
                            }
                        }
                    } else {
                        onError("API error: ${resp.code} - ${resp.message}")
                    }
                }
            }
        })
    }
}
