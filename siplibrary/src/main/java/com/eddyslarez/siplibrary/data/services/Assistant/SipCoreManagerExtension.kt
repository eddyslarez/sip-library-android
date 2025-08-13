package com.eddyslarez.siplibrary.data.services.Assistant

import android.R.attr.delay
import android.content.Context
import com.eddyslarez.siplibrary.EddysSipLibrary.IncomingCallInfo
import com.eddyslarez.siplibrary.core.SipCoreManager
import com.eddyslarez.siplibrary.utils.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Extensión para integrar el asistente con SipCoreManager
 */
class SipCoreManagerExtension(
    private val sipCoreManager: SipCoreManager,
    private val openAIApiKey: String
) {
    private val TAG = "SipCoreManagerExtension"
    private var realtimeAssistant: OpenAIRealtimeAssistant? = null
    private var isAssistantEnabled = false

    fun initializeAssistant(context: Context) {
        realtimeAssistant = OpenAIRealtimeAssistant(context, openAIApiKey)

        realtimeAssistant?.initialize(
            sipCoreManager.webRtcManager,
            object : OpenAIRealtimeAssistant.RealtimeAssistantListener {
                override fun onConnected() {
                    log.d(TAG) { "Realtime assistant connected" }
                }

                override fun onDisconnected() {
                    log.d(TAG) { "Realtime assistant disconnected" }
                }

                override fun onError(error: String) {
                    log.e(TAG) { "Assistant error: $error" }
                }

                override fun onTranscriptionReceived(text: String, isComplete: Boolean) {
                    log.d(TAG) { "Transcription ${if (isComplete) "(complete)" else "(partial)"}: $text" }
                }

                override fun onResponseStarted() {
                    log.d(TAG) { "Assistant response started" }
                }

                override fun onResponseCompleted() {
                    log.d(TAG) { "Assistant response completed" }
                }

                override fun onFunctionCall(name: String, arguments: String) {
                    log.d(TAG) { "Function call: $name($arguments)" }
                }
            }
        )
    }

    /**
     * Habilita el asistente para la próxima llamada
     */
    fun enableAssistantForCalls() {
        isAssistantEnabled = true
    }

    /**
     * Deshabilita el asistente
     */
    fun disableAssistantForCalls() {
        isAssistantEnabled = false
        realtimeAssistant?.stopAssistant()
    }

    /**
     * Maneja llamadas entrantes con asistente
     */
    fun handleIncomingCallWithAssistant() {
        if (!isAssistantEnabled) {
            // Manejar llamada normalmente
            sipCoreManager.acceptCall()
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Conectar al asistente
                val connected = realtimeAssistant?.connect() ?: false
                if (connected) {
                    // Aceptar llamada
                    sipCoreManager.acceptCall()

                    // Esperar a que se establezca la llamada
                    delay(2000)

                    // Iniciar asistente
                    realtimeAssistant?.startAssistant()

                    log.d(TAG) { "Call handled with AI assistant" }
                } else {
                    // Fallback a llamada normal
                    sipCoreManager.acceptCall()
                    log.w(TAG) { "Assistant connection failed, handling call normally" }
                }
            } catch (e: Exception) {
                log.e(TAG) { "Error handling call with assistant: ${e.message}" }
                sipCoreManager.acceptCall()
            }
        }
    }

    /**
     * Finaliza llamada y limpia asistente
     */
    fun endCallWithAssistant() {
        realtimeAssistant?.stopAssistant()
        sipCoreManager.endCall()

        CoroutineScope(Dispatchers.IO).launch {
            delay(1000)
            realtimeAssistant?.disconnect()
        }
    }
}
