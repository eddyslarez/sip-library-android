# 📊 Room Database Integration - EddysSipLibrary

Esta documentación explica cómo usar las nuevas funcionalidades de base de datos Room integradas en la librería SIP.

## 🚀 Características de la Base de Datos

### ✅ **Entidades Principales**
- 🏢 **SipAccountEntity**: Gestión de cuentas SIP
- 📞 **CallLogEntity**: Historial completo de llamadas
- 📊 **CallDataEntity**: Datos de llamadas activas
- 👥 **ContactEntity**: Gestión de contactos
- 📈 **CallStateHistoryEntity**: Historial detallado de estados

### ✅ **Funcionalidades Avanzadas**
- 🔄 Sincronización automática con estados de llamada
- 📊 Estadísticas detalladas de llamadas y cuentas
- 🔍 Búsqueda avanzada en historial y contactos
- 🧹 Limpieza automática de datos antiguos
- 📱 Soporte completo para múltiples cuentas SIP

## 📋 Configuración Inicial

### 1. Dependencias (Ya incluidas)

Las dependencias de Room ya están configuradas en el `build.gradle.kts`:

```kotlin
// Room Database
implementation("androidx.room:room-runtime:2.6.1")
implementation("androidx.room:room-ktx:2.6.1")
kapt("androidx.room:room-compiler:2.6.1")
```

### 2. Inicialización en tu Application

```kotlin
class MyApplication : Application() {
    
    // Instancia del DatabaseManager
    val databaseManager by lazy {
        DatabaseManager.getInstance(this)
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // Configurar limpieza automática (opcional)
        databaseManager.cleanupOldData(daysToKeep = 30)
    }
}
```

## 🏢 Gestión de Cuentas SIP

### Crear/Actualizar Cuenta

```kotlin
class SipAccountManager(private val databaseManager: DatabaseManager) {
    
    suspend fun registerAccount(
        username: String,
        password: String,
        domain: String,
        displayName: String? = null,
        pushToken: String? = null
    ) {
        // Crear cuenta en base de datos
        val account = databaseManager.createOrUpdateSipAccount(
            username = username,
            password = password,
            domain = domain,
            displayName = displayName ?: username,
            pushToken = pushToken,
            pushProvider = "fcm"
        )
        
        // Registrar con el servidor SIP
        sipLibrary.registerAccount(username, password, domain, pushToken, "fcm")
        
        Log.d("SipAccount", "Account created: ${account.getAccountKey()}")
    }
}
```

### Observar Cuentas Activas

```kotlin
@Composable
fun AccountsScreen(databaseManager: DatabaseManager) {
    val accounts by databaseManager.getActiveSipAccounts().collectAsState(initial = emptyList())
    val registeredAccounts by databaseManager.getRegisteredSipAccounts().collectAsState(initial = emptyList())
    
    LazyColumn {
        items(accounts) { account ->
            AccountItem(
                account = account,
                isRegistered = registeredAccounts.any { it.id == account.id },
                onToggleRegistration = { 
                    if (account.isRegistered()) {
                        sipLibrary.unregisterAccount(account.username, account.domain)
                    } else {
                        sipLibrary.registerAccount(
                            account.username, 
                            account.password, 
                            account.domain
                        )
                    }
                }
            )
        }
    }
}

@Composable
fun AccountItem(
    account: SipAccountEntity,
    isRegistered: Boolean,
    onToggleRegistration: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = account.displayName ?: account.username,
                style = MaterialTheme.typography.h6
            )
            Text(
                text = account.getAccountKey(),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isRegistered) "✅ Registrado" else "❌ No registrado",
                    style = MaterialTheme.typography.body2
                )
                
                Switch(
                    checked = isRegistered,
                    onCheckedChange = { onToggleRegistration() }
                )
            }
            
            // Estadísticas
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("Total", account.totalCalls.toString())
                StatItem("Exitosas", account.successfulCalls.toString())
                StatItem("Fallidas", account.failedCalls.toString())
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.h6)
        Text(
            text = label, 
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
        )
    }
}
```

## 📞 Historial de Llamadas

### Integración Automática con SipLibrary

