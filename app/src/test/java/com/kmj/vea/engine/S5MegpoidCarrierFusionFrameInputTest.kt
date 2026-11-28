package com.kmj.vea.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5MegpoidCarrierFusionFrameInputTest {
    private fun workspace() = S5MegpoidCarrierFusionWorkspace(
        S5MegpoidCarrierFusionBufferGeometry(
            inputFeatures = 2,
            carrierPositions = 3,
            spectrumBins = 3,
            historySlotsFromRoot181 = 0,
            historySlotsFromRoot182 = 0
        )
    )

    @Test
    fun buildsCarrierInputFromFinalSequenceFeaturesAndInterleavedComplexSpectrum() {
        val workspace = workspace()
        val sequence = S5MegpoidSequenceTransitionOutput(
            features = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f),
            channels = 2,
            positions = 3
        )
        val interleaved = floatArrayOf(
            10f, -10f,
            20f, -20f,
            30f, -30f
        )

        val frame = S5MegpoidCarrierFusionFrameInput.fromSequenceOutputAndInterleavedCarrierSpectrum(
            workspace = workspace,
            sequenceOutput = sequence,
            carrierSpectrumInterleaved = interleaved,
            scale = 0.75f,
            amplitude = 1.25f
        )

        assertEquals(workspace, frame.workspace)
        assertArrayEquals(sequence.features, frame.inputFeatureMajor, 0f)
        assertArrayEquals(floatArrayOf(10f, 20f, 30f), frame.carrierHistoryFirstPlane, 0f)
        assertArrayEquals(floatArrayOf(-10f, -20f, -30f), frame.carrierHistorySecondPlane, 0f)
        assertEquals(0.75f, frame.scale, 0f)
        assertEquals(1.25f, frame.amplitude, 0f)
    }

    @Test
    fun copiesSequenceFeatureStorageSoLaterMutationCannotChangeCarrierInput() {
        val workspace = workspace()
        val sequence = S5MegpoidSequenceTransitionOutput(
            features = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f),
            channels = 2,
            positions = 3
        )
        val frame = S5MegpoidCarrierFusionFrameInput.fromSequenceOutputAndInterleavedCarrierSpectrum(
            workspace = workspace,
            sequenceOutput = sequence,
            carrierSpectrumInterleaved = FloatArray(6),
            scale = 1f,
            amplitude = 1f
        )

        sequence.features.fill(99f)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), frame.inputFeatureMajor, 0f)
    }

    @Test
    fun rejectsSequenceGeometryAndCarrierSpectrumLengthMismatches() {
        val workspace = workspace()
        val correct = S5MegpoidSequenceTransitionOutput(
            features = FloatArray(6),
            channels = 2,
            positions = 3
        )

        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidCarrierFusionFrameInput.fromSequenceOutputAndInterleavedCarrierSpectrum(
                workspace,
                correct.copy(channels = 3, features = FloatArray(9)),
                FloatArray(6),
                scale = 1f,
                amplitude = 1f
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidCarrierFusionFrameInput.fromSequenceOutputAndInterleavedCarrierSpectrum(
                workspace,
                correct.copy(positions = 2, features = FloatArray(4)),
                FloatArray(6),
                scale = 1f,
                amplitude = 1f
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidCarrierFusionFrameInput.fromSequenceOutputAndInterleavedCarrierSpectrum(
                workspace,
                correct,
                FloatArray(5),
                scale = 1f,
                amplitude = 1f
            )
        }
    }

    @Test
    fun rejectsNonFiniteCarrierSpectrumAndNonFiniteScaleControls() {
        val workspace = workspace()
        val sequence = S5MegpoidSequenceTransitionOutput(
            features = FloatArray(6),
            channels = 2,
            positions = 3
        )

        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidCarrierFusionFrameInput.fromSequenceOutputAndInterleavedCarrierSpectrum(
                workspace,
                sequence,
                FloatArray(6).also { it[2] = Float.NaN },
                scale = 1f,
                amplitude = 1f
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidCarrierFusionFrameInput.fromSequenceOutputAndInterleavedCarrierSpectrum(
                workspace,
                sequence,
                FloatArray(6),
                scale = Float.POSITIVE_INFINITY,
                amplitude = 1f
            )
        }
    }
}
