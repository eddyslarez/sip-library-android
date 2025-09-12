package com.eddyslarez.siplibrary.data.services.contacts

import android.app.Application
import android.content.ContentResolver
import android.database.Cursor
import android.provider.ContactsContract
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import com.eddyslarez.siplibrary.data.models.Contact
import com.eddyslarez.siplibrary.data.models.ContactSources
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
import kotlinx.coroutines.withContext

/**
 * Extractor de contactos del dispositivo Android
 * 
 * @author Eddys Larez
 */
class DeviceContactExtractor(private val application: Application) {
    
    private val TAG = "DeviceContactExtractor"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val contentResolver: ContentResolver = application.contentResolver
    
    // LiveData para compatibilidad con el formato solicitado
    private val _contactsList = MediatorLiveData<List<Contact>>()
    private val _searchedList = MediatorLiveData<List<Contact>>()
    
    val contactsList: MediatorLiveData<List<Contact>> = _contactsList
    val searchedList: MediatorLiveData<List<Contact>> = _searchedList
    
    // StateFlow para observación moderna
    private val _contactsFlow = MutableStateFlow<List<Contact>>(emptyList())
    val contactsFlow: StateFlow<List<Contact>> = _contactsFlow.asStateFlow()
    
    private val _isLoadingFlow = MutableStateFlow(false)
    val isLoadingFlow: StateFlow<Boolean> = _isLoadingFlow.asStateFlow()
    
    // Cache interno
    private var cachedContacts: List<Contact> = emptyList()
    private var lastExtractionTime = 0L
    private val cacheValidityMs = 5 * 60 * 1000L // 5 minutos
    
    /**
     * Extrae todos los contactos del dispositivo
     */
    suspend fun extractDeviceContacts(
        config: ContactExtractionConfig = ContactExtractionConfig()
    ): List<Contact> = withContext(Dispatchers.IO) {
        
        // Verificar cache
        if (isCacheValid() && cachedContacts.isNotEmpty()) {
            log.d(tag = TAG) { "Returning cached contacts: ${cachedContacts.size}" }
            return@withContext cachedContacts
        }
        
        _isLoadingFlow.value = true
        
        try {
            log.d(tag = TAG) { "Starting device contacts extraction" }
            
            val contacts = mutableListOf<Contact>()
            val contactsMap = mutableMapOf<String, Contact>()
            
            // Extraer contactos básicos
            extractBasicContacts(contactsMap, config)
            
            // Extraer números de teléfono
            if (config.includePhoneNumbers) {
                extractPhoneNumbers(contactsMap)
            }
            
            // Extraer emails si está habilitado
            if (config.includeEmails) {
                extractEmails(contactsMap)
            }
            
            // Extraer fotos si está habilitado
            if (config.includePhotos) {
                extractPhotos(contactsMap)
            }
            
            // Convertir a lista y ordenar
            contacts.addAll(contactsMap.values)
            
            if (config.sortByDisplayName) {
                contacts.sortBy { it.displayName.lowercase() }
            }
            
            // Limitar cantidad si está configurado
            val finalContacts = if (config.maxContactsToExtract > 0) {
                contacts.take(config.maxContactsToExtract)
            } else {
                contacts
            }
            
            // Actualizar cache
            cachedContacts = finalContacts
            lastExtractionTime = System.currentTimeMillis()
            
            // Actualizar flows y LiveData
            _contactsFlow.value = finalContacts
            _contactsList.postValue(finalContacts)
            
            log.d(tag = TAG) { "Contacts extraction completed: ${finalContacts.size} contacts" }
            
            return@withContext finalContacts
            
        } catch (e: SecurityException) {
            log.e(tag = TAG) { "Permission denied for contacts access: ${e.message}" }
            return@withContext emptyList()
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error extracting contacts: ${e.message}" }
            return@withContext emptyList()
        } finally {
            _isLoadingFlow.value = false
        }
    }
    
