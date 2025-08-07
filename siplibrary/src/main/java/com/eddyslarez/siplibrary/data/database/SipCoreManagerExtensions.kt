package com.eddyslarez.siplibrary.data.database

import android.app.Application
import com.eddyslarez.siplibrary.core.SipCoreManager
import com.eddyslarez.siplibrary.data.database.entities.CallDataEntity
import com.eddyslarez.siplibrary.data.database.entities.SipAccountEntity
import com.eddyslarez.siplibrary.data.models.AccountInfo
import com.eddyslarez.siplibrary.data.models.CallData
import com.eddyslarez.siplibrary.data.models.CallState
import com.eddyslarez.siplibrary.data.models.RegistrationState
import com.eddyslarez.siplibrary.utils.generateId

/**
 * Extensiones para SipCoreManager para facilitar la integración con BD
 *
 * @author Eddys Larez
 */

/**
 * Configura la integración automática de base de datos
 */
fun SipCoreManager.setupDatabaseIntegration(application: Application): DatabaseAutoIntegration {
    val integration = DatabaseAutoIntegration.getInstance(application, this)
    integration.initialize()
    return integration
}

/**
 * Extensión para obtener información de cuenta en formato de entidad
 */
fun AccountInfo.toSipAccountEntity(accountId: String = generateId()): SipAccountEntity {
    return SipAccountEntity(
        id = accountId,
        username = this.username,
        password = this.password,
        domain = this.domain,
        displayName = this.username,
        pushToken = this.token,
        pushProvider = this.provider,
        registrationState = if (this.isRegistered) RegistrationState.OK else RegistrationState.NONE,
        userAgent = this.userAgent
    )
}

/**
 * Extensión para convertir CallData a CallDataEntity
 */
fun CallData.toCallDataEntity(accountId: String): CallDataEntity {
    return CallDataEntity(
        callId = this.callId,
        accountId = accountId,
        fromNumber = this.from,
        toNumber = this.to,
        direction = this.direction,
        currentState = CallState.IDLE, // Se actualizará por el state manager
        startTime = this.startTime,
        fromTag = this.fromTag,
        toTag = this.toTag,
        inviteFromTag = this.inviteFromTag,
        inviteToTag = this.inviteToTag,
        remoteContactUri = this.remoteContactUri,
        remoteDisplayName = this.remoteDisplayName,
        localSdp = this.localSdp,
        remoteSdp = this.remoteSdp,
        viaHeader = this.via,
        inviteViaBranch = this.inviteViaBranch,
        lastCSeqValue = this.lastCSeqValue,
        originalInviteMessage = this.originalInviteMessage,
        originalCallInviteMessage = this.originalCallInviteMessage,
        md5Hash = this.md5Hash,
        sipName = this.sipName,
        isOnHold = this.isOnHold ?: false
    )
}

/**
 * Obtiene la información de cuenta actual como entidad de BD
 */
suspend fun SipCoreManager.getCurrentAccountEntity(databaseManager: DatabaseManager): SipAccountEntity? {
    val currentAccount = this.currentAccountInfo ?: return null
    return databaseManager.getSipAccountByCredentials(currentAccount.username, currentAccount.domain)
}
