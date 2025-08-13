# 🎙️ Transcripción de Audio en Tiempo Real - EddysSipLibrary

Esta documentación explica cómo usar las nuevas funcionalidades de **transcripción de audio en tiempo real** integradas en la librería SIP.

## 🚀 Características de Transcripción

### ✅ **Funcionalidades Principales**
- 🎯 **Interceptación directa de audio WebRTC** antes de que llegue al altavoz
- 🔄 **Transcripción en tiempo real** con resultados parciales y finales
- 🎧 **Múltiples fuentes de audio**: remoto, local, o ambos
- 🌐 **Soporte multi-idioma** con detección automática
- 📊 **Análisis de calidad de audio** en tiempo real
- 💾 **Persistencia automática** en base de datos Room
- 📤 **Exportación** en múltiples formatos (texto, JSON, SRT, VTT)

### ✅ **Proveedores de Transcripción**
- 🤖 **Android Speech Recognition** (incluido)
- ☁️ **Google Cloud Speech-to-Text** (configuración opcional)
- 🔷 **Azure Cognitive Services** (configuración opcional)
- 🟠 **AWS Transcribe** (próximamente)

## 📱 Configuración Inicial

### 1. Permisos Adicionales

Los permisos ya están incluidos en el `AndroidManifest.xml`, pero asegúrate de solicitarlos en runtime:

```kotlin
// Permisos necesarios para transcripción
val transcriptionPermissions = arrayOf(
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.CAPTURE_AUDIO_OUTPUT // Para interceptar audio de salida
)

// Solicitar permisos
ActivityCompat.requestPermissions(this, transcriptionPermissions, REQUEST_TRANSCRIPTION_PERMISSIONS)
```

### 2. Inicialización en tu Application

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Configuración de la librería SIP con transcripción
        val config = EddysSipLibrary.SipConfig(
            defaultDomain = "tu-dominio.com",
            webSocketUrl = "wss://tu-servidor:puerto/",
            enableLogs = true,
            // Habilitar transcripción por defecto
            enableTranscription = true
        )
        
        EddysSipLibrary.getInstance().initialize(this, config)
    }
}
```

## 🎙️ Uso Básico de Transcripción

### Configurar Transcripción

```kotlin
class CallActivity : ComponentActivity() {
    private lateinit var sipLibrary: EddysSipLibrary
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        sipLibrary = EddysSipLibrary.getInstance()
        
        // Configurar transcripción
        setupTranscription()
    }
    
    private fun setupTranscription() {
        // Configuración de transcripción
        val transcriptionConfig = AudioTranscriptionService.TranscriptionConfig(
            isEnabled = true,
            language = "es-ES", // Español
            enablePartialResults = true,
            enablePunctuation = true,
            confidenceThreshold = 0.6f,
            audioSource = AudioTranscriptionService.AudioSource.WEBRTC_REMOTE, // Solo audio remoto
            transcriptionProvider = AudioTranscriptionService.TranscriptionProvider.ANDROID_SPEECH
        )
        
        // Habilitar transcripción
        sipLibrary.enableAudioTranscription(transcriptionConfig)
        
        // Configurar callbacks
        sipLibrary.setTranscriptionCallbacks(
            onTranscriptionResult = { result ->
                handleTranscriptionResult(result)
            },
            onSessionStateChange = { session ->
                handleSessionStateChange(session)
            },
            onTranscriptionError = { error ->
                showError("Error de transcripción: $error")
            }
        )
    }
    
    private fun handleTranscriptionResult(result: AudioTranscriptionService.TranscriptionResult) {
        runOnUiThread {
            if (result.isFinal) {
                // Resultado final - mostrar en UI
                addTranscriptionToUI(result.text, result.confidence, result.speakerLabel)
                
                // Guardar en base de datos (automático)
                Log.d("Transcription", "Final: ${result.text} (${result.confidence})")
            } else {
                // Resultado parcial - mostrar como preview
                showPartialTranscription(result.text)
                Log.d("Transcription", "Partial: ${result.text}")
            }
        }
    }
    
    private fun handleSessionStateChange(session: TranscriptionManager.TranscriptionSession) {
        runOnUiThread {
            if (session.isActive()) {
                showTranscriptionStatus("Transcribiendo...")
            } else {
                showTranscriptionStatus("Transcripción completada")
                
                // Mostrar estadísticas finales
                val stats = session.statistics
                showSessionStats(stats)
            }
        }
    }
}
```

### Control Durante Llamadas

```kotlin
class InCallViewModel : ViewModel() {
    private val sipLibrary = EddysSipLibrary.getInstance()
    
