package com.eddyslarez.siplibrary.data.services.sip

import com.eddyslarez.siplibrary.core.SipCoreManager
import com.eddyslarez.siplibrary.data.models.*
import com.eddyslarez.siplibrary.data.services.audio.SdpType
import com.eddyslarez.siplibrary.utils.CallStateManager
import com.eddyslarez.siplibrary.utils.generateId
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * Handles SIP message processing and generation
 * Optimized version with better organization and reduced duplication
 * 
 * @author Eddys Larez
 */
class SipMessageHandler(private val sipCoreManager: SipCoreManager) {
    var onCallTerminated: (() -> Unit) = {}

    private fun terminateCall() {
        // Ejecutar el callback de terminación
        onCallTerminated()
    }

    companion object {
        private const val TAG = "SipMessageHandler"
        private const val SIP_VERSION = "SIP/2.0"

        // Response codes constants
        private const val CODE_TRYING = 100
        private const val CODE_RINGING = 180
        private const val CODE_SESSION_PROGRESS = 183
        private const val CODE_OK = 200
        private const val CODE_UNAUTHORIZED = 401
        private const val CODE_BUSY = 486
        private const val CODE_REQUEST_TERMINATED = 487
        private const val CODE_DECLINE = 603
    }

    /**
     * Process SIP messages received via WebSocket
     */
    fun handleSipMessage(message: String, accountInfo: AccountInfo) {
        try {
            logIncomingMessage(message)
            val lines = message.split("\r\n")
            val firstLine = lines.firstOrNull() ?: return

            updateCSeqIfPresent(lines, accountInfo)

            when {
                firstLine.startsWith(SIP_VERSION) -> handleSipResponse(firstLine, message, accountInfo, lines)
                isValidSipRequest(firstLine) -> handleSipRequest(firstLine, message, accountInfo, lines)
            }
        } catch (e: Exception) {
            log.d(tag = TAG) { "Error in handleSipMessage: ${e.stackTraceToString()}" }
        }
    }

    private fun isValidSipRequest(firstLine: String): Boolean {
        return firstLine.startsWith("INVITE ") ||
                firstLine.startsWith("BYE ") ||
                firstLine.startsWith("CANCEL ") ||
                firstLine.startsWith("ACK ")
    }

    private fun logIncomingMessage(message: String) {
        log.d(tag = TAG) { "\n=== INCOMING SIP MESSAGE ===" }
        log.d(tag = TAG) { message.take(500) }
        log.d(tag = TAG) { "=== END OF MESSAGE ===" }
    }

    private fun updateCSeqIfPresent(lines: List<String>, accountInfo: AccountInfo) {
        lines.find { it.startsWith("CSeq:", ignoreCase = true) }?.let { cseqLine ->
            val parts = cseqLine.split("\\s+".toRegex())
            if (parts.size >= 2) {
                parts[1].toIntOrNull()?.let { seqNum ->
                    accountInfo.cseq = seqNum
                    log.d(tag = TAG) { "Updated accountInfo.cseq = $seqNum" }
                }
            }
        }
    }

    /**
     * Handle SIP requests with simplified routing
     */
    private fun handleSipRequest(
        requestLine: String,
        message: String,
        accountInfo: AccountInfo,
        lines: List<String>
    ) {
        val method = requestLine.split(" ")[0]

        when (method) {
            "INVITE" -> handleIncomingInvite(message, accountInfo, lines)
            "BYE" -> handleIncomingBye(message, accountInfo, lines)
            "CANCEL" -> handleIncomingCancel(message, accountInfo, lines)
            "ACK" -> handleIncomingAck(message, accountInfo, lines)
        }
    }

