package com.eddyslarez.siplibrary.data.services.audio.newAudio

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresPermission
import com.eddyslarez.siplibrary.data.services.audio.AudioDevice
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Gestor especializado para audio Bluetooth
 * Maneja las conexiones SCO y la gestión de dispositivos Bluetooth de audio
 */
@SuppressLint("MissingPermission")
class BluetoothAudioManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter = bluetoothManager?.adapter

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Estados de conexión Bluetooth
    private val _isBluetoothScoConnected = MutableStateFlow(false)
    private val _isBluetoothScoRequested = MutableStateFlow(false)
    private val _connectedBluetoothDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())

    val isBluetoothScoConnected: StateFlow<Boolean> = _isBluetoothScoConnected.asStateFlow()
    val isBluetoothScoRequested: StateFlow<Boolean> = _isBluetoothScoRequested.asStateFlow()
    val connectedBluetoothDevices: StateFlow<List<BluetoothDevice>> = _connectedBluetoothDevices.asStateFlow()

    private var bluetoothScoReceiver: BroadcastReceiver? = null
    private var bluetoothConnectionReceiver: BroadcastReceiver? = null

    // Callbacks
    private val scoStateChangeCallbacks = mutableListOf<(Boolean) -> Unit>()
    private val deviceConnectionCallbacks = mutableListOf<(BluetoothDevice, Boolean) -> Unit>()

    companion object {
        private const val TAG = "BluetoothAudioManager"
        private const val SCO_CONNECTION_TIMEOUT = 3000L // 3 segundos
    }

    init {
        setupBluetoothReceivers()
        updateConnectedDevices()
    }

    /**
     * Configura los receivers para monitorear Bluetooth
     */
    private fun setupBluetoothReceivers() {
        // Receiver para cambios de estado SCO
        bluetoothScoReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                        val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                        handleScoStateChange(state)
                    }
                }
            }
        }

        // Receiver para conexiones de dispositivos Bluetooth
        bluetoothConnectionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        device?.let { handleDeviceConnected(it) }
                    }
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        device?.let { handleDeviceDisconnected(it) }
                    }
                }
            }
        }

        // Registrar receivers
        val scoFilter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        context.registerReceiver(bluetoothScoReceiver, scoFilter)

        val connectionFilter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        context.registerReceiver(bluetoothConnectionReceiver, connectionFilter)
    }

    /**
     * Maneja cambios en el estado SCO
     */
    private fun handleScoStateChange(state: Int) {
        when (state) {
            AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                log.d(TAG) { "Bluetooth SCO conectado" }
                _isBluetoothScoConnected.value = true
                _isBluetoothScoRequested.value = false
                notifyScoStateChanged(true)
            }
            AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                log.d(TAG) { "Bluetooth SCO desconectado" }
                _isBluetoothScoConnected.value = false
                _isBluetoothScoRequested.value = false
                notifyScoStateChanged(false)
            }
            AudioManager.SCO_AUDIO_STATE_CONNECTING -> {
                log.d(TAG) { "Bluetooth SCO conectando..." }
            }
            AudioManager.SCO_AUDIO_STATE_ERROR -> {
                log.e(TAG) { "Error en Bluetooth SCO" }
                _isBluetoothScoConnected.value = false
                _isBluetoothScoRequested.value = false
                notifyScoStateChanged(false)
            }
        }
    }

    /**
     * Conecta audio Bluetooth (SCO)
     */
    fun connectBluetoothAudio(device: AudioDevice): Boolean {
        log.d(TAG) { "Conectando audio Bluetooth: ${device.name}" }

        if (!audioManager.isBluetoothScoAvailableOffCall) {
            log.w(TAG) { "Bluetooth SCO no disponible" }
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                log.w(TAG) { "Permiso BLUETOOTH_CONNECT no concedido" }
                return false
            }
        }

        // Si ya está conectado, no hacer nada
        if (_isBluetoothScoConnected.value) {
            log.d(TAG) { "Bluetooth SCO ya conectado" }
            return true
        }

        // Si ya se solicitó conexión, esperar
        if (_isBluetoothScoRequested.value) {
            log.d(TAG) { "Conexión Bluetooth SCO ya solicitada" }
            return false
        }

        return try {
            // Configurar modo de audio
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = false

            // Solicitar conexión SCO
            _isBluetoothScoRequested.value = true
            audioManager.startBluetoothSco()

            // Timeout para la conexión
            scope.launch {
                delay(SCO_CONNECTION_TIMEOUT)
                if (!_isBluetoothScoConnected.value && _isBluetoothScoRequested.value) {
                    log.w(TAG) { "Timeout conectando Bluetooth SCO" }
                    _isBluetoothScoRequested.value = false
                }
            }

            true
        } catch (e: Exception) {
            log.e(TAG) { "Error conectando Bluetooth audio: ${e.message}" }
            _isBluetoothScoRequested.value = false
            false
        }
    }

    /**
     * Desconecta audio Bluetooth (SCO)
     */
    fun disconnectBluetoothAudio(): Boolean {
        log.d(TAG) { "Desconectando audio Bluetooth" }

        return try {
            if (_isBluetoothScoConnected.value || _isBluetoothScoRequested.value) {
                audioManager.stopBluetoothSco()
                _isBluetoothScoRequested.value = false
            }
            true
        } catch (e: Exception) {
            log.e(TAG) { "Error desconectando Bluetooth audio: ${e.message}" }
            false
        }
    }

    /**
     * Obtiene dispositivos Bluetooth conectados
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun getConnectedBluetoothAudioDevices(): List<BluetoothDevice> {
        return try {
            bluetoothAdapter?.bondedDevices?.filter { device ->
                hasAudioService(device) && isDeviceConnected(device)
            } ?: emptyList()
        } catch (e: SecurityException) {
            log.w(TAG) { "Permiso Bluetooth no concedido: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Verifica si un dispositivo tiene servicio de audio
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun hasAudioService(device: BluetoothDevice): Boolean {
        return try {
            device.bluetoothClass?.hasService(android.bluetooth.BluetoothClass.Service.AUDIO) == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Verifica si un dispositivo está conectado
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun isDeviceConnected(device: BluetoothDevice): Boolean {
        return try {
            val connectionState = bluetoothManager?.getConnectionState(device, BluetoothProfile.HEADSET)
            connectionState == BluetoothProfile.STATE_CONNECTED ||
                    bluetoothManager?.getConnectionState(device, BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
        } catch (e: Exception) {
            device.bondState == BluetoothDevice.BOND_BONDED
        }
    }

    /**
     * Maneja dispositivo conectado
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun handleDeviceConnected(device: BluetoothDevice) {
        if (hasAudioService(device)) {
            log.d(TAG) { "Dispositivo Bluetooth de audio conectado: ${getDeviceName(device)}" }
            updateConnectedDevices()
            notifyDeviceConnectionChanged(device, true)
        }
    }

    /**
     * Maneja dispositivo desconectado
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun handleDeviceDisconnected(device: BluetoothDevice) {
        if (hasAudioService(device)) {
            log.d(TAG) { "Dispositivo Bluetooth de audio desconectado: ${getDeviceName(device)}" }
            updateConnectedDevices()
            notifyDeviceConnectionChanged(device, false)

            // Si era el dispositivo actual, desconectar SCO
            if (_isBluetoothScoConnected.value) {
                disconnectBluetoothAudio()
            }
        }
    }

    /**
     * Actualiza la lista de dispositivos conectados
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun updateConnectedDevices() {
        if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            _connectedBluetoothDevices.value = getConnectedBluetoothAudioDevices()
        }
    }

    /**
     * Obtiene el nombre de un dispositivo Bluetooth
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun getDeviceName(device: BluetoothDevice): String {
        return try {
            device.name ?: "Dispositivo Bluetooth"
        } catch (e: SecurityException) {
            "Dispositivo Bluetooth"
        }
    }

    /**
     * Verifica si Bluetooth está disponible y habilitado
     */
    fun isBluetoothAvailable(): Boolean {
        return bluetoothAdapter?.isEnabled == true && audioManager.isBluetoothScoAvailableOffCall
    }

    /**
     * Obtiene información de batería de un dispositivo
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun getDeviceBatteryLevel(device: BluetoothDevice): Int? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val method = BluetoothDevice::class.java.getMethod("getBatteryLevel")
                val battery = method.invoke(device) as? Int
                battery?.takeIf { it in 0..100 }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Verifica si un dispositivo soporta A2DP
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun supportsA2DP(device: BluetoothDevice): Boolean {
        return try {
            bluetoothAdapter?.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothAdapter.STATE_CONNECTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Verifica si un dispositivo soporta HFP
     */
    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun supportsHFP(device: BluetoothDevice): Boolean {
        return try {
            bluetoothAdapter?.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothAdapter.STATE_CONNECTED
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Callbacks y listeners
     */
    fun addScoStateChangeCallback(callback: (Boolean) -> Unit) {
        scoStateChangeCallbacks.add(callback)
    }

    fun removeScoStateChangeCallback(callback: (Boolean) -> Unit) {
        scoStateChangeCallbacks.remove(callback)
    }

    fun addDeviceConnectionCallback(callback: (BluetoothDevice, Boolean) -> Unit) {
        deviceConnectionCallbacks.add(callback)
    }

    fun removeDeviceConnectionCallback(callback: (BluetoothDevice, Boolean) -> Unit) {
        deviceConnectionCallbacks.remove(callback)
    }

    private fun notifyScoStateChanged(connected: Boolean) {
        scoStateChangeCallbacks.forEach { callback ->
            try {
                callback(connected)
            } catch (e: Exception) {
                log.e(TAG) { "Error en callback SCO: ${e.message}" }
            }
        }
    }

    private fun notifyDeviceConnectionChanged(device: BluetoothDevice, connected: Boolean) {
        deviceConnectionCallbacks.forEach { callback ->
            try {
                callback(device, connected)
            } catch (e: Exception) {
                log.e(TAG) { "Error en callback conexión dispositivo: ${e.message}" }
            }
        }
    }

    /**
     * Obtiene información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== BLUETOOTH AUDIO MANAGER ===")
            appendLine("Bluetooth disponible: ${isBluetoothAvailable()}")
            appendLine("SCO conectado: ${_isBluetoothScoConnected.value}")
            appendLine("SCO solicitado: ${_isBluetoothScoRequested.value}")
            appendLine("SCO disponible: ${audioManager.isBluetoothScoAvailableOffCall}")
            appendLine("A2DP activo: ${audioManager.isBluetoothA2dpOn}")
            appendLine("Dispositivos conectados: ${_connectedBluetoothDevices.value.size}")

            if (hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                _connectedBluetoothDevices.value.forEach { device ->
                    try {
                        appendLine("- ${device.name} (${device.address})")
                    } catch (e: SecurityException) {
                        appendLine("- Dispositivo sin permisos")
                    }
                }
            }
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(context, permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * Limpieza de recursos
     */
    fun dispose() {
        try {
            // Desconectar SCO si está conectado
            disconnectBluetoothAudio()

            // Desregistrar receivers
            bluetoothScoReceiver?.let { receiver ->
                context.unregisterReceiver(receiver)
            }
            bluetoothConnectionReceiver?.let { receiver ->
                context.unregisterReceiver(receiver)
            }

            // Limpiar callbacks
            scoStateChangeCallbacks.clear()
            deviceConnectionCallbacks.clear()

        } catch (e: Exception) {
            log.e(TAG) { "Error disposing BluetoothAudioManager: ${e.message}" }
        }
    }
}