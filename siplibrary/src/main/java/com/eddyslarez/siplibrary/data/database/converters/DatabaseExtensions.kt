package com.eddyslarez.siplibrary.data.database.converters


import com.eddyslarez.siplibrary.data.database.entities.CallLogEntity
import com.eddyslarez.siplibrary.data.database.repository.CallLogWithContact
import com.eddyslarez.siplibrary.data.models.CallDirections
import com.eddyslarez.siplibrary.data.models.CallLog
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Convierte CallLogEntity a CallLog (modelo de la librería)
 */
fun CallLogEntity.toCallLog(): CallLog {
    return CallLog(
        id = this.id,
        direction = this.direction,
        to = this.phoneNumber.takeIf { this.direction == CallDirections.OUTGOING } ?: "",
        formattedTo = formatPhoneNumber(this.phoneNumber.takeIf { this.direction == CallDirections.OUTGOING } ?: ""),
        from = this.phoneNumber.takeIf { this.direction == CallDirections.INCOMING } ?: "",
        formattedFrom = formatPhoneNumber(this.phoneNumber.takeIf { this.direction == CallDirections.INCOMING } ?: ""),
        contact = this.displayName,
        formattedStartDate = formatStartDate(this.startTime),
        duration = this.duration,
        callType = this.callType,
        localAddress = this.localAddress ?: ""
    )
}

/**
 * Convierte CallLogWithContact a CallLog
 */
fun CallLogWithContact.toCallLog(): CallLog {
    val phoneNumber = this.callLog.phoneNumber
    val contactName = this.contact?.displayName ?: this.callLog.displayName

    return CallLog(
        id = this.callLog.id,
        direction = this.callLog.direction,
        to = phoneNumber.takeIf { this.callLog.direction == CallDirections.OUTGOING } ?: "",
        formattedTo = formatPhoneNumber(phoneNumber.takeIf { this.callLog.direction == CallDirections.OUTGOING } ?: ""),
        from = phoneNumber.takeIf { this.callLog.direction == CallDirections.INCOMING } ?: "",
        formattedFrom = formatPhoneNumber(phoneNumber.takeIf { this.callLog.direction == CallDirections.INCOMING } ?: ""),
        contact = contactName,
        formattedStartDate = formatStartDate(this.callLog.startTime),
        duration = this.callLog.duration,
        callType = this.callLog.callType,
        localAddress = this.callLog.localAddress ?: ""
    )
}

/**
 * Convierte lista de CallLogWithContact a lista de CallLog
 */
@JvmName("toCallLogsFromCallLogWithContact")
fun List<CallLogWithContact>.toCallLogs(): List<CallLog> {
    return this.map { it.toCallLog() }
}

/**
 * Convierte lista de CallLogEntity a lista de CallLog
 */
@JvmName("toCallLogsFromCallLogEntity")
fun List<CallLogEntity>.toCallLogs(): List<CallLog> {
    return this.map { it.toCallLog() }
}

private fun formatStartDate(timestamp: Long): String {
    val instant = Instant.fromEpochMilliseconds(timestamp)
    val localDateTime = instant.toLocalDateTime(TimeZone.currentSystemDefault())

    return localDateTime.dayOfMonth.toString().padStart(2, '0') +
            ".${localDateTime.monthNumber.toString().padStart(2, '0')}" +
            ".${localDateTime.year} " +
            localDateTime.hour.toString().padStart(2, '0') +
            ":${localDateTime.minute.toString().padStart(2, '0')}"
}

private fun formatPhoneNumber(phoneNumber: String): String {
    return phoneNumber.trim()
}
