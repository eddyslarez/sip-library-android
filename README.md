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

<!-- Permisos para servicios en primer plano -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
```

### 2. Inicialización en Application

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Configuración personalizada
        val config = EddysSipLibrary.SipConfig(
            defaultDomain = "tu-dominio.com",
            webSocketUrl = "wss://tu-servidor:puerto/",
            userAgent = "MiApp/1.0.0",
            enableLogs = true,
            enableAutoReconnect = true,
            pingIntervalMs = 30000L
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

### Realizar Llamadas

```kotlin
// Llamada básica
sipLibrary.makeCall("1234567890")

// Llamada desde cuenta específica
sipLibrary.makeCall(
    phoneNumber = "1234567890",
    username = "usuario1",
    domain = "dominio1.com"
)
```

### Gestionar Llamadas Entrantes

```kotlin
// Aceptar llamada
sipLibrary.acceptCall()

// Rechazar llamada
sipLibrary.declineCall()

// Terminar llamada activa
sipLibrary.endCall()
```

### Controles Durante la Llamada

```kotlin
// Silenciar/desmute
sipLibrary.toggleMute()

// Verificar estado de mute
val isMuted = sipLibrary.isMuted()

// Poner en espera
sipLibrary.holdCall()

// Reanudar llamada
sipLibrary.resumeCall()

// Enviar DTMF
sipLibrary.sendDtmf('1')
sipLibrary.sendDtmfSequence("123*")
```

## 🎧 Gestión de Audio

### Obtener Dispositivos Disponibles

```kotlin
val (inputDevices, outputDevices) = sipLibrary.getAvailableAudioDevices()

inputDevices.forEach { device ->
    println("Input: ${device.name} - ${device.audioUnit.type}")
}

outputDevices.forEach { device ->
    println("Output: ${device.name} - Quality: ${device.qualityScore}")
}
```

### Cambiar Dispositivos Durante Llamada

```kotlin
// Obtener dispositivos actuales
val (currentInput, currentOutput) = sipLibrary.getCurrentAudioDevices()

// Cambiar a Bluetooth
outputDevices.forEach { device ->
    if (device.isBluetooth && device.connectionState == DeviceConnectionState.AVAILABLE) {
        sipLibrary.changeAudioDevice(device)
    }
}

// Refrescar lista de dispositivos
sipLibrary.refreshAudioDevices()
```

## 🔄 Observar Estados con Flow

### En ViewModel

```kotlin
class SipViewModel : ViewModel() {
    private val sipLibrary = EddysSipLibrary.getInstance()
    
