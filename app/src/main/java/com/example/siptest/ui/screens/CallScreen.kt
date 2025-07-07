@Composable
fun CallScreen(
    sipViewModel: SipViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by sipViewModel.uiState.collectAsState()
    val callState by sipViewModel.callState.collectAsState()
    
    // NUEVO: Estados detallados
    val detailedCallState by sipViewModel.detailedCallState.collectAsState()
    val callDuration by sipViewModel.callDuration.collectAsState()
    
    val currentCall = uiState.currentCall
    val incomingCall = uiState.incomingCall

    // MEJORADO: Navegar de vuelta cuando la llamada termine usando estados detallados
    LaunchedEffect(detailedCallState.state) {
        if (detailedCallState.state == DetailedCallState.ENDED || 
            detailedCallState.state == DetailedCallState.IDLE) {
            delay(2000) // Mostrar mensaje por 2 segundos
            onNavigateBack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // MEJORADO: Información de la llamada con estados detallados
        DetailedCallInfoSection(
            callState = callState,
            detailedState = detailedCallState,
            currentCall = currentCall,
            incomingCall = incomingCall,
            callDuration = callDuration,
            message = uiState.callMessage,
            detailedMessage = uiState.detailedCallMessage,
            hasError = uiState.hasCallError,
            errorReason = uiState.errorReason
        )

        // MEJORADO: Controles de llamada con estados detallados
        DetailedCallControlsSection(
            callState = callState,
            detailedState = detailedCallState,
            currentCall = currentCall,
            incomingCall = incomingCall,
            onAccept = { sipViewModel.acceptCall() },
            onDecline = { sipViewModel.declineCall() },
            onEnd = { sipViewModel.endCall() },
            onHold = { sipViewModel.holdCall() },
            onResume = { sipViewModel.resumeCall() },
            onMute = { sipViewModel.toggleMute() },
            onDtmf = { digit -> sipViewModel.sendDtmf(digit) }
        )
    }
}

// MEJORADO: Sección de información con estados detallados
@Composable
fun DetailedCallInfoSection(
    callState: CallState,
    detailedState: CallStateInfo,
    currentCall: CallInfo?,
    incomingCall: IncomingCallInfo?,
    callDuration: Long,
    message: String,
    detailedMessage: String,
    hasError: Boolean,
    errorReason: String?
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(vertical = 32.dp)
    ) {
        // Avatar/Icon con estado visual
        val avatarColor = when {
            hasError -> MaterialTheme.colorScheme.errorContainer
            detailedState.state == DetailedCallState.STREAMS_RUNNING -> MaterialTheme.colorScheme.primaryContainer
            detailedState.state.isCallActive() -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
        
        Card(
            modifier = Modifier.size(120.dp),
            shape = CircleShape,
            colors = CardDefaults.cardColors(containerColor = avatarColor)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = "Caller",
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                
                // Indicador de estado en la esquina
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(24.dp)
                        .background(
                            color = when (detailedState.state) {
                                DetailedCallState.STREAMS_RUNNING -> Color.Green
                                DetailedCallState.PAUSED -> Color.Yellow
                                DetailedCallState.ERROR -> Color.Red
                                else -> Color.Gray
                            },
                            shape = CircleShape
                        )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Nombre/Número
        val displayName = when {
            incomingCall != null -> incomingCall.callerName ?: incomingCall.callerNumber
            currentCall != null -> currentCall.phoneNumber
            else -> "Unknown"
        }

        Text(
            text = displayName,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Estado detallado de la llamada
        Text(
            text = detailedState.state.getDisplayText(),
            style = MaterialTheme.typography.bodyLarge,
            color = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (hasError) FontWeight.Bold else FontWeight.Normal
        )

        // Duración de la llamada
        if (detailedState.state == DetailedCallState.STREAMS_RUNNING && callDuration > 0) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatDuration(callDuration),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }

        // Mensaje detallado
        if (detailedMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = detailedMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        // Información de error
        if (hasError && errorReason != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "❌ ${SipErrorMapper.getErrorDescription(CallErrorReason.valueOf(errorReason))}",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Información SIP (solo en debug)
        if (BuildConfig.DEBUG && detailedState.sipCode != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "SIP: ${detailedState.sipCode} ${detailedState.sipReason ?: ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

// MEJORADO: Controles con estados detallados
@Composable
fun DetailedCallControlsSection(
    callState: CallState,
    detailedState: CallStateInfo,
    currentCall: CallInfo?,
    incomingCall: IncomingCallInfo?,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onEnd: () -> Unit,
    onHold: () -> Unit,
    onResume: () -> Unit,
    onMute: () -> Unit,
    onDtmf: (Char) -> Unit
) {
    when (detailedState.state) {
        DetailedCallState.INCOMING_RECEIVED -> {
            // Botones para llamada entrante
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Declinar
                FloatingActionButton(
                    onClick = onDecline,
                    containerColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        Icons.Default.CallEnd,
                        contentDescription = "Decline",
                        tint = MaterialTheme.colorScheme.onError
                    )
                }

                // Aceptar
                FloatingActionButton(
                    onClick = onAccept,
                    containerColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        Icons.Default.Call,
                        contentDescription = "Accept",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        DetailedCallState.STREAMS_RUNNING, DetailedCallState.CONNECTED -> {
            // Controles durante llamada activa
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Primera fila de controles
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Mute
                    FloatingActionButton(
                        onClick = onMute,
                        containerColor = if (currentCall?.isMuted == true)
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            if (currentCall?.isMuted == true) Icons.Default.MicOff else Icons.Default.Mic,
                            contentDescription = "Mute",
                            tint = if (currentCall?.isMuted == true)
                                MaterialTheme.colorScheme.onError
                            else
                                MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }

                    // Hold/Resume
                    FloatingActionButton(
                        onClick = if (detailedState.state == DetailedCallState.PAUSED) onResume else onHold,
                        containerColor = if (detailedState.state == DetailedCallState.PAUSED)
                            MaterialTheme.colorScheme.tertiary
                        else
                            MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            if (detailedState.state == DetailedCallState.PAUSED) 
                                Icons.Default.PlayArrow 
                            else 
                                Icons.Default.Pause,
                            contentDescription = if (detailedState.state == DetailedCallState.PAUSED) 
                                "Resume" 
                            else 
                                "Hold",
                            tint = if (detailedState.state == DetailedCallState.PAUSED)
                                MaterialTheme.colorScheme.onTertiary
                            else
                                MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }

                    // Speaker
                    FloatingActionButton(
                        onClick = { /* TODO: Implementar cambio de altavoz */ },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(
                            Icons.Default.VolumeUp,
                            contentDescription = "Speaker",
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Botón terminar llamada
                FloatingActionButton(
                    onClick = onEnd,
                    containerColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        Icons.Default.CallEnd,
                        contentDescription = "End Call",
                        tint = MaterialTheme.colorScheme.onError
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Teclado DTMF solo si está en streams running
                if (detailedState.state == DetailedCallState.STREAMS_RUNNING) {
                    DtmfKeypad(onDtmf = onDtmf)
                }
            }
        }

        DetailedCallState.PAUSED -> {
            // Controles para llamada en hold
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Botón resume prominente
                FloatingActionButton(
                    onClick = onResume,
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = "Resume Call",
                        tint = MaterialTheme.colorScheme.onTertiary,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Botón terminar
                FloatingActionButton(
                    onClick = onEnd,
                    containerColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        Icons.Default.CallEnd,
                        contentDescription = "End Call",
                        tint = MaterialTheme.colorScheme.onError
                    )
                }
            }
        }

        DetailedCallState.OUTGOING_INIT,
        DetailedCallState.OUTGOING_PROGRESS,
        DetailedCallState.OUTGOING_RINGING -> {
            // Estados de llamada saliente - solo mostrar botón de cancelar
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                FloatingActionButton(
                    onClick = onEnd,
                    containerColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        Icons.Default.CallEnd,
                        contentDescription = "Cancel Call",
                        tint = MaterialTheme.colorScheme.onError
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Mostrar estado específico
                Text(
                    text = when (detailedState.state) {
                        DetailedCallState.OUTGOING_INIT -> "Iniciando llamada..."
                        DetailedCallState.OUTGOING_PROGRESS -> "Conectando..."
                        DetailedCallState.OUTGOING_RINGING -> "Sonando..."
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        DetailedCallState.ERROR -> {
            // Estado de error - mostrar información y botón de cerrar
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                FloatingActionButton(
                    onClick = onEnd,
                    containerColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onError
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Toca para cerrar",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        else -> {
            // Estados de transición - mostrar indicador de carga
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = detailedState.state.getDisplayText(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// Resto de componentes existentes...
@Composable
fun DtmfKeypad(onDtmf: (Char) -> Unit) {
    // ... implementación existente
}

private fun formatDuration(durationMs: Long): String {
    // ... implementación existente
}