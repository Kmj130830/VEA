package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class PreparedMegpoidExplicitPhoneSequenceSessionTest {
    private data class BlockCall(val row: Int, val column: Int)

    private class IdentityResidualNode(
        override val row: Int,
        override val column: Int,
        override val channels: Int,
        private val calls: MutableList<BlockCall>
    ) : S5MegpoidResidualBlockNode {
        override fun runChunk(residualInput: FloatArray, epsilon: Float): FloatArray {
            calls += BlockCall(row, column)
            return residualInput.copyOf()
        }

        override fun resetHistory() = Unit
    }

    private data class Fixture(
        val session: PreparedMegpoidExplicitPhoneSequenceSession,
        val calls: MutableList<BlockCall>
    )

    private fun fixture(): Fixture {
        val inputFeatures = 29
        val channels = 32
        val dense = S5MegpoidDenseNormalizationStage(
            projection = S5MegpoidDenseProjection(
                inputFeatures = inputFeatures,
                outputFeatures = channels,
                weights = FloatArray(inputFeatures * channels),
                bias = null
            ),
            normalization = S5MegpoidGlobalFeatureNormalization(
                features = channels,
                scale = FloatArray(channels) { 1f },
                bias = FloatArray(channels),
                epsilon = 1.0e-6f
            )
        )
        val temporal = List(3) {
            S5MegpoidTemporalStridedConvolution(
                inputFeatures = channels,
                outputFeatures = channels,
                kernelSize = 3,
                weights = FloatArray(channels * channels * 3),
                bias = FloatArray(channels)
            )
        }
        val alternate = List(3) {
            S5MegpoidAlternateTemporalProjection(
                inputFeatures = channels,
                outputFeatures = channels,
                weights = FloatArray(channels * channels * 3),
                bias = FloatArray(channels)
            )
        }
        val calls = mutableListOf<BlockCall>()
        val residual = S5MegpoidResidualBlockExecutor(
            nodes = buildList {
                for (row in 0 until S5MegpoidRuntimeBlockFactoryKeys.BLOCK_ROWS) {
                    for (column in 0 until S5MegpoidRuntimeBlockFactoryKeys.BLOCK_COLUMNS) {
                        add(IdentityResidualNode(row, column, channels, calls))
                    }
                }
            }
        )
        val pipeline = S5MegpoidAlternateSequenceTransitionPipeline.fromModelStages(
            denseStage = dense,
            temporalStages = temporal,
            alternateStages = alternate,
            residualExecutor = residual
        )
        return Fixture(PreparedMegpoidExplicitPhoneSequenceSession(pipeline), calls)
    }

    private fun schedule(
        phoneCount: Int = 49,
        phonemeText: String = List(phoneCount) { "a" }.joinToString(" ")
    ): VsmRenderSchedule {
        val sampleRate = 44_100
        val samplesPerPhone = 441L // 10 ms per explicit phone.
        val totalSamples = phoneCount.toLong() * samplesPerPhone
        val note = VsmScheduledNote(
            trackId = "track-1",
            clipId = "clip-1",
            noteId = "note-1",
            absoluteStartTick = 0L,
            durationTicks = 480L,
            absoluteStartSample = 0L,
            frameOffsetInChunk = 0,
            pitch = 60,
            durationSamples = totalSamples,
            sampleRate = sampleRate,
            lyric = "test",
            phoneme = phonemeText
        )
        val chunkSamples = 256
        val chunkCount = ((totalSamples + chunkSamples - 1L) / chunkSamples).toInt()
        val chunks = List(chunkCount) { index ->
            val start = index.toLong() * chunkSamples
            val valid = minOf(chunkSamples.toLong(), totalSamples - start).toInt()
            VsmInputChunk(
                index = index,
                startSample = start,
                renderSampleCount = chunkSamples,
                validSampleCount = valid,
                noteStarts = if (index == 0) listOf(note) else emptyList()
            )
        }
        return VsmRenderSchedule(
            sampleRate = sampleRate,
            chunkSamples = chunkSamples,
            totalSampleCount = totalSamples,
            chunks = chunks
        )
    }

    private fun descriptor(
        categoryWidth: Int = 14,
        layoutMode: Int = 0
    ) = S5ApiDefaultPhoneFeatureDescriptor(
        categoryWidthAt10 = categoryWidth,
        minimumPhoneSecondsAt14 = 0.005f,
        maximumPhoneSecondsAt18 = 0.030f,
        followingContextCountAt1C = 0,
        precedingContextCountAt20 = 1,
        includeLeadingFlagPairAt24 = false,
        layoutModeAt25 = layoutMode
    )

    @Test
    fun runsExplicitNotePhonesThroughDenseTemporalAlternateAndResidualStages() {
        val fixture = fixture()
        val result = fixture.session.process(
            schedule = schedule(),
            descriptor = descriptor(),
            gemm = ReferenceS5MegpoidGemmKernel,
            statistics = ReferenceS5MegpoidStatisticsKernel
        )

        assertEquals(49, result.alignment.explicitPhoneCount)
        assertEquals(0, result.alignment.fallbackPhoneLabels.size)
        assertEquals(49, result.phoneFeatureMatrix.rowCount)
        assertEquals(29, result.phoneFeatureMatrix.rowWidth)
        assertEquals(32, result.sequenceOutput.channels)
        assertEquals(56, result.sequenceOutput.positions)
        assertEquals(32 * 56, result.sequenceOutput.features.size)
        assertTrue(result.sequenceOutput.features.all { it.isFinite() })
        assertEquals(
            listOf(
                BlockCall(3, 0), BlockCall(3, 1),
                BlockCall(2, 0), BlockCall(2, 1),
                BlockCall(1, 0), BlockCall(1, 1),
                BlockCall(0, 0), BlockCall(0, 1)
            ),
            fixture.calls
        )
    }

    @Test
    fun rejectsAutomaticPronunciationWhenNoteHasNoExplicitPhoneLabels() {
        val fixture = fixture()
        assertThrows(IllegalArgumentException::class.java) {
            fixture.session.process(
                schedule = schedule(phonemeText = ""),
                descriptor = descriptor(),
                gemm = ReferenceS5MegpoidGemmKernel,
                statistics = ReferenceS5MegpoidStatisticsKernel
            )
        }
    }

    @Test
    fun rejectsNonDefaultPhoneLayoutInsteadOfTreatingItAsModeZero() {
        val fixture = fixture()
        assertThrows(IllegalArgumentException::class.java) {
            fixture.session.process(
                schedule = schedule(),
                descriptor = descriptor(layoutMode = 3),
                gemm = ReferenceS5MegpoidGemmKernel,
                statistics = ReferenceS5MegpoidStatisticsKernel
            )
        }
    }

    @Test
    fun rejectsFeatureWidthMismatchAgainstLoadedDenseInput() {
        val fixture = fixture()
        assertThrows(IllegalArgumentException::class.java) {
            fixture.session.process(
                schedule = schedule(),
                descriptor = descriptor(categoryWidth = 15),
                gemm = ReferenceS5MegpoidGemmKernel,
                statistics = ReferenceS5MegpoidStatisticsKernel
            )
        }
    }

    @Test
    fun rejectsPhoneSequencesTooShortForTheResidualHistoryKernel() {
        val fixture = fixture()
        assertThrows(IllegalArgumentException::class.java) {
            fixture.session.process(
                schedule = schedule(phoneCount = 48),
                descriptor = descriptor(),
                gemm = ReferenceS5MegpoidGemmKernel,
                statistics = ReferenceS5MegpoidStatisticsKernel
            )
        }
    }
}
