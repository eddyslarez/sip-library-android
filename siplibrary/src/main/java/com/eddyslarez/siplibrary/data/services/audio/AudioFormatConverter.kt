package com.eddyslarez.siplibrary.data.services.audio

import com.eddyslarez.siplibrary.utils.log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Convertidor de formatos de audio para manejar PCMA/8000 (G.711 A-law)
 * Convierte entre PCM 16-bit y PCMA (A-law)
 *
 * @author Eddys Larez
 */
object AudioFormatConverter {
    private const val TAG = "AudioFormatConverter"

    // Tabla de conversión A-law
    private val aLawTable = intArrayOf(
        0x0EA0, 0x0EC0, 0x0EE0, 0x0F00, 0x0F20, 0x0F40, 0x0F60, 0x0F80,
        0x0FC0, 0x0FE0, 0x1000, 0x1020, 0x1040, 0x1060, 0x1080, 0x10C0,
        0x10E0, 0x1100, 0x1120, 0x1140, 0x1160, 0x1180, 0x11C0, 0x11E0,
        0x1200, 0x1220, 0x1240, 0x1260, 0x1280, 0x12C0, 0x12E0, 0x1300,
        0x1320, 0x1340, 0x1360, 0x1380, 0x13C0, 0x13E0, 0x1400, 0x1420,
        0x1440, 0x1460, 0x1480, 0x14C0, 0x14E0, 0x1500, 0x1520, 0x1540,
        0x1560, 0x1580, 0x15C0, 0x15E0, 0x1600, 0x1620, 0x1640, 0x1660,
        0x1680, 0x16C0, 0x16E0, 0x1700, 0x1720, 0x1740, 0x1760, 0x1780,
        0x17C0, 0x17E0, 0x1800, 0x1820, 0x1840, 0x1860, 0x1880, 0x18C0,
        0x18E0, 0x1900, 0x1920, 0x1940, 0x1960, 0x1980, 0x19C0, 0x19E0,
        0x1A00, 0x1A20, 0x1A40, 0x1A60, 0x1A80, 0x1AC0, 0x1AE0, 0x1B00,
        0x1B20, 0x1B40, 0x1B60, 0x1B80, 0x1BC0, 0x1BE0, 0x1C00, 0x1C20,
        0x1C40, 0x1C60, 0x1C80, 0x1CC0, 0x1CE0, 0x1D00, 0x1D20, 0x1D40,
        0x1D60, 0x1D80, 0x1DC0, 0x1DE0, 0x1E00, 0x1E20, 0x1E40, 0x1E60,
        0x1E80, 0x1EC0, 0x1EE0, 0x1F00, 0x1F20, 0x1F40, 0x1F60, 0x1F80,
        0x1FC0, 0x1FE0, 0x2000, 0x2020, 0x2040, 0x2060, 0x2080, 0x20C0
    )

    /**
     * Convierte PCM 16-bit a PCMA (A-law)
     */
    fun convertToPCMA(pcmData: ByteArray): ByteArray {
        if (pcmData.isEmpty()) return byteArrayOf()

        return try {
            val buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
            val output = ByteArrayOutputStream()

            while (buffer.remaining() >= 2) {
                val pcmSample = buffer.short.toInt()
                val aLawSample = linearToALaw(pcmSample)
                output.write(aLawSample)
            }

            val result = output.toByteArray()
            log.d(TAG) { "Converted ${pcmData.size} bytes PCM to ${result.size} bytes PCMA" }
            result

        } catch (e: Exception) {
            log.e(TAG) { "Error converting PCM to PCMA: ${e.message}" }
            byteArrayOf()
        }
    }

