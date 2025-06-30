package com.eddyslarez.siplibrary.core

import android.app.Application
import com.eddyslarez.siplibrary.EddysSipLibrary
import com.eddyslarez.siplibrary.data.models.*
import com.eddyslarez.siplibrary.data.services.audio.AudioDevice
import com.eddyslarez.siplibrary.data.services.audio.AudioDeviceManager
import com.eddyslarez.siplibrary.data.services.audio.CallHoldManager
import com.eddyslarez.siplibrary.data.services.audio.AudioManager
import com.eddyslarez.siplibrary.data.services.audio.WebRtcConnectionState
import com.eddyslarez.siplibrary.data.services.audio.WebRtcEventListener
import com.eddyslarez.siplibrary.data.services.audio.WebRtcManagerFactory
import com.eddyslarez.siplibrary.data.services.sip.SipMessageHandler
import com.eddyslarez.siplibrary.data.services.websocket.MultiplatformWebSocket
import com.eddyslarez.siplibrary.data.services.websocket.WebSocket
import com.eddyslarez.siplibrary.data.store.SettingsDataStore
import com.eddyslarez.siplibrary.platform.PlatformInfo
import com.eddyslarez.siplibrary.platform.PlatformRegistration
import com.eddyslarez.siplibrary.platform.WindowManager
import com.eddyslarez.siplibrary.utils.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlin.math.pow

/**
 * Gestor principal del core SIP - Adaptado para Android
 *
 * @author Eddys Larez
 */
