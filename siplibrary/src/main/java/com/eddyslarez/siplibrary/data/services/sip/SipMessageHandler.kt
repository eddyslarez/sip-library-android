package com.eddyslarez.siplibrary.data.services.sip

import com.eddyslarez.siplibrary.core.SipCoreManager
import com.eddyslarez.siplibrary.data.models.*
import com.eddyslarez.siplibrary.data.services.sip.AuthenticationHandler
import com.eddyslarez.siplibrary.data.services.sip.SipMessageBuilder
import com.eddyslarez.siplibrary.data.services.sip.SipMessageParser
import com.eddyslarez.siplibrary.utils.CallStateManager
import com.eddyslarez.siplibrary.utils.generateId
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * Manejador de mensajes SIP con gestión mejorada de estados
 *
 * @author Eddys Larez
 */
class SipMessageHandler(private val sipCoreManager: SipCoreManager) {

    private val TAG = "SipMessageHandler"

    /**
     * Maneja mensajes SIP entrantes
     */
   suspend fun handleSipMessage(message: String, accountInfo: AccountInfo) {
        try {
            SipMessageParser.logIncomingMessage(message)

            val lines = message.split("\r\n")
            val firstLine = lines.firstOrNull() ?: return

            when {
                // Respuestas SIP
                firstLine.startsWith("SIP/2.0") -> handleSipResponse(firstLine, lines, accountInfo)

                // Requests SIP

                firstLine.startsWith("REFER") -> handleReferRequest(lines, accountInfo)
                firstLine.startsWith("NOTIFY") -> handleNotifyRequest(lines, accountInfo)
                firstLine.startsWith("INVITE") -> handleInviteRequest(lines, accountInfo)
                firstLine.startsWith("BYE") -> handleByeRequest(lines, accountInfo)
                firstLine.startsWith("CANCEL") -> handleCancelRequest(lines, accountInfo)
                firstLine.startsWith("ACK") -> handleAckRequest(lines, accountInfo)
                firstLine.startsWith("INFO") -> handleInfoRequest(lines, accountInfo)
                firstLine.startsWith("OPTIONS") -> handleOptionsRequest(lines, accountInfo)

                else -> {
                    log.d(tag = TAG) { "Unknown SIP message type: $firstLine" }
                }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error handling SIP message: ${e.message}" }
        }
    }

    /**
     * Maneja respuestas SIP
     */
    private suspend fun handleSipResponse(
        firstLine: String,
        lines: List<String>,
        accountInfo: AccountInfo
    ) {
        val statusCode = extractStatusCode(firstLine)
        val method = SipMessageParser.extractMethodFromCSeq(firstLine, lines)

        log.d(tag = TAG) { "Handling SIP response: $statusCode for method: $method" }

        when (method) {
            "REGISTER" -> handleRegisterResponse(statusCode, lines, accountInfo)
            "INVITE" -> handleInviteResponse(statusCode, lines, accountInfo)
            "BYE" -> handleByeResponse(statusCode, lines, accountInfo)
            "CANCEL" -> handleCancelResponse(statusCode, lines, accountInfo)
            "ACK" -> handleAckResponse(statusCode, lines, accountInfo)
            else -> {
                log.d(tag = TAG) { "Unhandled response for method: $method" }
            }
        }
    }

    /**
     * Maneja respuestas de REGISTER
     */
    private suspend fun handleRegisterResponse(
        statusCode: Int,
        lines: List<String>,
        accountInfo: AccountInfo
    ) {
        val accountKey = "${accountInfo.username}@${accountInfo.domain}"
        val fullResponse = lines.joinToString("\r\n")
        val reason = SipMessageParser.extractStatusReason(fullResponse)

        when (statusCode) {
            200 -> {
                CoroutineScope(Dispatchers.IO).launch {
                    log.d(tag = TAG) { "Registration successful for $accountKey" }
                    sipCoreManager.handleRegistrationSuccess(accountInfo)

                    // Extraer tiempo de expiración
                    val expiresValue = SipMessageParser.extractExpiresValue(fullResponse)
                    val expirationTime =
                        Clock.System.now().toEpochMilliseconds() + (expiresValue * 1000L)

                    // Configurar renovación automática
                    accountInfo.webSocketClient?.setRegistrationExpiration(
                        accountKey,
                        expirationTime
                    )
                }
            }

            401, 407 -> {
                log.d(tag = TAG) { "Authentication required for registration: $statusCode" }
                handleAuthenticationChallenge(lines, accountInfo, "REGISTER")
            }

            403 -> {
                CoroutineScope(Dispatchers.IO).launch {
                    log.e(tag = TAG) { "Registration forbidden: $reason" }
                    // Error grave, probablemente credenciales incorrectas o cuenta deshabilitada.
                    // No se debe reintentar agresivamente.
                    sipCoreManager.handleRegistrationError(
                        accountInfo,
                        "Forbidden: $reason"
                    )
                }
            }

            in 400..499 -> {
                CoroutineScope(Dispatchers.IO).launch {
                    log.e(tag = TAG) { "Registration client error: $statusCode - $reason" }
                    // Otros errores de cliente. Podrían ser temporales (e.g., 423 Interval Too Brief)
                    // o permanentes. Por defecto, se reintenta pero con cuidado.
                    val retryAfterMs = SipMessageParser.parseRetryAfter(fullResponse)?.times(1000L)
                    sipCoreManager.handleRegistrationError(
                        accountInfo,
                        "Client error: $reason",
                    )
                }
            }

            in 500..599 -> {
                CoroutineScope(Dispatchers.IO).launch {
                    log.e(tag = TAG) { "Registration server error: $statusCode - $reason" }
                    // Error del servidor. Se debe reintentar, pero usando el encabezado Retry-After si está presente.
                    val retryAfterMs = SipMessageParser.parseRetryAfter(fullResponse)?.times(1000L)

                    // Lógica simple para diferenciar errores temporales vs. graves
                    val isTemporaryServerError = statusCode == 503 || statusCode == 504

                    sipCoreManager.handleRegistrationError(
                        accountInfo,
                        "Server error: $reason",
                    )
                }
            }

            else -> {
                CoroutineScope(Dispatchers.IO).launch {
                    log.w(tag = TAG) { "Unhandled registration response: $statusCode" }
                    // Para códigos no manejados, se asume un error temporal y se reintenta.
                    sipCoreManager.handleRegistrationError(
                        accountInfo,
                        "Unhandled status: $statusCode",
                    )
                }
            }
        }
    }

    /**
     * Maneja respuestas de INVITE
     */
    private suspend fun handleInviteResponse(
        statusCode: Int,
        lines: List<String>,
        accountInfo: AccountInfo
    ) {
        val callData = accountInfo.currentCallData ?: return
        val cseqHeader = SipMessageParser.extractHeader(lines, "CSeq")
        val isReInvite = cseqHeader.contains("INVITE") &&
                CallStateManager.getCurrentState().let { state ->
                    state.state == CallState.PAUSING ||
                            state.state == CallState.RESUMING ||
                            state.state == CallState.STREAMS_RUNNING ||
                            state.state == CallState.PAUSED
                }
        when (statusCode) {
            100 -> {
                log.d(tag = TAG) { "Received 100 Trying for ${if (isReInvite) "re-INVITE" else "INVITE"}" }
            }

            180 -> {
                if (!isReInvite) {
                    log.d(tag = TAG) { "Received 180 Ringing" }
                    CallStateManager.outgoingCallRinging(callData.callId, 180)
                    sipCoreManager.audioManager.playOutgoingRingtone()
                    sipCoreManager.notifyCallStateChanged(CallState.OUTGOING_RINGING)
                }
            }

            183 -> {
                if (!isReInvite) {
                    log.d(tag = TAG) { "Received 183 Session Progress" }
                    CallStateManager.outgoingCallProgress(callData.callId, 183)
                    sipCoreManager.notifyCallStateChanged(CallState.OUTGOING_PROGRESS)
                }
            }

            200 -> {
                if (isReInvite) {
                    log.d(tag = TAG) { "Received 200 OK for re-INVITE (hold/resume)" }
                    handle200OKForReInvite(lines, accountInfo, callData)
                } else {
                    log.d(tag = TAG) { "Received 200 OK for INVITE - Call connected" }
                    handle200OKForInvite(lines, accountInfo, callData)
                }
            }

            401, 407 -> {
                log.d(tag = TAG) { "Authentication required for INVITE: $statusCode" }
                handleAuthenticationChallenge(lines, accountInfo, "INVITE")
            }

            486 -> {
                log.d(tag = TAG) { "Received 486 Busy Here" }
                handleCallError(callData, statusCode, "Busy Here", CallErrorReason.BUSY)
            }

            487 -> {
                log.d(tag = TAG) { "Received 487 Request Terminated" }
                handleCallCancelled(callData, lines, accountInfo)
            }

            603 -> {
                log.d(tag = TAG) { "Received 603 Decline" }
                handleCallError(callData, statusCode, "Declined", CallErrorReason.REJECTED)
            }

            408 -> {
                log.d(tag = TAG) { "Received 408 Request Timeout" }
                handleCallError(callData, statusCode, "No Answer", CallErrorReason.NO_ANSWER)
            }

            480 -> {
                log.d(tag = TAG) { "Received 480 Temporarily Unavailable" }
                handleCallError(
                    callData,
                    statusCode,
                    "Temporarily Unavailable",
                    CallErrorReason.TEMPORARILY_UNAVAILABLE
                )
            }

            404 -> {
                log.d(tag = TAG) { "Received 404 Not Found" }
                handleCallError(callData, statusCode, "Not Found", CallErrorReason.NOT_FOUND)
            }

            in 400..499 -> {
                val reason = SipMessageParser.extractStatusReason(lines.joinToString("\r\n"))
                log.e(tag = TAG) { "INVITE client error: $statusCode - $reason" }
                handleCallError(callData, statusCode, reason, CallErrorReason.UNKNOWN)
            }

            in 500..599 -> {
                val reason = SipMessageParser.extractStatusReason(lines.joinToString("\r\n"))
                log.e(tag = TAG) { "INVITE server error: $statusCode - $reason" }
                handleCallError(callData, statusCode, reason, CallErrorReason.SERVER_ERROR)
            }

            else -> {
                log.w(tag = TAG) { "Unhandled INVITE response: $statusCode" }
            }
        }
    }

    /**
     * NUEVO: Maneja 200 OK para re-INVITE (hold/resume)
     */
    private fun handle200OKForReInvite(
        lines: List<String>,
        accountInfo: AccountInfo,
        callData: CallData
    ) {
        try {
            log.d(tag = TAG) { "Processing 200 OK for re-INVITE" }

            val remoteSdp = SipMessageParser.extractSdpContent(lines.joinToString("\r\n"))
            val currentState = CallStateManager.getCurrentState()

            // Enviar ACK para re-INVITE
            val ack = SipMessageBuilder.buildAckMessage(accountInfo, callData)
            accountInfo.webSocketClient?.send(ack)

            // Determinar si es hold o resume basado en SDP
            val isRemoteOnHold = SipMessageParser.isSdpOnHold(remoteSdp)

            when (currentState.state) {
                CallState.PAUSING -> {
                    CoroutineScope(Dispatchers.IO).launch {
                        // Completar transición a hold
                        CallStateManager.callOnHold(callData.callId)
                        sipCoreManager.notifyCallStateChanged(CallState.PAUSED)
                        log.d(tag = TAG) { "Call successfully put on hold" }
                    }
                }

                CallState.RESUMING -> {
                    CoroutineScope(Dispatchers.IO).launch {
                        // Completar transición a resume
                        CallStateManager.callResumed(callData.callId)
                        sipCoreManager.notifyCallStateChanged(CallState.STREAMS_RUNNING)
                        log.d(tag = TAG) { "Call successfully resumed" }
                    }
                }

                else -> {
                    log.w(tag = TAG) { "Unexpected re-INVITE 200 OK in state: ${currentState.state}" }
                }
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error processing re-INVITE 200 OK: ${e.message}" }
        }
    }

    /**
     * Maneja 200 OK para INVITE
     */
    private fun handle200OKForInvite(
        lines: List<String>,
        accountInfo: AccountInfo,
        callData: CallData
    ) {
        try {
            // CRÍTICO: Detener tono ANTES de cualquier otra operación
            sipCoreManager.audioManager.stopAllRingtones()

            log.d(tag = TAG) { "Procesando 200 OK para call: ${callData.callId}" }

            // Extraer headers y SDP
            val toTag = SipMessageParser.extractTag(SipMessageParser.extractHeader(lines, "To"))
            val contactUri = SipMessageParser.extractUriFromContact(
                SipMessageParser.extractHeader(
                    lines,
                    "Contact"
                )
            )
            val remoteSdp = SipMessageParser.extractSdpContent(lines.joinToString("\r\n"))

            // Validar que tenemos SDP remoto
            if (remoteSdp.isEmpty()) {
                log.e(tag = TAG) { "ERROR: No remote SDP in 200 OK response" }
                handleCallError(callData, 200, "No remote SDP", CallErrorReason.NETWORK_ERROR)
                return
            }

            // Guardar en callData
            callData.inviteToTag = toTag
            callData.remoteContactUri = contactUri
            callData.remoteSdp = remoteSdp

            // CORREGIDO: Estado de conexión primero
            CallStateManager.callConnected(callData.callId, 200)
            sipCoreManager.notifyCallStateChanged(CallState.CONNECTED)

            // Configurar WebRTC y luego transicionar a STREAMS_RUNNING
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Establecer SDP remoto
                    sipCoreManager.webRtcManager.setRemoteDescription(
                        remoteSdp,
                        com.eddyslarez.siplibrary.data.services.audio.SdpType.ANSWER
                    )

                    // CRÍTICO: Enviar ACK antes de activar audio
                    val ack = SipMessageBuilder.buildAckMessage(accountInfo, callData)
                    accountInfo.webSocketClient?.send(ack)
                    log.d(tag = TAG) { "ACK enviado para call: ${callData.callId}" }

                    // Esperar un momento para que se establezca la conexión
                    delay(500)

                    // Activar audio
                    sipCoreManager.webRtcManager.setAudioEnabled(true)
                    sipCoreManager.webRtcManager.setMuted(false)

                    // TRANSICIÓN AUTOMÁTICA A STREAMS_RUNNING
                    CallStateManager.streamsRunning(callData.callId)
                    sipCoreManager.notifyCallStateChanged(CallState.STREAMS_RUNNING)

                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error en WebRTC tras 200 OK: ${e.message}" }
                    handleCallError(
                        callData,
                        200,
                        "Fallo WebRTC: ${e.message}",
                        CallErrorReason.NETWORK_ERROR
                    )
                }
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error procesando 200 OK: ${e.message}" }
            handleCallError(callData, 200, "Error procesando respuesta", CallErrorReason.UNKNOWN)
        }
    }


    /**
     * Maneja errores de llamada
     */
    private fun handleCallError(
        callData: CallData,
        sipCode: Int?,
        reason: String,
        errorReason: CallErrorReason
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            // Detener todos los ringtones
            sipCoreManager.audioManager.stopAllRingtones()

        // Actualizar estado de error
        CallStateManager.callError(callData.callId, sipCode, reason, errorReason)
        sipCoreManager.notifyCallStateChanged(CallState.ERROR)

            // Limpiar recursos
            cleanupCall(callData)
        }
    }

