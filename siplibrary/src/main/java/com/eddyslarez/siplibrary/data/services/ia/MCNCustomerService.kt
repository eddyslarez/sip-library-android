package com.eddyslarez.siplibrary.data.services.ia
//
//import android.util.Log
//import kotlinx.coroutines.CoroutineScope
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.launch
//
//class MCNCustomerService {
//    private var mcnAssistant: MCNAssistantClient? = null
//    private var apiService: MCNApiService? = null // Tu servicio API existente
//
//    companion object {
//        private const val TAG = "MCNCustomerService"
//    }
//
//    fun initializeAssistant(apiKey: String) {
//        mcnAssistant = MCNAssistantClient(apiKey).apply {
//
//            // Configurar listeners
//            setConnectionStateListener { isConnected ->
//                if (isConnected) {
//                    Log.d(TAG, "MCN Assistant conectado y listo")
//                } else {
//                    Log.w(TAG, "MCN Assistant desconectado")
//                }
//            }
//
//            setErrorListener { error ->
//                Log.e(TAG, "Error en MCN Assistant: $error")
//                // Manejar errores, posiblemente mostrar mensaje al cliente
//            }
//
//            setAudioReceivedListener { audioData ->
//                // Reproducir respuesta del asistente al cliente
//                playAudioToCustomer(audioData)
//            }
//
//            setInputTranscriptionListener { transcript ->
//                Log.d(TAG, "Cliente dijo: $transcript")
//                // Opcional: mostrar transcripción en interfaz
//            }
//
//            // IMPORTANTE: Configurar listener para llamadas API
//            setApiCallRequestListener { apiRequest ->
//                handleApiRequest(apiRequest)
//            }
//        }
//    }
//
//    suspend fun startCustomerCall() {
//        mcnAssistant?.connect()?.let { connected ->
//            if (connected) {
//                Log.d(TAG, "Llamada iniciada con MCN Assistant")
//
//                // Cargar información del cliente al inicio
//                loadCustomerData()
//            } else {
//                Log.e(TAG, "Error iniciando llamada")
//            }
//        }
//    }
//
//    private suspend fun loadCustomerData() {
//        try {
//            // Obtener datos del cliente (usando tus APIs existentes)
//            val userState = apiService?.getUserState()
//            val sipAccounts = apiService?.getSipAccounts()
//
//            userState?.let { state ->
//                // Convertir tus datos al formato del asistente
//                val userInfo = MCNAssistantClient.UserInfo(
//                    id = state.user.id,
//                    fullName = state.user.fullName,
//                    email = state.user.email,
//                    phone = state.user.phone,
//                    selectedContractId = state.user.selectedContractId,
//                    selectedAccount = MCNAssistantClient.AccountInfo(
//                        id = state.user.selectedAccount.id,
//                        contractId = state.user.selectedAccount.contractId,
//                        credit = state.user.selectedAccount.credit,
//                        balance = state.user.selectedAccount.balance,
//                        currency = state.user.selectedAccount.currency
//                    ),
//                    isBlocked = state.user.isBlocked,
//                    isVerified = state.user.isVerified
//                )
//
//                // Convertir cuentas SIP
//                val sipAccountsInfo = sipAccounts?.mapValues { (phone, account) ->
//                    MCNAssistantClient.SipAccountInfo(
//                        name = account.name,
//                        phone = phone,
//                        accountId = account.accountId,
//                        usageType = account.usageType,
//                        product = account.product,
//                        server = account.server,
//                        outboundDid = account.outboundDid,
//                        isEnabled = account.isEnabled,
//                        isCurrent = account.isCurrent
//                    )
//                } ?: emptyMap()
//
//                // Actualizar información en el asistente
//                mcnAssistant?.updateUserInfo(userInfo)
//                mcnAssistant?.updateSipAccounts(sipAccountsInfo)
//
//                Log.d(TAG, "Datos del cliente cargados: ${userInfo.fullName}")
//            }
//
//        } catch (e: Exception) {
//            Log.e(TAG, "Error cargando datos del cliente: ${e.message}")
//        }
//    }
//
//    private fun handleApiRequest(request: MCNAssistantClient.ApiCallRequest) {
//        Log.d(TAG, "Asistente solicita: ${request.action}")
//
//        when (request.action) {
//            "GET_BALANCE" -> {
//                // Obtener saldo actualizado
//                CoroutineScope(Dispatchers.IO).launch {
//                    try {
//                        val balance = apiService?.getCurrentBalance()
//                        balance?.let {
//                            sendBalanceInfoToAssistant(it)
//                        }
//                    } catch (e: Exception) {
//                        Log.e(TAG, "Error obteniendo saldo: ${e.message}")
//                        sendErrorToAssistant("No se pudo obtener el saldo actual")
//                    }
//                }
//            }
//
//            "GET_SIP_ACCOUNTS" -> {
//                // Obtener información actualizada de números
//                CoroutineScope(Dispatchers.IO).launch {
//                    try {
//                        val sipAccounts = apiService?.getSipAccounts()
//                        sipAccounts?.let {
//                            sendSipAccountsInfoToAssistant(it)
//                        }
//                    } catch (e: Exception) {
//                        Log.e(TAG, "Error obteniendo números: ${e.message}")
//                        sendErrorToAssistant("No se pudo obtener información de números")
//                    }
//                }
//            }
//
//            "GET_USER_INFO" -> {
//                // Refrescar información del usuario
//                CoroutineScope(Dispatchers.IO).launch {
//                    try {
//                        loadCustomerData()
//                    } catch (e: Exception) {
//                        Log.e(TAG, "Error actualizando información: ${e.message}")
//                    }
//                }
//            }
//
//            "TRANSFER_TO_OPERATOR" -> {
//                // Transferir a operador humano
//                transferToHumanOperator()
//            }
//
//            "RECHARGE_ACCOUNT" -> {
//                // Iniciar proceso de recarga
//                val amount = request.parameters["amount"] as? Double
//                initiateAccountRecharge(amount)
//            }
//
//            "ACTIVATE_NUMBER" -> {
//                // Activar número específico
//                val phoneNumber = request.parameters["phone"] as? String
//                phoneNumber?.let { activatePhoneNumber(it) }
//            }
//
//            "DEACTIVATE_NUMBER" -> {
//                // Desactivar número específico
//                val phoneNumber = request.parameters["phone"] as? String
//                phoneNumber?.let { deactivatePhoneNumber(it) }
//            }
//
//            else -> {
//                Log.w(TAG, "Acción no reconocida: ${request.action}")
//            }
//        }
//    }
//
//    private suspend fun sendBalanceInfoToAssistant(balance: BalanceInfo) {
//        val balanceMessage = """
//            INFORMACIÓN DE SALDO ACTUALIZADA:
//            Saldo actual: ${balance.currentBalance} ${balance.currency}
//            Crédito disponible: ${balance.availableCredit} ${balance.currency}
//            Estado de cuenta: ${if (balance.currentBalance < 0) "EN ROJO" else "POSITIVO"}
//            Último pago: ${balance.lastPaymentDate ?: "Sin registro"}
//            Próximo vencimiento: ${balance.nextDueDate ?: "No aplica"}
//        """.trimIndent()
//
//        sendContextMessageToAssistant(balanceMessage)
//    }
//
//    private suspend fun sendSipAccountsInfoToAssistant(sipAccounts: Map<String, SipAccount>) {
//        val accountsMessage = buildString {
//            appendLine("INFORMACIÓN ACTUALIZADA DE NÚMEROS:")
//            sipAccounts.forEach { (phone, account) ->
//                appendLine("📞 $phone:")
//                appendLine("  - Estado: ${if (account.isEnabled) "ACTIVO ✅" else "INACTIVO ❌"}")
//                appendLine("  - Servidor: ${account.server}")
//                appendLine("  - Tipo: ${account.usageType}")
//                appendLine("  - Producto: ${account.product}")
//                if (account.isCurrent) {
//                    appendLine("  - ⭐ NÚMERO PRINCIPAL")
//                }
//                appendLine()
//            }
//        }
//
//        sendContextMessageToAssistant(accountsMessage)
//    }
//
//    private suspend fun sendErrorToAssistant(errorMessage: String) {
//        val message = "ERROR: $errorMessage. Por favor, informa al cliente que puede intentar más tarde o solicitar ayuda de un operador."
//        sendContextMessageToAssistant(message)
//    }
//
//    private suspend fun sendContextMessageToAssistant(message: String) {
//        // Este método enviaría información actualizada al asistente
//        // Implementación depende de cómo quieras inyectar información al contexto
//        Log.d(TAG, "Enviando contexto al asistente: $message")
//
//        // Podrías implementar esto enviando un mensaje de contexto al WebSocket
//        // o guardando la información para incluirla en la próxima interacción
//    }
//
//    private fun transferToHumanOperator() {
//        Log.d(TAG, "Transfiriendo llamada a operador humano...")
//
//        // Implementar transferencia real
//        try {
//            // 1. Notificar al cliente sobre la transferencia
//            announceTransfer()
//
//            // 2. Pausar el asistente IA
//            pauseAssistant()
//
//            // 3. Conectar con operador disponible
//            val operator = findAvailableOperator()
//            if (operator != null) {
//                connectToOperator(operator)
//                Log.d(TAG, "Cliente transferido a operador: ${operator.name}")
//            } else {
//                // No hay operadores disponibles
//                announceWaitingForOperator()
//                queueForOperator()
//            }
//
//        } catch (e: Exception) {
//            Log.e(TAG, "Error en transferencia: ${e.message}")
//            announceTransferError()
//        }
//    }
//
//    private fun initiateAccountRecharge(amount: Double?) {
//        Log.d(TAG, "Iniciando proceso de recarga: ${amount ?: "monto no especificado"}")
//
//        try {
//            // 1. Generar enlace de pago o proporcionar instrucciones
//            val rechargeOptions = generateRechargeOptions(amount)
//
//            // 2. Enviar información al asistente para que la comparta con el cliente
//            val rechargeMessage = """
//                OPCIONES DE RECARGA DISPONIBLES:
//                ${rechargeOptions.joinToString("\n")}
//
//                Para proceder con la recarga, el cliente puede:
//                1. Usar el portal web de MCN
//                2. Llamar al número de recarga automática
//                3. Usar la aplicación móvil
//                4. Solicitar ayuda de un operador
//            """.trimIndent()
//
//            CoroutineScope(Dispatchers.IO).launch {
//                sendContextMessageToAssistant(rechargeMessage)
//            }
//
//        } catch (e: Exception) {
//            Log.e(TAG, "Error generando opciones de recarga: ${e.message}")
//        }
//    }
//
//    private fun activatePhoneNumber(phoneNumber: String) {
//        Log.d(TAG, "Activando número: $phoneNumber")
//
//        CoroutineScope(Dispatchers.IO).launch {
//            try {
//                val result = apiService?.activateNumber(phoneNumber)
//
//                if (result?.success == true) {
//                    sendContextMessageToAssistant(
//                        "✅ NÚMERO ACTIVADO: $phoneNumber está ahora activo y listo para usar."
//                    )
//                    // Refrescar información de números
//                    loadCustomerData()
//                } else {
//                    sendContextMessageToAssistant(
//                        "❌ ERROR: No se pudo activar $phoneNumber. ${result?.errorMessage ?: "Error desconocido"}"
//                    )
//                }
//
//            } catch (e: Exception) {
//                Log.e(TAG, "Error activando número: ${e.message}")
//                sendErrorToAssistant("Error técnico activando el número $phoneNumber")
//            }
//        }
//    }
//
//    private fun deactivatePhoneNumber(phoneNumber: String) {
//        Log.d(TAG, "Desactivando número: $phoneNumber")
//
//        CoroutineScope(Dispatchers.IO).launch {
//            try {
//                val result = apiService?.deactivateNumber(phoneNumber)
//
//                if (result?.success == true) {
//                    sendContextMessageToAssistant(
//                        "✅ NÚMERO DESACTIVADO: $phoneNumber ha sido desactivado correctamente."
//                    )
//                    loadCustomerData()
//                } else {
//                    sendContextMessageToAssistant(
//                        "❌ ERROR: No se pudo desactivar $phoneNumber. ${result?.errorMessage ?: "Error desconocido"}"
//                    )
//                }
//
//            } catch (e: Exception) {
//                Log.e(TAG, "Error desactivando número: ${e.message}")
//                sendErrorToAssistant("Error técnico desactivando el número $phoneNumber")
//            }
//        }
//    }
//
//    // === MÉTODOS DE AUDIO ===
//    fun sendCustomerAudio(audioData: ByteArray) {
//        CoroutineScope(Dispatchers.IO).launch {
//            mcnAssistant?.addAudioData(audioData)
//        }
//    }
//
//    private fun playAudioToCustomer(audioData: ByteArray) {
//        try {
//            // Implementar reproducción de audio al cliente
//            // Esto depende de tu sistema de audio existente
//            audioPlayer?.playPCM16Audio(audioData)
//        } catch (e: Exception) {
//            Log.e(TAG, "Error reproduciendo audio: ${e.message}")
//        }
//    }
//
//    // === MÉTODOS DE TRANSFERENCIA Y OPERADORES ===
//    private fun announceTransfer() {
//        val message = "Un momento por favor, lo estoy transfiriendo con un operador especializado."
//        speakToCustomer(message)
//    }
//
//    private fun announceWaitingForOperator() {
//        val message = "Todos nuestros operadores están ocupados. Lo mantendremos en línea hasta que uno esté disponible."
//        speakToCustomer(message)
//    }
//
//    private fun announceTransferError() {
//        val message = "Lo siento, hay un problema técnico con la transferencia. ¿Puedo ayudarle con algo más mientras tanto?"
//        speakToCustomer(message)
//    }
//
//    private fun speakToCustomer(message: String) {
//        // Implementar síntesis de voz o usar el asistente para hablar
//        // Podrías usar el propio asistente o un TTS separado
//        Log.d(TAG, "Mensaje al cliente: $message")
//    }
//
//    private fun pauseAssistant() {
//        // Pausar el asistente mientras se transfiere
//        // Implementación específica según tus necesidades
//    }
//
//    private fun findAvailableOperator(): Operator? {
//        // Buscar operador disponible en tu sistema
//        return operatorManager.getAvailableOperator()
//    }
//
//    private fun connectToOperator(operator: Operator) {
//        // Conectar la llamada con el operador
//        callManager.transferCall(operator.id)
//    }
//
//    private fun queueForOperator() {
//        // Poner en cola para el siguiente operador disponible
//        operatorQueue.addCustomer(currentCustomerId)
//    }
//
//    private fun generateRechargeOptions(amount: Double?): List<String> {
//        return listOf(
//            "💳 Tarjeta de crédito/débito - Inmediato",
//            "🏦 Transferencia bancaria - 1-2 días hábiles",
//            "📱 Pago móvil - Inmediato",
//            "🏪 Puntos de recarga MCN - Inmediato",
//            if (amount != null) "💰 Monto sugerido: $amount RUB" else "💰 Monto mínimo: 100 RUB"
//        )
//    }
//
//    // === GESTIÓN DE SESIÓN ===
//    suspend fun endCustomerCall() {
//        try {
//            mcnAssistant?.disconnect()
//            Log.d(TAG, "Llamada MCN finalizada")
//        } catch (e: Exception) {
//            Log.e(TAG, "Error finalizando llamada: ${e.message}")
//        }
//    }
//
//    fun isAssistantReady(): Boolean {
//        return mcnAssistant?.isSessionReady() == true
//    }
//
//    fun isAssistantConnected(): Boolean {
//        return mcnAssistant?.isConnected() == true
//    }
//}
//
//// === DATA CLASSES AUXILIARES ===
//data class BalanceInfo(
//    val currentBalance: Double,
//    val availableCredit: Double,
//    val currency: String,
//    val lastPaymentDate: String?,
//    val nextDueDate: String?
//)
//
//data class Operator(
//    val id: String,
//    val name: String,
//    val isAvailable: Boolean,
//    val specialization: String
//)
//
//data class ApiResult(
//    val success: Boolean,
//    val errorMessage: String? = null
//)
//
//// === EJEMPLO DE USO EN ACTIVIDAD O SERVICIO ===
//class CustomerCallActivity : AppCompatActivity() {
//    private lateinit var mcnService: MCNCustomerService
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//
//        mcnService = MCNCustomerService()
//        mcnService.initializeAssistant("tu-api-key-de-openai")
//
//        lifecycleScope.launch {
//            if (mcnService.startCustomerCall()) {
//                // Llamada iniciada exitosamente
//                setupAudioRecording()
//            } else {
//                // Error iniciando llamada
//                showError("No se pudo iniciar el asistente")
//            }
//        }
//    }
//
//    private fun setupAudioRecording() {
//        // Configurar grabación de audio del cliente
//        audioRecorder.startRecording { audioData ->
//            mcnService.sendCustomerAudio(audioData)
//        }
//    }
//
//    override fun onDestroy() {
//        super.onDestroy()
//        lifecycleScope.launch {
//            mcnService.endCustomerCall()
//        }
//    }
//}