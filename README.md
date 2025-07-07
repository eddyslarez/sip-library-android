# 📞 EddysSipLibrary - Biblioteca SIP/VoIP para Android

Una biblioteca SIP/VoIP completa y moderna para Android desarrollada por **Eddys Larez**, que proporciona funcionalidades avanzadas para realizar y recibir llamadas SIP usando WebRTC y WebSocket con soporte multi-cuenta.

[![Version](https://img.shields.io/badge/version-1.4.0-blue.svg)](https://github.com/eddyslarez/sip-library)
[![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=24)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

## 🚀 Características Principales

### ✅ **Funcionalidades Core**
- 📱 Llamadas SIP entrantes y salientes
- 🌐 Soporte completo para WebRTC
- 🔌 Conexión WebSocket robusta con reconexión automática
- 🎯 Soporte multi-cuenta simultáneo
- 🔊 Gestión avanzada de dispositivos de audio (altavoz, auriculares, Bluetooth)
- 📋 Historial completo de llamadas
- 🔔 Notificaciones push integradas
- 🎛️ Control DTMF durante llamadas

### ✅ **Arquitectura Moderna**
- 🏗️ Estados de llamada unificados y detallados
- 🌊 Reactive Streams con Kotlin Flow
- 🎨 Compatible con Jetpack Compose
- 🔄 Reconexión automática inteligente
- 📊 Sistema de diagnóstico integrado
- 🛡️ Manejo robusto de errores

### ✅ **Gestión de Audio**
- 🎧 Detección automática de dispositivos
- 📻 Cambio dinámico de dispositivos durante llamadas
- 🔇 Control de mute/unmute
- ⏸️ Funciones de hold/resume
- 🔊 Soporte para audio HD

### ✅ **Configuración Completa**
- 🎵 Control total de ringtones (activar/desactivar, personalizar)
- 📳 Configuración de vibración personalizable
- 🔔 Control de notificaciones push
- 🎚️ Ajustes de volumen y calidad de audio
- 🔧 Más de 50 opciones de configuración

## 📱 Instalación

### Usando JitPack (Recomendado)

1. **Agrega JitPack** en tu `settings.gradle.kts` (nivel proyecto):

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

2. **Agrega la dependencia** en tu `build.gradle.kts` (nivel app):

```kotlin
dependencies {
    implementation("com.github.eddyslarez:sip-library:1.4.0")
}
```

### Desde GitHub

```bash
git clone https://github.com/eddyslarez/sip-library.git
```

## 🛠️ Configuración Inicial

### 1. Permisos Requeridos

Agrega estos permisos en tu `AndroidManifest.xml`:

```xml
<!-- Permisos básicos para VoIP -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

<!-- Permisos para Bluetooth -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<!-- Permisos para notificaciones -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- Permisos para vibración -->
<uses-permission android:name="android.permission.VIBRATE" />

<!-- Permisos para servicios en primer plano -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
```

### 2. Configuración Completa en Application

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Configuración completa personalizada
        val config = EddysSipLibrary.SipConfig(
            // === CONFIGURACIÓN BÁSICA ===
            defaultDomain = "tu-dominio.com",
            webSocketUrl = "wss://tu-servidor:puerto/",
            userAgent = "MiApp/1.0.0",
            
            // === CONFIGURACIÓN DE LOGS ===
            enableLogs = BuildConfig.DEBUG,
            logLevel = EddysSipLibrary.LogLevel.DEBUG,
            
            // === CONFIGURACIÓN DE CONEXIÓN ===
            enableAutoReconnect = true,
            maxReconnectAttempts = 5,
            reconnectDelayMs = 2000L,
            pingIntervalMs = 30000L,
            
            // === CONFIGURACIÓN DE PUSH NOTIFICATIONS ===
            enablePushNotifications = true,
            defaultPushProvider = "fcm",
            
            // === CONFIGURACIÓN DE AUDIO ===
            enableAudioProcessing = true,
            enableEchoCancellation = true,
            enableNoiseSuppression = true,
            audioSampleRate = 48000,
            preferredAudioCodec = EddysSipLibrary.AudioCodec.OPUS,
            
            // === CONFIGURACIÓN DE RINGTONES ===
            enableIncomingRingtone = true,
            enableOutgoingRingtone = true,
            ringtoneVolume = 0.8f,
            enableVibration = true,
            vibrationPattern = longArrayOf(0, 1000, 500, 1000),
            
            // === CONFIGURACIÓN DE LLAMADAS ===
            enableDTMF = true,
            dtmfToneDuration = 160,
            enableCallHold = true,
            enableCallHistory = true,
            maxCallHistoryEntries = 1000,
            
            // === CONFIGURACIÓN DE DISPOSITIVOS DE AUDIO ===
            enableBluetoothAudio = true,
            enableWiredHeadsetAudio = true,
            autoSwitchToBluetoothWhenConnected = true,
            preferredAudioRoute = EddysSipLibrary.AudioRoute.AUTO,
            
            // === CONFIGURACIÓN DE SEGURIDAD ===
            enableTLS = true,
            enableSRTP = true,
            tlsVersion = EddysSipLibrary.TLSVersion.TLS_1_2,
            
            // === CONFIGURACIÓN DE INTERFAZ ===
            enableFullScreenIncomingCall = true,
            enableCallNotifications = true,
            enableMissedCallNotifications = true,
            
            // === CONFIGURACIÓN DE RENDIMIENTO ===
            enableBatteryOptimization = true,
            maxConcurrentCalls = 1,
            
            // === CONFIGURACIÓN DE DEBUGGING ===
            enableDiagnosticMode = BuildConfig.DEBUG
        )
        
        // Inicializar la biblioteca
        EddysSipLibrary.getInstance().initialize(this, config)
    }
}
```

## 📋 Uso Básico

### Configurar Listeners

```kotlin
class MainActivity : ComponentActivity() {
    private lateinit var sipLibrary: EddysSipLibrary
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        sipLibrary = EddysSipLibrary.getInstance()
        
        // Listener principal para todos los eventos
        sipLibrary.addSipEventListener(object : EddysSipLibrary.SipEventListener {
            override fun onRegistrationStateChanged(
                state: RegistrationState, 
                username: String, 
                domain: String
            ) {
                when (state) {
                    RegistrationState.OK -> showMessage("✅ Registrado: $username@$domain")
                    RegistrationState.FAILED -> showMessage("❌ Error de registro")
                    else -> showMessage("🔄 Estado: $state")
                }
            }
            
            override fun onCallStateChanged(stateInfo: CallStateInfo) {
                when (stateInfo.state) {
                    DetailedCallState.INCOMING_RECEIVED -> {
                        showMessage("📞 Llamada entrante")
                        showIncomingCallUI()
                    }
                    DetailedCallState.OUTGOING_RINGING -> {
                        showMessage("📱 Sonando...")
                    }
                    DetailedCallState.STREAMS_RUNNING -> {
                        showMessage("🟢 Llamada conectada")
                        showInCallUI()
                    }
                    DetailedCallState.ENDED -> {
                        showMessage("📴 Llamada terminada")
                        showMainUI()
                    }
                    DetailedCallState.ERROR -> {
                        val error = SipErrorMapper.getErrorDescription(stateInfo.errorReason)
                        showMessage("❌ Error: $error")
                    }
                }
            }
            
            override fun onIncomingCall(callInfo: EddysSipLibrary.IncomingCallInfo) {
                showNotification("Llamada de ${callInfo.callerNumber}")
            }
            
            override fun onCallQualityChanged(quality: EddysSipLibrary.CallQuality) {
                updateQualityIndicator(quality.overallScore)
            }
        })
    }
}
```

### Registro de Cuenta SIP

```kotlin
// Registro básico
sipLibrary.registerAccount(
    username = "usuario",
    password = "contraseña",
    domain = "mi-dominio.com"
)

