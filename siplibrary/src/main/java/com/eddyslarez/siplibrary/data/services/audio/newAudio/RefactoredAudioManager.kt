package com.eddyslarez.siplibrary.data.services.audio.newAudio

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.annotation.RequiresPermission
import com.eddyslarez.siplibrary.data.services.audio.AudioDevice
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/**
 * Gestor principal de audio refactorizado que coordina todos los componentes
 * Reemplaza la funcionalidad de audio del AndroidWebRtcManager original
 */
class RefactoredAudioManager(private val application: Application) {

    private val context: Context = application.applicationContext
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Componentes especializados
    private val audioDeviceDetector = AudioDeviceDetector(context)
    private val bluetoothManager = BluetoothAudioManager(context)
    private val inputManager = AudioInputManager(context, bluetoothManager)
    private val outputManager = AudioOutputManager(context, bluetoothManager)
    private val streamProcessor = AudioStreamProcessor(context)

    // Estados combinados
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    // Flows observables
    val availableInputDevices = audioDeviceDetector.availableInputDevices
    val availableOutputDevices = audioDeviceDetector.availableOutputDevices
    val currentInputDevice = inputManager.currentInputDevice
    val currentOutputDevice = outputManager.currentOutputDevice
    val isBluetoothScoConnected = bluetoothManager.isBluetoothScoConnected
    val transcribedText = streamProcessor.transcribedText
    val isRecording = streamProcessor.isRecording
    val audioLevel = streamProcessor.audioLevel

    // Estado combinado de calidad de audio
    val audioQualityInfo = combine(
        inputManager.currentInputDevice,
        outputManager.currentOutputDevice
    ) { input, output ->
        AudioQualityInfo(
            inputQuality = input?.let { getInputQuality(it) } ?: AudioQuality.UNKNOWN,
            outputQuality = output?.let { getOutputQuality(it) } ?: AudioQuality.UNKNOWN,
            overallQuality = calculateOverallQuality(input, output)
        )
    }

    companion object {
        private const val TAG = "RefactoredAudioManager"
    }

    init {
        setupEventListeners()
    }

    /**
     * Inicializa el gestor de audio
     */
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
    fun initialize(): Boolean {
        log.d(TAG) { "Inicializando RefactoredAudioManager..." }

        return try {
            // Detectar dispositivos disponibles
            audioDeviceDetector.detectAllDevices()

            // Configurar dispositivos por defecto
            setupDefaultDevices()

            _isInitialized.value = true
            log.d(TAG) { "RefactoredAudioManager inicializado exitosamente" }
            true

        } catch (e: Exception) {
            log.e(TAG) { "Error inicializando RefactoredAudioManager: ${e.message}" }
            false
        }
    }

    /**
     * Configurar dispositivos por defecto
     */
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
    private fun setupDefaultDevices() {
        // Seleccionar dispositivo de entrada recomendado
        val recommendedInput = inputManager.getRecommendedInputDevice(availableInputDevices.value)
        recommendedInput?.let { inputManager.changeInputDevice(it) }

        // Seleccionar dispositivo de salida recomendado
        val recommendedOutput = outputManager.getRecommendedOutputDevice(availableOutputDevices.value)
        recommendedOutput?.let { outputManager.changeOutputDevice(it) }
    }

    /**
     * Cambia el dispositivo de entrada
     */
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
    fun changeInputDevice(device: AudioDevice): Boolean {
        log.d(TAG) { "Cambiando dispositivo de entrada a: ${device.name}" }
        return inputManager.changeInputDevice(device)
    }

    /**
     * Cambia el dispositivo de salida
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun changeOutputDevice(device: AudioDevice): Boolean {
        log.d(TAG) { "Cambiando dispositivo de salida a: ${device.name}" }
        return outputManager.changeOutputDevice(device)
    }

    /**
     * Inicia la grabación y procesamiento de audio a texto
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startAudioToTextProcessing(): Boolean {
        log.d(TAG) { "Iniciando procesamiento de audio a texto" }
        return streamProcessor.startRecording()
    }

    /**
     * Detiene la grabación y procesamiento de audio a texto
     */
    fun stopAudioToTextProcessing() {
        log.d(TAG) { "Deteniendo procesamiento de audio a texto" }
        streamProcessor.stopRecording()
    }

    /**
     * Silencia/activa el micrófono
     */
    fun setMicrophoneMuted(muted: Boolean): Boolean {
        return inputManager.setMicrophoneMuted(muted)
    }

    /**
     * Verifica si el micrófono está silenciado
     */
    fun isMicrophoneMuted(): Boolean {
        return inputManager.isMicrophoneMuted()
    }

    /**
     * Ajusta el volumen de salida
     */
    fun setOutputVolume(volume: Int): Boolean {
        return outputManager.setVolume(volume)
    }

    /**
     * Obtiene el volumen actual
     */
    fun getCurrentVolume(): Int {
        return outputManager.getCurrentVolume()
    }

