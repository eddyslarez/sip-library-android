package com.eddyslarez.siplibrary.data.services.translation

import com.eddyslarez.siplibrary.data.services.audio.WebRtcManager
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.nio.ByteBuffer
//
///**
// * Procesador de audio WebRTC simplificado
// */
//class WebRtcAudioProcessor(private val webRtcManager: WebRtcManager) {
//    private var isRunning = false
//    private var audioInterceptor: AudioInterceptorCallback? = null
//    private val processingScope = CoroutineScope(Dispatchers.IO)
//
//    interface AudioInterceptorCallback {
//        fun onIncomingAudioData(audioData: ByteArray): ByteArray
//        fun onOutgoingAudioData(audioData: ByteArray): ByteArray
//    }
//
//    fun setAudioInterceptor(callback: AudioInterceptorCallback) {
//        this.audioInterceptor = callback
//    }
//
//    fun start() {
//        isRunning = true
//        log.d("WebRtcAudioProcessor") { "Audio processor started" }
//    }
//
//    fun stop() {
//        isRunning = false
//        audioInterceptor = null
//        log.d("WebRtcAudioProcessor") { "Audio processor stopped" }
//    }
//
//    // Método para procesar audio (llamado desde el interceptor)
//    fun processAudioData(audioData: ByteArray, isIncoming: Boolean): ByteArray {
//        if (!isRunning || audioInterceptor == null) {
//            return audioData
//        }
//
//        return try {
//            if (isIncoming) {
//                audioInterceptor?.onIncomingAudioData(audioData) ?: audioData
//            } else {
//                audioInterceptor?.onOutgoingAudioData(audioData) ?: audioData
//            }
//        } catch (e: Exception) {
//            log.e("WebRtcAudioProcessor") { "Error processing audio: ${e.message}" }
//            audioData
//        }
//    }
//}
/**
 * Procesador de audio WebRTC simplificado
 */
class WebRtcAudioProcessor(private val webRtcManager: WebRtcManager) {
    private var isRunning = false
    private var audioInterceptor: AudioInterceptorCallback? = null
    private val processingScope = CoroutineScope(Dispatchers.IO)

    interface AudioInterceptorCallback {
        fun onIncomingAudioData(audioData: ByteArray): ByteArray
        fun onOutgoingAudioData(audioData: ByteArray): ByteArray
    }

    fun setAudioInterceptor(callback: AudioInterceptorCallback) {
        this.audioInterceptor = callback
    }

    fun start() {
        isRunning = true
        log.d("WebRtcAudioProcessor") { "Audio processor started" }
    }

    fun stop() {
        isRunning = false
        audioInterceptor = null
        log.d("WebRtcAudioProcessor") { "Audio processor stopped" }
    }

    // Método para procesar audio (llamado desde el interceptor)
    fun processAudioData(audioData: ByteArray, isIncoming: Boolean): ByteArray {
        if (!isRunning || audioInterceptor == null) {
            return audioData
        }

        return try {
            if (isIncoming) {
                audioInterceptor?.onIncomingAudioData(audioData) ?: audioData
            } else {
                audioInterceptor?.onOutgoingAudioData(audioData) ?: audioData
            }
        } catch (e: Exception) {
            log.e("WebRtcAudioProcessor") { "Error processing audio: ${e.message}" }
            audioData
        }
    }
}