package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class S5ApiPhoneCategorySpecialCasesTest {
    @Test
    fun mapsOnlyExactUppercaseSILToZero() {
        assertEquals(0, S5ApiPhoneCategorySpecialCases.fixedIndexOrNull("SIL"))
        assertEquals(1, S5ApiPhoneCategorySpecialCases.fixedIndexOrNull("Sil"))
    }

    @Test
    fun mapsExactTitleAndUppercaseBreakToOne() {
        assertEquals(1, S5ApiPhoneCategorySpecialCases.fixedIndexOrNull("Br"))
        assertEquals(1, S5ApiPhoneCategorySpecialCases.fixedIndexOrNull("BR"))
    }

    @Test
    fun leavesLowercaseAndOrdinaryPhonesForContextMapLookup() {
        assertNull(S5ApiPhoneCategorySpecialCases.fixedIndexOrNull("sil"))
        assertNull(S5ApiPhoneCategorySpecialCases.fixedIndexOrNull("br"))
        assertNull(S5ApiPhoneCategorySpecialCases.fixedIndexOrNull("a"))
        assertNull(S5ApiPhoneCategorySpecialCases.fixedIndexOrNull("sh"))
    }
    @Test
    fun appliesCanonicalPhoneAliasBeforeFixedExceptionLookup() {
        val silence = VsmPhoneEvent(0.0, 0.02, "SIL", 0)
        val breakTitleCase = VsmPhoneEvent(0.02, 0.04, "Br", 0)
        val alias = VsmPhoneEvent(0.04, 0.06, "x", 0)

        assertEquals(0, S5ApiPhoneCategorySpecialCases.fixedIndexOrNull(silence))
        assertEquals(1, S5ApiPhoneCategorySpecialCases.fixedIndexOrNull(breakTitleCase))
        // "x" canonicalizes to "h", which still needs the context map.
        assertNull(S5ApiPhoneCategorySpecialCases.fixedIndexOrNull(alias))
    }

}
