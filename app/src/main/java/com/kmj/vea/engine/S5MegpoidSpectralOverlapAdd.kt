package com.kmj.vea.engine

/**
 * Neutral names for the fields consumed by S5API FUN_18012c740.
 *
 * The native transform descriptor is not yet reconstructed at its constructor
 * boundary, so geometryParameterAt04 and windowOffsetAt10 deliberately retain
 * their byte-offset identity instead of claiming a vendor semantic name.
 */
data class S5MegpoidOverlapAddParameters(
    val outputSamples: Int,
    val geometryParameterAt04: Int,
    val fftSize: Int,
    val windowOffsetAt10: Int,
    val overlapContributionCountAt14: Int,
    val ringMaskAt1C: Int,
    val alternatingBinSignAt90: Boolean,
    val frameWindow: FloatArray,
    val outputWindow: FloatArray
) {
    init {
        require(outputSamples > 0)
        require(geometryParameterAt04 >= 0)
        require(fftSize >= 2 && fftSize % 2 == 0)
        require(overlapContributionCountAt14 > 0)
        require(ringMaskAt1C >= 0 && ringMaskAt1C < Int.MAX_VALUE)
        require((ringMaskAt1C and (ringMaskAt1C + 1)) == 0) {
            "The S5API circular-buffer mask must be 2^n - 1."
        }
        require(frameWindow.size == fftSize) {
            "The frame window must contain exactly fftSize values."
        }
        require(outputWindow.size == outputSamples) {
            "The output window must contain exactly outputSamples values."
        }
        require(frameWindow.all { it.isFinite() } && outputWindow.all { it.isFinite() }) {
            "Overlap-add window coefficients must be finite."
        }
    }

    val ringSlotCount: Int get() = ringMaskAt1C + 1
}

/**
 * Stateful spectral-frame inverse transform and overlap-add stage.
 *
 * This ports the observed buffer flow in FUN_18012c740:
 *   1. inverse-transform one-sided complex spectrum to N real samples;
 *   2. normalize the frame by N (performed by S5MegpoidInverseRealFft);
 *   3. apply the frame window and store it in the current circular-ring slot;
 *   4. advance the ring cursor;
 *   5. accumulate the configured newest-to-oldest ring contributions into
 *      outputSamples samples using the descriptor's geometry fields;
 *   6. apply the output window.
 *
 * The two windows and raw geometry fields must come from a verified native
 * transform descriptor. This class does not synthesize their values.
 */
class S5MegpoidSpectralOverlapAdd(
    parameters: S5MegpoidOverlapAddParameters
) {
    private val outputSamples = parameters.outputSamples
    private val geometryParameterAt04 = parameters.geometryParameterAt04
    private val fftSize = parameters.fftSize
    private val windowOffsetAt10 = parameters.windowOffsetAt10
    private val overlapContributionCountAt14 = parameters.overlapContributionCountAt14
    private val ringMaskAt1C = parameters.ringMaskAt1C
    private val alternatingBinSignAt90 = parameters.alternatingBinSignAt90
    private val frameWindow = parameters.frameWindow.copyOf()
    private val outputWindow = parameters.outputWindow.copyOf()
    private val inverseFft = S5MegpoidInverseRealFft(fftSize)
    private val ring = FloatArray(checkedProduct(parameters.ringSlotCount, fftSize))

    private var ringWriteIndex = 0

    /** Converts model-produced complex bins and emits one output block. */
    @Synchronized
    fun processSpectrum(interleavedSpectrum: FloatArray): FloatArray =
        processFrame(inverseFft.transform(interleavedSpectrum, alternatingBinSignAt90))

    /**
     * Accepts an already inverse-transformed N-sample frame.
     *
     * This entry point is also useful for testing the recovered ring/window
     * behavior independently from FFT arithmetic.
     */
    @Synchronized
    fun processFrame(timeDomainFrame: FloatArray): FloatArray {
        require(timeDomainFrame.size == fftSize) {
            "A time-domain frame must contain exactly fftSize samples."
        }
        require(timeDomainFrame.all { it.isFinite() }) {
            "The time-domain frame contains a non-finite value."
        }

        val writeSlot = ringWriteIndex and ringMaskAt1C
        val writeBase = checkedNonNegativeProduct(writeSlot, fftSize)
        for (sample in 0 until fftSize) {
            ring[writeBase + sample] = timeDomainFrame[sample] * frameWindow[sample]
        }

        // Native code increments descriptor +0x20 before reading newest-first.
        ringWriteIndex += 1

        val output = FloatArray(outputSamples)
        val frameHalf = (fftSize + 1) / 2
        val geometryHalf = geometryParameterAt04 / 2

        for (contributionIndex in 0 until overlapContributionCountAt14) {
            val slot = ((ringWriteIndex.toLong() - contributionIndex.toLong() - 1L) and
                ringMaskAt1C.toLong()).toInt()
            val ringBase = checkedNonNegativeProduct(slot, fftSize)
            val relativeOffset = windowOffsetAt10.toLong() - contributionIndex.toLong()

            // Reproduces the clamped source interval selected by FUN_18012c740.
            val startCandidate = -checkedLongProduct(relativeOffset, outputSamples.toLong())
            val sourceStartValue = (frameHalf.toLong() +
                minOf(geometryHalf.toLong(), startCandidate)).coerceAtLeast(0L)

            val endCandidate = checkedLongProduct(1L - relativeOffset, outputSamples.toLong())
            val sourceEndValue = (frameHalf.toLong() +
                minOf(geometryHalf.toLong(), endCandidate)).coerceAtLeast(0L)

            require(sourceStartValue <= fftSize.toLong() && sourceEndValue <= fftSize.toLong()) {
                "The active overlap geometry addresses samples outside the inverse FFT frame."
            }

            val sourceStart = sourceStartValue.toInt()
            val sourceEnd = sourceEndValue.toInt()
            val outputStartValue = (outputSamples.toLong() - sourceEndValue).coerceAtLeast(0L)
            val copyCount = sourceEnd - sourceStart

            if (copyCount <= 0) continue
            require(outputStartValue + copyCount.toLong() <= outputSamples.toLong()) {
                "The active overlap geometry addresses samples outside the output block."
            }

            val outputStart = outputStartValue.toInt()
            for (sample in 0 until copyCount) {
                output[outputStart + sample] += ring[ringBase + sourceStart + sample]
            }
        }

        for (sample in output.indices) {
            output[sample] *= outputWindow[sample]
            check(output[sample].isFinite()) {
                "Overlap-add produced a non-finite output sample."
            }
        }
        return output
    }

    /** Clears retained frame history at an utterance boundary. */
    @Synchronized
    fun resetHistory() {
        ring.fill(0f)
        ringWriteIndex = 0
    }

    internal val writeIndexForTesting: Int get() = ringWriteIndex

    private fun checkedProduct(a: Int, b: Int): Int {
        val product = a.toLong() * b.toLong()
        require(a > 0 && b > 0 && product <= Int.MAX_VALUE) {
            "Overlap-add buffer size is invalid or exceeds the JVM array limit."
        }
        return product.toInt()
    }

    private fun checkedNonNegativeProduct(a: Int, b: Int): Int {
        val product = a.toLong() * b.toLong()
        require(a >= 0 && b >= 0 && product <= Int.MAX_VALUE) {
            "Overlap-add ring offset is invalid or exceeds the JVM array limit."
        }
        return product.toInt()
    }

    private fun checkedLongProduct(a: Long, b: Long): Long {
        val product = a * b
        require(a == 0L || product / a == b) {
            "Overlap-add geometry arithmetic overflowed."
        }
        return product
    }
}
