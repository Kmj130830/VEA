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
        assertEquals(listOf("g a", "r a"), result.tokens[1].syllablePhoneGroups)
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
    fun flagsPhoneLabelsNotCoveredByTheRecoveredS5ApiCategoryGroups() {
        val result = KoreanG2pPreviewEngine.preview("각")

        assertTrue(result.phoneLabels.contains("gp"))
        assertTrue(result.tokens.single().categoryFallbackLabels.contains("gp"))
    }

    @Test
    fun reportsNonHangulCharactersThatTheRecoveredFinalMapperSkips() {
        val result = KoreanG2pPreviewEngine.preview("가!나")

        assertEquals(listOf('!'), result.tokens.single().ignoredCharacters)
        assertEquals(listOf("g", "a", "n", "a"), result.phoneLabels)
        assertTrue(result.hasIgnoredCharacters)
    }

    @Test
    fun previewsScheduledNotesWithoutChangingTheirTimingOrStoredPhonemes() {
        val note = VsmScheduledNote(
            trackId = "track",
            clipId = "clip",
            noteId = "note-1",
            absoluteStartTick = 120L,
            durationTicks = 480L,
            absoluteStartSample = 50L,
            frameOffsetInChunk = 50,
            pitch = 60,
            durationSamples = 100L,
            sampleRate = 44_100,
            lyric = "가라",
            phoneme = ""
        )
        val schedule = VsmRenderSchedule(
            sampleRate = 44_100,
            chunkSamples = 256,
            totalSampleCount = 512L,
            chunks = listOf(
                VsmInputChunk(0, 0L, 256, 256, listOf(note)),
                VsmInputChunk(1, 256L, 256, 256, emptyList())
            )
        )

        val previews = KoreanG2pPreviewEngine.previewSchedule(schedule, "Korean")

        assertEquals(1, previews.size)
        assertEquals("note-1", previews.single().noteId)
        assertEquals(50L, previews.single().absoluteStartSample)
        assertEquals(100L, previews.single().durationSamples)
        assertEquals(44_100, previews.single().sampleRate)
        assertEquals("가라", previews.single().lyric)
        assertEquals(listOf("g", "a", "r", "a"), previews.single().preview.phoneLabels)
        assertFalse(previews.single().preview.isComplete)
        assertEquals("", schedule.chunks.first().noteStarts.single().phoneme)
    }

    @Test
    fun refusesToUseTheKoreanPreviewForAnotherLanguage() {
        val schedule = VsmRenderSchedule(44_100, 256, 0L, emptyList())
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            KoreanG2pPreviewEngine.previewSchedule(schedule, "Japanese")
        }
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
