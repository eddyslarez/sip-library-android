package com.eddyslarez.siplibrary.data.database.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import com.eddyslarez.siplibrary.data.database.SipDatabase
import com.eddyslarez.siplibrary.data.database.entities.*
import com.eddyslarez.siplibrary.data.database.dao.*
import com.eddyslarez.siplibrary.data.models.*
import com.eddyslarez.siplibrary.data.services.transcription.AudioTranscriptionService
import com.eddyslarez.siplibrary.utils.generateId
import com.eddyslarez.siplibrary.utils.log

/**
 * Repositorio principal para operaciones de base de datos SIP
 * 
 * @author Eddys Larez
 */
class SipRepository(private val database: SipDatabase) {
    
    private val sipAccountDao = database.sipAccountDao()
    private val callLogDao = database.callLogDao()
    private val callDataDao = database.callDataDao()
    private val contactDao = database.contactDao()
    private val callStateHistoryDao = database.callStateHistoryDao()
    private val transcriptionDao = database.transcriptionDao()
    private val transcriptionSessionDao = database.transcriptionSessionDao()
    
    private val TAG = "SipRepository"
    
    // === OPERACIONES DE CUENTAS SIP ===
    
    /**
     * Obtiene todas las cuentas activas
     */
    fun getActiveAccounts(): Flow<List<SipAccountEntity>> {
        return sipAccountDao.getActiveAccounts()
    }
    
    /**
     * Obtiene cuentas registradas
     */
    fun getRegisteredAccounts(): Flow<List<SipAccountEntity>> {
        return sipAccountDao.getRegisteredAccounts()
    }
    
    /**
     * Obtiene cuenta por credenciales
     */
    suspend fun getAccountByCredentials(username: String, domain: String): SipAccountEntity? {
        return sipAccountDao.getAccountByCredentials(username, domain)
    }
    
    /**
     * Crea o actualiza una cuenta SIP
     */
    suspend fun createOrUpdateAccount(
        username: String,
        password: String,
        domain: String,
        displayName: String? = null,
        pushToken: String? = null,
        pushProvider: String? = null
    ): SipAccountEntity {
        val existingAccount = getAccountByCredentials(username, domain)
        
        val account = if (existingAccount != null) {
            existingAccount.copy(
                password = password,
                displayName = displayName ?: existingAccount.displayName,
                pushToken = pushToken ?: existingAccount.pushToken,
                pushProvider = pushProvider ?: existingAccount.pushProvider,
                updatedAt = System.currentTimeMillis()
            )
        } else {
            SipAccountEntity(
                id = generateId(),
                username = username,
                password = password,
                domain = domain,
                displayName = displayName ?: username,
                pushToken = pushToken,
                pushProvider = pushProvider
            )
        }
        
        sipAccountDao.insertAccount(account)
        log.d(tag = TAG) { "Account created/updated: ${account.getAccountKey()}" }
        
        return account
    }
    
    /**
     * Actualiza estado de registro
     */
    suspend fun updateRegistrationState(
        accountId: String,
        state: RegistrationState,
        expiry: Long? = null
    ) {
        if (expiry != null) {
            sipAccountDao.updateRegistrationWithExpiry(accountId, state, expiry)
        } else {
            sipAccountDao.updateRegistrationState(accountId, state)
        }
        log.d(tag = TAG) { "Registration state updated: $accountId -> $state" }
    }
    
    /**
     * Elimina una cuenta
     */
    suspend fun deleteAccount(accountId: String) {
        sipAccountDao.deleteAccountById(accountId)
        log.d(tag = TAG) { "Account deleted: $accountId" }
    }
    
    // === OPERACIONES DE HISTORIAL DE LLAMADAS ===
    
    /**
     * Obtiene historial de llamadas reciente
     */
    fun getRecentCallLogs(limit: Int = 50): Flow<List<CallLogWithContact>> {
        return callLogDao.getRecentCallLogs(limit).map { callLogs ->
            callLogs.map { callLog ->
                val contact = contactDao.getContactByPhoneNumber(callLog.phoneNumber)
                CallLogWithContact(callLog, contact)
            }
        }
    }
    
