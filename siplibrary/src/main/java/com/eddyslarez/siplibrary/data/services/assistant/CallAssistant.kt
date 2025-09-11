package com.eddyslarez.siplibrary.data.services.assistant

import android.content.Context
import android.provider.ContactsContract
import android.database.Cursor
import android.content.SharedPreferences
import com.eddyslarez.siplibrary.EddysSipLibrary
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class CallAssistant(
    private val context: Context,
    private val eddysSipLibrary: EddysSipLibrary
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("call_assistant", Context.MODE_PRIVATE)

    // Estados del asistente
    private val _isEnabled = MutableStateFlow(prefs.getBoolean("assistant_enabled", false))
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _redirectNumber = MutableStateFlow(prefs.getString("redirect_number", "") ?: "")
    val redirectNumber: StateFlow<String> = _redirectNumber.asStateFlow()

    // Cache de contactos para mejor rendimiento
    private val _deviceContacts = MutableStateFlow<Set<String>>(emptySet())
    val deviceContacts: StateFlow<Set<String>> = _deviceContacts.asStateFlow()

    // Listener para llamadas entrantes
    private var incomingCallListener: EddysSipLibrary.IncomingCallListener? = null

    companion object {
        private const val TAG = "CallAssistant"
    }

    init {
        setupIncomingCallListener()
        loadDeviceContacts()
    }

    /**
     * Activa o desactiva el asistente
     */
    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        prefs.edit().putBoolean("assistant_enabled", enabled).apply()

        if (enabled) {
            // Recargar contactos cuando se activa
            loadDeviceContacts()
        }

        log.d(tag = TAG) { "Call Assistant ${if (enabled) "enabled" else "disabled"}" }
    }

    /**
     * Configura el número de redirección
     */
    fun setRedirectNumber(number: String) {
        _redirectNumber.value = number
        prefs.edit().putString("redirect_number", number).apply()
        log.d(tag = TAG) { "Redirect number set to: $number" }
    }

    /**
     * Configura el listener para llamadas entrantes
     */
    private fun setupIncomingCallListener() {
        incomingCallListener = object : EddysSipLibrary.IncomingCallListener {
            override fun onIncomingCall(callInfo: EddysSipLibrary.IncomingCallInfo) {
                handleIncomingCall(callInfo)
            }

            override fun onIncomingCallCancelled(callInfo: EddysSipLibrary.IncomingCallInfo) {
                log.d(tag = TAG) { "Incoming call cancelled: ${callInfo.callerNumber}" }
            }

            override fun onIncomingCallTimeout(callInfo: EddysSipLibrary.IncomingCallInfo) {
                log.d(tag = TAG) { "Incoming call timeout: ${callInfo.callerNumber}" }
            }
        }

        eddysSipLibrary.setIncomingCallListener(incomingCallListener)
    }

    /**
     * Maneja las llamadas entrantes según la configuración del asistente
     */
    private fun handleIncomingCall(callInfo: EddysSipLibrary.IncomingCallInfo) {
        if (!_isEnabled.value) {
            log.d(tag = TAG) { "Assistant disabled, call passes normally: ${callInfo.callerNumber}" }
            return // Dejar que la llamada pase normalmente
        }

        val callerNumber = normalizePhoneNumber(callInfo.callerNumber)
        val isInContacts = isNumberInContacts(callerNumber)

        log.d(tag = TAG) {
            "Processing incoming call from $callerNumber, in contacts: $isInContacts"
        }

        if (isInContacts) {
            // Número está en contactos, dejar pasar normalmente
            log.d(tag = TAG) { "Number in contacts, allowing call to pass: $callerNumber" }
        } else {
            // Número NO está en contactos, redirigir
            val redirectTo = _redirectNumber.value
            if (redirectTo.isNotEmpty()) {
                log.d(tag = TAG) { "Number NOT in contacts, redirecting to: $redirectTo" }
                eddysSipLibrary.deflectIncomingCall(redirectTo, callInfo.callId)
            } else {
                log.w(tag = TAG) { "No redirect number configured, allowing call to pass" }
            }
        }
    }

    /**
     * Verifica si un número está en los contactos del dispositivo
     */
    private fun isNumberInContacts(phoneNumber: String): Boolean {
        val normalizedNumber = normalizePhoneNumber(phoneNumber)
        return _deviceContacts.value.any { contactNumber ->
            val normalizedContact = normalizePhoneNumber(contactNumber)
            numbersMatch(normalizedNumber, normalizedContact)
        }
    }

    /**
     * Normaliza un número de teléfono para comparación
     */
    private fun normalizePhoneNumber(phoneNumber: String): String {
        return phoneNumber.replace(Regex("[^\\d+]"), "").let { cleaned ->
            // Remover códigos de país comunes para comparación local
            when {
                cleaned.startsWith("+7") && cleaned.length > 10 -> cleaned.substring(2)
                cleaned.startsWith("7") && cleaned.length > 10 -> cleaned.substring(1)
                cleaned.startsWith("8") && cleaned.length > 10 -> cleaned.substring(1)
                else -> cleaned
            }
        }
    }

    /**
     * Compara dos números normalizados para ver si coinciden
     */
    private fun numbersMatch(number1: String, number2: String): Boolean {
        // Comparación exacta
        if (number1 == number2) return true

        // Comparación de los últimos 10 dígitos (número local)
        val suffix1 = if (number1.length >= 10) number1.takeLast(10) else number1
        val suffix2 = if (number2.length >= 10) number2.takeLast(10) else number2

        return suffix1 == suffix2
    }

    /**
     * Carga los contactos del dispositivo
     */
    fun loadDeviceContacts() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val contacts = getDevicePhoneNumbers()
                _deviceContacts.value = contacts
                log.d(tag = TAG) { "Loaded ${contacts.size} contact numbers from device" }
            } catch (e: Exception) {
                log.e(tag = TAG) { "Error loading device contacts: ${e.message}" }
            }
        }
    }

    /**
     * Obtiene todos los números de teléfono de los contactos del dispositivo
     */
    private suspend fun getDevicePhoneNumbers(): Set<String> = withContext(Dispatchers.IO) {
        val phoneNumbers = mutableSetOf<String>()

        try {
            val cursor: Cursor? = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                null,
                null,
                null
            )

            cursor?.use {
                val phoneColumnIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (it.moveToNext()) {
                    val phoneNumber = it.getString(phoneColumnIndex)
                    if (!phoneNumber.isNullOrBlank()) {
                        phoneNumbers.add(phoneNumber.trim())
                    }
                }
            }

        } catch (e: SecurityException) {
            log.e(tag = TAG) { "Permission denied to read contacts: ${e.message}" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error reading contacts: ${e.message}" }
        }

        phoneNumbers
    }

    /**
     * Obtiene los contactos completos del dispositivo (con nombres)
     */
    suspend fun getDeviceContactsWithNames(): List<DeviceContact> = withContext(Dispatchers.IO) {
        val contacts = mutableListOf<DeviceContact>()

        try {
            val cursor: Cursor? = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE
                ),
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )

            cursor?.use {
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)

                while (it.moveToNext()) {
                    val name = it.getString(nameIndex) ?: ""
                    val number = it.getString(numberIndex) ?: ""
                    val type = it.getInt(typeIndex)

                    if (name.isNotBlank() && number.isNotBlank()) {
                        contacts.add(
                            DeviceContact(
                                name = name,
                                phoneNumber = number,
                                phoneType = getPhoneTypeLabel(type)
                            )
                        )
                    }
                }
            }

        } catch (e: SecurityException) {
            log.e(tag = TAG) { "Permission denied to read contacts: ${e.message}" }
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error reading contacts with names: ${e.message}" }
        }

        contacts
    }

    /**
     * Obtiene la etiqueta del tipo de teléfono
     */
    private fun getPhoneTypeLabel(type: Int): String {
        return when (type) {
            ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Casa"
            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Móvil"
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Trabajo"
            ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "Principal"
            ContactsContract.CommonDataKinds.Phone.TYPE_OTHER -> "Otro"
            else -> "Desconocido"
        }
    }

    /**
     * Obtiene información de diagnóstico del asistente
     */
    fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== CALL ASSISTANT DIAGNOSTIC ===")
            appendLine("Enabled: ${_isEnabled.value}")
            appendLine("Redirect Number: '${_redirectNumber.value}'")
            appendLine("Device Contacts Loaded: ${_deviceContacts.value.size}")
            appendLine("Listener Configured: ${incomingCallListener != null}")

            appendLine("\n--- Sample Contacts ---")
            _deviceContacts.value.take(5).forEach { contact ->
                appendLine("  - $contact")
            }

            if (_deviceContacts.value.size > 5) {
                appendLine("  ... and ${_deviceContacts.value.size - 5} more")
            }
        }
    }

    /**
     * Testa si un número específico estaría en contactos
     */
    fun testNumberInContacts(phoneNumber: String): Boolean {
        val isInContacts = isNumberInContacts(phoneNumber)
        log.d(tag = TAG) {
            "Test number '$phoneNumber' in contacts: $isInContacts (normalized: '${normalizePhoneNumber(phoneNumber)}')"
        }
        return isInContacts
    }

    fun dispose() {
        eddysSipLibrary.setIncomingCallListener(null)
        incomingCallListener = null
    }
}

/**
 * Data class para representar un contacto del dispositivo
 */
data class DeviceContact(
    val name: String,
    val phoneNumber: String,
    val phoneType: String
)