    /**
     * Busca contactos por query
     */
    suspend fun searchContacts(
        query: String,
        config: ContactExtractionConfig = ContactExtractionConfig()
    ): ContactSearchResult = withContext(Dispatchers.IO) {
        
        val startTime = System.currentTimeMillis()
        
        try {
            // Si no hay cache válido, extraer primero
            if (!isCacheValid() || cachedContacts.isEmpty()) {
                extractDeviceContacts(config)
            }
            
            val filteredContacts = if (query.isBlank()) {
                cachedContacts
            } else {
                cachedContacts.filter { contact ->
                    contact.displayName.contains(query, ignoreCase = true) ||
                    contact.phones.any { it.contains(query) } ||
                    contact.email?.contains(query, ignoreCase = true) == true ||
                    contact.company?.contains(query, ignoreCase = true) == true
                }
            }
            
            // Actualizar searched list
            _searchedList.postValue(filteredContacts)
            
            val searchTime = System.currentTimeMillis() - startTime
            
            log.d(tag = TAG) { "Search completed: ${filteredContacts.size} results for '$query' in ${searchTime}ms" }
            
            return@withContext ContactSearchResult(
                contacts = filteredContacts,
                totalCount = filteredContacts.size,
                searchQuery = query,
                searchTime = searchTime
            )
            
        } catch (e: Exception) {
            log.e(tag = TAG) { "Error searching contacts: ${e.message}" }
            return@withContext ContactSearchResult(
                contacts = emptyList(),
                totalCount = 0,
                searchQuery = query
            )
        }
    }
    
    /**
     * Verifica si un número de teléfono existe en los contactos
     */
    suspend fun isPhoneNumberInContacts(phoneNumber: String): Boolean = withContext(Dispatchers.IO) {
        
        // Asegurar que tenemos contactos cargados
        if (!isCacheValid() || cachedContacts.isEmpty()) {
            extractDeviceContacts()
        }
        
        val formattedNumber = formatPhoneNumber(phoneNumber)
        
        return@withContext cachedContacts.any { contact ->
            contact.phones.any { formatPhoneNumber(it) == formattedNumber }
        }
    }
    
    /**
     * Busca contacto por número de teléfono
     */
    suspend fun findContactByPhoneNumber(phoneNumber: String): Contact? = withContext(Dispatchers.IO) {
        
        if (!isCacheValid() || cachedContacts.isEmpty()) {
            extractDeviceContacts()
        }
        
        val formattedNumber = formatPhoneNumber(phoneNumber)
        
        return@withContext cachedContacts.find { contact ->
            contact.phones.any { formatPhoneNumber(it) == formattedNumber }
        }
    }
    
    /**
     * Permite establecer contactos manualmente (para usuarios que no quieren extracción automática)
     */
    fun setManualContacts(contacts: List<Contact>) {
        log.d(tag = TAG) { "Setting manual contacts: ${contacts.size}" }
        
        cachedContacts = contacts
        lastExtractionTime = System.currentTimeMillis()
        
        _contactsFlow.value = contacts
        _contactsList.postValue(contacts)
        
        log.d(tag = TAG) { "Manual contacts set successfully" }
    }
    
    /**
     * Fuerza actualización de contactos
     */
    fun forceRefresh() {
        scope.launch {
            log.d(tag = TAG) { "Forcing contacts refresh" }
            lastExtractionTime = 0L // Invalidar cache
            extractDeviceContacts()
        }
    }
    
    /**
     * Extrae contactos básicos
     */
    private fun extractBasicContacts(
        contactsMap: MutableMap<String, Contact>,
        config: ContactExtractionConfig
    ) {
        val projection = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ContactsContract.Contacts.HAS_PHONE_NUMBER,
            ContactsContract.Contacts.PHOTO_THUMBNAIL_URI
        )
        
