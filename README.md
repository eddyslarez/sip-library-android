# Eddys Larez SIP Library

Una biblioteca SIP/VoIP para Android desarrollada por Eddys Larez, que proporciona funcionalidades completas para realizar y recibir llamadas SIP usando WebRTC y WebSocket.

## 🚀 Características

- ✅ Llamadas SIP entrantes y salientes
- ✅ Soporte para WebRTC
- ✅ Conexión WebSocket robusta con reconexión automática
- ✅ Soporte para DTMF
- ✅ Gestión de dispositivos de audio (altavoz, auriculares, Bluetooth)
- ✅ Historial de llamadas
- ✅ Notificaciones push
- ✅ Estados de llamada reactivos con Flow
- ✅ Arquitectura moderna con Kotlin

## 📱 Instalación

### Usando JitPack

1. Agrega JitPack en tu `settings.gradle.kts` (nivel proyecto):

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

2. Agrega la dependencia en tu `build.gradle.kts` (nivel app):

```kotlin
dependencies {
    implementation("com.github.eddyslarez:sip-library:1.0.0")
}
```

### Desde GitHub directamente

También puedes clonar el repositorio e incluir el módulo en tu proyecto:

```bash
git clone https://github.com/eddyslarez/sip-library.git
```

## 🛠️ Configuración

### 1. Permisos

Agrega estos permisos en tu `AndroidManifest.xml`:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

### 2. Inicialización

En tu `Application` clase:

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Configuración personalizada (opcional)
        val config = EddysSipLibrary.SipConfig(
            defaultDomain = "tu-dominio.com",
            webSocketUrl = "wss://tu-servidor:puerto/",
            userAgent = "MiApp/1.0.0",
            enableLogs = true
        )
        
        // Inicializar la biblioteca
        EddysSipLibrary.getInstance().initialize(this, config)
    }
}
```

## 📋 Uso Básico

### Registrar una cuenta SIP

```kotlin
val sipLibrary = EddysSipLibrary.getInstance()

sipLibrary.registerAccount(
    username = "usuario",
    password = "contraseña",
    domain = "mi-dominio.com", // opcional, usa el configurado por defecto
    pushToken = "token_fcm", // opcional
    pushProvider = "fcm" // fcm o apns
)
```

### Realizar una llamada

```kotlin
sipLibrary.makeCall("1234567890")
```

### Responder/Rechazar llamadas

```kotlin
// Aceptar llamada entrante
sipLibrary.acceptCall()

// Rechazar llamada entrante
sipLibrary.declineCall()

// Terminar llamada actual
sipLibrary.endCall()
```

### Funciones durante la llamada

```kotlin
// Silenciar/desmute
sipLibrary.toggleMute()

// Verificar si está silenciado
val isMuted = sipLibrary.isMuted()

// Enviar DTMF
sipLibrary.sendDtmf('1')
sipLibrary.sendDtmfSequence("123*")

// Poner en espera
sipLibrary.holdCall()
sipLibrary.resumeCall()
```

### Gestión de audio

```kotlin
// Obtener dispositivos disponibles
val (inputDevices, outputDevices) = sipLibrary.getAudioDevices()

// Cambiar dispositivo de salida
outputDevices.forEach { device ->
    if (device.name.contains("Bluetooth")) {
        sipLibrary.changeAudioOutput(device)
    }
}
```

## 🔄 Observar Estados

### Estados de llamada

```kotlin
class MainActivity : ComponentActivity() {
    private lateinit var sipLibrary: EddysSipLibrary
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        sipLibrary = EddysSipLibrary.getInstance()
        
        // Observar cambios de estado de llamada
        lifecycleScope.launch {
            sipLibrary.getCallStateFlow().collect { callState ->
                when (callState) {
                    CallState.INCOMING -> {
                        // Llamada entrante
                        showIncomingCallUI()
                    }
                    CallState.CONNECTED -> {
                        // Llamada conectada
                        showInCallUI()
                    }
                    CallState.ENDED -> {
                        // Llamada terminada
                        showMainUI()
                    }
                    else -> {
                        // Otros estados
                    }
                }
            }
        }
    }
}
```

### Estados de registro

```kotlin
lifecycleScope.launch {
    sipLibrary.getRegistrationStateFlow().collect { registrationState ->
        when (registrationState) {
            RegistrationState.OK -> {
                // Registrado exitosamente
                updateUI("Conectado")
            }
            RegistrationState.FAILED -> {
                // Error en registro
                updateUI("Error de conexión")
            }
            else -> {
                // Otros estados
            }
        }
    }
}
```

## 📞 Historial de Llamadas

```kotlin
// Obtener todas las llamadas
val callLogs = sipLibrary.getCallLogs()

