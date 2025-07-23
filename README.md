# EddysSipLibrary - Biblioteca SIP/VoIP para Android

Una biblioteca completa de SIP/VoIP para Android con soporte para audio virtual, transcripción en tiempo real y gestión avanzada de dispositivos de audio.

## 🚀 Características Principales

- ✅ **Llamadas SIP/VoIP** completas con WebRTC
- ✅ **Audio Virtual** - Inyecta audio personalizado en lugar del micrófono
- ✅ **Transcripción en tiempo real** del audio remoto recibido
- ✅ **Gestión avanzada de dispositivos de audio** (Bluetooth, USB, auriculares)
- ✅ **Múltiples cuentas SIP** simultáneas
- ✅ **Estados de llamada optimizados** con StateFlow
- ✅ **Soporte para DTMF** durante llamadas
- ✅ **Historial de llamadas** completo
- ✅ **Reconexión automática** y gestión de errores

## 📦 Instalación

```kotlin
// En tu build.gradle (Module: app)
dependencies {
    implementation 'com.eddyslarez:sip-library:1.5.0'
}
```

## 🔧 Configuración Inicial

### 1. Inicializar la biblioteca

```kotlin
class MainActivity : AppCompatActivity() {
    private lateinit var sipLibrary: EddysSipLibrary
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Configuración
        val config = EddysSipLibrary.SipConfig(
            defaultDomain = "tu-dominio.com",
            webSocketUrl = "wss://tu-servidor-sip.com/ws",
            userAgent = "MiApp/1.0",
            enableLogs = true,
            enableAutoReconnect = true,
            pingIntervalMs = 30000L
        )
        
        // Inicializar
        sipLibrary = EddysSipLibrary.getInstance()
        sipLibrary.initialize(application, config)
    }
}
```

### 2. Configurar Listeners

```kotlin
// Listener principal para todos los eventos
sipLibrary.addSipEventListener(object : EddysSipLibrary.SipEventListener {
    override fun onRegistrationStateChanged(state: RegistrationState, username: String, domain: String) {
        when (state) {
            RegistrationState.OK -> println("✅ Registrado: $username@$domain")
            RegistrationState.FAILED -> println("❌ Fallo registro: $username@$domain")
            RegistrationState.IN_PROGRESS -> println("⏳ Registrando: $username@$domain")
            else -> println("📱 Estado: $state para $username@$domain")
        }
    }
    
    override fun onCallStateChanged(stateInfo: CallStateInfo) {
        println("📞 Estado llamada: ${stateInfo.state}")
    }
    
    override fun onIncomingCall(callInfo: IncomingCallInfo) {
        println("📞 Llamada entrante de: ${callInfo.callerNumber}")
        // Mostrar UI de llamada entrante
        showIncomingCallUI(callInfo)
    }
    
    override fun onCallConnected(callInfo: CallInfo) {
        println("✅ Llamada conectada con: ${callInfo.phoneNumber}")
    }
    
    override fun onCallEnded(callInfo: CallInfo, reason: CallEndReason) {
        println("📞 Llamada terminada. Razón: $reason")
    }
    
    override fun onRemoteAudioTranscribed(transcribedText: String, callInfo: CallInfo?) {
        println("🎤 Audio transcrito: $transcribedText")
        // Procesar el texto transcrito
        processTranscribedText(transcribedText)
    }
})

// Listener específico para llamadas
sipLibrary.setCallListener(object : EddysSipLibrary.CallListener {
    override fun onCallInitiated(callInfo: CallInfo) {
        println("📞 Iniciando llamada a: ${callInfo.phoneNumber}")
    }
    
    override fun onCallRinging(callInfo: CallInfo) {
        println("📞 Llamada sonando...")
    }
    
    override fun onCallConnected(callInfo: CallInfo) {
        println("✅ Llamada conectada")
        // Habilitar audio virtual si es necesario
        sipLibrary.enableVirtualAudio(true)
        sipLibrary.startRemoteAudioTranscription()
    }
    
    override fun onMuteStateChanged(isMuted: Boolean, callInfo: CallInfo) {
        println("🔇 Micrófono ${if (isMuted) "silenciado" else "activado"}")
    }
    
    override fun onRemoteAudioTranscribed(transcribedText: String, callInfo: CallInfo) {
        // Procesar transcripción específica de esta llamada
        handleCallTranscription(transcribedText, callInfo)
    }
})
```

