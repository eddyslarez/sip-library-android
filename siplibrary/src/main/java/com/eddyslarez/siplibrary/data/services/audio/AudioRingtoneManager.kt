package com.eddyslarez.siplibrary.data.services.audio
import android.app.Application
import android.content.Context
import android.media.AudioAttributes
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
class AudioRingtoneManager(private val application: Application) {

    private var outgoingRingtoneJob: Job? = null
    private var incomingRingtoneJob: Job? = null
    private var vibrationJob: Job? = null
    private var vibrationSyncJob: Job? = null
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

    // Configuración de patrones de vibración
    private val vibrationPatterns = mapOf(
        "default" to VibrationPattern(
            pattern = longArrayOf(0, 500, 200, 500, 200),
            amplitudes = intArrayOf(0, 255, 0, 255, 0),
            repeat = 1
        ),
        "gentle" to VibrationPattern(
            pattern = longArrayOf(0, 300, 300, 300, 300),
            amplitudes = intArrayOf(0, 150, 0, 150, 0),
            repeat = 1
        ),
        "strong" to VibrationPattern(
            pattern = longArrayOf(0, 800, 400, 400, 400),
            amplitudes = intArrayOf(0, 255, 0, 200, 0),
            repeat = 1
        ),
        "heartbeat" to VibrationPattern(
            pattern = longArrayOf(0, 100, 100, 200, 600),
            amplitudes = intArrayOf(0, 255, 0, 255, 0),
            repeat = 1
        )
    )

    private var currentVibrationPattern = "default"

    data class VibrationPattern(
        val pattern: LongArray,
        val amplitudes: IntArray,
        val repeat: Int
    )

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

