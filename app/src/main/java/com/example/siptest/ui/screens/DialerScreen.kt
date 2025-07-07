@Composable
fun DialerScreen(
    sipViewModel: SipViewModel
) {
    val uiState by sipViewModel.uiState.collectAsState()
    val registrationState by sipViewModel.registrationState.collectAsState()
    
    // OPTIMIZADO: Estados unificados
    val callState by sipViewModel.callState.collectAsState()
    val registrationStates by sipViewModel.registrationStates.collectAsState()
    val callDuration by sipViewModel.callDuration.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        // OPTIMIZADO: Estado de la llamada con información unificada
        CallStatusCard(
            callState = callState,
            message = uiState.callMessage,
            detailedMessage = uiState.detailedCallMessage,
            hasError = uiState.hasCallError,
            errorReason = uiState.errorReason,
            duration = callDuration
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Estado de múltiples cuentas
        if (registrationStates.isNotEmpty()) {
            MultiAccountStatusCard(
                registrationStates = registrationStates,
                multiAccountStatus = uiState.multiAccountStatus
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Número marcado
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Text(
                text = if (uiState.dialedNumber.isEmpty()) "Enter number" else uiState.dialedNumber,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                color = if (uiState.dialedNumber.isEmpty())
                    MaterialTheme.colorScheme.onSurfaceVariant
                else
                    MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Teclado numérico
        ModernDialPad(
            onDigitClick = { digit ->
                sipViewModel.updateDialedNumber(uiState.dialedNumber + digit)
            },
            onBackspaceClick = {
                val currentNumber = uiState.dialedNumber
                if (currentNumber.isNotEmpty()) {
                    sipViewModel.updateDialedNumber(currentNumber.dropLast(1))
                }
            },
            onCallClick = {
                if (uiState.dialedNumber.isNotEmpty()) {
                    sipViewModel.makeCall(uiState.dialedNumber)
                }
            },
            enabled = registrationState == RegistrationState.OK && !callState.state.isCallActive(),
            hasNumber = uiState.dialedNumber.isNotEmpty()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Estado de registro
        if (registrationState != RegistrationState.OK) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "⚠️ Please register a SIP account first",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Información de debugging (solo en modo debug)
        if (BuildConfig.DEBUG) {
            Spacer(modifier = Modifier.height(16.dp))
            DebugInfoCard(
                callState = callState,
                lastTransition = uiState.lastStateTransition,
                onShowDiagnostic = {
                    Log.d("SipDebug", sipViewModel.getSystemDiagnostic())
                }
            )
        }
    }
}

// OPTIMIZADO: Card para estado unificado de llamada
@Composable
fun CallStatusCard(
    callState: CallStateInfo,
    message: String,
    detailedMessage: String,
    hasError: Boolean,
    errorReason: String?,
    duration: Long
) {
    val (containerColor, contentColor, icon) = when {
        hasError -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "❌"
        )
        callState.state == DetailedCallState.STREAMS_RUNNING -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            "📞"
        )
        callState.state.isCallActive() -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            "📱"
        )
        callState.state == DetailedCallState.INCOMING_RECEIVED -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            "📲"
        )
        else -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "📴"
        )
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = containerColor
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = icon,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = callState.state.getDisplayText(),
                        color = contentColor,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (detailedMessage.isNotEmpty()) {
                        Text(
                            text = detailedMessage,
                            color = contentColor,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                
                // Mostrar duración si está en llamada
                if (duration > 0 && callState.state == DetailedCallState.STREAMS_RUNNING) {
                    Text(
                        text = formatDuration(duration),
                        color = contentColor,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            // Mostrar información de error si existe
            if (hasError && errorReason != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Error: ${SipErrorMapper.getErrorDescription(CallErrorReason.valueOf(errorReason))}",
                    color = contentColor,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic
                )
            }
            
            // Mostrar código SIP si está disponible
            if (callState.sipCode != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "SIP: ${callState.sipCode} ${callState.sipReason ?: ""}",
                    color = contentColor.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

// Card para estado de múltiples cuentas
@Composable
fun MultiAccountStatusCard(
    registrationStates: Map<String, RegistrationState>,
    multiAccountStatus: String
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = "📋 $multiAccountStatus",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            registrationStates.forEach { (account, state) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = account,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    
                    val (stateIcon, stateColor) = when (state) {
                        RegistrationState.OK -> "✅" to MaterialTheme.colorScheme.primary
                        RegistrationState.FAILED -> "❌" to MaterialTheme.colorScheme.error
                        RegistrationState.IN_PROGRESS -> "🔄" to MaterialTheme.colorScheme.secondary
                        else -> "⚪" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    
                    Text(
                        text = stateIcon,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

// OPTIMIZADO: Card de información de debugging
@Composable
fun DebugInfoCard(
    callState: CallStateInfo,
    lastTransition: String,
    onShowDiagnostic: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            Text(
                text = "🔧 Debug Info",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = "Last transition: $lastTransition",
                style = MaterialTheme.typography.labelSmall
            )
            
            Text(
                text = "Call ID: ${callState.callId}",
                style = MaterialTheme.typography.labelSmall
            )
            
            Text(
                text = "Direction: ${callState.direction.name}",
                style = MaterialTheme.typography.labelSmall
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Button(
                onClick = onShowDiagnostic,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text(
                    text = "Show Full Diagnostic",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

// Función auxiliar para formatear duración
private fun formatDuration(durationMs: Long): String {
    val seconds = (durationMs / 1000) % 60
    val minutes = (durationMs / (1000 * 60)) % 60
    val hours = (durationMs / (1000 * 60 * 60))

    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}

// Resto de componentes existentes...
@Composable
fun ModernDialPad(
    onDigitClick: (String) -> Unit,
    onBackspaceClick: () -> Unit,
    onCallClick: () -> Unit,
    enabled: Boolean,
    hasNumber: Boolean
) {
    // ... implementación existente
}

// ... resto de componentes