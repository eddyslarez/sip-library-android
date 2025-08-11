package com.eddyslarez.siplibrary.data.services.network

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * Monitor de red avanzado para detectar cambios de conectividad
 *
 * @author Eddys Larez
 */
class NetworkMonitor(private val application: Application) {

    private val TAG = "NetworkMonitor"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Estados de red
    private val _networkStateFlow = MutableStateFlow(NetworkState.UNKNOWN)
    val networkStateFlow: StateFlow<NetworkState> = _networkStateFlow.asStateFlow()

    private val _networkInfoFlow = MutableStateFlow(NetworkInfo())
    val networkInfoFlow: StateFlow<NetworkInfo> = _networkInfoFlow.asStateFlow()

    // Callback de red
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Listeners
    private val networkChangeListeners = mutableSetOf<NetworkChangeListener>()

    // Estado interno
    private var currentNetwork: Network? = null
    private var lastNetworkType: NetworkType = NetworkType.NONE
    private var lastConnectionTime = 0L
    private var reconnectionAttempts = 0
    private var isMonitoring = false

    // Configuración
    private val maxReconnectionAttempts = 10
    private val baseReconnectionDelay = 1000L // 1 segundo
    private val maxReconnectionDelay = 30000L // 30 segundos

    data class NetworkInfo(
        val isConnected: Boolean = false,
        val networkType: NetworkType = NetworkType.NONE,
        val networkName: String = "",
        val ipAddress: String = "",
        val hasInternet: Boolean = false,
        val isMetered: Boolean = false,
        val linkSpeed: Int = 0,
        val signalStrength: Int = 0,
        val timestamp: Long = Clock.System.now().toEpochMilliseconds()
    )

    enum class NetworkState {
        UNKNOWN,
        CONNECTED,
        DISCONNECTED,
        CONNECTING,
        LOST,
        AVAILABLE,
        CHANGED
    }

    enum class NetworkType {
        NONE,
        WIFI,
        CELLULAR,
        ETHERNET,
        VPN,
        OTHER
    }

    interface NetworkChangeListener {
        fun onNetworkConnected(networkInfo: NetworkInfo)
        fun onNetworkDisconnected(previousNetworkInfo: NetworkInfo)
        fun onNetworkChanged(oldNetworkInfo: NetworkInfo, newNetworkInfo: NetworkInfo)
        fun onNetworkLost(networkInfo: NetworkInfo)
        fun onInternetConnectivityChanged(hasInternet: Boolean)
    }

    /**
     * Inicia el monitoreo de red
     */
    fun startMonitoring() {
        if (isMonitoring) {
            log.d(tag = TAG) { "Network monitoring already started" }
            return
        }

        log.d(tag = TAG) { "Starting network monitoring..." }

        // Verificar estado inicial
        updateCurrentNetworkInfo()

        // Configurar callback de red
        setupNetworkCallback()

        isMonitoring = true
        log.d(tag = TAG) { "Network monitoring started successfully" }
    }

