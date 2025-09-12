# 🤖 Sistema de Asistente SIP - EddysSipLibrary

Esta documentación explica cómo usar el nuevo **Sistema de Asistente SIP** integrado en la librería, que permite filtrar y gestionar llamadas entrantes de forma automática por cuenta individual.

## 🚀 Características del Asistente

### ✅ **Funcionalidades Principales**
- 🏢 **Configuración individual por cuenta SIP**
- 📞 **Filtrado automático de llamadas entrantes**
- 👥 **Modo "Solo Contactos"** - Solo permite llamadas de números registrados
- 🚫 **Modo "Lista Negra"** - Bloquea números específicos
- 📱 **Rechazo silencioso** - Sin notificación ni sonido
- 🔄 **Desvío al asistente** - Redirige llamadas a número fijo
- 📊 **Historial completo** de acciones del asistente
- 🎯 **Gestión de contactos** automática y manual

### ✅ **Modos de Operación**
- **DISABLED**: Asistente desactivado (por defecto)
- **CONTACTS_ONLY**: Solo permite llamadas de contactos registrados
- **BLACKLIST_FILTER**: Filtra llamadas según lista negra

### ✅ **Acciones Disponibles**
- **REJECT_IMMEDIATELY**: Rechaza sin que suene ni se muestre notificación
- **SEND_TO_ASSISTANT**: Desvía la llamada a un número de asistente configurado

## 📱 Configuración Inicial

### 1. Inicialización del Asistente

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Inicializar la librería SIP normalmente
        val config = EddysSipLibrary.SipConfig(
            defaultDomain = "tu-dominio.com",
            webSocketUrl = "wss://tu-servidor:puerto/",
            // ... otras configuraciones
        )
        
        EddysSipLibrary.getInstance().initialize(this, config)
        
        // El sistema de asistente se inicializa automáticamente
        // No requiere configuración adicional
    }
}
```

### 2. Permisos Adicionales (para contactos del dispositivo)

Si planeas usar contactos del dispositivo, agrega este permiso:

```xml
<!-- Permiso para leer contactos del dispositivo -->
<uses-permission android:name="android.permission.READ_CONTACTS" />
```

## 🤖 Uso del Sistema de Asistente

### Activar Asistente para una Cuenta

```kotlin
class AssistantSetupActivity : ComponentActivity() {
    private lateinit var sipLibrary: EddysSipLibrary
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        sipLibrary = EddysSipLibrary.getInstance()
        
        // Activar asistente en modo "Solo Contactos" con rechazo inmediato
        lifecycleScope.launch {
            sipLibrary.enableAssistant(
                accountKey = "usuario@dominio.com",
                mode = AssistantMode.CONTACTS_ONLY,
                action = AssistantAction.REJECT_IMMEDIATELY
            )
        }
        
        // O activar con desvío al asistente
        lifecycleScope.launch {
            sipLibrary.enableAssistant(
                accountKey = "usuario@dominio.com",
                mode = AssistantMode.CONTACTS_ONLY,
                action = AssistantAction.SEND_TO_ASSISTANT,
                assistantNumber = "+1234567890"
            )
        }
    }
}
```

### Configurar Modo Lista Negra

```kotlin
// Activar modo lista negra
lifecycleScope.launch {
    sipLibrary.enableAssistant(
        accountKey = "usuario@dominio.com",
        mode = AssistantMode.BLACKLIST_FILTER,
        action = AssistantAction.SEND_TO_ASSISTANT,
        assistantNumber = "+1234567890"
    )
    
    // Añadir números a la lista negra
    sipLibrary.addToBlacklist(
        accountKey = "usuario@dominio.com",
        phoneNumber = "+1234567891",
        displayName = "Spam Caller",
        reason = "Llamadas no deseadas"
    )
    
    sipLibrary.addToBlacklist(
        accountKey = "usuario@dominio.com",
        phoneNumber = "+1234567892",
        reason = "Telemarketing"
    )
}
```

## 👥 Gestión de Contactos

### Opción 1: Usar Contactos del Dispositivo (Automático)

```kotlin
// La librería extraerá automáticamente los contactos del dispositivo
sipLibrary.useDeviceContacts()

