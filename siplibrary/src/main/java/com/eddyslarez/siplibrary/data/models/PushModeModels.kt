package com.eddyslarez.siplibrary.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Modelos para gestión de modo push
 * 
 * @author Eddys Larez
 */

@Parcelize
enum class PushMode : Parcelable {
    FOREGROUND,     // Modo normal, aplicación en primer plano
    PUSH,           // Modo push, aplicación en segundo plano
    TRANSITIONING   // Estado transitorio durante cambios
}

@Parcelize
enum class PushModeStrategy : Parcelable {
    AUTOMATIC,      // Cambio automático basado en lifecycle de la app
    MANUAL          // Control manual por parte del desarrollador
}

@Parcelize
data class PushModeConfig(
    val strategy: PushModeStrategy = PushModeStrategy.AUTOMATIC,
    val autoTransitionDelay: Long = 5000L, // 5 segundos de delay para transición automática
    val forceReregisterOnIncomingCall: Boolean = true,
    val returnToPushAfterCallEnd: Boolean = true,
    val enablePushNotifications: Boolean = true
) : Parcelable

@Parcelize
data class PushModeState(
    val currentMode: PushMode,
    val previousMode: PushMode?,
    val timestamp: Long,
    val reason: String,
    val accountsInPushMode: Set<String> = emptySet(),
    val wasInPushBeforeCall: Boolean = false,
    val specificAccountInForeground: String? = null // Cuenta específica que está en foreground
) : Parcelable

/**
 * Razones para cambios de modo push
 */
object PushModeReasons {
    const val APP_BACKGROUNDED = "App moved to background"
    const val APP_FOREGROUNDED = "App moved to foreground"
    const val INCOMING_CALL_RECEIVED = "Incoming call received"
    const val CALL_ENDED = "Call ended"
    const val MANUAL_SWITCH = "Manual mode switch"
    const val REGISTRATION_REQUIRED = "Registration required"
    const val NETWORK_RECONNECTION = "Network reconnection"
    const val PUSH_NOTIFICATION_RECEIVED = "Push notification received"
}