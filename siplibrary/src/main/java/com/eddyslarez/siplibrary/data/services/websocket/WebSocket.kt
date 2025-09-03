package com.eddyslarez.siplibrary.data.services.websocket

import org.java_websocket.client.WebSocketClient
import org.java_websocket.framing.Framedata
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer
import java.util.*
import javax.net.ssl.SSLSocketFactory
import kotlin.collections.HashMap

/**
 * Implementación de WebSocket para Android
 * 
 * @author Eddys Larez
 */
class WebSocket(private val uri: String, private val headers: Map<String, String>) :
    MultiplatformWebSocket {

    private var webSocketClient: WebSocketClient? = null
    private var listener: MultiplatformWebSocket.Listener? = null
    private var isConnectedFlag = false

    // Timers
    private var pingTimer: Timer? = null
    private var registrationRenewalTimer: Timer? = null

    // Registro de expiración para cuentas
    private val registrationExpirations = HashMap<String, Long>()

    // Constantes
    private var registrationCheckIntervalMs: Long = 300000
    private var renewBeforeExpirationMs: Long = 60000

    // Variables para medir pings
    private var lastPingSentTime: Long = 0

    override fun connect() {
        try {
            log.d(tag = "WebSocket") { "=== WEBSOCKET CONNECT ATTEMPT ===" }
            log.d(tag = "WebSocket") { "URI: $uri" }
            
            val webSocketURI = URI(uri)

            webSocketClient = object : WebSocketClient(webSocketURI, headers) {
                override fun onOpen(handshakedata: ServerHandshake) {
                    isConnectedFlag = true
                    log.d(tag = "WebSocket") { "✅ WebSocket connected successfully" }
                    listener?.onOpen()
                }

                override fun onMessage(message: String) {
                    listener?.onMessage(message)
                }

                override fun onMessage(bytes: ByteBuffer) {
                    val binaryString = bytes.toString()
                    listener?.onMessage(binaryString)
                }

                override fun onClose(code: Int, reason: String, remote: Boolean) {
                    isConnectedFlag = false
                    stopPingTimer()
                    stopRegistrationRenewalTimer()
                    log.w(tag = "WebSocket") { "❌ WebSocket closed: code=$code, reason=$reason, remote=$remote" }
                    listener?.onClose(code, reason)
                }

                override fun onError(ex: Exception) {
                    log.e(tag = "WebSocket") { "❌ WebSocket error: ${ex.message}" }
                    listener?.onError(ex)
                }

                override fun onWebsocketPong(conn: org.java_websocket.WebSocket?, f: Framedata?) {
                    val currentTime = System.currentTimeMillis()
                    val pingLatency = currentTime - lastPingSentTime
                    log.d(tag = "WebSocket") { "🏓 Pong received, latency: ${pingLatency}ms" }
                    listener?.onPong(pingLatency)
                }
            }

            // Setup SSL if using secure WebSocket
            if (uri.startsWith("wss")) {
                val socketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                webSocketClient?.setSocketFactory(socketFactory)
                log.d(tag = "WebSocket") { "🔒 SSL socket factory configured" }
            }

            log.d(tag = "WebSocket") { "🔄 Starting WebSocket connection..." }
            webSocketClient?.connect()

        } catch (e: Exception) {
            log.e(tag = "WebSocket") { "❌ Error in WebSocket connect: ${e.message}" }
            listener?.onError(e)
        }
    }

    override fun send(message: String) {
        if (!isConnectedFlag || webSocketClient == null) {
            log.e(tag = "WebSocket") { "❌ Cannot send message - WebSocket not connected" }
            listener?.onError(Exception("Cannot send message - WebSocket not connected"))
            return
        }

        try {
            webSocketClient?.send(message)
            log.d(tag = "WebSocket") { "📤 Message sent successfully (${message.length} chars)" }
        } catch (e: Exception) {
            log.e(tag = "WebSocket") { "❌ Send error: ${e.message}" }
            listener?.onError(Exception("Send error: ${e.message}"))
        }
    }

    override fun close(code: Int, reason: String) {
        try {
            isConnectedFlag = false
            stopPingTimer()
            stopRegistrationRenewalTimer()
            log.d(tag = "WebSocket") { "🔌 Closing WebSocket: code=$code, reason=$reason" }
            webSocketClient?.close(code, reason)
            webSocketClient = null
        } catch (e: Exception) {
            log.e(tag = "WebSocket") { "❌ Error closing WebSocket: ${e.message}" }
            listener?.onError(e)
        }
    }

    override fun isConnected(): Boolean {
        val connected = isConnectedFlag && webSocketClient?.isOpen == true
        if (!connected && isConnectedFlag) {
            // Corregir flag si está desincronizado
            isConnectedFlag = false
            log.w(tag = "WebSocket") { "⚠️ WebSocket flag corrected - was true but connection is false" }
        }
        return connected
    }

    override fun setListener(listener: MultiplatformWebSocket.Listener) {
        this.listener = listener
    }

    override fun sendPing() {
        if (!isConnectedFlag || webSocketClient == null) {
            log.w(tag = "WebSocket") { "⚠️ Cannot send ping - WebSocket not connected" }
            return
        }

        try {
            lastPingSentTime = System.currentTimeMillis()
            webSocketClient?.sendPing()
            log.d(tag = "WebSocket") { "🏓 Ping sent" }
        } catch (e: Exception) {
            log.e(tag = "WebSocket") { "❌ Ping error: ${e.message}" }
            listener?.onError(Exception("Ping error: ${e.message}"))
        }
    }

    override fun startPingTimer(intervalMs: Long) {
        stopPingTimer()

        log.d(tag = "WebSocket") { "⏰ Starting ping timer with interval: ${intervalMs}ms" }
        
        pingTimer = Timer("WebSocketPingTimer")
        pingTimer?.schedule(object : TimerTask() {
            override fun run() {
                if (isConnected()) {
                    sendPing()
                } else {
                    log.w(tag = "WebSocket") { "⚠️ Ping timer running but WebSocket not connected" }
                }
            }
        }, intervalMs, intervalMs)
    }

    override fun stopPingTimer() {
        if (pingTimer != null) {
            log.d(tag = "WebSocket") { "⏹️ Stopping ping timer" }
        }
        pingTimer?.cancel()
        pingTimer = null
    }

    override fun startRegistrationRenewalTimer(checkIntervalMs: Long, renewBeforeExpirationMs: Long) {
        stopRegistrationRenewalTimer()

        this.registrationCheckIntervalMs = checkIntervalMs
        this.renewBeforeExpirationMs = renewBeforeExpirationMs

        registrationRenewalTimer = Timer("RegistrationRenewalTimer")
        registrationRenewalTimer?.schedule(object : TimerTask() {
            override fun run() {
                checkRegistrationRenewals()
            }
        }, 0, registrationCheckIntervalMs)
    }

    override fun stopRegistrationRenewalTimer() {
        registrationRenewalTimer?.cancel()
        registrationRenewalTimer = null
    }

    override fun setRegistrationExpiration(accountKey: String, expirationTimeMs: Long) {
        registrationExpirations[accountKey] = expirationTimeMs
    }

    override fun renewRegistration(accountKey: String) {
        listener?.onRegistrationRenewalRequired(accountKey)
    }

    private fun checkRegistrationRenewals() {
        val currentTime = System.currentTimeMillis()

        val expirationsToCheck = HashMap(registrationExpirations)

        for ((accountKey, expirationTime) in expirationsToCheck) {
            val timeToExpiration = expirationTime - currentTime

            if (timeToExpiration < renewBeforeExpirationMs) {
                renewRegistration(accountKey)
            }
        }
    }
}