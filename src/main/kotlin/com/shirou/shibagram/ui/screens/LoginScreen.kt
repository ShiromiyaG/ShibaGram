package com.shirou.shibagram.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shirou.shibagram.domain.model.AuthState
import qrcode.QRCode
import java.io.ByteArrayOutputStream

/**
 * Login screen with Material Design 3.
 * Ported from Android ShibaGram app.
 */
@Composable
fun LoginScreen(
    authState: AuthState,
    onPhoneNumberSubmit: (String) -> Unit,
    onCodeSubmit: (String) -> Unit,
    onPasswordSubmit: (String) -> Unit,
    onQRLoginClick: () -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var phoneNumber by remember { mutableStateOf("") }
    var verificationCode by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // Subtle gradient accent behind the card
            Box(
                modifier = Modifier
                    .size(500.dp)
                    .offset(y = (-80).dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
                                Color.Transparent
                            ),
                            radius = 400f
                        )
                    )
            )
            
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Logo — rounded container
                Surface(
                    modifier = Modifier.size(72.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.PlayCircle,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(20.dp))
                
                Text(
                    text = "ShibaGram",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                
                Spacer(modifier = Modifier.height(6.dp))
                
                Text(
                    text = "Watch videos from Telegram",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(40.dp))
                
                // Content based on auth state
                Card(
                    modifier = Modifier.widthIn(max = 420.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                    when (authState) {
                        is AuthState.NotAuthenticated,
                        is AuthState.WaitingForPhoneNumber -> {
                            PhoneNumberInput(
                                phoneNumber = phoneNumber,
                                onPhoneNumberChange = { phoneNumber = it },
                                onSubmit = { onPhoneNumberSubmit(phoneNumber) },
                                onQRLoginClick = onQRLoginClick
                            )
                        }
                        
                        is AuthState.WaitingForCode -> {
                            VerificationCodeInput(
                                code = verificationCode,
                                phoneNumber = authState.phoneNumber,
                                onCodeChange = { verificationCode = it },
                                onSubmit = { onCodeSubmit(verificationCode) },
                                onBackClick = onBackClick
                            )
                        }
                        
                        is AuthState.WaitingFor2FA -> {
                            TwoFactorInput(
                                password = password,
                                showPassword = showPassword,
                                onPasswordChange = { password = it },
                                onShowPasswordToggle = { showPassword = !showPassword },
                                onSubmit = { onPasswordSubmit(password) },
                                onBackClick = onBackClick
                            )
                        }
                        
                        is AuthState.QRLoginInProgress -> {
                            QRLoginWaiting(onBackClick = onBackClick)
                        }
                        
                        is AuthState.QRCodeReady -> {
                            QRCodeDisplay(
                                link = authState.link,
                                onBackClick = onBackClick
                            )
                        }
                        
                        is AuthState.Error -> {
                            ErrorDisplay(
                                message = authState.message,
                                onRetryClick = onBackClick
                            )
                        }
                        
                        is AuthState.Authenticated -> {
                            // Should not reach here
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun PhoneNumberInput(
    phoneNumber: String,
    onPhoneNumberChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onQRLoginClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Sign in with Telegram",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Enter your phone number to continue",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        OutlinedTextField(
            value = phoneNumber,
            onValueChange = onPhoneNumberChange,
            label = { Text("Phone number") },
            placeholder = { Text("+1 234 567 8900") },
            leadingIcon = {
                Icon(Icons.Default.Phone, contentDescription = null)
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth(),
            enabled = phoneNumber.isNotBlank()
        ) {
            Text("Continue")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        HorizontalDivider()
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedButton(
            onClick = onQRLoginClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                Icons.Default.QrCode,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Sign in with QR Code")
        }
    }
}

@Composable
private fun VerificationCodeInput(
    code: String,
    phoneNumber: String,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBackClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Enter verification code",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "We sent a code to $phoneNumber",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        OutlinedTextField(
            value = code,
            onValueChange = onCodeChange,
            label = { Text("Verification code") },
            leadingIcon = {
                Icon(Icons.Default.Lock, contentDescription = null)
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth(),
            enabled = code.isNotBlank()
        ) {
            Text("Verify")
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        TextButton(onClick = onBackClick) {
            Icon(
                Icons.Default.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Back")
        }
    }
}

@Composable
private fun TwoFactorInput(
    password: String,
    showPassword: Boolean,
    onPasswordChange: (String) -> Unit,
    onShowPasswordToggle: () -> Unit,
    onSubmit: () -> Unit,
    onBackClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Two-factor authentication",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Enter your 2FA password",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            leadingIcon = {
                Icon(Icons.Default.Lock, contentDescription = null)
            },
            trailingIcon = {
                IconButton(onClick = onShowPasswordToggle) {
                    Icon(
                        imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showPassword) "Hide password" else "Show password"
                    )
                }
            },
            visualTransformation = if (showPassword) {
                androidx.compose.ui.text.input.VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth(),
            enabled = password.isNotBlank()
        ) {
            Text("Submit")
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        TextButton(onClick = onBackClick) {
            Icon(
                Icons.Default.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Back")
        }
    }
}

@Composable
private fun QRLoginWaiting(onBackClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Scan QR Code",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        CircularProgressIndicator()
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Generating QR code...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        TextButton(onClick = onBackClick) {
            Icon(
                Icons.Default.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Back")
        }
    }
}

@Composable
private fun QRCodeDisplay(
    link: String,
    onBackClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Scan QR Code",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = "Open Telegram on your phone and scan this code",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Generate and display QR code
        val qrBitmap = remember(link) {
            try {
                println("Generating QR code for link: $link")
                val qrCode = QRCode.ofSquares()
                    .withSize(10)
                    .build(link)
                val bytes = ByteArrayOutputStream().also {
                    qrCode.render().writeImage(it)
                }.toByteArray()
                println("QR code generated, bytes: ${bytes.size}")
                org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
            } catch (e: Exception) {
                println("Error generating QR code: ${e.message}")
                e.printStackTrace()
                null
            }
        }
        
        if (qrBitmap != null) {
            Image(
                bitmap = qrBitmap,
                contentDescription = "QR Code",
                modifier = Modifier
                    .size(200.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Show the link for manual copying
        SelectionContainer {
            Text(
                text = link,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        TextButton(onClick = onBackClick) {
            Icon(
                Icons.Default.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Back")
        }
    }
}

@Composable
private fun ErrorDisplay(
    message: String,
    onRetryClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.error
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Authentication Error",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(onClick = onRetryClick) {
            Text("Try Again")
        }
    }
}
