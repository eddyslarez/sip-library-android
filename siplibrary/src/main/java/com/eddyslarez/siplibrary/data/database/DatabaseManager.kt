package com.eddyslarez.siplibrary.data.database

import android.app.Application
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import com.eddyslarez.siplibrary.data.database.entities.*
import com.eddyslarez.siplibrary.data.database.repository.SipRepository
import com.eddyslarez.siplibrary.data.database.repository.CallLogWithContact
import com.eddyslarez.siplibrary.data.database.repository.GeneralStatistics
import com.eddyslarez.siplibrary.data.models.*
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.cancel

/**
 * Gestor principal de base de datos para la librería SIP
 * Proporciona una interfaz simplificada para todas las operaciones de base de datos
 * 
 * @author Eddys Larez
 */
class DatabaseManager private constructor(application: Application) {

    private val database = SipDatabase.getDatabase(application)
    private val repository = SipRepository(database)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile
    private var isInitialized = false

    private val TAG = "DatabaseManager"

    companion object {
        @Volatile
        private var INSTANCE: DatabaseManager? = null
        private val LOCK = Any()

        fun getInstance(application: Application): DatabaseManager {
            return INSTANCE ?: synchronized(LOCK) {
                INSTANCE ?: DatabaseManager(application).also {
                    INSTANCE = it
                    it.ensureInitialized()
                }
            }
        }

        /**
         * NUEVO: Método para verificar si existe una instancia
         */
        fun hasInstance(): Boolean {
            return INSTANCE != null
        }

        /**
         * NUEVO: Obtiene instancia existente sin crear una nueva
         */
        fun getExistingInstance(): DatabaseManager? {
            return INSTANCE
        }
    }

    // === OPERACIONES DE CUENTAS SIP ===

    /**
     * Crea o actualiza una cuenta SIP
     */
    suspend fun createOrUpdateSipAccount(
        username: String,
        password: String,
        domain: String,
        displayName: String? = null,
        pushToken: String? = null,
        pushProvider: String? = null
    ): SipAccountEntity {
        return repository.createOrUpdateAccount(
            username = username,
            password = password,
            domain = domain,
            displayName = displayName,
            pushToken = pushToken,
            pushProvider = pushProvider
        )
    }

    /**
     * Obtiene todas las cuentas activas
     */
    fun getActiveSipAccounts(): Flow<List<SipAccountEntity>> {
        return repository.getActiveAccounts()
    }

    /**
     * Obtiene cuentas registradas
     */
    fun getRegisteredSipAccounts(): Flow<List<SipAccountEntity>> {
        return repository.getRegisteredAccounts()
    }

    /**
     * Obtiene cuenta por credenciales
     */
    suspend fun getSipAccountByCredentials(username: String, domain: String): SipAccountEntity? {
        return repository.getAccountByCredentials(username, domain)
    }

    /**
     * Actualiza estado de registro de cuenta
     */
    suspend fun updateSipAccountRegistrationState(
        accountId: String,
        state: RegistrationState,
        expiry: Long? = null
    ) {
        repository.updateRegistrationState(accountId, state, expiry)
    }

    /**
     * Elimina cuenta SIP
     */
    suspend fun deleteSipAccount(accountId: String) {
        repository.deleteAccount(accountId)
    }

    // === OPERACIONES DE HISTORIAL DE LLAMADAS ===

    /**
     * Obtiene historial de llamadas reciente
     */
    fun getRecentCallLogs(limit: Int = 50): Flow<List<CallLogWithContact>> {
        return repository.getRecentCallLogs(limit)
    }

    /**
     * Obtiene llamadas perdidas
     */
    fun getMissedCallLogs(): Flow<List<CallLogWithContact>> {
        return repository.getMissedCalls()
    }

    /**
     * Busca en historial de llamadas
     */
    fun searchCallLogs(query: String): Flow<List<CallLogWithContact>> {
        return repository.searchCallLogs(query)
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
        return repository.createCallLog(
            accountId = accountId,
            callData = callData,
            callType = callType,
            endTime = endTime,
            sipCode = sipCode,
            sipReason = sipReason
        )
    }

    /**
     * Limpia todo el historial de llamadas
     */
    suspend fun clearAllCallLogs() {
        repository.clearCallLogs()
    }

    // === OPERACIONES DE DATOS DE LLAMADAS ACTIVAS ===

    /**
     * Obtiene llamadas activas
     */
    fun getActiveCallData(): Flow<List<CallDataEntity>> {
        return repository.getActiveCalls()
    }

    /**
     * Crea datos de llamada activa
     */
    suspend fun createCallData(accountId: String, callData: CallData): CallDataEntity {
        return repository.createCallData(accountId, callData)
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
        repository.updateCallState(callId, newState, errorReason, sipCode, sipReason)
    }

    /**
     * Finaliza llamada
     */
    suspend fun endCall(callId: String, endTime: Long = System.currentTimeMillis()) {
        repository.endCall(callId, endTime)
    }

    // === OPERACIONES DE CONTACTOS ===

    /**
     * Obtiene todos los contactos
     */
    fun getAllContacts(): Flow<List<ContactEntity>> {
        return repository.getAllContacts()
    }