    /**
     * Obtiene llamadas perdidas
     */
    fun getMissedCalls(): Flow<List<CallLogWithContact>> {
        return callLogDao.getMissedCalls().map { callLogs ->
            callLogs.map { callLog ->
                val contact = contactDao.getContactByPhoneNumber(callLog.phoneNumber)
                CallLogWithContact(callLog, contact)
            }
        }
    }
    
    /**
     * Crea entrada en historial de llamadas
     */
    suspend fun createCallLog(
        accountId: String,
        callData: CallData,
        callType: CallTypes,
        endTime: Long? = null,
        sipCode: Int? = null,
        sipReason: String? = null
    ): CallLogEntity {
        val duration = if (endTime != null && callData.startTime > 0) {
            ((endTime - callData.startTime) / 1000).toInt()
        } else {
            0
        }
        
        val callLog = CallLogEntity(
            id = generateId(),
            accountId = accountId,
            callId = callData.callId,
            phoneNumber = callData.getRemoteParty(),
            displayName = callData.remoteDisplayName.ifEmpty { callData.getRemoteParty() },
            direction = callData.direction,
            callType = callType,
            startTime = callData.startTime,
            endTime = endTime,
            duration = duration,
            sipCode = sipCode,
            sipReason = sipReason,
            localAddress = callData.getLocalParty()
        )
        
        callLogDao.insertCallLog(callLog)
        
        // Actualizar estadísticas de contacto
        updateContactStatistics(callLog.phoneNumber, callType, duration.toLong())
        
        // Actualizar estadísticas de cuenta
        updateAccountStatistics(accountId, callType)
        
        log.d(tag = TAG) { "Call log created: ${callLog.id} (${callType.name})" }
        
        return callLog
    }
    
    /**
     * Busca en historial de llamadas
     */
    fun searchCallLogs(query: String): Flow<List<CallLogWithContact>> {
        return callLogDao.searchCallLogs(query).map { callLogs ->
            callLogs.map { callLog ->
                val contact = contactDao.getContactByPhoneNumber(callLog.phoneNumber)
                CallLogWithContact(callLog, contact)
            }
        }
    }
    
    /**
     * Limpia historial de llamadas
     */
    suspend fun clearCallLogs() {
        callLogDao.deleteAllCallLogs()
        log.d(tag = TAG) { "All call logs cleared" }
    }
    
    // === OPERACIONES DE DATOS DE LLAMADAS ===
    
    /**
     * Obtiene llamadas activas
     */
    fun getActiveCalls(): Flow<List<CallDataEntity>> {
        return callDataDao.getActiveCallData()
    }
    
    /**
     * Crea datos de llamada
     */
    suspend fun createCallData(
        accountId: String,
        callData: CallData
    ): CallDataEntity {
        val callDataEntity = CallDataEntity(
            callId = callData.callId,
            accountId = accountId,
            fromNumber = callData.from,
            toNumber = callData.to,
            direction = callData.direction,
            currentState = CallState.IDLE,
            startTime = callData.startTime,
            fromTag = callData.fromTag,
            toTag = callData.toTag,
            inviteFromTag = callData.inviteFromTag,
            inviteToTag = callData.inviteToTag,
            remoteContactUri = callData.remoteContactUri,
            remoteDisplayName = callData.remoteDisplayName,
            localSdp = callData.localSdp,
            remoteSdp = callData.remoteSdp,
            viaHeader = callData.via,
            inviteViaBranch = callData.inviteViaBranch,
            lastCSeqValue = callData.lastCSeqValue,
            originalInviteMessage = callData.originalInviteMessage,
            originalCallInviteMessage = callData.originalCallInviteMessage,
            md5Hash = callData.md5Hash,
            sipName = callData.sipName
        )
        
        callDataDao.insertCallData(callDataEntity)
        log.d(tag = TAG) { "Call data created: ${callData.callId}" }
        
        return callDataEntity
    }
    