// Registro con notificaciones push
sipLibrary.registerAccount(
    username = "usuario",
    password = "contraseña", 
    domain = "mi-dominio.com",
    pushToken = "token_fcm",
    pushProvider = "fcm"
)

// Registro de múltiples cuentas
sipLibrary.registerAccount("usuario1", "pass1", "dominio1.com")
sipLibrary.registerAccount("usuario2", "pass2", "dominio2.com")
```

## 🎵 Configuración de Ringtones

### Configuración Básica

```kotlin
// En la configuración inicial
val config = EddysSipLibrary.SipConfig(
    // Activar/desactivar ringtones
    enableIncomingRingtone = true,
    enableOutgoingRingtone = true,
    
    // Control de volumen (0.0 - 1.0)
    ringtoneVolume = 0.8f,
    
    // Vibración
    enableVibration = true,
    vibrationPattern = longArrayOf(0, 1000, 500, 1000), // personalizable
    
    // Ringtones personalizados
    incomingRingtoneUri = Uri.parse("content://media/internal/audio/media/123"),
    outgoingRingtoneUri = Uri.parse("content://media/internal/audio/media/456")
)
```

### Cambiar Configuración en Tiempo Real

```kotlin
// Actualizar configuración durante la ejecución
sipLibrary.updateRuntimeConfig(
    enableIncomingRingtone = false,  // Desactivar ringtone entrante
    enableOutgoingRingtone = true,   // Mantener ringtone saliente
    ringtoneVolume = 0.5f,           // Reducir volumen
    enableVibration = false          // Desactivar vibración
)
```

### Ringtones Personalizados

```kotlin
// Seleccionar ringtone desde galería
private fun selectCustomRingtone() {
    val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE)
        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Seleccionar ringtone")
    }
    startActivityForResult(intent, REQUEST_RINGTONE)
}

