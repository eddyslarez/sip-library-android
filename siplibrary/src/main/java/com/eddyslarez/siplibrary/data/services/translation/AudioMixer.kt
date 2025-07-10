package com.eddyslarez.siplibrary.data.services.translation

import java.nio.ByteBuffer

//
///**
// * Mixer de audio para combinar streams
// */
//class AudioMixer {
//    fun mix(original: ByteArray, translated: ByteArray, mixRatio: Float): ByteArray {
//        val result = ByteArray(maxOf(original.size, translated.size))
//
//        // Convertir a samples de 16-bit
//        val originalSamples = bytesToShorts(original)
//        val translatedSamples = bytesToShorts(translated)
//        val resultSamples = ShortArray(maxOf(originalSamples.size, translatedSamples.size))
//
//        for (i in resultSamples.indices) {
//            val originalSample = if (i < originalSamples.size) originalSamples[i].toFloat() else 0f
//            val translatedSample = if (i < translatedSamples.size) translatedSamples[i].toFloat() else 0f
//
//            // Mezclar según el ratio (0.0 = solo original, 1.0 = solo traducido)
//            val mixed = originalSample * (1f - mixRatio) + translatedSample * mixRatio
//            resultSamples[i] = mixed.coerceIn(-32768f, 32767f).toInt().toShort()
//        }
//
//        return shortsToBytes(resultSamples)
//    }
//
//    private fun bytesToShorts(bytes: ByteArray): ShortArray {
//        val shorts = ShortArray(bytes.size / 2)
//        val buffer = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
//        for (i in shorts.indices) {
//            shorts[i] = buffer.getShort(i * 2)
//        }
//        return shorts
//    }
//
//    private fun shortsToBytes(shorts: ShortArray): ByteArray {
//        val bytes = ByteArray(shorts.size * 2)
//        val buffer = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
//        for (i in shorts.indices) {
//            buffer.putShort(i * 2, shorts[i])
//        }
//        return bytes
//    }
//
//    fun dispose() {
//        // Limpiar recursos si es necesario
//    }
//}

/**
 * Mixer de audio para combinar streams
 */
class AudioMixer {
    fun mix(original: ByteArray, translated: ByteArray, mixRatio: Float): ByteArray {
        val result = ByteArray(maxOf(original.size, translated.size))

        // Convertir a samples de 16-bit
        val originalSamples = bytesToShorts(original)
        val translatedSamples = bytesToShorts(translated)
        val resultSamples = ShortArray(maxOf(originalSamples.size, translatedSamples.size))

        for (i in resultSamples.indices) {
            val originalSample = if (i < originalSamples.size) originalSamples[i].toFloat() else 0f
            val translatedSample = if (i < translatedSamples.size) translatedSamples[i].toFloat() else 0f

            // Mezclar según el ratio (0.0 = solo original, 1.0 = solo traducido)
            val mixed = originalSample * (1f - mixRatio) + translatedSample * mixRatio
            resultSamples[i] = mixed.coerceIn(-32768f, 32767f).toInt().toShort()
        }

        return shortsToBytes(resultSamples)
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val shorts = ShortArray(bytes.size / 2)
        val buffer = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in shorts.indices) {
            shorts[i] = buffer.getShort(i * 2)
        }
        return shorts
    }

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        val buffer = ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in shorts.indices) {
            buffer.putShort(i * 2, shorts[i])
        }
        return bytes
    }

    fun dispose() {
        // Limpiar recursos si es necesario
    }
}
