package com.shirou.shibagram.data.repository

import com.shirou.shibagram.data.remote.TelegramClientService
import com.shirou.shibagram.domain.model.AuthState
import kotlinx.coroutines.flow.StateFlow

/**
 * Repository for handling Telegram authentication.
 * Ported from Android ShibaGram app.
 */
class TelegramAuthRepository(
    private val telegramClient: TelegramClientService = TelegramClientService.getInstance()
) {
    val authState: StateFlow<AuthState> = telegramClient.authState
    
    suspend fun initialize() {
        telegramClient.initialize()
    }
    
    suspend fun sendPhoneNumber(phoneNumber: String) {
        telegramClient.sendPhoneNumber(phoneNumber)
    }
    
    suspend fun sendVerificationCode(code: String) {
        telegramClient.sendVerificationCode(code)
    }
    
    suspend fun send2FAPassword(password: String) {
        telegramClient.send2FAPassword(password)
    }
    
    suspend fun initiateQRLogin(): String? {
        return telegramClient.requestQRCode()
    }
    
    suspend fun logout() {
        telegramClient.logout()
    }
    
    fun resetToPhoneInput() {
        // Reset auth state to phone input
    }
}