```kotlin
class CallHistoryIntegration(
    private val databaseManager: DatabaseManager,
    private val sipLibrary: EddysSipLibrary
) {
    
    fun setupCallHistoryTracking() {
        sipLibrary.addSipEventListener(object : EddysSipLibrary.SipEventListener {
            override fun onCallStateChanged(stateInfo: CallStateInfo) {
                lifecycleScope.launch {
                    when (stateInfo.state) {
                        DetailedCallState.STREAMS_RUNNING -> {
                            // Llamada conectada - crear datos de llamada activa
                            val account = getCurrentAccount()
                            if (account != null) {
                                val callData = getCurrentCallData()
                                databaseManager.createCallData(account.id, callData)
                                databaseManager.updateCallState(
                                    callId = callData.callId,
                                    newState = CallState.STREAMS_RUNNING
                                )
                            }
                        }
                        
                        DetailedCallState.ENDED -> {
                            // Llamada terminada - crear entrada en historial
                            val account = getCurrentAccount()
                            val callData = getCurrentCallData()
                            
                            if (account != null && callData != null) {
                                val callType = determineCallType(stateInfo)
                                
                                databaseManager.createCallLog(
                                    accountId = account.id,
                                    callData = callData,
                                    callType = callType,
                                    endTime = System.currentTimeMillis(),
                                    sipCode = stateInfo.sipCode,
                                    sipReason = stateInfo.sipReason
                                )
                                
                                databaseManager.endCall(callData.callId)
                            }
                        }
                        
                        DetailedCallState.ERROR -> {
                            // Error en llamada
                            val callData = getCurrentCallData()
                            if (callData != null) {
                                databaseManager.updateCallState(
                                    callId = callData.callId,
                                    newState = CallState.ERROR,
                                    errorReason = stateInfo.errorReason ?: CallErrorReason.UNKNOWN,
                                    sipCode = stateInfo.sipCode,
                                    sipReason = stateInfo.sipReason
                                )
                            }
                        }
                    }
                }
            }
        })
    }
    
    private fun determineCallType(stateInfo: CallStateInfo): CallTypes {
        return when {
            stateInfo.errorReason == CallErrorReason.NO_ANSWER -> CallTypes.MISSED
            stateInfo.errorReason == CallErrorReason.REJECTED -> CallTypes.DECLINED
            stateInfo.hasError() -> CallTypes.ABORTED
            else -> CallTypes.SUCCESS
        }
    }
}
```

### UI para Historial de Llamadas

```kotlin
@Composable
fun CallHistoryScreen(databaseManager: DatabaseManager) {
    var searchQuery by remember { mutableStateOf("") }
    
    val callLogs by if (searchQuery.isBlank()) {
        databaseManager.getRecentCallLogs(100).collectAsState(initial = emptyList())
    } else {
        databaseManager.searchCallLogs(searchQuery).collectAsState(initial = emptyList())
    }
    
    val missedCalls by databaseManager.getMissedCallLogs().collectAsState(initial = emptyList())
    
    Column {
        // Barra de búsqueda
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Buscar llamadas...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        )
        
        // Indicador de llamadas perdidas
        if (missedCalls.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                backgroundColor = MaterialTheme.colors.error.copy(alpha = 0.1f)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.MissedVideoCall,
                        contentDescription = null,
                        tint = MaterialTheme.colors.error
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${missedCalls.size} llamadas perdidas",
                        color = MaterialTheme.colors.error
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        
        // Lista de llamadas
        LazyColumn {
            items(callLogs) { callLogWithContact ->
                CallLogItem(
                    callLog = callLogWithContact,
                    onCallBack = { phoneNumber ->
                        sipLibrary.makeCall(phoneNumber)
                    },
                    onAddToContacts = { phoneNumber ->
                        // Navegar a pantalla de crear contacto
                    }
                )
            }
        }
    }
}

@Composable
fun CallLogItem(
    callLog: CallLogWithContact,
    onCallBack: (String) -> Unit,
    onAddToContacts: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar o icono
            if (callLog.getAvatarUrl() != null) {
                AsyncImage(
                    model = callLog.getAvatarUrl(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            MaterialTheme.colors.primary.copy(alpha = 0.1f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = callLog.contact?.getInitials() ?: "?",
                        style = MaterialTheme.typography.h6,
                        color = MaterialTheme.colors.primary
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            // Información de la llamada
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = callLog.getDisplayName(),
                    style = MaterialTheme.typography.subtitle1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Icono de dirección
                    Icon(
                        imageVector = when (callLog.callLog.direction) {
                            CallDirections.INCOMING -> Icons.Default.CallReceived
                            CallDirections.OUTGOING -> Icons.Default.CallMade
                        },
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = when (callLog.callLog.callType) {
                            CallTypes.MISSED -> MaterialTheme.colors.error
                            CallTypes.SUCCESS -> MaterialTheme.colors.primary
                            else -> MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                        }
                    )
                    
                    Spacer(modifier = Modifier.width(4.dp))
                    
                    Text(
                        text = formatCallTime(callLog.callLog.startTime),
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                    )
                    
                    if (callLog.callLog.duration > 0) {
                        Text(
                            text = " • ${callLog.callLog.getFormattedDuration()}",
                            style = MaterialTheme.typography.body2,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            }
            
            // Botón de llamar
            IconButton(
                onClick = { onCallBack(callLog.callLog.phoneNumber) }
            ) {
                Icon(
                    Icons.Default.Call,
                    contentDescription = "Llamar",
                    tint = MaterialTheme.colors.primary
                )
            }
        }
    }
}

private fun formatCallTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    
    return when {
        diff < 60_000 -> "Hace un momento"
        diff < 3600_000 -> "${diff / 60_000} min"
        diff < 86400_000 -> "${diff / 3600_000} h"
        else -> {
            val date = Date(timestamp)
            SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(date)
        }
    }
}
```