        val cursor: Cursor? = contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC"
        )
        
        cursor?.use { c ->
            val lookupKeyIndex = c.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY)
            val displayNameIndex = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            val hasPhoneIndex = c.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)
            val photoIndex = c.getColumnIndex(ContactsContract.Contacts.PHOTO_THUMBNAIL_URI)
            
            while (c.moveToNext()) {
                try {
                    val lookupKey = c.getString(lookupKeyIndex) ?: continue
                    val displayName = c.getString(displayNameIndex) ?: ""
                    val hasPhone = c.getInt(hasPhoneIndex) > 0
                    val photoUri = c.getString(photoIndex)
                    
                    // Solo incluir contactos con teléfono si está configurado
                    if (config.includePhoneNumbers && !hasPhone) {
                        continue
                    }
                    
                    val contact = Contact(
                        lookupKey = lookupKey,
                        displayName = displayName,
                        phones = linkedSetOf(),
                        thumbnailPath = photoUri,
                        source = ContactSources.DEVICE
                    )
                    
                    contactsMap[lookupKey] = contact
                    
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error processing contact: ${e.message}" }
                }
            }
        }
        
        log.d(tag = TAG) { "Basic contacts extracted: ${contactsMap.size}" }
    }
    
    /**
     * Extrae números de teléfono para los contactos
     */
    private fun extractPhoneNumbers(contactsMap: MutableMap<String, Contact>) {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        
        val cursor: Cursor? = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            null
        )
        
        cursor?.use { c ->
            val lookupKeyIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY)
            val numberIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val typeIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
            
            while (c.moveToNext()) {
                try {
                    val lookupKey = c.getString(lookupKeyIndex) ?: continue
                    val phoneNumber = c.getString(numberIndex) ?: continue
                    val phoneType = c.getInt(typeIndex)
                    
                    val contact = contactsMap[lookupKey]
                    if (contact != null) {
                        val updatedPhones = contact.phones.toMutableSet()
                        updatedPhones.add(phoneNumber)
                        
                        val defaultPhone = if (contact.defaultPhoneNumber == null && 
                            phoneType == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) {
                            phoneNumber
                        } else {
                            contact.defaultPhoneNumber
                        }
                        
                        contactsMap[lookupKey] = contact.copy(
                            phones = LinkedHashSet(updatedPhones),
                            defaultPhoneNumber = defaultPhone ?: contact.phones.firstOrNull()
                        )
                    }
                    
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error processing phone number: ${e.message}" }
                }
            }
        }
        
        log.d(tag = TAG) { "Phone numbers extracted for contacts" }
    }
    
    /**
     * Extrae emails para los contactos
     */
    private fun extractEmails(contactsMap: MutableMap<String, Contact>) {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Email.LOOKUP_KEY,
            ContactsContract.CommonDataKinds.Email.ADDRESS
        )
        
        val cursor: Cursor? = contentResolver.query(
            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            projection,
            null,
            null,
            null
        )
        
        cursor?.use { c ->
            val lookupKeyIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.LOOKUP_KEY)
            val emailIndex = c.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS)
            
            while (c.moveToNext()) {
                try {
                    val lookupKey = c.getString(lookupKeyIndex) ?: continue
                    val email = c.getString(emailIndex) ?: continue
                    
                    val contact = contactsMap[lookupKey]
                    if (contact != null) {
                        contactsMap[lookupKey] = contact.copy(email = email)
                    }
                    
                } catch (e: Exception) {
                    log.e(tag = TAG) { "Error processing email: ${e.message}" }
                }
            }
        }
        
        log.d(tag = TAG) { "Emails extracted for contacts" }
    }
    
    /**
     * Extrae fotos para los contactos
     */
    private fun extractPhotos(contactsMap: MutableMap<String, Contact>) {
        // Las fotos ya se extraen en extractBasicContacts via PHOTO_THUMBNAIL_URI
        log.d(tag = TAG) { "Photos already extracted in basic contacts" }
    }
    
    /**
     * Verifica si el cache es válido
     */
    private fun isCacheValid(): Boolean {
        return (System.currentTimeMillis() - lastExtractionTime) < cacheValidityMs
    }
    
    /**
     * Formatea número de teléfono para comparación
     */
    private fun formatPhoneNumber(phoneNumber: String): String {
        return phoneNumber.replace(Regex("[\\s\\-\\(\\)\\+]"), "")
    }
    
    /**
     * Obtiene contactos desde cache o extrae si es necesario
     */
    suspend fun getContacts(forceRefresh: Boolean = false): List<Contact> {
        return if (forceRefresh || !isCacheValid() || cachedContacts.isEmpty()) {
            extractDeviceContacts()
        } else {
            cachedContacts
        }
    }
    
    /**
     * Busca contactos de forma asíncrona
     */
    fun searchContactsAsync(query: String) {
        scope.launch {
            val result = searchContacts(query)
            _searchedList.postValue(result.contacts)
        }
    }
    
    /**
     * Carga contactos de forma asíncrona
     */
    fun loadContactsAsync(config: ContactExtractionConfig = ContactExtractionConfig()) {
        scope.launch {
            val contacts = extractDeviceContacts(config)
            _contactsList.postValue(contacts)
        }
    }
    
    /**
     * Información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== DEVICE CONTACT EXTRACTOR DIAGNOSTIC ===")
            appendLine("Cached Contacts: ${cachedContacts.size}")
            appendLine("Last Extraction: ${if (lastExtractionTime > 0) lastExtractionTime else "Never"}")
            appendLine("Cache Valid: ${isCacheValid()}")
            appendLine("Cache Validity: ${cacheValidityMs}ms")
            appendLine("Is Loading: ${_isLoadingFlow.value}")
            appendLine("LiveData Contacts: ${_contactsList.value?.size ?: 0}")
            appendLine("LiveData Searched: ${_searchedList.value?.size ?: 0}")
        }
    }
    
    /**
     * Limpia cache y recursos
     */
    fun dispose() {
        cachedContacts = emptyList()
        lastExtractionTime = 0L
        _contactsFlow.value = emptyList()
        _contactsList.postValue(emptyList())
        _searchedList.postValue(emptyList())
        log.d(tag = TAG) { "DeviceContactExtractor disposed" }
    }
}