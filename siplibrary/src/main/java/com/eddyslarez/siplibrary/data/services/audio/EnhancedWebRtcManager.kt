package com.eddyslarez.siplibrary.data.services.audio

//import android.annotation.SuppressLint
//import android.app.Application
//import android.media.AudioManager
//import android.os.Build
//import android.util.Log
//import androidx.annotation.RequiresApi
//import com.eddyslarez.siplibrary.data.services.ia.OpenAIRealtimeClient
//import com.eddyslarez.siplibrary.utils.log
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.channels.Channel
//import kotlinx.coroutines.coroutineScope
//import kotlinx.coroutines.delay
//import kotlinx.coroutines.launch
//import org.webrtc.*
//import org.webrtc.audio.JavaAudioDeviceModule
//import java.nio.ByteBuffer
//
///**
// * Versión mejorada del AndroidWebRtcManager con audio de máxima calidad
// */
//class EnhancedWebRtcManager(
//    private val application: Application,
//    private val openAiApiKey: String? = "",
//    private val googleApiKey: String? = null
//) : AndroidWebRtcManager(application, openAiApiKey) {
//
//    companion object {
//        private const val TAG = "EnhancedWebRtcManager"
//    }
//    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
//
//    // Audio processing components
//    private val audioQualityOptimizer = AudioQualityOptimizer()
//    private val advancedAudioProcessor = AdvancedAudioProcessor()
//    private val speechToTextManager = SpeechToTextManager(
//        context = application.applicationContext,
//        openAiApiKey = openAiApiKey,
//        googleApiKey = googleApiKey
//    )
//
//    // Audio interception
//    private val interceptedAudioChannel = Channel<ByteArray>(Channel.UNLIMITED)
//    private var audioInterceptionActive = false
//
//    // Speech-to-Text
//    private var sttEnabled = false
////    private var sttResultCallback: ((SpeechToTextManager.STTResult) -> Unit)? = null
//
//    init {
//        setupEnhancedAudio()
//    }
//
//    /**
//     * Configuración de audio mejorada
//     */
//    private fun setupEnhancedAudio() {
//        coroutineScope.launch {
//            try {
//                // Inicializar procesador de audio avanzado
//                advancedAudioProcessor.initializeSystemEffects(0) // AudioSession ID por defecto
//
//                // Inicializar STT
////                speechToTextManager.initialize(SpeechToTextManager.STTProvider.AUTO_SELECT)
//
//                log.d(TAG) { "Enhanced audio setup completed" }
//            } catch (e: Exception) {
//                log.e(TAG) { "Error setting up enhanced audio: ${e.message}" }
//            }
//        }
//    }
//
//    /**
//     * Habilita Speech-to-Text con callback
//     */
//    fun enableSpeechToText(
//        enabled: Boolean,
//        callback: ((SpeechToTextManager.STTResult) -> Unit)? = null
//    ) {
//        sttEnabled = enabled
//        sttResultCallback = callback
//
//        if (enabled) {
//            speechToTextManager.setOnResultCallback { result ->
//                log.d(TAG) { "STT Result: ${result.text} (${result.confidence})" }
//                callback?.invoke(result)
//            }
//
//            speechToTextManager.setOnErrorCallback { error ->
//                log.e(TAG) { "STT Error: ${error.message}" }
//            }
//        }
//    }
//
//    /**
//     * Configuración de WebRTC mejorada para máxima calidad de audio
//     */
//    override fun initializeWebRTC() {
//        try {
//            PeerConnectionFactory.initialize(
//                PeerConnectionFactory.InitializationOptions.builder(application)
//                    .setEnableInternalTracer(false)
//                    .createInitializationOptions()
//            )
//
//            val options = PeerConnectionFactory.Options()
//            val eglBase = EglBase.create()
//            val eglBaseContext = eglBase.eglBaseContext
//
//            // Configuración optimizada de audio
//            val optimalConfig = audioQualityOptimizer.getOptimalWebRtcConfig()
//
//            val audioDeviceModule = JavaAudioDeviceModule.builder(application)
//                .setSampleRate(optimalConfig.sampleRate)
//                .setAudioFormat(optimalConfig.audioFormat)
//                .setUseStereoInput(false)
//                .setUseStereoOutput(false)
//                .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
//                    override fun onWebRtcAudioRecordStart() {
//                        log.d(TAG) { "WebRTC audio recording started" }
//                    }
//
//                    override fun onWebRtcAudioRecordStop() {
//                        log.d(TAG) { "WebRTC audio recording stopped" }
//                    }
//                })
//                .setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
//                    override fun onWebRtcAudioTrackStart() {
//                        log.d(TAG) { "WebRTC audio track started" }
//                    }
//
//                    override fun onWebRtcAudioTrackStop() {
//                        log.d(TAG) { "WebRTC audio track stopped" }
//                    }
//                })
//                .createAudioDeviceModule()
//
//            peerConnectionFactory = PeerConnectionFactory.builder()
//                .setOptions(options)
//                .setAudioDeviceModule(audioDeviceModule)
//                .setVideoEncoderFactory(
//                    DefaultVideoEncoderFactory(eglBaseContext, true, true)
//                )
//                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBaseContext))
//                .createPeerConnectionFactory()
//
//            log.d(TAG) { "Enhanced WebRTC initialized successfully" }
//        } catch (e: Exception) {
//            log.e(TAG) { "Error initializing enhanced WebRTC: ${e.message}" }
//        }
//    }
//
//    /**
//     * Interceptación de audio mejorada con máxima calidad
//     */
//    @RequiresApi(Build.VERSION_CODES.O)
//    override fun setupAudioInterceptionWithSink(audioTrack: AudioTrack) {
//        val enhancedAudioSink = object : AudioTrackSink {
//            override fun onData(
//                audioData: ByteBuffer,
//                bitsPerSample: Int,
//                sampleRate: Int,
//                numberOfChannels: Int,
//                numberOfFrames: Int,
//                absoluteCaptureTimestampMs: Long
//            ) {
//                if (audioInterceptionActive) {
//                    coroutineScope.launch {
//                        processHighQualityAudio(
//                            audioData = audioData,
//                            bitsPerSample = bitsPerSample,
//                            sampleRate = sampleRate,
//                            numberOfChannels = numberOfChannels,
//                            numberOfFrames = numberOfFrames
//                        )
//                    }
//                }
//            }
//        }
//
//        audioTrack.addSink(enhancedAudioSink)
//        audioInterceptionActive = true
//        log.d(TAG) { "Enhanced audio sink configured with high quality processing" }
//    }
//
//    /**
//     * Procesa audio con máxima calidad para OpenAI y STT
//     */
//    @RequiresApi(Build.VERSION_CODES.O)
//    private suspend fun processHighQualityAudio(
//        audioData: ByteBuffer,
//        bitsPerSample: Int,
//        sampleRate: Int,
//        numberOfChannels: Int,
//        numberOfFrames: Int
//    ) {
//        try {
//            // Convertir ByteBuffer a ByteArray
//            val data = ByteArray(audioData.remaining())
//            audioData.get(data)
//            audioData.rewind() // Reset buffer position
//
//            // Análisis de calidad inicial
//            val qualityMetrics = audioQualityOptimizer.analyzeAudioQuality(data, sampleRate)
//            log.d(TAG) { "Audio quality: ${qualityMetrics.qualityScore}% (SNR: ${qualityMetrics.snr} dB)" }
//
//            // Solo procesar audio de buena calidad o con actividad de voz
//            if (qualityMetrics.qualityScore < 30 && qualityMetrics.voiceActivityProbability < 0.3) {
//                log.d(TAG) { "Skipping low quality audio chunk" }
//                return
//            }
//
//            // Procesar para OpenAI si está habilitado
//            if (isOpenAiEnabled) {
//                val openAiAudio = advancedAudioProcessor.processForOpenAI(
//                    audioData = data,
//                    inputSampleRate = sampleRate,
//                    qualityOptimizer = audioQualityOptimizer
//                )
//
//                openAiClient?.addAudioData(openAiAudio)
//            }
//
//            // Procesar para STT si está habilitado
//            if (sttEnabled && qualityMetrics.voiceActivityProbability > 0.5) {
//                val sttAudio = advancedAudioProcessor.processForSTT(
//                    audioData = data,
//                    inputSampleRate = sampleRate
//                )
//
//                // Enviar al canal de audio interceptado para procesamiento STT
//                interceptedAudioChannel.trySend(sttAudio)
//            }
//
//        } catch (e: Exception) {
//            log.e(TAG) { "Error processing high quality audio: ${e.message}" }
//        }
//    }
//
//    /**
//     * Inicia el procesamiento de STT en tiempo real
//     */
//    private fun startRealtimeSTT() {
//        if (!sttEnabled) return
//
//        coroutineScope.launch {
//            try {
//                speechToTextManager.startRealTimeTranscription(
//                    audioChannel = interceptedAudioChannel,
//                    sampleRate = AudioQualityOptimizer.STT_SAMPLE_RATE,
//                    language = "es-ES"
//                )
//            } catch (e: Exception) {
//                log.e(TAG) { "Error starting realtime STT: ${e.message}" }
//            }
//        }
//    }
//
//    /**
//     * Configuración de audio optimizada para llamadas
//     */
//    override fun initializeAudio() {
//        super.initializeAudio()
//
//        // Configuraciones adicionales para máxima calidad
//        audioManager?.let { am ->
//            try {
//                // Configurar modo de comunicación optimizado
//                am.mode = AudioManager.MODE_IN_COMMUNICATION
//
//                // Optimizar parámetros de audio
//                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
//                    am.setParameter("noise_suppression", "on")
//                    am.setParameter("echo_cancellation", "on")
//                    am.setParameter("auto_gain_control", "on")
//                }
//
//                log.d(TAG) { "Enhanced audio configuration applied" }
//
//                // Iniciar STT si está habilitado
//                if (sttEnabled) {
//                    startRealtimeSTT()
//                }
//
//            } catch (e: Exception) {
//                log.e(TAG) { "Error applying enhanced audio configuration: ${e.message}" }
//            }
//        }
//    }
//
//    /**
//     * Crea pista de audio local con configuración optimizada
//     */
//    override suspend fun ensureLocalAudioTrack(): Boolean {
//        return try {
//            val peerConn = peerConnection ?: return false
//
//            if (localAudioTrack != null) {
//                log.d(TAG) { "Local audio track already exists" }
//                return true
//            }
//
//            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
//            audioManager?.isMicrophoneMute = false
//
//            // Configuración de constraints optimizada
//            val optimalConfig = audioQualityOptimizer.getOptimalWebRtcConfig()
//            val audioConstraints = MediaConstraints().apply {
//                // Echo cancellation
//                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
//                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation2", "true"))
//                mandatory.add(MediaConstraints.KeyValuePair("googDAEchoCancellation", "false"))
//
//                // Noise suppression
//                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
//                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression2", "true"))
//
//                // Auto gain control
//                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
//                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl2", "true"))
//
//                // Audio processing
//                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
//                mandatory.add(MediaConstraints.KeyValuePair("googAudioMirroring", "false"))
//                mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))
//
//                // Quality optimizations
//                mandatory.add(MediaConstraints.KeyValuePair("googBeamforming", "true"))
//                mandatory.add(MediaConstraints.KeyValuePair("googArrayGeometry", "1 0 0 0.05 0 0"))
//
//                // Experimental features for better quality
//                optional.add(MediaConstraints.KeyValuePair("googExperimentalEchoCancellation", "true"))
//                optional.add(MediaConstraints.KeyValuePair("googExperimentalNoiseSuppression", "true"))
//                optional.add(MediaConstraints.KeyValuePair("googExperimentalAutoGainControl", "true"))
//            }
//
//            // Create audio source with optimized constraints
//            audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
//
//            // Create local audio track
//            localAudioTrack = peerConnectionFactory?.createAudioTrack(
//                "enhanced_local_audio_${System.currentTimeMillis()}",
//                audioSource
//            )
//            localAudioTrack?.setEnabled(true)
//
//            // Add track to peer connection
//            localAudioTrack?.let { track ->
//                val rtpSender = peerConn.addTrack(track, listOf("enhanced_local_stream_${System.currentTimeMillis()}"))
//
//                // Configure transceiver for optimal performance
//                peerConn.transceivers.find { it.sender == rtpSender }?.let { transceiver ->
//                    transceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
//
//                    // Set codec preferences for best quality
//                    val codecs = mutableListOf<RtpCodecCapability>()
//
//                    // Prefer Opus with optimal settings
//                    codecs.add(RtpCodecCapability().apply {
//                        name = "opus"
//                        clockRate = 48000
//                        numChannels = 2
//                        parameters = mapOf(
//                            "minptime" to "10",
//                            "maxptime" to "120",
//                            "stereo" to "1",
//                            "sprop-stereo" to "1",
//                            "maxaveragebitrate" to "128000",
//                            "maxplaybackrate" to "48000"
//                        )
//                    })
//
//                    transceiver.setCodecPreferences(codecs)
//                }
//
//                log.d(TAG) { "Enhanced local audio track created and configured successfully" }
//            }
//
//            true
//
//        } catch (e: Exception) {
//            log.e(TAG) { "Error creating enhanced local audio track: ${e.message}" }
//            false
//        }
//    }
//
//    /**
//     * Obtiene un diagnóstico completo de la calidad de audio
//     */
//    @SuppressLint("MissingPermission")
//    override fun diagnoseAudioIssues(): String {
//        return buildString {
//            appendLine("=== ENHANCED AUDIO DIAGNOSIS ===")
//            appendLine(super.diagnoseAudioIssues())
//
//            appendLine("\n=== ENHANCED FEATURES ===")
//            appendLine("Audio Quality Optimizer: Initialized")
//            appendLine("Advanced Audio Processor: ${if (advancedAudioProcessor != null) "Active" else "Inactive"}")
//            appendLine("Speech-to-Text: ${if (sttEnabled) "Enabled" else "Disabled"}")
//            appendLine("Audio Interception: ${if (audioInterceptionActive) "Active" else "Inactive"}")
//            appendLine("OpenAI Enhanced: ${if (isOpenAiEnabled) "Active" else "Inactive"}")
//
//            // Audio quality analysis
//            try {
//                val testAudio = ByteArray(1024) // Test buffer
//                val qualityMetrics = audioQualityOptimizer.analyzeAudioQuality(testAudio, 16000)
//                appendLine("\n=== AUDIO QUALITY METRICS ===")
//                appendLine("Quality Score: ${qualityMetrics.qualityScore}%")
//                appendLine("SNR: ${qualityMetrics.snr} dB")
//                appendLine("Dynamic Range: ${qualityMetrics.dynamicRange} dB")
//                appendLine("Voice Activity Probability: ${qualityMetrics.voiceActivityProbability}")
//            } catch (e: Exception) {
//                appendLine("Quality Analysis Error: ${e.message}")
//            }
//        }
//    }
//
//    /**
//     * Cleanup mejorado con liberación de recursos adicionales
//     */
//    @RequiresApi(Build.VERSION_CODES.O)
//    override fun dispose() {
//        try {
//            audioInterceptionActive = false
//            speechToTextManager.stopRealTimeTranscription()
//            speechToTextManager.release()
//            advancedAudioProcessor.release()
//            interceptedAudioChannel.close()
//        } catch (e: Exception) {
//            log.e(TAG) { "Error disposing enhanced components: ${e.message}" }
//        }
//
//        super.dispose()
//    }
//
//    /**
//     * Obtiene estadísticas de calidad de audio en tiempo real
//     */
//    fun getAudioQualityStats(): AudioQualityStats {
//        return AudioQualityStats(
//            isEnhancedProcessingActive = audioInterceptionActive,
//            isSttEnabled = sttEnabled,
//            isOpenAiActive = isOpenAiEnabled,
//            interceptedChannelSize = interceptedAudioChannel.tryReceive().let {
//                if (it.isSuccess) 1 else 0
//            }
//        )
//    }
//
//    data class AudioQualityStats(
//        val isEnhancedProcessingActive: Boolean,
//        val isSttEnabled: Boolean,
//        val isOpenAiActive: Boolean,
//        val interceptedChannelSize: Int
//    )
//}