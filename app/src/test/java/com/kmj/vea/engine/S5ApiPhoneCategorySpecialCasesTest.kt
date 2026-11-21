package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class S5ApiPhoneCategorySpecialCasesTest {
    @Test
    fun mapsExactSilenceSpellingsToZero() {
        assertEquals(0, S5ApiPhoneCategorySpecialCases.fixedIndexOrNull("Sil"))
        assertEquals(0, S5ApiPhoneCategorySpecialCases.fixedIndexOrNull("SIL"))
    }

    @Test
    fun mapsExactBreakSpellingsToOne() {
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
}