    /**
     * Actualiza estado de llamada
     */
    suspend fun updateCallState(
        callId: String,
        newState: CallState,
        errorReason: CallErrorReason = CallErrorReason.NONE,
        sipCode: Int? = null,
        sipReason: String? = null
    ) {
        // Obtener estado anterior
        val currentCallData = callDataDao.getCallDataById(callId)
        val previousState = currentCallData?.currentState
        
        // Actualizar estado en call_data
        callDataDao.updateCallState(callId, newState)
        
        // Crear entrada en historial de estados
        val stateHistory = CallStateHistoryEntity(
            id = generateId(),
            callId = callId,
            state = newState,
            previousState = previousState,
            timestamp = System.currentTimeMillis(),
            errorReason = errorReason,
            sipCode = sipCode,
            sipReason = sipReason,
            hasError = errorReason != CallErrorReason.NONE || newState == CallState.ERROR
        )
        
        callStateHistoryDao.insertStateHistory(stateHistory)
        
        log.d(tag = TAG) { "Call state updated: $callId -> $previousState -> $newState" }
    }
    
    /**
     * Finaliza llamada
     */
    suspend fun endCall(callId: String, endTime: Long = System.currentTimeMillis()) {
        callDataDao.endCall(callId, endTime)
        updateCallState(callId, CallState.ENDED)
        log.d(tag = TAG) { "Call ended: $callId" }
    }
    
    // === OPERACIONES DE CONTACTOS ===
    
    /**
     * Obtiene todos los contactos
     */
    fun getAllContacts(): Flow<List<ContactEntity>> {
        return contactDao.getAllContacts()
    }
    
    /**
     * Busca contactos
     */
    fun searchContacts(query: String): Flow<List<ContactEntity>> {
        return contactDao.searchContacts(query)
    }
    
    /**
     * Crea o actualiza contacto
     */
    suspend fun createOrUpdateContact(
        phoneNumber: String,
        displayName: String,
        firstName: String? = null,
        lastName: String? = null,
        email: String? = null,
        company: String? = null
    ): ContactEntity {
        val existingContact = contactDao.getContactByPhoneNumber(phoneNumber)
        
        val contact = if (existingContact != null) {
            existingContact.copy(
                displayName = displayName,
                firstName = firstName ?: existingContact.firstName,
                lastName = lastName ?: existingContact.lastName,
                email = email ?: existingContact.email,
                company = company ?: existingContact.company,
                updatedAt = System.currentTimeMillis()
            )
        } else {
            ContactEntity(
                id = generateId(),
                phoneNumber = phoneNumber,
                displayName = displayName,
                firstName = firstName,
                lastName = lastName,
                email = email,
                company = company
            )
        }
        
        contactDao.insertContact(contact)
        log.d(tag = TAG) { "Contact created/updated: $phoneNumber" }
        
        return contact
    }
    
    /**
     * Verifica si un número está bloqueado
     */
    suspend fun isPhoneNumberBlocked(phoneNumber: String): Boolean {
        return contactDao.isPhoneNumberBlocked(phoneNumber) ?: false
    }
    
    // === OPERACIONES DE TRANSCRIPCIÓN ===
    
    /**
     * Crea sesión de transcripción
     */
    suspend fun createTranscriptionSession(
        callLogId: String,
        callId: String,
        config: AudioTranscriptionService.TranscriptionConfig
    ): TranscriptionSessionEntity {
        val session = TranscriptionSessionEntity(
            id = generateId(),
            callLogId = callLogId,
            callId = callId,
            startTime = System.currentTimeMillis(),
            language = config.language,
            audioSource = config.audioSource,
            transcriptionProvider = config.transcriptionProvider,
            enablePartialResults = config.enablePartialResults,
            confidenceThreshold = config.confidenceThreshold,
            enableProfanityFilter = config.enableProfanityFilter,
            enablePunctuation = config.enablePunctuation
        )
        
        transcriptionSessionDao.insertSession(session)
        log.d(tag = TAG) { "Transcription session created: ${session.id}" }
        
        return session
    }
    
