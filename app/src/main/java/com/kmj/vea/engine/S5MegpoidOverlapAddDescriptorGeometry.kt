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
