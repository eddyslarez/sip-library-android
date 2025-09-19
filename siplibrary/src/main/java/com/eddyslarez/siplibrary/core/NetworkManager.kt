package com.eddyslarez.siplibrary.core

//import android.annotation.SuppressLint
//import android.content.Context
//import android.net.*
//import kotlinx.coroutines.*
//import java.net.HttpURLConnection
//import java.net.URL
//import kotlin.math.*
//import com.eddyslarez.siplibrary.utils.log
//
///**
// * NetworkManager - Gestiona la conectividad de red de forma inteligente
// * Evita falsos positivos/negativos comunes en Android
// */
//class NetworkManager private constructor(
//    private val context: Context
//) {
//    companion object {
//        private const val TAG = "NetworkManager"
//        private const val INTERNET_CHECK_TIMEOUT = 3000
//        private const val DEBOUNCE_DELAY = 1000L
//        private const val STABILITY_CHECK_DELAY = 2000L
//        private const val MAX_VALIDATION_RETRIES = 3
//
//        @Volatile
//        private var INSTANCE: NetworkManager? = null
//
//        fun getInstance(context: Context): NetworkManager {
//            return INSTANCE ?: synchronized(this) {
//                INSTANCE ?: NetworkManager(context.applicationContext).also { INSTANCE = it }
//            }
//        }
//    }
//
//    private var connectivityManager: ConnectivityManager? = null
//    private var networkCallback: ConnectivityManager.NetworkCallback? = null
//
//    // Estado interno más robusto
//    private var isNetworkAvailable = false
//    private var lastNetworkChangeTime = 0L
//    private var pendingValidationJob: Job? = null
//    private var currentNetwork: Network? = null
//    private var networkValidationRetries = 0
//
//    // Listeners
//    private var connectivityListener: NetworkConnectivityListener? = null
//
//    // Scope para operaciones async
//    private val networkScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
//
//    /**
//     * Inicializa el monitoreo de red
//     */
//    @SuppressLint("MissingPermission")
//    fun initialize() {
//        try {
//            connectivityManager =
//                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
//            setupNetworkCallback()
//            checkInitialNetworkState()
//
//            log.d(tag = TAG) { "NetworkManager initialized successfully" }
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error initializing NetworkManager: ${e.message}" }
//        }
//    }
//
//    /**
//     * Configura el callback de red con lógica mejorada
//     */
//    @SuppressLint("MissingPermission")
//    private fun setupNetworkCallback() {
//        networkCallback = object : ConnectivityManager.NetworkCallback() {
//
//            override fun onAvailable(network: Network) {
//                log.d(tag = TAG) { "🌐 Network available: $network" }
//                currentNetwork = network
//
//                // Cancelar validación anterior si existe
//                pendingValidationJob?.cancel()
//
//                // Validar red con estabilización
//                pendingValidationJob = networkScope.launch {
//                    validateNetworkWithStabilization(network)
//                }
//            }
//
//            override fun onLost(network: Network) {
//                log.d(tag = TAG) { "❌ Network lost: $network" }
//
//                // Solo procesar si es la red actual
//                if (currentNetwork == network) {
//                    currentNetwork = null
//                    handleNetworkUnavailable()
//                }
//            }
//
//            override fun onCapabilitiesChanged(
//                network: Network,
//                capabilities: NetworkCapabilities
//            ) {
//                // Solo procesar si es la red actual
//                if (currentNetwork != network) {
//                    return
//                }
//
//                val hasInternet =
//                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
//                val validated =
//                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
//                val notMetered =
//                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
//
//                log.d(tag = TAG) {
//                    "📡 Capabilities changed for $network - Internet=$hasInternet, Validated=$validated, NotMetered=$notMetered"
//                }
//
//                // MEJORA CRÍTICA: Solo actuar si hay cambios significativos
//                if (hasInternet && !validated) {
//                    // Red disponible pero no validada - dar tiempo antes de marcar como perdida
//                    pendingValidationJob?.cancel()
//                    pendingValidationJob = networkScope.launch {
//                        delay(STABILITY_CHECK_DELAY)
//
//                        // Re-verificar después del delay
//                        val currentCapabilities =
//                            connectivityManager?.getNetworkCapabilities(network)
//                        val stillHasInternet =
//                            currentCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
//                                ?: false
//                        val nowValidated =
//                            currentCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
//                                ?: false
//
//                        if (stillHasInternet && nowValidated) {
//                            log.d(tag = TAG) { "Network became validated after stability check" }
//                            handleNetworkAvailable()
//                        } else if (stillHasInternet && !nowValidated) {
//                            // Validar manualmente con ping
//                            log.d(tag = TAG) { "Performing manual internet validation" }
//                            validateInternetAccess { hasRealInternet ->
//                                if (hasRealInternet) {
//                                    handleNetworkAvailable()
//                                } else {
//                                    handleNetworkUnavailable()
//                                }
//                            }
//                        } else {
//                            handleNetworkUnavailable()
//                        }
//                    }
//                } else if (hasInternet && validated) {
//                    // Red completamente funcional
//                    handleNetworkAvailable()
//                }
//                // Si no tiene internet, la red ya se marcará como no disponible en onLost
//            }
//
//            override fun onUnavailable() {
//                log.d(tag = TAG) { "📵 Network unavailable" }
//                handleNetworkUnavailable()
//            }
//        }
//
//        // Registrar callback
//        val request = NetworkRequest.Builder()
//            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
//            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
//            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
//            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
//            .build()
//
//        connectivityManager?.registerNetworkCallback(request, networkCallback!!)
//    }
//
//    /**
//     * Valida la red con un período de estabilización
//     */
//    private suspend fun validateNetworkWithStabilization(network: Network) {
//        try {
//            // Esperar a que la red se estabilice
//            delay(DEBOUNCE_DELAY)
//
//            // Verificar que la red sigue siendo la actual
//            if (currentNetwork != network) {
//                log.d(tag = TAG) { "Network changed during validation, aborting" }
//                return
//            }
//
//            // Obtener capacidades actuales
//            val capabilities = connectivityManager?.getNetworkCapabilities(network)
//            val hasInternet =
//                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
//            val validated =
//                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false
//
//            log.d(tag = TAG) { "Validating network after stabilization - Internet=$hasInternet, Validated=$validated" }
//
//            when {
//                hasInternet && validated -> {
//                    // Red completamente funcional
//                    networkValidationRetries = 0
//                    handleNetworkAvailable()
//                }
//
//                hasInternet && !validated -> {
//                    // Validar manualmente
//                    validateInternetAccess { hasRealInternet ->
//                        if (hasRealInternet) {
//                            networkValidationRetries = 0
//                            handleNetworkAvailable()
//                        } else {
//                            handleValidationFailure()
//                        }
//                    }
//                }
//
//                else -> {
//                    handleNetworkUnavailable()
//                }
//            }
//
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error during network validation: ${e.message}" }
//            handleValidationFailure()
//        }
//    }
//
//    /**
//     * Maneja fallos de validación con reintentos
//     */
//    private fun handleValidationFailure() {
//        networkValidationRetries++
//
//        if (networkValidationRetries < MAX_VALIDATION_RETRIES) {
//            log.d(tag = TAG) { "Network validation failed, retry $networkValidationRetries/$MAX_VALIDATION_RETRIES" }
//
//            // Reintentar después de un delay exponencial
//            val delay = min(2000L * (1 shl networkValidationRetries), 10000L)
//
//            pendingValidationJob = networkScope.launch {
//                delay(delay)
//                currentNetwork?.let { network ->
//                    validateNetworkWithStabilization(network)
//                }
//            }
//        } else {
//            log.w(tag = TAG) { "Max validation retries reached, marking network as unavailable" }
//            networkValidationRetries = 0
//            handleNetworkUnavailable()
//        }
//    }
//
//    /**
//     * Valida acceso real a internet
//     */
//    private fun validateInternetAccess(callback: (Boolean) -> Unit) {
//        networkScope.launch {
//            try {
//                val url = URL("https://clients3.google.com/generate_204")
//                val connection = url.openConnection() as HttpURLConnection
//
//                connection.apply {
//                    connectTimeout = INTERNET_CHECK_TIMEOUT
//                    readTimeout = INTERNET_CHECK_TIMEOUT
//                    instanceFollowRedirects = false
//                    useCaches = false
//
//                    // Usar la red específica si está disponible (API 21+)
//                    currentNetwork?.let { network ->
//                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
//                            connectivityManager?.bindProcessToNetwork(network)
//                        }
//                    }
//
//                    connect()
//                    val success = responseCode == 204
//                    disconnect()
//
//                    // Restaurar binding de red
//                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
//                        connectivityManager?.bindProcessToNetwork(null)
//                    }
//
//                    withContext(Dispatchers.Main) {
//                        callback(success)
//                    }
//                }
//
//            } catch (e: Exception) {
//                log.w(tag = TAG) { "Internet validation failed: ${e.message}" }
//                withContext(Dispatchers.Main) {
//                    callback(false)
//                }
//            }
//        }
//    }
//
//    /**
//     * Verifica el estado inicial de la red
//     */
//    private fun checkInitialNetworkState() {
//        try {
//            val activeNetwork = connectivityManager?.activeNetwork
//            val capabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)
//
//            val hasInternet =
//                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
//            val validated =
//                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false
//
//            isNetworkAvailable = hasInternet && validated
//            currentNetwork = if (isNetworkAvailable) activeNetwork else null
//
//            log.d(tag = TAG) {
//                "🌍 Initial network state: available=$isNetworkAvailable (internet=$hasInternet, validated=$validated)"
//            }
//
//            // Si hay internet pero no está validado, verificar manualmente
//            if (hasInternet && !validated) {
//                validateInternetAccess { hasRealInternet ->
//                    if (hasRealInternet && !isNetworkAvailable) {
//                        log.d(tag = TAG) { "Manual validation successful, network is actually available" }
//                        isNetworkAvailable = true
//                        connectivityListener?.onNetworkRestored()
//                    }
//                }
//            }
//
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error checking initial network state: ${e.message}" }
//            isNetworkAvailable = false
//            currentNetwork = null
//        }
//    }
//
//    /**
//     * Maneja cuando la red está disponible
//     */
//    private fun handleNetworkAvailable() {
//        val wasUnavailable = !isNetworkAvailable
//        val currentTime = System.currentTimeMillis()
//
//        // Evitar notificaciones spam con debounce
//        if (currentTime - lastNetworkChangeTime < DEBOUNCE_DELAY) {
//            log.d(tag = TAG) { "Network change too recent, debouncing" }
//            return
//        }
//
//        isNetworkAvailable = true
//        lastNetworkChangeTime = currentTime
//
//        log.d(tag = TAG) { "✅ Network available confirmed. Was unavailable: $wasUnavailable" }
//
//        if (wasUnavailable) {
//            connectivityListener?.onNetworkRestored()
//        }
//    }
//
//    /**
//     * Maneja cuando la red no está disponible
//     */
//    private fun handleNetworkUnavailable() {
//        val wasAvailable = isNetworkAvailable
//        val currentTime = System.currentTimeMillis()
//
//        // Evitar notificaciones spam con debounce
//        if (currentTime - lastNetworkChangeTime < DEBOUNCE_DELAY) {
//            log.d(tag = TAG) { "Network change too recent, debouncing" }
//            return
//        }
//
//        isNetworkAvailable = false
//        lastNetworkChangeTime = currentTime
//        currentNetwork = null
//
//        // Cancelar validaciones pendientes
//        pendingValidationJob?.cancel()
//        networkValidationRetries = 0
//
//        log.d(tag = TAG) { "❌ Network unavailable confirmed. Was available: $wasAvailable" }
//
//        if (wasAvailable) {
//            connectivityListener?.onNetworkLost()
//        }
//    }
//
//    /**
//     * Métodos públicos para integración
//     */
//    fun isNetworkAvailable(): Boolean = isNetworkAvailable
//
//    fun setConnectivityListener(listener: NetworkConnectivityListener?) {
//        this.connectivityListener = listener
//    }
//
//    fun getCurrentNetwork(): Network? = currentNetwork
//
//    fun forceNetworkCheck() {
//        log.d(tag = TAG) { "Force network check requested" }
//        networkScope.launch {
//            checkInitialNetworkState()
//        }
//    }
//
//    fun getNetworkInfo(): Map<String, Any> {
//        val activeNetwork = connectivityManager?.activeNetwork
//        val capabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)
//
//        return mapOf(
//            "isAvailable" to isNetworkAvailable,
//            "currentNetwork" to (currentNetwork?.toString() ?: "null"),
//            "hasInternet" to (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
//                ?: false),
//            "validated" to (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
//                ?: false),
//            "notMetered" to (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
//                ?: false),
//            "lastChangeTime" to lastNetworkChangeTime,
//            "validationRetries" to networkValidationRetries
//        )
//    }
//
//    /**
//     * Cleanup
//     */
//    fun dispose() {
//        try {
//            pendingValidationJob?.cancel()
//            networkCallback?.let { callback ->
//                connectivityManager?.unregisterNetworkCallback(callback)
//            }
//            networkScope.cancel()
//            connectivityListener = null
//
//            log.d(tag = TAG) { "NetworkManager disposed" }
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error disposing NetworkManager: ${e.message}" }
//        }
//    }
//}

