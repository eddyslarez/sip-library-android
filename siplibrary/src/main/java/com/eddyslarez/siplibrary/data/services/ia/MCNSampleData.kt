package com.eddyslarez.siplibrary.data.services.ia

// === DATOS DE MUESTRA PARA TESTING MCN ASSISTANT ===

object MCNSampleData {

    // === USUARIOS DE MUESTRA ===

    // Cliente empresarial con saldo positivo y múltiples números
    val empresarialUser = MCNAssistantClient.UserInfo(
        id = 170029L,
        fullName = "Eddys Jose Larez Guevara",
        email = "eddyslarez@gmail.com",
        phone = "+7 (995) 888 75 84",
        selectedContractId = 136711L,
        selectedAccount = MCNAssistantClient.AccountInfo(
            id = 136711L,
            contractId = 136711L,
            credit = 25000.00,
            balance = 8750.50,
            currency = "RUB"
        ),
        isBlocked = false,
        isVerified = true
    )

    // Cliente con cuenta bloqueada y saldo negativo
    val blockedUser = MCNAssistantClient.UserInfo(
        id = 175543L,
        fullName = "Иван Петров",
        email = "ivan.petrov@example.ru",
        phone = "+7 (812) 345 67 89",
        selectedContractId = 138945L,
        selectedAccount = MCNAssistantClient.AccountInfo(
            id = 138945L,
            contractId = 138945L,
            credit = 5000.00,
            balance = -1250.75,
            currency = "RUB"
        ),
        isBlocked = true,
        isVerified = true
    )

    // Cliente personal sin verificar
    val unverifiedUser = MCNAssistantClient.UserInfo(
        id = 182967L,
        fullName = "María González Rodríguez",
        email = "maria.gonzalez@gmail.com",
        phone = "+7 (495) 123 45 67",
        selectedContractId = 140123L,
        selectedAccount = MCNAssistantClient.AccountInfo(
            id = 140123L,
            contractId = 140123L,
            credit = 3000.00,
            balance = 500.25,
            currency = "RUB"
        ),
        isBlocked = false,
        isVerified = false
    )

    // Cliente VIP con alto saldo
    val vipUser = MCNAssistantClient.UserInfo(
        id = 156789L,
        fullName = "Александр Смирнов",
        email = "a.smirnov@businesscorp.ru",
        phone = "+7 (499) 987 65 43",
        selectedContractId = 135421L,
        selectedAccount = MCNAssistantClient.AccountInfo(
            id = 135421L,
            contractId = 135421L,
            credit = 100000.00,
            balance = 45780.90,
            currency = "RUB"
        ),
        isBlocked = false,
        isVerified = true
    )

    // === CUENTAS SIP DE MUESTRA ===

    // Números para usuario empresarial (múltiples números activos)
    val empresarialSipAccounts = mapOf(
        "+7 (995) 888 75 84" to MCNAssistantClient.SipAccountInfo(
            name = "90544778",
            phone = "+7 (995) 888 75 84",
            accountId = 136771L,
            usageType = "MOBILE",
            product = "PHONE",
            server = "sip.mcn.ru",
            outboundDid = "79958887584",
            isEnabled = true,
            isCurrent = true
        ),
        "+7 (993) 997 88 29" to MCNAssistantClient.SipAccountInfo(
            name = "90545500",
            phone = "+7 (993) 997 88 29",
            accountId = 136711L,
            usageType = "MOBILE",
            product = "PHONE",
            server = "sip.spb.mcn.ru",
            outboundDid = "79939978829",
            isEnabled = false,
            isCurrent = false
        ),
        "+7 (812) 456 78 90" to MCNAssistantClient.SipAccountInfo(
            name = "90546123",
            phone = "+7 (812) 456 78 90",
            accountId = 136711L,
            usageType = "FIXED",
            product = "OFFICE_LINE",
            server = "sip.spb.mcn.ru",
            outboundDid = "78124567890",
            isEnabled = true,
            isCurrent = false
        )
    )

