package com.eddyslarez.siplibrary.data.services.audio

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import com.eddyslarez.siplibrary.utils.log

/**
 * Use case for playing ringtones during calls
 * 
 * @author Eddys Larez
 */
class PlayRingtoneUseCase(private val application: Application) {
    
    private val TAG = "PlayRingtoneUseCase"
    private var incomingRingtone: MediaPlayer? = null
    private var outgoingRingtone: MediaPlayer? = null

    fun playRingtone() {
        try {
            stopRingtone()
            
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            
            incomingRingtone = MediaPlayer().apply {
                setDataSource(application, ringtoneUri)
                
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(AudioManager.STREAM_RING)
                }
                
                isLooping = true
                prepare()
                start()
            }
            
            log.d(tag = TAG) { "Incoming ringtone started" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error playing incoming ringtone: ${e.message}" }
        }
    }

    fun playOutgoingRingtone() {
        try {
            stopOutgoingRingtone()
            
            // Use a simple beep tone for outgoing calls
            outgoingRingtone = MediaPlayer().apply {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(AudioManager.STREAM_VOICE_CALL)
                }
                
                // You can set a custom outgoing ringtone here
                // For now, we'll use the default notification sound
                val notificationUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                setDataSource(application, notificationUri)
                
                isLooping = true
                prepare()
                start()
            }
            
            log.d(tag = TAG) { "Outgoing ringtone started" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error playing outgoing ringtone: ${e.message}" }
        }
    }

    fun stopRingtone() {
        try {
            incomingRingtone?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
            incomingRingtone = null
            log.d(tag = TAG) { "Incoming ringtone stopped" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error stopping incoming ringtone: ${e.message}" }
        }
    }

    fun stopOutgoingRingtone() {
        try {
            outgoingRingtone?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
            outgoingRingtone = null
            log.d(tag = TAG) { "Outgoing ringtone stopped" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error stopping outgoing ringtone: ${e.message}" }
        }
    }

    fun stopAllRingtones() {
        stopRingtone()
        stopOutgoingRingtone()
    }
}