    /**
     * Busca contactos
     */
    fun searchContacts(query: String): Flow<List<ContactEntity>> {
        return repository.searchContacts(query)
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
        return repository.createOrUpdateContact(
            phoneNumber = phoneNumber,
            displayName = displayName,
            firstName = firstName,
            lastName = lastName,
            email = email,
            company = company
        )
    }

    /**
     * Verifica si un número está bloqueado
     */
    suspend fun isPhoneNumberBlocked(phoneNumber: String): Boolean {
        return repository.isPhoneNumberBlocked(phoneNumber)
    }

    // === ESTADÍSTICAS ===

    /**
     * Obtiene estadísticas generales
     */
    suspend fun getGeneralStatistics(): GeneralStatistics {
        return repository.getGeneralStatistics()
    }

    /**
     * Obtiene estadísticas de llamadas para un número específico
     */
    suspend fun getCallStatisticsForNumber(phoneNumber: String): com.eddyslarez.siplibrary.data.database.dao.CallStatistics? {
        return repository.getCallStatisticsForNumber(phoneNumber)
    }

    // === OPERACIONES DE MANTENIMIENTO ===

    /**
     * Limpia datos antiguos
     */
    fun cleanupOldData(daysToKeep: Int = 30) {
        scope.launch {
            try {
                repository.cleanupOldData(daysToKeep)
                log.d(tag = TAG) { "Old data cleanup completed" }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error during cleanup: ${e.message}" }
            }
        }
    }

    /**
     * Mantiene solo los registros más recientes
     */
    fun keepOnlyRecentData(
        callLogsLimit: Int = 1000,
        stateHistoryLimit: Int = 5000
    ) {
        scope.launch {
            try {
                repository.keepOnlyRecentData(callLogsLimit, stateHistoryLimit)
                log.d(tag = TAG) { "Recent data maintenance completed" }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error during data maintenance: ${e.message}" }
            }
        }
    }

    /**
     * Optimiza la base de datos
     */
    fun optimizeDatabase() {
        scope.launch {
            try {
                database.query("VACUUM", null)
                log.d(tag = TAG) { "Database optimization completed" }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error during database optimization: ${e.message}" }
            }
        }
    }

    init {
        log.d(tag = TAG) { "DatabaseManager instance created" }
    }
    // === MÉTODOS DE UTILIDAD ===
    private fun ensureInitialized() {
        if (!isInitialized) {
            scope.launch {
                try {
                    // Verificar integridad de la base de datos
                    val stats = getGeneralStatistics()
                    log.d(tag = TAG) { "Database initialized with ${stats.totalAccounts} accounts and ${stats.totalCalls} calls" }
                    isInitialized = true
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error initializing database: ${e.message}" }
                    // Intentar recrear la base de datos si hay problemas
                    recoverDatabase()
                }
            }
        }
    }

    private suspend fun recoverDatabase() {
        try {
            log.d(tag = TAG) { "Attempting database recovery" }

            val tablesExist = try {
                database.withTransaction {
                    database.sipAccountDao().getAccountCount()
                    database.callLogDao().getCallLogCount()
                    database.callStateDao().getCallHistoryCount()
                    true
                }
            } catch (e: Exception) {
                false
            }

            if (!tablesExist) {
                log.w(tag = TAG) { "Database tables missing, recreating..." }
            }

            isInitialized = true
            log.d(tag = TAG) { "Database recovery completed" }

        } catch (e: Exception) {
            log.e(tag = TAG) { "Database recovery failed: ${e.message}" }
        }
    }

    /**
     * Obtiene información de diagnóstico de la base de datos
     */
    suspend fun getDatabaseDiagnosticInfo(): String {
        return try {
            val stats = getGeneralStatistics()
            buildString {
                appendLine("=== DATABASE DIAGNOSTIC INFO ===")
                appendLine("Total Accounts: ${stats.totalAccounts}")
                appendLine("Registered Accounts: ${stats.registeredAccounts}")
                appendLine("Total Calls: ${stats.totalCalls}")
                appendLine("Missed Calls: ${stats.missedCalls}")
                appendLine("Total Contacts: ${stats.totalContacts}")
                appendLine("Active Calls: ${stats.activeCalls}")
                appendLine("Database Path: ${database.openHelper.readableDatabase.path}")
                appendLine("Database Version: ${database.openHelper.readableDatabase.version}")
            }
        } catch (e: Exception) {
            "Error getting diagnostic info: ${e.message}"
        }
    }

    /**
     * Cierra la base de datos (útil para testing)
     */
    fun closeDatabase() {
        try {
            log.d(tag = TAG) { "Closing database safely" }

            // Cancelar operaciones pendientes
            scope.cancel()

            // Forzar commit de transacciones pendientes
            database.runInTransaction {
                // Operación vacía para forzar el flush
            }

            // Cerrar la base de datos
            SipDatabase.closeDatabase()

            synchronized(LOCK) {
                INSTANCE = null
                isInitialized = false
            }

            log.d(tag = TAG) { "Database closed successfully" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error closing database: ${e.message}" }
        }
    }
}