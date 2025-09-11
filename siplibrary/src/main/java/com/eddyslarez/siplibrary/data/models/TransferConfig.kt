package com.eddyslarez.siplibrary.data.models

import com.eddyslarez.siplibrary.data.models.TransferConfig

data class TransferConfig(
    val defaultTransferNumber: String = "",
    val enableBlindTransfer: Boolean = true,
    val enableAttendedTransfer: Boolean = false,
    val transferTimeout: Long = 30000L // 30 segundos
)
/**
 * Configuración para call deflection
 */
data class CallDeflectionConfig(
    var defaultDeflectionNumber: String = "",
    var autoDeflectEnabled: Boolean = false,
    var deflectionRules: Map<String, String> = emptyMap() // caller -> redirect_to
)
