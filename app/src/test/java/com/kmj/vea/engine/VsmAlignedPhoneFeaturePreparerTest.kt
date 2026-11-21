package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VsmAlignedPhoneFeaturePreparerTest {
    @Test
    fun preparesDurationAndExactCategoryFields() {
        val input = listOf(
            VsmPhoneEvent(0.00, 0.08, "a", 10),
            VsmPhoneEvent(0.08, 0.12, "-", 11),
            VsmPhoneEvent(0.12, 0.20, "SIL", 12),
            VsmPhoneEvent(0.20, 0.30, "Br", 13),
            VsmPhoneEvent(0.30, 0.40, "ia+1", 14)
        )
        val prepared = VsmAlignedPhoneFeaturePreparer.prepare(
            input, minimumPhoneSeconds = 0.001f, maximumPhoneSeconds = 0.1f
        )
        assertEquals(listOf("a", "SIL", "Br", "ah"), prepared.map { it.event.phoneLabel })
        assertEquals(listOf(2, 1, 1, 2), prepared.map { it.categoryIndex })
        assertEquals(1, prepared[1].fixedCategoryIndex)
        assertEquals(1, prepared[2].fixedCategoryIndex)
        assertEquals(listOf(10, 12, 13, 14), prepared.map { it.event.opaqueSourceU32 })
        assertTrue(prepared.all { it.normalizedDuration in -1.0f..1.0f })
    }

    @Test
    fun resolvesOrdinaryPhonesAndUnknownLabels() {
        val result = VsmAlignedPhoneFeaturePreparer.prepare(
            listOf(
                VsmPhoneEvent(0.0, 0.04, "sh", 0),
                VsmPhoneEvent(0.04, 0.08, "unknown", 1)
            ),
            minimumPhoneSeconds = 0.001f, maximumPhoneSeconds = 0.1f
        )
        assertEquals(listOf(10, 13), result.map { it.categoryIndex })
    }

    @Test
    fun noPhoneEventsProducesNoFeatureRows() {
        assertTrue(
            VsmAlignedPhoneFeaturePreparer.prepare(
                emptyList(), minimumPhoneSeconds = 0.001f, maximumPhoneSeconds = 0.1f
            ).isEmpty()
        )
    }
}
