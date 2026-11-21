package com.kmj.vea.engine

/** Row-major float32 phone feature matrix from the FUN_18006c720 mode-0 path. */
data class S5ApiDefaultPhoneFeatureMatrix(
    val rowCount: Int,
    val rowWidth: Int,
    val values: FloatArray
) {
    init {
        require(rowCount >= 0 && rowWidth > 0)
        require(values.size.toLong() == rowCount.toLong() * rowWidth.toLong())
    }

    fun row(index: Int): FloatArray {
        require(index in 0 until rowCount)
        val start = index * rowWidth
        return values.copyOfRange(start, start + rowWidth)
    }

    /** Transposes [position][feature] rows into the Dense stage's [feature][position] layout. */
    fun toFeatureMajor(): FloatArray {
        val transposed = FloatArray(values.size)
        for (position in 0 until rowCount) {
            val rowStart = position * rowWidth
            for (feature in 0 until rowWidth) {
                transposed[feature * rowCount + position] = values[rowStart + feature]
            }
        }
        return transposed
    }
}

/**
 * Builds the default phone feature rows from already aligned events.
 * Order: duration, optional prefix flag pair, centre category, preceding
 * categories (oldest to nearest), following categories (nearest to farthest).
 *
 * Descriptor +0x20 controls the preceding window; +0x1C controls following.
 * Neighbour indexes clamp to the first/last event. Other layout modes are not
 * silently treated as this default representation.
 */
object S5ApiDefaultPhoneFeatureMatrixBuilder {
    fun build(
        features: List<VsmAlignedPhoneFeature>,
        layout: S5ApiDefaultPhoneFeatureLayout
    ): S5ApiDefaultPhoneFeatureMatrix {
        require(layout.layoutMode == 0) {
            "Only S5API default phone-feature layout mode 0 is implemented."
        }
        require(layout.categoryWidth >= S5ApiPhoneCategoryMap.CATEGORY_COUNT) {
            "Category width must fit all " +
                S5ApiPhoneCategoryMap.CATEGORY_COUNT + " recovered categories."
        }

        if (features.isEmpty()) {
            return S5ApiDefaultPhoneFeatureMatrix(0, layout.rowWidth, FloatArray(0))
        }

        for (index in features.indices) {
            val feature = features[index]
            require(feature.categoryIndex in 0 until S5ApiPhoneCategoryMap.CATEGORY_COUNT) {
                "Phone feature at index $index has an invalid category index."
            }
            require(feature.normalizedDuration.isFinite()) {
                "Phone feature at index $index has a non-finite duration scalar."
            }
            if (index > 0) {
                require(features[index - 1].event.startSeconds <= feature.event.startSeconds) {
                    "Phone events must be ordered by start time before feature-row creation."
                }
            }
        }

        val rowWidth = layout.rowWidth
        val totalValues = features.size.toLong() * rowWidth.toLong()
        require(totalValues <= Int.MAX_VALUE.toLong()) {
            "Phone feature matrix is too large for a single Android FloatArray."
        }

        val matrix = FloatArray(totalValues.toInt())
        val lastIndex = features.lastIndex
        val leftCount = layout.contextCountAt20
        val rightCount = layout.contextCountAt1C
        val flagStart = layout.flagFeatureStartIndex

        for (centreIndex in features.indices) {
            val rowStart = centreIndex * rowWidth
            val centre = features[centreIndex]
            matrix[rowStart + layout.durationFeatureIndex] = centre.normalizedDuration

            if (flagStart != null) {
                // FUN_180074a50 sets the output flag when the original label starts with '!'.
                // FUN_18006c720 emits [1,0] for marked labels and [0,1] otherwise.
                matrix[rowStart + flagStart] = if (centre.hasLeadingBang) 1.0f else 0.0f
                matrix[rowStart + flagStart + 1] = if (centre.hasLeadingBang) 0.0f else 1.0f
            }

            writeCategory(matrix, rowStart, layout, 0, centre)

            // param_10 / descriptor +0x20: preceding events, oldest to nearest.
            for (contextOffset in 0 until leftCount) {
                val neighbourIndex = (centreIndex - leftCount + contextOffset)
                    .coerceIn(0, lastIndex)
                writeCategory(
                    matrix, rowStart, layout, 1 + contextOffset, features[neighbourIndex]
                )
            }

            // param_9 / descriptor +0x1C: following events, nearest to farthest.
            for (contextOffset in 0 until rightCount) {
                val neighbourIndex = (centreIndex + 1 + contextOffset)
                    .coerceIn(0, lastIndex)
                writeCategory(
                    matrix,
                    rowStart,
                    layout,
                    1 + leftCount + contextOffset,
                    features[neighbourIndex]
                )
            }
        }

        return S5ApiDefaultPhoneFeatureMatrix(features.size, rowWidth, matrix)
    }

    private fun writeCategory(
        matrix: FloatArray,
        rowStart: Int,
        layout: S5ApiDefaultPhoneFeatureLayout,
        segmentOrdinal: Int,
        feature: VsmAlignedPhoneFeature
    ) {
        val offset = rowStart + layout.categorySegmentStartIndex(segmentOrdinal) +
            feature.categoryIndex
        matrix[offset] = 1.0f
    }
}