    /**
     * Convierte PCMA (A-law) a PCM 16-bit
     */
    fun convertFromPCMA(pcmaData: ByteArray): ByteArray {
        if (pcmaData.isEmpty()) return byteArrayOf()

        return try {
            val output = ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)

            pcmaData.forEach { aLawByte ->
                val pcmSample = aLawToLinear(aLawByte.toInt() and 0xFF)
                buffer.clear()
                buffer.putShort(pcmSample.toShort())
                output.write(buffer.array())
            }

            val result = output.toByteArray()
            log.d(TAG) { "Converted ${pcmaData.size} bytes PCMA to ${result.size} bytes PCM" }
            result

        } catch (e: Exception) {
            log.e(TAG) { "Error converting PCMA to PCM: ${e.message}" }
            byteArrayOf()
        }
    }

    /**
     * Convierte una muestra PCM lineal a A-law
     */
    private fun linearToALaw(pcmSample: Int): Int {
        var sample = pcmSample
        var sign = 0

        if (sample < 0) {
            sign = 0x80
            sample = -sample
        }

        if (sample > 32767) {
            sample = 32767
        }

        val exponent = when {
            sample < 256 -> 0
            sample < 512 -> 1
            sample < 1024 -> 2
            sample < 2048 -> 3
            sample < 4096 -> 4
            sample < 8192 -> 5
            sample < 16384 -> 6
            else -> 7
        }

        val mantissa = if (exponent == 0) {
            sample shr 4
        } else {
            (sample shr (exponent + 3)) and 0x0F
        }

        val aLawValue = sign or (exponent shl 4) or mantissa
        return aLawValue xor 0x55
    }

    /**
     * Convierte una muestra A-law a PCM lineal
     */
    private fun aLawToLinear(aLawSample: Int): Int {
        val sample = aLawSample xor 0x55
        val sign = if ((sample and 0x80) != 0) -1 else 1
        val exponent = (sample shr 4) and 0x07
        val mantissa = sample and 0x0F

        val linear = if (exponent == 0) {
            (mantissa shl 4) + 8
        } else {
            ((mantissa shl 4) + 0x108) shl (exponent - 1)
        }

        return sign * linear
    }

    /**
     * Valida que los datos sean válidos para conversión
     */
    fun isValidPCMData(data: ByteArray): Boolean {
        return data.isNotEmpty() && data.size % 2 == 0
    }

    /**
     * Valida que los datos PCMA sean válidos
     */
    fun isValidPCMAData(data: ByteArray): Boolean {
        return data.isNotEmpty()
    }

    /**
     * Convierte datos de audio a un formato específico
     */
    fun convertAudioFormat(
        inputData: ByteArray,
        inputFormat: AudioFormat,
        outputFormat: AudioFormat
    ): ByteArray {
        return when {
            inputFormat == AudioFormat.PCM_16BIT && outputFormat == AudioFormat.PCMA -> {
                convertToPCMA(inputData)
            }
            inputFormat == AudioFormat.PCMA && outputFormat == AudioFormat.PCM_16BIT -> {
                convertFromPCMA(inputData)
            }
            inputFormat == outputFormat -> {
                inputData // No conversion needed
            }
            else -> {
                log.w(TAG) { "Unsupported format conversion: $inputFormat to $outputFormat" }
                byteArrayOf()
            }
        }
    }

    /**
     * Redimensiona el audio para que coincida con el sample rate objetivo
     */
    fun resampleAudio(
        inputData: ByteArray,
        inputSampleRate: Int,
        outputSampleRate: Int,
        format: AudioFormat
    ): ByteArray {
        if (inputSampleRate == outputSampleRate) {
            return inputData
        }

        return try {
            val ratio = outputSampleRate.toDouble() / inputSampleRate.toDouble()
            val bytesPerSample = when (format) {
                AudioFormat.PCM_16BIT -> 2
                AudioFormat.PCMA -> 1
                else -> 2
            }

            val inputSamples = inputData.size / bytesPerSample
            val outputSamples = (inputSamples * ratio).toInt()
            val outputData = ByteArray(outputSamples * bytesPerSample)

            for (i in 0 until outputSamples) {
                val inputIndex = (i / ratio).toInt()
                val sourceIndex = inputIndex * bytesPerSample
                val targetIndex = i * bytesPerSample

                if (sourceIndex + bytesPerSample <= inputData.size &&
                    targetIndex + bytesPerSample <= outputData.size) {
                    System.arraycopy(inputData, sourceIndex, outputData, targetIndex, bytesPerSample)
                }
            }

            log.d(TAG) { "Resampled audio from ${inputSampleRate}Hz to ${outputSampleRate}Hz" }
            outputData

        } catch (e: Exception) {
            log.e(TAG) { "Error resampling audio: ${e.message}" }
            inputData
        }
    }

    /**
     * Formatos de audio soportados
     */
    enum class AudioFormat {
        PCM_16BIT,
        PCMA,
        UNKNOWN
    }

    /**
     * Detecta el formato de audio basado en el contenido
     */
    fun detectAudioFormat(data: ByteArray): AudioFormat {
        return when {
            data.isEmpty() -> AudioFormat.UNKNOWN
            data.size % 2 == 0 -> AudioFormat.PCM_16BIT
            else -> AudioFormat.PCMA
        }
    }

    /**
     * Información de diagnóstico
     */
    fun getDiagnosticInfo(): String {
        return buildString {
            appendLine("=== AUDIO FORMAT CONVERTER DIAGNOSTIC ===")
            appendLine("Supported formats:")
            appendLine("- PCM 16-bit (Little Endian)")
            appendLine("- PCMA (G.711 A-law)")
            appendLine("Sample rate: 8000 Hz")
            appendLine("Channels: Mono")
            appendLine("A-law table size: ${aLawTable.size}")
        }
    }
}