override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    
    if (requestCode == REQUEST_RINGTONE && resultCode == RESULT_OK) {
        val uri = data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        uri?.let {
            // Actualizar configuración con nuevo ringtone
            val newConfig = sipLibrary.getCurrentConfig().copy(
                incomingRingtoneUri = it
            )
            sipLibrary.updateRuntimeConfig(/* parámetros actualizados */)
        }
    }
}
```

## 🔔 Configuración de Notificaciones Push

### Configuración Completa

```kotlin
val config = EddysSipLibrary.SipConfig(
    // Activar/desactivar push notifications
    enablePushNotifications = true,
    
    // Proveedor por defecto
    defaultPushProvider = "fcm", // fcm, apns, custom
    
    // Timeout para push
    pushTimeoutMs = 30000L,
    
    // Wake up desde push
    enablePushWakeup = true
)
```

### Integración con Firebase

```kotlin
class MyFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        
        // Actualizar token en la biblioteca SIP
        EddysSipLibrary.getInstance().updatePushToken(token, "fcm")
    }
    
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        
        // Manejar notificación de llamada entrante
        if (remoteMessage.data["type"] == "incoming_call") {
            handleIncomingCallPush(remoteMessage.data)
        }
    }
}
```

## 🎧 Gestión Avanzada de Audio

### Configuración de Audio

```kotlin
val config = EddysSipLibrary.SipConfig(
    // Procesamiento de audio
    enableAudioProcessing = true,
    enableEchoCancellation = true,
    enableNoiseSuppression = true,
    enableAutoGainControl = true,
    
    // Calidad de audio
    audioSampleRate = 48000,
    audioChannels = EddysSipLibrary.AudioChannels.MONO,
    preferredAudioCodec = EddysSipLibrary.AudioCodec.OPUS,
    enableHDAudio = true,
    
    // Dispositivos de audio
    enableBluetoothAudio = true,
    enableWiredHeadsetAudio = true,
    enableSpeakerAudio = true,
    autoSwitchToBluetoothWhenConnected = true,
    preferredAudioRoute = EddysSipLibrary.AudioRoute.AUTO
)
```

### Gestión de Dispositivos

```kotlin
// Obtener dispositivos disponibles
val (inputDevices, outputDevices) = sipLibrary.getAvailableAudioDevices()

// Cambiar a Bluetooth automáticamente
outputDevices.forEach { device ->
    if (device.isBluetooth && device.connectionState == DeviceConnectionState.AVAILABLE) {
        sipLibrary.changeAudioDevice(device)
        showMessage("Cambiado a: ${device.name}")
    }
}

