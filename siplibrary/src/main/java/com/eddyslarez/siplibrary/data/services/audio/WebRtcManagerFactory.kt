package com.eddyslarez.siplibrary.data.services.audio

import android.app.Application

/**
 * Factory for creating platform-specific WebRTC manager instances
 * 
 * @author Eddys Larez
 */
object WebRtcManagerFactory {
    /**
     * Create a new WebRTC manager instance
     * @return A platform-specific implementation of WebRtcManager
     */
    fun createWebRtcManager(application: Application): WebRtcManager {
        return EnhancedWebRtcManager(application) // Usar versión mejorada
    }

}