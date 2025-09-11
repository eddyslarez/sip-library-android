package com.eddyslarez.siplibrary.data.services.audio
import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.eddyslarez.siplibrary.R
import com.eddyslarez.siplibrary.utils.log
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
/**
 * AudioManager corregido con reproducción continua de ringtones y vibración
 * @author Eddys Larez
 */
class AudioManager(private val application: Application) {

    private var outgoingRingtoneJob: Job? = null
    private var incomingRingtoneJob: Job? = null
    private var vibrationJob: Job? = null
    private val audioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val TAG = "AudioManager"
    private var incomingRingtone: MediaPlayer? = null
    private var outgoingRingtone: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    private var incomingRingtoneUri: Uri? = null
    private var outgoingRingtoneUri: Uri? = null

    // Estados para control de reproducción con atomic para thread safety
    @Volatile private var isIncomingRingtonePlaying = false
    @Volatile private var isOutgoingRingtonePlaying = false
    @Volatile private var shouldStopIncoming = false
    @Volatile private var shouldStopOutgoing = false
    @Volatile private var isVibrating = false

    init {
        incomingRingtoneUri = "android.resource://${application.packageName}/${R.raw.call}".toUri()
        outgoingRingtoneUri = "android.resource://${application.packageName}/${R.raw.ringback}".toUri()

        // Inicializar vibrador
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = application.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            application.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
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
     * Reproduce ringtone de entrada con loop continuo mejorado y vibración
     */
    fun playRingtone() {
        log.d(tag = TAG) { "playRingtone() called - Current state: isPlaying=$isIncomingRingtonePlaying" }

        // Si ya está sonando, no hacer nada
        if (isIncomingRingtonePlaying) {
            log.d(tag = TAG) { "Incoming ringtone already playing, ignoring request" }
            return
        }

        // Detener otros ringtones primero
        stopOutgoingRingtone()

        val uri = incomingRingtoneUri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        // Marcar flags
        isIncomingRingtonePlaying = true
        shouldStopIncoming = false

        log.d(tag = TAG) { "Starting incoming ringtone with URI: $uri" }

        // Iniciar vibración en paralelo
        startVibration()

        incomingRingtoneJob = audioScope.launch {
            try {
                var loopCount = 0
                while (!shouldStopIncoming && isIncomingRingtonePlaying) {
                    loopCount++
                    log.d(tag = TAG) { "Incoming ringtone loop iteration: $loopCount" }

                    // Crear nuevo MediaPlayer para cada iteración
                    val mediaPlayer = createIncomingMediaPlayer(uri)
                    if (mediaPlayer == null) {
                        log.e(tag = TAG) { "Failed to create MediaPlayer, stopping ringtone" }
                        break
                    }

                    incomingRingtone = mediaPlayer

                    try {
                        // Iniciar reproducción
                        mediaPlayer.start()
                        log.d(tag = TAG) { "MediaPlayer started successfully" }

                        // Esperar a que termine o se detenga
                        while (mediaPlayer.isPlaying && !shouldStopIncoming && isIncomingRingtonePlaying) {
                            delay(100)
                        }

                        log.d(tag = TAG) { "MediaPlayer finished or stopped" }

                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error during MediaPlayer playback: ${e.message}" }
                    } finally {
                        // Limpiar MediaPlayer
                        try {
                            if (mediaPlayer.isPlaying) {
                                mediaPlayer.stop()
                            }
                            mediaPlayer.release()
                        } catch (e: Exception) {
                            log.e(tag = TAG) { "Error releasing MediaPlayer: ${e.message}" }
                        }
                        incomingRingtone = null
                    }

                    // Pausa entre repeticiones (solo si debe continuar)
                    if (!shouldStopIncoming && isIncomingRingtonePlaying) {
                        log.d(tag = TAG) { "Pausing before next iteration..." }
                        delay(800) // Pausa más corta entre repeticiones
                    }
                }

                log.d(tag = TAG) { "Ringtone loop ended after $loopCount iterations" }

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in incoming ringtone coroutine: ${e.message}" }
            } finally {
                // Limpieza final
                isIncomingRingtonePlaying = false
                shouldStopIncoming = false

                // Detener vibración
                stopVibration()

                incomingRingtone?.let { player ->
                    try {
                        if (player.isPlaying) player.stop()
                        player.release()
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error in final cleanup: ${e.message}" }
                    }
                }
                incomingRingtone = null
                log.d(tag = TAG) { "Incoming ringtone completely stopped and cleaned up" }
            }
        }
    }

    /**
     * Inicia el patrón de vibración para llamadas entrantes
     */
    private fun startVibration() {
        if (isVibrating) {
            log.d(tag = TAG) { "Vibration already active" }
            return
        }

        // Verificar permisos de vibración
        if (application.checkSelfPermission(android.Manifest.permission.VIBRATE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            log.w(tag = TAG) { "VIBRATE permission not granted" }
            return
        }

        if (vibrator == null || !vibrator!!.hasVibrator()) {
            log.w(tag = TAG) { "Device doesn't support vibration" }
            return
        }

        isVibrating = true
        log.d(tag = TAG) { "Starting vibration pattern" }

        vibrationJob = audioScope.launch {
            try {
                while (isVibrating && isIncomingRingtonePlaying && !shouldStopIncoming) {
                    // Patrón de vibración: vibra 1000ms, pausa 1000ms, vibra 500ms, pausa 500ms
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator?.vibrate(
                            VibrationEffect.createWaveform(
                                longArrayOf(0, 1000, 1000, 500, 500), // delay, vibra, pausa, vibra, pausa
                                intArrayOf(0, 255, 0, 255, 0), // amplitudes
                                0 // repetir desde índice 0
                            )
                        )
                        // Esperar el ciclo completo antes de continuar
                        delay(3000) // 1000 + 1000 + 500 + 500
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(longArrayOf(0, 1000, 1000, 500, 500), 0)
                        delay(3000)
                    }
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in vibration coroutine: ${e.message}" }
            } finally {
                stopVibration()
            }
        }
    }

    /**
     * Detiene la vibración
     */
    private fun stopVibration() {
        if (!isVibrating) return

        isVibrating = false

        try {
            vibrationJob?.cancel()
            vibrationJob = null

            vibrator?.cancel()
            log.d(tag = TAG) { "Vibration stopped" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error stopping vibration: ${e.message}" }
        }
    }

    /**
     * Crea MediaPlayer para ringtone entrante
     */
    private fun createIncomingMediaPlayer(uri: Uri): MediaPlayer? {
        return try {
            MediaPlayer().apply {
                setDataSource(application, uri)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setLegacyStreamType(android.media.AudioManager.STREAM_RING)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(android.media.AudioManager.STREAM_RING)
                }

                // Configurar para looping automático del archivo
                isLooping = false // Lo manejamos manualmente para mejor control

                prepare()
                log.d(tag = TAG) { "MediaPlayer prepared successfully" }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating MediaPlayer: ${e.message}" }
            null
        }
    }

    /**
     * Reproduce ringtone de salida con loop continuo mejorado (sin vibración)
     */
    fun playOutgoingRingtone() {
        log.d(tag = TAG) { "playOutgoingRingtone() called - Current state: isPlaying=$isOutgoingRingtonePlaying" }

        if (isOutgoingRingtonePlaying) {
            log.d(tag = TAG) { "Outgoing ringtone already playing, ignoring request" }
            return
        }

        // Detener otros ringtones primero
        stopRingtone()

        val uri = outgoingRingtoneUri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        isOutgoingRingtonePlaying = true
        shouldStopOutgoing = false

        log.d(tag = TAG) { "Starting outgoing ringtone with URI: $uri" }

        outgoingRingtoneJob = audioScope.launch {
            try {
                var loopCount = 0
                while (!shouldStopOutgoing && isOutgoingRingtonePlaying) {
                    loopCount++
                    log.d(tag = TAG) { "Outgoing ringtone loop iteration: $loopCount" }

                    val mediaPlayer = createOutgoingMediaPlayer(uri)
                    if (mediaPlayer == null) {
                        log.e(tag = TAG) { "Failed to create outgoing MediaPlayer, stopping ringtone" }
                        break
                    }

                    outgoingRingtone = mediaPlayer

                    try {
                        mediaPlayer.start()
                        log.d(tag = TAG) { "Outgoing MediaPlayer started successfully" }

                        while (mediaPlayer.isPlaying && !shouldStopOutgoing && isOutgoingRingtonePlaying) {
                            delay(100)
                        }

                        log.d(tag = TAG) { "Outgoing MediaPlayer finished or stopped" }

                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error during outgoing MediaPlayer playback: ${e.message}" }
                    } finally {
                        try {
                            if (mediaPlayer.isPlaying) {
                                mediaPlayer.stop()
                            }
                            mediaPlayer.release()
                        } catch (e: Exception) {
                            log.e(tag = TAG) { "Error releasing outgoing MediaPlayer: ${e.message}" }
                        }
                        outgoingRingtone = null
                    }

                    if (!shouldStopOutgoing && isOutgoingRingtonePlaying) {
                        delay(1000)
                    }
                }

                log.d(tag = TAG) { "Outgoing ringtone loop ended after $loopCount iterations" }

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in outgoing ringtone coroutine: ${e.message}" }
            } finally {
                isOutgoingRingtonePlaying = false
                shouldStopOutgoing = false
                outgoingRingtone?.let { player ->
                    try {
                        if (player.isPlaying) player.stop()
                        player.release()
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error in outgoing final cleanup: ${e.message}" }
                    }
                }
                outgoingRingtone = null
                log.d(tag = TAG) { "Outgoing ringtone completely stopped and cleaned up" }
            }
        }
    }

    /**
     * Crea MediaPlayer para ringtone saliente
     */
    private fun createOutgoingMediaPlayer(uri: Uri): MediaPlayer? {
        return try {
            MediaPlayer().apply {
                setDataSource(application, uri)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setLegacyStreamType(android.media.AudioManager.STREAM_VOICE_CALL)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(android.media.AudioManager.STREAM_VOICE_CALL)
                }

                isLooping = false
                prepare()
                log.d(tag = TAG) { "Outgoing MediaPlayer prepared successfully" }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating outgoing MediaPlayer: ${e.message}" }
            null
        }
    }

    /**
     * Detiene ringtone de entrada INMEDIATAMENTE (incluyendo vibración)
     */
    fun stopRingtone() {
        log.d(tag = TAG) { "stopRingtone() called - Stopping incoming ringtone and vibration" }

        // Marcar para detener INMEDIATAMENTE
        shouldStopIncoming = true
        isIncomingRingtonePlaying = false

        // Detener vibración inmediatamente
        stopVibration()

        try {
            // Cancelar job
            incomingRingtoneJob?.cancel()
            incomingRingtoneJob = null

            // Detener MediaPlayer actual
            incomingRingtone?.let { player ->
                try {
                    if (player.isPlaying) {
                        player.stop()
                        log.d(tag = TAG) { "Incoming MediaPlayer stopped" }
                    }
                    player.release()
                    log.d(tag = TAG) { "Incoming MediaPlayer released" }
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
     * Detiene ringtone de salida INMEDIATAMENTE
     */
    fun stopOutgoingRingtone() {
        log.d(tag = TAG) { "stopOutgoingRingtone() called - Stopping outgoing ringtone" }

        shouldStopOutgoing = true
        isOutgoingRingtonePlaying = false

        try {
            outgoingRingtoneJob?.cancel()
            outgoingRingtoneJob = null

            outgoingRingtone?.let { player ->
                try {
                    if (player.isPlaying) {
                        player.stop()
                        log.d(tag = TAG) { "Outgoing MediaPlayer stopped" }
                    }
                    player.release()
                    log.d(tag = TAG) { "Outgoing MediaPlayer released" }
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
     * Detiene TODOS los ringtones y vibración INMEDIATAMENTE
     */
    fun stopAllRingtones() {
        log.d(tag = TAG) { "stopAllRingtones() called - FORCE STOPPING ALL RINGTONES AND VIBRATION" }

        // Marcar flags inmediatamente
        shouldStopIncoming = true
        shouldStopOutgoing = true
        isIncomingRingtonePlaying = false
        isOutgoingRingtonePlaying = false

        // Detener vibración
        stopVibration()

        try {
            // Cancelar jobs
            incomingRingtoneJob?.cancel()
            outgoingRingtoneJob?.cancel()
            incomingRingtoneJob = null
            outgoingRingtoneJob = null

            // Detener MediaPlayers
            incomingRingtone?.let { player ->
                try {
                    if (player.isPlaying) player.stop()
                    player.release()
                    log.d(tag = TAG) { "Incoming MediaPlayer force stopped" }
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error force stopping incoming: ${e.message}" }
                }
            }
            incomingRingtone = null

            outgoingRingtone?.let { player ->
                try {
                    if (player.isPlaying) player.stop()
                    player.release()
                    log.d(tag = TAG) { "Outgoing MediaPlayer force stopped" }
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error force stopping outgoing: ${e.message}" }
                }
            }
            outgoingRingtone = null

            log.d(tag = TAG) { "ALL ringtones and vibration force stopped successfully" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in stopAllRingtones: ${e.message}" }
        }
    }

    /**
     * Estados de los ringtones
     */
    fun isRingtonePlaying(): Boolean {
        return isIncomingRingtonePlaying || isOutgoingRingtonePlaying
    }

    fun isIncomingRingtonePlaying(): Boolean {
        return isIncomingRingtonePlaying && !shouldStopIncoming
    }

    fun isOutgoingRingtonePlaying(): Boolean {
        return isOutgoingRingtonePlaying && !shouldStopOutgoing
    }

    fun isVibrating(): Boolean {
        return isVibrating
    }

    /**
     * Diagnóstico de estado
     */
    fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== AUDIO MANAGER DIAGNOSTIC ===")
            appendLine("Incoming playing: $isIncomingRingtonePlaying")
            appendLine("Outgoing playing: $isOutgoingRingtonePlaying")
            appendLine("Vibrating: $isVibrating")
            appendLine("Should stop incoming: $shouldStopIncoming")
            appendLine("Should stop outgoing: $shouldStopOutgoing")
            appendLine("Incoming job active: ${incomingRingtoneJob?.isActive}")
            appendLine("Outgoing job active: ${outgoingRingtoneJob?.isActive}")
            appendLine("Vibration job active: ${vibrationJob?.isActive}")
            appendLine("Incoming MediaPlayer: ${incomingRingtone != null}")
            appendLine("Outgoing MediaPlayer: ${outgoingRingtone != null}")
            appendLine("Vibrator available: ${vibrator?.hasVibrator()}")
            appendLine("Audio scope active: ${audioScope.isActive}")
        }
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