## 👥 Gestión de Contactos

### Crear y Gestionar Contactos

```kotlin
class ContactManager(private val databaseManager: DatabaseManager) {
    
    suspend fun createContact(
        phoneNumber: String,
        displayName: String,
        firstName: String? = null,
        lastName: String? = null,
        email: String? = null,
        company: String? = null
    ): ContactEntity {
        return databaseManager.createOrUpdateContact(
            phoneNumber = phoneNumber,
            displayName = displayName,
            firstName = firstName,
            lastName = lastName,
            email = email,
            company = company
        )
    }
    
    suspend fun blockContact(phoneNumber: String) {
        val contact = databaseManager.createOrUpdateContact(
            phoneNumber = phoneNumber,
            displayName = phoneNumber
        )
        // Actualizar estado de bloqueo a través del DAO
        // (necesitarías acceso directo al DAO o agregar método al DatabaseManager)
    }
    
    suspend fun isBlocked(phoneNumber: String): Boolean {
        return databaseManager.isPhoneNumberBlocked(phoneNumber)
    }
}
```

### UI de Contactos

```kotlin
@Composable
fun ContactsScreen(databaseManager: DatabaseManager) {
    var searchQuery by remember { mutableStateOf("") }
    
    val contacts by if (searchQuery.isBlank()) {
        databaseManager.getAllContacts().collectAsState(initial = emptyList())
    } else {
        databaseManager.searchContacts(searchQuery).collectAsState(initial = emptyList())
    }
    
    Column {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Buscar contactos...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        )
        
        LazyColumn {
            items(contacts) { contact ->
                ContactItem(
                    contact = contact,
                    onCall = { phoneNumber ->
                        sipLibrary.makeCall(phoneNumber)
                    },
                    onEdit = { contact ->
                        // Navegar a pantalla de edición
                    }
                )
            }
        }
    }
}

@Composable
fun ContactItem(
    contact: ContactEntity,
    onCall: (String) -> Unit,
    onEdit: (ContactEntity) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onEdit(contact) }
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        if (contact.isFavorite) MaterialTheme.colors.secondary.copy(alpha = 0.2f)
                        else MaterialTheme.colors.primary.copy(alpha = 0.1f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (contact.avatarUrl != null) {
                    AsyncImage(
                        model = contact.avatarUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                    )
                } else {
                    Text(
                        text = contact.getInitials(),
                        style = MaterialTheme.typography.h6,
                        color = if (contact.isFavorite) MaterialTheme.colors.secondary
                               else MaterialTheme.colors.primary
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = contact.getFullName(),
                        style = MaterialTheme.typography.subtitle1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    if (contact.isFavorite) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Star,
                            contentDescription = "Favorito",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colors.secondary
                        )
                    }
                    
                    if (contact.isBlocked) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Block,
                            contentDescription = "Bloqueado",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colors.error
                        )
                    }
                }
                
                Text(
                    text = contact.phoneNumber,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
                )
                
                if (contact.company != null) {
                    Text(
                        text = contact.company,
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                    )
                }
                
                // Estadísticas de llamadas
                if (contact.totalCalls > 0) {
                    Text(
                        text = "${contact.totalCalls} llamadas",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.primary
                    )
                }
            }
            
            IconButton(
                onClick = { onCall(contact.phoneNumber) },
                enabled = !contact.isBlocked
            ) {
                Icon(
                    Icons.Default.Call,
                    contentDescription = "Llamar",
                    tint = if (contact.isBlocked) 
                        MaterialTheme.colors.onSurface.copy(alpha = 0.3f)
                    else MaterialTheme.colors.primary
                )
            }
        }
    }
}
```