// Con configuración personalizada
sipLibrary.useDeviceContacts(
    ContactExtractionConfig(
        includePhoneNumbers = true,
        includeEmails = true,
        includeSipAddresses = true,
        includePhotos = false,
        maxContactsToExtract = 1000,
        sortByDisplayName = true
    )
)
```

### Opción 2: Proporcionar Contactos Manualmente

```kotlin
class ContactProvider {
    
    fun setupManualContacts() {
        // Crear lista de contactos como solicitado
        val contacts = listOf(
            Contact(
                lookupKey = "contact_1",
                displayName = "Juan Pérez",
                phones = linkedSetOf("+1234567890", "1234567890"),
                defaultPhoneNumber = "+1234567890",
                source = ContactSources.MANUAL,
                email = "juan@example.com",
                company = "Mi Empresa"
            ),
            Contact(
                lookupKey = "contact_2",
                displayName = "María García",
                phones = linkedSetOf("+0987654321"),
                defaultPhoneNumber = "+0987654321",
                source = ContactSources.MANUAL
            )
        )
        
        // Establecer contactos en la librería
        sipLibrary.setManualContacts(contacts)
    }
}
```

### Observar Contactos con LiveData (Como Solicitado)

```kotlin
class ContactsViewModel : ViewModel() {
    
    // Obtener LiveData de contactos de la librería
    val contactsList: MediatorLiveData<List<Contact>> = sipLibrary.getContactsList()
    val searchedList: MediatorLiveData<List<Contact>> = sipLibrary.getSearchedContactsList()
    
    // Observar cambios
    fun observeContacts() {
        contactsList.observe(this) { contacts ->
            // Manejar lista completa de contactos
            updateContactsUI(contacts)
        }
        
        searchedList.observe(this) { searchResults ->
            // Manejar resultados de búsqueda
            updateSearchResultsUI(searchResults)
        }
    }
    
    // Buscar contactos
    fun searchContacts(query: String) {
        sipLibrary.searchContactsAsync(query)
    }
}
```

## 🔧 Configuración Avanzada del Asistente

### Gestión Completa por Cuenta

```kotlin
class AssistantConfigManager {
    
    suspend fun setupAssistantForAccount(accountKey: String) {
        val sipLibrary = EddysSipLibrary.getInstance()
        
        // 1. Activar asistente
        sipLibrary.enableAssistant(
            accountKey = accountKey,
            mode = AssistantMode.CONTACTS_ONLY,
            action = AssistantAction.SEND_TO_ASSISTANT,
            assistantNumber = "+1234567890"
        )
        
        // 2. Configurar lista negra si se usa ese modo
        if (useBlacklistMode) {
            sipLibrary.updateAssistantMode(accountKey, AssistantMode.BLACKLIST_FILTER)
            
            // Añadir números problemáticos
            val spamNumbers = listOf(
                "+1234567891" to "Telemarketing",
                "+1234567892" to "Spam",
                "+1234567893" to "Robocalls"
            )
            
            spamNumbers.forEach { (number, reason) ->
                sipLibrary.addToBlacklist(
                    accountKey = accountKey,
                    phoneNumber = number,
                    reason = reason
                )
            }
        }
        
        // 3. Verificar configuración
        val config = sipLibrary.getAssistantConfig(accountKey)
        if (config?.isActive() == true) {
            showMessage("✅ Asistente activado para $accountKey")
        }
    }
    