    // Estados de transcripción
    val isTranscriptionActive = sipLibrary.getTranscriptionManager()
        ?.isActiveFlow ?: MutableStateFlow(false)
    
    val transcriptionResults = sipLibrary.getTranscriptionManager()
        ?.transcriptionResultFlow ?: MutableStateFlow(null)
    
    val audioLevel = sipLibrary.getTranscriptionManager()
        ?.getAudioLevel() ?: 0f
    
    /**
     * Inicia transcripción para la llamada actual
     */
    fun startTranscription() {
        val config = AudioTranscriptionService.TranscriptionConfig(
            isEnabled = true,
            language = "es-ES",
            enablePartialResults = true,
            audioSource = AudioTranscriptionService.AudioSource.WEBRTC_REMOTE
        )
        
        sipLibrary.startTranscriptionForCurrentCall(config)
    }
    
    /**
     * Detiene transcripción
     */
    fun stopTranscription() {
        sipLibrary.stopTranscriptionForCurrentCall()
    }
    
    /**
     * Cambia idioma de transcripción
     */
    fun changeTranscriptionLanguage(language: String) {
        sipLibrary.setTranscriptionLanguage(language)
    }
    
    /**
     * Cambia fuente de audio
     */
    fun changeAudioSource(source: AudioTranscriptionService.AudioSource) {
        sipLibrary.setTranscriptionAudioSource(source)
    }
    
    /**
     * Exporta transcripción actual
     */
    fun exportCurrentTranscription(format: TranscriptionManager.ExportFormat): String? {
        val session = sipLibrary.getCurrentTranscriptionSession()
        return if (session != null) {
            sipLibrary.exportTranscriptionSession(session.id, format)
        } else {
            null
        }
    }
}
```

## 🎨 UI de Transcripción con Compose

### Pantalla de Transcripción en Tiempo Real

```kotlin
@Composable
fun TranscriptionScreen(viewModel: InCallViewModel) {
    val isTranscriptionActive by viewModel.isTranscriptionActive.collectAsState()
    val transcriptionResult by viewModel.transcriptionResults.collectAsState()
    val audioLevel = viewModel.audioLevel
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header con controles
        TranscriptionHeader(
            isActive = isTranscriptionActive,
            audioLevel = audioLevel,
            onToggleTranscription = {
                if (isTranscriptionActive) {
                    viewModel.stopTranscription()
                } else {
                    viewModel.startTranscription()
                }
            },
            onChangeLanguage = { language ->
                viewModel.changeTranscriptionLanguage(language)
            }
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Área de transcripción
        TranscriptionContent(
            result = transcriptionResult,
            isActive = isTranscriptionActive
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Controles adicionales
        TranscriptionControls(
            onChangeAudioSource = { source ->
                viewModel.changeAudioSource(source)
            },
            onExport = { format ->
                val exported = viewModel.exportCurrentTranscription(format)
                if (exported != null) {
                    // Compartir o guardar archivo
                    shareTranscription(exported, format)
                }
            }
        )
    }
}

@Composable
fun TranscriptionHeader(
    isActive: Boolean,
    audioLevel: Float,
    onToggleTranscription: () -> Unit,
    onChangeLanguage: (String) -> Unit
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
                Text(
                    text = "Transcripción de Audio",
                    style = MaterialTheme.typography.h6
                )
                
                Switch(
                    checked = isActive,
                    onCheckedChange = { onToggleTranscription() }
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Indicador de nivel de audio
            AudioLevelIndicator(
                level = audioLevel,
                isActive = isActive
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Selector de idioma
            LanguageSelector(
                currentLanguage = "es-ES",
                onLanguageSelected = onChangeLanguage
            )
        }
    }
}

@Composable
fun AudioLevelIndicator(
    level: Float,
    isActive: Boolean
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isActive) Icons.Default.Mic else Icons.Default.MicOff,
            contentDescription = null,
            tint = if (isActive) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        LinearProgressIndicator(
            progress = level,
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = when {
                level > 0.8f -> Color.Red
                level > 0.5f -> Color.Orange
                level > 0.2f -> Color.Green
                else -> MaterialTheme.colors.primary.copy(alpha = 0.3f)
            }
        )
        
        Spacer(modifier = Modifier.width(8.dp))
        
        Text(
            text = "${(level * 100).toInt()}%",
            style = MaterialTheme.typography.caption
        )
    }
}