    // Estados de llamada
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

### En Compose UI

```kotlin
@Composable
fun CallScreen(viewModel: SipViewModel) {
    val callState by viewModel.callState.collectAsState()
    val registrationStates by viewModel.registrationStates.collectAsState()
    
    Column {
        // Estado de la llamada
        CallStatusCard(
            state = callState.state,
            duration = if (callState.state == DetailedCallState.STREAMS_RUNNING) 
                calculateDuration() else 0,
            hasError = callState.hasError()
        )
        
        // Controles según el estado
        when (callState.state) {
            DetailedCallState.INCOMING_RECEIVED -> {
                IncomingCallControls(
                    onAccept = { viewModel.acceptCall() },
                    onDecline = { viewModel.declineCall() }
                )
            }
            DetailedCallState.STREAMS_RUNNING -> {
                ActiveCallControls(
                    onMute = { viewModel.toggleMute() },
                    onHold = { viewModel.holdCall() },
                    onEnd = { viewModel.endCall() }
                )
            }
        }
        
        // Estado multi-cuenta
        MultiAccountStatus(registrationStates)
    }
}
```

## 📞 Historial de Llamadas

### Obtener Historial

```kotlin
// Todas las llamadas
val allCalls = sipLibrary.getCallLogs()

// Solo llamadas perdidas
val missedCalls = sipLibrary.getMissedCalls()

// Llamadas de un número específico
val callsFromNumber = sipLibrary.getCallLogsForNumber("1234567890")

// Estadísticas
val stats = sipLibrary.getCallStatistics()
println("Total: ${stats.totalCalls}, Perdidas: ${stats.missedCalls}")
```

### Limpiar Historial

```kotlin
sipLibrary.clearCallLogs()
```

## 🔔 Notificaciones Push

### Configurar Token

```kotlin
// Actualizar token FCM
sipLibrary.updatePushToken("nuevo_token_fcm", "fcm")

// Para APNS (iOS)
sipLibrary.updatePushToken("apns_token", "apns")
```

### Registro con Push

```kotlin
sipLibrary.registerAccount(
    username = "usuario",
    password = "contraseña",
    domain = "dominio.com",
    pushToken = FirebaseMessaging.getInstance().token.result,
    pushProvider = "fcm"
)
```

## 🔧 Configuración Avanzada

### Listeners Específicos

```kotlin
// Listener solo para registro
sipLibrary.setRegistrationListener(object : EddysSipLibrary.RegistrationListener {
    override fun onRegistrationSuccessful(username: String, domain: String) {
        saveCredentials(username, domain)
    }
    
    override fun onRegistrationFailed(username: String, domain: String, error: String) {
        showError("Error registrando $username@$domain: $error")
    }
})

// Listener solo para llamadas
sipLibrary.setCallListener(object : EddysSipLibrary.CallListener {
    override fun onCallInitiated(callInfo: EddysSipLibrary.CallInfo) {
        startCallLogging(callInfo)
    }
    
    override fun onCallStateChanged(stateInfo: CallStateInfo) {
        updateCallUI(stateInfo)
    }
    
    override fun onMuteStateChanged(isMuted: Boolean, callInfo: EddysSipLibrary.CallInfo) {
        updateMuteButton(isMuted)
    }
})

// Listener solo para llamadas entrantes
sipLibrary.setIncomingCallListener(object : EddysSipLibrary.IncomingCallListener {
    override fun onIncomingCall(callInfo: EddysSipLibrary.IncomingCallInfo) {
        showFullScreenIncomingCall(callInfo)
    }
})
```

### Configuración Personalizada

```kotlin
val config = EddysSipLibrary.SipConfig(
    defaultDomain = "mi-servidor.com",
    webSocketUrl = "wss://mi-servidor.com:8443/",
    userAgent = "MiApp/2.0.0 (Android)",
    enableLogs = BuildConfig.DEBUG,
    enableAutoReconnect = true,
    pingIntervalMs = 30000L // 30 segundos
)
```

## 🔍 Diagnóstico y Debugging

### Verificar Salud del Sistema

```kotlin
// Verificar si el sistema está saludable
val isHealthy = sipLibrary.isSystemHealthy()

// Obtener reporte detallado
val healthReport = sipLibrary.getSystemHealthReport()
Log.d("SIP_HEALTH", healthReport)

// Diagnóstico de listeners
val listenerDiag = sipLibrary.diagnoseListeners()
Log.d("SIP_LISTENERS", listenerDiag)
```

### Historial de Estados

```kotlin
// Obtener historial de estados de llamada
val stateHistory = sipLibrary.getCallStateHistory()
stateHistory.forEach { state ->
    Log.d("CALL_HISTORY", "${state.timestamp}: ${state.previousState} -> ${state.state}")
}

// Limpiar historial
sipLibrary.clearCallStateHistory()
```

### Información de Audio

```kotlin
// Diagnóstico de audio
val audioDiag = sipLibrary.webRtcManager.diagnoseAudioIssues()
Log.d("AUDIO_DIAG", audioDiag)

// Estado de dispositivos
val (inputs, outputs) = sipLibrary.getAvailableAudioDevices()
Log.d("AUDIO_DEVICES", "Inputs: ${inputs.size}, Outputs: ${outputs.size}")
```

## 🌟 Características Avanzadas

### Multi-Cuenta

```kotlin
// Registrar múltiples cuentas
sipLibrary.registerAccount("trabajo", "pass1", "empresa.com")
sipLibrary.registerAccount("personal", "pass2", "proveedor.com")

// Observar estados de todas las cuentas
lifecycleScope.launch {
    sipLibrary.getRegistrationStatesFlow().collect { states ->
        states.forEach { (account, state) ->
            updateAccountUI(account, state)
        }
    }
}

// Hacer llamada desde cuenta específica
sipLibrary.makeCall(
    phoneNumber = "555-1234",
    username = "trabajo",
    domain = "empresa.com"
)
```

### Estados Detallados de Llamada

```kotlin
sipLibrary.addSipEventListener(object : EddysSipLibrary.SipEventListener {
    override fun onCallStateChanged(stateInfo: CallStateInfo) {
        when (stateInfo.state) {
            DetailedCallState.OUTGOING_INIT -> showStatus("Iniciando llamada...")
            DetailedCallState.OUTGOING_PROGRESS -> showStatus("Estableciendo conexión...")
            DetailedCallState.OUTGOING_RINGING -> {
                showStatus("Sonando...")
                startRingbackTone()
            }
            DetailedCallState.CONNECTED -> showStatus("Conectando audio...")
            DetailedCallState.STREAMS_RUNNING -> {
                showStatus("En llamada")
                startCallTimer()
            }
            DetailedCallState.PAUSING -> showStatus("Pausando...")
            DetailedCallState.PAUSED -> showStatus("En espera")
            DetailedCallState.RESUMING -> showStatus("Reanudando...")
            DetailedCallState.ENDING -> showStatus("Finalizando...")
            DetailedCallState.ENDED -> {
                showStatus("Llamada terminada")
                stopCallTimer()
            }
            DetailedCallState.ERROR -> {
                val errorMsg = SipErrorMapper.getErrorDescription(stateInfo.errorReason)
                showError("Error: $errorMsg")
                
                // Manejar errores específicos
                when (stateInfo.errorReason) {
                    CallErrorReason.BUSY -> showMessage("Línea ocupada")
                    CallErrorReason.NO_ANSWER -> showMessage("Sin respuesta")
                    CallErrorReason.NETWORK_ERROR -> checkNetworkConnection()
                    else -> showGenericError()
                }
            }
        }
    }
})
```

### Gestión Avanzada de Audio

```kotlin
class AudioManager {
    private val sipLibrary = EddysSipLibrary.getInstance()
    
    fun setupAudioPreferences() {
        // Configurar tipos de dispositivos preferidos
        val preferredTypes = setOf(
            AudioUnitTypes.BLUETOOTH,
            AudioUnitTypes.HEADSET,
            AudioUnitTypes.SPEAKER
        )
        
        // La librería seleccionará automáticamente el mejor dispositivo
        sipLibrary.audioDeviceManager.setPreferredDeviceTypes(preferredTypes)
    }
    
    fun handleBluetoothConnection() {
        val (_, outputs) = sipLibrary.getAvailableAudioDevices()
        
        outputs.filter { it.isBluetooth }.forEach { device ->
            when (device.connectionState) {
                DeviceConnectionState.CONNECTED -> {
                    if (device.signalStrength != null && device.signalStrength > 70) {
                        sipLibrary.changeAudioDevice(device)
                    }
                }
                DeviceConnectionState.LOW_BATTERY -> {
                    showWarning("Bluetooth device low battery: ${device.name}")
                }
                else -> {}
            }
        }
    }
}
```

## 🐛 Solución de Problemas

### Problemas Comunes

#### 1. Error de Permisos de Audio

```kotlin
// Verificar y solicitar permisos
if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
    != PackageManager.PERMISSION_GRANTED) {
    
    ActivityCompat.requestPermissions(this, 
        arrayOf(Manifest.permission.RECORD_AUDIO), 
        REQUEST_AUDIO_PERMISSION)
}
```

#### 2. Problemas de Conexión

```kotlin
// Verificar estado de salud
val healthReport = sipLibrary.getSystemHealthReport()
if (!sipLibrary.isSystemHealthy()) {
    Log.e("SIP_ERROR", "System unhealthy: $healthReport")
    
    // Reintentar conexión
    sipLibrary.unregisterAllAccounts()
    delay(2000)
    sipLibrary.registerAccount(username, password, domain)
}
```

#### 3. Audio No Funciona

```kotlin
// Verificar dispositivos disponibles
val (inputs, outputs) = sipLibrary.getAvailableAudioDevices()
if (inputs.isEmpty() || outputs.isEmpty()) {
    Log.e("AUDIO_ERROR", "No audio devices available")
    sipLibrary.refreshAudioDevices()
}

// Verificar dispositivos actuales
val (currentInput, currentOutput) = sipLibrary.getCurrentAudioDevices()
if (currentInput == null || currentOutput == null) {
    Log.e("AUDIO_ERROR", "No current audio devices selected")
}
```

#### 4. Llamadas No Se Conectan

```kotlin
// Verificar estado de registro
val registrationStates = sipLibrary.getAllRegistrationStates()
val hasRegisteredAccount = registrationStates.values.any { it == RegistrationState.OK }

if (!hasRegisteredAccount) {
    Log.e("CALL_ERROR", "No registered accounts available")
    // Re-registrar cuentas
}

// Verificar historial de estados
val stateHistory = sipLibrary.getCallStateHistory()
val lastError = stateHistory.lastOrNull { it.hasError() }
if (lastError != null) {
    Log.e("CALL_ERROR", "Last call error: ${lastError.errorReason}")
}
```

### Logs de Debugging

```kotlin
// Habilitar logs detallados
val config = EddysSipLibrary.SipConfig(
    enableLogs = true,
    // ... otras configuraciones
)

// Filtrar logs por tag
adb logcat | grep "EddysSipLibrary\|SipCoreManager\|WebRtcManager"
```

## 📊 Métricas y Monitoreo

### Estadísticas de Llamadas

```kotlin
val stats = sipLibrary.getCallStatistics()
println("""
    📊 Estadísticas de Llamadas:
    - Total: ${stats.totalCalls}
    - Exitosas: ${stats.successfulCalls}
    - Perdidas: ${stats.missedCalls}
    - Rechazadas: ${stats.declinedCalls}
    - Duración total: ${stats.totalDuration}s
""")
```

### Monitoreo de Red

```kotlin
sipLibrary.addSipEventListener(object : EddysSipLibrary.SipEventListener {
    override fun onNetworkStateChanged(isConnected: Boolean) {
        if (!isConnected) {
            showWarning("Conexión de red perdida")
            pauseCallsIfActive()
        } else {
            showInfo("Conexión de red restaurada")
            resumeCallsIfPaused()
        }
    }
})
```

## 🔒 Seguridad

### Mejores Prácticas

```kotlin
// 1. No hardcodear credenciales
val credentials = getEncryptedCredentials()
sipLibrary.registerAccount(
    username = credentials.username,
    password = credentials.password,
    domain = credentials.domain
)

// 2. Usar conexiones seguras
val config = EddysSipLibrary.SipConfig(
    webSocketUrl = "wss://secure-server.com:443/", // WSS, no WS
    // ...
)

// 3. Validar certificados SSL
// La librería maneja automáticamente la validación SSL

// 4. Limpiar datos sensibles al cerrar
override fun onDestroy() {
    super.onDestroy()
    sipLibrary.unregisterAllAccounts()
    sipLibrary.dispose()
}
```

## 🚀 Optimización de Rendimiento

### Configuración para Producción

```kotlin
val productionConfig = EddysSipLibrary.SipConfig(
    defaultDomain = "production-server.com",
    webSocketUrl = "wss://production-server.com:443/",
    userAgent = "MyApp/1.0.0 (Android)",
    enableLogs = false, // Deshabilitar en producción
    enableAutoReconnect = true,
    pingIntervalMs = 60000L // Ping cada minuto
)
```

### Gestión de Memoria

```kotlin
class SipService : Service() {
    override fun onDestroy() {
        super.onDestroy()
        
        // Limpiar recursos
        sipLibrary.clearCallLogs()
        sipLibrary.clearCallStateHistory()
        sipLibrary.unregisterAllAccounts()
    }
}
```

## 📄 Licencia

```
MIT License

Copyright (c) 2024 Eddys Larez

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## 🤝 Contribución

Las contribuciones son bienvenidas. Por favor:

1. Fork el proyecto
2. Crea una rama para tu feature (`git checkout -b feature/nueva-caracteristica`)
3. Commit tus cambios (`git commit -am 'Agregar nueva característica'`)
4. Push a la rama (`git push origin feature/nueva-caracteristica`)
5. Abre un Pull Request

### Guías de Contribución

- Sigue las convenciones de código Kotlin
- Agrega tests para nuevas funcionalidades
- Actualiza la documentación
- Asegúrate de que todos los tests pasen

## 📞 Soporte

Para soporte técnico o preguntas:

- **GitHub Issues**: [Reportar un problema](https://github.com/eddyslarez/sip-library/issues)
- **Email**: eddyslarez@example.com
- **Documentación**: [Wiki del proyecto](https://github.com/eddyslarez/sip-library/wiki)

## 🔄 Changelog

### v1.4.0 (Actual)
- ✅ **OPTIMIZADO**: Estados de llamada unificados
- ✅ **ELIMINADO**: Duplicación de `onCallStateChanged`
- ✅ **MEJORADO**: API más simple y clara
- ✅ **AÑADIDO**: Mejor diagnóstico de sistema
- ✅ **OPTIMIZADO**: Rendimiento de notificaciones

### v1.3.0
- ✅ Estados detallados de llamada
- ✅ Soporte multi-cuenta mejorado
- ✅ Gestión avanzada de audio
- ✅ Sistema de diagnóstico

### v1.2.0
- ✅ Soporte multi-cuenta
- ✅ Gestión de dispositivos de audio
- ✅ Notificaciones push
- ✅ Historial de llamadas

### v1.1.0
- ✅ Estados reactivos con Flow
- ✅ Soporte para DTMF
- ✅ Hold/Resume de llamadas
- ✅ Reconexión automática

### v1.0.0
- ✅ Lanzamiento inicial
- ✅ Soporte completo para SIP/WebRTC
- ✅ Llamadas entrantes y salientes
- ✅ Gestión básica de audio

---

**Desarrollado con ❤️ por Eddys Larez**

*¿Te gusta la librería? ¡Dale una ⭐ en GitHub!*