    fun setVibrationPattern(patternName: String) {
        if (vibrationPatterns.containsKey(patternName)) {
            currentVibrationPattern = patternName
            log.d(tag = TAG) { "Vibration pattern set to: $patternName" }
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
     * Reproduce ringtone de entrada con vibración sincronizada
     */
    fun playRingtone(syncVibration: Boolean = true) {
        log.d(tag = TAG) { "playRingtone() called - sync vibration: $syncVibration" }

        if (isIncomingRingtonePlaying) {
            log.d(tag = TAG) { "Incoming ringtone already playing, ignoring request" }
            return
        }

        stopOutgoingRingtone()

        val uri = incomingRingtoneUri ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        isIncomingRingtonePlaying = true
        shouldStopIncoming = false

        log.d(tag = TAG) { "Starting incoming ringtone with URI: $uri" }

        incomingRingtoneJob = audioScope.launch {
            try {
                var loopCount = 0
                while (!shouldStopIncoming && isIncomingRingtonePlaying) {
                    loopCount++
                    log.d(tag = TAG) { "Incoming ringtone loop iteration: $loopCount" }

                    val mediaPlayer = createIncomingMediaPlayer(uri)
                    if (mediaPlayer == null) {
                        log.e(tag = TAG) { "Failed to create MediaPlayer, stopping ringtone" }
                        break
                    }

                    incomingRingtone = mediaPlayer

                    try {
                        // Iniciar vibración sincronizada con el ringtone
                        if (syncVibration) {
                            startSynchronizedVibration(mediaPlayer)
                        } else {
                            startVibration()
                        }

                        mediaPlayer.start()
                        log.d(tag = TAG) { "MediaPlayer started successfully" }

                        while (mediaPlayer.isPlaying && !shouldStopIncoming && isIncomingRingtonePlaying) {
                            delay(100)
                        }

                        log.d(tag = TAG) { "MediaPlayer finished or stopped" }

                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error during MediaPlayer playback: ${e.message}" }
                    } finally {
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

                    if (!shouldStopIncoming && isIncomingRingtonePlaying) {
                        log.d(tag = TAG) { "Pausing before next iteration..." }
                        delay(800)
                    }
                }

                log.d(tag = TAG) { "Ringtone loop ended after $loopCount iterations" }

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in incoming ringtone coroutine: ${e.message}" }
            } finally {
                isIncomingRingtonePlaying = false
                shouldStopIncoming = false
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
     * Inicia vibración sincronizada con el MediaPlayer
     */
    private fun startSynchronizedVibration(mediaPlayer: MediaPlayer) {
        if (isVibrating) {
            log.d(tag = TAG) { "Vibration already active" }
            return
        }

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
        log.d(tag = TAG) { "Starting synchronized vibration" }

        vibrationSyncJob = audioScope.launch {
            try {
                while (isVibrating && isIncomingRingtonePlaying && !shouldStopIncoming) {
                    // Método 1: Vibración basada en tempo estimado
                    vibrateToRhythm(mediaPlayer)

                    // Esperar un poco antes del siguiente ciclo
                    delay(100)
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in synchronized vibration: ${e.message}" }
            } finally {
                stopVibration()
            }
        }
    }

    /**
     * Vibra siguiendo un patrón rítmico estimado
     */
    private suspend fun vibrateToRhythm(mediaPlayer: MediaPlayer) {
        val pattern = vibrationPatterns[currentVibrationPattern] ?: vibrationPatterns["default"]!!

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Crear efecto de vibración con el patrón seleccionado
                val vibrationEffect = VibrationEffect.createWaveform(
                    pattern.pattern,
                    pattern.amplitudes,
                    -1 // No repetir automáticamente
                )
                vibrator?.vibrate(vibrationEffect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern.pattern, -1)
            }

            // Esperar la duración del patrón
            val totalDuration = pattern.pattern.sum()
            delay(totalDuration)

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error in rhythm vibration: ${e.message}" }
        }
    }

    /**
     * Método avanzado: Analiza el audio en tiempo real para sincronizar vibración
     */
    private fun startAdvancedSynchronizedVibration(mediaPlayer: MediaPlayer) {
        if (!isVibrating) {
            isVibrating = true

            vibrationSyncJob = audioScope.launch {
                try {
                    // Simular análisis de audio básico
                    val beatInterval = 500L // ms entre beats (120 BPM)
                    var lastBeatTime = System.currentTimeMillis()

                    while (isVibrating && mediaPlayer.isPlaying && !shouldStopIncoming) {
                        val currentTime = System.currentTimeMillis()

                        if (currentTime - lastBeatTime >= beatInterval) {
                            // Vibración corta en cada beat
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                vibrator?.vibrate(
                                    VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                vibrator?.vibrate(50)
                            }

                            lastBeatTime = currentTime
                        }

                        delay(50) // Check frequency
                    }
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error in advanced sync vibration: ${e.message}" }
                } finally {
                    isVibrating = false
                }
            }
        }
    }

    /**
     * Inicia el patrón de vibración estándar
     */
    private fun startVibration() {
        if (isVibrating) {
            log.d(tag = TAG) { "Vibration already active" }
            return
        }

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
        log.d(tag = TAG) { "Starting standard vibration pattern" }

        vibrationJob = audioScope.launch {
            try {
                val pattern = vibrationPatterns[currentVibrationPattern] ?: vibrationPatterns["default"]!!

                while (isVibrating && isIncomingRingtonePlaying && !shouldStopIncoming) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator?.vibrate(
                            VibrationEffect.createWaveform(
                                pattern.pattern,
                                pattern.amplitudes,
                                0 // Repetir desde índice 0
                            )
                        )
                        delay(pattern.pattern.sum())
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(pattern.pattern, 0)
                        delay(pattern.pattern.sum())
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
            vibrationSyncJob?.cancel()
            vibrationJob = null
            vibrationSyncJob = null

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

                isLooping = false
                prepare()
                log.d(tag = TAG) { "MediaPlayer prepared successfully" }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error creating MediaPlayer: ${e.message}" }
            null
        }
    }

    /**
     * Reproduce ringtone de salida (sin cambios)
     */
    fun playOutgoingRingtone() {
        log.d(tag = TAG) { "playOutgoingRingtone() called - Current state: isPlaying=$isOutgoingRingtonePlaying" }

        if (isOutgoingRingtonePlaying) {
            log.d(tag = TAG) { "Outgoing ringtone already playing, ignoring request" }
            return
        }

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

    // Métodos de control sin cambios
    fun stopRingtone() {
        log.d(tag = TAG) { "stopRingtone() called - Stopping incoming ringtone and vibration" }

        shouldStopIncoming = true
        isIncomingRingtonePlaying = false

        stopVibration()

        try {
            incomingRingtoneJob?.cancel()
            incomingRingtoneJob = null

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

    fun stopAllRingtones() {
        log.d(tag = TAG) { "stopAllRingtones() called - FORCE STOPPING ALL RINGTONES AND VIBRATION" }

        shouldStopIncoming = true
        shouldStopOutgoing = true
        isIncomingRingtonePlaying = false
        isOutgoingRingtonePlaying = false

        stopVibration()

        try {
            incomingRingtoneJob?.cancel()
            outgoingRingtoneJob?.cancel()
            incomingRingtoneJob = null
            outgoingRingtoneJob = null

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

    // Métodos de estado sin cambios
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
            appendLine("Vibration sync job active: ${vibrationSyncJob?.isActive}")
            appendLine("Current vibration pattern: $currentVibrationPattern")
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
