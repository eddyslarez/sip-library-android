package com.eddyslarez.siplibrary.data.services.audio

import android.app.Application
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import com.eddyslarez.siplibrary.EddysSipLibrary
import com.eddyslarez.siplibrary.R
import com.eddyslarez.siplibrary.utils.log
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Gestor de audio mejorado con configuración completa
 *
 * @author Eddys Larez
 */
class AudioManager(
    private val application: Application,
    private var config: EddysSipLibrary.SipConfig = EddysSipLibrary.SipConfig()
) {
    private var outgoingRingtoneJob: Job? = null
    private var incomingRingtoneJob: Job? = null

    private val TAG = "AudioManager"
    private var incomingRingtone: MediaPlayer? = null
    private var outgoingRingtone: MediaPlayer? = null

    private var incomingRingtoneUri: Uri? = null
    private var outgoingRingtoneUri: Uri? = null
    
    // Vibrator para notificaciones
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = ContextCompat.getSystemService(application, VibratorManager::class.java)
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ContextCompat.getSystemService(application, Vibrator::class.java)
        }
    }

    init {
        updateRingtoneUris()
    }

    /**
     * Actualiza la configuración en tiempo de ejecución
     */
    fun updateConfig(newConfig: EddysSipLibrary.SipConfig) {
        this.config = newConfig
        updateRingtoneUris()
        log.d(tag = TAG) { "Audio configuration updated" }
    }

    private fun updateRingtoneUris() {
        // Configurar URIs de ringtones
        incomingRingtoneUri = config.incomingRingtoneUri 
            ?: "android.resource://${application.packageName}/${R.raw.call}".toUri()
        
        outgoingRingtoneUri = config.outgoingRingtoneUri 
            ?: "android.resource://${application.packageName}/${R.raw.ringback}".toUri()
    }

    fun setIncomingRingtone(uri: Uri) {
        incomingRingtoneUri = uri
        log.d(tag = TAG) { "Custom incoming ringtone set: $uri" }
    }

    fun setOutgoingRingtone(uri: Uri) {
        outgoingRingtoneUri = uri
        log.d(tag = TAG) { "Custom outgoing ringtone set: $uri" }
    }

    fun playRingtone() {
        if (!config.enableIncomingRingtone) {
            log.d(tag = TAG) { "Incoming ringtone disabled in config" }
            return
        }

        stopRingtone() // Detenemos si ya hay uno sonando

        val uri = incomingRingtoneUri
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        incomingRingtoneJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val mediaPlayer = MediaPlayer().apply {
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

                        // Aplicar volumen configurado
                        setVolume(config.ringtoneVolume, config.ringtoneVolume)

                        prepare()
                        start()
                    }

                    incomingRingtone = mediaPlayer
                    log.d(tag = TAG) { "Incoming ringtone started with volume: ${config.ringtoneVolume}" }

                    // Iniciar vibración si está habilitada
                    if (config.enableVibration) {
                        startVibration()
                    }

                    while (mediaPlayer.isPlaying && isActive) {
                        delay(100)
                    }

                    mediaPlayer.release()
                    incomingRingtone = null

                    delay(1000) // pausa de 1 segundo entre repeticiones

                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error playing incoming ringtone: ${e.message}" }
                    break
                }
            }
        }
    }

    fun playOutgoingRingtone() {
        if (!config.enableOutgoingRingtone) {
            log.d(tag = TAG) { "Outgoing ringtone disabled in config" }
            return
        }

        stopOutgoingRingtone() // Asegúrate de detener cualquier intento anterior

        val uri = outgoingRingtoneUri
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        outgoingRingtoneJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val mediaPlayer = MediaPlayer().apply {
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

                        // Aplicar volumen configurado
                        setVolume(config.ringtoneVolume, config.ringtoneVolume)

                        prepare()
                        start()
                    }

                    outgoingRingtone = mediaPlayer
                    log.d(tag = TAG) { "Outgoing ringtone started with volume: ${config.ringtoneVolume}" }

                    // Espera hasta que termine el sonido
                    while (mediaPlayer.isPlaying && isActive) {
                        delay(100)
                    }

                    mediaPlayer.release()
                    outgoingRingtone = null

                    delay(1000) // Pausa entre repeticiones

                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error playing outgoing ringtone: ${e.message}" }
                    break
                }
            }
        }
    }

    private fun startVibration() {
        try {
            vibrator?.let { vib ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val vibrationEffect = VibrationEffect.createWaveform(
                        config.vibrationPattern,
                        0 // Repetir desde el inicio
                    )
                    vib.vibrate(vibrationEffect)
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(config.vibrationPattern, 0)
                }
                log.d(tag = TAG) { "Vibration started with pattern: ${config.vibrationPattern.contentToString()}" }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error starting vibration: ${e.message}" }
        }
    }

    private fun stopVibration() {
        try {
            vibrator?.cancel()
            log.d(tag = TAG) { "Vibration stopped" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error stopping vibration: ${e.message}" }
        }
    }

    fun stopRingtone() {
        try {
            incomingRingtoneJob?.cancel()
            incomingRingtoneJob = null

            incomingRingtone?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            incomingRingtone = null
            
            stopVibration()
            
            log.d(tag = TAG) { "Incoming ringtone stopped" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error stopping incoming ringtone: ${e.message}" }
        }
    }

    fun stopOutgoingRingtone() {
        try {
            outgoingRingtoneJob?.cancel()
            outgoingRingtoneJob = null

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

    /**
     * Obtiene información de diagnóstico del audio
     */
    fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== AUDIO MANAGER DIAGNOSTIC ===")
            appendLine("Incoming ringtone enabled: ${config.enableIncomingRingtone}")
            appendLine("Outgoing ringtone enabled: ${config.enableOutgoingRingtone}")
            appendLine("Ringtone volume: ${config.ringtoneVolume}")
            appendLine("Vibration enabled: ${config.enableVibration}")
            appendLine("Vibration pattern: ${config.vibrationPattern.contentToString()}")
            appendLine("Incoming ringtone URI: $incomingRingtoneUri")
            appendLine("Outgoing ringtone URI: $outgoingRingtoneUri")
            appendLine("Incoming ringtone playing: ${incomingRingtone?.isPlaying ?: false}")
            appendLine("Outgoing ringtone playing: ${outgoingRingtone?.isPlaying ?: false}")
            appendLine("Vibrator available: ${vibrator != null}")
        }
    }
}