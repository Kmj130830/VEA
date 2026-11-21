package com.kmj.vea.engine

import kotlin.math.abs
import kotlin.math.cos

/**
 * Result of the two-window construction in S5API FUN_180127dc0.
 * frameWindow is returned separately because the +0x91 branch modifies it
 * while building the complementary output window.
 */
data class S5MegpoidOverlapAddWindowPair(
    val frameWindow: FloatArray,
    val outputWindow: FloatArray
)

/**
 * Ports the three output-window construction branches in FUN_180127dc0.
 *
 * flagAt92 == true:
 *   replace the frame window with all ones, accumulate that rectangular
 *   window and invert the overlap sum.
 *
 * flagAt92 == false and flagAt91 == false:
 *   accumulate the square of the current frame window and invert the sum.
 *
 * flagAt92 == false and flagAt91 == true:
 *   construct a centered raised-cosine synthesis window, divide the frame
 *   window by max(originalFrameWindow, 1e-4), accumulate the raised-cosine
 *   window, and invert that overlap sum.
 *
 * The Megpoid V7 caller supplies geometry and fftSize from the same source
 * scalar. This reference requires them to match because the native frame and
 * overlap-ring buffers are both fftSize samples long in the observed path.
 */
object S5MegpoidOutputWindowBuilder {
    fun build(
        frameWindow: FloatArray,
        outputSamples: Int,
        geometry: Int,
        fftSize: Int,
        windowOffset: Int,
        overlapContributionCount: Int,
        flagAt91: Boolean,
        flagAt92: Boolean
    ): S5MegpoidOverlapAddWindowPair {
        require(outputSamples > 0)
        require(geometry > 0 && fftSize > 0)
        require(geometry == fftSize) {
            "The current Megpoid output-window port requires geometry == fftSize."
        }
        require(frameWindow.size == geometry)
        require(frameWindow.all { it.isFinite() })
        require(windowOffset >= 0)
        require(overlapContributionCount > 0)

        val resultFrameWindow = frameWindow.copyOf()
        val sourceForOverlap = when {
            flagAt92 -> {
                resultFrameWindow.fill(1f)
                resultFrameWindow.copyOf()
            }
            flagAt91 -> {
                val raisedCosine = buildRaisedCosine(
                    geometry = geometry,
                    outputSamples = outputSamples
                )
                for (index in resultFrameWindow.indices) {
                    val denominator = maxOf(resultFrameWindow[index], FRAME_WINDOW_EPSILON)
                    resultFrameWindow[index] = raisedCosine[index] / denominator
                }
                raisedCosine
            }
            else -> FloatArray(geometry) { index ->
                val value = resultFrameWindow[index]
                value * value
            }
        }

        val accumulated = accumulateOverlapWindow(
            sourceWindow = sourceForOverlap,
            outputSamples = outputSamples,
            geometry = geometry,
            fftSize = fftSize,
            windowOffset = windowOffset,
            overlapContributionCount = overlapContributionCount
        )
        val outputWindow = FloatArray(outputSamples) { index ->
            val denominator = maxOf(accumulated[index], OUTPUT_WINDOW_EPSILON)
            1f / denominator
        }
        return S5MegpoidOverlapAddWindowPair(resultFrameWindow, outputWindow)
    }

    private fun buildRaisedCosine(
        geometry: Int,
        outputSamples: Int
    ): FloatArray = FloatArray(geometry) { index ->
        var phase = (index.toFloat() - geometry.toFloat() * 0.5f) / outputSamples.toFloat()
        if (abs(phase) >= 1f) {
            0f
        } else {
            phase *= PI_F
            val cosine = cos(phase.toDouble()).toFloat()
            (cosine + 1f) * 0.5f
        }
    }

    /**
     * Mirrors the source and destination interval calculation in the native
     * overlap constructor. The same index expressions are used by
     * FUN_18012c740 when it accumulates real audio frames.
     */
    private fun accumulateOverlapWindow(
        sourceWindow: FloatArray,
        outputSamples: Int,
        geometry: Int,
        fftSize: Int,
        windowOffset: Int,
        overlapContributionCount: Int
    ): FloatArray {
        require(sourceWindow.size == geometry)
        val output = FloatArray(outputSamples)
        val fftHalf = fftSize / 2
        val geometryHalf = geometry / 2

        for (contributionIndex in 0 until overlapContributionCount) {
            val relativeOffset = windowOffset.toLong() - contributionIndex.toLong()
            val startProduct = checkedProduct(-relativeOffset, outputSamples.toLong())
            val endProduct = checkedProduct(1L - relativeOffset, outputSamples.toLong())

            val startCandidate = minOf(geometryHalf.toLong(), startProduct)
            val endCandidate = minOf(geometryHalf.toLong(), endProduct)
            val sourceStart = maxOf(0L, fftHalf.toLong() + startCandidate).toInt()
            val sourceEnd = maxOf(0L, fftHalf.toLong() + endCandidate).toInt()

            require(sourceStart <= geometry && sourceEnd <= geometry) {
                "Output-window overlap geometry addresses samples outside the frame window."
            }
            if (sourceEnd <= sourceStart) continue

            val outputStart = if (sourceEnd <= outputSamples) {
                0
            } else {
                maxOf(0, outputSamples - sourceEnd)
            }
            val copyCount = sourceEnd - sourceStart
            require(outputStart.toLong() + copyCount.toLong() <= outputSamples.toLong()) {
                "Output-window overlap geometry addresses samples outside the output vector."
            }

            for (sample in 0 until copyCount) {
                output[outputStart + sample] += sourceWindow[sourceStart + sample]
            }
        }
        return output
    }

    private fun checkedProduct(a: Long, b: Long): Long {
        val product = a * b
        require(a == 0L || product / a == b) {
            "Output-window overlap geometry arithmetic overflowed."
        }
        return product
    }

    private const val FRAME_WINDOW_EPSILON = 1.0e-3f
    private const val OUTPUT_WINDOW_EPSILON = 1.0e-8f
    private const val PI_F = 3.1415927f
}
