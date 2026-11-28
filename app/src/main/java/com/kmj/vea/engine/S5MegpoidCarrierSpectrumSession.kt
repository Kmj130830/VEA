package com.kmj.vea.engine

/**
 * S5API's independent carrier/noise spectrum path recovered from
 * FUN_18012b4a0 (called by FUN_18012b140 and FUN_18012b320).
 *
 * This is NOT the periodic/F0 excitation path in FUN_18012b150:
 * - it writes either caller-provided real samples or seed-12345 Gaussian noise
 *   into a circular sample history;
 * - it selects a delayed FFT-sized frame from that history;
 * - it applies the native frame window and forward real FFT;
 * - an optional flag flips the sign of bins 1,3,5,... in both complex planes.
 *
 * Native state values:
 *   +0x00 hop/sample count
 *   +0x04 frame-window length
 *   +0x08 FFT size
 *   +0x10 lag block count = ceil(windowLength / hop) + 1
 *   +0x14 left padding = (fftSize - hop) / 2
 *   +0x18 window coefficients
 *   +0x30 FFT plan
 *   +0x40 FFT working buffer
 *   +0x50 circular sample ring
 *   +0x68 ring mask (power-of-two capacity - 1)
 *   +0x70 logical sample cursor
 *   +0x78 odd-bin sign-flip flag
 *
 * The AI Megpoid initializer call sites pass windowLength == fftSize,
 * hop/sample count from render-root +0x0C, the window core length from
 * +0x61C, odd-bin flag from +0x620, and Gaussian seed 12345. The public
 * constructor keeps these values explicit so other singers/contexts can
 * supply their own render-state values.
 */
