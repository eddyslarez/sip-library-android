package com.eddyslarez.siplibrary.data.services.transcription

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import org.webrtc.AudioTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioTranscriptionManager(
    private val context: Context,
    private val onTranscription: (String, Boolean) -> Unit, // text, isComplete
    private val onError: (String) -> Unit
) {
    private var currentTranscriber: Any? = null
    private var audioSink: CustomAudioSink? = null

    enum class TranscriberType {
        ANDROID_SPEECH,
        VOSK_OFFLINE,
        OPENAI_WHISPER,
        OPENAI_REALTIME
    }

    fun setupAudioInterceptionWithSink(audioTrack: AudioTrack, type: TranscriberType) {
        audioSink = CustomAudioSink { audioData ->
            when (type) {
                TranscriberType.ANDROID_SPEECH -> {
                    // Android Speech Recognizer trabaja con micrófono, no con datos raw
                    // Necesitarías usar AudioRecord para capturar y luego procesar
                }

                TranscriberType.VOSK_OFFLINE -> {
                    (currentTranscriber as? VoskTranscriber)?.transcribeAudioShort(byteArrayToShortArray(audioData))
                }

                TranscriberType.OPENAI_REALTIME -> {
                    (currentTranscriber as? OpenAIRealtimeTranscriber)?.sendAudio(audioData)
                }

                TranscriberType.OPENAI_WHISPER -> {
                    // Para Whisper API necesitas acumular audio y enviarlo en chunks
                    handleWhisperAudio(audioData)
                }
            }
        }

        audioTrack.addSink(audioSink)
    }
    fun byteArrayToShortArray(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN) // PCM16LE usa little endian
            .asShortBuffer()
            .get(shorts)
        return shorts
    }

    private fun handleWhisperAudio(audioData: ByteArray) {
        // Implementar lógica para acumular audio y enviarlo a Whisper API
        // en chunks de ~30 segundos para mejor rendimiento
    }

    @RequiresApi(Build.VERSION_CODES.O)
    suspend fun initializeTranscriber(type: TranscriberType, apiKey: String? = null) {
        currentTranscriber = when (type) {
            TranscriberType.ANDROID_SPEECH -> {
                AndroidSpeechTranscriber(
                    context,
                    { text -> onTranscription(text, true) },
                    onError
                ).apply { startTranscription() }
            }

            TranscriberType.VOSK_OFFLINE -> {
                VoskTranscriber(
                    context,
                    { text -> onTranscription(text, true) },
                    onError
                ).apply { initialize() }
            }

            TranscriberType.OPENAI_WHISPER -> {
                requireNotNull(apiKey) { "API key required for OpenAI Whisper" }
                OpenAIWhisperTranscriber(
                    apiKey,
                    { text -> onTranscription(text, true) },
                    onError
                )
            }

            TranscriberType.OPENAI_REALTIME -> {
                requireNotNull(apiKey) { "API key required for OpenAI Realtime" }
                OpenAIRealtimeTranscriber(apiKey, onTranscription, onError).apply {
                    connect()
                }
            }
        }
    }

    fun cleanup() {
        when (currentTranscriber) {
            is AndroidSpeechTranscriber -> (currentTranscriber as AndroidSpeechTranscriber).stopTranscription()
            is VoskTranscriber -> (currentTranscriber as VoskTranscriber).cleanup()
            is OpenAIRealtimeTranscriber -> (currentTranscriber as OpenAIRealtimeTranscriber).disconnect()
        }
        currentTranscriber = null
        audioSink = null
    }
}