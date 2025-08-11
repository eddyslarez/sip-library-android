package com.eddyslarez.siplibrary.utils

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * Monitor de estado de red para detectar cambios de conectividad
 * 
 * @author Eddys Larez
 */
class NetworkStateMonitor(private val application: Application) {
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val TAG = "NetworkStateMonitor"
    
    private val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    // Estados de red
    private val _isConnectedFlow = MutableStateFlow(false)
    val isConnectedFlow: StateFlow<Boolean> = _isConnectedFlow.asStateFlow()
    
    private val _networkTypeFlow = MutableStateFlow(NetworkType.NONE)
    val networkTypeFlow: StateFlow<NetworkType> = _networkTypeFlow.asStateFlow()
    
    private val _connectionQualityFlow = MutableStateFlow(ConnectionQuality.UNKNOWN)
    val connectionQualityFlow: StateFlow<ConnectionQuality> = _connectionQualityFlow.asStateFlow()
    
    // Callback para notificaciones
    private var onNetworkStateChangeCallback: ((Boolean, NetworkType) -> Unit)? = null
    
    // Network callback para monitoreo
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    
    // Estado interno
    private var isMonitoring = false
    private var lastConnectedTime = 0L
    private var lastDisconnectedTime = 0L
    
    enum class NetworkType {
        NONE,
        WIFI,
        CELLULAR,
        ETHERNET,
        VPN,
        OTHER
    }
    
    enum class ConnectionQuality {
        UNKNOWN,
        POOR,
        MODERATE,
        GOOD,
        EXCELLENT
    }
    
    /**
     * Configura callback para notificaciones de cambio de red
     */
    fun setNetworkStateChangeCallback(callback: (Boolean, NetworkType) -> Unit) {
        this.onNetworkStateChangeCallback = callback
    }
    
    /**
     * Inicia el monitoreo de red
     */
    fun startMonitoring() {
        if (isMonitoring) {
            log.d(tag = TAG) { "Network monitoring already started" }
            return
        }
        
        log.d(tag = TAG) { "Starting network monitoring" }
        
        // Verificar estado inicial
        updateNetworkState()
        
        // Configurar callback de red
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            setupNetworkCallback()
        } else {
            // Para versiones anteriores, usar polling
            startPollingMode()
        }
        