@Composable
fun LanguageSelector(
    currentLanguage: String,
    onLanguageSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    val languages = mapOf(
        "es-ES" to "Español",
        "en-US" to "English",
        "fr-FR" to "Français",
        "de-DE" to "Deutsch",
        "it-IT" to "Italiano",
        "pt-BR" to "Português"
    )
    
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Idioma: ${languages[currentLanguage] ?: currentLanguage}")
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            languages.forEach { (code, name) ->
                DropdownMenuItem(
                    onClick = {
                        onLanguageSelected(code)
                        expanded = false
                    }
                ) {
                    Text(name)
                }
            }
        }
    }
}

@Composable
fun TranscriptionContent(
    result: AudioTranscriptionService.TranscriptionResult?,
    isActive: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
        elevation = 2.dp
    ) {
        if (isActive) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                reverseLayout = true // Mostrar lo más reciente arriba
            ) {
                if (result != null) {
                    item {
                        TranscriptionItem(
                            result = result,
                            isLatest = true
                        )
                    }
                }
                
                // Aquí se mostrarían resultados anteriores del historial
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.MicOff,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Transcripción desactivada",
                        style = MaterialTheme.typography.body1,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
fun TranscriptionItem(
    result: AudioTranscriptionService.TranscriptionResult,
    isLatest: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        backgroundColor = if (isLatest && !result.isFinal) {
            MaterialTheme.colors.primary.copy(alpha = 0.1f)
        } else {
            MaterialTheme.colors.surface
        },
        border = if (isLatest && !result.isFinal) {
            BorderStroke(1.dp, MaterialTheme.colors.primary.copy(alpha = 0.3f))
        } else null
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Indicador de speaker
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (result.speakerLabel == "remote") Icons.Default.Person else Icons.Default.PersonOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (result.speakerLabel == "remote") MaterialTheme.colors.primary else MaterialTheme.colors.secondary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (result.speakerLabel == "remote") "Remoto" else "Local",
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                    )
                }
                
                // Timestamp y confianza
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!result.isFinal) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Parcial",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colors.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    
                    Text(
                        text = "${(result.confidence * 100).toInt()}%",
                        style = MaterialTheme.typography.caption,
                        color = when {
                            result.confidence > 0.8f -> Color.Green
                            result.confidence > 0.5f -> Color.Orange
                            else -> Color.Red
                        }
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Text(
                        text = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(result.timestamp)),
                        style = MaterialTheme.typography.caption,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Texto transcrito
            Text(
                text = result.text,
                style = MaterialTheme.typography.body1,
                color = if (result.isFinal) {
                    MaterialTheme.colors.onSurface
                } else {
                    MaterialTheme.colors.onSurface.copy(alpha = 0.7f)
                }
            )
        }
    }
}

@Composable
fun TranscriptionControls(
    onChangeAudioSource: (AudioTranscriptionService.AudioSource) -> Unit,
    onExport: (TranscriptionManager.ExportFormat) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Controles de Transcripción",
                style = MaterialTheme.typography.subtitle1,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            // Selector de fuente de audio
            AudioSourceSelector(
                onSourceSelected = onChangeAudioSource
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Botones de exportación
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ExportButton("Texto", TranscriptionManager.ExportFormat.TEXT, onExport)
                ExportButton("JSON", TranscriptionManager.ExportFormat.JSON, onExport)
                ExportButton("SRT", TranscriptionManager.ExportFormat.SRT, onExport)
                ExportButton("VTT", TranscriptionManager.ExportFormat.VTT, onExport)
            }
        }
    }
}

@Composable
fun AudioSourceSelector(
    onSourceSelected: (AudioTranscriptionService.AudioSource) -> Unit
) {
    var selectedSource by remember { mutableStateOf(AudioTranscriptionService.AudioSource.WEBRTC_REMOTE) }
    
    Column {
        Text(
            text = "Fuente de Audio:",
            style = MaterialTheme.typography.body2,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            AudioSourceOption(
                text = "Remoto",
                source = AudioTranscriptionService.AudioSource.WEBRTC_REMOTE,
                isSelected = selectedSource == AudioTranscriptionService.AudioSource.WEBRTC_REMOTE,
                onSelected = {
                    selectedSource = it
                    onSourceSelected(it)
                }
            )
            
            AudioSourceOption(
                text = "Local",
                source = AudioTranscriptionService.AudioSource.WEBRTC_LOCAL,
                isSelected = selectedSource == AudioTranscriptionService.AudioSource.WEBRTC_LOCAL,
                onSelected = {
                    selectedSource = it
                    onSourceSelected(it)
                }
            )
            
            AudioSourceOption(
                text = "Ambos",
                source = AudioTranscriptionService.AudioSource.WEBRTC_BOTH,
                isSelected = selectedSource == AudioTranscriptionService.AudioSource.WEBRTC_BOTH,
                onSelected = {
                    selectedSource = it
                    onSourceSelected(it)
                }
            )
        }
    }
}

