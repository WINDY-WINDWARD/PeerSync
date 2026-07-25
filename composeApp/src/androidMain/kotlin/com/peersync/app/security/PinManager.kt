package com.peersync.app.security

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

sealed class PinValidationResult {
    object Success : PinValidationResult()
    data class InvalidPin(val attemptsRemaining: Int) : PinValidationResult()
    data class RateLimited(val cooldownRemainingMs: Long) : PinValidationResult()
}

/**
 * Handles PIN generation, HMAC-SHA256 session token hashing for NSD TXT records,
 * and rate-limiting brute force protection per client (3 failed attempts -> 30s cooldown).
 */
object PinManager {

    private const val MAX_ATTEMPTS = 3
    private const val COOLDOWN_MS = 30_000L

    private val secureRandom = SecureRandom()
    private val failedAttemptsMap = mutableMapOf<String, Int>()
    private val cooldownTimestampMap = mutableMapOf<String, Long>()

    /**
     * Generates an 8-digit numeric PIN (e.g. "48201923").
     * WPA2 requires at least 8 characters, so we use 8 digits for the passphrase.
     */
    fun generatePin(): String {
        val number = secureRandom.nextInt(100_000_000)
        return String.format("%08d", number)
    }

    /**
     * Generates a random salt nonce hex string.
     */
    fun generateNonce(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Computes HMAC-SHA256(PIN, SaltNonce) returned as hex string.
     */
    fun computeSessionToken(pin: String, nonce: String): String {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            val secretKey = SecretKeySpec(nonce.toByteArray(Charsets.UTF_8), "HmacSHA256")
            mac.init(secretKey)
            val hmacBytes = mac.doFinal(pin.toByteArray(Charsets.UTF_8))
            hmacBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Validates an input PIN against expected PIN with rate-limiting enforced per clientId.
     */
    @Synchronized
    fun validatePin(inputPin: String, targetPin: String, clientId: String): PinValidationResult {
        val now = System.currentTimeMillis()
        val cooldownEnd = cooldownTimestampMap[clientId] ?: 0L

        if (now < cooldownEnd) {
            val remaining = cooldownEnd - now
            return PinValidationResult.RateLimited(remaining)
        }

        if (inputPin == targetPin) {
            failedAttemptsMap.remove(clientId)
            cooldownTimestampMap.remove(clientId)
            return PinValidationResult.Success
        }

        val attempts = (failedAttemptsMap[clientId] ?: 0) + 1
        return if (attempts >= MAX_ATTEMPTS) {
            failedAttemptsMap.remove(clientId)
            cooldownTimestampMap[clientId] = now + COOLDOWN_MS
            PinValidationResult.RateLimited(COOLDOWN_MS)
        } else {
            failedAttemptsMap[clientId] = attempts
            PinValidationResult.InvalidPin(MAX_ATTEMPTS - attempts)
        }
    }

    /**
     * Clears tracking maps.
     */
    @Synchronized
    fun reset() {
        failedAttemptsMap.clear()
        cooldownTimestampMap.clear()
    }
}
