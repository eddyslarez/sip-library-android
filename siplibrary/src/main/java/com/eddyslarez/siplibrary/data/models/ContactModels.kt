package com.eddyslarez.siplibrary.data.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Modelos para gestión de contactos
 * 
 * @author Eddys Larez
 */

@Parcelize
enum class ContactSources : Parcelable {
    DEVICE,     // Contactos del dispositivo
    MANUAL,     // Contactos añadidos manualmente
    IMPORTED,   // Contactos importados
    SIP         // Contactos SIP específicos
}

@Parcelize
data class Contact(
    val lookupKey: String,
    val displayName: String = "",
    val phones: LinkedHashSet<String> = linkedSetOf(),
    val sipAddress: String? = null,
    val thumbnailPath: String? = null,
    val defaultPhoneNumber: String? = null,
    val source: ContactSources,
    val email: String? = null,
    val company: String? = null,
    val notes: String? = null
) : Parcelable {
    
    fun getFormattedPhones(): List<String> {
        return phones.map { formatPhoneNumber(it) }
    }
    
    fun hasPhoneNumber(phoneNumber: String): Boolean {
        val formattedInput = formatPhoneNumber(phoneNumber)
        return phones.any { formatPhoneNumber(it) == formattedInput }
    }
    
    fun getPrimaryPhone(): String? {
        return defaultPhoneNumber ?: phones.firstOrNull()
    }
    
    private fun formatPhoneNumber(phone: String): String {
        // Remover espacios, guiones y paréntesis
        return phone.replace(Regex("[\\s\\-\\(\\)\\+]"), "")
    }
}

/**
 * Resultado de búsqueda de contactos
 */
data class ContactSearchResult(
    val contacts: List<Contact>,
    val totalCount: Int,
    val searchQuery: String,
    val searchTime: Long = System.currentTimeMillis()
)

/**
 * Configuración para extracción de contactos
 */
data class ContactExtractionConfig(
    val includePhoneNumbers: Boolean = true,
    val includeEmails: Boolean = false,
    val includeSipAddresses: Boolean = true,
    val includePhotos: Boolean = false,
    val maxContactsToExtract: Int = 1000,
    val sortByDisplayName: Boolean = true
)