import android.annotation.SuppressLint
import android.content.Context
import android.net.*
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*
import com.eddyslarez.siplibrary.utils.log

/**
 * NetworkManager - Gestiona la conectividad de red de forma inteligente
 * Evita falsos positivos/negativos comunes en Android
 * MEJORA: Detecta específicamente la red primaria activa y evita falsos positivos
 */
class NetworkManager private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "NetworkManager"
        private const val INTERNET_CHECK_TIMEOUT = 3000
        private const val DEBOUNCE_DELAY = 1500L // Aumentado para mayor estabilidad
        private const val STABILITY_CHECK_DELAY = 2500L // Aumentado
        private const val MAX_VALIDATION_RETRIES = 3
        private const val PRIMARY_NETWORK_VALIDATION_DELAY = 1000L // Nuevo

        @Volatile
        private var INSTANCE: NetworkManager? = null

        fun getInstance(context: Context): NetworkManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NetworkManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Estado interno más robusto
    private var isNetworkAvailable = false
    private var lastNetworkChangeTime = 0L
    private var pendingValidationJob: Job? = null

    // NUEVO: Tracking de red primaria específica
    private var primaryNetwork: Network? = null
    private var primaryNetworkCapabilities: NetworkCapabilities? = null
    private var networkValidationRetries = 0

    // Listeners
    private var connectivityListener: NetworkConnectivityListener? = null

    // Scope para operaciones async
    private val networkScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Inicializa el monitoreo de red
     */
    @SuppressLint("MissingPermission")
    fun initialize() {
        try {
            connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            setupNetworkCallback()
            checkInitialNetworkState()

            log.d(tag = TAG) { "NetworkManager initialized successfully" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error initializing NetworkManager: ${e.message}" }
        }
    }

    /**
     * MEJORADO: Configura el callback de red con lógica específica para red primaria
     */
    @SuppressLint("MissingPermission")
    private fun setupNetworkCallback() {
        networkCallback = object : ConnectivityManager.NetworkCallback() {

            override fun onAvailable(network: Network) {
                log.d(tag = TAG) { "🌐 Network available: $network" }

                // NUEVO: Verificar si esta es la red primaria
                networkScope.launch {
                    delay(PRIMARY_NETWORK_VALIDATION_DELAY) // Dar tiempo a que se establezca
                    evaluatePrimaryNetwork(network, "onAvailable")
                }
            }

            override fun onLost(network: Network) {
                log.d(tag = TAG) { "❌ Network lost: $network" }

                // CRÍTICO: Solo procesar si es la red primaria actual
                if (primaryNetwork == network) {
                    log.w(tag = TAG) { "🚨 PRIMARY network lost: $network" }
                    primaryNetwork = null
                    primaryNetworkCapabilities = null
                    handleNetworkUnavailable()
                } else {
                    log.d(tag = TAG) { "📱 Secondary network lost (ignored): $network" }
                    // Re-evaluar la red primaria para asegurar que sigue disponible
                    networkScope.launch {
                        delay(500) // Breve delay
                        evaluateCurrentPrimaryNetwork()
                    }
                }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                val isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

                log.d(tag = TAG) {
                    "📡 Capabilities changed for $network - Internet=$hasInternet, Validated=$validated, WiFi=$isWifi, Cellular=$isCellular"
                }

                // NUEVO: Solo procesar cambios en la red primaria
                networkScope.launch {
                    evaluatePrimaryNetwork(network, "onCapabilitiesChanged")
                }
            }

            override fun onUnavailable() {
                log.d(tag = TAG) { "📵 Network unavailable" }
                // Solo marcar como no disponible si no hay red primaria
                if (primaryNetwork == null) {
                    handleNetworkUnavailable()
                }
            }
        }

        // Registrar callback - SIN filtros específicos para detectar todas las redes
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager?.registerNetworkCallback(request, networkCallback!!)
    }

    /**
     * NUEVO: Evalúa cuál debe ser la red primaria
     */
    private suspend fun evaluatePrimaryNetwork(network: Network, source: String) {
        try {
            val activeNetwork = connectivityManager?.activeNetwork
            val capabilities = connectivityManager?.getNetworkCapabilities(network)
            val activeCapabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)

            log.d(tag = TAG) { "🔍 Evaluating primary network from $source - Network: $network, Active: $activeNetwork" }

            // Determinar si esta red debe ser la primaria
            val shouldBePrimary = when {
                // La red activa del sistema tiene prioridad
                activeNetwork == network -> true
                // Si no hay red activa, pero esta tiene internet validado
                activeNetwork == null && capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                        && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) -> true
                // Si la red actual no es la activa del sistema, no debe ser primaria
                else -> false
            }

            if (shouldBePrimary && network != primaryNetwork) {
                log.d(tag = TAG) { "🎯 Setting new primary network: $network" }

                // Cancelar validación anterior
                pendingValidationJob?.cancel()

                primaryNetwork = network
                primaryNetworkCapabilities = capabilities

                // Validar la nueva red primaria
                validatePrimaryNetworkWithStabilization()

            } else if (!shouldBePrimary && network == primaryNetwork) {
                log.d(tag = TAG) { "⚠️ Current primary network is no longer active, re-evaluating" }

                // La red primaria ya no es la activa, buscar nueva primaria
                evaluateCurrentPrimaryNetwork()
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error evaluating primary network: ${e.message}" }
        }
    }

    /**
     * NUEVO: Evalúa la red primaria actual del sistema
     */
    private suspend fun evaluateCurrentPrimaryNetwork() {
        try {
            val activeNetwork = connectivityManager?.activeNetwork
            val capabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)

            if (activeNetwork != null && activeNetwork != primaryNetwork) {
                log.d(tag = TAG) { "🔄 Active network changed, updating primary: $activeNetwork" }
                evaluatePrimaryNetwork(activeNetwork, "systemActiveChange")
            } else if (activeNetwork == null && primaryNetwork != null) {
                log.w(tag = TAG) { "🚨 No active network available" }
                primaryNetwork = null
                primaryNetworkCapabilities = null
                handleNetworkUnavailable()
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error evaluating current primary network: ${e.message}" }
        }
    }

    /**
     * MEJORADO: Valida específicamente la red primaria
     */
    private suspend fun validatePrimaryNetworkWithStabilization() {
        try {
            // Esperar estabilización
            delay(DEBOUNCE_DELAY)

            val currentPrimary = primaryNetwork
            if (currentPrimary == null) {
                log.d(tag = TAG) { "No primary network to validate" }
                handleNetworkUnavailable()
                return
            }

            // Verificar que sigue siendo la red activa
            val activeNetwork = connectivityManager?.activeNetwork
            if (activeNetwork != currentPrimary) {
                log.d(tag = TAG) { "Primary network changed during validation, re-evaluating" }
                evaluateCurrentPrimaryNetwork()
                return
            }

            // Obtener capacidades actuales de la red primaria
            val capabilities = connectivityManager?.getNetworkCapabilities(currentPrimary)
            val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
            val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false
            val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
            val isCellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false

            log.d(tag = TAG) {
                "🔍 Validating PRIMARY network - Internet=$hasInternet, Validated=$validated, WiFi=$isWifi, Cellular=$isCellular"
            }

            when {
                hasInternet && validated -> {
                    // Red primaria completamente funcional
                    networkValidationRetries = 0
                    primaryNetworkCapabilities = capabilities
                    handleNetworkAvailable()
                }

                hasInternet && !validated -> {
                    // Validar manualmente con ping específico a esta red
                    validateInternetAccessOnNetwork(currentPrimary) { hasRealInternet ->
                        if (hasRealInternet) {
                            networkValidationRetries = 0
                            primaryNetworkCapabilities = capabilities
                            handleNetworkAvailable()
                        } else {
                            handleValidationFailure()
                        }
                    }
                }

                else -> {
                    log.w(tag = TAG) { "Primary network lacks internet capability" }
                    handleValidationFailure()
                }
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error validating primary network: ${e.message}" }
            handleValidationFailure()
        }
    }

    /**
     * NUEVO: Valida acceso a internet específicamente en la red primaria
     */
    private fun validateInternetAccessOnNetwork(network: Network, callback: (Boolean) -> Unit) {
        networkScope.launch {
            try {
                val url = URL("https://clients3.google.com/generate_204")
                val connection = url.openConnection() as HttpURLConnection

                connection.apply {
                    connectTimeout = INTERNET_CHECK_TIMEOUT
                    readTimeout = INTERNET_CHECK_TIMEOUT
                    instanceFollowRedirects = false
                    useCaches = false

                    // CRÍTICO: Usar específicamente la red primaria
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        network.openConnection(url).let { networkConnection ->
                            (networkConnection as HttpURLConnection).apply {
                                connectTimeout = INTERNET_CHECK_TIMEOUT
                                readTimeout = INTERNET_CHECK_TIMEOUT
                                instanceFollowRedirects = false
                                useCaches = false

                                connect()
                                val success = responseCode == 204
                                disconnect()

                                withContext(Dispatchers.Main) {
                                    log.d(tag = TAG) { "Internet validation on network $network: $success" }
                                    callback(success)
                                }
                            }
                            return@launch
                        }
                    } else {
                        // Fallback para versiones anteriores
                        connect()
                        val success = responseCode == 204
                        disconnect()

                        withContext(Dispatchers.Main) {
                            callback(success)
                        }
                    }
                }

            } catch (e: Exception) {
                log.w(tag = TAG) { "Internet validation failed on network $network: ${e.message}" }
                withContext(Dispatchers.Main) {
                    callback(false)
                }
            }
        }
    }

    /**
     * Maneja fallos de validación con reintentos
     */
    private fun handleValidationFailure() {
        networkValidationRetries++

        if (networkValidationRetries < MAX_VALIDATION_RETRIES) {
            log.d(tag = TAG) { "Primary network validation failed, retry $networkValidationRetries/$MAX_VALIDATION_RETRIES" }

            val delay = min(2000L * (1 shl networkValidationRetries), 10000L)
            pendingValidationJob = networkScope.launch {
                delay(delay)
                validatePrimaryNetworkWithStabilization()
            }
        } else {
            log.w(tag = TAG) { "Max validation retries reached for primary network" }
            networkValidationRetries = 0
            handleNetworkUnavailable()
        }
    }

    /**
     * MEJORADO: Verifica el estado inicial enfocándose en la red primaria
     */
    private fun checkInitialNetworkState() {
        try {
            val activeNetwork = connectivityManager?.activeNetwork
            val capabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)

            val hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false
            val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false
            val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
            val isCellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false

            log.d(tag = TAG) {
                "🌍 Initial PRIMARY network state - Internet=$hasInternet, Validated=$validated, WiFi=$isWifi, Cellular=$isCellular"
            }

            if (activeNetwork != null) {
                primaryNetwork = activeNetwork
                primaryNetworkCapabilities = capabilities
                isNetworkAvailable = hasInternet && validated

                // Si hay internet pero no está validado, verificar manualmente
                if (hasInternet && !validated) {
                    validateInternetAccessOnNetwork(activeNetwork) { hasRealInternet ->
                        if (hasRealInternet && !isNetworkAvailable) {
                            log.d(tag = TAG) { "Manual validation successful for primary network" }
                            isNetworkAvailable = true
                            connectivityListener?.onNetworkRestored()
                        }
                    }
                }
            } else {
                primaryNetwork = null
                primaryNetworkCapabilities = null
                isNetworkAvailable = false
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error checking initial network state: ${e.message}" }
            isNetworkAvailable = false
            primaryNetwork = null
            primaryNetworkCapabilities = null
        }
    }

    /**
     * Maneja cuando la red está disponible
     */
    private fun handleNetworkAvailable() {
        val wasUnavailable = !isNetworkAvailable
        val currentTime = System.currentTimeMillis()

        // Evitar notificaciones spam con debounce
        if (currentTime - lastNetworkChangeTime < DEBOUNCE_DELAY) {
            log.d(tag = TAG) { "Network change too recent, debouncing" }
            return
        }

        isNetworkAvailable = true
        lastNetworkChangeTime = currentTime

        log.d(tag = TAG) { "✅ PRIMARY Network available confirmed. Was unavailable: $wasUnavailable" }

        if (wasUnavailable) {
            connectivityListener?.onNetworkRestored()
        }
    }

    /**
     * Maneja cuando la red no está disponible
     */
    private fun handleNetworkUnavailable() {
        val wasAvailable = isNetworkAvailable
        val currentTime = System.currentTimeMillis()

        // Evitar notificaciones spam con debounce
        if (currentTime - lastNetworkChangeTime < DEBOUNCE_DELAY) {
            log.d(tag = TAG) { "Network change too recent, debouncing" }
            return
        }

        isNetworkAvailable = false
        lastNetworkChangeTime = currentTime

        // Cancelar validaciones pendientes
        pendingValidationJob?.cancel()
        networkValidationRetries = 0

        log.d(tag = TAG) { "❌ PRIMARY Network unavailable confirmed. Was available: $wasAvailable" }

        if (wasAvailable) {
            connectivityListener?.onNetworkLost()
        }
    }

    /**
     * Métodos públicos para integración
     */
    fun isNetworkAvailable(): Boolean = isNetworkAvailable

    fun setConnectivityListener(listener: NetworkConnectivityListener?) {
        this.connectivityListener = listener
    }

    fun getCurrentNetwork(): Network? = primaryNetwork

    fun getPrimaryNetwork(): Network? = primaryNetwork

    fun forceNetworkCheck() {
        log.d(tag = TAG) { "Force network check requested" }
        networkScope.launch {
            evaluateCurrentPrimaryNetwork()
        }
    }

    fun getNetworkInfo(): Map<String, Any> {
        val capabilities = primaryNetworkCapabilities

        return mapOf(
            "isAvailable" to isNetworkAvailable,
            "primaryNetwork" to (primaryNetwork?.toString() ?: "null"),
            "hasInternet" to (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: false),
            "validated" to (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ?: false),
            "notMetered" to (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ?: false),
            "isWifi" to (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false),
            "isCellular" to (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false),
            "lastChangeTime" to lastNetworkChangeTime,
            "validationRetries" to networkValidationRetries
        )
    }

    /**
     * Cleanup
     */
    fun dispose() {
        try {
            pendingValidationJob?.cancel()
            networkCallback?.let { callback ->
                connectivityManager?.unregisterNetworkCallback(callback)
            }
            networkScope.cancel()
            connectivityListener = null

            log.d(tag = TAG) { "NetworkManager disposed" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error disposing NetworkManager: ${e.message}" }
        }
    }
}
interface NetworkConnectivityListener {
    fun onNetworkLost()
    fun onNetworkRestored()
    fun onReconnectionStarted()
    fun onReconnectionCompleted(successful: Boolean)
}