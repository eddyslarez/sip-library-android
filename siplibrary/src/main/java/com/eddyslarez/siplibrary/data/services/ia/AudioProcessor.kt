package com.eddyslarez.siplibrary.data.services.ia

class AudioProcessor {
    companion object {
        private const val TAG = "AudioProcessor"

        /**
         * Convert WebRTC audio samples to PCM16 format for OpenAI
         */
        fun convertToPCM16(audioSamples: FloatArray, sampleRate: Int = 24000): ByteArray {
            val pcm16Data = ByteArray(audioSamples.size * 2)

            for (i in audioSamples.indices) {
                val sample = (audioSamples[i] * Short.MAX_VALUE).coerceIn(
                    Short.MIN_VALUE.toFloat(),
                    Short.MAX_VALUE.toFloat()
                ).toInt()

                pcm16Data[i * 2] = (sample and 0xFF).toByte()
                pcm16Data[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
            }

            return pcm16Data
        }

        /**
         * Convert PCM16 data from OpenAI back to float samples for WebRTC
         */
        fun convertFromPCM16(pcm16Data: ByteArray): FloatArray {
            val samples = FloatArray(pcm16Data.size / 2)

            for (i in samples.indices) {
                val low = pcm16Data[i * 2].toInt() and 0xFF
                val high = pcm16Data[i * 2 + 1].toInt() and 0xFF
                val sample = (high shl 8) or low

                // Convert to signed short, then normalize to float
                val signedSample = if (sample > 32767) sample - 65536 else sample
                samples[i] = signedSample.toFloat() / Short.MAX_VALUE
            }

            return samples
        }

        /**
         * Resample audio to target sample rate if needed
         */
        fun resampleAudio(audioData: FloatArray, currentRate: Int, targetRate: Int): FloatArray {
            if (currentRate == targetRate) return audioData

            val ratio = targetRate.toFloat() / currentRate
            val outputLength = (audioData.size * ratio).toInt()
            val output = FloatArray(outputLength)

            for (i in output.indices) {
                val sourceIndex = (i / ratio).toInt()
                if (sourceIndex < audioData.size) {
                    output[i] = audioData[sourceIndex]
                }
            }

            return output
        }
    }
}