@Composable
fun AudioSourceOption(
    text: String,
    source: AudioTranscriptionService.AudioSource,
    isSelected: Boolean,
    onSelected: (AudioTranscriptionService.AudioSource) -> Unit
) {
    FilterChip(
        selected = isSelected,
        onClick = { onSelected(source) },
        label = { Text(text) },
        selectedIcon = {
            Icon(
                Icons.Default.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        }
    )
}

@Composable
fun ExportButton(
    text: String,
    format: TranscriptionManager.ExportFormat,
    onExport: (TranscriptionManager.ExportFormat) -> Unit
) {
    OutlinedButton(
        onClick = { onExport(format) },
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        Text(text)
    }
}
```

## 📊 Historial y Estadísticas

### Pantalla de Historial de Transcripciones

```kotlin
@Composable
fun TranscriptionHistoryScreen(databaseManager: DatabaseManager) {
    var searchQuery by remember { mutableStateOf("") }
    
    val transcriptionSessions by databaseManager.getTranscriptionSessions()
        .collectAsState(initial = emptyList())
    
    val searchResults by if (searchQuery.isBlank()) {
        flowOf(emptyList<TranscriptionEntity>())
    } else {
        databaseManager.searchTranscriptions(searchQuery)
    }.collectAsState(initial = emptyList())
    
    Column {
        // Barra de búsqueda
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            label = { Text("Buscar en transcripciones...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        )
        
        if (searchQuery.isBlank()) {
            // Mostrar sesiones
            LazyColumn {
                items(transcriptionSessions) { session ->
                    TranscriptionSessionItem(
                        session = session,
                        onClick = { 
                            // Navegar a detalles de sesión
                        }
                    )
                }
            }
        } else {
            // Mostrar resultados de búsqueda
            LazyColumn {
                items(searchResults) { transcription ->
                    TranscriptionSearchResultItem(
                        transcription = transcription,
                        searchQuery = searchQuery
                    )
                }
            }
        }
    }
}

@Composable
fun TranscriptionSessionItem(
    session: TranscriptionSessionEntity,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onClick() }
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Sesión ${session.id.take(8)}",
                    style = MaterialTheme.typography.subtitle1
                )
                
                Text(
                    text = session.getFormattedDuration(),
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.primary
                )
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(session.startTime)),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Estadísticas
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatChip("${session.totalWords} palabras")
                StatChip("${(session.averageConfidence * 100).toInt()}% confianza")
                StatChip("${session.language}")
            }
        }
    }
}

@Composable
fun StatChip(text: String) {
    Surface(
        color = MaterialTheme.colors.primary.copy(alpha = 0.1f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.caption,
            color = MaterialTheme.colors.primary
        )
    }
}
```

## 🔧 Configuración Avanzada

### Configuración de Proveedores en la Nube

```kotlin
class TranscriptionConfigManager {
    
    /**
     * Configura Google Cloud Speech-to-Text
     */
    fun setupGoogleCloudSpeech(apiKey: String) {
        val config = AudioTranscriptionService.TranscriptionConfig(
            isEnabled = true,
            language = "es-ES",
            transcriptionProvider = AudioTranscriptionService.TranscriptionProvider.GOOGLE_CLOUD,
            enablePartialResults = true,
            confidenceThreshold = 0.7f
        )
        
        // Configurar proveedor
        val provider = TranscriptionProviderFactory.createProvider(
            provider = AudioTranscriptionService.TranscriptionProvider.GOOGLE_CLOUD,
            apiKey = apiKey
        )
        
        if (provider != null) {
            sipLibrary.enableAudioTranscription(config)
        }
    }
    
