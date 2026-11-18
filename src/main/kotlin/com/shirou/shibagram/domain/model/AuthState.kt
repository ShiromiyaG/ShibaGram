package com.shirou.shibagram.domain.model

/**
 * Represents the authentication state of the Telegram client.
 * Ported from Android ShibaGram app.
 */
sealed class AuthState {
    data object NotAuthenticated : AuthState()
    data object WaitingForPhoneNumber : AuthState()
    data class WaitingForCode(val phoneNumber: String) : AuthState()
    data object WaitingFor2FA : AuthState()
    data object QRLoginInProgress : AuthState()
    data class QRCodeReady(val link: String) : AuthState()
    data object Authenticated : AuthState()
    data class Error(val message: String) : AuthState()
}
