package com.eddyslarez.siplibrary.data.services.audio

/**
 * Represents an audio device (input or output)
 * 
 * @author Eddys Larez
 */
data class AudioDevice(
    val name: String,
    val descriptor: String,
    val nativeDevice: Any? = null,
    val isOutput: Boolean
)