    /**
     * Configura Azure Cognitive Services
     */
    fun setupAzureSpeech(subscriptionKey: String, region: String) {
        val config = AudioTranscriptionService.TranscriptionConfig(
            isEnabled = true,
            language = "es-ES",
            transcriptionProvider = AudioTranscriptionService.TranscriptionProvider.AZURE_COGNITIVE,
            enablePartialResults = true,
            confidenceThreshold = 0.6f
        )
        
        val provider = TranscriptionProviderFactory.createProvider(
            provider = AudioTranscriptionService.TranscriptionProvider.AZURE_COGNITIVE,
            apiKey = subscriptionKey,
            region = region
        )
        
        if (provider != null) {
            sipLibrary.enableAudioTranscription(config)
        }
    }
}
```

### Configuración Personalizada de Audio

```kotlin
class CustomAudioTranscriptionSetup {
    
    fun setupAdvancedTranscription() {
        val config = AudioTranscriptionService.TranscriptionConfig(
            isEnabled = true,
            language = "es-ES",
            enablePartialResults = true,
            enableProfanityFilter = false,
            enablePunctuation = true,
            confidenceThreshold = 0.5f,
            maxSilenceDurationMs = 2000L, // 2 segundos de silencio máximo
            audioSource = AudioTranscriptionService.AudioSource.WEBRTC_BOTH, // Transcribir ambos audios
            transcriptionProvider = AudioTranscriptionService.TranscriptionProvider.ANDROID_SPEECH
        )
        
        sipLibrary.enableAudioTranscription(config)
        
        // Configurar callbacks avanzados
        sipLibrary.setTranscriptionCallbacks(
            onTranscriptionResult = { result ->
                handleAdvancedTranscriptionResult(result)
            },
            onSessionStateChange = { session ->
                handleAdvancedSessionChange(session)
            },
            onTranscriptionError = { error ->
                handleTranscriptionError(error)
            }
        )
    }
    
    private fun handleAdvancedTranscriptionResult(result: AudioTranscriptionService.TranscriptionResult) {
        // Análisis avanzado del resultado
        when {
            result.confidence > 0.9f -> {
                // Alta confianza - procesar inmediatamente
                processHighConfidenceResult(result)
            }
            result.confidence > 0.6f -> {
                // Confianza media - validar con contexto
                validateMediumConfidenceResult(result)
            }
            else -> {
                // Baja confianza - marcar para revisión
                flagLowConfidenceResult(result)
            }
        }
        
        // Detectar palabras clave
        detectKeywords(result.text)
        
        // Análisis de sentimiento básico
        analyzeSentiment(result.text)
    }
    
    private fun processHighConfidenceResult(result: AudioTranscriptionService.TranscriptionResult) {
        // Procesar resultado de alta confianza
        Log.d("Transcription", "High confidence result: ${result.text}")
    }
    
    private fun validateMediumConfidenceResult(result: AudioTranscriptionService.TranscriptionResult) {
        // Validar resultado de confianza media
        Log.d("Transcription", "Medium confidence result: ${result.text}")
    }
    
    private fun flagLowConfidenceResult(result: AudioTranscriptionService.TranscriptionResult) {
        // Marcar resultado de baja confianza para revisión
        Log.w("Transcription", "Low confidence result: ${result.text}")
    }
    
    private fun detectKeywords(text: String) {
        val keywords = listOf("urgente", "importante", "problema", "error", "ayuda")
        val foundKeywords = keywords.filter { text.contains(it, ignoreCase = true) }
        
        if (foundKeywords.isNotEmpty()) {
            Log.i("Transcription", "Keywords detected: $foundKeywords")
            // Notificar o tomar acción específica
        }
    }
    
    private fun analyzeSentiment(text: String) {
        // Análisis básico de sentimiento
        val positiveWords = listOf("bien", "bueno", "excelente", "perfecto", "gracias")
        val negativeWords = listOf("mal", "problema", "error", "molesto", "terrible")
        
        val positiveCount = positiveWords.count { text.contains(it, ignoreCase = true) }
        val negativeCount = negativeWords.count { text.contains(it, ignoreCase = true) }
        
        val sentiment = when {
            positiveCount > negativeCount -> "Positivo"
            negativeCount > positiveCount -> "Negativo"
            else -> "Neutral"
        }
        
        Log.d("Transcription", "Sentiment analysis: $sentiment")
    }
}
```

## 📈 Monitoreo y Análisis

### Dashboard de Transcripción

```kotlin
@Composable
fun TranscriptionDashboard(databaseManager: DatabaseManager) {
    var statistics by remember { mutableStateOf<AudioTranscriptionService.TranscriptionStatistics?>(null) }
    var audioQuality by remember { mutableStateOf<WebRtcAudioInterceptor.AudioQuality?>(null) }
    
    LaunchedEffect(Unit) {
        // Obtener estadísticas
        statistics = sipLibrary.getTranscriptionStatistics()
        audioQuality = sipLibrary.getCurrentAudioQuality()
    }
    
    LazyColumn(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Dashboard de Transcripción",
                style = MaterialTheme.typography.h5
            )
        }
        
        statistics?.let { stats ->
            item {
                TranscriptionStatsCard(stats)
            }
        }
        
        audioQuality?.let { quality ->
            item {
                AudioQualityCard(quality)
            }
        }
        
        item {
            RealtimeTranscriptionCard()
        }
    }
}