    /**
     * Crea resultado de transcripción
     */
    suspend fun createTranscriptionResult(
        sessionId: String,
        callLogId: String,
        result: AudioTranscriptionService.TranscriptionResult
    ): TranscriptionEntity {
        val transcription = TranscriptionEntity(
            id = result.id,
            sessionId = sessionId,
            callLogId = callLogId,
            text = result.text,
            confidence = result.confidence,
            isFinal = result.isFinal,
            timestamp = result.timestamp,
            duration = result.duration,
            audioSource = result.audioSource,
            language = result.language,
            speakerLabel = result.speakerLabel,
            wordCount = result.text.split("\\s+".toRegex()).size
        )
        
        transcriptionDao.insertTranscription(transcription)
        
        // Actualizar estadísticas de sesión si es resultado final
        if (result.isFinal) {
            updateTranscriptionSessionStats(sessionId)
        }
        
        log.d(tag = TAG) { "Transcription result created: ${transcription.id}" }
        
        return transcription
    }
    
    /**
     * Finaliza sesión de transcripción
     */
    suspend fun endTranscriptionSession(sessionId: String) {
        val endTime = System.currentTimeMillis()
        transcriptionSessionDao.endSession(sessionId, endTime)
        
        // Actualizar estadísticas finales
        updateTranscriptionSessionStats(sessionId)
        
        log.d(tag = TAG) { "Transcription session ended: $sessionId" }
    }
    
    /**
     * Obtiene transcripciones por sesión
     */
    fun getTranscriptionsBySession(sessionId: String): Flow<List<TranscriptionEntity>> {
        return transcriptionDao.getTranscriptionsBySession(sessionId)
    }
    
    /**
     * Obtiene sesiones de transcripción
     */
    fun getTranscriptionSessions(): Flow<List<TranscriptionSessionEntity>> {
        return transcriptionSessionDao.getAllSessions()
    }
    
    /**
     * Busca en transcripciones
     */
    fun searchTranscriptions(query: String): Flow<List<TranscriptionEntity>> {
        return transcriptionDao.searchTranscriptions(query)
    }
    
    /**
     * Actualiza estadísticas de sesión de transcripción
     */
    private suspend fun updateTranscriptionSessionStats(sessionId: String) {
        try {
            val analysis = transcriptionDao.getTranscriptionById(sessionId)
            if (analysis != null) {
                transcriptionSessionDao.updateSessionStatistics(
                    sessionId = sessionId,
                    total = analysis.wordCount,
                    final = analysis.wordCount,
                    partial = analysis.wordCount - analysis.wordCount,
                    words = analysis.wordCount,
                    confidence = analysis.confidence,
                    speechDuration = 0L, // Se calcularía de los timestamps
                    silenceDuration = 0L, // Se calcularía de los gaps
                    audioFrames = 0L, // Se trackearía desde el interceptor
                    errors = 0 // Se trackearían los errores


                )
            }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error updating transcription session stats: ${e.message}" }
        }
    }
    
    // === ESTADÍSTICAS ===
    
    /**
     * Obtiene estadísticas generales
     */
    suspend fun getGeneralStatistics(): GeneralStatistics {
        val totalAccounts = sipAccountDao.getActiveAccountCount()
        val registeredAccounts = sipAccountDao.getRegisteredAccountCount()
        val totalCalls = callLogDao.getTotalCallCount()
        val missedCalls = callLogDao.getCallCountByType(CallTypes.MISSED)
        val totalContacts = contactDao.getTotalContactCount()
        val activeCalls = callDataDao.getActiveCallCount()
        val totalTranscriptions = transcriptionDao.getTotalTranscriptionCount()
        val activeSessions = transcriptionSessionDao.getActiveSessionCount()
        
        return GeneralStatistics(
            totalAccounts = totalAccounts,
            registeredAccounts = registeredAccounts,
            totalCalls = totalCalls,
            missedCalls = missedCalls,
            totalContacts = totalContacts,
            activeCalls = activeCalls,
            totalTranscriptions = totalTranscriptions,
            activeTranscriptionSessions = activeSessions
        )
    }
    
