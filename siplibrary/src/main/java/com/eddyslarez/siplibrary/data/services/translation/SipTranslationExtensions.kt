package com.eddyslarez.siplibrary.data.services.translation

import com.eddyslarez.siplibrary.data.models.CallData
import com.eddyslarez.siplibrary.utils.log

/**
 * Extensiones SIP para detectar y manejar capacidades de traducción
 * 
 * @author Eddys Larez
 */
object SipTranslationExtensions {
    private const val TAG = "SipTranslationExtensions"
    
    // Headers personalizados para indicar soporte de traducción
    const val TRANSLATION_SUPPORT_HEADER = "X-Translation-Support"
    const val TRANSLATION_LANGUAGE_HEADER = "X-Translation-Language"
    const val TRANSLATION_ENABLED_HEADER = "X-Translation-Enabled"
    
    /**
     * Agregar headers de traducción a un mensaje SIP
     */
    fun addTranslationHeaders(
        sipMessage: String,
        supportsTranslation: Boolean,
        preferredLanguage: String,
        translationEnabled: Boolean
    ): String {
        if (!supportsTranslation) return sipMessage
        
        val headers = buildString {
            append("$TRANSLATION_SUPPORT_HEADER: true\r\n")
            append("$TRANSLATION_LANGUAGE_HEADER: $preferredLanguage\r\n")
            append("$TRANSLATION_ENABLED_HEADER: $translationEnabled\r\n")
        }
        
        // Insertar headers antes de Content-Length
        val contentLengthIndex = sipMessage.indexOf("Content-Length:")
        return if (contentLengthIndex != -1) {
            sipMessage.substring(0, contentLengthIndex) + 
            headers + 
            sipMessage.substring(contentLengthIndex)
        } else {
            // Si no hay Content-Length, agregar antes del final
            val endIndex = sipMessage.indexOf("\r\n\r\n")
            if (endIndex != -1) {
                sipMessage.substring(0, endIndex) + 
                "\r\n" + headers + 
                sipMessage.substring(endIndex)
            } else {
                sipMessage + headers
            }
        }
    }
    
    /**
     * Extraer información de traducción de un mensaje SIP
     */
    fun extractTranslationInfo(sipMessage: String): TranslationCapability {
        val lines = sipMessage.split("\r\n")
        
        val supportsTranslation = lines.any { 
            it.startsWith(TRANSLATION_SUPPORT_HEADER) && it.contains("true", ignoreCase = true)
        }
        
        val preferredLanguage = lines.find { 
            it.startsWith(TRANSLATION_LANGUAGE_HEADER) 
        }?.substringAfter(":")?.trim() ?: "en"
        
        val translationEnabled = lines.any { 
            it.startsWith(TRANSLATION_ENABLED_HEADER) && it.contains("true", ignoreCase = true)
        }
        
        return TranslationCapability(
            supportsTranslation = supportsTranslation,
            preferredLanguage = preferredLanguage,
            translationEnabled = translationEnabled
        )
    }
    
    /**
     * Verificar si ambas partes soportan traducción
     */
    fun canEnableTranslation(
        localCapability: TranslationCapability,
        remoteCapability: TranslationCapability
    ): Boolean {
        return localCapability.supportsTranslation && 
               remoteCapability.supportsTranslation &&
               localCapability.translationEnabled &&
               remoteCapability.translationEnabled
    }
    
    /**
     * Determinar idiomas de traducción para la llamada
     */
    fun determineTranslationLanguages(
        localLanguage: String,
        remoteLanguage: String
    ): Pair<String, String> {
        // Si los idiomas son diferentes, habilitar traducción
        return if (localLanguage != remoteLanguage) {
            Pair(localLanguage, remoteLanguage)
        } else {
            // Mismo idioma, no necesita traducción
            Pair(localLanguage, localLanguage)
        }
    }
    
    /**
     * Crear SDP modificado para incluir información de traducción
     */
    fun addTranslationToSdp(originalSdp: String, translationInfo: TranslationCapability): String {
        if (!translationInfo.supportsTranslation) return originalSdp
        
        val translationAttribute = "a=translation:${translationInfo.preferredLanguage}\r\n"
        
        // Agregar después de la línea de conexión
        val lines = originalSdp.split("\r\n").toMutableList()
        val connectionIndex = lines.indexOfFirst { it.startsWith("c=") }
        
        if (connectionIndex != -1 && connectionIndex + 1 < lines.size) {
            lines.add(connectionIndex + 1, translationAttribute.trimEnd())
        } else {
            // Si no hay línea de conexión, agregar al final de la sección de medios
            val mediaIndex = lines.indexOfFirst { it.startsWith("m=audio") }
            if (mediaIndex != -1) {
                lines.add(mediaIndex + 1, translationAttribute.trimEnd())
            }
        }
        
        return lines.joinToString("\r\n")
    }
    
    /**
     * Extraer información de traducción del SDP
     */
    fun extractTranslationFromSdp(sdp: String): String? {
        val lines = sdp.split("\r\n")
        val translationLine = lines.find { it.startsWith("a=translation:") }
        return translationLine?.substringAfter("a=translation:")?.trim()
    }
    
    /**
     * Validar compatibilidad de traducción
     */
    fun validateTranslationCompatibility(
        localCapability: TranslationCapability,
        remoteCapability: TranslationCapability
    ): TranslationCompatibilityResult {
        return when {
            !localCapability.supportsTranslation && !remoteCapability.supportsTranslation -> {
                TranslationCompatibilityResult.NOT_SUPPORTED
            }
            
            localCapability.supportsTranslation && !remoteCapability.supportsTranslation -> {
                TranslationCompatibilityResult.LOCAL_ONLY
            }
            
            !localCapability.supportsTranslation && remoteCapability.supportsTranslation -> {
                TranslationCompatibilityResult.REMOTE_ONLY
            }
            
            localCapability.supportsTranslation && remoteCapability.supportsTranslation -> {
                if (localCapability.translationEnabled && remoteCapability.translationEnabled) {
                    TranslationCompatibilityResult.FULLY_SUPPORTED
                } else {
                    TranslationCompatibilityResult.SUPPORTED_BUT_DISABLED
                }
            }
            
            else -> TranslationCompatibilityResult.NOT_SUPPORTED
        }
    }
}

/**
 * Información de capacidad de traducción
 */
data class TranslationCapability(
    val supportsTranslation: Boolean,
    val preferredLanguage: String,
    val translationEnabled: Boolean
)

/**
 * Resultado de compatibilidad de traducción
 */
enum class TranslationCompatibilityResult {
    NOT_SUPPORTED,
    LOCAL_ONLY,
    REMOTE_ONLY,
    SUPPORTED_BUT_DISABLED,
    FULLY_SUPPORTED
}

/**
 * Información de traducción para una llamada
 */
data class CallTranslationInfo(
    val isTranslationEnabled: Boolean,
    val localLanguage: String,
    val remoteLanguage: String,
    val translationDirection: TranslationDirection
)

enum class TranslationDirection {
    NONE,
    LOCAL_TO_REMOTE,
    REMOTE_TO_LOCAL,
    BIDIRECTIONAL
}