package com.eddyslarez.siplibrary.core

import com.eddyslarez.siplibrary.data.models.AccountInfo
import com.eddyslarez.siplibrary.data.models.CallData
import com.eddyslarez.siplibrary.data.models.CallDirections
import com.eddyslarez.siplibrary.data.models.CallErrorReason
import com.eddyslarez.siplibrary.data.models.CallState
import com.eddyslarez.siplibrary.data.models.CallTypes
import com.eddyslarez.siplibrary.data.models.DtmfRequest
import com.eddyslarez.siplibrary.data.services.audio.AudioDevice
import com.eddyslarez.siplibrary.data.services.audio.CallHoldManager
import com.eddyslarez.siplibrary.data.services.audio.WebRtcManager
import com.eddyslarez.siplibrary.data.services.sip.SipMessageHandler
import com.eddyslarez.siplibrary.utils.CallStateManager
import com.eddyslarez.siplibrary.utils.MultiCallManager
import com.eddyslarez.siplibrary.utils.calculateMD5
import com.eddyslarez.siplibrary.utils.generateId
import com.eddyslarez.siplibrary.utils.generateSipTag
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

class CallManager(
    private val sipCoreManager: SipCoreManager,
    private val audioManager: SipAudioManager,
    private val webRtcManager: WebRtcManager,
    private val messageHandler: SipMessageHandler
) {
    private val callHoldManager = CallHoldManager(webRtcManager)
    private val dtmfQueue = mutableListOf<DtmfRequest>()
    private var isDtmfProcessing = false
    private val dtmfMutex = Mutex()

    companion object {
        private const val TAG = "CallManager"
    }

    /**
     * Realizar llamada saliente
     */
    fun makeCall(phoneNumber: String, accountInfo: AccountInfo) {
        if (!accountInfo.isRegistered) {
            log.d(tag = TAG) { "Error: Not registered with SIP server" }
            sipCoreManager.sipCallbacks?.onCallFailed("Not registered with SIP server")
            return
        }

        log.d(tag = TAG) { "Making call from ${accountInfo.username}@${accountInfo.domain} to $phoneNumber" }
        audioManager.stopAllRingtones()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                audioManager.prepareAudioForOutgoingCall()
                val sdp = audioManager.createOffer()

                val callId = accountInfo.generateCallId()
                val md5Hash = calculateMD5(callId)
                val callData = CallData(
                    callId = callId,
                    to = phoneNumber,
                    from = accountInfo.username,
                    direction = CallDirections.OUTGOING,
                    inviteFromTag = generateSipTag(),
                    localSdp = sdp,
                    md5Hash = md5Hash
                )

                accountInfo.currentCallData = callData

                CallStateManager.startOutgoingCall(callId, phoneNumber)
                sipCoreManager.notifyCallStateChanged(CallState.OUTGOING_INIT)

                audioManager.playOutgoingRingtone()
                messageHandler.sendInvite(accountInfo, callData)

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error creating call: ${e.stackTraceToString()}" }
                sipCoreManager.sipCallbacks?.onCallFailed("Error creating call: ${e.message}")

                accountInfo.currentCallData?.let { callData ->
                    CallStateManager.callError(
                        callData.callId,
                        errorReason = CallErrorReason.NETWORK_ERROR
                    )
                }
                audioManager.stopOutgoingRingtone()
            }
        }
    }

    /**
     * Finalizar llamada
     */
    fun endCall(callId: String? = null) {
        val accountInfo = sipCoreManager.currentAccountInfo ?: run {
            log.e(tag = TAG) { "No current account available for end call" }
            return
        }

        val targetCallData = if (callId != null) {
            MultiCallManager.getCall(callId)
        } else {
            accountInfo.currentCallData
        } ?: run {
            log.e(tag = TAG) { "No call data available for end" }
            return
        }

        val callState = if (callId != null) {
            MultiCallManager.getCallState(callId)
        } else {
            CallStateManager.getCurrentState()
        }

        if (callState?.isActive() != true) {
            log.w(tag = TAG) { "No active call to end" }
            return
        }

        val endTime = Clock.System.now().toEpochMilliseconds()
        val currentState = callState.state

        log.d(tag = TAG) { "Ending call: ${targetCallData.callId}" }

        // Detener todos los ringtones inmediatamente
        audioManager.stopAllRingtones()

        CallStateManager.startEnding(targetCallData.callId)
        clearDtmfQueue()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Enviar mensaje SIP apropiado
                val messageJob = launch {
                    when (currentState) {
                        CallState.CONNECTED, CallState.STREAMS_RUNNING, CallState.PAUSED -> {
                            log.d(tag = TAG) { "Sending BYE for established call" }
                            messageHandler.sendBye(accountInfo, targetCallData)
                            sipCoreManager.callHistoryManager.addCallLog(targetCallData, CallTypes.SUCCESS, endTime)
                        }
                        CallState.OUTGOING_INIT, CallState.OUTGOING_PROGRESS, CallState.OUTGOING_RINGING -> {
                            log.d(tag = TAG) { "Sending CANCEL for outgoing call" }
                            messageHandler.sendCancel(accountInfo, targetCallData)
                            sipCoreManager.callHistoryManager.addCallLog(targetCallData, CallTypes.ABORTED, endTime)
                        }
                        CallState.INCOMING_RECEIVED -> {
                            log.d(tag = TAG) { "Sending DECLINE for incoming call" }
                            messageHandler.sendDeclineResponse(accountInfo, targetCallData)
                            sipCoreManager.callHistoryManager.addCallLog(targetCallData, CallTypes.DECLINED, endTime)
                        }
                        else -> {
                            log.w(tag = TAG) { "Ending call in unexpected state: $currentState" }
                            messageHandler.sendBye(accountInfo, targetCallData)
                        }
                    }
                }

                // Limpieza en paralelo
                val cleanupJob = launch {
                    delay(500)

                    if (MultiCallManager.getAllCalls().size <= 1) {
                        audioManager.dispose()
                        log.d(tag = TAG) { "Audio disposed - no more active calls" }
                    }

                    delay(500)
                    CallStateManager.callEnded(targetCallData.callId)
                    sipCoreManager.notifyCallStateChanged(CallState.ENDED)

                    if (accountInfo.currentCallData?.callId == targetCallData.callId) {
                        accountInfo.resetCallState()
                    }

                    sipCoreManager.handleCallTermination()
                }

                messageJob.join()
                cleanupJob.join()

                log.d(tag = TAG) { "Call cleanup completed successfully" }

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error during call cleanup: ${e.message}" }
                audioManager.stopAllRingtones()
                if (accountInfo.currentCallData?.callId == targetCallData.callId) {
                    accountInfo.resetCallState()
                }
            }
        }
    }

    /**
     * Aceptar llamada entrante
     */
    fun acceptCall(callId: String? = null) {
        val accountInfo = sipCoreManager.currentAccountInfo ?: run {
            log.e(tag = TAG) { "No current account available for accepting call" }
            return
        }

        val targetCallData = if (callId != null) {
            MultiCallManager.getCall(callId)
        } else {
            accountInfo.currentCallData
        } ?: run {
            log.e(tag = TAG) { "No call data available for accepting" }
            return
        }

        val callState = if (callId != null) {
            MultiCallManager.getCallState(callId)
        } else {
            CallStateManager.getCurrentState()
        }

        if (targetCallData.direction != CallDirections.INCOMING ||
            callState?.state != CallState.INCOMING_RECEIVED) {
            log.w(tag = TAG) { "Cannot accept call - invalid state or direction" }
            return
        }

        log.d(tag = TAG) { "Accepting call: ${targetCallData.callId}" }
        audioManager.stopAllRingtones()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                audioManager.prepareAudioForIncomingCall()
                val sdp = audioManager.createAnswer(accountInfo, targetCallData.remoteSdp ?: "")
                targetCallData.localSdp = sdp

                messageHandler.sendInviteOkResponse(accountInfo, targetCallData)

                CallStateManager.callConnected(targetCallData.callId, 200)
                sipCoreManager.notifyCallStateChanged(CallState.CONNECTED)

                delay(500)
                audioManager.setAudioEnabled(true)

            } catch (e: Exception) {
                log.e(tag = TAG) { "Error accepting call: ${e.message}" }
                CallStateManager.callError(
                    targetCallData.callId,
                    errorReason = CallErrorReason.NETWORK_ERROR
                )
                declineCall(callId)
            }
        }
    }

    /**
     * Rechazar llamada entrante
     */
    fun declineCall(callId: String? = null) {
        val accountInfo = sipCoreManager.currentAccountInfo ?: run {
            log.e(tag = TAG) { "No current account available for declining call" }
            return
        }

        val targetCallData = if (callId != null) {
            MultiCallManager.getCall(callId)
        } else {
            accountInfo.currentCallData
        } ?: run {
            log.e(tag = TAG) { "No call data available for declining" }
            return
        }

        if (targetCallData.direction != CallDirections.INCOMING) {
            log.w(tag = TAG) { "Cannot decline call - not incoming" }
            return
        }

        log.d(tag = TAG) { "Declining call: ${targetCallData.callId}" }

        if (targetCallData.toTag?.isEmpty() == true) {
            targetCallData.toTag = generateId()
        }

        audioManager.stopRingtone()
        messageHandler.sendDeclineResponse(accountInfo, targetCallData)

        val endTime = Clock.System.now().toEpochMilliseconds()
        sipCoreManager.callHistoryManager.addCallLog(targetCallData, CallTypes.DECLINED, endTime)

        CallStateManager.callEnded(targetCallData.callId, sipReason = "Declined")
    }

    /**
     * Silenciar/Desactivar silencio
     */
    fun toggleMute(): Boolean {
        return audioManager.toggleMute()
    }

    /**
     * Verificar si está silenciado
     */
    fun isMuted(): Boolean = audioManager.isMuted()

    /**
     * Poner llamada en espera
     */
    fun holdCall(callId: String? = null) {
        val accountInfo = sipCoreManager.currentAccountInfo ?: return
        val targetCallData = if (callId != null) {
            MultiCallManager.getCall(callId)
        } else {
            accountInfo.currentCallData
        } ?: return

        val currentState = CallStateManager.getCurrentState()
        if (currentState.state != CallState.STREAMS_RUNNING &&
            currentState.state != CallState.CONNECTED) {
            log.w(tag = TAG) { "Cannot hold call in current state: ${currentState.state}" }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                CallStateManager.startHold(targetCallData.callId)
                sipCoreManager.notifyCallStateChanged(CallState.PAUSING)

                callHoldManager.holdCall()?.let { holdSdp ->
                    targetCallData.localSdp = holdSdp
                    targetCallData.isOnHold = true
                    messageHandler.sendReInvite(accountInfo, targetCallData, holdSdp)

                    delay(1000)
                    CallStateManager.callOnHold(targetCallData.callId)
                    sipCoreManager.notifyCallStateChanged(CallState.PAUSED)
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error holding call: ${e.message}" }
            }
        }
    }

    /**
     * Reanudar llamada en espera
     */
    fun resumeCall(callId: String? = null) {
        val accountInfo = sipCoreManager.currentAccountInfo ?: return
        val targetCallData = if (callId != null) {
            MultiCallManager.getCall(callId)
        } else {
            accountInfo.currentCallData
        } ?: return

        val currentState = CallStateManager.getCurrentState()
        if (currentState.state != CallState.PAUSED) {
            log.w(tag = TAG) { "Cannot resume call in current state: ${currentState.state}" }
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                CallStateManager.startResume(targetCallData.callId)
                sipCoreManager.notifyCallStateChanged(CallState.RESUMING)

                callHoldManager.resumeCall()?.let { resumeSdp ->
                    targetCallData.localSdp = resumeSdp
                    targetCallData.isOnHold = false
                    messageHandler.sendReInvite(accountInfo, targetCallData, resumeSdp)

                    delay(1000)
                    CallStateManager.callResumed(targetCallData.callId)
                    sipCoreManager.notifyCallStateChanged(CallState.STREAMS_RUNNING)
                }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error resuming call: ${e.message}" }
            }
        }
    }

    /**
     * Enviar DTMF
     */
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

    /**
     * Enviar secuencia DTMF
     */
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
                    delay(150)
                }
            }
        } finally {
            dtmfMutex.withLock {
                isDtmfProcessing = false
            }
        }
    }

    private suspend fun sendSingleDtmf(digit: Char, duration: Int): Boolean {
        val currentAccount = sipCoreManager.currentAccountInfo
        val callData = currentAccount?.currentCallData

        if (currentAccount == null || callData == null ||
            !CallStateManager.getCurrentState().isConnected()) {
            return false
        }

        return try {
            webRtcManager.sendDtmfTones(
                tones = digit.toString().uppercase(),
                duration = duration,
                gap = 100
            )
        } catch (e: Exception) {
            false
        }
    }

    internal fun clearDtmfQueue() {
        CoroutineScope(Dispatchers.IO).launch {
            dtmfMutex.withLock {
                dtmfQueue.clear()
                isDtmfProcessing = false
            }
        }
    }

    /**
     * Obtener dispositivos de audio
     */
    fun getAudioDevices() = audioManager.getAudioDevices()

    /**
     * Cambiar dispositivo de audio
     */
    fun changeAudioDevice(device: AudioDevice) = audioManager.changeAudioDevice(device)

    /**
     * Verificar si hay llamada activa
     */
    fun hasActiveCall(): Boolean = CallStateManager.getCurrentState().isActive()

    /**
     * Verificar si hay llamada conectada
     */
    fun hasConnectedCall(): Boolean = CallStateManager.getCurrentState().isConnected()

    /**
     * Manejar conexión WebRTC establecida
     */
    fun handleWebRtcConnected() {
        sipCoreManager.callStartTimeMillis = Clock.System.now().toEpochMilliseconds()

        sipCoreManager.currentAccountInfo?.currentCallData?.let { callData ->
            CallStateManager.streamsRunning(callData.callId)
        }

        sipCoreManager.notifyCallStateChanged(CallState.STREAMS_RUNNING)
    }

    /**
     * Manejar cierre de WebRTC
     */
    fun handleWebRtcClosed() {
        sipCoreManager.currentAccountInfo?.currentCallData?.let { callData ->
            CallStateManager.callEnded(callData.callId)
        }

        sipCoreManager.notifyCallStateChanged(CallState.ENDED)

        sipCoreManager.currentAccountInfo?.currentCallData?.let { callData ->
            val endTime = Clock.System.now().toEpochMilliseconds()
            val callType = determineCallType(callData, CallStateManager.getCurrentState().state)
            sipCoreManager.callHistoryManager.addCallLog(callData, callType, endTime)
        }
    }

    private fun determineCallType(callData: CallData, finalState: CallState): CallTypes {
        return when (finalState) {
            CallState.CONNECTED, CallState.STREAMS_RUNNING, CallState.ENDED -> CallTypes.SUCCESS
            CallState.ERROR -> if (callData.direction == CallDirections.INCOMING) {
                CallTypes.MISSED
            } else {
                CallTypes.ABORTED
            }
            else -> if (callData.direction == CallDirections.INCOMING) {
                CallTypes.MISSED
            } else {
                CallTypes.ABORTED
            }
        }
    }
}