    /**
     * Handle SIP responses with simplified routing
     */
    fun handleSipResponse(
        statusLine: String,
        message: String,
        accountInfo: AccountInfo,
        lines: List<String>
    ) {
        val statusCode = statusLine.split(" ")[1].toIntOrNull()
        val method = SipMessageParser.extractMethodFromCSeq(message, lines)
        val isReInvite = method == "INVITE" && accountInfo.currentCallData?.isOnHold != null

        log.d(tag = TAG) { "Status code: $statusCode, Method: $method, IsReInvite: $isReInvite" }

        when (method) {
            "REGISTER" -> handleRegisterResponse(statusCode, message, accountInfo, lines)
            "INVITE" -> {
                if (isReInvite) {
                    handleReInviteResponse(statusCode, message, accountInfo, lines)
                } else {
                    handleInviteResponse(statusCode, message, accountInfo, lines)
                }
            }
            "BYE" -> handleByeResponse(statusCode, message, accountInfo, lines)
            "CANCEL" -> handleCancelResponse(statusCode, message, accountInfo, lines)
            else -> log.d(tag = TAG) { "Response for unhandled method: $method" }
        }
    }

    // ===================== INCOMING REQUEST HANDLERS =====================

    private fun handleIncomingInvite(message: String, accountInfo: AccountInfo, lines: List<String>) {
        log.d(tag = TAG) { "🔔 Incoming call received" }
        sipCoreManager.currentAccountInfo = accountInfo
        sipCoreManager.isCallFromPush = true

        try {
            val callData = createIncomingCallData(message, lines, accountInfo)
            setupIncomingCall(callData, accountInfo, lines)

            // Send immediate responses
            sendTrying(accountInfo, callData)

            // Delayed ringing and UI updates
            CoroutineScope(Dispatchers.IO).launch {
                delay(50)
                sendRinging(accountInfo, callData)
                updateUIForIncomingCall(callData)
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error handling incoming INVITE: ${e.stackTraceToString()}" }
        }
    }

    private fun createIncomingCallData(message: String, lines: List<String>, accountInfo: AccountInfo): CallData {
        val callId = SipMessageParser.extractHeader(lines, "Call-ID")
        val via = SipMessageParser.extractHeader(lines, "Via")
        val from = SipMessageParser.extractHeader(lines, "From")
        val fromTag = SipMessageParser.extractTag(from)
        val toTag = generateId()
        val sdpContent = SipMessageParser.extractSdpContent(message)
        val fromDisplayName = SipMessageParser.extractDisplayName(from)
        val fromUri = SipMessageParser.extractUriFromHeader(from)
        val fromNumber = SipMessageParser.extractUserFromUri(fromUri)

        return CallData(
            callId = callId,
            to = accountInfo.username,
            from = fromNumber,
            direction = CallDirections.INCOMING,
            remoteDisplayName = fromDisplayName,
            remoteSdp = sdpContent,
            via = via,
            fromTag = fromTag,
            toTag = toTag,
            inviteFromTag = fromTag,
            inviteToTag = toTag
        ).apply {
            originalInviteMessage = message
        }
    }

    private fun setupIncomingCall(callData: CallData, accountInfo: AccountInfo, lines: List<String>) {
        val cseqHeader = SipMessageParser.extractHeader(lines, "CSeq")
        val cseqValue = cseqHeader.split(" ")[0].toIntOrNull() ?: accountInfo.cseq

        accountInfo.apply {
            cseq = cseqValue
            currentCallData = callData
            this.callId = callData.callId
            fromTag = callData.fromTag
            toTag = callData.toTag
        }
    }

    private fun updateUIForIncomingCall(callData: CallData) {
        CallStateManager.callerNumber(callData.from)
        CallStateManager.callId(callData.callId)
        CallStateManager.updateCallState(CallState.INCOMING)
        sipCoreManager.callState = CallState.INCOMING

        CoroutineScope(Dispatchers.IO).launch {
            sipCoreManager.playRingtoneUseCase.playRingtone()
        }

        sipCoreManager.windowManager.bringToFront()
    }

    private fun handleTerminationRequest(
        message: String,
        accountInfo: AccountInfo,
        lines: List<String>,
        requestType: String
    ) {
        log.d(tag = TAG) { "📞 $requestType received" }

        try {
            val callId = SipMessageParser.extractHeader(lines, "Call-ID")
            val currentCallData = accountInfo.currentCallData

            if (currentCallData == null || currentCallData.callId != callId) {
                log.d(tag = TAG) { "$requestType received for non-active call, ignoring" }
                return
            }

            when (requestType) {
                "BYE" -> {
                    val sipResponse = SipMessageBuilder.buildByeOkResponse(accountInfo, lines)
                    accountInfo.webSocketClient?.send(sipResponse)
                    log.d(tag = TAG) { "Sending 200 OK for BYE" }

                    // Si la llamada estaba conectada, fue exitosa, sino fue perdida
                    val endTime = Clock.System.now().toEpochMilliseconds()
                    val callType = if (sipCoreManager.callState == CallState.CONNECTED) {
                        CallTypes.SUCCESS
                    } else if (currentCallData.direction == CallDirections.INCOMING) {
                        CallTypes.MISSED
                    } else {
                        CallTypes.ABORTED
                    }

                    // Registrar en el historial
                    sipCoreManager.callHistoryManager.addCallLog(currentCallData, callType, endTime)
                }
                "CANCEL" -> {
                    if (sipCoreManager.callState != CallState.INCOMING &&
                        sipCoreManager.callState != CallState.RINGING) {
                        log.d(tag = TAG) { "CANCEL received but call not in INCOMING/RINGING state, ignoring" }
                        return
                    }

                    val okResponse = SipMessageBuilder.buildCancelOkResponse(accountInfo, lines)
                    accountInfo.webSocketClient?.send(okResponse)

                    val requestTerminatedResponse = SipMessageBuilder.buildRequestTerminatedResponse(accountInfo, currentCallData)
                    accountInfo.webSocketClient?.send(requestTerminatedResponse)

                    // Llamada cancelada = perdida si era entrante, abortada si era saliente
                    val endTime = Clock.System.now().toEpochMilliseconds()
                    val callType = if (currentCallData.direction == CallDirections.INCOMING) {
                        CallTypes.MISSED
                    } else {
                        CallTypes.ABORTED
                    }

                    sipCoreManager.callHistoryManager.addCallLog(currentCallData, callType, endTime)
                }
            }

            // Common termination cleanup
            CallStateManager.updateCallState(CallState.ENDED)
            sipCoreManager.webRtcManager.dispose()
            accountInfo.currentCallData = null
            terminateCall()

        } catch (e: Exception) {
            log.e(tag = TAG) { "Error handling $requestType: ${e.stackTraceToString()}" }
        }
    }

    private fun handleIncomingBye(message: String, accountInfo: AccountInfo, lines: List<String>) {
        handleTerminationRequest(message, accountInfo, lines, "BYE")
    }

    private fun handleIncomingCancel(message: String, accountInfo: AccountInfo, lines: List<String>) {
        handleTerminationRequest(message, accountInfo, lines, "CANCEL")
    }

    private fun handleIncomingAck(message: String, accountInfo: AccountInfo, lines: List<String>) {
        log.d(tag = TAG) { "✅ ACK received for call" }

        try {
            val callId = SipMessageParser.extractHeader(lines, "Call-ID")
            val currentCallData = accountInfo.currentCallData

            if (currentCallData == null || currentCallData.callId != callId) {
                log.d(tag = TAG) { "ACK received for non-active call, ignoring" }
                return
            }

            if (sipCoreManager.callState == CallState.ACCEPTING) {
                sipCoreManager.callState = CallState.CONNECTED
                CallStateManager.updateCallState(CallState.CONNECTED)
                log.d(tag = TAG) { "🟢 Call connected after receiving ACK" }
                sipCoreManager.callStartTimeMillis = Clock.System.now().toEpochMilliseconds()
                accountInfo.isCallConnected = true
                accountInfo.callStartTime = sipCoreManager.callStartTimeMillis
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error handling incoming ACK: ${e.stackTraceToString()}" }
        }
    }

    // ===================== RESPONSE HANDLERS =====================

    private fun handleRegisterResponse(statusCode: Int?, message: String, accountInfo: AccountInfo, lines: List<String>) {
        when (statusCode) {
            CODE_TRYING -> handleTrying()
            CODE_OK -> handleRegisterOk(message, accountInfo, lines)
            CODE_UNAUTHORIZED -> handleAuthenticationChallenge(accountInfo, message, lines)
            else -> handleOtherStatusCodes(statusCode)
        }
    }

    private fun handleInviteResponse(statusCode: Int?, message: String, accountInfo: AccountInfo, lines: List<String>) {
        when (statusCode) {
            CODE_TRYING -> handleTrying()
            CODE_RINGING, CODE_SESSION_PROGRESS -> handleRinging()
            CODE_OK -> handleInviteOk(message, accountInfo, lines)
            CODE_UNAUTHORIZED -> handleAuthenticationChallenge(accountInfo, message, lines)
            CODE_BUSY -> handleBusy()
            CODE_REQUEST_TERMINATED -> handleRequestTerminated(accountInfo, lines)
            else -> handleOtherStatusCodes(statusCode)
        }
    }

    private fun handleReInviteResponse(statusCode: Int?, message: String, accountInfo: AccountInfo, lines: List<String>) {
        log.d(tag = TAG) { "Response to re-INVITE received: $statusCode" }
        val callData = accountInfo.currentCallData ?: return

        when (statusCode) {
            CODE_TRYING -> log.d(tag = TAG) { "Provisional response (100)" }
            CODE_OK -> {
                log.d(tag = TAG) { "Re-INVITE accepted (200 OK)" }
                val sdpContent = SipMessageParser.extractSdpContent(message)
                callData.remoteSdp = sdpContent
                sendAck(accountInfo, callData)

                val stateMessage = if (callData.isOnHold == true) "placed on hold" else "resumed"
                log.d(tag = TAG) { "Call $stateMessage successfully" }
            }
            CODE_UNAUTHORIZED -> handleAuthenticationChallenge(accountInfo, message, lines)
            CODE_BUSY -> {
                log.d(tag = TAG) { "Re-INVITE rejected: Busy (486)" }
                restorePreviousHoldState(callData)
            }
            else -> {
                log.d(tag = TAG) { "Unhandled re-INVITE response: $statusCode" }
                restorePreviousHoldState(callData)
            }
        }
    }

    private fun restorePreviousHoldState(callData: CallData) {
        callData.isOnHold = !callData.isOnHold!!
        val newState = if (callData.isOnHold!!) CallState.HOLDING else CallState.CONNECTED
        CallStateManager.updateCallState(newState)
    }

    private fun handleByeResponse(statusCode: Int?, message: String, accountInfo: AccountInfo, lines: List<String>) {
        when (statusCode) {
            CODE_OK -> {
                log.d(tag = TAG) { "BYE accepted by server" }
                // Limpiar y ejecutar callback
                terminateCall()
            }
            else -> log.d(tag = TAG) { "Unhandled BYE response: $statusCode" }
        }
    }

    private fun handleCancelResponse(statusCode: Int?, message: String, accountInfo: AccountInfo, lines: List<String>) {
        when (statusCode) {
            CODE_OK -> {
                log.d(tag = TAG) { "CANCEL accepted (200 OK)" }
                terminateCall()
            }            
            else -> {
                log.d(tag = TAG) { "Unexpected CANCEL response: $statusCode" }
                terminateCall()
                CallStateManager.updateCallState(CallState.ENDED)
                accountInfo.resetCallState()
            }
        }
    }

    // ===================== SPECIFIC STATUS HANDLERS =====================

    private fun handleInviteOk(message: String, accountInfo: AccountInfo, lines: List<String>) {
        log.d(tag = TAG) { "Outgoing call accepted (200 OK)" }

        val callData = accountInfo.currentCallData ?: return
        val toHeader = SipMessageParser.extractHeader(lines, "To")
        val toTag = SipMessageParser.extractTag(toHeader)
        val sdpContent = SipMessageParser.extractSdpContent(message)

        callData.remoteSdp = sdpContent
        callData.inviteToTag = toTag

        CoroutineScope(Dispatchers.IO).launch {
            sipCoreManager.webRtcManager.setRemoteDescription(sdpContent, SdpType.ANSWER)
        }

        sendAck(accountInfo, callData)
        CallStateManager.updateCallState(CallState.CONNECTED)
        sipCoreManager.callStartTimeMillis = Clock.System.now().toEpochMilliseconds()
    }

    private fun handleRequestTerminated(accountInfo: AccountInfo, lines: List<String>) {
        log.d(tag = TAG) { "Call was canceled (487 Request Terminated)" }

        val callData = accountInfo.currentCallData
        if (callData != null) {
            val ackMessage = SipMessageBuilder.buildAckFor487Response(accountInfo, callData, lines)
            accountInfo.webSocketClient?.send(ackMessage)
        }

        CallStateManager.updateCallState(CallState.ENDED)
        accountInfo.resetCallState()
    }

    private fun handleTrying() {
        log.d(tag = TAG) { "Trying (100)" }
        CallStateManager.updateCallState(CallState.OUTGOING)
    }

    private fun handleRinging() {
        sipCoreManager.playRingtoneUseCase.playOutgoingRingtone()
        log.d(tag = TAG) { "Ringing/Session Progress (180/183)" }
    }

    private fun handleRegisterOk(message: String, accountInfo: AccountInfo, lines: List<String>) {
        val expires = SipMessageParser.extractExpiresValue(message)
        log.d(tag = TAG) { "Successful registration with expiration: $expires seconds" }

        accountInfo.isRegistered = true
        sipCoreManager.updateRegistrationState(RegistrationState.OK)

        // Configure renewal
        val expiresMs = expires * 1000L
        val accountKey = "${accountInfo.username}@${accountInfo.domain}"
        val expirationTime = Clock.System.now().toEpochMilliseconds() + expiresMs
        accountInfo.webSocketClient?.setRegistrationExpiration(accountKey, expirationTime)

        log.d(tag = TAG) { "Registration renewal configured for ${accountInfo.username}" }
    }

    private fun handleBusy() {
        log.d(tag = TAG) { "Call rejected: Busy (486)" }
        CallStateManager.updateCallState(CallState.DECLINED)
    }

    private fun handleOtherStatusCodes(statusCode: Int?) {
        log.d(tag = TAG) { "Unhandled SIP code: $statusCode" }

        if (statusCode != null && statusCode >= 400) {
            sipCoreManager.updateRegistrationState(RegistrationState.FAILED)
            CallStateManager.updateCallState(CallState.ERROR)
        }
    }

    private fun handleAuthenticationChallenge(accountInfo: AccountInfo, message: String, lines: List<String>) {
        log.d(tag = TAG) { "Authentication challenge 401" }

        try {
            val method = SipMessageParser.extractMethodFromCSeq(message, lines)
            accountInfo.method = method
            log.d(tag = TAG) { "Method: $method" }

            val authData = AuthenticationHandler.extractAuthenticationData(lines) ?: return
            log.d(tag = TAG) { "authData: $authData" }

            val authResponse = AuthenticationHandler.calculateAuthResponse(accountInfo, authData, method)
            log.d(tag = TAG) { "authResponse: $authResponse" }
            AuthenticationHandler.updateAccountAuthInfo(accountInfo, authData, authResponse, method)

            when (method) {
                "REGISTER" -> sendAuthenticatedRegister(accountInfo)
                "INVITE" -> accountInfo.currentCallData?.let { sendAuthenticatedInvite(accountInfo, it) }
                else -> log.d(tag = TAG) { "Unhandled method for authentication: $method" }
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "CRITICAL ERROR in handleAuthenticationChallenge: ${e.stackTraceToString()}" }
        }
    }

    // ===================== SENDING METHODS =====================

    /**
     * Generic message sender with error handling
     */
    private fun sendSipMessage(messageBuilder: () -> String, messageType: String, accountInfo: AccountInfo) {
        try {
            val sipMessage = messageBuilder()
            log.d(tag = TAG) { "Sending $messageType" }
            accountInfo.webSocketClient?.send(sipMessage)
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error sending $messageType: ${e.stackTraceToString()}" }
        }
    }

    fun sendRegister(accountInfo: AccountInfo, isAppInBackground: Boolean) {
        sendSipMessage(
            messageBuilder = {
                val callId = generateId()
                val fromTag = generateId()
                accountInfo.callId = callId
                accountInfo.fromTag = fromTag
                SipMessageBuilder.buildRegisterMessage(accountInfo, callId, fromTag, isAppInBackground)
            },
            messageType = "REGISTER",
            accountInfo = accountInfo
        )
    }

    fun sendInvite(accountInfo: AccountInfo, callData: CallData) {
        sendSipMessage(
            messageBuilder = {
                val sipMessage = SipMessageBuilder.buildInviteMessage(accountInfo, callData, callData.localSdp)
                callData.originalInviteMessage = sipMessage
                callData.storeInviteMessage(sipMessage)
                sipMessage
            },
            messageType = "INVITE",
            accountInfo = accountInfo
        )
    }

    fun sendUnregister(accountInfo: AccountInfo) {
        sendSipMessage(
            messageBuilder = {
                val callId = accountInfo.callId ?: generateId()
                val fromTag = accountInfo.fromTag ?: generateId()
                SipMessageBuilder.buildUnregisterMessage(accountInfo, callId, fromTag)
            },
            messageType = "UNREGISTER",
            accountInfo = accountInfo
        )
    }

    fun sendDeclineResponse(accountInfo: AccountInfo, callData: CallData) {
        sendSipMessage(
            messageBuilder = { SipMessageBuilder.buildDeclineResponse(accountInfo, callData) },
            messageType = "603 DECLINE",
            accountInfo = accountInfo
        )
        terminateCall()
    }

    fun sendBye(accountInfo: AccountInfo, callData: CallData) {
        sendSipMessage(
            messageBuilder = { SipMessageBuilder.buildByeMessage(accountInfo, callData) },
            messageType = "BYE",
            accountInfo = accountInfo
        )
    }

    fun sendTrying(accountInfo: AccountInfo, callData: CallData) {
        sendSipMessage(
            messageBuilder = { SipMessageBuilder.buildTryingResponse(accountInfo, callData) },
            messageType = "100 TRYING",
            accountInfo = accountInfo
        )
    }

    fun sendRinging(accountInfo: AccountInfo, callData: CallData) {
        sendSipMessage(
            messageBuilder = { SipMessageBuilder.buildRingingResponse(accountInfo, callData) },
            messageType = "180 RINGING",
            accountInfo = accountInfo
        )
    }

    fun sendAck(accountInfo: AccountInfo, callData: CallData) {
        sendSipMessage(
            messageBuilder = { SipMessageBuilder.buildAckMessage(accountInfo, callData) },
            messageType = "ACK",
            accountInfo = accountInfo
        )
    }

    fun sendInviteOkResponse(accountInfo: AccountInfo, callData: CallData) {
        sendSipMessage(
            messageBuilder = { SipMessageBuilder.buildInviteOkResponse(accountInfo, callData) },
            messageType = "200 OK (INVITE)",
            accountInfo = accountInfo
        )
    }

    fun sendDtmfInfo(accountInfo: AccountInfo, callData: CallData, digit: Char, duration: Int) {
        sendSipMessage(
            messageBuilder = {
                SipMessageBuilder.buildDtmfInfoMessage(accountInfo, callData, digit, duration)
            },
            messageType = "INFO (DTMF)",
            accountInfo = accountInfo
        )
    }

    fun sendCancel(accountInfo: AccountInfo, callData: CallData) {
        sendSipMessage(
            messageBuilder = {
                val sipMessage = SipMessageBuilder.buildCancelMessage(accountInfo, callData)
                sipMessage
            },
            messageType = "CANCEL",
            accountInfo = accountInfo
        )
    }

    fun sendReInvite(accountInfo: AccountInfo, callData: CallData, sdp: String) {
        sendSipMessage(
            messageBuilder = { SipMessageBuilder.buildReInviteMessage(accountInfo, callData, sdp) },
            messageType = "RE-INVITE",
            accountInfo = accountInfo
        )
    }

    private fun sendAuthenticatedRegister(accountInfo: AccountInfo) {
        log.d(tag = TAG) { "accountInfo: ${accountInfo.authorizationHeader}" }

        sendSipMessage(
            messageBuilder = { SipMessageBuilder.buildAuthenticatedRegisterMessage(accountInfo, sipCoreManager.isAppInBackground) },
            messageType = "authenticated REGISTER",
            accountInfo = accountInfo
        )
    }

    private fun sendAuthenticatedInvite(accountInfo: AccountInfo, callData: CallData) {
        sendSipMessage(
            messageBuilder = {
                val sipMessage = SipMessageBuilder.buildAuthenticatedInviteMessage(accountInfo, callData, callData.localSdp)
                callData.originalCallInviteMessage = sipMessage
                sipMessage
            },
            messageType = "authenticated INVITE",
            accountInfo = accountInfo
        )
    }
}