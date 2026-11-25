package com.kmj.vea.engine

/**
 * Buffer geometry recovered from the carrier branch in FUN_1800ced10 and
 * FUN_1800c7ad0. Root fields are kept explicit so the Android adapter does
 * not silently invent a history size or render-position count.
 *
 * Native correspondence:
 * - root +0x08 -> inputFeatures (K)
 * - root +0x18 -> carrier sequence positions (N)
 * - root word +2 -> one-sided complex spectrum bins
 * - root words +0x181 and +0x182 -> the two history-count contributions
 * - root +0xD6C -> [inputFeature][position] carrier Dense input
 * - root +0xD74 -> [outputFeature=2][position] carrier Dense output
 * - root +0xD80 -> [historySlot][firstPlane[bins], secondPlane[bins]] ring
 * - root +0xD86 -> current two-plane carrier frame
 * - root +0x15E -> two input-feature vectors
 * - root +0x162 / +0x166 -> two-element accumulation vectors
 */
data class S5MegpoidCarrierFusionBufferGeometry(
    val inputFeatures: Int,
    val carrierPositions: Int,
    val spectrumBins: Int,
    val historySlotsFromRoot181: Int,
    val historySlotsFromRoot182: Int
) {
    init {
        require(inputFeatures > 0)
        require(carrierPositions > 0)
        require(spectrumBins > 0)
        require(historySlotsFromRoot181 >= 0 && historySlotsFromRoot182 >= 0)
        require(historySlotCount > 0)
        // Force all derived element counts through the overflow guards.
        denseInputElementCount
        denseOutputElementCount
        carrierSourceVectorElementCount
        carrierRingElementCount
        currentCarrierFrameElementCount
    }

    /** Native root[0x181] + root[0x182] + 1. */
    val historySlotCount: Int
        get() = checkedCount(
            historySlotsFromRoot181.toLong() +
                historySlotsFromRoot182.toLong() + 1L,
            "Carrier history slot count"
        )

    /** D6C: inputFeatures * carrierPositions. */
    val denseInputElementCount: Int
        get() = checkedProduct(inputFeatures, carrierPositions, "Carrier Dense input")

    /** D74: two output features for each carrier position. */
    val denseOutputElementCount: Int
        get() = checkedProduct(2, carrierPositions, "Carrier Dense output")

    /** Root +0x15E: two input-feature vectors. */
    val carrierSourceVectorElementCount: Int
        get() = checkedProduct(2, inputFeatures, "Carrier source vector")

    /** D80/D86 slot stride: two planar float arrays, each containing spectrumBins values. */
    val currentCarrierFrameElementCount: Int
        get() = checkedProduct(2, spectrumBins, "Carrier planar frame")

    val carrierHistoryFrameStride: Int
        get() = currentCarrierFrameElementCount

    /** D80: historySlotCount * carrierHistoryFrameStride. */
    val carrierRingElementCount: Int
        get() = checkedProduct(
            historySlotCount,
            carrierHistoryFrameStride,
            "Carrier history ring"
        )

    /** Root +0x162 and +0x166 are each allocated as two-float vectors. */
    val carrierAccumulationVectorElementCount: Int get() = 2

    private fun checkedProduct(a: Int, b: Int, label: String): Int {
        val product = a.toLong() * b.toLong()
        require(a > 0 && b > 0 && product <= Int.MAX_VALUE.toLong()) {
            "$label size is invalid or exceeds the Kotlin array limit."
        }
        return product.toInt()
    }

    private fun checkedCount(value: Long, label: String): Int {
        require(value in 1..Int.MAX_VALUE.toLong()) {
            "$label is invalid or exceeds the Kotlin array limit."
        }
        return value.toInt()
    }
}

/**
 * State for the carrier branch's separate Dense input/output buffers and
 * two-plane carrier-frame history ring.
 *
 * This workspace accepts a fully prepared feature-major carrier input. The
 * fallback branch may reach it after root +0xC4 normalization; the active
 * carrier branch builds a conditioned feature tensor from the source/control
 * vectors before Dense. That conditioning step is not synthesized here.
 */
