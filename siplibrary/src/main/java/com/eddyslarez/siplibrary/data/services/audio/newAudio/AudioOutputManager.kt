package com.eddyslarez.siplibrary.data.services.audio.newAudio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.annotation.RequiresPermission
import com.eddyslarez.siplibrary.data.services.audio.AudioDevice
import com.eddyslarez.siplibrary.data.services.audio.DeviceConnectionState
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Gestor especializado para dispositivos de salida de audio
 */
class AudioOutputManager(
    private val context: Context,
    private val bluetoothManager: BluetoothAudioManager
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Estado actual del dispositivo de salida
    private val _currentOutputDevice = MutableStateFlow<AudioDevice?>(null)
    val currentOutputDevice: StateFlow<AudioDevice?> = _currentOutputDevice.asStateFlow()

    // Listeners para cambios
    private val outputChangeListeners = mutableListOf<(AudioDevice?) -> Unit>()

    companion object {
        private const val TAG = "AudioOutputManager"
    }

    /**
     * Cambia el dispositivo de salida de audio
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun changeOutputDevice(device: AudioDevice): Boolean {
        log.d(TAG) { "Cambiando dispositivo de salida a: ${device.name}" }

        if (!device.canPlay) {
            log.w(TAG) { "El dispositivo no puede reproducir audio" }
            return false
        }

        val success = when {
            device.descriptor.startsWith("bluetooth_") -> {
                switchToBluetoothOutput(device)
            }
            device.descriptor == "speaker" -> {
                switchToSpeaker()
            }
            device.descriptor == "wired_headset" -> {
                switchToWiredHeadset()
            }
            device.descriptor == "earpiece" -> {
                switchToEarpiece()
            }
            device.descriptor.startsWith("usb_out_") -> {
                switchToUsbOutput(device)
            }
            else -> {
                log.w(TAG) { "Tipo de dispositivo de salida desconocido: ${device.descriptor}" }
                false
            }
        }

        if (success) {
            _currentOutputDevice.value = device
            notifyOutputDeviceChanged(device)
            log.d(TAG) { "Cambio exitoso a: ${device.name}" }
        }

        return success
    }

    /**
     * Cambia a salida Bluetooth
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun switchToBluetoothOutput(device: AudioDevice): Boolean {
        log.d(TAG) { "Cambiando a salida Bluetooth: ${device.name}" }

        if (!bluetoothManager.isBluetoothAvailable()) {
            log.w(TAG) { "Bluetooth no disponible" }
            return false
        }

        return try {
            // Desactivar altavoz
            audioManager.isSpeakerphoneOn = false

            // Configurar modo de comunicación
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            // Conectar audio Bluetooth
            val connected = bluetoothManager.connectBluetoothAudio(device)
            if (connected) {
                log.d(TAG) { "Audio Bluetooth conectado para salida" }
            }

            connected
        } catch (e: Exception) {
            log.e(TAG) { "Error cambiando a salida Bluetooth: ${e.message}" }
            false
        }
    }

    /**
     * Cambia a altavoz
     */
    private fun switchToSpeaker(): Boolean {
        log.d(TAG) { "Cambiando a altavoz" }

        return try {
            // Desconectar Bluetooth si está activo
            bluetoothManager.disconnectBluetoothAudio()

            // Activar altavoz
            audioManager.isSpeakerphoneOn = true
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            log.d(TAG) { "Cambiado a altavoz" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error cambiando a altavoz: ${e.message}" }
            false
        }
    }

    /**
     * Cambia a auriculares con cable
     */
    private fun switchToWiredHeadset(): Boolean {
        log.d(TAG) { "Cambiando a auriculares con cable" }

        if (!audioManager.isWiredHeadsetOn) {
            log.w(TAG) { "Auriculares con cable no conectados" }
            return false
        }

        return try {
            // Desconectar Bluetooth y desactivar altavoz
            bluetoothManager.disconnectBluetoothAudio()
            audioManager.isSpeakerphoneOn = false

            // Configurar para auriculares con cable
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            log.d(TAG) { "Cambiado a auriculares con cable" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error cambiando a auriculares con cable: ${e.message}" }
            false
        }
    }

    /**
     * Cambia a auricular (earpiece)
     */
    private fun switchToEarpiece(): Boolean {
        log.d(TAG) { "Cambiando a auricular" }

        if (!hasEarpiece()) {
            log.w(TAG) { "Dispositivo no tiene auricular" }
            return false
        }

        return try {
            // Desconectar Bluetooth y desactivar altavoz
            bluetoothManager.disconnectBluetoothAudio()
            audioManager.isSpeakerphoneOn = false

            // Configurar para auricular
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            log.d(TAG) { "Cambiado a auricular" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error cambiando a auricular: ${e.message}" }
            false
        }
    }

    /**
     * Cambia a salida USB
     */
    private fun switchToUsbOutput(device: AudioDevice): Boolean {
        log.d(TAG) { "Cambiando a salida USB: ${device.name}" }

        return try {
            // Desconectar Bluetooth y desactivar altavoz
            bluetoothManager.disconnectBluetoothAudio()
            audioManager.isSpeakerphoneOn = false

            // Para dispositivos USB, el routing es automático
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            log.d(TAG) { "Cambiado a salida USB" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error cambiando a salida USB: ${e.message}" }
            false
        }
    }

    /**
     * Obtiene el dispositivo de salida recomendado
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun getRecommendedOutputDevice(availableDevices: List<AudioDevice>): AudioDevice? {
        val outputDevices = availableDevices.filter { it.canPlay && it.connectionState == DeviceConnectionState.AVAILABLE }

        // Prioridad: Bluetooth conectado > Auriculares con cable > Auricular > Altavoz
        return outputDevices.firstOrNull { it.isBluetooth && it.connectionState == DeviceConnectionState.CONNECTED }
            ?: outputDevices.firstOrNull { it.descriptor == "wired_headset" }
            ?: outputDevices.firstOrNull { it.descriptor == "earpiece" }
            ?: outputDevices.firstOrNull { it.descriptor == "speaker" }
    }

    /**
     * Ajusta el volumen del dispositivo actual
     */
    fun setVolume(volume: Int): Boolean {
        if (volume !in 0..100) {
            log.w(TAG) { "Volumen fuera de rango: $volume" }
            return false
        }

        return try {
            val streamType = when {
                bluetoothManager.isBluetoothScoConnected.value -> AudioManager.STREAM_VOICE_CALL
                audioManager.isSpeakerphoneOn -> AudioManager.STREAM_VOICE_CALL
                else -> AudioManager.STREAM_VOICE_CALL
            }

            val maxVolume = audioManager.getStreamMaxVolume(streamType)
            val targetVolume = (volume * maxVolume) / 100

            audioManager.setStreamVolume(streamType, targetVolume, 0)
            log.d(TAG) { "Volumen ajustado a: $volume%" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error ajustando volumen: ${e.message}" }
            false
        }
    }

    /**
     * Obtiene el volumen actual
     */
    fun getCurrentVolume(): Int {
        return try {
            val streamType = AudioManager.STREAM_VOICE_CALL
            val currentVolume = audioManager.getStreamVolume(streamType)
            val maxVolume = audioManager.getStreamMaxVolume(streamType)

            if (maxVolume > 0) {
                (currentVolume * 100) / maxVolume
            } else {
                0
            }
        } catch (e: Exception) {
            log.e(TAG) { "Error obteniendo volumen: ${e.message}" }
            0
        }
    }

    /**
     * Verifica la calidad del audio de salida
     */
    fun checkOutputQuality(): AudioQuality {
        val currentDevice = _currentOutputDevice.value

        return when {
            currentDevice == null -> AudioQuality.UNKNOWN
            currentDevice.supportsHDVoice -> AudioQuality.HD
            currentDevice.isBluetooth -> AudioQuality.GOOD
            currentDevice.descriptor == "wired_headset" -> AudioQuality.GOOD
            currentDevice.descriptor == "earpiece" -> AudioQuality.STANDARD
            currentDevice.descriptor == "speaker" -> AudioQuality.STANDARD
            else -> AudioQuality.STANDARD
        }
    }

    /**
     * Verifica si el dispositivo tiene auricular
     */
    private fun hasEarpiece(): Boolean {
        return context.packageManager?.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ?: false
    }

    /**
     * Obtiene información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== AUDIO OUTPUT MANAGER ===")
            appendLine("Dispositivo actual: ${_currentOutputDevice.value?.name ?: "Ninguno"}")
            appendLine("Altavoz activo: ${audioManager.isSpeakerphoneOn}")
            appendLine("Volumen actual: ${getCurrentVolume()}%")
            appendLine("Modo de audio: ${audioManager.mode}")
            appendLine("Calidad de salida: ${checkOutputQuality()}")
            appendLine("Tiene auricular: ${hasEarpiece()}")

            _currentOutputDevice.value?.let { device ->
                appendLine("\n--- Dispositivo Actual ---")
                appendLine("Nombre: ${device.name}")
                appendLine("Descriptor: ${device.descriptor}")
                appendLine("Inalámbrico: ${device.isWireless}")
                appendLine("HD Voice: ${device.supportsHDVoice}")
                appendLine("Latencia: ${device.latency}ms")
            }
        }
    }

    /**
     * Notificaciones y listeners
     */
    fun addOutputChangeListener(listener: (AudioDevice?) -> Unit) {
        outputChangeListeners.add(listener)
    }

    fun removeOutputChangeListener(listener: (AudioDevice?) -> Unit) {
        outputChangeListeners.remove(listener)
    }

    private fun notifyOutputDeviceChanged(device: AudioDevice?) {
        outputChangeListeners.forEach { listener ->
            try {
                listener(device)
            } catch (e: Exception) {
                log.e(TAG) { "Error en listener de cambio de salida: ${e.message}" }
            }
        }
    }

    /**
     * Restaura configuración de audio por defecto
     */
    fun restoreDefaults() {
        try {
            audioManager.isSpeakerphoneOn = false
            _currentOutputDevice.value = null
            log.d(TAG) { "Configuración de salida restaurada" }
        } catch (e: Exception) {
            log.e(TAG) { "Error restaurando configuración: ${e.message}" }
        }
    }
}