    /**
     * Detiene el monitoreo de red
     */
    fun stopMonitoring() {
        if (!isMonitoring) return

        log.d(tag = TAG) { "Stopping network monitoring..." }

        networkCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error unregistering network callback: ${e.message}" }
            }
        }

        networkCallback = null
        isMonitoring = false

        log.d(tag = TAG) { "Network monitoring stopped" }
    }

    /**
     * Configura el callback de red
     */
    private fun setupNetworkCallback() {
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                log.d(tag = TAG) { "Network available: $network" }

                scope.launch {
                    delay(500) // Pequeño delay para que la red se estabilice
                    handleNetworkAvailable(network)
                }
            }

            override fun onLost(network: Network) {
                log.d(tag = TAG) { "Network lost: $network" }
                handleNetworkLost(network)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                log.d(tag = TAG) { "Network capabilities changed: $network" }
                handleNetworkCapabilitiesChanged(network, networkCapabilities)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) {
                log.d(tag = TAG) { "Network link properties changed: $network" }
                scope.launch {
                    delay(200) // Pequeño delay para que los cambios se apliquen
                    updateCurrentNetworkInfo()
                }
            }

            override fun onUnavailable() {
                log.d(tag = TAG) { "Network unavailable" }
                handleNetworkUnavailable()
            }
        }

        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
            log.d(tag = TAG) { "Network callback registered successfully" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error registering network callback: ${e.message}" }
        }
    }

    /**
     * Maneja cuando una red está disponible
     */
    private fun handleNetworkAvailable(network: Network) {
        val previousNetworkInfo = _networkInfoFlow.value
        val wasConnected = previousNetworkInfo.isConnected

        currentNetwork = network
        updateCurrentNetworkInfo()

        val currentNetworkInfo = _networkInfoFlow.value

        if (!wasConnected && currentNetworkInfo.isConnected) {
            // Nueva conexión
            _networkStateFlow.value = NetworkState.CONNECTED
            lastConnectionTime = Clock.System.now().toEpochMilliseconds()
            reconnectionAttempts = 0

            log.d(tag = TAG) { "Network connected: ${currentNetworkInfo.networkType}" }

            networkChangeListeners.forEach { listener ->
                try {
                    listener.onNetworkConnected(currentNetworkInfo)
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error in network connected listener: ${e.message}" }
                }
            }

        } else if (wasConnected && currentNetworkInfo.isConnected) {
            // Cambio de red
            if (hasNetworkChanged(previousNetworkInfo, currentNetworkInfo)) {
                _networkStateFlow.value = NetworkState.CHANGED

                log.d(tag = TAG) {
                    "Network changed: ${previousNetworkInfo.networkType} -> ${currentNetworkInfo.networkType}"
                }

                networkChangeListeners.forEach { listener ->
                    try {
                        listener.onNetworkChanged(previousNetworkInfo, currentNetworkInfo)
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error in network changed listener: ${e.message}" }
                    }
                }
            }
        }

        // Verificar conectividad a internet
        verifyInternetConnectivity(network)
    }

    /**
     * Maneja cuando se pierde una red
     */
    private fun handleNetworkLost(network: Network) {
        if (currentNetwork == network) {
            val previousNetworkInfo = _networkInfoFlow.value

            _networkStateFlow.value = NetworkState.LOST

            // Actualizar info de red
            val lostNetworkInfo = previousNetworkInfo.copy(
                isConnected = false,
                hasInternet = false,
                timestamp = Clock.System.now().toEpochMilliseconds()
            )
            _networkInfoFlow.value = lostNetworkInfo

            log.d(tag = TAG) { "Network lost: ${previousNetworkInfo.networkType}" }

            networkChangeListeners.forEach { listener ->
                try {
                    listener.onNetworkLost(previousNetworkInfo)
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error in network lost listener: ${e.message}" }
                }
            }

            currentNetwork = null

            // Intentar reconectar después de un delay
            scheduleReconnectionAttempt()
        }
    }

    /**
     * Maneja cambios en las capacidades de red
     */
    private fun handleNetworkCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
        if (currentNetwork == network) {
            val previousNetworkInfo = _networkInfoFlow.value
            updateCurrentNetworkInfo()

            val currentNetworkInfo = _networkInfoFlow.value
            val hadInternet = previousNetworkInfo.hasInternet
            val hasInternet = currentNetworkInfo.hasInternet

            if (hadInternet != hasInternet) {
                log.d(tag = TAG) { "Internet connectivity changed: $hasInternet" }

                networkChangeListeners.forEach { listener ->
                    try {
                        listener.onInternetConnectivityChanged(hasInternet)
                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error in internet connectivity listener: ${e.message}" }
                    }
                }
            }
        }
    }

    /**
     * Maneja cuando no hay redes disponibles
     */
    private fun handleNetworkUnavailable() {
        val previousNetworkInfo = _networkInfoFlow.value

        _networkStateFlow.value = NetworkState.DISCONNECTED

        val disconnectedInfo = NetworkInfo(
            isConnected = false,
            networkType = NetworkType.NONE,
            hasInternet = false,
            timestamp = Clock.System.now().toEpochMilliseconds()
        )
        _networkInfoFlow.value = disconnectedInfo

        log.d(tag = TAG) { "No network available" }

        networkChangeListeners.forEach { listener ->
            try {
                listener.onNetworkDisconnected(previousNetworkInfo)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error in network disconnected listener: ${e.message}" }
            }
        }

        currentNetwork = null
        scheduleReconnectionAttempt()
    }

    /**
     * Actualiza la información de red actual
     */
    private fun updateCurrentNetworkInfo() {
        val networkInfo = fetchCurrentNetworkInfo()
        _networkInfoFlow.value = networkInfo

        log.d(tag = TAG) {
            "Network info updated: ${networkInfo.networkType}, connected: ${networkInfo.isConnected}, internet: ${networkInfo.hasInternet}"
        }
    }

    /**
     * Obtiene la información de red actual del sistema
     */
    private fun fetchCurrentNetworkInfo(): NetworkInfo {
        try {
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = activeNetwork?.let {
                connectivityManager.getNetworkCapabilities(it)
            }
            val linkProperties = activeNetwork?.let {
                connectivityManager.getLinkProperties(it)
            }

            if (activeNetwork == null || networkCapabilities == null) {
                return NetworkInfo(
                    isConnected = false,
                    networkType = NetworkType.NONE,
                    timestamp = Clock.System.now().toEpochMilliseconds()
                )
            }

            val networkType = getNetworkType(networkCapabilities)
            val isConnected = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val isMetered = !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

            // Obtener velocidad de enlace
            val linkSpeed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                networkCapabilities.linkDownstreamBandwidthKbps
            } else {
                0
            }

            // Obtener fuerza de señal
            val signalStrength = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                networkCapabilities.signalStrength
            } else {
                0
            }

            // Obtener dirección IP
            val ipAddress = linkProperties?.linkAddresses?.firstOrNull()?.address?.hostAddress ?: ""

            // Obtener nombre de red
            val networkName = getNetworkName(networkType)

            return NetworkInfo(
                isConnected = isConnected,
                networkType = networkType,
                networkName = networkName,
                ipAddress = ipAddress,
                hasInternet = hasInternet,
                isMetered = isMetered,
                linkSpeed = linkSpeed,
                signalStrength = signalStrength,
                timestamp = Clock.System.now().toEpochMilliseconds()
            )

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error getting network info: ${e.message}" }
            return NetworkInfo(
                isConnected = false,
                networkType = NetworkType.NONE,
                timestamp = Clock.System.now().toEpochMilliseconds()
            )
        }
    }

    /**
     * Determina el tipo de red
     */
    private fun getNetworkType(capabilities: NetworkCapabilities): NetworkType {
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkType.VPN
            else -> NetworkType.OTHER
        }
    }

    /**
     * Obtiene el nombre de la red
     */
    private fun getNetworkName(networkType: NetworkType): String {
        return when (networkType) {
            NetworkType.WIFI -> "WiFi"
            NetworkType.CELLULAR -> "Cellular"
            NetworkType.ETHERNET -> "Ethernet"
            NetworkType.VPN -> "VPN"
            NetworkType.OTHER -> "Other"
            NetworkType.NONE -> "None"
        }
    }

    /**
     * Verifica si la red ha cambiado significativamente
     */
    private fun hasNetworkChanged(old: NetworkInfo, new: NetworkInfo): Boolean {
        return old.networkType != new.networkType ||
                old.ipAddress != new.ipAddress ||
                old.networkName != new.networkName
    }

    /**
     * Verifica conectividad a internet
     */
    private fun verifyInternetConnectivity(network: Network) {
        scope.launch {
            try {
                // Hacer ping a un servidor confiable
                val process = Runtime.getRuntime().exec("ping -c 1 8.8.8.8")
                val exitCode = process.waitFor()
                val hasInternet = exitCode == 0

                val currentInfo = _networkInfoFlow.value
                if (currentInfo.hasInternet != hasInternet) {
                    _networkInfoFlow.value = currentInfo.copy(
                        hasInternet = hasInternet,
                        timestamp = Clock.System.now().toEpochMilliseconds()
                    )

                    networkChangeListeners.forEach { listener ->
                        try {
                            listener.onInternetConnectivityChanged(hasInternet)
                        } catch (e: Exception) {
                            log.e(tag = TAG) { "Error in internet connectivity listener: ${e.message}" }
                        }
                    }
                }

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error verifying internet connectivity: ${e.message}" }
            }
        }
    }

    /**
     * Programa un intento de reconexión
     */
    private fun scheduleReconnectionAttempt() {
        if (reconnectionAttempts >= maxReconnectionAttempts) {
            log.w(tag = TAG) { "Max reconnection attempts reached: $maxReconnectionAttempts" }
            return
        }

        reconnectionAttempts++
        val delay = calculateReconnectionDelay(reconnectionAttempts)

        log.d(tag = TAG) { "Scheduling reconnection attempt $reconnectionAttempts in ${delay}ms" }

        scope.launch {
            delay(delay)

            // Verificar si ya hay conexión
            updateCurrentNetworkInfo()
            val currentInfo = _networkInfoFlow.value

            if (!currentInfo.isConnected) {
                log.d(tag = TAG) { "Reconnection attempt $reconnectionAttempts - still no connection" }
                scheduleReconnectionAttempt()
            } else {
                log.d(tag = TAG) { "Reconnection successful on attempt $reconnectionAttempts" }
                reconnectionAttempts = 0
            }
        }
    }

    /**
     * Calcula el delay para reconexión con backoff exponencial
     */
    private fun calculateReconnectionDelay(attempt: Int): Long {
        val delay = baseReconnectionDelay * (1 shl (attempt - 1)) // 2^(attempt-1)
        return delay.coerceAtMost(maxReconnectionDelay)
    }

    // === MÉTODOS PÚBLICOS ===

    fun addNetworkChangeListener(listener: NetworkChangeListener) {
        networkChangeListeners.add(listener)
        log.d(tag = TAG) { "Network change listener added. Total: ${networkChangeListeners.size}" }
    }

    fun removeNetworkChangeListener(listener: NetworkChangeListener) {
        networkChangeListeners.remove(listener)
        log.d(tag = TAG) { "Network change listener removed. Total: ${networkChangeListeners.size}" }
    }

    fun getCurrentNetworkState(): NetworkState = _networkStateFlow.value

    /**
     * Obtiene la información de red actual
     */
    fun getCurrentNetworkInfo(): NetworkInfo = _networkInfoFlow.value

    fun isConnected(): Boolean = _networkInfoFlow.value.isConnected
    fun hasInternet(): Boolean = _networkInfoFlow.value.hasInternet
    fun getNetworkType(): NetworkType = _networkInfoFlow.value.networkType

    /**
     * Fuerza una verificación de red
     */
    fun forceNetworkCheck() {
        log.d(tag = TAG) { "Forcing network check..." }
        scope.launch {
            updateCurrentNetworkInfo()

            val networkInfo = _networkInfoFlow.value
            if (networkInfo.isConnected) {
                currentNetwork?.let { network ->
                    verifyInternetConnectivity(network)
                }
            }
        }
    }

    /**
     * Resetea los intentos de reconexión
     */
    fun resetReconnectionAttempts() {
        reconnectionAttempts = 0
        log.d(tag = TAG) { "Reconnection attempts reset" }
    }

    /**
     * Información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        val networkInfo = _networkInfoFlow.value
        val networkState = _networkStateFlow.value

        return buildString {
            appendLine("=== NETWORK MONITOR DIAGNOSTIC ===")
            appendLine("Is Monitoring: $isMonitoring")
            appendLine("Network State: $networkState")
            appendLine("Is Connected: ${networkInfo.isConnected}")
            appendLine("Has Internet: ${networkInfo.hasInternet}")
            appendLine("Network Type: ${networkInfo.networkType}")
            appendLine("Network Name: ${networkInfo.networkName}")
            appendLine("IP Address: ${networkInfo.ipAddress}")
            appendLine("Is Metered: ${networkInfo.isMetered}")
            appendLine("Link Speed: ${networkInfo.linkSpeed} Kbps")
            appendLine("Signal Strength: ${networkInfo.signalStrength}")
            appendLine("Current Network: $currentNetwork")
            appendLine("Last Connection Time: $lastConnectionTime")
            appendLine("Reconnection Attempts: $reconnectionAttempts")
            appendLine("Max Reconnection Attempts: $maxReconnectionAttempts")
            appendLine("Listeners Count: ${networkChangeListeners.size}")
            appendLine("Timestamp: ${networkInfo.timestamp}")
        }
    }

    /**
     * Limpieza de recursos
     */
    fun dispose() {
        stopMonitoring()
        networkChangeListeners.clear()
        log.d(tag = TAG) { "NetworkMonitor disposed" }
    }
}