## 📱 Uso Básico

### Registro de Cuenta SIP

```kotlin
// Registrar una cuenta
sipLibrary.registerAccount(
    username = "usuario123",
    password = "mi_password",
    domain = "mi-dominio.com",
    pushToken = "fcm_token_opcional"
)

// Verificar estado de registro
val state = sipLibrary.getRegistrationState("usuario123", "mi-dominio.com")
println("Estado: $state")

// Observar cambios de estado con Flow
lifecycleScope.launch {
    sipLibrary.getRegistrationStatesFlow().collect { states ->
        states.forEach { (account, state) ->
            println("$account -> $state")
        }
    }
}
```

### Realizar Llamadas

```kotlin
// Hacer una llamada
sipLibrary.makeCall(
    phoneNumber = "+1234567890",
    username = "usuario123",  // opcional
    domain = "mi-dominio.com" // opcional
)

// Verificar si hay llamada activa
if (sipLibrary.hasActiveCall()) {
    println("Hay una llamada activa")
}

// Obtener información de la llamada actual
val callInfo = sipLibrary.getCurrentCallInfo()
callInfo?.let {
    println("Llamando a: ${it.phoneNumber}")
    println("Duración: ${it.duration}ms")
    println("Estado: ${it.state}")
}
```

### Gestionar Llamadas Entrantes

```kotlin
// En el listener de llamadas entrantes
override fun onIncomingCall(callInfo: IncomingCallInfo) {
    // Mostrar UI de llamada entrante
    showIncomingCallDialog(
        callerNumber = callInfo.callerNumber,
        callerName = callInfo.callerName,
        onAccept = { 
            sipLibrary.acceptCall(callInfo.callId)
        },
        onDecline = { 
            sipLibrary.declineCall(callInfo.callId)
        }
    )
}

// Aceptar llamada
sipLibrary.acceptCall() // Para llamada única
// o
sipLibrary.acceptCall("call_id_específico") // Para múltiples llamadas

// Rechazar llamada
sipLibrary.declineCall()

// Terminar llamada
sipLibrary.endCall()
```

## 🎤 Audio Virtual y Transcripción

### Habilitar Audio Virtual

```kotlin
// Habilitar procesamiento de audio virtual
sipLibrary.enableVirtualAudio(true)

// Iniciar transcripción del audio remoto
sipLibrary.startRemoteAudioTranscription()
```

### Inyectar Audio Personalizado

```kotlin
// Cargar audio desde archivo (necesitas implementar la carga)
val audioData = loadAudioFromFile("mi_audio.wav") // Tu implementación

// Inyectar audio en lugar del micrófono
sipLibrary.injectCustomAudio(audioData, 16000)

// Reproducir audio personalizado en lugar del remoto
sipLibrary.playCustomAudio(audioData, 16000)
```

### Procesar Transcripciones

```kotlin
// Listener para transcripciones
sipLibrary.setVirtualAudioListener(object : EddysSipLibrary.VirtualAudioListener {
    override fun onRemoteAudioTranscribed(transcribedText: String, callInfo: CallInfo?) {
        println("🎤 Transcripción: $transcribedText")
        
        // Procesar el texto y generar respuesta
        val response = processUserSpeech(transcribedText)
        
        // Convertir respuesta a audio y reproducir
        val responseAudio = textToSpeech(response)
        sipLibrary.playCustomAudio(responseAudio)
    }
    
    override fun onAudioLevelChanged(level: Float, callInfo: CallInfo?) {
        // Mostrar nivel de audio en UI
        updateAudioLevelIndicator(level)
    }
    
    override fun onVirtualAudioError(error: String) {
        println("❌ Error audio virtual: $error")
    }
})
```

## 🔊 Gestión de Dispositivos de Audio

### Obtener Dispositivos Disponibles

```kotlin
// Obtener todos los dispositivos
val (inputDevices, outputDevices) = sipLibrary.getAvailableAudioDevices()

println("Dispositivos de entrada:")
inputDevices.forEach { device ->
    println("- ${device.name} (${device.audioUnit.type})")
}

println("Dispositivos de salida:")
outputDevices.forEach { device ->
    println("- ${device.name} (${device.audioUnit.type})")
}
```