    /**
     * Reescanea dispositivos de audio disponibles
     */
    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.RECORD_AUDIO])
    fun refreshAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        log.d(TAG) { "Refrescando dispositivos de audio..." }
        return audioDeviceDetector.detectAllDevices()
    }

    /**
     * Obtiene información de diagnóstico completa
     */
    fun getCompleteDiagnosticInfo(): String {
        return buildString {
            appendLine("=== REFACTORED AUDIO MANAGER DIAGNOSTICS ===")
            appendLine("Inicializado: ${_isInitialized.value}")
            appendLine("Grabando: ${isRecording.value}")
            appendLine("Nivel de audio: ${audioLevel.value}")
            appendLine("Bluetooth SCO: ${isBluetoothScoConnected.value}")
            appendLine()

//            appendLine(audioDeviceDetector.getDiagnosticInfo())
            appendLine()
            appendLine(bluetoothManager.getDiagnosticInfo())
            appendLine()
            appendLine(inputManager.getDiagnosticInfo())
            appendLine()
            appendLine(outputManager.getDiagnosticInfo())
            appendLine()
            appendLine(streamProcessor.getDiagnosticInfo())
        }
    }

    /**
     * Configurar listeners para eventos de audio
     */
    private fun setupEventListeners() {
        // Listener para cambios de dispositivos
        audioDeviceDetector.addDeviceChangeListener { inputs, outputs ->
            log.d(TAG) { "Dispositivos cambiados - Entradas: ${inputs.size}, Salidas: ${outputs.size}" }
        }

        // Listener para transcripciones
        streamProcessor.addTranscriptionCallback { text ->
            log.d(TAG) { "Transcripción: $text" }
        }

        // Listener para errores de audio
        streamProcessor.addErrorCallback { error ->
            log.e(TAG) { "Error de procesamiento: $error" }
        }
    }

    /**
     * Añadir callback para transcripciones
     */
    fun addTranscriptionCallback(callback: (String) -> Unit) {
        streamProcessor.addTranscriptionCallback(callback)
    }

    /**
     * Remover callback para transcripciones
     */
    fun removeTranscriptionCallback(callback: (String) -> Unit) {
        streamProcessor.removeTranscriptionCallback(callback)
    }

    /**
     * Añadir callback para nivel de audio
     */
    fun addAudioLevelCallback(callback: (Float) -> Unit) {
        streamProcessor.addAudioLevelCallback(callback)
    }

    /**
     * Remover callback para nivel de audio
     */
    fun removeAudioLevelCallback(callback: (Float) -> Unit) {
        streamProcessor.removeAudioLevelCallback(callback)
    }

    /**
     * Obtiene estadísticas de audio
     */
    fun getAudioStats(): AudioStats {
        return streamProcessor.getAudioStats()
    }

    /**
     * Restaura configuración por defecto
     */
    fun restoreDefaults() {
        log.d(TAG) { "Restaurando configuración por defecto" }
        inputManager.restoreDefaults()
        outputManager.restoreDefaults()
        streamProcessor.clearTranscription()
    }

    // Métodos helper para calidad de audio
    private fun getInputQuality(device: AudioDevice): AudioQuality {
        return when {
            device.supportsHDVoice -> AudioQuality.HD
            device.isBluetooth -> AudioQuality.GOOD
            device.descriptor == "wired_headset_mic" -> AudioQuality.GOOD
            else -> AudioQuality.STANDARD
        }
    }

    private fun getOutputQuality(device: AudioDevice): AudioQuality {
        return when {
            device.supportsHDVoice -> AudioQuality.HD
            device.isBluetooth -> AudioQuality.GOOD
            device.descriptor == "wired_headset" -> AudioQuality.GOOD
            else -> AudioQuality.STANDARD
        }
    }

    private fun calculateOverallQuality(input: AudioDevice?, output: AudioDevice?): AudioQuality {
        val inputQuality = input?.let { getInputQuality(it) } ?: AudioQuality.UNKNOWN
        val outputQuality = output?.let { getOutputQuality(it) } ?: AudioQuality.UNKNOWN

        return when {
            inputQuality == AudioQuality.HD && outputQuality == AudioQuality.HD -> AudioQuality.HD
            inputQuality == AudioQuality.GOOD || outputQuality == AudioQuality.GOOD -> AudioQuality.GOOD
            inputQuality == AudioQuality.STANDARD || outputQuality == AudioQuality.STANDARD -> AudioQuality.STANDARD
            else -> AudioQuality.UNKNOWN
        }
    }

    /**
     * Limpieza de recursos
     */
    fun dispose() {
        log.d(TAG) { "Disposing RefactoredAudioManager..." }

        try {
            // Detener procesamiento de audio
            streamProcessor.stopRecording()

            // Restaurar configuración
            restoreDefaults()

            // Limpiar componentes
            audioDeviceDetector.dispose()
            bluetoothManager.dispose()
            streamProcessor.dispose()

            _isInitialized.value = false
            log.d(TAG) { "RefactoredAudioManager disposed successfully" }

        } catch (e: Exception) {
            log.e(TAG) { "Error disposing RefactoredAudioManager: ${e.message}" }
        }
    }
}

/**
 * Información de calidad de audio combinada
 */
data class AudioQualityInfo(
    val inputQuality: AudioQuality,
    val outputQuality: AudioQuality,
    val overallQuality: AudioQuality
)