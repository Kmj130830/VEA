package com.kmj.vea.engine

/**
 * Four-part post-residual output-head bridge recovered from FUN_1800c7ad0.
 *
 * The native render path first produces an outputFeature-major [8, positions]
 * tensor, then reads it as four consecutive (real, imaginary) plane pairs:
 *
 *   part 0: [channel 0][channel 1]
 *   part 1: [channel 2][channel 3]
 *   part 2: [channel 4][channel 5]
 *   part 3: [channel 6][channel 7]
 *
 * Within each channel, positions are contiguous. This is not an arbitrary
 * channel interpretation: it follows the native pointer increments by
 * 2 * positionCount for each part and by positionCount between the two planes.
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
        require(firstPlanes.size == partCount && secondPlanes.size == partCount)
        require(firstPlanes.all { plane -> plane.size == positionCount && plane.all { it.isFinite() } })
        require(secondPlanes.all { plane -> plane.size == positionCount && plane.all { it.isFinite() } })
    }

    fun firstFeaturePlane(partIndex: Int): FloatArray {
        require(partIndex in 0 until partCount)
        return firstPlanes[partIndex].copyOf()
    }

    fun secondFeaturePlane(partIndex: Int): FloatArray {
        require(partIndex in 0 until partCount)
        return secondPlanes[partIndex].copyOf()
    }

    /**
     * Adds the four part contributions to an interleaved complex accumulator
     * using the modulation table generated from the same frame's F0/theta.
     */
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
 * Splits the post-residual output-head tensor into the exact four real/imaginary
 * plane pairs consumed by the native multipart modulation loop.
 *
 * Input is row-major [feature][position], i.e. feature * positionCount + position.
 * The native D00=4, D01=true Megpoid configuration requires exactly 8 feature rows.
 */
object S5MegpoidMultipartFeaturePlaneExtractor {
    fun extract(
        outputFeatureMajor: FloatArray,
        partCount: Int,
        positionCount: Int
    ): S5MegpoidMultipartFeaturePlanes {
        require(partCount >= 2) {
            "The native multipart output head requires at least two parts."
        }
        require(positionCount > 0)
        val featureCount = checkedProduct(partCount, 2)
        val expectedValues = checkedProduct(featureCount, positionCount)
        require(outputFeatureMajor.size == expectedValues) {
            "Multipart output tensor must contain exactly $featureCount * $positionCount values."
        }
        require(outputFeatureMajor.all { it.isFinite() }) {
            "Multipart output tensor contains a non-finite value."
        }

        val first = ArrayList<FloatArray>(partCount)
        val second = ArrayList<FloatArray>(partCount)
        for (part in 0 until partCount) {
            val firstChannelOffset = checkedProduct(part * 2, positionCount)
            val secondChannelOffset = checkedProduct(part * 2 + 1, positionCount)
            first += outputFeatureMajor.copyOfRange(
                firstChannelOffset,
                firstChannelOffset + positionCount
            )
            second += outputFeatureMajor.copyOfRange(
                secondChannelOffset,
                secondChannelOffset + positionCount
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

