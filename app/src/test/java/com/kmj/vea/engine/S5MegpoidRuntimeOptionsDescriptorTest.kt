package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class S5MegpoidRuntimeOptionsDescriptorTest {
    private fun tree(
        overrides: Map<String, S5ModelValue> = emptyMap(),
        includeAlternateSelector: Boolean = true
    ): S5ModelObjectTree {
        val defaults = linkedMapOf<String, S5ModelValue>(
            S5MegpoidRuntimeOptionKeys.PARAM_0 to S5ModelValue.NumberValue(217.0),
            S5MegpoidRuntimeOptionKeys.PARAM_1 to S5ModelValue.NumberValue(1024.0),
            S5MegpoidRuntimeOptionKeys.PARAM_2 to S5ModelValue.NumberValue(513.0),
            S5MegpoidRuntimeOptionKeys.PARAM_3 to S5ModelValue.NumberValue(320.0),
            S5MegpoidRuntimeOptionKeys.PARAM_4 to S5ModelValue.NumberValue(32000.0),
            S5MegpoidRuntimeOptionKeys.PARAM_5 to S5ModelValue.NumberValue(4.0),
            S5MegpoidRuntimeOptionKeys.PARAM_6 to S5ModelValue.NumberValue(2.0),
            S5MegpoidRuntimeOptionKeys.PARAM_13 to S5ModelValue.BooleanValue(false),
            S5MegpoidRuntimeOptionKeys.PARAM_14 to S5ModelValue.NumberValue(0.0),
            S5MegpoidRuntimeOptionKeys.PARAM_15 to S5ModelValue.NumberValue(1.0),
            S5MegpoidRuntimeOptionKeys.PARAM_D00 to S5ModelValue.NumberValue(4.0),
            S5MegpoidRuntimeOptionKeys.PARAM_D01 to S5ModelValue.BooleanValue(true),
            S5MegpoidRuntimeOptionKeys.PARAM_D02 to S5ModelValue.NumberValue(133.34),
            S5MegpoidRuntimeOptionKeys.PARAM_D03 to S5ModelValue.NumberValue(25.0),
            S5MegpoidRuntimeOptionKeys.PARAM_D04 to S5ModelValue.BooleanValue(true),
            S5MegpoidRuntimeOptionKeys.PARAM_D05 to S5ModelValue.NumberValue(0.01),
            S5MegpoidRuntimeOptionKeys.TERMINAL_SPECTRUM_LOG_MAGNITUDE_PHASE to
                S5ModelValue.BooleanValue(true),
            S5MegpoidRuntimeOptionKeys.CARRIER_FUSION_ENABLED to S5ModelValue.BooleanValue(true),
            S5MegpoidRuntimeOptionKeys.ALTERNATE_TRANSITION_ENABLED to S5ModelValue.BooleanValue(true)
        )
        if (!includeAlternateSelector) {
            defaults.remove(S5MegpoidRuntimeOptionKeys.ALTERNATE_TRANSITION_ENABLED)
        }
        defaults.putAll(overrides)
        val entries = defaults.entries.joinToString(",") { (key, value) ->
            val encoded = when (value) {
                is S5ModelValue.NumberValue -> value.value.toString()
                is S5ModelValue.BooleanValue -> value.value.toString()
                else -> error("Unsupported test value.")
            }
            "\"" + key + "\":" + encoded
        }
        return S5ModelObjectTree.parseUtf8("{" + entries + "}")
    }

    @Test
    fun readsTheExactMegpoidRuntimeOptionsAndDerivesNativeLoopCounts() {
        val options = S5MegpoidRuntimeOptionsDescriptor.from(tree())
        assertEquals(217, options.param0)
        assertEquals(1024, options.param1)
        assertEquals(513, options.param2)
        assertEquals(320, options.param3)
        assertEquals(32000, options.param4)
        assertEquals(4, options.sequenceSlotCount)
        assertEquals(2, options.residualBlockColumns)
        assertEquals(3, options.temporalTransitionCount)
        assertEquals(3, options.bankedTransitionCount)
        assertEquals(8, options.residualBlockCount)
        assertFalse(options.param13)
        assertTrue(options.paramD01)
        assertEquals(133.34, options.paramD02, 0.000001)
        assertEquals(25.0, options.paramD03, 0.000001)
        assertTrue(options.paramD04)
        assertEquals(0.01, options.paramD05, 0.000001)
        assertTrue(options.terminalSpectrumLogMagnitudePhase)
        assertEquals(S5MegpoidSpectrumFrameEncoding.LOG_MAGNITUDE_PHASE,
            options.terminalSpectrumEncoding)
        assertTrue(options.carrierFusionEnabled)
        assertTrue(options.alternateTransitionEnabled)
        assertEquals(S5MegpoidReverseTransitionMode.ALTERNATE, options.reverseTransitionMode)
        options.requireMegpoidV7BlockGrid()
    }

    @Test
    fun readsAlternateTransitionSelectorAndDefaultsToStandardWhenTheKeyIsAbsent() {
        val alternate = S5MegpoidRuntimeOptionsDescriptor.from(tree())
        assertTrue(alternate.alternateTransitionEnabled)
        assertEquals(S5MegpoidReverseTransitionMode.ALTERNATE, alternate.reverseTransitionMode)

        val standard = S5MegpoidRuntimeOptionsDescriptor.from(
            tree(includeAlternateSelector = false)
        )
        assertFalse(standard.alternateTransitionEnabled)
        assertEquals(S5MegpoidReverseTransitionMode.STANDARD_BANKED, standard.reverseTransitionMode)

        val explicitlyStandard = S5MegpoidRuntimeOptionsDescriptor.from(tree(mapOf(
            S5MegpoidRuntimeOptionKeys.ALTERNATE_TRANSITION_ENABLED to
                S5ModelValue.BooleanValue(false)
        )))
        assertFalse(explicitlyStandard.alternateTransitionEnabled)
    }

    @Test
    fun derivesTheActiveFourPartOutputHeadWidthFromMegpoidOptions() {
        val options = S5MegpoidRuntimeOptionsDescriptor.from(tree())

        assertTrue(options.usesMultiPartOutputHead)
        assertEquals(8, options.postResidualHeadFeatureWidth)
    }

    @Test
    fun fallsBackToTwoOutputPlanesWhenTheMultiPartBranchIsDisabled() {
        val disabled = S5MegpoidRuntimeOptionsDescriptor.from(tree(mapOf(
            S5MegpoidRuntimeOptionKeys.PARAM_D01 to S5ModelValue.BooleanValue(false)
        )))
        assertFalse(disabled.usesMultiPartOutputHead)
        assertEquals(2, disabled.postResidualHeadFeatureWidth)

        val onePart = S5MegpoidRuntimeOptionsDescriptor.from(tree(mapOf(
            S5MegpoidRuntimeOptionKeys.PARAM_D00 to S5ModelValue.NumberValue(1.0)
        )))
        assertFalse(onePart.usesMultiPartOutputHead)
        assertEquals(2, onePart.postResidualHeadFeatureWidth)
    }

    @Test
    fun rejectsOverflowingMultiPartOutputHeadWidth() {
        val options = S5MegpoidRuntimeOptionsDescriptor.from(tree(mapOf(
            S5MegpoidRuntimeOptionKeys.PARAM_D00 to
                S5ModelValue.NumberValue(Int.MAX_VALUE.toDouble())
        )))
        assertTrue(options.usesMultiPartOutputHead)
        assertThrows(IllegalArgumentException::class.java) {
            options.postResidualHeadFeatureWidth
        }
    }

    @Test
    fun derivesMegpoidOutputTransformInputsFromModelOptionsAndNativeRootDefaults() {
        val options = S5MegpoidRuntimeOptionsDescriptor.from(tree())
        val inputs = options.buildOutputTransformInputsFromNativeMegpoidDefaults()

        assertEquals(320, inputs.outputSamplesAtRoot0C)
        assertEquals(1024, inputs.geometryAndFftSizeAtRoot04)
        assertEquals(32000f, inputs.floatParameterAtRoot10, 0f)
        assertEquals(0, inputs.frameWindowCoreLengthAtRoot61C)
        assertFalse(inputs.alternatingBinSignAtRoot620)
        assertFalse(inputs.flagAtRoot1F74)
        assertEquals(0, inputs.frameWindowShapeModeAtRoot3418)
        assertFalse(inputs.flagAtRoot1F75)

        val parameters = inputs.buildOverlapAddParameters()
        assertEquals(1024, parameters.fftSize)
        assertEquals(320, parameters.outputSamples)
        assertEquals(2, parameters.windowOffsetAt10)
        assertEquals(4, parameters.overlapContributionCountAt14)
        assertEquals(1, parameters.ringMaskAt1C)
    }
    @Test
    fun derivesOneSidedComplexSpectrumOutputShapeFromModelOptions() {
        val options = S5MegpoidRuntimeOptionsDescriptor.from(tree())
        val shape = options.buildSpectrumOutputShape()

        assertEquals(1024, shape.fftSize)
        assertEquals(513, shape.complexBinCount)
        assertEquals(1026, shape.interleavedFloatCount)

        val frame = shape.newInterleavedFrame()
        assertEquals(1026, frame.size)
        assertTrue(frame.all { it == 0f })
        shape.requireInterleavedFrame(frame)
        assertThrows(IllegalArgumentException::class.java) {
            shape.requireInterleavedFrame(FloatArray(1024))
        }
    }

    @Test
    fun rejectsSpectrumBinCountThatDoesNotMatchTheRealFftSize() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidSpectrumOutputShape(fftSize = 1024, complexBinCount = 512)
        }
    }
    @Test
    fun mapsSpectrumRepresentationFlagToExactOutputEncoding() {
        val logPolar = S5MegpoidRuntimeOptionsDescriptor.from(tree())
        assertEquals(
            S5MegpoidSpectrumFrameEncoding.LOG_MAGNITUDE_PHASE,
            logPolar.terminalSpectrumEncoding
        )

        val direct = S5MegpoidRuntimeOptionsDescriptor.from(tree(mapOf(
            S5MegpoidRuntimeOptionKeys.TERMINAL_SPECTRUM_LOG_MAGNITUDE_PHASE to
                S5ModelValue.BooleanValue(false)
        )))
        assertEquals(
            S5MegpoidSpectrumFrameEncoding.DIRECT_COMPLEX,
            direct.terminalSpectrumEncoding
        )
    }

    @Test
    fun readsCarrierFusionFlagIndependentlyOfTheMultipartHeadFlag() {
        val enabled = S5MegpoidRuntimeOptionsDescriptor.from(tree())
        assertTrue(enabled.usesMultiPartOutputHead)
        assertTrue(enabled.carrierFusionEnabled)

        val disabled = S5MegpoidRuntimeOptionsDescriptor.from(tree(mapOf(
            S5MegpoidRuntimeOptionKeys.CARRIER_FUSION_ENABLED to
                S5ModelValue.BooleanValue(false)
        )))
        assertTrue(disabled.usesMultiPartOutputHead)
        assertFalse(disabled.carrierFusionEnabled)
    }

    @Test
    fun rejectsMissingFieldsAndWrongJsonValueTypes() {
        val missing = S5ModelObjectTree.parseUtf8("{}")
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidRuntimeOptionsDescriptor.from(missing)
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidRuntimeOptionsDescriptor.from(tree(mapOf(
                S5MegpoidRuntimeOptionKeys.PARAM_5 to S5ModelValue.BooleanValue(true)
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidRuntimeOptionsDescriptor.from(tree(mapOf(
                S5MegpoidRuntimeOptionKeys.PARAM_D01 to S5ModelValue.NumberValue(1.0)
            )))
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidRuntimeOptionsDescriptor.from(tree(mapOf(
                S5MegpoidRuntimeOptionKeys.TERMINAL_SPECTRUM_LOG_MAGNITUDE_PHASE to
                    S5ModelValue.NumberValue(1.0)
            )))
        }
    }

    @Test
    fun rejectsNonIntegralDimensionsAndUnsupportedResidualGrids() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidRuntimeOptionsDescriptor.from(tree(mapOf(
                S5MegpoidRuntimeOptionKeys.PARAM_5 to S5ModelValue.NumberValue(4.5)
            )))
        }
        val threeColumns = S5MegpoidRuntimeOptionsDescriptor.from(tree(mapOf(
            S5MegpoidRuntimeOptionKeys.PARAM_6 to S5ModelValue.NumberValue(3.0)
        )))
        assertEquals(12, threeColumns.residualBlockCount)
        assertThrows(IllegalArgumentException::class.java) {
            threeColumns.requireMegpoidV7BlockGrid()
        }
    }
}