### Cambiar Dispositivos Durante Llamada

```kotlin
// Cambiar a Bluetooth
val bluetoothDevice = outputDevices.find { it.isBluetooth }
bluetoothDevice?.let { device ->
    sipLibrary.changeAudioDevice(device)
}

// Cambiar a altavoz
val speakerDevice = outputDevices.find { it.audioUnit.type == AudioUnitTypes.SPEAKER }
speakerDevice?.let { device ->
    sipLibrary.changeAudioDevice(device)
}

// Obtener dispositivos actuales
val (currentInput, currentOutput) = sipLibrary.getCurrentAudioDevices()
println("Entrada actual: ${currentInput?.name}")
println("Salida actual: ${currentOutput?.name}")
```

## 📞 Funciones de Llamada Avanzadas

### Control de Llamada

```kotlin
// Silenciar/activar micrófono
sipLibrary.toggleMute()

// Verificar si está silenciado
val isMuted = sipLibrary.isMuted()

// Poner en espera
sipLibrary.holdCall()

// Reanudar llamada
sipLibrary.resumeCall()

// Enviar tonos DTMF
sipLibrary.sendDtmf('1')
sipLibrary.sendDtmfSequence("123*456#")
```

### Múltiples Llamadas

```kotlin
// Obtener todas las llamadas activas
val allCalls = sipLibrary.getAllCalls()
allCalls.forEach { call ->
    println("Llamada ${call.callId}: ${call.phoneNumber} - ${call.state}")
}

// Gestionar llamada específica
val specificCall = allCalls.find { it.phoneNumber == "+1234567890" }
specificCall?.let { call ->
    sipLibrary.holdCall(call.callId)
    sipLibrary.resumeCall(call.callId)
    sipLibrary.endCall(call.callId)
}

// Limpiar llamadas terminadas
sipLibrary.cleanupTerminatedCalls()
```

## 📊 Historial y Estadísticas

### Historial de Llamadas

```kotlin
// Obtener todas las llamadas
val callLogs = sipLibrary.getCallLogs()
callLogs.forEach { log ->
    println("${log.phoneNumber} - ${log.callType} - ${log.duration}s")
}

// Obtener llamadas perdidas
val missedCalls = sipLibrary.getMissedCalls()
println("Llamadas perdidas: ${missedCalls.size}")

// Obtener llamadas de un número específico
val callsFromNumber = sipLibrary.getCallLogsForNumber("+1234567890")

// Limpiar historial
sipLibrary.clearCallLogs()
```

### Diagnósticos del Sistema

```kotlin
// Verificar salud del sistema
val isHealthy = sipLibrary.isSystemHealthy()
println("Sistema saludable: $isHealthy")

// Obtener reporte completo
val healthReport = sipLibrary.getSystemHealthReport()
println(healthReport)

// Diagnóstico de listeners
val listenersInfo = sipLibrary.diagnoseListeners()
println(listenersInfo)
```

## 🔄 Observar Estados con Flow

### Estados de Llamada

```kotlin
// Observar cambios de estado de llamada
lifecycleScope.launch {
    sipLibrary.getCallStateFlow().collect { stateInfo ->
        when (stateInfo.state) {
            CallState.OUTGOING_INIT -> showCallingUI()
            CallState.OUTGOING_RINGING -> showRingingUI()
            CallState.CONNECTED -> showConnectedUI()
            CallState.ENDED -> hideCallUI()
            CallState.ERROR -> showErrorUI(stateInfo.errorReason)
        }
    }
}

// Obtener historial de estados
val stateHistory = sipLibrary.getCallStateHistory()
stateHistory.forEach { state ->
    println("${state.timestamp}: ${state.previousState} -> ${state.state}")
}
```

## 🛠️ Configuración Avanzada

### Configuración Personalizada

