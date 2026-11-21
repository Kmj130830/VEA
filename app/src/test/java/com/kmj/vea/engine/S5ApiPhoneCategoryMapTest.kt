package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class S5ApiPhoneCategoryMapTest {
    @Test
    fun mapsARepresentativeFromEveryStaticCategory() {
        val examples = listOf(
            "sil" to 0, "br" to 1, "a" to 2, "ng" to 3,
            "g" to 4, "z" to 5, "j" to 6, "y" to 7,
            "r" to 8, "k" to 9, "sh" to 10, "ch" to 11,
            "cl" to 12, "junk" to 13
        )
        examples.forEach { (label, expected) ->
            assertEquals("label=$label", expected, S5ApiPhoneCategoryMap.resolve(label))
        }
        assertEquals(14, S5ApiPhoneCategoryMap.CATEGORY_COUNT)
    }

    @Test
    fun preservesCaseSensitiveOverrides() {
        assertEquals(0, S5ApiPhoneCategoryMap.resolve("sil"))
        listOf("SIL", "Sil", "Br", "BR", "br").forEach { label ->
            assertEquals(label, 1, S5ApiPhoneCategoryMap.resolve(label))
        }
    }

    @Test
    fun mapsPunctuationBearingLabelsFromStaticTables() {
        assertEquals(2, S5ApiPhoneCategoryMap.resolve("i_n"))
        assertEquals(2, S5ApiPhoneCategoryMap.resolve("@N"))
        assertEquals(8, S5ApiPhoneCategoryMap.resolve("4"))
        assertEquals(9, S5ApiPhoneCategoryMap.resolve("k_h"))
        assertEquals(10, S5ApiPhoneCategoryMap.resolve("s\\"))
        assertEquals(11, S5ApiPhoneCategoryMap.resolve("ts\\"))
        assertEquals(11, S5ApiPhoneCategoryMap.resolve("ts`_h"))
    }

    @Test
    fun resolvesAliasesBeforeCategoryLookup() {
        assertEquals(10, S5ApiPhoneCategoryMap.resolve("x"))
        assertEquals(10, S5ApiPhoneCategoryMap.resolve("s`"))
        assertEquals(11, S5ApiPhoneCategoryMap.resolve("ts\\_h"))
        assertEquals(7, S5ApiPhoneCategoryMap.resolve("ia+0"))
        assertEquals(2, S5ApiPhoneCategoryMap.resolve("ia+1"))
        assertEquals(2, S5ApiPhoneCategoryMap.resolve("y|CH"))
    }

    @Test
    fun appliesNativeAsciiFilteringToUnknownSpellings() {
        assertEquals(2, S5ApiPhoneCategoryMap.resolve("_a_"))
        assertEquals(3, S5ApiPhoneCategoryMap.resolve("n!"))
        assertEquals(8, S5ApiPhoneCategoryMap.resolve("4+"))
        assertEquals(13, S5ApiPhoneCategoryMap.resolve("@#$"))
        assertEquals(13, S5ApiPhoneCategoryMap.resolve("not-a-phone"))
    }

    @Test
    fun returnsDefensiveCategoryCopies() {
        assertEquals(setOf("sil", "Sil", "SIL"), S5ApiPhoneCategoryMap.labelsInCategory(0))
        assertEquals(setOf("junk"), S5ApiPhoneCategoryMap.labelsInCategory(13))
    }
}
