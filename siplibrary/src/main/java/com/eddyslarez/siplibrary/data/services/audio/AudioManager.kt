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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

/**
 * AudioManager corregido con mejor gestión de ringtones
 * @author Eddys Larez
 */
class AudioManager(private val application: Application) {

    private var outgoingRingtoneJob: Job? = null
    private var incomingRingtoneJob: Job? = null

    private val TAG = "AudioManager"
    private var incomingRingtone: MediaPlayer? = null
    private var outgoingRingtone: MediaPlayer? = null

    private var incomingRingtoneUri: Uri? = null
    private var outgoingRingtoneUri: Uri? = null

    // Estados para control de reproducción
    private var isIncomingRingtonePlaying = false
    private var isOutgoingRingtonePlaying = false

    init {
        incomingRingtoneUri = "android.resource://${application.packageName}/${R.raw.call}".toUri()
        outgoingRingtoneUri = "android.resource://${application.packageName}/${R.raw.ringback}".toUri()
    }

    fun setIncomingRingtone(uri: Uri) {
        incomingRingtoneUri = uri
        log.d(tag = TAG) { "Incoming ringtone URI set: $uri" }
    }

    fun setOutgoingRingtone(uri: Uri) {
        outgoingRingtoneUri = uri
        log.d(tag = TAG) { "Outgoing ringtone URI set: $uri" }
    }