```kotlin
val advancedConfig = EddysSipLibrary.SipConfig(
    defaultDomain = "mi-dominio.com",
    webSocketUrl = "wss://sip.mi-dominio.com:8089/ws",
    userAgent = "MiApp/1.0 (Android)",
    enableLogs = BuildConfig.DEBUG,
    enableAutoReconnect = true,
    pingIntervalMs = 30000L,
    incomingRingtoneUri = Uri.parse("android.resource://com.miapp/raw/ringtone"),
    outgoingRingtoneUri = Uri.parse("android.resource://com.miapp/raw/ringback")
)

sipLibrary.initialize(application, advancedConfig)
```

### Gestión de Tokens Push

```kotlin
// Actualizar token FCM
FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
    if (task.isSuccessful) {
        val token = task.result
        sipLibrary.updatePushToken(token, "fcm")
    }
}
```

## 🧹 Limpieza y Disposición

```kotlin
override fun onDestroy() {
    super.onDestroy()
    
    // Detener transcripción
    sipLibrary.stopRemoteAudioTranscription()
    
    // Deshabilitar audio virtual
    sipLibrary.enableVirtualAudio(false)
    
    // Desregistrar todas las cuentas
    sipLibrary.unregisterAllAccounts()
    
    // Limpiar recursos
    sipLibrary.dispose()
}
```

## 📋 Permisos Requeridos

Añade estos permisos en tu `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

## 🎯 Casos de Uso Comunes

### Bot de Voz Automatizado

```kotlin
class VoiceBotManager {
    private val sipLibrary = EddysSipLibrary.getInstance()
    
    fun setupVoiceBot() {
        sipLibrary.enableVirtualAudio(true)
        
        sipLibrary.setVirtualAudioListener(object : EddysSipLibrary.VirtualAudioListener {
            override fun onRemoteAudioTranscribed(transcribedText: String, callInfo: CallInfo?) {
                // Procesar comando de voz
                val response = processVoiceCommand(transcribedText)
                
                // Generar respuesta de audio
                val audioResponse = generateAudioResponse(response)
                
                // Reproducir respuesta
                sipLibrary.playCustomAudio(audioResponse)
            }
        })
    }
    
    private fun processVoiceCommand(text: String): String {
        return when {
            text.contains("saldo", ignoreCase = true) -> "Su saldo actual es de $1,500 pesos"
            text.contains("horario", ignoreCase = true) -> "Nuestro horario es de 9 AM a 6 PM"
            else -> "No entendí su solicitud, ¿puede repetir?"
        }
    }
}
```

### Sistema de Grabación y Análisis

```kotlin
class CallAnalyzer {
    private val transcriptions = mutableListOf<String>()
    
    fun startAnalysis() {
        sipLibrary.startRemoteAudioTranscription()
        
        sipLibrary.addSipEventListener(object : EddysSipLibrary.SipEventListener {
            override fun onRemoteAudioTranscribed(transcribedText: String, callInfo: CallInfo?) {
                transcriptions.add(transcribedText)
                
                // Análisis en tiempo real
                analyzeText(transcribedText)
            }
            
            override fun onCallEnded(callInfo: CallInfo, reason: CallEndReason) {
                // Generar reporte final
                val report = generateCallReport(transcriptions)
                saveReport(report)
            }
        })
    }
    
    private fun analyzeText(text: String) {
        // Análisis de sentimiento, palabras clave, etc.
        val sentiment = analyzeSentiment(text)
        val keywords = extractKeywords(text)
        
        println("Sentimiento: $sentiment")
        println("Palabras clave: $keywords")
    }
}
```

## 🐛 Solución de Problemas

### Problemas Comunes

1. **Audio no se escucha**: Verificar permisos de audio y dispositivos
2. **Bluetooth no funciona**: Verificar permisos BLUETOOTH_CONNECT
3. **Transcripción no funciona**: Verificar configuración del servicio de transcripción
4. **Llamadas se cortan**: Verificar conexión de red y configuración WebSocket

### Debug y Logs

```kotlin
// Habilitar logs detallados
val config = EddysSipLibrary.SipConfig(
    enableLogs = true
    // ... otras configuraciones
)

// Obtener información de diagnóstico
val diagnostics = sipLibrary.getSystemHealthReport()
Log.d("SipLibrary", diagnostics)
```

---

## 📄 Licencia

Copyright © 2024 Eddys Larez. Todos los derechos reservados.

---

**¿Necesitas ayuda?** Contacta al desarrollador: [eddys.larez@email.com](mailto:eddys.larez@email.com)