class S5MegpoidCarrierSpectrumSession(
    val fftSize: Int,
    val hopSamples: Int,
    windowCoreLength: Int,
    windowShapeMode: Int,
    val invertOddComplexBins: Boolean,
    randomSeed: Int = NATIVE_RANDOM_SEED
) {
    private val frameWindow = S5MegpoidFrameWindowBuilder.build(
        totalLength = fftSize,
        coreLength = windowCoreLength,
        shapeMode = windowShapeMode
    )
    private val forwardFft = S5MegpoidForwardRealFft(fftSize)
    private val lagBlocks: Int
    private val leftPadding: Int
    private val ringMask: Int
    private val ring: FloatArray
    private var gaussianNoise = S5MegpoidGaussianNoise.fromNativeSeed(randomSeed)
    private var sampleCursor = 0L
    private var framesProcessed = 0L

    init {
        require(fftSize >= 2 && (fftSize and (fftSize - 1)) == 0) {
            "The native carrier FFT requires a radix-2 FFT size."
        }
        require(hopSamples > 0 && hopSamples <= fftSize) {
            "Carrier hop size must be between one sample and the FFT size."
        }
        require(windowCoreLength >= 0) {
            "Carrier window core length cannot be negative."
        }
        require(windowCoreLength == 0 || windowCoreLength <= fftSize) {
            "Carrier window core length cannot exceed the FFT size."
        }

        // FUN_180128a50 stores ceil(windowLength / hopSamples) + 1 at +0x10.
        lagBlocks = ceilDiv(fftSize, hopSamples) + 1

        // Native signed integer division truncates toward zero. The supported
        // model geometry has fftSize >= hopSamples, so this is floor division.
        leftPadding = (fftSize - hopSamples) / 2

        // FUN_180128a50 allocates the next power-of-two ring covering the
        // delayed source frame, its left padding, and the current sample hop:
        // (ceil(windowLength / hopSamples) + 2)*hop
        // + leftPadding + fftSize - 1 + hop.
        // Since lagBlocks = ceil(windowLength / hopSamples) + 1, this is
        // (lagBlocks + 1)*hop + leftPadding + fftSize - 1 + hop.
        val requiredCapacity =
            (lagBlocks.toLong() + 1L) * hopSamples.toLong() +
                leftPadding.toLong() +
                fftSize.toLong() - 1L +
                hopSamples.toLong()
        require(requiredCapacity in 1..(1L shl 30)) {
            "Native carrier ring capacity exceeds the supported array size."
        }
        val capacity = nextPowerOfTwo(requiredCapacity)
        ringMask = (capacity - 1L).toInt()
        ring = FloatArray(capacity.toInt())
    }

    val processedFrameCount: Long
        @Synchronized get() = framesProcessed

    val logicalSampleCursor: Long
        @Synchronized get() = sampleCursor

    val ringCapacity: Int
        get() = ring.size

    val nativeLagBlockCount: Int
        get() = lagBlocks

    val nativeLeftPadding: Int
        get() = leftPadding

    /**
     * Generates one carrier spectrum frame.
     *
     * [inputSamples] is the optional native b4a0 input pointer. When null,
     * samples come from the state-owned seed-12345 Gaussian stream, matching
     * the b140 wrapper's explicit null input. Otherwise exactly hopSamples
     * samples are written to the ring.
     *
     * Output is fftSize/2+1 interleaved complex bins.
     */
    @Synchronized
    fun processFrame(inputSamples: FloatArray? = null): FloatArray {
        require(inputSamples == null || inputSamples.size == hopSamples) {
            "Carrier input must contain exactly $hopSamples samples."
        }
        require(inputSamples == null || inputSamples.all { it.isFinite() }) {
            "Carrier input contains a non-finite sample."
        }
        check(sampleCursor <= Long.MAX_VALUE - hopSamples.toLong()) {
            "Carrier sample cursor overflowed."
        }
        check(framesProcessed < Long.MAX_VALUE) {
            "Carrier frame counter overflowed."
        }

        // FUN_18012b4a0 writes at (cursor + sampleIndex) & ringMask.
        for (index in 0 until hopSamples) {
            val sample = inputSamples?.get(index) ?: gaussianNoise.nextSample()
            ring[((sampleCursor + index.toLong()) and ringMask.toLong()).toInt()] = sample
        }
        sampleCursor += hopSamples.toLong()

        // Native source start:
        // (cursor / hop - lagBlocks - 1) * hop - leftPadding.
        val startBlock = sampleCursor / hopSamples.toLong() -
            lagBlocks.toLong() - 1L
        val sourceStart = startBlock * hopSamples.toLong() - leftPadding.toLong()

        val windowedFrame = FloatArray(fftSize)
        for (index in 0 until fftSize) {
            val logicalIndex = sourceStart + index.toLong()
            val sample = if (logicalIndex >= 0L && logicalIndex < sampleCursor) {
                ring[(logicalIndex and ringMask.toLong()).toInt()]
            } else {
                0f
            }
            windowedFrame[index] = sample * frameWindow[index]
        }

        val spectrum = forwardFft.transform(windowedFrame)

        // Native b4a0 starts at complex bin 1 and flips both float sign bits,
        // advancing by one complex pair (two float values) each iteration.
        if (invertOddComplexBins) {
            var bin = 1
            while (bin < forwardFft.binCount) {
                spectrum[bin * 2] = -spectrum[bin * 2]
                spectrum[bin * 2 + 1] = -spectrum[bin * 2 + 1]
                bin += 2
            }
        }

        framesProcessed++
        return spectrum
    }

    /** Returns a defensive copy of the circular buffer in logical order. */
    @Synchronized
    fun copyHistoryOldestToNewest(): FloatArray {
        val result = FloatArray(ring.size)
        val oldestLogicalIndex = (sampleCursor - ring.size.toLong()).coerceAtLeast(0L)
        val validCount = minOf(sampleCursor, ring.size.toLong()).toInt()
        val zeroCount = ring.size - validCount
        for (index in 0 until validCount) {
            val logicalIndex = oldestLogicalIndex + index.toLong()
            result[zeroCount + index] =
                ring[(logicalIndex and ringMask.toLong()).toInt()]
        }
        return result
    }

    /** Resets history, cursor, frame count, and the native Gaussian stream. */
    @Synchronized
    fun reset(seed: Int = NATIVE_RANDOM_SEED) {
        ring.fill(0f)
        sampleCursor = 0L
        framesProcessed = 0L
        gaussianNoise = S5MegpoidGaussianNoise.fromNativeSeed(seed)
    }

    private fun ceilDiv(value: Int, divisor: Int): Int {
        val quotient = value / divisor
        return if (value % divisor == 0) quotient else quotient + 1
    }

    private fun nextPowerOfTwo(value: Long): Long {
        require(value > 0L && value <= (1L shl 30))
        var result = 1L
        while (result < value) result = result shl 1
        return result
    }

    companion object {
        /** FUN_1800d5a00 / FUN_1800d8940 pass 0x3039 to the state constructor. */
        const val NATIVE_RANDOM_SEED = 12_345
    }
}