@Composable
fun TranscriptionStatsCard(stats: AudioTranscriptionService.TranscriptionStatistics) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Estadísticas de Transcripción",
                style = MaterialTheme.typography.h6,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("Total", stats.totalTranscriptions.toString())
                StatItem("Finales", stats.finalTranscriptions.toString())
                StatItem("Parciales", stats.partialTranscriptions.toString())
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("Confianza Promedio", "${(stats.averageConfidence * 100).toInt()}%")
                StatItem("Duración Total", "${stats.totalDuration / 1000}s")
                StatItem("Idiomas", stats.languagesUsed.size.toString())
            }
        }
    }
}

@Composable
fun AudioQualityCard(quality: WebRtcAudioInterceptor.AudioQuality) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Calidad de Audio",
                style = MaterialTheme.typography.h6,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            
            // Nivel promedio
            QualityIndicator(
                label = "Nivel Promedio",
                value = quality.averageLevel,
                maxValue = 1f,
                color = MaterialTheme.colors.primary
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // SNR
            QualityIndicator(
                label = "SNR",
                value = quality.signalToNoiseRatio,
                maxValue = 60f,
                color = Color.Green,
                unit = "dB"
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Indicadores de problemas
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ProblemIndicator(
                    label = "Clipping",
                    hasIssue = quality.clippingDetected,
                    icon = Icons.Default.Warning
                )
                
                ProblemIndicator(
                    label = "Silencio",
                    hasIssue = quality.silenceDetected,
                    icon = Icons.Default.VolumeOff
                )
            }
        }
    }
}

@Composable
fun QualityIndicator(
    label: String,
    value: Float,
    maxValue: Float,
    color: Color,
    unit: String = ""
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.body2
            )
            Text(
                text = "${value.toInt()}$unit",
                style = MaterialTheme.typography.body2,
                fontWeight = FontWeight.Bold
            )
        }
        
        LinearProgressIndicator(
            progress = (value / maxValue).coerceIn(0f, 1f),
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = color
        )
    }
}

@Composable
fun ProblemIndicator(
    label: String,
    hasIssue: Boolean,
    icon: ImageVector
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (hasIssue) Color.Red else Color.Green
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.caption,
            color = if (hasIssue) Color.Red else Color.Green
        )
    }
}
```

## 🔧 Integración Automática

### Auto-inicio de Transcripción

```kotlin
class AutoTranscriptionManager(
    private val sipLibrary: EddysSipLibrary,
    private val databaseManager: DatabaseManager
) {
    
    fun setupAutoTranscription() {
        sipLibrary.addSipEventListener(object : EddysSipLibrary.SipEventListener {
            override fun onCallStateChanged(stateInfo: CallStateInfo) {
                when (stateInfo.state) {
                    CallState.STREAMS_RUNNING -> {
                        // Llamada conectada - iniciar transcripción automáticamente
                        startAutoTranscription(stateInfo.callId)
                    }
                    
                    CallState.ENDED -> {
                        // Llamada terminada - detener transcripción
                        stopAutoTranscription()
                    }
                }
            }
        })
    }
    
    private fun startAutoTranscription(callId: String) {
        val config = AudioTranscriptionService.TranscriptionConfig(
            isEnabled = true,
            language = getPreferredLanguage(),
            enablePartialResults = true,
            audioSource = AudioTranscriptionService.AudioSource.WEBRTC_REMOTE,
            confidenceThreshold = 0.5f
        )
        
        sipLibrary.startTranscriptionForCurrentCall(config)
        
        Log.d("AutoTranscription", "Auto-transcription started for call: $callId")
    }
    
    private fun stopAutoTranscription() {
        sipLibrary.stopTranscriptionForCurrentCall()
        Log.d("AutoTranscription", "Auto-transcription stopped")
    }
    
    private fun get PreferredLanguage(): String {
        // Obtener idioma preferido del usuario o del sistema
        return Locale.getDefault().toLanguageTag()
    }
}
```

## 🚨 Mejores Prácticas

### 1. **Gestión de Recursos**

```kotlin
class TranscriptionResourceManager {
    
