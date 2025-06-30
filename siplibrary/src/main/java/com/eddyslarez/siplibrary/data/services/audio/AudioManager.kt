package com.eddyslarez.siplibrary.data.services.audio

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import com.eddyslarez.siplibrary.R
import com.eddyslarez.siplibrary.utils.log
import androidx.core.net.toUri

/**
 * Use case for playing ringtones during calls
 *
 * @author Eddys Larez
 */
class AudioManager(private val application: Application) {

    private val TAG = "AudioManager"
    private var incomingRingtone: MediaPlayer? = null
    private var outgoingRingtone: MediaPlayer? = null

    private var incomingRingtoneUri: Uri? = null
    private var outgoingRingtoneUri: Uri? = null

    init {
        // Valores por defecto (archivos locales en res/raw)
        incomingRingtoneUri = "android.resource://${application.packageName}/${R.raw.call}".toUri()
        outgoingRingtoneUri =
            "android.resource://${application.packageName}/${R.raw.ringback}".toUri()
    }

    fun setIncomingRingtone(uri: Uri) {
        incomingRingtoneUri = uri
    }

    fun setOutgoingRingtone(uri: Uri) {
        outgoingRingtoneUri = uri
    }

    fun playRingtone() {
        try {
            stopRingtone()

            val uri =
                incomingRingtoneUri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            incomingRingtone = MediaPlayer().apply {
                setDataSource(application, uri)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(android.media.AudioManager.STREAM_RING)
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

            val uri = outgoingRingtoneUri
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            outgoingRingtone = MediaPlayer().apply {
                setDataSource(application, uri)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(android.media.AudioManager.STREAM_VOICE_CALL)
                }

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
            incomingRingtone?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            incomingRingtone = null
            log.d(tag = TAG) { "Incoming ringtone stopped" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error stopping incoming ringtone: ${e.message}" }
        }
    }

    fun stopOutgoingRingtone() {
        try {
            outgoingRingtone?.let {
                if (it.isPlaying) it.stop()
                it.release()
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