// Obtener solo llamadas perdidas
val missedCalls = sipLibrary.getMissedCalls()

// Limpiar historial
sipLibrary.clearCallLogs()

// Buscar llamadas de un número específico
val callsFromNumber = sipLibrary.getCallLogsForNumber("1234567890")
```

## 🔧 Configuración Avanzada

### Callbacks personalizados

```kotlin
sipLibrary.setCallbacks(object : EddysSipLibrary.SipCallbacks {
    override fun onCallTerminated() {
        // Llamada terminada
    }
    
    override fun onCallStateChanged(state: CallState) {
        // Estado de llamada cambió
    }
    
    override fun onRegistrationStateChanged(state: RegistrationState) {
        // Estado de registro cambió
    }
    
    override fun onIncomingCall(callerNumber: String, callerName: String?) {
        // Llamada entrante
        showNotification("Llamada de $callerNumber")
    }
})
```

### Diagnóstico y salud del sistema

```kotlin
// Verificar si el sistema está saludable
val isHealthy = sipLibrary.isSystemHealthy()

// Obtener reporte detallado
val healthReport = sipLibrary.getSystemHealthReport()
println(healthReport)
```

## 🌟 Características Avanzadas

### Soporte para múltiples dominios

```kotlin
// Configurar diferentes servidores según el dominio
val config = EddysSipLibrary.SipConfig(
    defaultDomain = "dominio",
    webSocketUrl = "wss://dominio:XXXXXX/"
)

// registro
sipLibrary.registerAccount(
    username = "usuario",
    password = "contraseña",
    domain = "dominio"
)
```

### Reconexión automática

La biblioteca maneja automáticamente:
- ✅ Reconexión de WebSocket
- ✅ Re-registro SIP
- ✅ Manejo de cambios de red
- ✅ Keepalive con ping/pong

### Soporte para notificaciones push

```kotlin
// Actualizar token de push
sipLibrary.updatePushToken("nuevo_token_fcm", "fcm")
```

## 🐛 Solución de Problemas

### Problemas comunes

1. **Error de permisos de audio**:
   ```kotlin
   // Solicitar permisos antes de usar
   if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) 
       != PackageManager.PERMISSION_GRANTED) {
       ActivityCompat.requestPermissions(this, 
           arrayOf(Manifest.permission.RECORD_AUDIO), 1)
   }
   ```

2. **Problemas de conexión**:
   ```kotlin
   // Verificar estado de salud
   val healthReport = sipLibrary.getSystemHealthReport()
   Log.d("SIP", healthReport)
   ```

3. **Audio no funciona**:
   ```kotlin
   // Verificar dispositivos disponibles
   val (input, output) = sipLibrary.getAudioDevices()
   Log.d("Audio", "Input devices: $input")
   Log.d("Audio", "Output devices: $output")
   ```

## 📄 Licencia

Desarrollado por **Eddys Larez**

Este proyecto es de código abierto y está disponible bajo la licencia MIT.

## 🤝 Contribución

Las contribuciones son bienvenidas. Por favor:

1. Fork el proyecto
2. Crea una rama para tu feature (`git checkout -b feature/nueva-caracteristica`)
3. Commit tus cambios (`git commit -am 'Agregar nueva característica'`)
4. Push a la rama (`git push origin feature/nueva-caracteristica`)
5. Abre un Pull Request

## 📞 Soporte

Para soporte técnico o preguntas:

- GitHub Issues: [Reportar un problema](https://github.com/eddyslarez/sip-library/issues)
- Email: eddyslarez@example.com

## 🔄 Changelog

### v1.0.0
- ✅ Lanzamiento inicial
- ✅ Soporte completo para SIP/WebRTC
- ✅ Gestión de llamadas
- ✅ Historial de llamadas
- ✅ Soporte para DTMF
- ✅ Gestión de audio
- ✅ Estados reactivos

---

**Desarrollado con ❤️ por Eddys Larez**