    suspend fun updateAssistantSettings(accountKey: String) {
        val sipLibrary = EddysSipLibrary.getInstance()
        
        // Cambiar modo
        sipLibrary.updateAssistantMode(accountKey, AssistantMode.BLACKLIST_FILTER)
        
        // Cambiar acción
        sipLibrary.updateAssistantAction(accountKey, AssistantAction.REJECT_IMMEDIATELY)
        
        // Cambiar número del asistente
        sipLibrary.updateAssistantNumber(accountKey, "+0987654321")
        
        // Desactivar si es necesario
        sipLibrary.disableAssistant(accountKey)
    }
}
```

### UI de Configuración con Compose

```kotlin
@Composable
fun AssistantConfigScreen(
    accountKey: String,
    viewModel: AssistantViewModel
) {
    val config by viewModel.getAssistantConfig(accountKey).collectAsState(initial = null)
    val blacklist by viewModel.getBlacklist(accountKey).collectAsState(initial = emptyList())
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Configuración del Asistente",
            style = MaterialTheme.typography.h5,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // Estado del asistente
        AssistantStatusCard(
            config = config,
            onToggleEnabled = { enabled ->
                if (enabled) {
                    viewModel.enableAssistant(
                        accountKey = accountKey,
                        mode = AssistantMode.CONTACTS_ONLY,
                        action = AssistantAction.REJECT_IMMEDIATELY
                    )
                } else {
                    viewModel.disableAssistant(accountKey)
                }
            }
        )
        
        if (config?.isActive() == true) {
            Spacer(modifier = Modifier.height(16.dp))
            
            // Configuración de modo
            AssistantModeSelector(
                currentMode = config.mode,
                onModeChanged = { mode ->
                    viewModel.updateAssistantMode(accountKey, mode)
                }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Configuración de acción
            AssistantActionSelector(
                currentAction = config.action,
                assistantNumber = config.assistantNumber,
                onActionChanged = { action ->
                    viewModel.updateAssistantAction(accountKey, action)
                },
                onAssistantNumberChanged = { number ->
                    viewModel.updateAssistantNumber(accountKey, number)
                }
            )
            
            // Lista negra si está en modo blacklist
            if (config.mode == AssistantMode.BLACKLIST_FILTER) {
                Spacer(modifier = Modifier.height(16.dp))
                
                BlacklistManagement(
                    accountKey = accountKey,
                    blacklist = blacklist,
                    onAddToBlacklist = { phoneNumber, displayName, reason ->
                        viewModel.addToBlacklist(accountKey, phoneNumber, displayName, reason)
                    },
                    onRemoveFromBlacklist = { phoneNumber ->
                        viewModel.removeFromBlacklist(accountKey, phoneNumber)
                    }
                )
            }
        }
    }
}

@Composable
fun AssistantStatusCard(
    config: AssistantConfig?,
    onToggleEnabled: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Asistente SIP",
                        style = MaterialTheme.typography.h6
                    )
                    Text(
                        text = if (config?.isActive() == true) {
                            "Activo - ${config.mode.name}"
                        } else {
                            "Desactivado"
                        },
                        style = MaterialTheme.typography.body2,
                        color = if (config?.isActive() == true) {
                            MaterialTheme.colors.primary
                        } else {
                            MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                        }
                    )
                }
                
                Switch(
                    checked = config?.isEnabled ?: false,
                    onCheckedChange = onToggleEnabled
                )
            }
            
            if (config?.isActive() == true) {
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "Acción: ${config.action.name}",
                    style = MaterialTheme.typography.caption
                )
                
                if (config.assistantNumber.isNotEmpty()) {
                    Text(
                        text = "Número del asistente: ${config.assistantNumber}",
                        style = MaterialTheme.typography.caption
                    )
                }
            }
        }
    }
}

@Composable
fun BlacklistManagement(
    accountKey: String,
    blacklist: List<BlacklistEntry>,
    onAddToBlacklist: (String, String?, String?) -> Unit,
    onRemoveFromBlacklist: (String) -> Unit
) {
    var showAddDialog by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Lista Negra (${blacklist.size})",
                    style = MaterialTheme.typography.h6
                )
                
                IconButton(
                    onClick = { showAddDialog = true }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Añadir")
                }
            }
            
            if (blacklist.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 200.dp)
                ) {
                    items(blacklist) { entry ->
                        BlacklistItem(
                            entry = entry,
                            onRemove = { onRemoveFromBlacklist(entry.phoneNumber) }
                        )
                    }
                }
            } else {
                Text(
                    text = "No hay números en la lista negra",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
    
    if (showAddDialog) {
        AddToBlacklistDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { phoneNumber, displayName, reason ->
                onAddToBlacklist(phoneNumber, displayName, reason)
                showAddDialog = false
            }
        )
    }
}

@Composable
fun BlacklistItem(
    entry: BlacklistEntry,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayName ?: entry.phoneNumber,
                style = MaterialTheme.typography.body1
            )
            if (entry.displayName != null) {
                Text(
                    text = entry.phoneNumber,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
            }
            if (entry.reason != null) {
                Text(
                    text = entry.reason,
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                )
            }
        }
        
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Eliminar",
                tint = MaterialTheme.colors.error
            )
        }
    }
}
```

## 📊 Monitoreo y Estadísticas

### Observar Actividad del Asistente

```kotlin
class AssistantMonitoringViewModel : ViewModel() {
    private val sipLibrary = EddysSipLibrary.getInstance()
    