    // Números para usuario bloqueado (números inactivos)
    val blockedUserSipAccounts = mapOf(
        "+7 (812) 345 67 89" to MCNAssistantClient.SipAccountInfo(
            name = "90547891",
            phone = "+7 (812) 345 67 89",
            accountId = 138945L,
            usageType = "MOBILE",
            product = "PHONE",
            server = "sip.spb.mcn.ru",
            outboundDid = "78123456789",
            isEnabled = false,
            isCurrent = true
        ),
        "+7 (495) 234 56 78" to MCNAssistantClient.SipAccountInfo(
            name = "90548234",
            phone = "+7 (495) 234 56 78",
            accountId = 138945L,
            usageType = "FIXED",
            product = "HOME_LINE",
            server = "sip.msk.mcn.ru",
            outboundDid = "74952345678",
            isEnabled = false,
            isCurrent = false
        )
    )

    // Número único para usuario no verificado
    val unverifiedUserSipAccounts = mapOf(
        "+7 (495) 123 45 67" to MCNAssistantClient.SipAccountInfo(
            name = "90549876",
            phone = "+7 (495) 123 45 67",
            accountId = 140123L,
            usageType = "MOBILE",
            product = "PHONE",
            server = "sip.msk.mcn.ru",
            outboundDid = "74951234567",
            isEnabled = true,
            isCurrent = true
        )
    )

    // Múltiples números VIP (líneas empresariales premium)
    val vipUserSipAccounts = mapOf(
        "+7 (499) 987 65 43" to MCNAssistantClient.SipAccountInfo(
            name = "90550001",
            phone = "+7 (499) 987 65 43",
            accountId = 135421L,
            usageType = "PREMIUM",
            product = "VIP_LINE",
            server = "sip.premium.mcn.ru",
            outboundDid = "74999876543",
            isEnabled = true,
            isCurrent = true
        ),
        "+7 (495) 100 20 30" to MCNAssistantClient.SipAccountInfo(
            name = "90550002",
            phone = "+7 (495) 100 20 30",
            accountId = 135421L,
            usageType = "CONFERENCE",
            product = "CONFERENCE_LINE",
            server = "sip.premium.mcn.ru",
            outboundDid = "74951002030",
            isEnabled = true,
            isCurrent = false
        ),
        "+7 (800) 555 01 23" to MCNAssistantClient.SipAccountInfo(
            name = "90550003",
            phone = "+7 (800) 555 01 23",
            accountId = 135421L,
            usageType = "TOLL_FREE",
            product = "FREE_LINE",
            server = "sip.premium.mcn.ru",
            outboundDid = "78005550123",
            isEnabled = true,
            isCurrent = false
        )
    )

    // === ESCENARIOS COMPLETOS DE TESTING ===

    data class TestScenario(
        val name: String,
        val description: String,
        val userInfo: MCNAssistantClient.UserInfo,
        val sipAccounts: Map<String, MCNAssistantClient.SipAccountInfo>,
        val expectedIssues: List<String> = emptyList()
    )

    val testScenarios = listOf(
        TestScenario(
            name = "Cliente Empresarial Normal",
            description = "Cliente verificado con saldo positivo y múltiples números",
            userInfo = empresarialUser,
            sipAccounts = empresarialSipAccounts,
            expectedIssues = listOf("Un número inactivo que podría requerir reactivación")
        ),

        TestScenario(
            name = "Cliente Bloqueado",
            description = "Cuenta bloqueada por saldo negativo, números desactivados",
            userInfo = blockedUser,
            sipAccounts = blockedUserSipAccounts,
            expectedIssues = listOf(
                "Cuenta bloqueada",
                "Saldo negativo",
                "Números desactivados",
                "Requiere pago inmediato"
            )
        ),

        TestScenario(
            name = "Cliente Sin Verificar",
            description = "Usuario nuevo sin verificación completa",
            userInfo = unverifiedUser,
            sipAccounts = unverifiedUserSipAccounts,
            expectedIssues = listOf(
                "Cuenta sin verificar",
                "Funcionalidades limitadas"
            )
        ),

        TestScenario(
            name = "Cliente VIP",
            description = "Cliente premium con múltiples servicios y alto saldo",
            userInfo = vipUser,
            sipAccounts = vipUserSipAccounts,
            expectedIssues = emptyList()
        )
    )

