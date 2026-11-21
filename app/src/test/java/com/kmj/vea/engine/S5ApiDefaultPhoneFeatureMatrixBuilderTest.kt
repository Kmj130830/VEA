package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class S5ApiDefaultPhoneFeatureMatrixBuilderTest {
    private fun feature(
        start: Double,
        label: String,
        category: Int,
        marked: Boolean = false,
        duration: Float = 0.25f
    ) = VsmAlignedPhoneFeature(
        event = VsmPhoneEvent(start, start + 0.05, label, category),
        normalizedDuration = duration,
        categoryIndex = category,
        hasLeadingBang = marked
    )

    @Test
    fun writesCentreLeftAndRightCategoriesInNativeOrder() {
        val features = listOf(
            feature(0.00, "sil", 0),
            feature(0.10, "ng", 3),
            feature(0.20, "!sh", 10, marked = true, duration = -0.5f),
            feature(0.30, "cl", 12)
        )
        val layout = S5ApiDefaultPhoneFeatureLayout(14, 1, 2, true)
        val matrix = S5ApiDefaultPhoneFeatureMatrixBuilder.build(features, layout)
        val row = matrix.row(2)

        assertEquals(4, matrix.rowCount)
        assertEquals(59, matrix.rowWidth)
        assertEquals(59, row.size)
        assertEquals(-0.5f, row[0], 0.0f)
        assertEquals(1.0f, row[1], 0.0f)
        assertEquals(0.0f, row[2], 0.0f)
        assertEquals(1.0f, row[3 + 10], 0.0f)
        assertEquals(1.0f, row[17 + 0], 0.0f)
        assertEquals(1.0f, row[31 + 3], 0.0f)
        assertEquals(1.0f, row[45 + 12], 0.0f)
        assertEquals(6, row.count { it != 0.0f })
    }

    @Test
    fun clampsContextAtTheSequenceBoundaries() {
        val features = listOf(feature(0.00, "sil", 0), feature(0.10, "ng", 3))
        val layout = S5ApiDefaultPhoneFeatureLayout(14, 2, 2, false)
        val matrix = S5ApiDefaultPhoneFeatureMatrixBuilder.build(features, layout)
        val first = matrix.row(0)
        val last = matrix.row(1)

        assertEquals(1.0f, first[1 + 0], 0.0f)
        assertEquals(1.0f, first[15 + 0], 0.0f)
        assertEquals(1.0f, last[1 + 3], 0.0f)
        assertEquals(1.0f, last[43 + 3], 0.0f)
        assertEquals(1.0f, last[57 + 3], 0.0f)
    }

    @Test
    fun omitsFlagSlotsWhenTheDescriptorDisablesThem() {
        val marked = listOf(feature(0.0, "!a", 2, marked = true))
        val layout = S5ApiDefaultPhoneFeatureLayout(14, 0, 0, false)
        val row = S5ApiDefaultPhoneFeatureMatrixBuilder.build(marked, layout).row(0)
        assertEquals(15, row.size)
        assertEquals(0.25f, row[0], 0.0f)
        assertEquals(1.0f, row[1 + 2], 0.0f)
    }

    @Test
    fun returnsAnEmptyMatrixForNoPhoneEvents() {
        val layout = S5ApiDefaultPhoneFeatureLayout(14, 1, 1, false)
        val matrix = S5ApiDefaultPhoneFeatureMatrixBuilder.build(emptyList(), layout)
        assertEquals(0, matrix.rowCount)
        assertEquals(0, matrix.values.size)
        assertEquals(layout.rowWidth, matrix.rowWidth)
    }

    @Test
    fun rejectsAWidthSmallerThanTheRecoveredCategoryMap() {
        val layout = S5ApiDefaultPhoneFeatureLayout(13, 0, 0, false)
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiDefaultPhoneFeatureMatrixBuilder.build(listOf(feature(0.0, "a", 2)), layout)
        }
    }

    @Test
    fun rejectsNonDefaultLayoutMode() {
        val layout = S5ApiDefaultPhoneFeatureLayout(14, 0, 0, false, layoutMode = 1)
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiDefaultPhoneFeatureMatrixBuilder.build(listOf(feature(0.0, "a", 2)), layout)
        }
    }

    @Test
    fun rejectsOutOfOrderEvents() {
        val features = listOf(feature(0.1, "a", 2), feature(0.0, "ng", 3))
        val layout = S5ApiDefaultPhoneFeatureLayout(14, 0, 0, false)
        assertThrows(IllegalArgumentException::class.java) {
            S5ApiDefaultPhoneFeatureMatrixBuilder.build(features, layout)
        }
    }
}