        isMonitoring = true
    }
    
    /**
     * Detiene el monitoreo de red
     */
    fun stopMonitoring() {
        if (!isMonitoring) {
            return
        }
        
        log.d(tag = TAG) { "Stopping network monitoring" }
        
        networkCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error unregistering network callback: ${e.message}" }
            }
        }
        networkCallback = null
        isMonitoring = false
    }
    
    /**
     * Configura callback de red para Android N+
     */
    private fun setupNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                log.d(tag = TAG) { "Network available: $network" }
                scope.launch {
                    delay(500) // Pequeño delay para asegurar que la conexión esté estable
                    updateNetworkState()
                }
            }
            
            override fun onLost(network: Network) {
                log.d(tag = TAG) { "Network lost: $network" }
                scope.launch {
                    delay(100) // Pequeño delay para evitar falsos positivos
                    updateNetworkState()
                }
            }
            
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                log.d(tag = TAG) { "Network capabilities changed: $network" }
                scope.launch {
                    updateNetworkState()
                    updateConnectionQuality(networkCapabilities)
                }
            }
            
            override fun onUnavailable() {
                log.d(tag = TAG) { "Network unavailable" }
                scope.launch {
                    updateNetworkState()
                }
            }
        }
        
        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error registering network callback: ${e.message}" }
            // Fallback a polling mode
            startPollingMode()
        }
    }
    
    /**
     * Modo de polling para versiones anteriores de Android
     */
    private fun startPollingMode() {
        scope.launch {
            while (isMonitoring) {
                updateNetworkState()
                delay(5000) // Verificar cada 5 segundos
            }
        }
    }
    
    /**
     * Actualiza el estado de red actual
     */
    private fun updateNetworkState() {
        val wasConnected = _isConnectedFlow.value
        val previousNetworkType = _networkTypeFlow.value
        
        val isConnected = isNetworkConnected()
        val networkType = getCurrentNetworkType()
        
        // Actualizar estados
        _isConnectedFlow.value = isConnected
        _networkTypeFlow.value = networkType
        
        // Actualizar timestamps
        val currentTime = System.currentTimeMillis()
        if (isConnected && !wasConnected) {
            lastConnectedTime = currentTime
            log.d(tag = TAG) { "Network connected: $networkType" }
        } else if (!isConnected && wasConnected) {
            lastDisconnectedTime = currentTime
            log.d(tag = TAG) { "Network disconnected" }
        }
        
        // Notificar cambios
        if (wasConnected != isConnected || previousNetworkType != networkType) {
            onNetworkStateChangeCallback?.invoke(isConnected, networkType)
            
            // Notificar al RegistrationStateManager
            RegistrationStateManager.updateNetworkState(isConnected)
        }
    }
    
    /**
     * Verifica si hay conexión de red
     */
    private fun isNetworkConnected(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNetwork = connectivityManager.activeNetwork ?: return false
                val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
                
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } else {
                @Suppress("DEPRECATION")
                val activeNetworkInfo = connectivityManager.activeNetworkInfo
                activeNetworkInfo?.isConnected == true
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error checking network connection: ${e.message}" }
            false
        }
    }
    
    /**
     * Obtiene el tipo de red actual
     */
    private fun getCurrentNetworkType(): NetworkType {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNetwork = connectivityManager.activeNetwork ?: return NetworkType.NONE
                val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return NetworkType.NONE
                
                when {
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
                    networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkType.VPN
                    else -> NetworkType.OTHER
                }
            } else {
                @Suppress("DEPRECATION")
                val activeNetworkInfo = connectivityManager.activeNetworkInfo
                when (activeNetworkInfo?.type) {
                    ConnectivityManager.TYPE_WIFI -> NetworkType.WIFI
                    ConnectivityManager.TYPE_MOBILE -> NetworkType.CELLULAR
                    ConnectivityManager.TYPE_ETHERNET -> NetworkType.ETHERNET
                    ConnectivityManager.TYPE_VPN -> NetworkType.VPN
                    else -> if (activeNetworkInfo?.isConnected == true) NetworkType.OTHER else NetworkType.NONE
                }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error getting network type: ${e.message}" }
            NetworkType.NONE
        }
    }
    
    /**
     * Actualiza la calidad de conexión basada en las capacidades de red
     */
    private fun updateConnectionQuality(networkCapabilities: NetworkCapabilities) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        
        val quality = try {
            when {
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                    // Para WiFi, asumir buena calidad por defecto
                    ConnectionQuality.GOOD
                }
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                    // Para celular, verificar capacidades específicas
                    when {
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && 
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> 
                            ConnectionQuality.EXCELLENT
                        networkCapabilities.linkDownstreamBandwidthKbps > 10000 -> ConnectionQuality.EXCELLENT
                        networkCapabilities.linkDownstreamBandwidthKbps > 5000 -> ConnectionQuality.GOOD
                        networkCapabilities.linkDownstreamBandwidthKbps > 1000 -> ConnectionQuality.MODERATE
                        else -> ConnectionQuality.POOR
                    }
                }
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> {
                    ConnectionQuality.EXCELLENT
                }
                else -> ConnectionQuality.MODERATE
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error determining connection quality: ${e.message}" }
            ConnectionQuality.UNKNOWN
        }
        
        _connectionQualityFlow.value = quality
    }
    
    /**
     * Fuerza una verificación inmediata del estado de red
     */
    fun forceNetworkCheck() {
        log.d(tag = TAG) { "Forcing network state check" }
        scope.launch {
            updateNetworkState()
        }
    }
    
    /**
     * Verifica si la conexión es estable (no ha cambiado recientemente)
     */
    fun isConnectionStable(stabilityThresholdMs: Long = 5000): Boolean {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastChange = minOf(
            currentTime - lastConnectedTime,
            currentTime - lastDisconnectedTime
        )
        return timeSinceLastChange > stabilityThresholdMs
    }
    
    // === MÉTODOS DE CONSULTA ===
    
    fun isConnected(): Boolean = _isConnectedFlow.value
    fun getNetworkType(): NetworkType = _networkTypeFlow.value
    fun getConnectionQuality(): ConnectionQuality = _connectionQualityFlow.value
    fun getLastConnectedTime(): Long = lastConnectedTime
    fun getLastDisconnectedTime(): Long = lastDisconnectedTime
    
    /**
     * Información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== NETWORK STATE MONITOR DIAGNOSTIC ===")
            appendLine("Is Monitoring: $isMonitoring")
            appendLine("Is Connected: ${isConnected()}")
            appendLine("Network Type: ${getNetworkType()}")
            appendLine("Connection Quality: ${getConnectionQuality()}")
            appendLine("Connection Stable: ${isConnectionStable()}")
            appendLine("Last Connected: ${if (lastConnectedTime > 0) lastConnectedTime else "Never"}")
            appendLine("Last Disconnected: ${if (lastDisconnectedTime > 0) lastDisconnectedTime else "Never"}")
            appendLine("Callback Registered: ${networkCallback != null}")
        }
    }
    
    /**
     * Limpieza de recursos
     */
    fun dispose() {
        stopMonitoring()
        onNetworkStateChangeCallback = null
    }
}