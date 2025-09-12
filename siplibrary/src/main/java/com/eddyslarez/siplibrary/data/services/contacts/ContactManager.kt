package com.eddyslarez.siplibrary.data.services.contacts

import android.app.Application
import androidx.lifecycle.MediatorLiveData
import com.eddyslarez.siplibrary.data.models.Contact
import com.eddyslarez.siplibrary.data.models.ContactExtractionConfig
import com.eddyslarez.siplibrary.data.models.ContactSearchResult
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Gestor principal de contactos que combina extracción automática y manual
 * 
 * @author Eddys Larez
 */
class ContactManager(private val application: Application) {
    
    private val TAG = "ContactManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Extractor de contactos del dispositivo
    private val deviceExtractor = DeviceContactExtractor(application)
    
    // LiveData para compatibilidad (como solicitado)
    private val _list = MediatorLiveData<List<Contact>>()
    private val _searchedList = MediatorLiveData<List<Contact>>()
    
    val contactsList: MediatorLiveData<List<Contact>> = _list
    val searchedList: MediatorLiveData<List<Contact>> = _searchedList
    
    // StateFlow para observación moderna
    private val _contactsFlow = MutableStateFlow<List<Contact>>(emptyList())
    val contactsFlow: StateFlow<List<Contact>> = _contactsFlow.asStateFlow()
    
    private val _isUsingManualContacts = MutableStateFlow(false)
    val isUsingManualContacts: StateFlow<Boolean> = _isUsingManualContacts.asStateFlow()
    
    // Contactos manuales proporcionados por el usuario
    private var manualContacts: List<Contact> = emptyList()
    
    init {
        // Conectar LiveData del extractor con el nuestro
        _list.addSource(deviceExtractor.contactsList) { contacts ->
            if (!_isUsingManualContacts.value) {
                _list.value = contacts
                _contactsFlow.value = contacts ?: emptyList()
            }
        }
        
        _searchedList.addSource(deviceExtractor.searchedList) { contacts ->
            if (!_isUsingManualContacts.value) {
                _searchedList.value = contacts
            }
        }
    }
    
    /**
     * Establece contactos manualmente (como solicitado en el requerimiento)
     * Recibe MediatorLiveData<List<Contact>> del usuario de la librería
     */
    fun setManualContacts(contacts: List<Contact>) {
        log.d(tag = TAG) { "Setting manual contacts: ${contacts.size}" }
        
        manualContacts = contacts
        _isUsingManualContacts.value = true
        
        // Actualizar LiveData y StateFlow
        _list.value = contacts
        _searchedList.value = contacts
        _contactsFlow.value = contacts
        
        log.d(tag = TAG) { "Manual contacts set successfully" }
    }
    
    /**
     * Cambia a extracción automática de contactos del dispositivo
     */
    fun useDeviceContacts(config: ContactExtractionConfig = ContactExtractionConfig()) {
        log.d(tag = TAG) { "Switching to device contacts extraction" }
        
        _isUsingManualContacts.value = false
        
        // Cargar contactos del dispositivo
        scope.launch {
            deviceExtractor.extractDeviceContacts(config)
        }
    }
    
    /**
     * Busca contactos (funciona tanto para manuales como automáticos)
     */
    suspend fun searchContacts(query: String): ContactSearchResult {
        return if (_isUsingManualContacts.value) {
            searchManualContacts(query)
        } else {
            deviceExtractor.searchContacts(query)
        }
    }
    
    /**
     * Busca en contactos manuales
     */
    private suspend fun searchManualContacts(query: String): ContactSearchResult {
        val startTime = System.currentTimeMillis()
        
        val filteredContacts = if (query.isBlank()) {
            manualContacts
        } else {
            manualContacts.filter { contact ->
                contact.displayName.contains(query, ignoreCase = true) ||
                contact.phones.any { it.contains(query) } ||
                contact.email?.contains(query, ignoreCase = true) == true ||
                contact.company?.contains(query, ignoreCase = true) == true
            }
        }
        
        // Actualizar searched list
        _searchedList.postValue(filteredContacts)
        
        val searchTime = System.currentTimeMillis() - startTime
        
        return ContactSearchResult(
            contacts = filteredContacts,
            totalCount = filteredContacts.size,
            searchQuery = query,
            searchTime = searchTime
        )
    }
    
    /**
     * Verifica si un número está en los contactos
     */
    suspend fun isPhoneNumberInContacts(phoneNumber: String): Boolean {
        return if (_isUsingManualContacts.value) {
            isPhoneNumberInManualContacts(phoneNumber)
        } else {
            deviceExtractor.isPhoneNumberInContacts(phoneNumber)
        }
    }
    
    /**
     * Verifica en contactos manuales
     */
    private fun isPhoneNumberInManualContacts(phoneNumber: String): Boolean {
        val formattedNumber = formatPhoneNumber(phoneNumber)
        return manualContacts.any { contact ->
            contact.phones.any { formatPhoneNumber(it) == formattedNumber }
        }
    }
    
    /**
     * Busca contacto por número de teléfono
     */
    suspend fun findContactByPhoneNumber(phoneNumber: String): Contact? {
        return if (_isUsingManualContacts.value) {
            findContactInManualContacts(phoneNumber)
        } else {
            deviceExtractor.findContactByPhoneNumber(phoneNumber)
        }
    }
    
    /**
     * Busca en contactos manuales por número
     */
    private fun findContactInManualContacts(phoneNumber: String): Contact? {
        val formattedNumber = formatPhoneNumber(phoneNumber)
        return manualContacts.find { contact ->
            contact.phones.any { formatPhoneNumber(it) == formattedNumber }
        }
    }
    
    /**
     * Obtiene todos los contactos actuales
     */
    suspend fun getAllContacts(): List<Contact> {
        return if (_isUsingManualContacts.value) {
            manualContacts
        } else {
            deviceExtractor.getContacts()
        }
    }
    
    /**
     * Fuerza actualización de contactos
     */
    fun forceRefresh() {
        if (_isUsingManualContacts.value) {
            log.d(tag = TAG) { "Using manual contacts, no refresh needed" }
        } else {
            deviceExtractor.forceRefresh()
        }
    }
    
    /**
     * Busca contactos de forma asíncrona
     */
    fun searchContactsAsync(query: String) {
        scope.launch {
            searchContacts(query)
        }
    }
    
    /**
     * Formatea número de teléfono para comparación
     */
    private fun formatPhoneNumber(phoneNumber: String): String {
        return phoneNumber.replace(Regex("[\\s\\-\\(\\)\\+]"), "")
    }
    
    /**
     * Información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== CONTACT MANAGER DIAGNOSTIC ===")
            appendLine("Using Manual Contacts: ${_isUsingManualContacts.value}")
            appendLine("Manual Contacts Count: ${manualContacts.size}")
            appendLine("Current Contacts Count: ${_contactsFlow.value.size}")
            appendLine("LiveData Contacts: ${_list.value?.size ?: 0}")
            appendLine("LiveData Searched: ${_searchedList.value?.size ?: 0}")
            
            if (!_isUsingManualContacts.value) {
                appendLine("\n${deviceExtractor.getDiagnosticInfo()}")
            }
        }
    }
    
    /**
     * Limpia recursos
     */
    fun dispose() {
        deviceExtractor.dispose()
        manualContacts = emptyList()
        _contactsFlow.value = emptyList()
        _list.postValue(emptyList())
        _searchedList.postValue(emptyList())
        log.d(tag = TAG) { "ContactManager disposed" }
    }
}