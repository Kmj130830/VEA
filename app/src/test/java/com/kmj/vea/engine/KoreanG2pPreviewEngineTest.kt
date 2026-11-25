package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KoreanG2pPreviewEngineTest {
    @Test
    fun runsNativeInputNormalizationLexicalRewriteAndSyllableMappingInOrder() {
        val result = KoreanG2pPreviewEngine.preview("디귿이 가라")

        assertEquals("디귿이 가라", result.sourceText)
        assertEquals("디귿이 가라", result.normalizedText)
        assertEquals(2, result.tokens.size)
        assertEquals("디그시", result.tokens[0].lexicalForm)
        assertEquals(listOf("가라"), result.tokens[1].syllablePhoneGroups.let {
            listOf(it.joinToString(" "))
        }.filter { it == "가라" }) // Guard that the token remains independently scoped.
        assertEquals(listOf("g", "a", "r", "a"), result.tokens[1].phoneLabels)
    }

    @Test
    fun appliesTheVerifiedPunctuationNormalizationBeforeTokenSplitting() {
        val result = KoreanG2pPreviewEngine.preview("가ー나 다−라")

        assertEquals("가-나 다-라", result.normalizedText)
        assertEquals(listOf("가-나", "다-라"), result.tokens.map { it.originalToken })
        assertTrue(result.hasUnresolvedHyphens)
    }

    @Test
    fun exposesTheUnportedG2pStagesAndNeverReportsCompleteConversion() {
        val result = KoreanG2pPreviewEngine.preview("가")

        assertFalse(result.isComplete)
        assertEquals(
            listOf(
                "FUN_180007390: Korean source-character flag generation",
                "FUN_180008730: intermediate lyric/string transformation"
            ),
            result.missingStages
        )
        assertEquals("g a", result.phonemeText)
    }

    @Test
    fun reportsNonHangulCharactersThatTheRecoveredFinalMapperSkips() {
        val result = KoreanG2pPreviewEngine.preview("가!나")

        assertEquals(listOf('!'), result.tokens.single().ignoredCharacters)
        assertEquals(listOf("g", "a", "n", "a"), result.phoneLabels)
        assertTrue(result.hasIgnoredCharacters)
    }

    @Test
    fun emptyAndDelimiterOnlyInputProduceAnEmptyIncompletePreview() {
        val empty = KoreanG2pPreviewEngine.preview("")
        val delimiterOnly = KoreanG2pPreviewEngine.preview(" \t\u3000\n")

        assertTrue(empty.tokens.isEmpty())
        assertTrue(empty.phoneLabels.isEmpty())
        assertFalse(empty.isComplete)
        assertTrue(delimiterOnly.tokens.isEmpty())
    }
}
