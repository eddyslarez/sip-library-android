package com.eddyslarez.siplibrary.core

import android.app.Application
import android.net.Uri
import com.eddyslarez.siplibrary.data.database.DatabaseManager
import com.eddyslarez.siplibrary.data.database.entities.AppConfigEntity
import com.eddyslarez.siplibrary.data.models.AccountInfo
import com.eddyslarez.siplibrary.data.services.audio.AudioDevice
import com.eddyslarez.siplibrary.data.services.audio.AudioDeviceManager
import com.eddyslarez.siplibrary.data.services.audio.AudioManager
import com.eddyslarez.siplibrary.data.services.audio.WebRtcConnectionState
import com.eddyslarez.siplibrary.data.services.audio.WebRtcEventListener
import com.eddyslarez.siplibrary.data.services.audio.WebRtcManager
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SipAudioManager(
    private val application: Application,
    private val audioManager: AudioManager,
    private val webRtcManager: WebRtcManager
) {
    private val audioDeviceManager = AudioDeviceManager()
    private var loadedConfig: AppConfigEntity? = null

    companion object {
        private const val TAG = "SipAudioManager"
    }

    /**
     * Inicializar componentes de audio
     */
    fun initialize() {
        webRtcManager.initialize()
        setupWebRtcAudioListener()
        refreshAudioDevices()
    }

    /**
     * Configurar listener de eventos de audio WebRTC
     */
    private fun setupWebRtcAudioListener() {
        webRtcManager.setListener(object : WebRtcEventListener {
            override fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
                // Manejado por el call manager
            }

            override fun onConnectionStateChange(state: WebRtcConnectionState) {
                // Manejado por el call manager
            }

            override fun onRemoteAudioTrack() {
                log.d(tag = TAG) { "Remote audio track received" }
            }

            override fun onAudioDeviceChanged(device: AudioDevice?) {
                log.d(tag = TAG) { "Audio device changed: ${device?.name}" }
                refreshAudioDevices()
            }
        })
    }

    /**
     * Preparar audio para llamada entrante
     */
    suspend fun prepareAudioForIncomingCall() {
        if (!webRtcManager.isInitialized()) {
            webRtcManager.initialize()
            delay(1000)
        }
        webRtcManager.prepareAudioForIncomingCall()
    }

    /**
     * Configurar audio para llamada saliente
     */
    suspend fun prepareAudioForOutgoingCall() {
        webRtcManager.setAudioEnabled(true)
    }

    /**
     * Obtener dispositivos de audio disponibles
     */
    fun getAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        return webRtcManager.getAllAudioDevices()
    }

    /**
     * Obtener dispositivos de audio actuales
     */
    fun getCurrentDevices(): Pair<AudioDevice?, AudioDevice?> {
        return Pair(
            webRtcManager.getCurrentInputDevice(),
            webRtcManager.getCurrentOutputDevice()
        )
    }

    /**
     * Refrescar lista de dispositivos de audio
     */
    fun refreshAudioDevices() {
        val (inputs, outputs) = webRtcManager.getAllAudioDevices()
        audioDeviceManager.updateDevices(inputs, outputs)
    }

    /**
     * Cambiar dispositivo de audio durante llamada
     */
    fun changeAudioDevice(device: AudioDevice) {
        CoroutineScope(Dispatchers.IO).launch {
            val isInput = audioDeviceManager.inputDevices.value.contains(device)

            val success = if (isInput) {
                webRtcManager.changeAudioInputDeviceDuringCall(device)
            } else {
                webRtcManager.changeAudioOutputDeviceDuringCall(device)
            }

            if (success) {
                if (isInput) {
                    audioDeviceManager.selectInputDevice(device)
                } else {
                    audioDeviceManager.selectOutputDevice(device)
                }
            }
        }
    }

    /**
     * Silenciar/Desactivar silencio
     */
    fun toggleMute(): Boolean {
        val newMuteState = !webRtcManager.isMuted()
        webRtcManager.setMuted(newMuteState)
        return newMuteState
    }

    /**
     * Verificar si está silenciado
     */
    fun isMuted(): Boolean = webRtcManager.isMuted()

    /**
     * Habilitar/Deshabilitar audio
     */
    fun setAudioEnabled(enabled: Boolean) {
        webRtcManager.setAudioEnabled(enabled)
    }

    /**
     * Detener todos los ringtones
     */
    fun stopAllRingtones() {
        audioManager.stopAllRingtones()
    }

    /**
     * Reproducir ringtone de llamada entrante
     */
    fun playIncomingRingtone(syncVibration: Boolean = true) {
        audioManager.playRingtone(syncVibration)
    }

    /**
     * Reproducir ringtone de llamada saliente
     */
    fun playOutgoingRingtone() {
        audioManager.playOutgoingRingtone()
    }

    /**
     * Detener ringtone específico
     */
    fun stopRingtone() {
        audioManager.stopRingtone()
    }

    /**
     * Detener ringtone de llamada saliente
     */
    fun stopOutgoingRingtone() {
        audioManager.stopOutgoingRingtone()
    }

    /**
     * Configurar ringtone de llamada entrante
     */
    fun saveIncomingRingtoneUri(uri: Uri, databaseManager: DatabaseManager?) {
        try {
            CoroutineScope(Dispatchers.IO).launch {
                databaseManager?.updateIncomingRingtoneUri(uri)
                audioManager.setIncomingRingtone(uri)
                log.d(tag = TAG) { "Incoming ringtone URI saved to database: $uri" }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error saving incoming ringtone URI: ${e.message}" }
        }
    }

    /**
     * Configurar ringtone de llamada saliente
     */
    fun saveOutgoingRingtoneUri(uri: Uri, databaseManager: DatabaseManager?) {
        try {
            CoroutineScope(Dispatchers.IO).launch {
                databaseManager?.updateOutgoingRingtoneUri(uri)
                audioManager.setOutgoingRingtone(uri)
                log.d(tag = TAG) { "Outgoing ringtone URI saved to database: $uri" }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error saving outgoing ringtone URI: ${e.message}" }
        }
    }

    /**
     * Configurar ambos ringtones
     */
    suspend fun saveRingtoneUris(incomingUri: Uri?, outgoingUri: Uri?, databaseManager: DatabaseManager?) {
        try {
            databaseManager?.updateRingtoneUris(incomingUri, outgoingUri)

            incomingUri?.let { audioManager.setIncomingRingtone(it) }
            outgoingUri?.let { audioManager.setOutgoingRingtone(it) }

            log.d(tag = TAG) { "Both ringtone URIs saved to database - Incoming: $incomingUri, Outgoing: $outgoingUri" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error saving ringtone URIs: ${e.message}" }
        }
    }

    /**
     * Cargar configuración de audio desde base de datos
     */
    fun loadAudioConfigFromDatabase(config: AppConfigEntity?) {
        loadedConfig = config
        config?.let { appConfig ->
            // Aplicar ringtones
            appConfig.incomingRingtoneUri?.let { uriString ->
                try {
                    val uri = Uri.parse(uriString)
                    audioManager.setIncomingRingtone(uri)
                    log.d(tag = TAG) { "Loaded incoming ringtone from DB: $uriString" }
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error loading incoming ringtone URI: ${e.message}" }
                }
            }

            appConfig.outgoingRingtoneUri?.let { uriString ->
                try {
                    val uri = Uri.parse(uriString)
                    audioManager.setOutgoingRingtone(uri)
                    log.d(tag = TAG) { "Loaded outgoing ringtone from DB: $uriString" }
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error loading outgoing ringtone URI: ${e.message}" }
                }
            }
        }
    }

    /**
     * Crear SDP offer para llamada saliente
     */
    suspend fun createOffer(): String = webRtcManager.createOffer()

    /**
     * Crear SDP answer para llamada entrante
     */
    suspend fun createAnswer(accountInfo: AccountInfo, remoteSdp: String): String {
        return webRtcManager.createAnswer(accountInfo, remoteSdp)
    }

    /**
     * Limpiar recursos de audio
     */
    fun dispose() {
        audioManager.stopAllRingtones()
        webRtcManager.dispose()
    }

    /**
     * Verificar si WebRTC está inicializado
     */
    fun isWebRtcInitialized(): Boolean = webRtcManager.isInitialized()
}