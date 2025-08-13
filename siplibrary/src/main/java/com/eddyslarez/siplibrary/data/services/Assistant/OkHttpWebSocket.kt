package com.eddyslarez.siplibrary.data.services.Assistant

import okhttp3.WebSocket

class OkHttpWebSocket(
    private val webSocket: WebSocket
) {
    fun send(message: String): Boolean {
        return webSocket.send(message)
    }

    fun close(code: Int, reason: String): Boolean {
        return webSocket.close(code, reason)
    }
}