    // Observar configuraciones activas
    val activeConfigs: StateFlow<List<AssistantConfig>> = 
        sipLibrary.getActiveAssistantConfigurations()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    
    // Observar historial de acciones
    fun getAssistantCallLogs(accountKey: String): StateFlow<List<AssistantCallLog>> {
        return sipLibrary.getAssistantCallLogs(accountKey)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    }
    
    // Obtener estadísticas
    suspend fun getStatistics(accountKey: String): AssistantStatistics? {
        return sipLibrary.getAssistantStatistics(accountKey)
    }
}

@Composable
fun AssistantMonitoringScreen(
    accountKey: String,
    viewModel: AssistantMonitoringViewModel
) {
    val callLogs by viewModel.getAssistantCallLogs(accountKey).collectAsState()
    var statistics by remember { mutableStateOf<AssistantStatistics?>(null) }
    
    LaunchedEffect(accountKey) {
        statistics = viewModel.getStatistics(accountKey)
    }
    
    LazyColumn(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Estadísticas
        statistics?.let { stats ->
            item {
                AssistantStatisticsCard(stats)
            }
        }
        
        // Historial de llamadas procesadas
        item {
            Text(
                text = "Historial del Asistente",
                style = MaterialTheme.typography.h6
            )
        }
        
        items(callLogs) { callLog ->
            AssistantCallLogItem(callLog)
        }
    }
}

@Composable
fun AssistantStatisticsCard(stats: AssistantStatistics) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Estadísticas del Asistente",
                style = MaterialTheme.typography.h6,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("Total", stats.totalProcessedCalls.toString())
                StatItem("Rechazadas", stats.rejectedCalls.toString())
                StatItem("Desviadas", stats.deflectedCalls.toString())
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("Exitosas", stats.successfulDeflections.toString())
                StatItem("Fallidas", stats.failedDeflections.toString())
                StatItem("Lista Negra", stats.blacklistedCalls.toString())
            }
        }
    }
}