## 📊 Estadísticas y Reportes

### Dashboard de Estadísticas

```kotlin
@Composable
fun StatisticsScreen(databaseManager: DatabaseManager) {
    var statistics by remember { mutableStateOf<GeneralStatistics?>(null) }
    
    LaunchedEffect(Unit) {
        statistics = databaseManager.getGeneralStatistics()
    }
    
    statistics?.let { stats ->
        LazyColumn(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = "Estadísticas Generales",
                    style = MaterialTheme.typography.h5,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }
            
            item {
                StatisticsCard(
                    title = "Cuentas SIP",
                    items = listOf(
                        "Total" to stats.totalAccounts.toString(),
                        "Registradas" to stats.registeredAccounts.toString(),
                        "Activas" to "${stats.registeredAccounts}/${stats.totalAccounts}"
                    )
                )
            }
            
            item {
                StatisticsCard(
                    title = "Llamadas",
                    items = listOf(
                        "Total" to stats.totalCalls.toString(),
                        "Perdidas" to stats.missedCalls.toString(),
                        "Activas" to stats.activeCalls.toString()
                    )
                )
            }
            
            item {
                StatisticsCard(
                    title = "Contactos",
                    items = listOf(
                        "Total" to stats.totalContacts.toString()
                    )
                )
            }
        }
    }
}

@Composable
fun StatisticsCard(
    title: String,
    items: List<Pair<String, String>>
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.h6,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            items.forEach { (label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.body1
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.body1,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colors.primary
                    )
                }
            }
        }
    }
}
```

## 🧹 Mantenimiento de Base de Datos

### Configuración de Limpieza Automática

```kotlin
class DatabaseMaintenanceManager(
    private val databaseManager: DatabaseManager
) {
    
    fun setupAutomaticMaintenance() {
        // Limpieza diaria
        Timer().scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                performDailyMaintenance()
            }
        }, 0, 24 * 60 * 60 * 1000) // 24 horas
    }
    
    private fun performDailyMaintenance() {
        // Limpiar datos antiguos (30 días)
        databaseManager.cleanupOldData(daysToKeep = 30)
        
        // Mantener solo los 1000 registros más recientes
        databaseManager.keepOnlyRecentData(
            callLogsLimit = 1000,
            stateHistoryLimit = 5000
        )
        
        // Optimizar base de datos
        databaseManager.optimizeDatabase()
    }
    
    suspend fun getMaintenanceReport(): String {
        return databaseManager.getDatabaseDiagnosticInfo()
    }
}
```

### UI de Configuración de Base de Datos

```kotlin
@Composable
fun DatabaseSettingsScreen(databaseManager: DatabaseManager) {
    var diagnosticInfo by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Configuración de Base de Datos",
            style = MaterialTheme.typography.h5,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // Botones de mantenimiento
        Button(
            onClick = {
                databaseManager.cleanupOldData(30)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Limpiar datos antiguos (30 días)")
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(
            onClick = {
                databaseManager.optimizeDatabase()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Optimizar base de datos")
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Button(
            onClick = {
                isLoading = true
                CoroutineScope(Dispatchers.IO).launch {
                    diagnosticInfo = databaseManager.getDatabaseDiagnosticInfo()
                    isLoading = false
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Obtener información de diagnóstico")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Información de diagnóstico
        if (isLoading) {
            CircularProgressIndicator()
        } else if (diagnosticInfo.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = diagnosticInfo,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.body2,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
```

