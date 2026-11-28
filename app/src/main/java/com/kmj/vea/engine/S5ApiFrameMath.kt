package com.kmj.vea.engine

import kotlin.math.abs

/**
 * Scalar math helpers recovered from S5API FUN_18003be50 and its call sites.
 *
 * FUN_18003be50 is a fast exp approximation. It returns the IEEE-754 float
 * bit pattern through a 32-bit integer value; callers reinterpret those bits
 * as float before evaluating:
 *   tanh(x) = 2 / (exp(-2*x) + 1) - 1
 *
 * FUN_180076080/FUN_180077520 call this helper rather than the platform expf
 * for their vector activation path. Keep it separate from the conventional
 * tanh used by FUN_18003e920.
 */
object S5ApiFrameMath {
    fun nativeFastExp(value: Float): Float {
        require(value.isFinite()) { "Fast exp input must be finite." }

        // Native assignment stores the multiplication result back to float.
        val scaled = (value.toDouble() * LOG2_E).toFloat()
        var fractionOffset = if (scaled < 0f) 1f else 0f
        val exponent = if (scaled >= MIN_EXPONENT) scaled else MIN_EXPONENT
        fractionOffset += exponent - exponent.toInt().toFloat()

        // FUN_18003be50 uses double literals for this polynomial, then casts
        // the final scaled value to an integer whose low 32 bits are re-read
        // as float by its callers.
        val fraction = fractionOffset.toDouble()
        val exponentValue = exponent.toDouble()
        val approximation =
            ((27.728024 / (4.8425255 - fraction) + exponentValue + 121.274055) -
                fraction * 1.4901291) * FLOAT_MANTISSA_SCALE

        require(approximation.isFinite() &&
            approximation >= Long.MIN_VALUE.toDouble() &&
            approximation <= Long.MAX_VALUE.toDouble()) {
            "Fast exp approximation is outside the native integer conversion range."
        }
        val bits = approximation.toLong().toInt()
        return Float.fromBits(bits)
    }

    fun nativeTanh(value: Float): Float {
        require(value.isFinite()) { "Native tanh input must be finite." }
        val exponential = nativeFastExp(-2f * value)
        return 2f / (exponential + 1f) - 1f
    }

    /** Platform/libm tanh form used by the separate FUN_18003e920 wrapper. */
    fun standardTanh(value: Float): Float {
        require(value.isFinite()) { "Tanh input must be finite." }
        return kotlin.math.tanh(value.toDouble()).toFloat()
    }

    private const val LOG2_E = 1.442695
    private const val MIN_EXPONENT = -126f
    private const val FLOAT_MANTISSA_SCALE = 8_388_608.0
}
