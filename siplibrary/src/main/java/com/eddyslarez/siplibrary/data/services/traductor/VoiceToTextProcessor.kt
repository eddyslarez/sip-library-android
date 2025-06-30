package com.eddyslarez.siplibrary.data.services.traductor

interface VoiceToTextProcessor {
    fun startListening(onResult: (String, Boolean) -> Unit, onError: (String) -> Unit)
    fun stopListening()
    fun isListening(): Boolean
    fun setLanguage(languageCode: String)
}