package com.eddyslarez.siplibrary.utils

/**
 * Simulador de notificaciones push para debugging
 *
 * @author Eddys Larez
 */
class PushNotificationSimulator {
    private val TAG = "PushSimulator"

    data class PushData(
        val type: String,
        val sipName: String,
        val incomingPhoneNumber: String,
        val callId: String,
        val timestamp: Long = System.currentTimeMillis(),
    )

    /**
     * Simula el envío de una notificación push de llamada entrante
     */
    fun simulateIncomingCallPush(
        sipName: String,
        phoneNumber: String,
        callId: String? = null,
    ): Map<String, Any> {
        val finalCallId = callId ?: generateId()

        val pushData = PushData(
            type = "call",
            sipName = sipName,
            incomingPhoneNumber = phoneNumber,
            callId = finalCallId,
        )

        log.d(tag = TAG) {
            "=== SIMULATING PUSH NOTIFICATION ===" +
                    "\nType: ${pushData.type}" +
                    "\nSIP Name: ${pushData.sipName}" +
                    "\nPhone Number: ${pushData.incomingPhoneNumber}" +
                    "\nCall ID: ${pushData.callId}" +
                    "\nTimestamp: ${pushData.timestamp}"
        }

        return mapOf(
            "type" to pushData.type,
            "sipName" to pushData.sipName,
            "incomingPhoneNumber" to pushData.incomingPhoneNumber,
            "callId" to pushData.callId,
            "timestamp" to pushData.timestamp
        )
    }

    /**
     * Simula otros tipos de notificaciones push
     */
    fun simulateGenericPush(
        type: String = "generic",
        additionalData: Map<String, Any> = emptyMap()
    ): Map<String, Any> {
        val baseData = mapOf(
            "type" to type,
            "timestamp" to System.currentTimeMillis()
        )

        return baseData + additionalData
    }

    private fun generateId(): String {
        return "sim_${System.currentTimeMillis()}_${(1000..9999).random()}"
    }
}