    /**
     * Maneja llamada cancelada (487)
     */
    private fun handleCallCancelled(
        callData: CallData,
        lines: List<String>,
        accountInfo: AccountInfo
    ) {
        CoroutineScope(Dispatchers.IO).launch {

            try {

                log.d(tag = TAG) { "Processing 487 Request Terminated for call: ${callData.callId}" }

                // Detener outgoing ringtone
                sipCoreManager.audioManager.stopOutgoingRingtone()

                // CRÍTICO: Enviar ACK para 487 usando headers correctos
                val ackMessage =
                    SipMessageBuilder.buildAckFor487Response(accountInfo, callData, lines)
                accountInfo.webSocketClient?.send(ackMessage)
                log.d(tag = TAG) { "ACK sent for 487 response" }

            // Actualizar estado
            CallStateManager.callEnded(callData.callId, 487, "Request Terminated")
            sipCoreManager.notifyCallStateChanged(CallState.ENDED)

                // Limpiar recursos
                cleanupCall(callData)

                log.d(tag = TAG) { "Call cancellation completed successfully" }

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error handling call cancellation: ${e.message}" }
            }
        }
    }

    /**
     * Maneja request INVITE entrante
     */
    private suspend fun handleInviteRequest(lines: List<String>, accountInfo: AccountInfo) {
        try {
            log.d(tag = TAG) { "Handling incoming INVITE request" }

            // CRÍTICO: Detener cualquier ringtone existente primero
            sipCoreManager.audioManager.stopAllRingtones()

            sipCoreManager.currentAccountInfo = accountInfo

            // Extraer headers MEJORADO
            val fromHeader = SipMessageParser.extractHeader(lines, "From")
            val toHeader = SipMessageParser.extractHeader(lines, "To")
            val callIdHeader = SipMessageParser.extractHeader(lines, "Call-ID")
            val viaHeader = SipMessageParser.extractHeader(lines, "Via")
            val contactHeader = SipMessageParser.extractHeader(lines, "Contact")
            val cseqHeader = SipMessageParser.extractHeader(lines, "CSeq")
            val remoteSdp = SipMessageParser.extractSdpContent(lines.joinToString("\r\n"))

            // VALIDACIÓN CRÍTICA
            if (callIdHeader.isEmpty() || fromHeader.isEmpty() || viaHeader.isEmpty()) {
                log.e(tag = TAG) { "ERROR: Missing required headers in INVITE" }
                return
            }

            val fromUri = SipMessageParser.extractUriFromHeader(fromHeader)
            val fromUser = SipMessageParser.extractUserFromUri(fromUri)
            val fromTag = SipMessageParser.extractTag(fromHeader)
            val displayName = SipMessageParser.extractDisplayName(fromHeader)

            // CRÍTICO: Extraer Contact URI correctamente
            val remoteContactUri = if (contactHeader.isNotEmpty()) {
                SipMessageParser.extractUriFromContact(contactHeader)
            } else {
                "sip:$fromUser@${accountInfo.domain}"
            }

            // Crear datos de llamada entrante
            val callData = CallData(
                callId = callIdHeader,
                from = fromUser,
                to = accountInfo.username,
                direction = CallDirections.INCOMING,
                startTime = Clock.System.now().toEpochMilliseconds(),
                fromTag = fromTag,
                toTag = generateId(),
                remoteContactUri = remoteContactUri, // CRÍTICO: Guardar correctamente
                remoteDisplayName = displayName,
                remoteSdp = remoteSdp,
                via = viaHeader,
                originalInviteMessage = lines.joinToString("\r\n")
            )

            // CRÍTICO: Extraer y almacenar CSeq
            val cseqParts = cseqHeader.split(" ")
            if (cseqParts.size >= 2) {
                callData.lastCSeqValue = cseqParts[0].toIntOrNull() ?: 1
            }

            accountInfo.currentCallData = callData
            callData.storeInviteMessage(lines.joinToString("\r\n"))

            // CRÍTICO: Actualizar CSeq del account para respuestas
            SipMessageParser.updateCSeqIfPresent(lines, accountInfo)

            // Estado de llamada entrante
            CallStateManager.incomingCallReceived(callData.callId, fromUser)
            sipCoreManager.notifyCallStateChanged(CallState.INCOMING_RECEIVED)

            // Enviar respuestas SIP
            val tryingResponse = SipMessageBuilder.buildTryingResponse(accountInfo, callData)
            accountInfo.webSocketClient?.send(tryingResponse)

            CoroutineScope(Dispatchers.IO).launch {
                delay(100)
                val ringingResponse = SipMessageBuilder.buildRingingResponse(accountInfo, callData)
                accountInfo.webSocketClient?.send(ringingResponse)

                delay(200)
//                sipCoreManager.audioManager.playRingtone()
            }

            log.d(tag = TAG) { "Incoming call setup completed for ${callData.callId}" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error handling incoming INVITE: ${e.message}" }
            sipCoreManager.audioManager.stopAllRingtones()
        }
    }

    /**
     * Maneja request BYE
     */
    /**
     * Maneja request BYE
     */
    private fun handleByeRequest(lines: List<String>, accountInfo: AccountInfo) {
        log.d(tag = TAG) { "Handling BYE request" }

        try {
            // Detener todos los ringtones
            sipCoreManager.audioManager.stopAllRingtones()

            // Enviar 200 OK para BYE
            val okResponse = SipMessageBuilder.buildByeOkResponse(accountInfo, lines)
            accountInfo.webSocketClient?.send(okResponse)

            // Actualizar estado de llamada
            accountInfo.currentCallData?.let { callData ->
                CallStateManager.callEnded(callData.callId, sipReason = "Remote hangup")
                sipCoreManager.notifyCallStateChanged(CallState.ENDED)

                // NUEVO: Notificar al PushModeManager que la llamada terminó para esta cuenta específica
                val accountKey = "${accountInfo.username}@${accountInfo.domain}"
                sipCoreManager.notifyCallEndedForSpecificAccount(accountKey)

                // Limpiar recursos
                cleanupCall(callData)
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error handling BYE request: ${e.message}" }
        }
    }

    /**
     * Maneja request CANCEL
     */
    private fun handleCancelRequest(lines: List<String>, accountInfo: AccountInfo) {
        log.d(tag = TAG) { "Handling CANCEL request" }

        try {
            // CRÍTICO: Detener ringtone INMEDIATAMENTE y DEFINITIVAMENTE
            sipCoreManager.audioManager.stopAllRingtones()

            // Dar tiempo para que se detenga completamente
            CoroutineScope(Dispatchers.IO).launch {
                delay(100) // Pequeño delay para asegurar que se detiene
                sipCoreManager.audioManager.stopAllRingtones() // Doble verificación
            }

            // Enviar 200 OK para CANCEL
            val cancelOkResponse = SipMessageBuilder.buildCancelOkResponse(accountInfo, lines)
            accountInfo.webSocketClient?.send(cancelOkResponse)

            // Enviar 487 Request Terminated para el INVITE original
            accountInfo.currentCallData?.let { callData ->
                val requestTerminatedResponse =
                    SipMessageBuilder.buildRequestTerminatedResponse(accountInfo, callData)
                accountInfo.webSocketClient?.send(requestTerminatedResponse)

                // Actualizar estado
                CallStateManager.callEnded(callData.callId, 487, "Request Terminated")
                sipCoreManager.notifyCallStateChanged(CallState.ENDED)

                // NUEVO: Notificar al PushModeManager que la llamada terminó para esta cuenta específica
                val accountKey = "${accountInfo.username}@${accountInfo.domain}"
                sipCoreManager.notifyCallEndedForSpecificAccount(accountKey)

                // Limpiar recursos
                cleanupCall(callData)
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error handling CANCEL request: ${e.message}" }
            // En caso de error, forzar detención de ringtones
            sipCoreManager.audioManager.stopAllRingtones()
        }
    }

    /**
     * Maneja request ACK
     */
    private fun handleAckRequest(lines: List<String>, accountInfo: AccountInfo) {
        log.d(tag = TAG) { "Handling ACK request" }

        // ACK confirma que la llamada está establecida
        accountInfo.currentCallData?.let { callData ->
            if (CallStateManager.getCurrentState().state == CallState.CONNECTED) {
                // Transición a streams running
                CallStateManager.streamsRunning(callData.callId)
                sipCoreManager.notifyCallStateChanged(CallState.STREAMS_RUNNING)
            }
        }
    }

    /**
     * Maneja request INFO (para DTMF)
     */
    private fun handleInfoRequest(lines: List<String>, accountInfo: AccountInfo) {
        log.d(tag = TAG) { "Handling INFO request" }

        try {
            // Enviar 200 OK para INFO
            val okResponse = buildInfoOkResponse(lines)
            accountInfo.webSocketClient?.send(okResponse)

            // Procesar contenido DTMF si existe
            val contentType = SipMessageParser.extractHeader(lines, "Content-Type")
            if (contentType.contains("application/dtmf-relay", ignoreCase = true)) {
                val content = SipMessageParser.extractSdpContent(lines.joinToString("\r\n"))
                processDtmfContent(content, accountInfo)
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error handling INFO request: ${e.message}" }
        }
    }

    /**
     * Maneja request OPTIONS
     */
    private fun handleOptionsRequest(lines: List<String>, accountInfo: AccountInfo) {
        log.d(tag = TAG) { "Handling OPTIONS request" }

        try {
            val optionsResponse = buildOptionsResponse(lines)
            accountInfo.webSocketClient?.send(optionsResponse)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error handling OPTIONS request: ${e.message}" }
        }
    }

    /**
     * Maneja respuestas de BYE
     */
    private fun handleByeResponse(statusCode: Int, lines: List<String>, accountInfo: AccountInfo) {
        when (statusCode) {
            200 -> {
                log.d(tag = TAG) { "BYE confirmed with 200 OK" }
                accountInfo.currentCallData?.let { callData ->
                    cleanupCall(callData)
                }
            }

            else -> {
                log.w(tag = TAG) { "Unexpected BYE response: $statusCode" }
            }
        }
    }

    /**
     * Maneja respuestas de CANCEL
     */
    private fun handleCancelResponse(
        statusCode: Int,
        lines: List<String>,
        accountInfo: AccountInfo
    ) {
        when (statusCode) {
            200 -> {
                log.d(tag = TAG) { "CANCEL confirmed with 200 OK" }
                // El 487 debería llegar por separado
            }

            else -> {
                log.w(tag = TAG) { "Unexpected CANCEL response: $statusCode" }
            }
        }
    }

    /**
     * Maneja respuestas de ACK
     */
    private fun handleAckResponse(statusCode: Int, lines: List<String>, accountInfo: AccountInfo) {
        // ACK no debería tener respuestas normalmente
        log.d(tag = TAG) { "Received unexpected ACK response: $statusCode" }
    }

    /**
     * Maneja desafío de autenticación
     */
    private suspend fun handleAuthenticationChallenge(
        lines: List<String>,
        accountInfo: AccountInfo,
        method: String
    ) {
        try {
            log.d(tag = TAG) { "Handling authentication challenge for $method" }

            val authData = AuthenticationHandler.extractAuthenticationData(lines)
            if (authData == null) {
                log.e(tag = TAG) { "Failed to extract authentication data" }
                return
            }

            val response =
                AuthenticationHandler.calculateAuthResponse(accountInfo, authData, method)
            AuthenticationHandler.updateAccountAuthInfo(accountInfo, authData, response, method)

            // Reenviar request con autenticación
            when (method) {
                "REGISTER" -> {
                    val authenticatedRegister = SipMessageBuilder.buildAuthenticatedRegisterMessage(
                        accountInfo,
                        sipCoreManager.isAppInBackground
                    )
                    accountInfo.webSocketClient?.send(authenticatedRegister)
                }

                "INVITE" -> {
                    accountInfo.currentCallData?.let { callData ->
                        val authenticatedInvite = SipMessageBuilder.buildAuthenticatedInviteMessage(
                            accountInfo,
                            callData,
                            callData.localSdp
                        )
                        callData.originalCallInviteMessage = authenticatedInvite

                        accountInfo.webSocketClient?.send(authenticatedInvite)
                    }
                }
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error handling authentication challenge: ${e.message}" }
        }
    }

    /**
     * Limpia recursos de llamada
     */
    private fun cleanupCall(callData: CallData) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Detener todos los ringtones
                sipCoreManager.audioManager.stopAllRingtones()

                // Limpiar WebRTC
                sipCoreManager.webRtcManager.dispose()

                // Limpiar datos de cuenta
                sipCoreManager.currentAccountInfo?.resetCallState()

                // Limpiar DTMF
                sipCoreManager.clearDtmfQueue()

                log.d(tag = TAG) { "Call cleanup completed for ${callData.callId}" }

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error during call cleanup: ${e.message}" }
            }
        }
    }

    /**
     * Procesa contenido DTMF
     */
    private fun processDtmfContent(content: String, accountInfo: AccountInfo) {
        try {
            val lines = content.split("\r\n")
            var signal: Char? = null
            var duration: Int = 160

            lines.forEach { line ->
                when {
                    line.startsWith("Signal=") -> {
                        signal = line.substringAfter("Signal=").firstOrNull()
                    }

                    line.startsWith("Duration=") -> {
                        duration = line.substringAfter("Duration=").toIntOrNull() ?: 160
                    }
                }
            }

            signal?.let { digit ->
                log.d(tag = TAG) { "Received DTMF: $digit (duration: $duration)" }
                // Aquí puedes notificar al callback de DTMF recibido
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error processing DTMF content: ${e.message}" }
        }
    }

    /**
     * Construye respuesta OK para INFO
     */
    private fun buildInfoOkResponse(lines: List<String>): String {
        val viaHeader = SipMessageParser.extractHeader(lines, "Via")
        val fromHeader = SipMessageParser.extractHeader(lines, "From")
        val toHeader = SipMessageParser.extractHeader(lines, "To")
        val callId = SipMessageParser.extractHeader(lines, "Call-ID")
        val cseqHeader = SipMessageParser.extractHeader(lines, "CSeq")

        return buildString {
            append("SIP/2.0 200 OK\r\n")
            append("Via: $viaHeader\r\n")
            append("From: $fromHeader\r\n")
            append("To: $toHeader\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: $cseqHeader\r\n")
            append("Content-Length: 0\r\n\r\n")
        }
    }

    /**
     * Construye respuesta para OPTIONS
     */
    private fun buildOptionsResponse(lines: List<String>): String {
        val viaHeader = SipMessageParser.extractHeader(lines, "Via")
        val fromHeader = SipMessageParser.extractHeader(lines, "From")
        val toHeader = SipMessageParser.extractHeader(lines, "To")
        val callId = SipMessageParser.extractHeader(lines, "Call-ID")
        val cseqHeader = SipMessageParser.extractHeader(lines, "CSeq")

        return buildString {
            append("SIP/2.0 200 OK\r\n")
            append("Via: $viaHeader\r\n")
            append("From: $fromHeader\r\n")
            append("To: $toHeader\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: $cseqHeader\r\n")
            append("Allow: INVITE, ACK, CANCEL, BYE, INFO, OPTIONS\r\n")
            append("Accept: application/sdp, application/dtmf-relay\r\n")
            append("Content-Length: 0\r\n\r\n")
        }
    }

    /**
     * Extrae código de estado de la primera línea
     */
    private fun extractStatusCode(firstLine: String): Int {
        return try {
            val parts = firstLine.split(" ")
            if (parts.size >= 2) {
                parts[1].toInt()
            } else {
                0
            }
        } catch (e: Exception) {
            0
        }
    }

    // === MÉTODOS PARA ENVIAR MENSAJES ===

    /**
     * Envía mensaje REGISTER
     */
   suspend fun sendRegister(accountInfo: AccountInfo, isAppInBackground: Boolean) {
        try {
            val callId = accountInfo.callId ?: generateId()
            val fromTag = accountInfo.fromTag ?: generateId()

            accountInfo.callId = callId
            accountInfo.fromTag = fromTag

            val registerMessage = SipMessageBuilder.buildRegisterMessage(
                accountInfo, callId, fromTag, isAppInBackground
            )

            accountInfo.webSocketClient?.send(registerMessage)
            log.d(tag = TAG) { "REGISTER sent for ${accountInfo.username}@${accountInfo.domain}" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending REGISTER: ${e.message}" }
        }
    }

    /**
     * Envía mensaje UNREGISTER
     */
   suspend fun sendUnregister(accountInfo: AccountInfo) {
        try {
            val callId = accountInfo.callId ?: generateId()
            val fromTag = accountInfo.fromTag ?: generateId()

            val unregisterMessage = SipMessageBuilder.buildUnregisterMessage(
                accountInfo, callId, fromTag
            )

            accountInfo.webSocketClient?.send(unregisterMessage)
            log.d(tag = TAG) { "UNREGISTER sent for ${accountInfo.username}@${accountInfo.domain}" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending UNREGISTER: ${e.message}" }
        }
    }

    /**
     * Envía mensaje INVITE
     */
   suspend fun sendInvite(accountInfo: AccountInfo, callData: CallData) {
        try {
            val inviteMessage = SipMessageBuilder.buildInviteMessage(
                accountInfo, callData, callData.localSdp
            )

            callData.originalCallInviteMessage = inviteMessage
            accountInfo.webSocketClient?.send(inviteMessage)

            log.d(tag = TAG) { "INVITE sent for call ${callData.callId}" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending INVITE: ${e.message}" }
        }
    }

    /**
     * Envía mensaje BYE
     */
    suspend fun sendBye(accountInfo: AccountInfo, callData: CallData) {
        try {
            val byeMessage = SipMessageBuilder.buildByeMessage(accountInfo, callData)
            accountInfo.webSocketClient?.send(byeMessage)

            log.d(tag = TAG) { "BYE sent for call ${callData.callId}" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending BYE: ${e.message}" }
        }
    }

    /**
     * Envía mensaje CANCEL
     */
    suspend fun sendCancel(accountInfo: AccountInfo, callData: CallData) {
        try {
            val cancelMessage = SipMessageBuilder.buildCancelMessage(accountInfo, callData)
            accountInfo.webSocketClient?.send(cancelMessage)

            log.d(tag = TAG) { "CANCEL sent for call ${callData.callId}" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending CANCEL: ${e.message}" }
        }
    }

    /**
     * Envía respuesta 200 OK para INVITE
     */
    fun sendInviteOkResponse(accountInfo: AccountInfo, callData: CallData) {
        try {
            val okResponse = SipMessageBuilder.buildInviteOkResponse(accountInfo, callData)
            accountInfo.webSocketClient?.send(okResponse)

            log.d(tag = TAG) { "200 OK sent for INVITE ${callData.callId}" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending INVITE OK response: ${e.message}" }
        }
    }

    /**
     * Envía respuesta de rechazo
     */
    fun sendDeclineResponse(accountInfo: AccountInfo, callData: CallData) {
        try {
            val declineResponse = SipMessageBuilder.buildDeclineResponse(accountInfo, callData)
            accountInfo.webSocketClient?.send(declineResponse)

            log.d(tag = TAG) { "603 Decline sent for call ${callData.callId}" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending decline response: ${e.message}" }
        }
    }

    /**
     * Envía re-INVITE para hold/resume
     */
   suspend fun sendReInvite(accountInfo: AccountInfo, callData: CallData, sdp: String) {
        try {
            val reInviteMessage = SipMessageBuilder.buildReInviteMessage(accountInfo, callData, sdp)
            accountInfo.webSocketClient?.send(reInviteMessage)

            log.d(tag = TAG) { "Re-INVITE sent for call ${callData.callId}" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending re-INVITE: ${e.message}" }
        }
    }

    /**
     * Envía mensaje INFO para DTMF
     */
   suspend fun sendDtmfInfo(accountInfo: AccountInfo, callData: CallData, digit: Char, duration: Int) {
        try {
            val infoMessage = SipMessageBuilder.buildDtmfInfoMessage(
                accountInfo, callData, digit, duration
            )
            accountInfo.webSocketClient?.send(infoMessage)

            log.d(tag = TAG) { "DTMF INFO sent: $digit (duration: $duration)" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending DTMF INFO: ${e.message}" }
        }
    }

    /**
     * Maneja request REFER (transferencia)
     */
    private suspend fun handleReferRequest(lines: List<String>, accountInfo: AccountInfo) {
        try {
            log.d(tag = TAG) { "Handling REFER request (call transfer)" }

            val referToHeader = SipMessageParser.extractHeader(lines, "Refer-To")
            val referredByHeader = SipMessageParser.extractHeader(lines, "Referred-By")
            val replacesHeader = SipMessageParser.extractHeader(lines, "Replaces")
            val callIdHeader = SipMessageParser.extractHeader(lines, "Call-ID")

            // Enviar 200 OK para REFER
            val referOkResponse = SipMessageBuilder.buildReferOkResponse(accountInfo, lines)
            accountInfo.webSocketClient?.send(referOkResponse)

            // Extraer número de destino del Refer-To header
            val transferTarget = SipMessageParser.extractUserFromUri(
                SipMessageParser.extractUriFromHeader(referToHeader)
            )

            log.d(tag = TAG) { "Call transfer requested to: $transferTarget" }

            // Notificar al callback de transferencia
            sipCoreManager.sipCallbacks?.onCallTransferRequested(transferTarget, referredByHeader)

            // Enviar NOTIFY con estado "trying"
            accountInfo.currentCallData?.let { callData ->
                val notifyTrying = SipMessageBuilder.buildNotifyMessage(
                    accountInfo, callData, "active", "100 Trying"
                )
                accountInfo.webSocketClient?.send(notifyTrying)
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error handling REFER request: ${e.message}" }
        }
    }

    /**
     * Maneja request NOTIFY para estado de transferencia
     */
    private fun handleNotifyRequest(lines: List<String>, accountInfo: AccountInfo) {
        try {
            log.d(tag = TAG) { "Handling NOTIFY request" }

            val eventHeader = SipMessageParser.extractHeader(lines, "Event")
            val subscriptionStateHeader = SipMessageParser.extractHeader(lines, "Subscription-State")
            val contentType = SipMessageParser.extractHeader(lines, "Content-Type")

            // Enviar 200 OK para NOTIFY
            val notifyOkResponse = SipMessageBuilder.buildGenericOkResponse(lines)
            accountInfo.webSocketClient?.send(notifyOkResponse)

            if (eventHeader.contains("refer", ignoreCase = true)) {
                val content = SipMessageParser.extractSdpContent(lines.joinToString("\r\n"))
                log.d(tag = TAG) { "Transfer status update: $content" }

                // Procesar estado de transferencia
                when {
                    content.contains("200", ignoreCase = true) -> {
                        log.d(tag = TAG) { "Call transfer completed successfully" }
                        sipCoreManager.sipCallbacks?.onCallTransferCompleted(true)
                    }
                    content.contains("4", ignoreCase = true) || content.contains("5", ignoreCase = true) -> {
                        log.d(tag = TAG) { "Call transfer failed" }
                        sipCoreManager.sipCallbacks?.onCallTransferCompleted(false)
                    }
                }
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error handling NOTIFY request: ${e.message}" }
        }
    }

    /**
     * Envía mensaje REFER para transferencia
     */
    suspend fun sendRefer(accountInfo: AccountInfo, callData: CallData, transferTo: String) {
        try {
            val referMessage = SipMessageBuilder.buildReferMessage(
                accountInfo, callData, transferTo, isBlindTransfer = true
            )
            accountInfo.webSocketClient?.send(referMessage)

            log.d(tag = TAG) { "REFER sent for call transfer to: $transferTo" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending REFER: ${e.message}" }
        }
    }

    /**
     * Redirige una llamada entrante a otro número (deflection)
     */
    fun deflectCall(accountInfo: AccountInfo, callData: CallData, redirectToNumber: String) {
        try {
            log.d(tag = TAG) { "Deflecting call ${callData.callId} to $redirectToNumber" }

            // CRÍTICO: Detener ringtone inmediatamente
            sipCoreManager.audioManager.stopAllRingtones()

            // Opcional: Enviar 181 primero para informar que se está redirigiendo
            val forwardingResponse = SipMessageBuilder.buildCallForwardingResponse(accountInfo, callData)
            accountInfo.webSocketClient?.send(forwardingResponse)

            // Esperar un momento antes de enviar el 302
            CoroutineScope(Dispatchers.IO).launch {
                delay(100)

                // Enviar 302 Moved Temporarily con Contact hacia el nuevo destino
                val redirectResponse = SipMessageBuilder.buildCallRedirectResponse(
                    accountInfo,
                    callData,
                    redirectToNumber
                )
                accountInfo.webSocketClient?.send(redirectResponse)

                // Actualizar estado de llamada
                CallStateManager.callEnded(callData.callId, 302, "Call Redirected to $redirectToNumber")
                sipCoreManager.notifyCallStateChanged(CallState.ENDED)

                // Agregar al historial como redirigida
                val endTime = Clock.System.now().toEpochMilliseconds()
                sipCoreManager.callHistoryManager.addCallLog(
                    callData,
                    CallTypes.FORWARDED, // Necesitarás agregar este tipo
                    endTime
                )

                // Notificar al PushModeManager
                val accountKey = "${accountInfo.username}@${accountInfo.domain}"
                sipCoreManager.notifyCallEndedForSpecificAccount(accountKey)

                // Limpiar datos de cuenta
                accountInfo.resetCallState()

                log.d(tag = TAG) { "Call deflection completed successfully" }
            }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error deflecting call: ${e.message}" }
            sipCoreManager.audioManager.stopAllRingtones()
        }
    }
}

//
//import com.eddyslarez.siplibrary.core.SipCoreManager
//import com.eddyslarez.siplibrary.data.models.*
//import com.eddyslarez.siplibrary.data.services.audio.SdpType
//import com.eddyslarez.siplibrary.utils.CallStateManager
//import com.eddyslarez.siplibrary.utils.MultiCallManager
//import com.eddyslarez.siplibrary.utils.generateId
//import com.eddyslarez.siplibrary.utils.log
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.Job
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.launch
//import kotlinx.datetime.Clock
///**
// * Handles SIP message processing and generation with improved state management
// * Versión optimizada usando únicamente los nuevos estados
// *
// * @author Eddys Larez
// */
//class SipMessageHandler(private val sipCoreManager: SipCoreManager) {
//
//    private var callInitiationTimeout: Job? = null
//    private val inviteRetryMap = mutableMapOf<String, Int>()
//    private val retryJobs = mutableMapOf<String, Job>()
//
//    private fun terminateCall() {
//        sipCoreManager.audioManager.stopAllRingtones()
//        sipCoreManager.handleCallTermination()
//    }
//
//    companion object {
//        private const val TAG = "SipMessageHandler"
//        private const val SIP_VERSION = "SIP/2.0"
//
//        private const val CODE_TRYING = 100
//        private const val CODE_RINGING = 180
//        private const val CODE_SESSION_PROGRESS = 183
//        private const val CODE_OK = 200
//        private const val CODE_UNAUTHORIZED = 401
//        private const val CODE_FORBIDDEN = 403
//        private const val CODE_BUSY = 486
//        private const val CODE_REQUEST_TERMINATED = 487
//        private const val CODE_NOT_ACCEPTABLE = 488
//        private const val CODE_DECLINE = 603
//
//        private const val MAX_INVITE_RETRIES = 2
//        private const val RETRY_DELAY_MS = 2000L
//    }
//
//    /**
//     * Process SIP messages received via WebSocket
//     */
//    fun handleSipMessage(message: String, accountInfo: AccountInfo) {
//        try {
//            logIncomingMessage(message)
//            val lines = message.split("\r\n")
//            val firstLine = lines.firstOrNull() ?: return
//
//            updateCSeqIfPresent(lines, accountInfo)
//
//            when {
//                firstLine.startsWith(SIP_VERSION) -> handleSipResponse(
//                    firstLine,
//                    message,
//                    accountInfo,
//                    lines
//                )
//
//                isValidSipRequest(firstLine) -> handleSipRequest(
//                    firstLine,
//                    message,
//                    accountInfo,
//                    lines
//                )
//            }
//        } catch (e: Exception) {
//            log.d(tag = TAG) { "Error in handleSipMessage: ${e.stackTraceToString()}" }
//        }
//    }
//
//    private fun isValidSipRequest(firstLine: String): Boolean {
//        return firstLine.startsWith("INVITE ") ||
//                firstLine.startsWith("BYE ") ||
//                firstLine.startsWith("CANCEL ") ||
//                firstLine.startsWith("ACK ")
//    }
//
//    private fun logIncomingMessage(message: String) {
//        log.d(tag = TAG) { "\n=== INCOMING SIP MESSAGE ===" }
//        log.d(tag = TAG) { message.take(500) }
//        log.d(tag = TAG) { "=== END OF MESSAGE ===" }
//    }
//
//    private fun updateCSeqIfPresent(lines: List<String>, accountInfo: AccountInfo) {
//        lines.find { it.startsWith("CSeq:", ignoreCase = true) }?.let { cseqLine ->
//            val parts = cseqLine.split("\\s+".toRegex())
//            if (parts.size >= 2) {
//                parts[1].toIntOrNull()?.let { seqNum ->
//                    accountInfo.cseq = seqNum
//                    log.d(tag = TAG) { "Updated accountInfo.cseq = $seqNum" }
//                }
//            }
//        }
//    }
//
//    /**
//     * Handle SIP requests
//     */
//    private fun handleSipRequest(
//        requestLine: String,
//        message: String,
//        accountInfo: AccountInfo,
//        lines: List<String>
//    ) {
//        val method = requestLine.split(" ")[0]
//
//        when (method) {
//            "INVITE" -> handleIncomingInvite(message, accountInfo, lines)
//            "BYE" -> handleIncomingBye(message, accountInfo, lines)
//            "CANCEL" -> handleIncomingCancel(message, accountInfo, lines)
//            "ACK" -> handleIncomingAck(message, accountInfo, lines)
//        }
//    }
//
//    /**
//     * Handle SIP responses
//     */
//    fun handleSipResponse(
//        statusLine: String,
//        message: String,
//        accountInfo: AccountInfo,
//        lines: List<String>
//    ) {
//        val statusCode = statusLine.split(" ")[1].toIntOrNull()
//        val method = SipMessageParser.extractMethodFromCSeq(message, lines)
//        val isReInvite = method == "INVITE" && accountInfo.currentCallData?.isOnHold != null
//
//        log.d(tag = TAG) { "Status code: $statusCode, Method: $method, IsReInvite: $isReInvite" }
//
//        when (method) {
//            "REGISTER" -> handleRegisterResponse(statusCode, message, accountInfo, lines)
//            "INVITE" -> {
//                if (isReInvite) {
//                    handleReInviteResponse(statusCode, message, accountInfo, lines)
//                } else {
//                    handleInviteResponse(statusCode, message, accountInfo, lines)
//                }
//            }
//            "BYE" -> handleByeResponse(statusCode, message, accountInfo, lines)
//            "CANCEL" -> handleCancelResponse(statusCode, message, accountInfo, lines)
//            else -> log.d(tag = TAG) { "Response for unhandled method: $method" }
//        }
//    }
//
//    // ===================== INCOMING REQUEST HANDLERS =====================
//
//    private fun handleIncomingInvite(
//        message: String,
//        accountInfo: AccountInfo,
//        lines: List<String>
//    ) {
//        log.d(tag = TAG) { "🔔 Incoming call received" }
//        sipCoreManager.currentAccountInfo = accountInfo
//        sipCoreManager.isCallFromPush = true
//
//        try {
//            val callData = createIncomingCallData(message, lines, accountInfo)
//            setupIncomingCall(callData, accountInfo, lines)
//
//            // Actualizar estado detallado
//            CallStateManager.incomingCallReceived(callData.callId, callData.from)
//
//            // Send immediate responses
//            sendTrying(accountInfo, callData)
//
//            // Delayed ringing and UI updates
//            CoroutineScope(Dispatchers.IO).launch {
//                delay(50)
//                sendRinging(accountInfo, callData)
//                updateUIForIncomingCall(callData)
//            }
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error handling incoming INVITE: ${e.stackTraceToString()}" }
//            accountInfo.currentCallData?.let { callData ->
//                CallStateManager.callError(
//                    callData.callId,
//                    errorReason = CallErrorReason.NETWORK_ERROR
//                )
//            }
//        }
//    }
//
//    private fun createIncomingCallData(
//        message: String,
//        lines: List<String>,
//        accountInfo: AccountInfo
//    ): CallData {
//        val callId = SipMessageParser.extractHeader(lines, "Call-ID")
//        val via = SipMessageParser.extractHeader(lines, "Via")
//        val from = SipMessageParser.extractHeader(lines, "From")
//        val fromTag = SipMessageParser.extractTag(from)
//        val toTag = generateId()
//        val sdpContent = SipMessageParser.extractSdpContent(message)
//        val fromDisplayName = SipMessageParser.extractDisplayName(from)
//        val fromUri = SipMessageParser.extractUriFromHeader(from)
//        val fromNumber = SipMessageParser.extractUserFromUri(fromUri)
//
//        return CallData(
//            callId = callId,
//            to = accountInfo.username,
//            from = fromNumber,
//            direction = CallDirections.INCOMING,
//            remoteDisplayName = fromDisplayName,
//            remoteSdp = sdpContent,
//            via = via,
//            fromTag = fromTag,
//            toTag = toTag,
//            inviteFromTag = fromTag,
//            inviteToTag = toTag
//        ).apply {
//            originalInviteMessage = message
//        }
//    }
//
//    private fun setupIncomingCall(
//        callData: CallData,
//        accountInfo: AccountInfo,
//        lines: List<String>
//    ) {
//        val cseqHeader = SipMessageParser.extractHeader(lines, "CSeq")
//        val cseqValue = cseqHeader.split(" ")[0].toIntOrNull() ?: accountInfo.cseq
//
//        accountInfo.apply {
//            cseq = cseqValue
//            currentCallData = callData
//            this.callId = callData.callId
//            fromTag = callData.fromTag
//            toTag = callData.toTag
//        }
//    }
//
//    private fun updateUIForIncomingCall(callData: CallData) {
//        log.d(tag = TAG) { "Updating UI for incoming call from ${callData.from}" }
//
//        CallStateManager.callerNumber(callData.from)
//        CallStateManager.callId(callData.callId)
//
//        sipCoreManager.notifyCallStateChanged(CallState.INCOMING_RECEIVED)
//
//        CoroutineScope(Dispatchers.IO).launch {
//            sipCoreManager.audioManager.playRingtone()
//        }
//
//        sipCoreManager.windowManager.bringToFront()
//    }
//
//    private fun handleTerminationRequest(
//        message: String,
//        accountInfo: AccountInfo,
//        lines: List<String>,
//        requestType: String
//    ) {
//        log.d(tag = TAG) { "📞 $requestType received" }
//
//        try {
//            val callId = SipMessageParser.extractHeader(lines, "Call-ID")
//            val currentCallData = accountInfo.currentCallData
//
//            if (currentCallData == null || currentCallData.callId != callId) {
//                log.d(tag = TAG) { "$requestType received for non-active call, ignoring" }
//                return
//            }
//
//            clearRetryData(callId)
//
//            // Iniciar proceso de finalización
//            CallStateManager.startEnding(callId)
//
//            when (requestType) {
//                "BYE" -> {
//                    val sipResponse = SipMessageBuilder.buildByeOkResponse(accountInfo, lines)
//                    accountInfo.webSocketClient?.send(sipResponse)
//                    log.d(tag = TAG) { "Sending 200 OK for BYE" }
//
//                    val endTime = Clock.System.now().toEpochMilliseconds()
//                    val callType = if (sipCoreManager.getCurrentCallState().isConnected()) {
//                        CallTypes.SUCCESS
//                    } else if (currentCallData.direction == CallDirections.INCOMING) {
//                        CallTypes.MISSED
//                    } else {
//                        CallTypes.ABORTED
//                    }
//
//                    sipCoreManager.callHistoryManager.addCallLog(currentCallData, callType, endTime)
//                }
//                "CANCEL" -> {
//                    // Obtener estado específico de la llamada
//                    val callState = MultiCallManager.getCallState(callId)
//                    if (callState?.state != CallState.INCOMING_RECEIVED &&
//                        callState?.state != CallState.OUTGOING_RINGING) {
//                        log.d(tag = TAG) { "CANCEL received but call not in INCOMING/RINGING state, ignoring" }
//                        return
//                    }
//
//                    val okResponse = SipMessageBuilder.buildCancelOkResponse(accountInfo, lines)
//                    accountInfo.webSocketClient?.send(okResponse)
//
//                    val requestTerminatedResponse = SipMessageBuilder.buildRequestTerminatedResponse(accountInfo, currentCallData)
//                    accountInfo.webSocketClient?.send(requestTerminatedResponse)
//
//                    val endTime = Clock.System.now().toEpochMilliseconds()
//                    val callType = if (currentCallData.direction == CallDirections.INCOMING) {
//                        CallTypes.MISSED
//                    } else {
//                        CallTypes.ABORTED
//                    }
//
//                    sipCoreManager.callHistoryManager.addCallLog(currentCallData, callType, endTime)
//                }
//            }
//
//            sipCoreManager.audioManager.stopAllRingtones()
//
//            // Finalizar llamada
//            CallStateManager.callEnded(callId)
//            sipCoreManager.notifyCallStateChanged(CallState.ENDED)
//
//            // Solo dispose WebRTC si no hay más llamadas activas
//            if (MultiCallManager.getAllCalls().size <= 1) {
//                sipCoreManager.webRtcManager.dispose()
//            }
//
//            // Solo limpiar si es la llamada actual
//            if (accountInfo.currentCallData?.callId == callId) {
//                accountInfo.currentCallData = null
//            }
//
//            terminateCall()
//
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error handling $requestType: ${e.stackTraceToString()}" }
//            accountInfo.currentCallData?.let { callData ->
//                CallStateManager.callError(
//                    callData.callId,
//                    errorReason = CallErrorReason.NETWORK_ERROR
//                )
//            }
//        }
//    }
//
//    private fun handleIncomingBye(message: String, accountInfo: AccountInfo, lines: List<String>) {
//        handleTerminationRequest(message, accountInfo, lines, "BYE")
//    }
//
//    private fun handleIncomingCancel(
//        message: String,
//        accountInfo: AccountInfo,
//        lines: List<String>
//    ) {
//        handleTerminationRequest(message, accountInfo, lines, "CANCEL")
//    }
//
//    private fun handleIncomingAck(message: String, accountInfo: AccountInfo, lines: List<String>) {
//        log.d(tag = TAG) { "✅ ACK received for call" }
//
//        try {
//            val callId = SipMessageParser.extractHeader(lines, "Call-ID")
//            val currentCallData = accountInfo.currentCallData
//
//            if (currentCallData == null || currentCallData.callId != callId) {
//                log.d(tag = TAG) { "ACK received for non-active call, ignoring" }
//                return
//            }
//
//            sipCoreManager.audioManager.stopAllRingtones()
//
//            // Transición a streams running
//            CallStateManager.streamsRunning(callId)
//            sipCoreManager.notifyCallStateChanged(CallState.STREAMS_RUNNING)
//
//            log.d(tag = TAG) { "🟢 Call connected after receiving ACK" }
//            sipCoreManager.callStartTimeMillis = Clock.System.now().toEpochMilliseconds()
//            accountInfo.isCallConnected = true
//            accountInfo.callStartTime = sipCoreManager.callStartTimeMillis
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error handling incoming ACK: ${e.stackTraceToString()}" }
//            accountInfo.currentCallData?.let { callData ->
//                CallStateManager.callError(
//                    callData.callId,
//                    errorReason = CallErrorReason.NETWORK_ERROR
//                )
//            }
//        }
//    }
//
//    // ===================== RESPONSE HANDLERS =====================
//
//    private fun handleRegisterResponse(
//        statusCode: Int?,
//        message: String,
//        accountInfo: AccountInfo,
//        lines: List<String>
//    ) {
//        when (statusCode) {
//            CODE_TRYING -> handleTrying()
//            CODE_OK -> handleRegisterOk(message, accountInfo, lines)
//            CODE_UNAUTHORIZED -> handleAuthenticationChallenge(accountInfo, message, lines)
//            else -> handleRegisterError(message, accountInfo, lines)
//        }
//    }
//
//    private fun handleInviteResponse(
//        statusCode: Int?,
//        message: String,
//        accountInfo: AccountInfo,
//        lines: List<String>
//    ) {
//        // Cancelar timeout si recibimos cualquier respuesta
//        callInitiationTimeout?.cancel()
//        val callData = accountInfo.currentCallData
//
//        when (statusCode) {
//            CODE_TRYING -> {
//                handleTrying()
//                startCallInitiationTimeout(accountInfo)
//            }
//
//            CODE_SESSION_PROGRESS -> {
//                callData?.let { data ->
//                    CallStateManager.outgoingCallProgress(data.callId, CODE_SESSION_PROGRESS)
//                    clearRetryData(data.callId)
//                }
//                handleSessionProgress()
//            }
//
//            CODE_RINGING -> {
//                callData?.let { data ->
//                    CallStateManager.outgoingCallRinging(data.callId, CODE_RINGING)
//                    clearRetryData(data.callId)
//                }
//                handleRinging()
//            }
//
//            CODE_OK -> {
//                callData?.let { data ->
//                    CallStateManager.callConnected(data.callId, CODE_OK)
//                    clearRetryData(data.callId)
//                }
//                handleInviteOk(message, accountInfo, lines)
//                sipCoreManager.audioManager.stopAllRingtones()
//            }
//
//            CODE_UNAUTHORIZED -> handleAuthenticationChallenge(accountInfo, message, lines)
//
//            CODE_BUSY -> {
//                callData?.let { data ->
//                    CallStateManager.callError(
//                        data.callId,
//                        CODE_BUSY,
//                        "Busy Here",
//                        CallErrorReason.BUSY
//                    )
//                    clearRetryData(data.callId)
//                }
//                handleBusy()
//            }
//
//            CODE_FORBIDDEN, CODE_NOT_ACCEPTABLE -> {
//                handleRetryableError(statusCode, message, accountInfo, lines)
//            }
//
//            CODE_REQUEST_TERMINATED -> {
//                callData?.let { data ->
//                    CallStateManager.callEnded(data.callId, CODE_REQUEST_TERMINATED, "Request Terminated")
//                }
//                handleRequestTerminated(accountInfo, lines)
//            }
//
//            else -> {
//                if (statusCode != null && statusCode >= 400) {
//                    handleRetryableError(statusCode, message, accountInfo, lines)
//                } else {
//                    handleOtherStatusCodes(statusCode)
//                }
//            }
//        }
//    }
//
//    private fun handleRetryableError(
//        statusCode: Int,
//        message: String,
//        accountInfo: AccountInfo,
//        lines: List<String>
//    ) {
//        val callData = accountInfo.currentCallData ?: return
//        val callId = callData.callId
//
//        log.d(tag = TAG) { "Received retryable error: $statusCode for call $callId" }
//
//        val currentRetries = inviteRetryMap[callId] ?: 0
//
//        if (currentRetries < MAX_INVITE_RETRIES) {
//            val nextRetryCount = currentRetries + 1
//            inviteRetryMap[callId] = nextRetryCount
//
//            log.d(tag = TAG) { "Scheduling retry $nextRetryCount/${MAX_INVITE_RETRIES} for call $callId after error $statusCode" }
//
//            // Cancelar job de retry anterior si existe
//            retryJobs[callId]?.cancel()
//
//            // Programar nuevo intento
//            retryJobs[callId] = CoroutineScope(Dispatchers.IO).launch {
//                try {
//                    delay(RETRY_DELAY_MS)
//
//                    // Verificar que la llamada aún esté activa
//                    if (accountInfo.currentCallData?.callId == callId) {
//                        log.d(tag = TAG) { "Retrying INVITE for call $callId (attempt $nextRetryCount)" }
//
//                        // Generar nuevo Call-ID y tags para el retry
//                        val newCallId = generateId()
//                        val newFromTag = generateId()
//
//                        // Actualizar callData con nuevos identificadores
//                        callData.callId = newCallId
//                        callData.inviteFromTag = newFromTag
//
//                        // Actualizar accountInfo
//                        accountInfo.callId = newCallId
//                        accountInfo.fromTag = newFromTag
//
//                        // Reiniciar estado para retry
//                        CallStateManager.startOutgoingCall(newCallId, callData.to)
//
//                        // Actualizar el mapa de reintentos con el nuevo callId
//                        inviteRetryMap.remove(callId)
//                        inviteRetryMap[newCallId] = nextRetryCount
//
//                        // Limpiar job anterior
//                        retryJobs.remove(callId)
//
//                        // Enviar nuevo INVITE
//                        sendInvite(accountInfo, callData)
//
//                        log.d(tag = TAG) { "INVITE retry sent for call $newCallId" }
//                    } else {
//                        log.d(tag = TAG) { "Call $callId no longer active, canceling retry" }
//                        clearRetryData(callId)
//                    }
//                } catch (e: Exception) {
//                    log.e(tag = TAG) { "Error during INVITE retry: ${e.message}" }
//                    clearRetryData(callId)
//                    handleFinalCallFailure(accountInfo)
//                }
//            }
//        } else {
//            log.d(tag = TAG) { "Max retries (${MAX_INVITE_RETRIES}) reached for call $callId with error $statusCode" }
//
//            // Error final después de reintentos
//            CallStateManager.callError(
//                callId,
//                statusCode,
//                "Max retries reached",
//                SipErrorMapper.mapSipCodeToErrorReason(statusCode)
//            )
//
//            clearRetryData(callId)
//            handleFinalCallFailure(accountInfo)
//        }
//    }
//
//    private fun handleFinalCallFailure(accountInfo: AccountInfo) {
//        log.d(tag = TAG) { "Call failed after all retry attempts" }
//
//        accountInfo.currentCallData = null
//        accountInfo.resetCallState()
//
//        sipCoreManager.notifyCallStateChanged(CallState.ERROR)
//    }
//
//    private fun clearRetryData(callId: String) {
//        inviteRetryMap.remove(callId)
//        retryJobs[callId]?.cancel()
//        retryJobs.remove(callId)
//    }
//
//    private fun startCallInitiationTimeout(accountInfo: AccountInfo) {
//        callInitiationTimeout = CoroutineScope(Dispatchers.IO).launch {
//            delay(30000)
//            log.d(tag = TAG) { "Call initiation timeout" }
//
//            accountInfo.currentCallData?.let { callData ->
//                CallStateManager.callError(
//                    callData.callId,
//                    errorReason = CallErrorReason.NO_ANSWER
//                )
//            }
//
//            sipCoreManager.notifyCallStateChanged(CallState.ERROR)
//            accountInfo.currentCallData = null
//        }
//    }
//
//    private fun handleReInviteResponse(
//        statusCode: Int?,
//        message: String,
//        accountInfo: AccountInfo,
//        lines: List<String>
//    ) {
//        log.d(tag = TAG) { "Response to re-INVITE received: $statusCode" }
//        val callData = accountInfo.currentCallData ?: return
//
//        when (statusCode) {
//            CODE_TRYING -> log.d(tag = TAG) { "Provisional response (100)" }
//            CODE_OK -> {
//                log.d(tag = TAG) { "Re-INVITE accepted (200 OK)" }
//                val sdpContent = SipMessageParser.extractSdpContent(message)
//                callData.remoteSdp = sdpContent
//                sendAck(accountInfo, callData)
//
//                // Actualizar estado según hold/resume
//                if (callData.isOnHold == true) {
//                    CallStateManager.callOnHold(callData.callId)
//                } else {
//                    CallStateManager.streamsRunning(callData.callId)
//                }
//
//                val stateMessage = if (callData.isOnHold == true) "placed on hold" else "resumed"
//                log.d(tag = TAG) { "Call $stateMessage successfully" }
//            }
//
//            CODE_UNAUTHORIZED -> handleAuthenticationChallenge(accountInfo, message, lines)
//            CODE_BUSY -> {
//                log.d(tag = TAG) { "Re-INVITE rejected: Busy (486)" }
//                restorePreviousHoldState(callData)
//            }
//
//            else -> {
//                log.d(tag = TAG) { "Unhandled re-INVITE response: $statusCode" }
//                restorePreviousHoldState(callData)
//            }
//        }
//    }
//
//    private fun restorePreviousHoldState(callData: CallData) {
//        callData.isOnHold = !callData.isOnHold!!
//
//        // Restaurar estado anterior
//        if (callData.isOnHold!!) {
//            CallStateManager.callOnHold(callData.callId)
//        } else {
//            CallStateManager.streamsRunning(callData.callId)
//        }
//
//        val newState = if (callData.isOnHold!!) CallState.PAUSED else CallState.STREAMS_RUNNING
//        sipCoreManager.notifyCallStateChanged(newState)
//    }
//
//    private fun handleByeResponse(
//        statusCode: Int?,
//        message: String,
//        accountInfo: AccountInfo,
//        lines: List<String>
//    ) {
//        when (statusCode) {
//            CODE_OK -> {
//                log.d(tag = TAG) { "BYE accepted by server" }
//
//                accountInfo.currentCallData?.let { callData ->
//                    CallStateManager.callEnded(callData.callId, CODE_OK, "OK")
//                }
//
//                terminateCall()
//            }
//
//            else -> log.d(tag = TAG) { "Unhandled BYE response: $statusCode" }
//        }
//    }
//
//    private fun handleCancelResponse(
//        statusCode: Int?,
//        message: String,
//        accountInfo: AccountInfo,
//        lines: List<String>
//    ) {
//        when (statusCode) {
//            CODE_OK -> {
//                log.d(tag = TAG) { "CANCEL accepted (200 OK)" }
//
//                accountInfo.currentCallData?.let { callData ->
//                    CallStateManager.callEnded(callData.callId, CODE_OK, "Cancelled")
//                }
//
//                terminateCall()
//            }
//
//            else -> {
//                log.d(tag = TAG) { "Unexpected CANCEL response: $statusCode" }
//
//                accountInfo.currentCallData?.let { callData ->
//                    CallStateManager.callEnded(callData.callId, statusCode, "Unexpected response")
//                }
//
//                terminateCall()
//                sipCoreManager.notifyCallStateChanged(CallState.ENDED)
//                accountInfo.resetCallState()
//            }
//        }
//    }
//
//    // ===================== SPECIFIC STATUS HANDLERS =====================
//
//    private fun handleInviteOk(message: String, accountInfo: AccountInfo, lines: List<String>) {
//        log.d(tag = TAG) { "Outgoing call accepted (200 OK)" }
//
//        val callData = accountInfo.currentCallData ?: return
//        val toHeader = SipMessageParser.extractHeader(lines, "To")
//        val toTag = SipMessageParser.extractTag(toHeader)
//        val sdpContent = SipMessageParser.extractSdpContent(message)
//
//        callData.remoteSdp = sdpContent
//        callData.inviteToTag = toTag
//
//        CoroutineScope(Dispatchers.IO).launch {
//            sipCoreManager.webRtcManager.setRemoteDescription(sdpContent, SdpType.ANSWER)
//        }
//
//        sendAck(accountInfo, callData)
//        sipCoreManager.audioManager.stopAllRingtones()
//
//        sipCoreManager.notifyCallStateChanged(CallState.CONNECTED)
//        sipCoreManager.callStartTimeMillis = Clock.System.now().toEpochMilliseconds()
//    }
//
//    private fun handleRequestTerminated(accountInfo: AccountInfo, lines: List<String>) {
//        log.d(tag = TAG) { "Call was canceled (487 Request Terminated)" }
//
//        val callData = accountInfo.currentCallData
//        if (callData != null) {
//            val ackMessage = SipMessageBuilder.buildAckFor487Response(accountInfo, callData, lines)
//            accountInfo.webSocketClient?.send(ackMessage)
//        }
//        sipCoreManager.audioManager.stopAllRingtones()
//
//        sipCoreManager.notifyCallStateChanged(CallState.ENDED)
//        accountInfo.resetCallState()
//    }
//
//    private fun handleTrying() {
//        log.d(tag = TAG) { "Trying (100)" }
//        sipCoreManager.notifyCallStateChanged(CallState.OUTGOING_INIT)
//    }
//
//    private fun handleSessionProgress() {
//        log.d(tag = TAG) { "Session Progress (183)" }
//        sipCoreManager.notifyCallStateChanged(CallState.OUTGOING_PROGRESS)
//    }
//
//    private fun handleRinging() {
//        sipCoreManager.notifyCallStateChanged(CallState.OUTGOING_RINGING)
//        sipCoreManager.audioManager.playOutgoingRingtone()
//        log.d(tag = TAG) { "Call established - Ringing (180)" }
//    }
//
//    private fun handleRegisterOk(message: String, accountInfo: AccountInfo, lines: List<String>) {
//        val expires = SipMessageParser.extractExpiresValue(message)
//        log.d(tag = TAG) { "Successful registration with expiration: $expires seconds" }
//
//        accountInfo.isRegistered = true
//
//        sipCoreManager.handleRegistrationSuccess(accountInfo)
//
//        // Configure renewal
//        val expiresMs = expires * 1000L
//        val accountKey = "${accountInfo.username}@${accountInfo.domain}"
//        val expirationTime = Clock.System.now().toEpochMilliseconds() + expiresMs
//        accountInfo.webSocketClient?.setRegistrationExpiration(accountKey, expirationTime)
//
//        sipCoreManager.updateRegistrationState(accountKey, RegistrationState.OK)
//
//        log.d(tag = TAG) { "Registration renewal configured for ${accountInfo.username}" }
//    }
//
//    private fun handleRegisterError(message: String, accountInfo: AccountInfo, lines: List<String>) {
//        log.d(TAG) { "Registration Error" }
//        val reason = SipMessageParser.extractStatusReason(message)
//
//        val accountKey = "${accountInfo.username}@${accountInfo.domain}"
//        sipCoreManager.updateRegistrationState(accountKey, RegistrationState.FAILED)
//
//        sipCoreManager.handleRegistrationError(accountInfo, reason)
//    }
//
//    private fun handleBusy() {
//        log.d(tag = TAG) { "Call rejected: Busy (486)" }
//        sipCoreManager.notifyCallStateChanged(CallState.ERROR)
//    }
//
//    private fun handleOtherStatusCodes(statusCode: Int?) {
//        log.d(tag = TAG) { "Unhandled SIP code: $statusCode" }
//
//        if (statusCode != null && statusCode >= 400) {
//            sipCoreManager.currentAccountInfo?.let { accountInfo ->
//                val accountKey = "${accountInfo.username}@${accountInfo.domain}"
//                sipCoreManager.updateRegistrationState(accountKey, RegistrationState.FAILED)
//
//                // Error en llamada actual si existe
//                accountInfo.currentCallData?.let { callData ->
//                    CallStateManager.callError(
//                        callData.callId,
//                        statusCode,
//                        "Unhandled error",
//                        SipErrorMapper.mapSipCodeToErrorReason(statusCode)
//                    )
//                }
//            }
//            sipCoreManager.notifyCallStateChanged(CallState.ERROR)
//        }
//    }
//
//    private fun handleAuthenticationChallenge(
//        accountInfo: AccountInfo,
//        message: String,
//        lines: List<String>
//    ) {
//        log.d(tag = TAG) { "Authentication challenge 401" }
//
//        try {
//            val method = SipMessageParser.extractMethodFromCSeq(message, lines)
//            accountInfo.method = method
//            log.d(tag = TAG) { "Method: $method" }
//
//            val authData = AuthenticationHandler.extractAuthenticationData(lines) ?: return
//            log.d(tag = TAG) { "authData: $authData" }
//
//            val authResponse =
//                AuthenticationHandler.calculateAuthResponse(accountInfo, authData, method)
//            log.d(tag = TAG) { "authResponse: $authResponse" }
//            AuthenticationHandler.updateAccountAuthInfo(accountInfo, authData, authResponse, method)
//
//            when (method) {
//                "REGISTER" -> sendAuthenticatedRegister(accountInfo)
//                "INVITE" -> accountInfo.currentCallData?.let {
//                    sendAuthenticatedInvite(
//                        accountInfo,
//                        it
//                    )
//                }
//
//                else -> log.d(tag = TAG) { "Unhandled method for authentication: $method" }
//            }
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "CRITICAL ERROR in handleAuthenticationChallenge: ${e.stackTraceToString()}" }
//        }
//    }
//
//    // ===================== SENDING METHODS =====================
//
//    /**
//     * Generic message sender with error handling
//     */
//    private fun sendSipMessage(
//        messageBuilder: () -> String,
//        messageType: String,
//        accountInfo: AccountInfo
//    ) {
//        try {
//            val sipMessage = messageBuilder()
//            log.d(tag = TAG) { "Sending $messageType" }
//            accountInfo.webSocketClient?.send(sipMessage)
//        } catch (e: Exception) {
//            log.e(tag = TAG) { "Error sending $messageType: ${e.stackTraceToString()}" }
//        }
//    }
//
//    fun sendRegister(accountInfo: AccountInfo, isAppInBackground: Boolean) {
//        sendSipMessage(
//            messageBuilder = {
//                val callId = generateId()
//                val fromTag = generateId()
//                accountInfo.callId = callId
//                accountInfo.fromTag = fromTag
//                SipMessageBuilder.buildRegisterMessage(
//                    accountInfo,
//                    callId,
//                    fromTag,
//                    isAppInBackground
//                )
//            },
//            messageType = "REGISTER",
//            accountInfo = accountInfo
//        )
//    }
//
//    fun sendInvite(accountInfo: AccountInfo, callData: CallData) {
//        sendSipMessage(
//            messageBuilder = {
//                val sipMessage =
//                    SipMessageBuilder.buildInviteMessage(accountInfo, callData, callData.localSdp)
//                callData.originalInviteMessage = sipMessage
//                callData.storeInviteMessage(sipMessage)
//                sipMessage
//            },
//            messageType = "INVITE",
//            accountInfo = accountInfo
//        )
//    }
//
//    fun sendUnregister(accountInfo: AccountInfo) {
//        sendSipMessage(
//            messageBuilder = {
//                val callId = accountInfo.callId ?: generateId()
//                val fromTag = accountInfo.fromTag ?: generateId()
//                SipMessageBuilder.buildUnregisterMessage(accountInfo, callId, fromTag)
//            },
//            messageType = "UNREGISTER",
//            accountInfo = accountInfo
//        )
//    }
//
//    fun sendDeclineResponse(accountInfo: AccountInfo, callData: CallData) {
//        sendSipMessage(
//            messageBuilder = { SipMessageBuilder.buildDeclineResponse(accountInfo, callData) },
//            messageType = "603 DECLINE",
//            accountInfo = accountInfo
//        )
//
//        // Estado de error por rechazo
//        CallStateManager.callError(
//            callData.callId,
//            603,
//            "Decline",
//            CallErrorReason.REJECTED
//        )
//
//        // Detener ringtones
//        sipCoreManager.audioManager.stopAllRingtones()
//
//        terminateCall()
//    }
//
//    fun sendBye(accountInfo: AccountInfo, callData: CallData) {
//        // Iniciar finalización
//        CallStateManager.startEnding(callData.callId)
//
//        sendSipMessage(
//            messageBuilder = { SipMessageBuilder.buildByeMessage(accountInfo, callData) },
//            messageType = "BYE",
//            accountInfo = accountInfo
//        )
//    }
//
//    fun sendTrying(accountInfo: AccountInfo, callData: CallData) {
//        sendSipMessage(
//            messageBuilder = { SipMessageBuilder.buildTryingResponse(accountInfo, callData) },
//            messageType = "100 TRYING",
//            accountInfo = accountInfo
//        )
//    }
//
//    fun sendRinging(accountInfo: AccountInfo, callData: CallData) {
//        sendSipMessage(
//            messageBuilder = { SipMessageBuilder.buildRingingResponse(accountInfo, callData) },
//            messageType = "180 RINGING",
//            accountInfo = accountInfo
//        )
//    }
//
//    fun sendAck(accountInfo: AccountInfo, callData: CallData) {
//        sendSipMessage(
//            messageBuilder = { SipMessageBuilder.buildAckMessage(accountInfo, callData) },
//            messageType = "ACK",
//            accountInfo = accountInfo
//        )
//    }
//
//    fun sendInviteOkResponse(accountInfo: AccountInfo, callData: CallData) {
//        // Llamada conectada
//        CallStateManager.callConnected(callData.callId, 200)
//
//        sendSipMessage(
//            messageBuilder = { SipMessageBuilder.buildInviteOkResponse(accountInfo, callData) },
//            messageType = "200 OK (INVITE)",
//            accountInfo = accountInfo
//        )
//    }
//
//    fun sendDtmfInfo(accountInfo: AccountInfo, callData: CallData, digit: Char, duration: Int) {
//        sendSipMessage(
//            messageBuilder = {
//                SipMessageBuilder.buildDtmfInfoMessage(accountInfo, callData, digit, duration)
//            },
//            messageType = "INFO (DTMF)",
//            accountInfo = accountInfo
//        )
//    }
//
//    fun sendCancel(accountInfo: AccountInfo, callData: CallData) {
//        // Iniciar finalización por cancelación
//        CallStateManager.startEnding(callData.callId)
//
//        // Detener outgoing ringtone inmediatamente
//        sipCoreManager.audioManager.stopOutgoingRingtone()
//
//        sendSipMessage(
//            messageBuilder = {
//                val sipMessage = SipMessageBuilder.buildCancelMessage(accountInfo, callData)
//                sipMessage
//            },
//            messageType = "CANCEL",
//            accountInfo = accountInfo
//        )
//    }
//
//    fun sendReInvite(accountInfo: AccountInfo, callData: CallData, sdp: String) {
//        sendSipMessage(
//            messageBuilder = { SipMessageBuilder.buildReInviteMessage(accountInfo, callData, sdp) },
//            messageType = "RE-INVITE",
//            accountInfo = accountInfo
//        )
//    }
//
//    private fun sendAuthenticatedRegister(accountInfo: AccountInfo) {
//        log.d(tag = TAG) { "accountInfo: ${accountInfo.authorizationHeader}" }
//
//        sendSipMessage(
//            messageBuilder = {
//                SipMessageBuilder.buildAuthenticatedRegisterMessage(
//                    accountInfo,
//                    sipCoreManager.isAppInBackground
//                )
//            },
//            messageType = "authenticated REGISTER",
//            accountInfo = accountInfo
//        )
//    }
//
//    private fun sendAuthenticatedInvite(accountInfo: AccountInfo, callData: CallData) {
//        sendSipMessage(
//            messageBuilder = {
//                val sipMessage = SipMessageBuilder.buildAuthenticatedInviteMessage(
//                    accountInfo,
//                    callData,
//                    callData.localSdp
//                )
//                callData.originalCallInviteMessage = sipMessage
//                sipMessage
//            },
//            messageType = "authenticated INVITE",
//            accountInfo = accountInfo
//        )
//    }
//}
