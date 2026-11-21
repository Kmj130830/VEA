package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VsmAlignedPhoneFeaturePreparerTest {
    @Test
    fun preparesOnlyVerifiedDurationAndFixedCategoryFields() {
        val input = listOf(
            VsmPhoneEvent(0.00, 0.08, "a", 10),
            VsmPhoneEvent(0.08, 0.12, "-", 11),
            VsmPhoneEvent(0.12, 0.20, "SIL", 12),
            VsmPhoneEvent(0.20, 0.30, "Br", 13),
            VsmPhoneEvent(0.30, 0.40, "ia+1", 14)
        )

        val prepared = VsmAlignedPhoneFeaturePreparer.prepare(
            input,
            minimumPhoneSeconds = 0.001f,
            maximumPhoneSeconds = 0.1f
        )

        assertEquals(listOf("a", "SIL", "Br", "ah"), prepared.map { it.event.phoneLabel })
        assertNull(prepared[0].fixedCategoryIndex)
        assertEquals(0, prepared[1].fixedCategoryIndex)
        assertEquals(1, prepared[2].fixedCategoryIndex)
        assertNull(prepared[3].fixedCategoryIndex)
        assertTrue(prepared.all { it.normalizedDuration in -1.0f..1.0f })
        assertEquals(listOf(10, 12, 13, 14), prepared.map { it.event.opaqueSourceU32 })
    }

    @Test
    fun leavesOrdinaryPhoneCategoryUnresolvedInsteadOfGuessing() {
        val result = VsmAlignedPhoneFeaturePreparer.prepare(
            listOf(VsmPhoneEvent(0.0, 0.04, "sh", 0)),
            minimumPhoneSeconds = 0.001f,
            maximumPhoneSeconds = 0.1f
        )

        assertEquals(1, result.size)
        assertEquals("sh", result.single().event.phoneLabel)
        assertNull(result.single().fixedCategoryIndex)
    }

    @Test
    fun noPhoneEventsProducesNoFeatureRows() {
        assertTrue(
            VsmAlignedPhoneFeaturePreparer.prepare(
                emptyList(),
                minimumPhoneSeconds = 0.001f,
                maximumPhoneSeconds = 0.1f
            ).isEmpty()
        )
    }
}