// Monitorear cambios de dispositivos
sipLibrary.addSipEventListener(object : EddysSipLibrary.SipEventListener {
    override fun onAudioDeviceChanged(device: AudioDevice) {
        showMessage("Dispositivo de audio: ${device.name}")
        updateAudioDeviceUI(device)
    }
})
```

## 🔧 Configuración de Llamadas

### Configuración DTMF

```kotlin
val config = EddysSipLibrary.SipConfig(
    // Activar DTMF
    enableDTMF = true,
    
    // Duración de tonos DTMF (milisegundos)
    dtmfToneDuration = 160,
    
    // Pausa entre tonos
    dtmfToneGap = 70
)

// Usar DTMF
sipLibrary.sendDtmf('1')
sipLibrary.sendDtmfSequence("123*456#")
```

### Configuración de Hold/Resume

```kotlin
val config = EddysSipLibrary.SipConfig(
    // Activar funciones de hold
    enableCallHold = true,
    
    // Transferencia de llamadas
    enableCallTransfer = true,
    
    // Llamadas en conferencia
    enableConferenceCall = false
)
```

### Límites y Restricciones

```kotlin
val config = EddysSipLibrary.SipConfig(
    // Máximo de llamadas concurrentes
    maxConcurrentCalls = 1,
    
    // Duración máxima de llamada (0 = sin límite)
    maxCallDuration = 3600000L, // 1 hora en milisegundos
    
    // Grabación de llamadas
    enableCallRecording = false
)
```

## 🔒 Configuración de Seguridad

### Configuración TLS/SRTP

```kotlin
val config = EddysSipLibrary.SipConfig(
    // Seguridad de transporte
    enableTLS = true,
    tlsVersion = EddysSipLibrary.TLSVersion.TLS_1_2,
    certificateValidation = EddysSipLibrary.CertificateValidation.STRICT,
    
    // Seguridad de media
    enableSRTP = true,
    
    // Autenticación
    enableDigestAuthentication = true
)
```

## 📊 Configuración de Rendimiento

### Optimización de Batería y Red

```kotlin
val config = EddysSipLibrary.SipConfig(
    // Optimizaciones
    enableBatteryOptimization = true,
    enableNetworkOptimization = true,
    enableCpuOptimization = true,
    
    // Monitoreo de calidad
    enableCallQualityMonitoring = true,
    
    // Intervalos de conexión
    pingIntervalMs = 30000L,
    keepAliveIntervalMs = 25000L
)
```

### Configuración de Reconexión

```kotlin
val config = EddysSipLibrary.SipConfig(
    // Reconexión automática
    enableAutoReconnect = true,
    maxReconnectAttempts = 5,
    reconnectDelayMs = 2000L,
    connectionTimeoutMs = 30000L
)
```

## 🐛 Configuración de Debugging

### Logs y Diagnósticos

```kotlin
val config = EddysSipLibrary.SipConfig(
    // Configuración de logs
    enableLogs = BuildConfig.DEBUG,
    logLevel = EddysSipLibrary.LogLevel.DEBUG,
    enableFileLogging = false,
    maxLogFileSize = 10 * 1024 * 1024, // 10MB
    
    // Diagnósticos
    enableDiagnosticMode = BuildConfig.DEBUG,
    enableNetworkDiagnostics = false,
    enableAudioDiagnostics = false,
    enablePerformanceMetrics = false,
    diagnosticReportIntervalMs = 60000L
)
```

### Obtener Información de Diagnóstico

```kotlin
// Reporte completo del sistema
val healthReport = sipLibrary.getSystemHealthReport()
Log.d("SIP_HEALTH", healthReport)

// Diagnóstico de listeners
val listenerDiag = sipLibrary.diagnoseListeners()
Log.d("SIP_LISTENERS", listenerDiag)

// Configuración actual
val currentConfig = sipLibrary.getCurrentConfig()
Log.d("SIP_CONFIG", "Push enabled: ${currentConfig.enablePushNotifications}")
```

## 🔄 Observar Estados con Flow

### En ViewModel

```kotlin
class SipViewModel : ViewModel() {
    private val sipLibrary = EddysSipLibrary.getInstance()
    
