package com.eddyslarez.siplibrary.data.services.audio.newAudio

import com.eddyslarez.siplibrary.data.services.audio.AudioDevice
import com.eddyslarez.siplibrary.data.services.audio.WebRtcConnectionState

/**
 * Interface extendida para eventos WebRTC con soporte para audio virtual
 */
interface WebRtcEventListener {
    /**
     * Called when a new ICE candidate is generated
     */
    fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int)

    /**
     * Called when the connection state changes
     */
    fun onConnectionStateChange(state: WebRtcConnectionState)

    /**
     * Called when an audio track is received from the remote peer
     */
    fun onRemoteAudioTrack()

    /**
     * Called when audio device changes
     */
    fun onAudioDeviceChanged(device: AudioDevice?)

    /**
     * Called when remote audio is transcribed to text
     */
    fun onRemoteAudioTranscribed(transcribedText: String) {
        // Implementación por defecto vacía para compatibilidad
    }

    /**
     * Called when virtual audio processing encounters an error
     */
    fun onVirtualAudioError(error: String) {
        // Implementación por defecto vacía para compatibilidad
    }

    /**
     * Called when audio level changes (for visualization)
     */
    fun onAudioLevelChanged(level: Float) {
        // Implementación por defecto vacía para compatibilidad
    }
}