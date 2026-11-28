package com.kmj.vea.engine

/**
 * Phone-event feature rows gathered into render-frame order.
 *
 * Rows are [frame][feature]. This is an intermediate input representation;
 * it is not the output of FUN_180076080/FUN_180077520 and is not an acoustic
 * spectrum.
 */
data class S5ApiFrameAlignedPhoneFeatureMatrix(
    val frameCount: Int,
    val featureWidth: Int,
    val values: FloatArray
) {
    init {
        require(frameCount >= 0 && featureWidth > 0) {
            "Frame matrix dimensions must have a non-negative frame count and positive feature width."
        }
        require(values.size.toLong() == frameCount.toLong() * featureWidth.toLong()) {
            "Frame matrix storage does not match its declared dimensions."
        }
        require(values.all { it.isFinite() }) {
            "Frame matrix contains a non-finite value."
        }
    }

    /** Returns an owned copy of one [feature] row. */
    fun frame(index: Int): FloatArray {
        require(index in 0 until frameCount) {
            "Frame index is outside the aligned matrix."
        }
        val start = index * featureWidth
        return values.copyOfRange(start, start + featureWidth)
    }

    /** Transposes [frame][feature] rows into [feature][frame] order. */
    fun toFeatureMajor(): FloatArray {
        val transposed = FloatArray(values.size)
        for (frame in 0 until frameCount) {
            val rowStart = frame * featureWidth
            for (feature in 0 until featureWidth) {
                transposed[feature * frameCount + frame] = values[rowStart + feature]
            }
        }
        return transposed
    }
}

/**
 * Gathers event-level FUN_18006c720 rows according to the frame ownership
 * calculated by S5ApiTimedPhoneFrameAligner.
 *
 * This operation uses only the recovered event-index map. Relative frame
 * timing and event-control values remain in S5ApiTimedPhoneFrameAlignment
 * and are intentionally not appended to the feature rows here.
 */
object S5ApiFrameAlignedPhoneFeatureMatrixBuilder {
    fun build(
        eventFeatures: S5ApiDefaultPhoneFeatureMatrix,
        frameAlignment: S5ApiTimedPhoneFrameAlignment
    ): S5ApiFrameAlignedPhoneFeatureMatrix {
        require(frameAlignment.frameCount >= 0)
        require(frameAlignment.eventIndexByFrame.size == frameAlignment.frameCount) {
            "Frame ownership array does not match the declared frame count."
        }
        require(eventFeatures.rowWidth > 0) {
            "Event feature width must be positive."
        }

        val frameCount = frameAlignment.frameCount
        val featureWidth = eventFeatures.rowWidth
        val totalValues = frameCount.toLong() * featureWidth.toLong()
        require(totalValues <= Int.MAX_VALUE.toLong()) {
            "Frame-aligned phone matrix exceeds the supported FloatArray size."
        }

        val eventIndices = frameAlignment.copyEventIndices()
        require(eventIndices.size == frameCount) {
            "Frame ownership array changed size while being copied."
        }
        if (frameCount > 0) {
            require(eventFeatures.rowCount > 0) {
                "Non-empty frame alignment requires at least one event feature row."
            }
        }

        val output = FloatArray(totalValues.toInt())
        for (frame in 0 until frameCount) {
            val eventIndex = eventIndices[frame]
            require(eventIndex in 0 until eventFeatures.rowCount) {
                "Frame " + frame + " selects event row " + eventIndex +
                    ", outside the event feature matrix."
            }
            val sourceStart = (eventIndex.toLong() * featureWidth.toLong()).toInt()
            val destinationStart = frame * featureWidth
            eventFeatures.values.copyInto(
                destination = output,
                destinationOffset = destinationStart,
                startIndex = sourceStart,
                endIndex = sourceStart + featureWidth
            )
        }

        return S5ApiFrameAlignedPhoneFeatureMatrix(
            frameCount = frameCount,
            featureWidth = featureWidth,
            values = output
        )
    }
}