    /**
     * Reproduce ringtone de entrada
     */
    fun playRingtone() {
        // Primero detener cualquier ringtone activo
        stopAllRingtones()

        if (isIncomingRingtonePlaying) {
            log.d(tag = TAG) { "Incoming ringtone already playing" }
            return
        }

        val uri = incomingRingtoneUri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        isIncomingRingtonePlaying = true

        incomingRingtoneJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                while (isActive && isIncomingRingtonePlaying) {
                    val mediaPlayer = MediaPlayer().apply {
                        try {
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

                            prepare()
                            start()
                        } catch (e: Exception) {
                            log.e(tag = TAG) { "Error preparing incoming ringtone: ${e.message}" }
                            release()
                            return@launch
                        }
                    }

                    incomingRingtone = mediaPlayer
                    log.d(tag = TAG) { "Incoming ringtone started" }

                    // Esperar a que termine la reproducción
                    while (mediaPlayer.isPlaying && isActive && isIncomingRingtonePlaying) {
                        delay(100)
                    }

                    // Limpiar recursos
                    try {
                        mediaPlayer.release()
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error releasing incoming ringtone: ${e.message}" }
                    }

                    incomingRingtone = null

                    // Pausa entre repeticiones si aún debe sonar
                    if (isIncomingRingtonePlaying && isActive) {
                        delay(1000)
                    }
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in incoming ringtone loop: ${e.message}" }
            } finally {
                isIncomingRingtonePlaying = false
                incomingRingtone?.let { player ->
                    try {
                        if (player.isPlaying) player.stop()
                        player.release()
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error releasing incoming ringtone in finally: ${e.message}" }
                    }
                }
                incomingRingtone = null
            }
        }
    }

    /**
     * Reproduce ringtone de salida
     */
    fun playOutgoingRingtone() {
        // Primero detener cualquier ringtone activo
        stopAllRingtones()

        if (isOutgoingRingtonePlaying) {
            log.d(tag = TAG) { "Outgoing ringtone already playing" }
            return
        }

        val uri = outgoingRingtoneUri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        isOutgoingRingtonePlaying = true

        outgoingRingtoneJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                while (isActive && isOutgoingRingtonePlaying) {
                    val mediaPlayer = MediaPlayer().apply {
                        try {
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

                            prepare()
                            start()
                        } catch (e: Exception) {
                            log.e(tag = TAG) { "Error preparing outgoing ringtone: ${e.message}" }
                            release()
                            return@launch
                        }
                    }

                    outgoingRingtone = mediaPlayer
                    log.d(tag = TAG) { "Outgoing ringtone started" }

                    // Esperar a que termine la reproducción
                    while (mediaPlayer.isPlaying && isActive && isOutgoingRingtonePlaying) {
                        delay(100)
                    }

                    // Limpiar recursos
                    try {
                        mediaPlayer.release()
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error releasing outgoing ringtone: ${e.message}" }
                    }

                    outgoingRingtone = null

                    // Pausa entre repeticiones si aún debe sonar
                    if (isOutgoingRingtonePlaying && isActive) {
                        delay(1000)
                    }
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in outgoing ringtone loop: ${e.message}" }
            } finally {
                isOutgoingRingtonePlaying = false
                outgoingRingtone?.let { player ->
                    try {
                        if (player.isPlaying) player.stop()
                        player.release()
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error releasing outgoing ringtone in finally: ${e.message}" }
                    }
                }
                outgoingRingtone = null
            }
        }
    }

    /**
     * Detiene ringtone de entrada con limpieza completa
     */
    fun stopRingtone() {
        try {
            log.d(tag = TAG) { "Stopping incoming ringtone..." }
            isIncomingRingtonePlaying = false

            // Cancelar job
            incomingRingtoneJob?.cancel()
            incomingRingtoneJob = null

            // Detener y liberar MediaPlayer
            incomingRingtone?.let { player ->
                try {
                    if (player.isPlaying) {
                        player.stop()
                    }
                    player.release()
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error stopping/releasing incoming ringtone: ${e.message}" }
                }
            }
            incomingRingtone = null

            log.d(tag = TAG) { "Incoming ringtone stopped successfully" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error stopping incoming ringtone: ${e.message}" }
        }
    }

    /**
     * Detiene ringtone de salida con limpieza completa
     */
    fun stopOutgoingRingtone() {
        try {
            log.d(tag = TAG) { "Stopping outgoing ringtone..." }
            isOutgoingRingtonePlaying = false

            // Cancelar job
            outgoingRingtoneJob?.cancel()
            outgoingRingtoneJob = null

            // Detener y liberar MediaPlayer
            outgoingRingtone?.let { player ->
                try {
                    if (player.isPlaying) {
                        player.stop()
                    }
                    player.release()
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error stopping/releasing outgoing ringtone: ${e.message}" }
                }
            }
            outgoingRingtone = null

            log.d(tag = TAG) { "Outgoing ringtone stopped successfully" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error stopping outgoing ringtone: ${e.message}" }
        }
    }

    /**
     * Detiene todos los ringtones
     */
    fun stopAllRingtones() {
        log.d(tag = TAG) { "Stopping all ringtones..." }
        stopRingtone()
        stopOutgoingRingtone()
    }

    /**
     * Estado de los ringtones
     */
    fun isRingtonePlaying(): Boolean {
        return isIncomingRingtonePlaying || isOutgoingRingtonePlaying
    }

    fun isIncomingRingtonePlaying(): Boolean {
        return isIncomingRingtonePlaying
    }

    fun isOutgoingRingtonePlaying(): Boolean {
        return isOutgoingRingtonePlaying
    }
}

//import android.app.Application
//import android.media.AudioAttributes
//import android.media.AudioManager
//import android.media.MediaPlayer
//import android.media.RingtoneManager
//import android.net.Uri
//import android.os.Build
//import com.eddyslarez.siplibrary.R
//import com.eddyslarez.siplibrary.utils.log
//import androidx.core.net.toUri
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.Job
//import kotlinx.coroutines.NonCancellable.isActive
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.launch
//
///**
// * Use case for playing ringtones during calls
// *
// * @author Eddys Larez
// */
//class AudioManager(private val application: Application) {
//    private var outgoingRingtoneJob: Job? = null
//    private var incomingRingtoneJob: Job? = null
//
//    private val TAG = "AudioManager"
//    private var incomingRingtone: MediaPlayer? = null
//    private var outgoingRingtone: MediaPlayer? = null
//
//    private var incomingRingtoneUri: Uri? = null
//    private var outgoingRingtoneUri: Uri? = null
//
//    init {
//        // Valores por defecto (archivos locales en res/raw)
//        incomingRingtoneUri = "android.resource://${application.packageName}/${R.raw.call}".toUri()
//        outgoingRingtoneUri =
//            "android.resource://${application.packageName}/${R.raw.ringback}".toUri()
//    }
//
//    fun setIncomingRingtone(uri: Uri) {
//        incomingRingtoneUri = uri
//        log.d(tag = TAG) { "Incoming ringtone URI set: $uri" }
//    }
//
//    fun setOutgoingRingtone(uri: Uri) {
//        outgoingRingtoneUri = uri
//        log.d(tag = TAG) { "Outgoing ringtone URI set: $uri" }
//    }
//
//    fun playRingtone() {
//        stopRingtone() // Detenemos si ya hay uno sonando
//
//        val uri = incomingRingtoneUri
//            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
//
//        incomingRingtoneJob = CoroutineScope(Dispatchers.IO).launch {
//            while (isActive) {
//                try {
//                    val mediaPlayer = MediaPlayer().apply {
//                        setDataSource(application, uri)
//
//                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
//                            setAudioAttributes(
//                                AudioAttributes.Builder()
//                                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
//                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
//                                    .build()
//                            )
//                        } else {
//                            @Suppress("DEPRECATION")
//                            setAudioStreamType(android.media.AudioManager.STREAM_RING)
//                        }
//
//                        prepare()
//                        start()
//                    }
//
//                    incomingRingtone = mediaPlayer
//                    log.d(tag = TAG) { "Incoming ringtone started" }
//
//                    while (mediaPlayer.isPlaying && isActive) {
//                        delay(100)
//                    }
//
//                    mediaPlayer.release()
//                    incomingRingtone = null
//
//                    delay(1000) // pausa de 1 segundo entre repeticiones
//
//                } catch (e: Exception) {
//                    log.e(tag = TAG) { "Error playing incoming ringtone: ${e.message}" }
//                    break
//                }
//            }
//        }
//    }
//
//
//    fun playOutgoingRingtone() {
//        stopOutgoingRingtone() // Asegúrate de detener cualquier intento anterior
//
//        val uri = outgoingRingtoneUri
//            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
//
//        outgoingRingtoneJob = CoroutineScope(Dispatchers.IO).launch {
//            while (isActive) {
//                try {
//                    val mediaPlayer = MediaPlayer().apply {
//                        setDataSource(application, uri)
//
//                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
//                            setAudioAttributes(
//                                AudioAttributes.Builder()
//                                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
//                                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
//                                    .build()
//                            )
//                        } else {
//                            @Suppress("DEPRECATION")
//                            setAudioStreamType(android.media.AudioManager.STREAM_VOICE_CALL)
//                        }
//
//                        prepare()
//                        start()
//                    }
//
//                    outgoingRingtone = mediaPlayer
//                    log.d(tag = TAG) { "Outgoing ringtone started" }
//
//                    // Espera hasta que termine el sonido
//                    while (mediaPlayer.isPlaying && isActive) {
//                        delay(100)
//                    }
//
//                    mediaPlayer.release()
//                    outgoingRingtone = null
//
//                    delay(1000) // Pausa entre repeticiones
//
//                } catch (e: Exception) {
//                    log.e(tag = TAG) { "Error playing outgoing ringtone: ${e.message}" }
//                    break
//                }
//            }
//        }
//    }
//
//    fun stopRingtone() {
//        try {
//            incomingRingtoneJob?.cancel()
//            incomingRingtoneJob = null
//
//            incomingRingtone?.let {
//                if (it.isPlaying) it.stop()
//                it.release()
//            }
//            incomingRingtone = null
//            log.d(tag = TAG) { "Incoming ringtone stopped" }
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error stopping incoming ringtone: ${e.message}" }
//        }
//    }
//
//
//    fun stopOutgoingRingtone() {
//        try {
//            outgoingRingtoneJob?.cancel()
//            outgoingRingtoneJob = null
//
//            outgoingRingtone?.let {
//                if (it.isPlaying) it.stop()
//                it.release()
//            }
//            outgoingRingtone = null
//            log.d(tag = TAG) { "Outgoing ringtone stopped" }
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error stopping outgoing ringtone: ${e.message}" }
//        }
//    }
//
//
//    fun stopAllRingtones() {
//        stopRingtone()
//        stopOutgoingRingtone()
//    }
//}
