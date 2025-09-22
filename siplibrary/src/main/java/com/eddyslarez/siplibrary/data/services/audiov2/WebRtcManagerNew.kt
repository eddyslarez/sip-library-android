package com.eddyslarez.siplibrary.data.services.audiov2
//
//interface WebRtcManager {
//    /**
//     * Initialize the WebRTC subsystem
//     */
//    fun initialize()
//
//    /**
//     * Clean up and release WebRTC resources
//     */
//    fun dispose()
//
//    /**
//     * Create an SDP offer for starting a call
//     * @return The SDP offer string
//     */
//    suspend fun createOffer(): String
//
//    /**
//     * Create an SDP answer in response to an offer
//     * @param accountInfo The current account information
//     * @param offerSdp The SDP offer from the remote party
//     * @return The SDP answer string
//     */
//    suspend fun createAnswer(accountInfo: AccountInfo, offerSdp: String): String
//
//    /**
//     * Set the remote description (offer or answer)
//     * @param sdp The remote SDP string
//     * @param type The SDP type (offer or answer)
//     */
//    suspend fun setRemoteDescription(sdp: String, type: SdpType)
//
//    /**
//     * Add an ICE candidate received from the remote party
//     * @param candidate The ICE candidate string
//     * @param sdpMid The media ID
//     * @param sdpMLineIndex The media line index
//     */
//    suspend fun addIceCandidate(candidate: String, sdpMid: String?, sdpMLineIndex: Int?)
//
//    fun getAllAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>>
//
//    fun changeAudioOutputDeviceDuringCall(device: AudioDevice): Boolean
//    fun changeAudioInputDeviceDuringCall(device: AudioDevice): Boolean
//    fun getCurrentInputDevice(): AudioDevice?
//    fun getCurrentOutputDevice(): AudioDevice?
//
//    /**
//     * Enable or disable the local audio track
//     * @param enabled Whether audio should be enabled
//     */
//    fun setAudioEnabled(enabled: Boolean)
//
//    fun setMuted(muted: Boolean)
//    fun isMuted(): Boolean
//    fun getLocalDescription(): String?
//    fun diagnoseAudioIssues(): String
//
//    /**
//     * Get current connection state
//     * @return The connection state
//     */
//    fun getConnectionState(): WebRtcConnectionState
//
//    suspend fun setMediaDirection(direction: MediaDirection)
//    enum class MediaDirection { SENDRECV, SENDONLY, RECVONLY, INACTIVE }
//
//    /**
//     * Set a listener for WebRTC events
//     * @param listener The WebRTC event listener
//     */
//    fun setListener(listener: Any?)
//    fun prepareAudioForIncomingCall()
//    suspend fun applyModifiedSdp(modifiedSdp: String): Boolean
//    fun isInitialized(): Boolean
//
//    /**
//     * Send DTMF tones via RTP (RFC 2833)
//     * @param tones The DTMF tones to send (0-9, *, #, A-D)
//     * @param duration Duration in milliseconds for each tone (optional, default 100ms)
//     * @param gap Gap between tones in milliseconds (optional, default 70ms)
//     * @return true if successfully started sending tones, false otherwise
//     */
//    fun sendDtmfTones(tones: String, duration: Int = 100, gap: Int = 70): Boolean
//}
//
///**
// * Session Description Protocol type
// */
//enum class SdpType {
//    OFFER,
//    ANSWER
//}
//
///**
// * WebRTC connection state
// */
//enum class WebRtcConnectionState {
//    NEW,
//    CONNECTING,
//    CONNECTED,
//    DISCONNECTED,
//    FAILED,
//    CLOSED
//}
//
///**
// * Interface for WebRTC event callbacks
// */
//interface WebRtcEventListener {
//    /**
//     * Called when a new ICE candidate is generated
//     */
//    fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int)
//
//    /**
//     * Called when the connection state changes
//     */
//    fun onConnectionStateChange(state: WebRtcConnectionState)
//
//    /**
//     * Called when an audio track is received from the remote peer
//     */
//    fun onRemoteAudioTrack()
//
//    fun onAudioDeviceChanged(device: AudioDevice?)
//}