## 🔧 Configuración Avanzada

### Personalización de la Base de Datos

```kotlin
// En tu Application class
class MyApplication : Application() {
    
    val customDatabaseManager by lazy {
        // Configuración personalizada si necesitas acceso directo a los DAOs
        val database = SipDatabase.getDatabase(this)
        CustomDatabaseManager(database)
    }
}

class CustomDatabaseManager(private val database: SipDatabase) {
    
    // Acceso directo a DAOs para operaciones específicas
    suspend fun getCallLogsWithCustomFilter(): List<CallLogEntity> {
        return database.callLogDao().getCallLogsByDateRange(
            startTime = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000), // 7 días
            endTime = System.currentTimeMillis()
        ).first()
    }
    
    suspend fun getDetailedCallStatistics(): Map<String, Any> {
        val dao = database.callLogDao()
        
        return mapOf(
            "totalCalls" to dao.getTotalCallCount(),
            "averageDuration" to (dao.getAverageCallDuration() ?: 0.0),
            "mostCalledNumbers" to dao.getMostCalledNumbers(5),
            "dailyStats" to dao.getDailyCallStatistics(
                System.currentTimeMillis() - (30 * 24 * 60 * 60 * 1000) // 30 días
            )
        )
    }
}
```

## 🚨 Mejores Prácticas

### 1. **Manejo de Errores**

```kotlin
class SafeDatabaseOperations(private val databaseManager: DatabaseManager) {
    
    suspend fun safeCreateCallLog(
        accountId: String,
        callData: CallData,
        callType: CallTypes
    ): Result<CallLogEntity> {
        return try {
            val callLog = databaseManager.createCallLog(
                accountId = accountId,
                callData = callData,
                callType = callType
            )
            Result.success(callLog)
        } catch (e: Exception) {
            Log.e("DatabaseOps", "Error creating call log", e)
            Result.failure(e)
        }
    }
}
```

### 2. **Observación Reactiva**

```kotlin
class CallHistoryViewModel(
    private val databaseManager: DatabaseManager
) : ViewModel() {
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    
    val callLogs: StateFlow<List<CallLogWithContact>> = searchQuery
        .debounce(300) // Esperar 300ms después del último cambio
        .flatMapLatest { query ->
            if (query.isBlank()) {
                databaseManager.getRecentCallLogs(100)
            } else {
                databaseManager.searchCallLogs(query)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }
}
```

### 3. **Sincronización con Estados SIP**

```kotlin
class SipDatabaseSync(
    private val databaseManager: DatabaseManager,
    private val sipLibrary: EddysSipLibrary
) {
    
    fun startSync() {
        sipLibrary.addSipEventListener(object : EddysSipLibrary.SipEventListener {
            override fun onRegistrationStateChanged(
                state: RegistrationState,
                username: String,
                domain: String
            ) {
                lifecycleScope.launch {
                    val account = databaseManager.getSipAccountByCredentials(username, domain)
                    account?.let {
                        databaseManager.updateSipAccountRegistrationState(
                            accountId = it.id,
                            state = state
                        )
                    }
                }
            }
            
            override fun onCallStateChanged(stateInfo: CallStateInfo) {
                lifecycleScope.launch {
                    // Sincronizar estados de llamada con la base de datos
                    syncCallState(stateInfo)
                }
            }
        })
    }
    
    private suspend fun syncCallState(stateInfo: CallStateInfo) {
        when (stateInfo.state) {
            DetailedCallState.INCOMING_RECEIVED,
            DetailedCallState.OUTGOING_INIT -> {
                // Crear entrada de llamada activa
                val account = getCurrentAccount()
                val callData = getCurrentCallData()
                if (account != null && callData != null) {
                    databaseManager.createCallData(account.id, callData)
                }
            }
            
            DetailedCallState.ENDED -> {
                // Finalizar llamada y crear historial
                val callData = getCurrentCallData()
                if (callData != null) {
                    databaseManager.endCall(callData.callId)
                    
                    val account = getCurrentAccount()
                    if (account != null) {
                        val callType = determineCallType(stateInfo)
                        databaseManager.createCallLog(
                            accountId = account.id,
                            callData = callData,
                            callType = callType,
                            sipCode = stateInfo.sipCode,
                            sipReason = stateInfo.sipReason
                        )
                    }
                }
            }
        }
    }
}
```