    // Estados de llamada unificados
    val callState: StateFlow<CallStateInfo> = sipLibrary.getCallStateFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 
            CallStateInfo(
                state = DetailedCallState.IDLE,
                previousState = null,
                timestamp = System.currentTimeMillis()
            )
        )
    
    // Estados de registro multi-cuenta
    val registrationStates: StateFlow<Map<String, RegistrationState>> = 
        sipLibrary.getRegistrationStatesFlow()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    
    // Observar cambios
    init {
        viewModelScope.launch {
            callState.collect { stateInfo ->
                when (stateInfo.state) {
                    DetailedCallState.STREAMS_RUNNING -> startCallTimer()
                    DetailedCallState.ENDED -> stopCallTimer()
                    else -> {}
                }
            }
        }
    }
}
```

## 🌟 Características Experimentales

### Funciones Beta

```kotlin
val config = EddysSipLibrary.SipConfig(
    // Activar funciones experimentales
    enableExperimentalFeatures = true,
    
    // Funciones beta
    enableVideoCall = false,
    enableScreenSharing = false,
    enableChatMessaging = false,
    enablePresenceStatus = false
)
```

## 📄 Configuración Completa de Ejemplo

```kotlin
// Configuración completa para producción
val productionConfig = EddysSipLibrary.SipConfig(
    // Básico
    defaultDomain = "production-server.com",
    webSocketUrl = "wss://production-server.com:443/",
    userAgent = "MyApp/1.0.0 (Android)",
    
    // Logs (deshabilitados en producción)
    enableLogs = false,
    logLevel = EddysSipLibrary.LogLevel.ERROR,
    
    // Conexión optimizada
    enableAutoReconnect = true,
    maxReconnectAttempts = 3,
    reconnectDelayMs = 5000L,
    pingIntervalMs = 60000L,
    
    // Push notifications
    enablePushNotifications = true,
    defaultPushProvider = "fcm",
    
    // Audio optimizado
    enableAudioProcessing = true,
    enableEchoCancellation = true,
    enableNoiseSuppression = true,
    audioSampleRate = 48000,
    preferredAudioCodec = EddysSipLibrary.AudioCodec.OPUS,
    
    // Ringtones configurables
    enableIncomingRingtone = true,
    enableOutgoingRingtone = true,
    ringtoneVolume = 0.8f,
    enableVibration = true,
    
    // Llamadas
    enableDTMF = true,
    enableCallHold = true,
    enableCallHistory = true,
    maxCallHistoryEntries = 500,
    
    // Audio devices
    enableBluetoothAudio = true,
    autoSwitchToBluetoothWhenConnected = true,
    
    // Seguridad
    enableTLS = true,
    enableSRTP = true,
    tlsVersion = EddysSipLibrary.TLSVersion.TLS_1_2,
    
    // UI
    enableFullScreenIncomingCall = true,
    enableCallNotifications = true,
    
    // Rendimiento
    enableBatteryOptimization = true,
    maxConcurrentCalls = 1,
    
    // Sin debugging en producción
    enableDiagnosticMode = false
)
```

## 🔄 Changelog

### v1.4.0 (Actual)
- ✅ **NUEVO**: Configuración completa con más de 50 opciones
- ✅ **NUEVO**: Control total de ringtones y vibración
- ✅ **NUEVO**: Configuración de push notifications
- ✅ **NUEVO**: Configuración avanzada de audio
- ✅ **NUEVO**: Configuración de seguridad TLS/SRTP
- ✅ **NUEVO**: Configuración de rendimiento y batería
- ✅ **NUEVO**: Configuración de debugging y diagnósticos
- ✅ **OPTIMIZADO**: Estados de llamada unificados
- ✅ **MEJORADO**: API más simple y clara
- ✅ **AÑADIDO**: Actualización de configuración en tiempo real

### v1.3.0
- ✅ Estados detallados de llamada
- ✅ Soporte multi-cuenta mejorado
- ✅ Gestión avanzada de audio
- ✅ Sistema de diagnóstico

---

**Desarrollado con ❤️ por Eddys Larez**

*¿Te gusta la librería? ¡Dale una ⭐ en GitHub!*