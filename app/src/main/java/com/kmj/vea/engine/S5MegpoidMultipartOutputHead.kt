package com.kmj.vea.engine

/**
 * The four complex feature pairs consumed by the native D00-part output loop
 * in FUN_1800c7ad0.
 *
 * The active output buffer is feature-major:
 *   [part0.real][part0.imag][part1.real][part1.imag] ... [part3.real][part3.imag]
 * Each plane contains positionCount consecutive float32 values. Native
 * advances the first-plane and second-plane pointers by 2*positionCount for
 * each outer-loop part, while modulation tables advance by positionCount.
 */
class S5MegpoidMultipartFeaturePlanes internal constructor(
    val partCount: Int,
    val positionCount: Int,
    firstFeaturePlaneByPart: List<FloatArray>,
    secondFeaturePlaneByPart: List<FloatArray>
) {
    private val firstPlanes = firstFeaturePlaneByPart.map { it.copyOf() }
    private val secondPlanes = secondFeaturePlaneByPart.map { it.copyOf() }

    init {
        require(partCount >= 2 && positionCount > 0)
        require(firstPlanes.size == partCount && secondPlanes.size == partCount) {
            "Multipart output must contain exactly $partCount real/imaginary feature pairs."
        }
        require(firstPlanes.all { plane ->
            plane.size == positionCount && plane.all { it.isFinite() }
        })
        require(secondPlanes.all { plane ->
            plane.size == positionCount && plane.all { it.isFinite() }
        })
    }

    fun firstFeaturePlane(partIndex: Int): FloatArray {
        require(partIndex in 0 until partCount)
        return firstPlanes[partIndex].copyOf()
    }

    fun secondFeaturePlane(partIndex: Int): FloatArray {
        require(partIndex in 0 until partCount)
        return secondPlanes[partIndex].copyOf()
    }

    fun accumulateInto(
        outputInterleaved: FloatArray,
        modulation: S5MegpoidMultipartOutputModulation
    ) {
        require(modulation.partCount == partCount) {
            "Multipart modulation has ${modulation.partCount} parts; feature tensor has $partCount."
        }
        require(modulation.positionCount == positionCount) {
            "Multipart modulation has ${modulation.positionCount} positions; feature tensor has $positionCount."
        }
        modulation.accumulateAllPartsInto(
            outputInterleaved = outputInterleaved,
            firstFeaturePlaneByPart = firstPlanes,
            secondFeaturePlaneByPart = secondPlanes
        )
    }

    fun newInterleavedComplexAccumulator(): FloatArray =
        FloatArray(checkedProduct(positionCount, 2))

    private fun checkedProduct(a: Int, b: Int): Int {
        val product = a.toLong() * b.toLong()
        require(a > 0 && b > 0 && product <= Int.MAX_VALUE) {
            "Multipart complex accumulator exceeds the supported FloatArray size."
        }
        return product.toInt()
    }
}

/**
 * Splits the active post-residual feature-major tensor into the exact
 * part-specific real/imaginary plane pairs read by FUN_1800c7ad0.
 *
 * For AI Megpoid V7 D00=4, the input contains eight rows:
 *   [real0][imag0][real1][imag1][real2][imag2][real3][imag3].
 * This mapping is derived from the native per-part pointer stride of
 * 2*positionCount values, not from a guessed channel interpretation.
 */
object S5MegpoidMultipartFeaturePlaneExtractor {
    fun extract(
        outputFeatureMajor: FloatArray,
        partCount: Int,
        positionCount: Int
    ): S5MegpoidMultipartFeaturePlanes {
        require(partCount >= 2 && positionCount > 0)
        val featurePlaneCount = checkedProduct(partCount, 2)
        val expectedValues = checkedProduct(featurePlaneCount, positionCount)
        require(outputFeatureMajor.size == expectedValues) {
            "Active multipart feature tensor must contain $featurePlaneCount planes * $positionCount positions."
        }
        require(outputFeatureMajor.all { it.isFinite() }) {
            "Active multipart feature tensor contains a non-finite value."
        }

        val first = ArrayList<FloatArray>(partCount)
        val second = ArrayList<FloatArray>(partCount)
        for (part in 0 until partCount) {
            // expectedValues was checked above. Offset zero is valid for the
            // first real plane, so calculate offsets without the positive-size
            // helper used for allocations.
            val firstOffset = (part.toLong() * 2L * positionCount.toLong()).toInt()
            val secondOffset = (part.toLong() * 2L + 1L).times(positionCount.toLong()).toInt()
            first += outputFeatureMajor.copyOfRange(
                firstOffset,
                firstOffset + positionCount
            )
            second += outputFeatureMajor.copyOfRange(
                secondOffset,
                secondOffset + positionCount
            )
        }
        return S5MegpoidMultipartFeaturePlanes(
            partCount = partCount,
            positionCount = positionCount,
            firstFeaturePlaneByPart = first,
            secondFeaturePlaneByPart = second
        )
    }

    private fun checkedProduct(a: Int, b: Int): Int {
        val product = a.toLong() * b.toLong()
        require(a > 0 && b > 0 && product <= Int.MAX_VALUE) {
            "Multipart output tensor size is invalid or exceeds the Kotlin array limit."
        }
        return product.toInt()
    }
}
