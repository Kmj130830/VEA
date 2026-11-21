package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class S5ApiPhoneCategorySpecialCasesTest {
    @Test
    fun mapsAllFourNativeOverridesToCategoryOne() {
        listOf("SIL", "Sil", "Br", "BR").forEach { label ->
            assertEquals(label, 1, S5ApiPhoneCategorySpecialCases.fixedIndexOrNull(label))
        }
        assertNull(S5ApiPhoneCategorySpecialCases.fixedIndexOrNull("sil"))
        assertEquals(1, S5ApiPhoneCategorySpecialCases.fixedIndexOrNull("br"))
    }

    @Test
    fun appliesCanonicalPhoneAliasBeforeFixedExceptionLookup() {
        val silence = VsmPhoneEvent(0.0, 0.02, "SIL", 0)
        val breakCase = VsmPhoneEvent(0.02, 0.04, "Br", 0)
        val alias = VsmPhoneEvent(0.04, 0.06, "x", 0)
        assertEquals(1, S5ApiPhoneCategorySpecialCases.fixedIndexOrNull(silence))
        assertEquals(1, S5ApiPhoneCategorySpecialCases.fixedIndexOrNull(breakCase))
        assertNull(S5ApiPhoneCategorySpecialCases.fixedIndexOrNull(alias))
    }
}