    fun optimizeForBattery() {
        // Configuración optimizada para batería
        val config = AudioTranscriptionService.TranscriptionConfig(
            isEnabled = true,
            enablePartialResults = false, // Menos procesamiento
            confidenceThreshold = 0.7f, // Mayor umbral
            maxSilenceDurationMs = 5000L // Más tolerancia al silencio
        )
        
        sipLibrary.enableAudioTranscription(config)
    }
    
    fun optimizeForAccuracy() {
        // Configuración optimizada para precisión
        val config = AudioTranscriptionService.TranscriptionConfig(
            isEnabled = true,
            enablePartialResults = true,
            confidenceThreshold = 0.3f, // Menor umbral
            maxSilenceDurationMs = 1000L, // Menos tolerancia al silencio
            transcriptionProvider = AudioTranscriptionService.TranscriptionProvider.GOOGLE_CLOUD
        )
        
        sipLibrary.enableAudioTranscription(config)
    }
}
```

### 2. **Manejo de Errores**

```kotlin
class TranscriptionErrorHandler {
    
    fun setupErrorHandling() {
        sipLibrary.setTranscriptionCallbacks(
            onTranscriptionError = { error ->
                when {
                    error.contains("network", ignoreCase = true) -> {
                        // Error de red - cambiar a proveedor local
                        fallbackToLocalProvider()
                    }
                    error.contains("permission", ignoreCase = true) -> {
                        // Error de permisos - solicitar permisos
                        requestTranscriptionPermissions()
                    }
                    error.contains("busy", ignoreCase = true) -> {
                        // Servicio ocupado - reintentar después
                        retryTranscriptionAfterDelay()
                    }
                    else -> {
                        // Error genérico - log y notificar
                        logTranscriptionError(error)
                    }
                }
            }
        )
    }
    
    private fun fallbackToLocalProvider() {
        val fallbackConfig = AudioTranscriptionService.TranscriptionConfig(
            transcriptionProvider = AudioTranscriptionService.TranscriptionProvider.ANDROID_SPEECH
        )
        sipLibrary.enableAudioTranscription(fallbackConfig)
    }
    
    private fun requestTranscriptionPermissions() {
        // Solicitar permisos necesarios
    }
    
    private fun retryTranscriptionAfterDelay() {
        // Reintentar después de un delay
        Handler(Looper.getMainLooper()).postDelayed({
            sipLibrary.startTranscriptionForCurrentCall()
        }, 3000)
    }
    
    private fun logTranscriptionError(error: String) {
        Log.e("TranscriptionError", error)
        // Enviar a analytics o crash reporting
    }
}
```

## 📄 Exportación y Compartir

### Exportar Transcripciones

```kotlin
class TranscriptionExporter {
    
    fun exportAndShare(
        sessionId: String,
        format: TranscriptionManager.ExportFormat,
        context: Context
    ) {
        val exported = sipLibrary.exportTranscriptionSession(sessionId, format)
        
        if (exported != null) {
            when (format) {
                TranscriptionManager.ExportFormat.TEXT -> shareAsText(exported, context)
                TranscriptionManager.ExportFormat.JSON -> saveAsFile(exported, "transcription.json", context)
                TranscriptionManager.ExportFormat.SRT -> saveAsFile(exported, "transcription.srt", context)
                TranscriptionManager.ExportFormat.VTT -> saveAsFile(exported, "transcription.vtt", context)
            }
        }
    }
    
    private fun shareAsText(content: String, context: Context) {
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, content)
            putExtra(Intent.EXTRA_SUBJECT, "Transcripción de Llamada")
        }
        
        context.startActivity(Intent.createChooser(shareIntent, "Compartir transcripción"))
    }
    
    private fun saveAsFile(content: String, filename: String, context: Context) {
        try {
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), filename)
            file.writeText(content)
            
            // Notificar al usuario
            Toast.makeText(context, "Archivo guardado: ${file.absolutePath}", Toast.LENGTH_LONG).show()
            
        } catch (e: Exception) {
            Log.e("TranscriptionExporter", "Error saving file: ${e.message}")
        }
    }
}
```

## 🎯 Casos de Uso Avanzados

### 1. **Transcripción Bilingüe**

```kotlin
fun setupBilingualTranscription() {
    // Detectar idioma automáticamente y cambiar configuración
    sipLibrary.setTranscriptionCallbacks(
        onTranscriptionResult = { result ->
            val detectedLanguage = detectLanguage(result.text)
            if (detectedLanguage != result.language) {
                // Cambiar idioma de transcripción
                sipLibrary.setTranscriptionLanguage(detectedLanguage)
            }
        }
    )
}

