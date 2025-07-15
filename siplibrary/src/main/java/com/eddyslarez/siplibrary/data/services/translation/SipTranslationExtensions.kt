package com.eddyslarez.siplibrary.data.services.translation

import com.eddyslarez.siplibrary.data.models.CallData
import com.eddyslarez.siplibrary.utils.log
/**
 * Extensiones SIP para soporte de traducción
 */
object SipTranslationExtensions {

    private const val HEADER_TRANSLATION_SUPPORT = "X-Translation-Support"
    private const val HEADER_TRANSLATION_LANGUAGE = "X-Translation-Language"
    private const val HEADER_TRANSLATION_ENABLED = "X-Translation-Enabled"

    /**
     * Agregar headers de traducción al mensaje SIP
     */
    fun addTranslationHeaders(
        sipMessage: String,
        supportsTranslation: Boolean,
        preferredLanguage: String,
        translationEnabled: Boolean
    ): String {
        val lines = sipMessage.split("\r\n").toMutableList()

        // Encontrar donde insertar los headers (antes del Content-Length)
        var insertIndex = -1
        for (i in lines.indices) {
            if (lines[i].startsWith("Content-Length:")) {
                insertIndex = i
                break
            }
        }

        if (insertIndex == -1) {
            insertIndex = lines.size - 2 // Antes de la línea vacía final
        }

        // Insertar headers de traducción
        lines.add(insertIndex, "$HEADER_TRANSLATION_SUPPORT: ${if (supportsTranslation) "yes" else "no"}")
        lines.add(insertIndex + 1, "$HEADER_TRANSLATION_LANGUAGE: $preferredLanguage")
        lines.add(insertIndex + 2, "$HEADER_TRANSLATION_ENABLED: ${if (translationEnabled) "yes" else "no"}")

        return lines.joinToString("\r\n")
    }

    /**
     * Extraer información de traducción de headers SIP
     */
    fun extractTranslationInfo(sipMessage: String): TranslationCapability {
        val lines = sipMessage.split("\r\n")

        var supportsTranslation = false
        var preferredLanguage = "en"
        var translationEnabled = false

        for (line in lines) {
            when {
                line.startsWith("$HEADER_TRANSLATION_SUPPORT:") -> {
                    supportsTranslation = line.substringAfter(":").trim().equals("yes", ignoreCase = true)
                }
                line.startsWith("$HEADER_TRANSLATION_LANGUAGE:") -> {
                    preferredLanguage = line.substringAfter(":").trim()
                }
                line.startsWith("$HEADER_TRANSLATION_ENABLED:") -> {
                    translationEnabled = line.substringAfter(":").trim().equals("yes", ignoreCase = true)
                }
            }
        }

        return TranslationCapability(
            supportsTranslation = supportsTranslation,
            preferredLanguage = preferredLanguage,
            translationEnabled = translationEnabled
        )
    }

    /**
     * Agregar información de traducción al SDP
     */
    fun addTranslationToSdp(originalSdp: String, capability: TranslationCapability): String {
        val lines = originalSdp.split("\r\n").toMutableList()

        // Agregar atributos de traducción después de las líneas m=
        val translationAttributes = listOf(
            "a=translation-support:${if (capability.supportsTranslation) "yes" else "no"}",
            "a=translation-language:${capability.preferredLanguage}",
            "a=translation-enabled:${if (capability.translationEnabled) "yes" else "no"}"
        )

        // Encontrar la primera línea m= y agregar después
        for (i in lines.indices) {
            if (lines[i].startsWith("m=")) {
                // Insertar después de la línea m=
                translationAttributes.reversed().forEach { attr ->
                    lines.add(i + 1, attr)
                }
                break
            }
        }

        return lines.joinToString("\r\n")
    }

    /**
     * Extraer información de traducción del SDP
     */
    fun extractTranslationFromSdp(sdp: String): String? {
        val lines = sdp.split("\r\n")

        for (line in lines) {
            if (line.startsWith("a=translation-language:")) {
                return line.substringAfter(":").trim()
            }
        }

        return null
    }

    /**
     * Validar compatibilidad de traducción entre dos endpoints
     */
    fun validateTranslationCompatibility(
        local: TranslationCapability,
        remote: TranslationCapability
    ): TranslationCompatibilityResult {

        if (!local.supportsTranslation || !remote.supportsTranslation) {
            return TranslationCompatibilityResult.NOT_SUPPORTED
        }

        if (!local.translationEnabled && !remote.translationEnabled) {
            return TranslationCompatibilityResult.NOT_SUPPORTED
        }

        // Verificar si los idiomas son compatibles
        val localSupported = local.supportedLanguages.contains(remote.preferredLanguage)
        val remoteSupported = remote.supportedLanguages.contains(local.preferredLanguage)

        return when {
            localSupported && remoteSupported -> TranslationCompatibilityResult.FULLY_SUPPORTED
            localSupported || remoteSupported -> TranslationCompatibilityResult.PARTIALLY_SUPPORTED
            else -> TranslationCompatibilityResult.LANGUAGE_MISMATCH
        }
    }

    /**
     * Determinar idiomas de traducción basado en preferencias
     */
    fun determineTranslationLanguages(
        localLanguage: String,
        remoteLanguage: String
    ): Pair<String, String> {
        return if (localLanguage != remoteLanguage) {
            Pair(localLanguage, remoteLanguage)
        } else {
            // Si ambos hablan el mismo idioma, no se necesita traducción
            Pair(localLanguage, localLanguage)
        }
    }

    /**
     * Verificar si el mensaje SIP indica soporte de traducción
     */
    fun hasTranslationSupport(sipMessage: String): Boolean {
        return sipMessage.contains(HEADER_TRANSLATION_SUPPORT) ||
                sipMessage.contains("a=translation-support:")
    }

    /**
     * Crear mensaje de respuesta con información de traducción
     */
    fun createTranslationResponse(
        originalMessage: String,
        capability: TranslationCapability
    ): String {
        return addTranslationHeaders(
            originalMessage,
            capability.supportsTranslation,
            capability.preferredLanguage,
            capability.translationEnabled
        )
    }
}