    /**
     * Obtiene estadísticas de llamadas para un número
     */
    suspend fun getCallStatisticsForNumber(phoneNumber: String): CallStatistics? {
        return callLogDao.getCallStatisticsForNumber(phoneNumber)
    }
    
    // === MÉTODOS PRIVADOS ===
    
    /**
     * Actualiza estadísticas de contacto
     */
    private suspend fun updateContactStatistics(
        phoneNumber: String,
        callType: CallTypes,
        duration: Long
    ) {
        contactDao.incrementCallCount(phoneNumber)
        
        if (callType == CallTypes.SUCCESS && duration > 0) {
            contactDao.addCallDuration(phoneNumber, duration)
        }
        
        if (callType == CallTypes.MISSED) {
            contactDao.incrementMissedCalls(phoneNumber)
        }
    }
    
    /**
     * Actualiza estadísticas de cuenta
     */
    private suspend fun updateAccountStatistics(accountId: String, callType: CallTypes) {
        sipAccountDao.incrementTotalCalls(accountId)
        
        when (callType) {
            CallTypes.SUCCESS -> sipAccountDao.incrementSuccessfulCalls(accountId)
            CallTypes.MISSED, CallTypes.DECLINED, CallTypes.ABORTED -> 
                sipAccountDao.incrementFailedCalls(accountId)
        }
    }
    
    // === LIMPIEZA ===
    
    /**
     * Limpia datos antiguos
     */
    suspend fun cleanupOldData(daysToKeep: Int = 30) {
        val threshold = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)
        
        callLogDao.deleteCallLogsOlderThan(threshold)
        callStateHistoryDao.deleteStateHistoryOlderThan(threshold)
        callDataDao.deleteInactiveCallsOlderThan(threshold)
        transcriptionDao.deleteTranscriptionsOlderThan(threshold)
        transcriptionSessionDao.deleteSessionsOlderThan(threshold)
        
        log.d(tag = TAG) { "Cleanup completed for data older than $daysToKeep days" }
    }
    
    /**
     * Mantiene solo los registros más recientes
     */
    suspend fun keepOnlyRecentData(
        callLogsLimit: Int = 1000,
        stateHistoryLimit: Int = 5000,
        transcriptionsLimit: Int = 2000
    ) {
        callLogDao.keepOnlyRecentCallLogs(callLogsLimit)
        callStateHistoryDao.keepOnlyRecentStateHistory(stateHistoryLimit)
        transcriptionDao.keepOnlyRecentTranscriptions(transcriptionsLimit)
        
        log.d(tag = TAG) { 
            "Kept only recent data: $callLogsLimit call logs, $stateHistoryLimit state history, $transcriptionsLimit transcriptions" 
        }
    }
}

/**
 * Clases de datos para el repositorio
 */
data class CallLogWithContact(
    val callLog: CallLogEntity,
    val contact: ContactEntity?
) {
    fun getDisplayName(): String {
        return contact?.displayName ?: callLog.displayName ?: callLog.phoneNumber
    }
    
    fun getAvatarUrl(): String? {
        return contact?.avatarUrl
    }
    
    fun isBlocked(): Boolean {
        return contact?.isBlocked ?: false
    }
    
    fun isFavorite(): Boolean {
        return contact?.isFavorite ?: false
    }
}

data class GeneralStatistics(
    val totalAccounts: Int,
    val registeredAccounts: Int,
    val totalCalls: Int,
    val missedCalls: Int,
    val totalContacts: Int,
    val activeCalls: Int,
    val totalTranscriptions: Int,
    val activeTranscriptionSessions: Int
)