internal class SipCoreManager private constructor(
    private val application: Application,
    private val config: EddysSipLibrary.SipConfig,
    val audioManager: AudioManager,
    val windowManager: WindowManager,
    val platformInfo: PlatformInfo,
    val settingsDataStore: SettingsDataStore,
) {
    private var isRegistrationInProgress = false
    private var healthCheckJob: Job? = null
    private val registrationTimeout = 30000L
    private var lastRegistrationAttempt = 0L
    private var sipCallbacks: EddysSipLibrary.SipCallbacks? = null
    private var isShuttingDown = false
    val callHistoryManager = CallHistoryManager()
    private var registrationState = RegistrationState.NONE
    private val activeAccounts = HashMap<String, AccountInfo>()
    var callState = CallState.NONE
    var callStartTimeMillis: Long = 0
    var currentAccountInfo: AccountInfo? = null
    var isAppInBackground = false
    private var reconnectionInProgress = false
    private var lastConnectionCheck = 0L
    private val connectionCheckInterval = 30000L
    private val dtmfQueue = mutableListOf<DtmfRequest>()
    private var isDtmfProcessing = false
    private val dtmfMutex = Mutex()
    var onCallTerminated: (() -> Unit)? = null
    var isCallFromPush = false
    private var registrationCallbackForCall: ((AccountInfo, Boolean) -> Unit)? = null
    private var connectionRetryCount = 0
    private val maxRetryAttempts = 5

    // WebRTC manager and other managers
    val webRtcManager = WebRtcManagerFactory.createWebRtcManager(application)
    private val platformRegistration = PlatformRegistration(application)
    private val callHoldManager = CallHoldManager(webRtcManager)
    private val audioDeviceManager = AudioDeviceManager()


    companion object {
        private const val TAG = "SipCoreManager"
        private const val WEBSOCKET_PROTOCOL = "sip"
        private const val REGISTRATION_CHECK_INTERVAL_MS = 30 * 1000L

        fun createInstance(
            application: Application,
            config: EddysSipLibrary.SipConfig
        ): SipCoreManager {
            return SipCoreManager(
                application = application,
                config = config,
                audioManager = AudioManager(application),
                windowManager = WindowManager(),
                platformInfo = PlatformInfo(),
                settingsDataStore = SettingsDataStore(application)
            )
        }
    }

    private val messageHandler = SipMessageHandler(this)

    fun userAgent(): String = config.userAgent

    fun getDefaultDomain(): String? = currentAccountInfo?.domain

    fun getCurrentUsername(): String? = currentAccountInfo?.username

    fun initialize() {
        log.d(tag = TAG) { "Initializing SIP Core" }

        webRtcManager.initialize()
        setupWebRtcEventListener()
        setupPlatformLifecycleObservers()
        startConnectionHealthCheck()
    }

    fun setCallbacks(callbacks: EddysSipLibrary.SipCallbacks) {
        this.sipCallbacks = callbacks
    }

    private fun setupWebRtcEventListener() {
        webRtcManager.setListener(object : WebRtcEventListener {
            override fun onIceCandidate(candidate: String, sdpMid: String, sdpMLineIndex: Int) {
                // Implementar envío de ICE candidate
            }

            override fun onConnectionStateChange(state: WebRtcConnectionState) {
                when (state) {
                    WebRtcConnectionState.CONNECTED -> handleWebRtcConnected()
                    WebRtcConnectionState.CLOSED -> handleWebRtcClosed()
                    else -> {}
                }
            }

            override fun onRemoteAudioTrack() {
                log.d(tag = TAG) { "Remote audio track received" }
            }

            override fun onAudioDeviceChanged(device: AudioDevice?) {
                log.d(tag = TAG) { "Audio device changed: ${device?.name}" }
            }
        })
    }

    /**
     * Get available audio devices
     */
    fun getAudioDevices(): Pair<List<AudioDevice>, List<AudioDevice>> {
        return webRtcManager.getAllAudioDevices()
    }

    /**
     * Get current audio devices
     */
    fun getCurrentDevices(): Pair<AudioDevice?, AudioDevice?> {
        return Pair(
            webRtcManager.getCurrentInputDevice(),
            webRtcManager.getCurrentOutputDevice()
        )
    }

    /**
     * Refresh the list of available audio devices
     */
    fun refreshAudioDevices() {
        val (inputs, outputs) = webRtcManager.getAllAudioDevices()
        audioDeviceManager.updateDevices(inputs, outputs)
    }

    /**
     * Change audio device during call
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


    private fun setupPlatformLifecycleObservers() {
        platformRegistration.setupNotificationObservers(object : AppLifecycleListener {
            override fun onEvent(event: AppLifecycleEvent) {
                when (event) {
                    AppLifecycleEvent.EnterBackground -> {
                        isAppInBackground = true
                        refreshAllRegistrationsWithNewUserAgent()
                    }

                    AppLifecycleEvent.EnterForeground -> {
                        isAppInBackground = false
                        refreshAllRegistrationsWithNewUserAgent()
                    }

                    else -> {}
                }
            }
        })
    }

    private fun handleWebRtcConnected() {
        callStartTimeMillis = Clock.System.now().toEpochMilliseconds()
        CallStateManager.updateCallState(CallState.CONNECTED)
        callState = CallState.CONNECTED
    }

    private fun handleWebRtcClosed() {
        callState = CallState.ENDED
        currentAccountInfo?.currentCallData?.let { callData ->
            val endTime = Clock.System.now().toEpochMilliseconds()
            val callType = determineCallType(callData, callState)
            callHistoryManager.addCallLog(callData, callType, endTime)
        }
    }

    internal fun handleCallTermination() {
        onCallTerminated?.invoke()
    }

    private fun refreshAllRegistrationsWithNewUserAgent() {
        if (callState != CallState.NONE && callState != CallState.ENDED) {
            return
        }

        activeAccounts.values.forEach { accountInfo ->
            if (accountInfo.isRegistered) {
                accountInfo.userAgent = userAgent()
                messageHandler.sendRegister(accountInfo, isAppInBackground)
            }
        }
    }

    private fun startConnectionHealthCheck() {
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                delay(connectionCheckInterval)
                checkConnectionHealth()
            }
        }
    }

    private fun checkConnectionHealth() {
        activeAccounts.values.forEach { accountInfo ->
            val webSocket = accountInfo.webSocketClient
            if (webSocket != null && accountInfo.isRegistered) {
                if (!webSocket.isConnected()) {
                    reconnectAccount(accountInfo)
                }
            }
        }
    }

    private fun reconnectAccount(accountInfo: AccountInfo) {
        if (reconnectionInProgress) return

        reconnectionInProgress = true
        try {
            accountInfo.webSocketClient?.close()
            accountInfo.userAgent = userAgent()
            val headers = createHeaders()
            val newWebSocketClient = createWebSocketClient(accountInfo, headers)
            accountInfo.webSocketClient = newWebSocketClient
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error during reconnection: ${e.message}" }
        } finally {
            reconnectionInProgress = false
        }
    }

    fun updateRegistrationState(newState: RegistrationState) {
        registrationState = newState
        RegistrationStateManager.updateCallState(newState)
    }

    fun register(
        username: String,
        password: String,
        domain: String,
        provider: String,
        token: String
    ) {
        try {
            val accountKey = "$username@$domain"
            val accountInfo = AccountInfo(username, password, domain)
            activeAccounts[accountKey] = accountInfo

            accountInfo.token = token
            accountInfo.provider = provider
            accountInfo.userAgent = userAgent()

            connectWebSocketAndRegister(accountInfo)
        } catch (e: Exception) {
            updateRegistrationState(RegistrationState.FAILED)
            throw Exception("Registration error: ${e.message}")
        }
    }

    fun unregister(username: String, domain: String) {
        val accountKey = "$username@$domain"
        val accountInfo = activeAccounts[accountKey] ?: return

        try {
            messageHandler.sendUnregister(accountInfo)
            accountInfo.webSocketClient?.close()
            activeAccounts.remove(accountKey)
        } catch (e: Exception) {
            log.d(tag = TAG) { "Error unregistering account: ${e.message}" }
        }
    }

    private fun connectWebSocketAndRegister(accountInfo: AccountInfo) {
        try {
            accountInfo.webSocketClient?.close()
            val headers = createHeaders()
            val webSocketClient = createWebSocketClient(accountInfo, headers)
            accountInfo.webSocketClient = webSocketClient
        } catch (e: Exception) {
            log.d(tag = TAG) { "Error connecting WebSocket: ${e.stackTraceToString()}" }
        }
    }

    private fun createHeaders(): HashMap<String, String> {
        return hashMapOf(
            "User-Agent" to userAgent(),
            "Origin" to "https://telephony.${config.defaultDomain}",
            "Sec-WebSocket-Protocol" to WEBSOCKET_PROTOCOL
        )
    }

    private fun createWebSocketClient(
        accountInfo: AccountInfo,
        headers: Map<String, String>
    ): MultiplatformWebSocket {
        val websocket = WebSocket(config.webSocketUrl, headers)
        setupWebSocketListeners(websocket, accountInfo)
        websocket.connect()
        websocket.startPingTimer(config.pingIntervalMs)
        websocket.startRegistrationRenewalTimer(REGISTRATION_CHECK_INTERVAL_MS, 60000L)
        return websocket
    }

    private fun setupWebSocketListeners(websocket: WebSocket, accountInfo: AccountInfo) {
        websocket.setListener(object : MultiplatformWebSocket.Listener {
            override fun onOpen() {
                reconnectionInProgress = false
                lastConnectionCheck = Clock.System.now().toEpochMilliseconds()
                messageHandler.sendRegister(accountInfo, isAppInBackground)
            }

            override fun onMessage(message: String) {
                messageHandler.handleSipMessage(message, accountInfo)
            }

            override fun onClose(code: Int, reason: String) {
                accountInfo.isRegistered = false
                updateRegistrationState(RegistrationState.NONE)
                if (code != 1000) {
                    handleUnexpectedDisconnection(accountInfo)
                }
            }

            override fun onError(error: Exception) {
                accountInfo.isRegistered = false
                updateRegistrationState(RegistrationState.FAILED)
                handleConnectionError(accountInfo, error)
            }

            override fun onPong(timeMs: Long) {
                lastConnectionCheck = Clock.System.now().toEpochMilliseconds()
            }

            override fun onRegistrationRenewalRequired(accountKey: String) {
                val account = activeAccounts[accountKey]
                if (account != null && account.webSocketClient?.isConnected() == true) {
                    messageHandler.sendRegister(account, isAppInBackground)
                } else {
                    account?.let { reconnectAccount(it) }
                }
            }
        })
    }

    fun handleRegistrationError(accountInfo: AccountInfo, reason: String) {
        log.e(tag = TAG) { "Registration failed for ${accountInfo.username}@${accountInfo.domain}: $reason" }

        accountInfo.isRegistered = false
        updateRegistrationState(RegistrationState.FAILED)

        // Si hay un callback pendiente para llamada, ejecutarlo con fallo
        registrationCallbackForCall?.invoke(accountInfo, false)

        // Manejar reintento de registro normal
        handleRegistrationFailure()
    }

    private fun handleRegistrationFailure() {
        if (isShuttingDown) {
            log.d(tag = TAG) { "Skipping registration failure handling - shutting down" }
            return
        }

        if (connectionRetryCount >= maxRetryAttempts) {
            log.e(tag = TAG) { "Max retry attempts reached - stopping automatic reconnection" }
            return
        }

        connectionRetryCount++
        val delayMs = calculateBackoffDelay(connectionRetryCount)

        log.d(tag = TAG) { "Scheduling reconnection attempt $connectionRetryCount/$maxRetryAttempts in ${delayMs}ms" }

        CoroutineScope(Dispatchers.IO).launch {
            delay(delayMs)
            attemptReconnectionForAllAccounts()
        }
    }

    private fun attemptReconnectionForAllAccounts() {
        if (isShuttingDown) {
            log.d(tag = TAG) { "Skipping reconnection - shutting down" }
            return
        }
        log.d(tag = TAG) { "Attempting reconnection for all accounts" }

        activeAccounts.values.forEach { accountInfo ->
            if (!accountInfo.isRegistered || accountInfo.webSocketClient?.isConnected() != true) {
                log.d(tag = TAG) { "Reconnecting account: ${accountInfo.username}" }
                reconnectAccountImproved(accountInfo)
            }
        }
    }

    private fun reconnectAccountImproved(accountInfo: AccountInfo) {
        if (isShuttingDown) {
            log.d(tag = TAG) { "Skipping reconnection - shutting down" }
            return
        }

        if (reconnectionInProgress) {
            log.d(tag = TAG) { "Reconnection already in progress for ${accountInfo.username}" }
            return
        }

        reconnectionInProgress = true
        updateRegistrationState(RegistrationState.IN_PROGRESS)

        log.d(tag = TAG) { "Starting improved reconnection for: ${accountInfo.username}" }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Cerrar conexión existente limpiamente
                accountInfo.webSocketClient?.close()
                accountInfo.isRegistered = false

                // 2. Esperar un momento para que se liberen recursos
                delay(1000)

                // 3. Actualizar información de la cuenta
                accountInfo.userAgent = userAgent()

                // 4. Crear nueva conexión WebSocket
                val headers = createHeaders()
                val newWebSocketClient = createWebSocketClient(accountInfo, headers)
                accountInfo.webSocketClient = newWebSocketClient

                log.d(tag = TAG) { "Reconnection initiated for ${accountInfo.username}" }

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error during improved reconnection: ${e.message}" }
                updateRegistrationState(RegistrationState.FAILED)
                reconnectionInProgress = false

                // Programar otro intento si no hemos alcanzado el máximo
                if (connectionRetryCount < maxRetryAttempts) {
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(5000) // Esperar 5 segundos antes del siguiente intento
                        reconnectAccountImproved(accountInfo)
                    }
                }
            }
        }
    }

    private fun calculateBackoffDelay(attempt: Int): Long {
        val baseDelay = 2000L // 2 segundos base
        val maxDelay = 30000L // máximo 30 segundos
        val delay = (2.0.pow((attempt - 1).toDouble()) * baseDelay).toLong()
        return minOf(delay, maxDelay)
    }

    private fun handleUnexpectedDisconnection(accountInfo: AccountInfo) {
        if (!reconnectionInProgress) {
            CoroutineScope(Dispatchers.IO).launch {
                delay(2000)
                reconnectAccount(accountInfo)
            }
        }
    }

    private fun handleConnectionError(accountInfo: AccountInfo, error: Exception) {
        lastConnectionCheck = 0L
        when {
            error.message?.contains("timeout") == true -> {
                forceReconnectAccount(accountInfo)
            }

            else -> {
                reconnectAccount(accountInfo)
            }
        }
    }

    private fun forceReconnectAccount(accountInfo: AccountInfo) {
        reconnectAccount(accountInfo)
    }

    fun unregisterAllAccounts() {
        log.d(tag = TAG) { "Starting complete unregister and shutdown of all accounts" }

        // CRÍTICO: Marcar como shutting down PRIMERO
        isShuttingDown = true

        try {
            // 1. Detener health check inmediatamente
            healthCheckJob?.cancel()
            healthCheckJob = null
            log.d(tag = TAG) { "Health check stopped" }

            // 3. Terminar llamada activa si existe
            if (callState != CallState.NONE && callState != CallState.ENDED) {
                log.d(tag = TAG) { "Terminating active call during unregister" }
                try {
                    webRtcManager.dispose()
                    callState = CallState.ENDED
                    CallStateManager.updateCallState(CallState.ENDED)
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error terminating call: ${e.message}" }
                }
            }

            // 4. Unregister todas las cuentas
            if (activeAccounts.isNotEmpty()) {
                log.d(tag = TAG) { "Unregistering ${activeAccounts.size} accounts" }

                val accountsToUnregister = activeAccounts.toMap()

                accountsToUnregister.values.forEach { accountInfo ->
                    try {
                        log.d(tag = TAG) { "Unregistering account: ${accountInfo.username}@${accountInfo.domain}" }

                        // Detener timers del WebSocket
                        accountInfo.webSocketClient?.let { webSocket ->
                            webSocket.stopPingTimer()
                            webSocket.stopRegistrationRenewalTimer()
                        }

                        // Enviar unregister si está registrada
                        if (accountInfo.isRegistered && accountInfo.webSocketClient?.isConnected() == true) {
                            messageHandler.sendUnregister(accountInfo)
                            // Dar tiempo para que se envíe el mensaje
//                            Thread.sleep(500)
                        }

                        // Cerrar WebSocket
                        accountInfo.webSocketClient?.close(1000, "User logout")
                        accountInfo.webSocketClient = null

                        // Marcar como no registrada
                        accountInfo.isRegistered = false
                        accountInfo.resetCallState()

                        log.d(tag = TAG) { "Successfully unregistered: ${accountInfo.username}@${accountInfo.domain}" }

                    } catch (e: Exception) {
                        log.e(tag = TAG) { "Error unregistering account ${accountInfo.username}@${accountInfo.domain}: ${e.message}" }
                    }
                }
            }

            // 5. Limpiar todas las estructuras de datos
            activeAccounts.clear()
            currentAccountInfo = null

            // 6. Limpiar WebRTC completamente
            try {
                webRtcManager.setListener(null)
                webRtcManager.dispose()
                log.d(tag = TAG) { "WebRTC disposed" }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error disposing WebRTC: ${e.message}" }
            }

            // 7. Resetear todos los estados
            callState = CallState.NONE
            callStartTimeMillis = 0
            isAppInBackground = false
            reconnectionInProgress = false
            isRegistrationInProgress = false
            connectionRetryCount = 0
            lastConnectionCheck = 0L
            lastRegistrationAttempt = 0L

            // 8. Limpiar colas
            clearDtmfQueue()

            // 9. Actualizar estados
            updateRegistrationState(RegistrationState.CLEARED)
            CallStateManager.updateCallState(CallState.NONE)

            // 10. Limpiar callbacks
//            ackListener = null
//            onCallTerminated = null

            log.d(tag = TAG) { "Complete unregister and shutdown successful" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error during complete unregister: ${e.message}" }
        }

        // IMPORTANTE: NO llamar shutdown() aquí ya que ya hicimos todo
    }

    fun makeCall(phoneNumber: String, sipName: String, domain: String) {
        val accountKey = "$sipName@$domain"
        val accountInfo = activeAccounts[accountKey] ?: return
        currentAccountInfo = accountInfo

        if (!accountInfo.isRegistered) {
            log.d(tag = TAG) { "Error: Not registered with SIP server" }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                webRtcManager.setAudioEnabled(true)
                val sdp = webRtcManager.createOffer()

                val callId = generateId()
                val callData = CallData(
                    callId = callId,
                    to = phoneNumber,
                    from = accountInfo.username,
                    direction = CallDirections.OUTGOING,
                    inviteFromTag = generateSipTag(),
                    localSdp = sdp
                )

                accountInfo.currentCallData = callData
                CallStateManager.updateCallState(CallState.CALLING)
                callState = CallState.CALLING
                CallStateManager.callerNumber(phoneNumber)

                messageHandler.sendInvite(accountInfo, callData)
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error creating call: ${e.stackTraceToString()}" }
            }
        }
    }

    fun endCall() {
        val accountInfo = currentAccountInfo ?: return
        val callData = accountInfo.currentCallData ?: return

        if (callState == CallState.NONE || callState == CallState.ENDED) {
            return
        }

        val endTime = Clock.System.now().toEpochMilliseconds()

        when (callState) {
            CallState.CONNECTED, CallState.HOLDING, CallState.ACCEPTING -> {
                messageHandler.sendBye(accountInfo, callData)
                callHistoryManager.addCallLog(callData, CallTypes.SUCCESS, endTime)
            }

            CallState.CALLING, CallState.RINGING, CallState.OUTGOING -> {
                messageHandler.sendCancel(accountInfo, callData)
                callHistoryManager.addCallLog(callData, CallTypes.ABORTED, endTime)
            }

            else -> {}
        }

        CallStateManager.updateCallState(CallState.ENDED)
        callState = CallState.ENDED
        webRtcManager.dispose()
        clearDtmfQueue()
        accountInfo.resetCallState()
        onCallTerminated?.invoke()
    }

    fun acceptCall() {
        val accountInfo = currentAccountInfo ?: return
        val callData = accountInfo.currentCallData ?: return

        if (callData.direction != CallDirections.INCOMING ||
            (callState != CallState.INCOMING && callState != CallState.RINGING)
        ) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!webRtcManager.isInitialized()) {
                    webRtcManager.initialize()
                    delay(1000)
                }

                webRtcManager.prepareAudioForIncomingCall()
                delay(1000)

                val sdp = webRtcManager.createAnswer(accountInfo, callData.remoteSdp ?: "")
                callData.localSdp = sdp

                messageHandler.sendInviteOkResponse(accountInfo, callData)
                delay(500)

                webRtcManager.setAudioEnabled(true)
                webRtcManager.setMuted(false)

                callState = CallState.ACCEPTING
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error accepting call: ${e.message}" }
                rejectCall()
            }
        }
    }

    fun declineCall() {
        val accountInfo = currentAccountInfo ?: return
        val callData = accountInfo.currentCallData ?: return

        if (callData.direction != CallDirections.INCOMING ||
            (callState != CallState.INCOMING && callState != CallState.RINGING)
        ) {
            return
        }

        if (callData.toTag?.isEmpty() == true) {
            callData.toTag = generateId()
        }

        messageHandler.sendDeclineResponse(accountInfo, callData)

        val endTime = Clock.System.now().toEpochMilliseconds()
        callHistoryManager.addCallLog(callData, CallTypes.DECLINED, endTime)

        CallStateManager.updateCallState(CallState.DECLINED)
        callState = CallState.DECLINED
    }

    fun rejectCall() = declineCall()

    fun mute() {
        webRtcManager.setMuted(!webRtcManager.isMuted())
    }

    fun sendDtmf(digit: Char, duration: Int = 160): Boolean {
        val validDigits = setOf(
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
            '*', '#', 'A', 'B', 'C', 'D', 'a', 'b', 'c', 'd'
        )

        if (!validDigits.contains(digit)) {
            return false
        }

        val request = DtmfRequest(digit, duration)
        CoroutineScope(Dispatchers.IO).launch {
            dtmfMutex.withLock {
                dtmfQueue.add(request)
            }
            processDtmfQueue()
        }

        return true
    }

    fun sendDtmfSequence(digits: String, duration: Int = 160): Boolean {
        if (digits.isEmpty()) return false

        digits.forEach { digit ->
            sendDtmf(digit, duration)
        }

        return true
    }

    private suspend fun processDtmfQueue() = withContext(Dispatchers.IO) {
        dtmfMutex.withLock {
            if (isDtmfProcessing || dtmfQueue.isEmpty()) {
                return@withLock
            }
            isDtmfProcessing = true
        }

        try {
            while (true) {
                val request: DtmfRequest? = dtmfMutex.withLock {
                    if (dtmfQueue.isNotEmpty()) {
                        dtmfQueue.removeAt(0)
                    } else {
                        null
                    }
                }

                if (request == null) break

                val success = sendSingleDtmf(request.digit, request.duration)
                if (success) {
                    delay(150) // Gap between digits
                }
            }
        } finally {
            dtmfMutex.withLock {
                isDtmfProcessing = false
            }
        }
    }

    private suspend fun sendSingleDtmf(digit: Char, duration: Int): Boolean {
        val currentAccount = currentAccountInfo
        val callData = currentAccount?.currentCallData

        if (currentAccount == null || callData == null || callState != CallState.CONNECTED) {
            return false
        }

        return try {
            // Usar WebRTC para DTMF en Android
            webRtcManager.sendDtmfTones(
                tones = digit.toString().uppercase(),
                duration = duration,
                gap = 100
            )
        } catch (e: Exception) {
            false
        }
    }

    fun clearDtmfQueue() {
        CoroutineScope(Dispatchers.IO).launch {
            dtmfMutex.withLock {
                dtmfQueue.clear()
                isDtmfProcessing = false
            }
        }
    }

    fun holdCall() {
        val accountInfo = currentAccountInfo ?: return
        val callData = accountInfo.currentCallData ?: return

        CoroutineScope(Dispatchers.IO).launch {
            callHoldManager.holdCall()?.let { holdSdp ->
                callData.localSdp = holdSdp
                callData.isOnHold = true
                messageHandler.sendReInvite(accountInfo, callData, holdSdp)
                callState = CallState.HOLDING
            }
        }
    }

    fun resumeCall() {
        val accountInfo = currentAccountInfo ?: return
        val callData = accountInfo.currentCallData ?: return

        CoroutineScope(Dispatchers.IO).launch {
            callHoldManager.resumeCall()?.let { resumeSdp ->
                callData.localSdp = resumeSdp
                callData.isOnHold = false
                messageHandler.sendReInvite(accountInfo, callData, resumeSdp)
                callState = CallState.CONNECTED
            }
        }
    }

    fun clearCallLogs() = callHistoryManager.clearCallLogs()
    fun callLogs(): List<CallLog> = callHistoryManager.getAllCallLogs()
    fun getCallStatistics() = callHistoryManager.getCallStatistics()
    fun getMissedCalls(): List<CallLog> = callHistoryManager.getMissedCalls()
    fun getCallLogsForNumber(phoneNumber: String): List<CallLog> =
        callHistoryManager.getCallLogsForNumber(phoneNumber)

    fun getRegistrationState(): RegistrationState = registrationState
    fun currentCall(): Boolean = callState != CallState.NONE && callState != CallState.ENDED
    fun currentCallConnected(): Boolean = callState == CallState.CONNECTED

    fun isSipCoreManagerHealthy(): Boolean {
        return try {
            webRtcManager.isInitialized() &&
                    activeAccounts.isNotEmpty() &&
                    !reconnectionInProgress
        } catch (e: Exception) {
            false
        }
    }

    fun getSystemHealthReport(): String {
        return buildString {
            appendLine("=== SIP Core Manager Health Report ===")
            appendLine("Overall Health: ${if (isSipCoreManagerHealthy()) "✅ HEALTHY" else "❌ UNHEALTHY"}")
            appendLine("WebRTC Initialized: ${webRtcManager.isInitialized()}")
            appendLine("Active Accounts: ${activeAccounts.size}")
            appendLine("Current Call State: $callState")
            appendLine("Registration State: $registrationState")
        }
    }

    fun enterPushMode(token: String? = null) {
        token?.let { newToken ->
            activeAccounts.values.forEach { accountInfo ->
                accountInfo.token = newToken
            }
        }
    }

    private fun determineCallType(callData: CallData, finalState: CallState): CallTypes {
        return when (finalState) {
            CallState.CONNECTED, CallState.ENDED -> CallTypes.SUCCESS
            CallState.DECLINED -> CallTypes.DECLINED
            CallState.CALLING, CallState.RINGING -> CallTypes.ABORTED
            else -> if (callData.direction == CallDirections.INCOMING) {
                CallTypes.MISSED
            } else {
                CallTypes.ABORTED
            }
        }
    }

    fun dispose() {
        webRtcManager.dispose()
        activeAccounts.clear()
    }

    fun getMessageHandler(): SipMessageHandler = messageHandler
}