    // === DATOS ADICIONALES PARA TESTING ===

    val sampleBalanceInfo = MCNAssistantClient.BalanceInfo(
        currentBalance = 8750.50,
        availableCredit = 25000.00,
        currency = "RUB",
        lastPaymentDate = "2024-08-20",
        nextDueDate = "2024-09-20"
    )

    val sampleOperators = listOf(
        MCNAssistantClient.Operator(
            id = "OP001",
            name = "Анна Иванова",
            isAvailable = true,
            specialization = "Técnico SIP"
        ),
        MCNAssistantClient.Operator(
            id = "OP002",
            name = "Дмитрий Козлов",
            isAvailable = true,
            specialization = "Facturación"
        ),
        MCNAssistantClient.Operator(
            id = "OP003",
            name = "Elena Rodríguez",
            isAvailable = false,
            specialization = "Soporte VIP"
        )
    )

    // === FUNCIONES PARA CARGAR DATOS DE PRUEBA ===

    fun getRandomTestScenario(): TestScenario {
        return testScenarios.random()
    }

    fun getScenarioByType(type: String): TestScenario? {
        return when (type.lowercase()) {
            "empresarial", "business" -> testScenarios[0]
            "bloqueado", "blocked" -> testScenarios[1]
            "sin_verificar", "unverified" -> testScenarios[2]
            "vip", "premium" -> testScenarios[3]
            else -> null
        }
    }

    fun loadTestDataToAssistant(assistant: MCNAssistantClient, scenarioType: String = "empresarial") {
        val scenario = getScenarioByType(scenarioType) ?: testScenarios[0]

        assistant.updateUserInfo(scenario.userInfo)
        assistant.updateSipAccounts(scenario.sipAccounts)

        println("✅ Datos de prueba cargados: ${scenario.name}")
        println("👤 Cliente: ${scenario.userInfo.fullName}")
        println("💰 Saldo: ${scenario.userInfo.selectedAccount.balance} ${scenario.userInfo.selectedAccount.currency}")
        println("📱 Números: ${scenario.sipAccounts.size}")
        println("⚠️  Problemas esperados: ${scenario.expectedIssues.joinToString(", ")}")
    }
}

// === EJEMPLO DE USO ===
/*
// En tu actividad o servicio de testing:

class MCNTestingService {
    private lateinit var assistant: MCNAssistantClient

    fun initializeWithTestData(scenarioType: String = "empresarial") {
        assistant = MCNAssistantClient("your-api-key")

        // Cargar datos de prueba
        MCNSampleData.loadTestDataToAssistant(assistant, scenarioType)

        // Configurar listeners para ver respuestas
        assistant.setInputTranscriptionListener { transcript ->
            println("🎤 Cliente dice: $transcript")
        }

        assistant.setApiCallRequestListener { request ->
            println("🔍 Asistente solicita: ${request.action}")
            handleTestApiRequest(request)
        }
    }

    private fun handleTestApiRequest(request: MCNAssistantClient.ApiCallRequest) {
        // Simular respuestas de API con datos de prueba
        when (request.action) {
            "GET_BALANCE" -> {
                // Devolver balance de prueba
                println("💰 Devolviendo información de saldo...")
            }
            "GET_SIP_ACCOUNTS" -> {
                // Devolver cuentas SIP de prueba
                println("📞 Devolviendo información de números...")
            }
            // ... más casos
        }
    }
}

// Para probar diferentes escenarios:
val testService = MCNTestingService()

// Cliente normal
testService.initializeWithTestData("empresarial")

// Cliente con problemas
testService.initializeWithTestData("bloqueado")

// Cliente VIP
testService.initializeWithTestData("vip")
*/