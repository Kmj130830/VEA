package com.kmj.vea.engine

import kotlin.math.ln

/**
 * Ports the default-layout duration scalar from S5API FUN_18006c720.
 *
 * Native behavior:
 *   effective = max(minimumSeconds, float32(endSeconds - startSeconds))
 *   x = 2 * (logf(effective) - logf(minimumSeconds)) /
 *       (logf(maximumSeconds) - logf(minimumSeconds)) - 1
 *   output = clamp(x, -1, +1)
 *
 * This encoder is independent of phone-category IDs and context-window
 * layout. It does not claim to construct the complete S5API feature row.
 */
object S5ApiPhoneDurationFeature {
    fun encode(
        startSeconds: Double,
        endSeconds: Double,
        minimumSeconds: Float,
        maximumSeconds: Float
    ): Float {
        require(startSeconds.isFinite() && endSeconds.isFinite()) {
            "Phone event endpoints must be finite."
        }
        require(endSeconds > startSeconds) {
            "Phone event duration must be positive."
        }
        require(minimumSeconds.isFinite() && minimumSeconds > 0.0f) {
            "Minimum duration must be a finite positive float."
        }
        require(maximumSeconds.isFinite() && maximumSeconds > minimumSeconds) {
            "Maximum duration must be finite and greater than the minimum."
        }

        // The native routine subtracts the double endpoints first and then
        // narrows the duration to float32 before applying logf.
        val duration = (endSeconds - startSeconds).toFloat()
        require(duration.isFinite() && duration > 0.0f) {
            "Phone event duration is outside the supported float32 range."
        }

        val effective = if (minimumSeconds <= duration) duration else minimumSeconds
        val logMinimum = ln(minimumSeconds)
        val logMaximum = ln(maximumSeconds)
        val logDuration = ln(effective)

        // Preserve the native expression order instead of algebraically
        // simplifying the numerator.
        val delta = logDuration - logMinimum
        val normalized = (delta + delta) / (logMaximum - logMinimum) - 1.0f
        return normalized.coerceIn(-1.0f, 1.0f)
    }

    fun encode(event: VsmPhoneEvent, minimumSeconds: Float, maximumSeconds: Float): Float =
        encode(event.startSeconds, event.endSeconds, minimumSeconds, maximumSeconds)
}
