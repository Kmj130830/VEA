package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.exp
import kotlin.math.tanh

class S5ApiFrameMathTest {
    @Test
    fun fastExpMatchesRecoveredNativeFloatBitPatterns() {
        assertEquals(1_065_353_197, S5ApiFrameMath.nativeFastExp(0f).toRawBits())
        assertEquals(1_076_754_539, S5ApiFrameMath.nativeFastExp(1f).toRawBits())
        assertEquals(1_052_531_407, S5ApiFrameMath.nativeFastExp(-1f).toRawBits())
        assertEquals(1_040_880_520, S5ApiFrameMath.nativeFastExp(-2f).toRawBits())
        assertEquals(1_089_237_215, S5ApiFrameMath.nativeFastExp(2f).toRawBits())
        assertEquals(943_614_936, S5ApiFrameMath.nativeFastExp(-10f).toRawBits())
    }

    @Test
    fun nativeTanhUsesTheRecoveredFastExpAndBitReinterpretation() {
        val expected = 2f / (Float.fromBits(1_040_880_520) + 1f) - 1f
        assertEquals(expected.toRawBits(), S5ApiFrameMath.nativeTanh(1f).toRawBits())
        assertEquals(
            2f / (S5ApiFrameMath.nativeFastExp(-2f) + 1f) - 1f,
            S5ApiFrameMath.nativeTanh(1f),
            0f
        )
    }

    @Test
    fun standardTanhRemainsSeparateFromNativeFastTanh() {
        assertEquals(tanh(1.25).toFloat(), S5ApiFrameMath.standardTanh(1.25f), 1.0e-7f)
        assertEquals(
            2f / (exp(-2f * 1.25f) + 1f) - 1f,
            S5ApiFrameMath.standardTanh(1.25f),
            1.0e-6f
        )
    }

    @Test
    fun rejectsNonFiniteInputsAndApproximationOverflow() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            S5ApiFrameMath.nativeFastExp(Float.NaN)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            S5ApiFrameMath.nativeTanh(Float.POSITIVE_INFINITY)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            S5ApiFrameMath.nativeFastExp(Float.MAX_VALUE)
        }
    }
}