@Composable
fun AssistantCallLogItem(callLog: AssistantCallLog) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icono de acción
            Icon(
                imageVector = when (callLog.action) {
                    AssistantAction.REJECT_IMMEDIATELY -> Icons.Default.Block
                    AssistantAction.SEND_TO_ASSISTANT -> Icons.Default.Forward
                },
                contentDescription = null,
                tint = when (callLog.action) {
                    AssistantAction.REJECT_IMMEDIATELY -> MaterialTheme.colors.error
                    AssistantAction.SEND_TO_ASSISTANT -> MaterialTheme.colors.primary
                },
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = callLog.callerDisplayName ?: callLog.callerNumber,
                    style = MaterialTheme.typography.subtitle1
                )
                
                Text(
                    text = when (callLog.reason) {
                        "not_in_contacts" -> "No está en contactos"
                        "blacklisted" -> "En lista negra"
                        else -> callLog.reason
                    },
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
                
                Text(
                    text = formatTimestamp(callLog.timestamp),
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                )
            }
            
            // Estado del resultado
            if (callLog.wasDeflected) {
                Icon(
                    imageVector = if (callLog.deflectionSuccess) {
                        Icons.Default.CheckCircle
                    } else {
                        Icons.Default.Error
                    },
                    contentDescription = null,
                    tint = if (callLog.deflectionSuccess) {
                        MaterialTheme.colors.primary
                    } else {
                        MaterialTheme.colors.error
                    },
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
```

## 🔄 Integración con SipCoreManager

### Procesamiento Automático de Llamadas

El sistema se integra automáticamente con el flujo de llamadas entrantes:

```kotlin
// En SipCoreManager (integración interna)
class SipCoreManager {
    
    private lateinit var assistantIntegration: AssistantIntegration
    
    private fun setupAssistantIntegration() {
        assistantIntegration = AssistantIntegration(application, this, databaseManager)
        assistantIntegration.initialize()
    }
    
    // Cuando se recibe un INVITE entrante
    private suspend fun handleIncomingInvite(message: String, accountInfo: AccountInfo) {
        val callData = parseIncomingCall(message, accountInfo)
        
        // NUEVO: Verificar si el asistente debe procesar la llamada
        val shouldProcessByAssistant = assistantIntegration.processIncomingCall(
            accountKey = accountInfo.getAccountIdentity(),
            callId = callData.callId,
            callData = callData
        )
        
        if (shouldProcessByAssistant) {
            // El asistente procesará la llamada (rechazar o desviar)
            log.d("SipCoreManager") { "Call will be processed by assistant: ${callData.callId}" }
            return
        }
        
        // Continuar con el flujo normal de llamada entrante
        processNormalIncomingCall(callData, accountInfo)
    }
    
    // Método para rechazar llamada silenciosamente (llamado por el asistente)
    suspend fun rejectCallSilently(callId: String, reason: String) {
        log.d("SipCoreManager") { "Rejecting call silently: $callId, reason: $reason" }
        
        // Enviar respuesta 603 Decline sin mostrar UI ni reproducir sonido
        val callData = getCallData(callId)
        if (callData != null) {
            sendSipResponse(callData, 603, "Decline")
            
            // Actualizar estado sin notificar UI
            CallStateManager.callEnded(callId, 603, "Declined by Assistant")
        }
    }
    
    // Método para desviar llamada (ya existe deflectIncomingCall)
    suspend fun deflectIncomingCall(callId: String, targetNumber: String): Boolean {
        // Implementación existente de deflectIncomingCall
        return deflectIncomingCall(targetNumber)
    }
}
```

## 📋 API Completa del Asistente

### Métodos Principales en EddysSipLibrary

```kotlin
// En EddysSipLibrary.kt (métodos que se añadirán)

/**
 * Activa el asistente para una cuenta específica
 */
suspend fun enableAssistant(
    accountKey: String,
    mode: AssistantMode,
    action: AssistantAction,
    assistantNumber: String = ""
): AssistantConfig

/**
 * Desactiva el asistente para una cuenta
 */
suspend fun disableAssistant(accountKey: String)

/**
 * Obtiene configuración del asistente
 */
suspend fun getAssistantConfig(accountKey: String): AssistantConfig?

/**
 * Actualiza modo del asistente
 */
suspend fun updateAssistantMode(accountKey: String, mode: AssistantMode)

/**
 * Actualiza acción del asistente
 */
suspend fun updateAssistantAction(accountKey: String, action: AssistantAction)

/**
 * Actualiza número del asistente
 */
suspend fun updateAssistantNumber(accountKey: String, assistantNumber: String)

/**
 * Añade número a la lista negra
 */
suspend fun addToBlacklist(
    accountKey: String,
    phoneNumber: String,
    displayName: String? = null,
    reason: String? = null
): BlacklistEntry

/**
 * Remueve número de la lista negra
 */
suspend fun removeFromBlacklist(accountKey: String, phoneNumber: String)

/**
 * Obtiene lista negra para una cuenta
 */
fun getBlacklist(accountKey: String): Flow<List<BlacklistEntry>>

/**
 * Establece contactos manualmente
 */
fun setManualContacts(contacts: List<Contact>)

/**
 * Cambia a usar contactos del dispositivo
 */
fun useDeviceContacts(config: ContactExtractionConfig = ContactExtractionConfig())

/**
 * Obtiene LiveData de contactos (como solicitado)
 */
fun getContactsList(): MediatorLiveData<List<Contact>>

/**
 * Obtiene LiveData de búsqueda de contactos
 */
fun getSearchedContactsList(): MediatorLiveData<List<Contact>>

/**
 * Busca contactos de forma asíncrona
 */
fun searchContactsAsync(query: String)

/**
 * Obtiene configuraciones activas del asistente
 */
fun getActiveAssistantConfigurations(): Flow<List<AssistantConfig>>

/**
 * Obtiene historial de llamadas del asistente
 */
fun getAssistantCallLogs(accountKey: String): Flow<List<AssistantCallLog>>

/**
 * Obtiene estadísticas del asistente
 */
suspend fun getAssistantStatistics(accountKey: String): AssistantStatistics?

/**
 * Verifica si el asistente está activo para una cuenta
 */
fun isAssistantActive(accountKey: String): Boolean
```

## 🎯 Ejemplos de Uso Completos

### Configuración Básica

```kotlin
class AssistantSetup {
    
    suspend fun setupBasicAssistant() {
        val sipLibrary = EddysSipLibrary.getInstance()
        val accountKey = "usuario@dominio.com"
        
        // 1. Activar asistente en modo "solo contactos"
        sipLibrary.enableAssistant(
            accountKey = accountKey,
            mode = AssistantMode.CONTACTS_ONLY,
            action = AssistantAction.REJECT_IMMEDIATELY
        )
        
        // 2. Usar contactos del dispositivo
        sipLibrary.useDeviceContacts()
        
        // 3. Verificar que está activo
        val isActive = sipLibrary.isAssistantActive(accountKey)
        println("Asistente activo: $isActive")
    }
}
```

### Configuración Avanzada con Lista Negra

```kotlin
class AdvancedAssistantSetup {
    
    suspend fun setupAdvancedAssistant() {
        val sipLibrary = EddysSipLibrary.getInstance()
        val accountKey = "usuario@dominio.com"
        
        // 1. Activar con desvío al asistente
        sipLibrary.enableAssistant(
            accountKey = accountKey,
            mode = AssistantMode.BLACKLIST_FILTER,
            action = AssistantAction.SEND_TO_ASSISTANT,
            assistantNumber = "+1234567890"
        )
        
        // 2. Configurar lista negra
        val spamNumbers = listOf(
            "+1111111111" to "Telemarketing",
            "+2222222222" to "Robocalls",
            "+3333333333" to "Spam"
        )
        
        spamNumbers.forEach { (number, reason) ->
            sipLibrary.addToBlacklist(
                accountKey = accountKey,
                phoneNumber = number,
                reason = reason
            )
        }
        
        // 3. Establecer contactos manualmente
        val contacts = listOf(
            Contact(
                lookupKey = "contact_1",
                displayName = "Cliente Importante",
                phones = linkedSetOf("+5555555555"),
                source = ContactSources.MANUAL
            )
        )
        
        sipLibrary.setManualContacts(contacts)
        
        // 4. Verificar configuración
        val config = sipLibrary.getAssistantConfig(accountKey)
        println("Configuración: $config")
    }
}
```

### Monitoreo en Tiempo Real

```kotlin
@Composable
fun AssistantDashboard(accountKey: String) {
    val sipLibrary = EddysSipLibrary.getInstance()
    
    val config by sipLibrary.getAssistantConfig(accountKey).collectAsState(initial = null)
    val callLogs by sipLibrary.getAssistantCallLogs(accountKey).collectAsState(initial = emptyList())
    val blacklist by sipLibrary.getBlacklist(accountKey).collectAsState(initial = emptyList())
    
    var statistics by remember { mutableStateOf<AssistantStatistics?>(null) }
    
    LaunchedEffect(accountKey) {
        statistics = sipLibrary.getAssistantStatistics(accountKey)
    }
    
    Column {
        // Estado actual
        config?.let { cfg ->
            AssistantStatusCard(
                config = cfg,
                onToggleEnabled = { enabled ->
                    if (enabled) {
                        // Activar con configuración por defecto
                        lifecycleScope.launch {
                            sipLibrary.enableAssistant(
                                accountKey = accountKey,
                                mode = AssistantMode.CONTACTS_ONLY,
                                action = AssistantAction.REJECT_IMMEDIATELY
                            )
                        }
                    } else {
                        lifecycleScope.launch {
                            sipLibrary.disableAssistant(accountKey)
                        }
                    }
                }
            )
        }
        
        // Estadísticas
        statistics?.let { stats ->
            Spacer(modifier = Modifier.height(16.dp))
            AssistantStatisticsCard(stats)
        }
        
        // Lista negra
        if (config?.mode == AssistantMode.BLACKLIST_FILTER) {
            Spacer(modifier = Modifier.height(16.dp))
            BlacklistManagement(
                accountKey = accountKey,
                blacklist = blacklist,
                onAddToBlacklist = { phoneNumber, displayName, reason ->
                    lifecycleScope.launch {
                        sipLibrary.addToBlacklist(accountKey, phoneNumber, displayName, reason)
                    }
                },
                onRemoveFromBlacklist = { phoneNumber ->
                    lifecycleScope.launch {
                        sipLibrary.removeFromBlacklist(accountKey, phoneNumber)
                    }
                }
            )
        }
        
        // Historial reciente
        if (callLogs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Actividad Reciente",
                style = MaterialTheme.typography.h6
            )
            
            LazyColumn(
                modifier = Modifier.heightIn(max = 300.dp)
            ) {
                items(callLogs.take(10)) { callLog ->
                    AssistantCallLogItem(callLog)
                }
            }
        }
    }
}
```

## 🔧 Configuración Multi-Cuenta

```kotlin
class MultiAccountAssistantManager {
    
    suspend fun setupAssistantForAllAccounts() {
        val sipLibrary = EddysSipLibrary.getInstance()
        
        // Obtener todas las cuentas registradas
        val registeredAccounts = sipLibrary.getAllRegisteredAccountKeys()
        
        registeredAccounts.forEach { accountKey ->
            // Configurar asistente específico por cuenta
            when (accountKey) {
                "trabajo@empresa.com" -> {
                    // Cuenta de trabajo: solo contactos, desviar al asistente
                    sipLibrary.enableAssistant(
                        accountKey = accountKey,
                        mode = AssistantMode.CONTACTS_ONLY,
                        action = AssistantAction.SEND_TO_ASSISTANT,
                        assistantNumber = "+1234567890"
                    )
                }
                
                "personal@proveedor.com" -> {
                    // Cuenta personal: lista negra, rechazar inmediatamente
                    sipLibrary.enableAssistant(
                        accountKey = accountKey,
                        mode = AssistantMode.BLACKLIST_FILTER,
                        action = AssistantAction.REJECT_IMMEDIATELY
                    )
                    
                    // Añadir números de spam conocidos
                    addCommonSpamNumbers(accountKey)
                }
            }
        }
    }
    
    private suspend fun addCommonSpamNumbers(accountKey: String) {
        val sipLibrary = EddysSipLibrary.getInstance()
        
        val spamNumbers = listOf(
            "+1800123456" to "Telemarketing",
            "+1900123456" to "Servicios premium",
            "+1555123456" to "Robocalls"
        )
        
        spamNumbers.forEach { (number, reason) ->
            sipLibrary.addToBlacklist(accountKey, number, null, reason)
        }
    }
}
```

## 🚨 Mejores Prácticas

### 1. **Gestión de Permisos**

```kotlin
class PermissionManager {
    
    fun checkContactsPermission(activity: Activity): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    fun requestContactsPermission(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.READ_CONTACTS),
            REQUEST_CONTACTS_PERMISSION
        )
    }
}
```

### 2. **Manejo de Errores**

```kotlin
class SafeAssistantOperations {
    