class S5MegpoidCarrierFusionWorkspace(
    val geometry: S5MegpoidCarrierFusionBufferGeometry,
    private val carrierDenseProjection: S5MegpoidDenseProjection
) {
    val denseInputFeatureMajor = FloatArray(geometry.denseInputElementCount)
    val denseOutputFeatureMajor = FloatArray(geometry.denseOutputElementCount)
    val carrierSourceVector = FloatArray(geometry.carrierSourceVectorElementCount)
    val carrierAccumulationVectorA =
        FloatArray(geometry.carrierAccumulationVectorElementCount)
    val carrierAccumulationVectorB =
        FloatArray(geometry.carrierAccumulationVectorElementCount)
    val currentCarrierFrame = FloatArray(geometry.currentCarrierFrameElementCount)
    private val historyRing = FloatArray(geometry.carrierRingElementCount)

    /** Next slot to be overwritten, matching native root word +0xD7E. */
    var nextHistoryWriteIndex: Int = 0
        private set

    /** Diagnostic count; the native implementation instead relies on zero-filled ring slots. */
    var historyFramesWritten: Long = 0L
        private set

    init {
        require(carrierDenseProjection.inputFeatures == geometry.inputFeatures) {
            "Carrier Dense input width does not match the native workspace geometry."
        }
        require(carrierDenseProjection.outputFeatures == 2) {
            "Carrier Dense projection must produce exactly two output features."
        }
    }

    /**
     * Runs the recovered 32-input/2-output Dense projection over an already
     * normalized feature-major [inputFeature][position] carrier tensor.
     * Returns a defensive copy of the [2][position] result.
     */
    @Synchronized
    fun projectPreparedCarrierFeatures(
        inputFeatureMajor: FloatArray,
        gemm: S5MegpoidGemmKernel = NativeS5MegpoidGemmKernel
    ): FloatArray {
        require(inputFeatureMajor.size == geometry.denseInputElementCount) {
            "Carrier input must contain ${geometry.denseInputElementCount} feature-major values."
        }
        require(inputFeatureMajor.all { it.isFinite() }) {
            "Carrier Dense input contains a non-finite value."
        }
        inputFeatureMajor.copyInto(denseInputFeatureMajor)
        val projected = carrierDenseProjection.project(
            input = denseInputFeatureMajor,
            positions = geometry.carrierPositions,
            gemm = gemm
        )
        require(projected.size == denseOutputFeatureMajor.size)
        projected.copyInto(denseOutputFeatureMajor)
        return denseOutputFeatureMajor.copyOf()
    }

    /**
     * Writes the two planar carrier arrays to D86 and the D80 ring:
     * [firstPlane[0..bins), secondPlane[0..bins)]. This ordering is explicit
     * in the native copy loop at FUN_1800c7ad0; it is not interleaved by bin.
     *
     * @return the ring slot that received this frame.
     */
    @Synchronized
    fun appendCarrierFramePlanes(firstPlane: FloatArray, secondPlane: FloatArray): Int {
        val bins = geometry.spectrumBins
        require(firstPlane.size == bins && secondPlane.size == bins) {
            "Each carrier frame plane must contain exactly $bins values."
        }
        require(firstPlane.all { it.isFinite() } && secondPlane.all { it.isFinite() }) {
            "Carrier frame planes contain a non-finite value."
        }

        firstPlane.copyInto(currentCarrierFrame, destinationOffset = 0)
        secondPlane.copyInto(currentCarrierFrame, destinationOffset = bins)
        val writtenSlot = nextHistoryWriteIndex
        val offset = writtenSlot * geometry.carrierHistoryFrameStride
        currentCarrierFrame.copyInto(
            destination = historyRing,
            destinationOffset = offset
        )
        nextHistoryWriteIndex = (writtenSlot + 1) % geometry.historySlotCount
        historyFramesWritten = (historyFramesWritten + 1L)
            .coerceAtMost(geometry.historySlotCount.toLong())
        return writtenSlot
    }

    /**
     * Reproduces the final carrier-history complex multiply from FUN_1800c7ad0.
     * The native slot expression selects the oldest ring slot at the current
     * write cursor: writeIndex - root[0x181] - root[0x182] - 1 == writeIndex-H.
     * D74 is two planar rows; the output buffer is interleaved complex bins.
     */
    @Synchronized
    fun accumulateDelayedCarrierFrameInto(outputInterleaved: FloatArray): FloatArray {
        val bins = geometry.spectrumBins
        require(geometry.carrierPositions == bins) {
            "Native carrier fusion requires the D74 position count to match the spectrum-bin count."
        }
        require(outputInterleaved.size == geometry.currentCarrierFrameElementCount) {
            "Carrier fusion output must contain exactly ${geometry.currentCarrierFrameElementCount} floats."
        }
        require(outputInterleaved.all { it.isFinite() } && denseOutputFeatureMajor.all { it.isFinite() }) {
            "Carrier fusion contains a non-finite value."
        }

        val oldestSlot = nextHistoryWriteIndex
        val frameOffset = oldestSlot * geometry.carrierHistoryFrameStride
        for (bin in 0 until bins) {
            val carrierFirst = historyRing[frameOffset + bin]
            val carrierSecond = historyRing[frameOffset + bins + bin]
            val denseFirst = denseOutputFeatureMajor[bin]
            val denseSecond = denseOutputFeatureMajor[bins + bin]
            val outputOffset = bin * 2
            outputInterleaved[outputOffset] += denseSecond * carrierFirst - denseFirst * carrierSecond
            outputInterleaved[outputOffset + 1] += denseFirst * carrierFirst + denseSecond * carrierSecond
        }
        require(outputInterleaved.all { it.isFinite() }) {
            "Carrier history accumulation produced a non-finite output."
        }
        return outputInterleaved
    }

    /**
     * Copies the complete planar ring in oldest-to-newest slot order, matching
     * the native selected delayed slot at the next write cursor. Unwritten slots remain zero-filled.
     */
    @Synchronized
    fun copyHistoryOldestToNewest(): FloatArray {
        val result = FloatArray(historyRing.size)
        val stride = geometry.carrierHistoryFrameStride
        for (age in 0 until geometry.historySlotCount) {
            val slot = (nextHistoryWriteIndex + age) % geometry.historySlotCount
            historyRing.copyInto(
                destination = result,
                destinationOffset = age * stride,
                startIndex = slot * stride,
                endIndex = slot * stride + stride
            )
        }
        return result
    }

    /** Returns the most recent planar frame [firstPlane, secondPlane], or zeros before the first write. */
    @Synchronized
    fun copyMostRecentCarrierFrame(): FloatArray {
        if (historyFramesWritten == 0L) return FloatArray(geometry.currentCarrierFrameElementCount)
        val slot = if (nextHistoryWriteIndex == 0) {
            geometry.historySlotCount - 1
        } else {
            nextHistoryWriteIndex - 1
        }
        val start = slot * geometry.carrierHistoryFrameStride
        return historyRing.copyOfRange(
            start,
            start + geometry.carrierHistoryFrameStride
        )
    }

    /** Resets the utterance-local carrier history and its write cursor. */
    @Synchronized
    fun resetHistory() {
        historyRing.fill(0f)
        currentCarrierFrame.fill(0f)
        nextHistoryWriteIndex = 0
        historyFramesWritten = 0L
    }
}
