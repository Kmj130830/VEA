package com.kmj.vea.engine

/**
 * The shared complex feature pair consumed by the native four-part output
 * modulation loop in FUN_1800c7ad0.
 *
 * The active native path writes two contiguous planes to +0x136:
 *   [first/real: positionCount values][second/imaginary: positionCount values]
 *
 * The outer loop iterates D00 parts, but it reuses this same pair for every
 * part. Per-part locations, rotation and gain are stored in separate
 * part-major tables and applied by S5MegpoidMultipartOutputModulation.
 */
class S5MegpoidMultipartComplexFeaturePair internal constructor(
    val positionCount: Int,
    firstFeaturePlane: FloatArray,
    secondFeaturePlane: FloatArray
) {
    private val first = firstFeaturePlane.copyOf()
    private val second = secondFeaturePlane.copyOf()

    init {
        require(positionCount > 0)
        require(first.size == positionCount && second.size == positionCount) {
            "The active multipart feature pair must contain exactly $positionCount values per plane."
        }
        require(first.all { it.isFinite() } && second.all { it.isFinite() }) {
            "The active multipart feature pair contains a non-finite value."
        }
    }

    fun firstFeaturePlane(): FloatArray = first.copyOf()

    fun secondFeaturePlane(): FloatArray = second.copyOf()

    /**
     * Applies the same complex pair to each part in native part-index order.
     */
    fun accumulateInto(
        outputInterleaved: FloatArray,
        modulation: S5MegpoidMultipartOutputModulation
    ) {
        require(modulation.positionCount == positionCount) {
            "Multipart modulation has ${modulation.positionCount} positions; feature pair has $positionCount."
        }
        modulation.accumulateAllPartsInto(
            outputInterleaved = outputInterleaved,
            firstFeaturePlane = first,
            secondFeaturePlane = second
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
 * Extracts the two shared real/imaginary planes from the active output tensor.
 *
 * This is intentionally not a 2*D00 tensor split: the native render code
 * offsets the second plane by the output width and reuses the same pair for
 * every one of the D00 modulation parts.
 */
object S5MegpoidMultipartFeaturePlaneExtractor {
    fun extract(
        outputFeatureMajor: FloatArray,
        positionCount: Int
    ): S5MegpoidMultipartComplexFeaturePair {
        require(positionCount > 0)
        val expectedValues = checkedProduct(2, positionCount)
        require(outputFeatureMajor.size == expectedValues) {
            "Active multipart feature tensor must contain exactly two planes * $positionCount positions."
        }
        require(outputFeatureMajor.all { it.isFinite() }) {
            "Active multipart feature tensor contains a non-finite value."
        }

        return S5MegpoidMultipartComplexFeaturePair(
            positionCount = positionCount,
            firstFeaturePlane = outputFeatureMajor.copyOfRange(0, positionCount),
            secondFeaturePlane = outputFeatureMajor.copyOfRange(positionCount, expectedValues)
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
