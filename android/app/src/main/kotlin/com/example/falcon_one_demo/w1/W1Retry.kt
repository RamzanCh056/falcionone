package com.example.falcon_one_demo.w1

import kotlin.random.Random
import kotlinx.coroutines.delay

internal suspend fun <T> withRetries(
    sessionId: String,
    phase: String,
    logger: W1Logger,
    maxAttempts: Int = 5,
    initialDelayMs: Long = 400L,
    maxDelayMs: Long = 12_000L,
    block: suspend (attempt: Int) -> Result<T>,
): Result<T> {
    var delay = initialDelayMs
    var last: Throwable? = null
    repeat(maxAttempts) { attempt ->
        val r = block(attempt + 1)
        if (r.isSuccess) return r
        last = r.exceptionOrNull()
        logger.w(
            sessionId,
            "retry",
            mapOf(
                "phase" to phase,
                "attempt" to (attempt + 1),
                "maxAttempts" to maxAttempts,
                "error" to (last?.message ?: "unknown"),
            ),
        )
        if (attempt < maxAttempts - 1) {
            val jitter = Random.nextLong(0, (delay / 4).coerceAtLeast(1))
            delay(delay + jitter)
            delay = minOf(delay * 2, maxDelayMs)
        }
    }
    return Result.failure(last ?: IllegalStateException("$phase failed"))
}
