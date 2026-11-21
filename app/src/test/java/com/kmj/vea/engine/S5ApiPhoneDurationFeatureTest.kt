package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class S5ApiPhoneDurationFeatureTest {
    @Test
    fun minimumDurationMapsToMinusOne() {
        assertEquals(
            -1.0f,
            S5ApiPhoneDurationFeature.encode(0.0, 0.001, 0.001f, 0.1f),
            1e-6f
        )
    }

    @Test
    fun geometricMeanDurationMapsToZero() {
        // log(sqrt(lo * hi)) is the midpoint between log(lo) and log(hi).
        assertEquals(
            0.0f,
            S5ApiPhoneDurationFeature.encode(0.0, 0.01, 0.001f, 0.1f),
            1e-6f
        )
    }

    @Test
    fun maximumDurationMapsToPlusOne() {
        assertEquals(
            1.0f,
            S5ApiPhoneDurationFeature.encode(0.0, 0.1, 0.001f, 0.1f),
            1e-6f
        )
    }

    @Test
    fun durationAboveMaximumIsClampedToPlusOne() {
        assertEquals(
            1.0f,
            S5ApiPhoneDurationFeature.encode(0.0, 1.0, 0.001f, 0.1f),
            1e-6f
        )
    }

    @Test
    fun durationBelowMinimumIsFlooredAtMinusOne() {
        assertEquals(
            -1.0f,
            S5ApiPhoneDurationFeature.encode(0.0, 0.00001, 0.001f, 0.1f),
            1e-6f
        )
    }

    @Test
    fun acceptsPreprocessedPhoneEvent() {
        val event = VsmPhoneEvent(2.0, 2.01, "a", 0)
        val encoded = S5ApiPhoneDurationFeature.encode(event, 0.001f, 0.1f)
        assertTrue(encoded > -1.0f && encoded < 1.0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveMinimum() {
        S5ApiPhoneDurationFeature.encode(0.0, 0.1, 0.0f, 0.1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidLogRange() {
        S5ApiPhoneDurationFeature.encode(0.0, 0.1, 0.1f, 0.1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonPositiveDuration() {
        S5ApiPhoneDurationFeature.encode(0.1, 0.1, 0.001f, 0.1f)
    }
}