    suspend fun safeEnableAssistant(
        accountKey: String,
        mode: AssistantMode,
        action: AssistantAction,
        assistantNumber: String = ""
    ): Result<AssistantConfig> {
        return try {
            val config = sipLibrary.enableAssistant(accountKey, mode, action, assistantNumber)
            Result.success(config)
        } catch (e: Exception) {
            log.e("AssistantOps") { "Error enabling assistant: ${e.message}" }
            Result.failure(e)
        }
    }
}
```

### 3. **Optimización de Rendimiento**

```kotlin
class AssistantPerformanceOptimizer {
    
    fun optimizeContactsLoading() {
        // Cargar contactos en background
        lifecycleScope.launch(Dispatchers.IO) {
            sipLibrary.useDeviceContacts(
                ContactExtractionConfig(
                    maxContactsToExtract = 500, // Limitar para mejor rendimiento
                    includePhotos = false,      // Evitar cargar fotos si no es necesario
                    sortByDisplayName = true
                )
            )
        }
    }
    
    fun setupPeriodicCleanup() {
        // Limpiar logs antiguos del asistente
        Timer().scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                lifecycleScope.launch {
                    databaseManager.cleanupOldAssistantLogs(daysToKeep = 30)
                }
            }
        }, 0, 24 * 60 * 60 * 1000) // Cada 24 horas
    }
}
```

## 🎯 Conclusión

El Sistema de Asistente SIP proporciona:

- ✅ **Filtrado automático** de llamadas entrantes por cuenta individual
- ✅ **Gestión flexible de contactos** (automática o manual)
- ✅ **Lista negra personalizable** por cuenta
- ✅ **Rechazo silencioso** sin molestar al usuario
- ✅ **Desvío inteligente** al asistente configurado
- ✅ **Historial completo** de todas las acciones
- ✅ **Estadísticas detalladas** de uso
- ✅ **Integración transparente** con el flujo SIP existente

Esta implementación permite crear aplicaciones SIP con capacidades avanzadas de filtrado de llamadas y gestión automática de asistentes virtuales.

---

**Desarrollado con ❤️ por Eddys Larez**

*¿Te gusta el nuevo sistema de asistente? ¡Dale una ⭐ en GitHub!*