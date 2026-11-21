package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VsmPhoneEventPreprocessorTest {
    private fun event(start: Double, end: Double, label: String, flags: Int = 0) =
        VsmPhoneEvent(start, end, label, flags)

    @Test
    fun rewritesLeadingBreakToSilence() {
        val input = listOf(event(0.0, 0.04, "br", 7))

        val output = VsmPhoneEventPreprocessor.preprocess(input)

        assertEquals(1, output.size)
        assertEquals("sil", output.single().phoneLabel)
        assertEquals(0.0, output.single().startSeconds, 1e-9)
        assertEquals(0.04, output.single().endSeconds, 1e-9)
        assertEquals(7, output.single().opaqueSourceU32)
    }

    @Test
    fun mergesBreakAfterSilenceIntoPreviousSilence() {
        val input = listOf(
            event(0.0, 0.03, "sil", 10),
            event(0.03, 0.08, "br", 11),
            event(0.08, 0.12, "a", 12)
        )

        val output = VsmPhoneEventPreprocessor.preprocess(input)

        assertEquals(listOf("sil", "a"), output.map { it.phoneLabel })
        assertEquals(0.08, output[0].endSeconds, 1e-9)
        assertEquals(10, output[0].opaqueSourceU32)
    }

    @Test
    fun convertsNonLeadingBreakToSilence() {
        val input = listOf(
            event(0.0, 0.03, "a"),
            event(0.03, 0.06, "br")
        )

        val output = VsmPhoneEventPreprocessor.preprocess(input)

        assertEquals(listOf("a", "sil"), output.map { it.phoneLabel })
    }

    @Test
    fun removesHyphenRowsAndExtendsEachKeptRowToNextKeptEnd() {
        val input = listOf(
            event(0.00, 0.05, "a", 1),
            event(0.05, 0.10, "-", 2),
            event(0.10, 0.15, "i", 3),
            event(0.15, 0.20, "u", 4)
        )

        val output = VsmPhoneEventPreprocessor.preprocess(input)

        assertEquals(listOf("a", "i", "u"), output.map { it.phoneLabel })
        assertEquals(0.10, output[0].endSeconds, 1e-9)
        assertEquals(0.15, output[1].endSeconds, 1e-9)
        assertEquals(0.20, output[2].endSeconds, 1e-9)
        assertEquals(listOf(1, 3, 4), output.map { it.opaqueSourceU32 })
    }

    @Test
    fun usesFinalPreFilterEndpointWhenLastRowIsHyphen() {
        val input = listOf(
            event(0.00, 0.03, "a"),
            event(0.03, 0.08, "-")
        )

        val output = VsmPhoneEventPreprocessor.preprocess(input)

        assertEquals(1, output.size)
        assertEquals(0.08, output.single().endSeconds, 1e-9)
    }

    @Test
    fun dropsEventsShorterThanNativeOneMillisecondThreshold() {
        val input = listOf(
            event(0.00, 0.0005, "a"),
            event(0.0005, 0.0020, "i")
        )

        val output = VsmPhoneEventPreprocessor.preprocess(input)

        assertEquals(listOf("i"), output.map { it.phoneLabel })
    }

    @Test
    fun exactCaseSensitiveSpecialLabelsArePreserved() {
        val input = listOf(event(0.0, 0.02, "BR"), event(0.02, 0.04, "- "))

        val output = VsmPhoneEventPreprocessor.preprocess(input)

        assertEquals(listOf("BR", "- "), output.map { it.phoneLabel })
    }

    @Test
    fun dropsReversedIntervalsInsteadOfRejectingTheWholeBatch() {
        val input = listOf(event(0.10, 0.05, "invalid"))

        val output = VsmPhoneEventPreprocessor.preprocess(input)

        assertTrue(output.isEmpty())
    }

    @Test
    fun emptyInputProducesEmptyOutput() {
        assertTrue(VsmPhoneEventPreprocessor.preprocess(emptyList()).isEmpty())
    }
}
