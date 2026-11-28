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
 * All externally prepared inputs required for one native active carrier frame.
 *
 * The carrier generator producing history planes and the feature source feeding
 * the active Dense projection are independent upstream stages. This holder
 * makes both sources explicit instead of synthesizing either from the other.
 */
data class S5MegpoidCarrierFusionFrameInput(
    val workspace: S5MegpoidCarrierFusionWorkspace,
    val inputFeatureMajor: FloatArray,
    val carrierHistoryFirstPlane: FloatArray,
    val carrierHistorySecondPlane: FloatArray,
    val scale: Float,
    val amplitude: Float,
    val activeParameters: S5MegpoidCarrierFusionActiveParameters? = null
) {
    init {
        require(scale.isFinite() && amplitude.isFinite()) {
            "Carrier frame scale and amplitude must be finite."
        }
        require(inputFeatureMajor.size.toLong() == workspace.geometry.denseInputElementCount.toLong()) {
            "Carrier Dense input does not match the workspace [inputFeature][position] geometry."
        }
        require(inputFeatureMajor.all { it.isFinite() }) {
            "Carrier Dense input contains a non-finite value."
        }
        require(carrierHistoryFirstPlane.size == workspace.geometry.spectrumBins &&
            carrierHistorySecondPlane.size == workspace.geometry.spectrumBins) {
            "Carrier history planes must match the workspace's one-sided spectrum-bin count."
        }
        require(carrierHistoryFirstPlane.all { it.isFinite() } &&
            carrierHistorySecondPlane.all { it.isFinite() }) {
            "Carrier history frame contains a non-finite value."
        }
    }

    companion object {
        /**
         * Creates the active carrier-frame input from native-equivalent
         * upstream values:
         * - the final 32-channel sequence output feeds the active 2xK carrier Dense layer;
         * - the separately generated carrier spectrum is interleaved [real,imag]
         *   and is split into the two planar history buffers stored in D80/D86.
         *
         * This factory does not generate the carrier spectrum. The caller must
         * provide the output of the independently prepared excitation/carrier
         * generator corresponding to the same render frame.
         */
        fun fromSequenceOutputAndInterleavedCarrierSpectrum(
            workspace: S5MegpoidCarrierFusionWorkspace,
            sequenceOutput: S5MegpoidSequenceTransitionOutput,
            carrierSpectrumInterleaved: FloatArray,
            scale: Float,
            amplitude: Float,
            activeParameters: S5MegpoidCarrierFusionActiveParameters? = null
        ): S5MegpoidCarrierFusionFrameInput {
            val geometry = workspace.geometry
            require(sequenceOutput.channels == geometry.inputFeatures) {
                "Sequence output has ${sequenceOutput.channels} channels; carrier Dense expects ${geometry.inputFeatures}."
            }
            require(sequenceOutput.positions == geometry.carrierPositions) {
                "Sequence output has ${sequenceOutput.positions} positions; carrier Dense expects ${geometry.carrierPositions}."
            }
            require(sequenceOutput.features.size.toLong() ==
                geometry.denseInputElementCount.toLong()) {
                "Sequence output tensor does not match the carrier Dense [feature][position] input."
            }
            require(carrierSpectrumInterleaved.size.toLong() ==
                geometry.currentCarrierFrameElementCount.toLong()) {
                "Carrier spectrum must contain exactly two interleaved values per workspace spectrum bin."
            }
            require(carrierSpectrumInterleaved.all { it.isFinite() }) {
                "Carrier spectrum contains a non-finite value."
            }

            val first = FloatArray(geometry.spectrumBins)
            val second = FloatArray(geometry.spectrumBins)
            for (bin in 0 until geometry.spectrumBins) {
                first[bin] = carrierSpectrumInterleaved[bin * 2]
                second[bin] = carrierSpectrumInterleaved[bin * 2 + 1]
            }
            return S5MegpoidCarrierFusionFrameInput(
                workspace = workspace,
                inputFeatureMajor = sequenceOutput.features,
                carrierHistoryFirstPlane = first,
                carrierHistorySecondPlane = second,
                scale = scale,
                amplitude = amplitude,
                activeParameters = activeParameters
            )
        }
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
    val geometry: S5MegpoidCarrierFusionBufferGeometry
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

    /**
     * Active carrier branch: multiply the runtime-created [2][inputFeatures]
     * source matrix at root +0x15E by the current [inputFeatures][positions]
     * feature tensor. The native call is FUN_1800c3740(M=2,N=positions,K=inputFeatures).
     *
     * This path does not consume the optional VTB2 +0xD60 Dense descriptor.
     * It applies the two row-specific affine controls after GEMM:
     *
     *   adjustment[r] = -scale * amplitude * control166[r] + control162[r]
     *   output[r,n] = output[r,n] * scale + adjustment[r]
     */
    @Synchronized
    fun projectCarrierConditionedFeatures(
        inputFeatureMajor: FloatArray,
        sourceMatrixTwoByInput: FloatArray,
        scale: Float,
        amplitude: Float,
        control162: FloatArray,
        control166: FloatArray,
        gemm: S5MegpoidGemmKernel = NativeS5MegpoidGemmKernel
    ): FloatArray {
        require(inputFeatureMajor.size == geometry.denseInputElementCount) {
            "Carrier feature input must contain ${geometry.denseInputElementCount} feature-major values."
        }
        require(sourceMatrixTwoByInput.size == geometry.carrierSourceVectorElementCount) {
            "Carrier source matrix must contain exactly 2 * inputFeatures values."
        }
        require(control162.size == 2 && control166.size == 2) {
            "Carrier affine-control vectors must each contain two values."
        }
        require(scale.isFinite() && amplitude.isFinite() &&
            inputFeatureMajor.all { it.isFinite() } &&
            sourceMatrixTwoByInput.all { it.isFinite() } &&
            control162.all { it.isFinite() } && control166.all { it.isFinite() }) {
            "Carrier-conditioned projection contains a non-finite input."
        }

        inputFeatureMajor.copyInto(denseInputFeatureMajor)
        sourceMatrixTwoByInput.copyInto(carrierSourceVector)
        control162.copyInto(carrierAccumulationVectorA)
        control166.copyInto(carrierAccumulationVectorB)

        val projected = gemm.multiply(
            a = carrierSourceVector,
            b = denseInputFeatureMajor,
            c = denseOutputFeatureMajor,
            m = 2,
            n = geometry.carrierPositions,
            k = geometry.inputFeatures
        )
        require(projected.size == geometry.denseOutputElementCount) {
            "Carrier GEMM returned an unexpected number of output values."
        }

        val positions = geometry.carrierPositions
        for (row in 0 until 2) {
            val adjustment = -scale * amplitude * carrierAccumulationVectorB[row] +
                carrierAccumulationVectorA[row]
            val rowStart = row * positions
            for (position in 0 until positions) {
                val index = rowStart + position
                denseOutputFeatureMajor[index] =
                    denseOutputFeatureMajor[index] * scale + adjustment
            }
        }
        require(denseOutputFeatureMajor.all { it.isFinite() }) {
            "Carrier-conditioned projection produced a non-finite output."
        }
        return denseOutputFeatureMajor.copyOf()
    }

    /**
     * Model-backed active route. The source matrix and control vectors are
     * compiled from the exact VTB2 carrier Dense and normalization tensors.
     */
    @Synchronized
    fun projectCarrierConditionedFeatures(
        inputFeatureMajor: FloatArray,
        activeParameters: S5MegpoidCarrierFusionActiveParameters,
        scale: Float,
        amplitude: Float,
        gemm: S5MegpoidGemmKernel = NativeS5MegpoidGemmKernel
    ): FloatArray {
        require(activeParameters.inputFeatures == geometry.inputFeatures) {
            "Carrier active parameters require ${activeParameters.inputFeatures} input features, " +
                "but this workspace was created for ${geometry.inputFeatures}."
        }
        return projectCarrierConditionedFeatures(
            inputFeatureMajor = inputFeatureMajor,
            sourceMatrixTwoByInput = activeParameters.copySourceMatrixTwoByInput(),
            scale = scale,
            amplitude = amplitude,
            control162 = activeParameters.copyControl162(),
            control166 = activeParameters.copyControl166(),
            gemm = gemm
        )
    }

    /**
     * Completes one active carrier-history frame in native order:
     * 1) store the separately generated carrier frame in the D80 ring;
     * 2) run the model-backed [2][K] carrier projection and affine controls;
     * 3) read the native delayed slot and add its complex product to the
     *    caller's interleaved multipart accumulator.
     *
     * The carrier-history planes and Dense input tensor are separate upstream
     * inputs. Neither is derived from the D74 projection result here.
     */
    fun processActiveCarrierFrame(
        outputInterleaved: FloatArray,
        inputFeatureMajor: FloatArray,
        carrierHistoryFirstPlane: FloatArray,
        carrierHistorySecondPlane: FloatArray,
        activeParameters: S5MegpoidCarrierFusionActiveParameters,
        scale: Float,
        amplitude: Float,
        gemm: S5MegpoidGemmKernel = NativeS5MegpoidGemmKernel
    ): FloatArray = processActiveCarrierFrame(
        outputInterleaved = outputInterleaved,
        inputFeatureMajor = inputFeatureMajor,
        carrierHistoryFirstPlane = carrierHistoryFirstPlane,
        carrierHistorySecondPlane = carrierHistorySecondPlane,
        sourceMatrixTwoByInput = activeParameters.copySourceMatrixTwoByInput(),
        scale = scale,
        amplitude = amplitude,
        control162 = activeParameters.copyControl162(),
        control166 = activeParameters.copyControl166(),
        gemm = gemm
    )

    /**
     * One active frame using an explicitly supplied carrier-history frame.
     *
     * Native FUN_1800c7ad0 records the separate carrier frame in D80/D86
     * before it executes the D74 carrier projection and delayed complex
     * multiplication. The two input planes therefore must not be derived from
     * the D74 projection returned by this same call.
     */
    @Synchronized
    fun processActiveCarrierFrame(
        outputInterleaved: FloatArray,
        inputFeatureMajor: FloatArray,
        carrierHistoryFirstPlane: FloatArray,
        carrierHistorySecondPlane: FloatArray,
        sourceMatrixTwoByInput: FloatArray,
        scale: Float,
        amplitude: Float,
        control162: FloatArray,
        control166: FloatArray,
        gemm: S5MegpoidGemmKernel = NativeS5MegpoidGemmKernel
    ): FloatArray {
        require(geometry.carrierPositions == geometry.spectrumBins) {
            "Native active carrier fusion requires carrier positions to equal spectrum bins."
        }
        require(outputInterleaved.size == geometry.currentCarrierFrameElementCount) {
            "Multipart complex output must contain exactly two values per spectrum bin."
        }

        // This frame originates in the separate carrier generator (+0x2e4),
        // not from the D74 Dense projection computed below.
        appendCarrierFramePlanes(
            firstPlane = carrierHistoryFirstPlane,
            secondPlane = carrierHistorySecondPlane
        )

        projectCarrierConditionedFeatures(
            inputFeatureMajor = inputFeatureMajor,
            sourceMatrixTwoByInput = sourceMatrixTwoByInput,
            scale = scale,
            amplitude = amplitude,
            control162 = control162,
            control166 = control166,
            gemm = gemm
        )
        return accumulateDelayedCarrierFrameInto(outputInterleaved)
    }

    /**
     * Fallback-only route: apply the optional VTB2 +0xD60 32-to-2 Dense
     * descriptor to the selected fallback input. The native forward path
     * uses this when MRW_CARRIER_FUSE is disabled, root +0x16C is false, or
     * the dynamic +0x15E source matrix is absent.
     */
    @Synchronized
    fun projectFallbackCarrierFeatures(
        inputFeatureMajor: FloatArray,
        fallbackProjection: S5MegpoidDenseProjection,
        gemm: S5MegpoidGemmKernel = NativeS5MegpoidGemmKernel
    ): FloatArray {
        require(fallbackProjection.inputFeatures == geometry.inputFeatures &&
            fallbackProjection.outputFeatures == 2) {
            "Fallback carrier Dense projection must match the native 32-to-2 descriptor."
        }
        require(inputFeatureMajor.size == geometry.denseInputElementCount) {
            "Carrier fallback input must contain ${geometry.denseInputElementCount} feature-major values."
        }
        require(inputFeatureMajor.all { it.isFinite() }) {
            "Carrier fallback input contains a non-finite value."
        }
        inputFeatureMajor.copyInto(denseInputFeatureMajor)
        val projected = fallbackProjection.project(
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
