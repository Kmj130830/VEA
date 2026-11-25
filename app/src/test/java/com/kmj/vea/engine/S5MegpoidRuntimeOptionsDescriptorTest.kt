package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class S5MegpoidRuntimeOptionsDescriptorTest {
    private fun tree(
        overrides: Map<String, S5ModelValue> = emptyMap(),
        includeAlternateSelector: Boolean = true,
        includeMultipartThresholdSelector: Boolean = true,
        includeNearestPartMaskSelector: Boolean = true,
        includePartModulationIdentifier: Boolean = true
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
            S5MegpoidRuntimeOptionKeys.ALTERNATE_TRANSITION_ENABLED to S5ModelValue.BooleanValue(true),
            S5MegpoidRuntimeOptionKeys.MULTIPART_THRESHOLD_MODE_ENABLED to S5ModelValue.BooleanValue(true),
            S5MegpoidRuntimeOptionKeys.NEAREST_PART_MASK_ENABLED to S5ModelValue.BooleanValue(true),
            S5MegpoidRuntimeOptionKeys.PART_MODULATION_IDENTIFIER to
                S5ModelValue.StringValue(S5MegpoidRuntimeOptionKeys.EXPECTED_PART_MODULATION_IDENTIFIER)
        )
        if (!includeAlternateSelector) {
            defaults.remove(S5MegpoidRuntimeOptionKeys.ALTERNATE_TRANSITION_ENABLED)
        }
        if (!includeMultipartThresholdSelector) {
            defaults.remove(S5MegpoidRuntimeOptionKeys.MULTIPART_THRESHOLD_MODE_ENABLED)
        }
        if (!includeNearestPartMaskSelector) {
            defaults.remove(S5MegpoidRuntimeOptionKeys.NEAREST_PART_MASK_ENABLED)
        }
        if (!includePartModulationIdentifier) {
            defaults.remove(S5MegpoidRuntimeOptionKeys.PART_MODULATION_IDENTIFIER)
        }
        defaults.putAll(overrides)
        val entries = defaults.entries.joinToString(",") { (key, value) ->
            val encoded = when (value) {
                is S5ModelValue.NumberValue -> value.value.toString()
                is S5ModelValue.BooleanValue -> value.value.toString()
                is S5ModelValue.StringValue -> "\"" + value.value + "\""
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
        assertTrue(options.multipartThresholdModeEnabled)
        assertTrue(options.nearestPartMaskEnabled)
        assertEquals(
            S5MegpoidRuntimeOptionKeys.EXPECTED_PART_MODULATION_IDENTIFIER,
            options.partModulationIdentifier
        )
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
    fun readsMultipartThresholdModeAndDefaultsToFalseWhenTheOptionIsAbsent() {
        val enabled = S5MegpoidRuntimeOptionsDescriptor.from(tree())
        assertTrue(enabled.multipartThresholdModeEnabled)

        val absent = S5MegpoidRuntimeOptionsDescriptor.from(
            tree(includeMultipartThresholdSelector = false)
        )
        assertFalse(absent.multipartThresholdModeEnabled)

        val explicitlyDisabled = S5MegpoidRuntimeOptionsDescriptor.from(tree(mapOf(
            S5MegpoidRuntimeOptionKeys.MULTIPART_THRESHOLD_MODE_ENABLED to
                S5ModelValue.BooleanValue(false)
        )))
        assertFalse(explicitlyDisabled.multipartThresholdModeEnabled)
    }

    @Test
    fun readsTheNearestPartMaskFlagAndDefaultsToFalseWhenAbsent() {
        val enabled = S5MegpoidRuntimeOptionsDescriptor.from(tree())
        assertTrue(enabled.nearestPartMaskEnabled)
        assertEquals(
            S5MegpoidRuntimeOptionKeys.EXPECTED_PART_MODULATION_IDENTIFIER,
            enabled.partModulationIdentifier
        )

        val absent = S5MegpoidRuntimeOptionsDescriptor.from(
            tree(includeNearestPartMaskSelector = false)
        )
        assertFalse(absent.nearestPartMaskEnabled)
        assertEquals(null, absent.partModulationIdentifier)
    }

    @Test
    fun requiresTheNativePartModulationIdentifierOnlyWhenTheMaskFlagIsEnabled() {
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidRuntimeOptionsDescriptor.from(
                tree(includePartModulationIdentifier = false)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5MegpoidRuntimeOptionsDescriptor.from(tree(mapOf(
                S5MegpoidRuntimeOptionKeys.PART_MODULATION_IDENTIFIER to
                    S5ModelValue.StringValue("wrong-modulation-id")
            )))
        }

        // A disabled mask branch does not read/validate the optional identifier.
        val disabled = S5MegpoidRuntimeOptionsDescriptor.from(tree(mapOf(
            S5MegpoidRuntimeOptionKeys.NEAREST_PART_MASK_ENABLED to
                S5ModelValue.BooleanValue(false),
            S5MegpoidRuntimeOptionKeys.PART_MODULATION_IDENTIFIER to
                S5ModelValue.StringValue("unused")
        )))
        assertFalse(disabled.nearestPartMaskEnabled)
        assertEquals(null, disabled.partModulationIdentifier)
    }

    @Test
    fun derivesTheActiveFourPartOutputHeadWidthFromMegpoidOptions() {
        val options = S5MegpoidRuntimeOptionsDescriptor.from(tree())

        assertTrue(options.usesMultiPartOutputHead)
        assertEquals(8, options.postResidualHeadFeatureWidth)
    }

    @Test
    fun buildsFourPartModulationFromTheActualMegpoidOutputGeometryAndDValues() {
        val options = S5MegpoidRuntimeOptionsDescriptor.from(tree())
        val table = options.buildMultipartOutputModulation(
            positionCount = 3,
            fundamentalFrequencyHz = 100f,
            thetaCenterRadians = 0.5f
        )

        assertEquals(4, table.partCount)
        assertEquals(3, table.positionCount)
        // Native ratio is root +0x10 / root +0x04 = 32000 / 1024 = 31.25.
        assertEquals(
            listOf(100f, 200f, 300f, 400f),
            table.copyDistances().copyOfRange(0, 4).toList()
        )
        // Native stores D02 in an int-backed word, so 133.34 is narrowed to 133.
        assertEquals(listOf(1f, 0f, 0f, 0f), table.copyGains().copyOfRange(0, 4).toList())
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
