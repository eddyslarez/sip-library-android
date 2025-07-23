package com.eddyslarez.siplibrary.data.services.audio.newAudio

import android.Manifest
import android.content.Context
import android.media.AudioManager
import androidx.annotation.RequiresPermission
import com.eddyslarez.siplibrary.data.services.audio.AudioDevice
import com.eddyslarez.siplibrary.data.services.audio.DeviceConnectionState
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Gestor especializado para dispositivos de entrada de audio
 */
class AudioInputManager(
    private val context: Context,
    private val bluetoothManager: BluetoothAudioManager
) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Estado actual del dispositivo de entrada
    private val _currentInputDevice = MutableStateFlow<AudioDevice?>(null)
    val currentInputDevice: StateFlow<AudioDevice?> = _currentInputDevice.asStateFlow()

    // Listeners para cambios
    private val inputChangeListeners = mutableListOf<(AudioDevice?) -> Unit>()

    companion object {
        private const val TAG = "AudioInputManager"
    }

    /**
     * Cambia el dispositivo de entrada de audio
     */
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
    fun changeInputDevice(device: AudioDevice): Boolean {
        log.d(TAG) { "Cambiando dispositivo de entrada a: ${device.name}" }

        if (!device.canRecord) {
            log.w(TAG) { "El dispositivo no puede grabar audio" }
            return false
        }

        val success = when {
            device.descriptor.startsWith("bluetooth_mic_") -> {
                switchToBluetoothInput(device)
            }
            device.descriptor == "wired_headset_mic" -> {
                switchToWiredHeadsetMic()
            }
            device.descriptor == "builtin_mic" -> {
                switchToBuiltinMic()
            }
            device.descriptor.startsWith("usb_in_") -> {
                switchToUsbInput(device)
            }
            else -> {
                log.w(TAG) { "Tipo de dispositivo de entrada desconocido: ${device.descriptor}" }
                false
            }
        }

        if (success) {
            _currentInputDevice.value = device
            notifyInputDeviceChanged(device)
            log.d(TAG) { "Cambio exitoso a: ${device.name}" }
        }

        return success
    }

    /**
     * Cambia a entrada Bluetooth
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun switchToBluetoothInput(device: AudioDevice): Boolean {
        log.d(TAG) { "Cambiando a entrada Bluetooth: ${device.name}" }

        if (!bluetoothManager.isBluetoothAvailable()) {
            log.w(TAG) { "Bluetooth no disponible" }
            return false
        }

        return try {
            // Configurar modo de comunicación
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            // Conectar audio Bluetooth
            val connected = bluetoothManager.connectBluetoothAudio(device)
            if (connected) {
                log.d(TAG) { "Audio Bluetooth conectado para entrada" }
            }

            connected
        } catch (e: Exception) {
            log.e(TAG) { "Error cambiando a entrada Bluetooth: ${e.message}" }
            false
        }
    }

    /**
     * Cambia a micrófono de auriculares con cable
     */
    private fun switchToWiredHeadsetMic(): Boolean {
        log.d(TAG) { "Cambiando a micrófono de auriculares con cable" }

        if (!audioManager.isWiredHeadsetOn) {
            log.w(TAG) { "Auriculares con cable no conectados" }
            return false
        }

        return try {
            // Desconectar Bluetooth si está activo
            bluetoothManager.disconnectBluetoothAudio()

            // Configurar para auriculares con cable
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false

            log.d(TAG) { "Cambiado a micrófono de auriculares con cable" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error cambiando a auriculares con cable: ${e.message}" }
            false
        }
    }

    /**
     * Cambia a micrófono integrado
     */
    private fun switchToBuiltinMic(): Boolean {
        log.d(TAG) { "Cambiando a micrófono integrado" }

        return try {
            // Desconectar Bluetooth si está activo
            bluetoothManager.disconnectBluetoothAudio()

            // Configurar para micrófono integrado
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            log.d(TAG) { "Cambiado a micrófono integrado" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error cambiando a micrófono integrado: ${e.message}" }
            false
        }
    }

    /**
     * Cambia a entrada USB
     */
    private fun switchToUsbInput(device: AudioDevice): Boolean {
        log.d(TAG) { "Cambiando a entrada USB: ${device.name}" }

        return try {
            // Desconectar Bluetooth si está activo
            bluetoothManager.disconnectBluetoothAudio()

            // Para dispositivos USB, el routing es automático en la mayoría de casos
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            log.d(TAG) { "Cambiado a entrada USB" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error cambiando a entrada USB: ${e.message}" }
            false
        }
    }

    /**
     * Silencia o desmutea el micrófono
     */
    fun setMicrophoneMuted(muted: Boolean): Boolean {
        return try {
            audioManager.isMicrophoneMute = muted
            log.d(TAG) { "Micrófono ${if (muted) "silenciado" else "activado"}" }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error cambiando estado del micrófono: ${e.message}" }
            false
        }
    }

    /**
     * Verifica si el micrófono está silenciado
     */
    fun isMicrophoneMuted(): Boolean {
        return audioManager.isMicrophoneMute
    }

    /**
     * Obtiene el dispositivo de entrada recomendado
     */
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
    fun getRecommendedInputDevice(availableDevices: List<AudioDevice>): AudioDevice? {
        val inputDevices = availableDevices.filter { it.canRecord && it.connectionState == DeviceConnectionState.AVAILABLE }

        // Prioridad: Bluetooth conectado > Auriculares con cable > Micrófono integrado
        return inputDevices.firstOrNull { it.isBluetooth && it.connectionState == DeviceConnectionState.CONNECTED }
            ?: inputDevices.firstOrNull { it.descriptor == "wired_headset_mic" }
            ?: inputDevices.firstOrNull { it.descriptor == "builtin_mic" }
    }

    /**
     * Verifica la calidad del audio de entrada
     */
    fun checkInputQuality(): AudioQuality {
        val currentDevice = _currentInputDevice.value

        return when {
            currentDevice == null -> AudioQuality.UNKNOWN
            currentDevice.supportsHDVoice -> AudioQuality.HD
            currentDevice.isBluetooth -> AudioQuality.GOOD
            currentDevice.descriptor == "wired_headset_mic" -> AudioQuality.GOOD
            currentDevice.descriptor == "builtin_mic" -> AudioQuality.STANDARD
            else -> AudioQuality.STANDARD
        }
    }

    /**
     * Obtiene información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== AUDIO INPUT MANAGER ===")
            appendLine("Dispositivo actual: ${_currentInputDevice.value?.name ?: "Ninguno"}")
            appendLine("Micrófono silenciado: ${isMicrophoneMuted()}")
            appendLine("Modo de audio: ${audioManager.mode}")
            appendLine("Calidad de entrada: ${checkInputQuality()}")

            _currentInputDevice.value?.let { device ->
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
    fun addInputChangeListener(listener: (AudioDevice?) -> Unit) {
        inputChangeListeners.add(listener)
    }

    fun removeInputChangeListener(listener: (AudioDevice?) -> Unit) {
        inputChangeListeners.remove(listener)
    }

    private fun notifyInputDeviceChanged(device: AudioDevice?) {
        inputChangeListeners.forEach { listener ->
            try {
                listener(device)
            } catch (e: Exception) {
                log.e(TAG) { "Error en listener de cambio de entrada: ${e.message}" }
            }
        }
    }

    /**
     * Restaura configuración de audio por defecto
     */
    fun restoreDefaults() {
        try {
            audioManager.isMicrophoneMute = false
            _currentInputDevice.value = null
            log.d(TAG) { "Configuración de entrada restaurada" }
        } catch (e: Exception) {
            log.e(TAG) { "Error restaurando configuración: ${e.message}" }
        }
    }
}

/**
 * Enumeration para calidad de audio
 */
enum class AudioQuality {
    UNKNOWN,
    POOR,
    STANDARD,
    GOOD,
    HD
}