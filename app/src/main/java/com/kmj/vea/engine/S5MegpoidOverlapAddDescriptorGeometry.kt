package com.kmj.vea.engine

/**
 * Geometry fields computed by S5API FUN_180127dc0 for the output/overlap
 * descriptor. The source values are kept separate because the wrapper
 * reads outputSamples, geometry and fftSize from three independent arguments.
 */
data class S5MegpoidOverlapAddDescriptorGeometry(
    val outputSamples: Int,
    val geometry: Int,
    val fftSize: Int,
    val windowOffset: Int,
    val overlapContributionCount: Int,
    val ringSlotCount: Int,
    val ringMask: Int
) {
    init {
        require(outputSamples > 0)
        require(geometry > 0)
        require(fftSize > 0)
        require(windowOffset >= 0)
        require(overlapContributionCount == windowOffset + 2)
        require(ringSlotCount > 0 && (ringSlotCount and (ringSlotCount - 1)) == 0) {
            "The S5API overlap ring slot count must be a power of two."
        }
        require(ringMask == ringSlotCount - 1)
    }

    /**
     * Builds frame and output windows from the native descriptor inputs, then
     * returns the complete parameter object consumed by the spectral OLA
     * executor. This is the supported Megpoid V7 path where geometry == fftSize.
     */
    fun buildOverlapAddParameters(
        coreWindowLength: Int,
        frameWindowShapeMode: Int,
        alternatingBinSign: Boolean,
        flagAt91: Boolean = false,
        flagAt92: Boolean = false
    ): S5MegpoidOverlapAddParameters {
        require(geometry == fftSize) {
            "The current Megpoid V7 overlap-add integration requires geometry == fftSize."
        }
        val baseFrameWindow = S5MegpoidFrameWindowBuilder.build(
            totalLength = geometry,
            coreLength = coreWindowLength,
            shapeMode = frameWindowShapeMode
        )
        val windows = S5MegpoidOutputWindowBuilder.build(
            frameWindow = baseFrameWindow,
            outputSamples = outputSamples,
            geometry = geometry,
            fftSize = fftSize,
            windowOffset = windowOffset,
            overlapContributionCount = overlapContributionCount,
            flagAt91 = flagAt91,
            flagAt92 = flagAt92
        )
        return toOverlapAddParameters(
            alternatingBinSign = alternatingBinSign,
            frameWindow = windows.frameWindow,
            outputWindow = windows.outputWindow
        )
    }

    fun createSpectralOverlapAdd(
        coreWindowLength: Int,
        frameWindowShapeMode: Int,
        alternatingBinSign: Boolean,
        flagAt91: Boolean = false,
        flagAt92: Boolean = false
    ): S5MegpoidSpectralOverlapAdd =
        S5MegpoidSpectralOverlapAdd(
            buildOverlapAddParameters(
                coreWindowLength = coreWindowLength,
                frameWindowShapeMode = frameWindowShapeMode,
                alternatingBinSign = alternatingBinSign,
                flagAt91 = flagAt91,
                flagAt92 = flagAt92
            )
        )

    /**
     * Builds the parameter carrier consumed by the Android overlap-add
     * reference, using these descriptor-derived scalars and supplied windows.
     */
    fun toOverlapAddParameters(
        alternatingBinSign: Boolean,
        frameWindow: FloatArray,
        outputWindow: FloatArray
    ): S5MegpoidOverlapAddParameters =
        S5MegpoidOverlapAddParameters(
            outputSamples = outputSamples,
            geometryParameterAt04 = geometry,
            fftSize = fftSize,
            windowOffsetAt10 = windowOffset,
            overlapContributionCountAt14 = overlapContributionCount,
            ringMaskAt1C = ringMask,
            alternatingBinSignAt90 = alternatingBinSign,
            frameWindow = frameWindow,
            outputWindow = outputWindow
        )
}

/**
 * Reproduces the scalar geometry construction in S5API FUN_180127dc0.
 *
 * For positive geometry G and output block length O:
 *   halfGeometry = floor(G / 2)
 *   windowOffset = ceil(halfGeometry / O)
 *   overlapContributionCount = windowOffset + 2
 *   ringSlotCount = nextPowerOfTwo(windowOffset)
 *   ringMask = ringSlotCount - 1
 *
 * The FFT size is copied independently from the constructor's third scalar
 * argument. The current native caller may pass the same value for geometry
 * and FFT size, but this helper deliberately does not assume that equality.
 */
object S5MegpoidOverlapAddDescriptorGeometryBuilder {
    fun build(
        outputSamples: Int,
        geometry: Int,
        fftSize: Int
    ): S5MegpoidOverlapAddDescriptorGeometry {
        require(outputSamples > 0) { "Output sample count must be positive." }
        require(geometry > 0) { "Overlap geometry must be positive." }
        require(fftSize > 0) { "FFT size must be positive." }

        val halfGeometry = geometry / 2
        val quotient = halfGeometry / outputSamples
        val remainder = halfGeometry % outputSamples
        val windowOffset = checkedAdd(quotient, if (remainder == 0) 0 else 1)
        val overlapCount = checkedAdd(windowOffset, 2)
        val ringSlots = nextPowerOfTwo(windowOffset)

        return S5MegpoidOverlapAddDescriptorGeometry(
            outputSamples = outputSamples,
            geometry = geometry,
            fftSize = fftSize,
            windowOffset = windowOffset,
            overlapContributionCount = overlapCount,
            ringSlotCount = ringSlots,
            ringMask = ringSlots - 1
        )
    }

    private fun nextPowerOfTwo(value: Int): Int {
        if (value <= 1) return 1
        require(value <= (1 shl 30)) {
            "Overlap ring capacity exceeds the supported positive Int range."
        }
        var spread = value - 1
        spread = spread or (spread ushr 1)
        spread = spread or (spread ushr 2)
        spread = spread or (spread ushr 4)
        spread = spread or (spread ushr 8)
        spread = spread or (spread ushr 16)
        return checkedAdd(spread, 1)
    }

    private fun checkedAdd(a: Int, b: Int): Int {
        val result = a.toLong() + b.toLong()
        require(result in 0..Int.MAX_VALUE.toLong()) {
            "Output overlap descriptor geometry overflows a signed 32-bit field."
        }
        return result.toInt()
    }
}