## 📱 Ejemplo Completo de Integración

```kotlin
class MainActivity : ComponentActivity() {
    
    private lateinit var sipLibrary: EddysSipLibrary
    private lateinit var databaseManager: DatabaseManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Inicializar componentes
        sipLibrary = EddysSipLibrary.getInstance()
        databaseManager = DatabaseManager.getInstance(application)
        
        // Configurar sincronización
        setupDatabaseSync()
        
        setContent {
            MyAppTheme {
                MainScreen(
                    sipLibrary = sipLibrary,
                    databaseManager = databaseManager
                )
            }
        }
    }
    
    private fun setupDatabaseSync() {
        sipLibrary.addSipEventListener(object : EddysSipLibrary.SipEventListener {
            override fun onRegistrationStateChanged(
                state: RegistrationState,
                username: String,
                domain: String
            ) {
                lifecycleScope.launch {
                    val account = databaseManager.getSipAccountByCredentials(username, domain)
                    account?.let {
                        databaseManager.updateSipAccountRegistrationState(it.id, state)
                    }
                }
            }
            
            override fun onCallStateChanged(stateInfo: CallStateInfo) {
                // Manejar cambios de estado y sincronizar con BD
                handleCallStateChange(stateInfo)
            }
        })
    }
    
    private fun handleCallStateChange(stateInfo: CallStateInfo) {
        lifecycleScope.launch {
            when (stateInfo.state) {
                DetailedCallState.ENDED -> {
                    // Crear entrada en historial cuando termine la llamada
                    val account = getCurrentSipAccount()
                    val callData = getCurrentCallData()
                    
                    if (account != null && callData != null) {
                        val callType = when {
                            stateInfo.errorReason == CallErrorReason.NO_ANSWER -> CallTypes.MISSED
                            stateInfo.errorReason == CallErrorReason.REJECTED -> CallTypes.DECLINED
                            stateInfo.hasError() -> CallTypes.ABORTED
                            else -> CallTypes.SUCCESS
                        }
                        
                        databaseManager.createCallLog(
                            accountId = account.id,
                            callData = callData,
                            callType = callType,
                            endTime = System.currentTimeMillis(),
                            sipCode = stateInfo.sipCode,
                            sipReason = stateInfo.sipReason
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    sipLibrary: EddysSipLibrary,
    databaseManager: DatabaseManager
) {
    var selectedTab by remember { mutableStateOf(0) }
    
    Scaffold(
        bottomBar = {
            BottomNavigation {
                BottomNavigationItem(
                    icon = { Icon(Icons.Default.Call, contentDescription = null) },
                    label = { Text("Llamadas") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                BottomNavigationItem(
                    icon = { Icon(Icons.Default.People, contentDescription = null) },
                    label = { Text("Contactos") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                BottomNavigationItem(
                    icon = { Icon(Icons.Default.AccountBox, contentDescription = null) },
                    label = { Text("Cuentas") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
                BottomNavigationItem(
                    icon = { Icon(Icons.Default.Analytics, contentDescription = null) },
                    label = { Text("Stats") },
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 }
                )
            }
        }
    ) { paddingValues ->
        when (selectedTab) {
            0 -> CallHistoryScreen(databaseManager)
            1 -> ContactsScreen(databaseManager)
            2 -> AccountsScreen(databaseManager)
            3 -> StatisticsScreen(databaseManager)
        }
    }
}
```

## 🎯 Conclusión

La integración de Room Database en EddysSipLibrary proporciona:

- ✅ **Persistencia robusta** de todos los datos SIP
- ✅ **Sincronización automática** con estados de llamada
- ✅ **Búsqueda avanzada** en historial y contactos
- ✅ **Estadísticas detalladas** de uso
- ✅ **Gestión eficiente** de múltiples cuentas SIP
- ✅ **Mantenimiento automático** de la base de datos

Esta implementación te permite crear aplicaciones SIP completas con persistencia de datos profesional y una experiencia de usuario superior.

---

**Desarrollado con ❤️ por Eddys Larez**

*¿Te gusta la nueva funcionalidad? ¡Dale una ⭐ en GitHub!*