private fun detectLanguage(text: String): String {
    // Implementación básica de detección de idioma
    val spanishWords = listOf("el", "la", "de", "que", "y", "en", "un", "es", "se", "no")
    val englishWords = listOf("the", "of", "and", "to", "a", "in", "is", "it", "you", "that")
    
    val words = text.lowercase().split("\\s+".toRegex())
    val spanishCount = words.count { it in spanishWords }
    val englishCount = words.count { it in englishWords }
    
    return if (spanishCount > englishCount) "es-ES" else "en-US"
}
```

### 2. **Análisis de Conversación**

```kotlin
class ConversationAnalyzer {
    
    fun analyzeConversation(session: TranscriptionManager.TranscriptionSession) {
        val results = session.getFinalResults()
        
        // Análisis de participación
        val remoteWords = results.filter { it.speakerLabel == "remote" }.sumOf { 
            it.text.split("\\s+".toRegex()).size 
        }
        val localWords = results.filter { it.speakerLabel == "local" }.sumOf { 
            it.text.split("\\s+".toRegex()).size 
        }
        
        val participationRatio = if (localWords > 0) {
            remoteWords.toFloat() / localWords.toFloat()
        } else {
            Float.MAX_VALUE
        }
        
        // Análisis de velocidad de habla
        val speechDuration = session.statistics.speechDuration / 1000f // en segundos
        val wordsPerMinute = if (speechDuration > 0) {
            (session.statistics.totalWords / speechDuration) * 60
        } else {
            0f
        }
        
        // Análisis de pausas
        val silenceRatio = session.statistics.silenceDuration.toFloat() / session.getDuration()
        
        Log.d("ConversationAnalysis", """
            Análisis de Conversación:
            - Ratio de participación: $participationRatio
            - Palabras por minuto: $wordsPerMinute
            - Ratio de silencio: ${(silenceRatio * 100).toInt()}%
            - Palabras remotas: $remoteWords
            - Palabras locales: $localWords
        """.trimIndent())
    }
}
```

## 🔒 Privacidad y Seguridad

### Configuración de Privacidad

```kotlin
class TranscriptionPrivacyManager {
    
    fun setupPrivacyControls() {
        // Configuración con filtros de privacidad
        val privacyConfig = AudioTranscriptionService.TranscriptionConfig(
            isEnabled = true,
            enableProfanityFilter = true, // Filtrar contenido inapropiado
            confidenceThreshold = 0.8f, // Solo alta confianza para datos sensibles
            // No guardar resultados parciales para mayor privacidad
            enablePartialResults = false
        )
        
        sipLibrary.enableAudioTranscription(privacyConfig)
    }
    
    fun enableLocalOnlyMode() {
        // Usar solo Android Speech (sin servicios en la nube)
        val localConfig = AudioTranscriptionService.TranscriptionConfig(
            transcriptionProvider = AudioTranscriptionService.TranscriptionProvider.ANDROID_SPEECH,
            isEnabled = true
        )
        
        sipLibrary.enableAudioTranscription(localConfig)
    }
    
    fun clearSensitiveData() {
        // Limpiar historial de transcripciones
        sipLibrary.clearTranscriptionHistory()
        
        // Limpiar datos de la base de datos
        lifecycleScope.launch {
            databaseManager.cleanupOldData(daysToKeep = 0) // Eliminar todo
        }
    }
}
```

## 🎯 Conclusión

La integración de transcripción de audio en tiempo real en EddysSipLibrary proporciona:

- ✅ **Interceptación directa** del audio WebRTC antes del altavoz
- ✅ **Transcripción en tiempo real** con múltiples proveedores
- ✅ **Análisis de calidad** de audio automático
- ✅ **Persistencia completa** en base de datos
- ✅ **Exportación flexible** en múltiples formatos
- ✅ **Configuración granular** para diferentes casos de uso
- ✅ **Privacidad y seguridad** con opciones locales

Esta implementación te permite crear aplicaciones SIP con capacidades avanzadas de transcripción, análisis de conversaciones y documentación automática de llamadas.

---

**Desarrollado con ❤️ por Eddys Larez**

*¿Te gusta la nueva funcionalidad de transcripción? ¡Dale una ⭐ en GitHub!*