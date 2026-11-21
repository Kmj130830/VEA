package com.kmj.vea.engine

/**
 * One phone-aligned event at the S5API input boundary.
 *
 * The recovered 0x38-byte native row contains an interval, an owned phone
 * label string and an opaque uint32 copied from the caller's 0x28-byte row.
 * The uint32 is kept bit-for-bit as an Int; its vendor meaning is not named.
 */
data class VsmPhoneEvent(
    val startSeconds: Double,
    val endSeconds: Double,
    val phoneLabel: String,
    val opaqueSourceU32: Int
) {
    init {
        require(startSeconds.isFinite() && endSeconds.isFinite()) {
            "Phone event times must be finite."
        }
        require(phoneLabel.isNotEmpty()) { "Phone label must not be empty." }
    }

    val durationSeconds: Double
        get() = endSeconds - startSeconds
}

/**
 * Ports the confirmed three-step preprocessing path:
 * FUN_180007740 -> FUN_180007a90 -> FUN_180007ec0.
 *
 * This works on already phone-aligned events. It does not perform G2P,
 * assign phone IDs, or split a note-level phoneme display string.
 */
object VsmPhoneEventPreprocessor {
    // This is the exact float32 0.001f value promoted to double by the native
    // comparison in FUN_180007ec0.
    private const val MIN_INTERVAL_SECONDS = 0.0010000000474974513

    fun preprocess(source: List<VsmPhoneEvent>): List<VsmPhoneEvent> {
        if (source.isEmpty()) return emptyList()

        val breakNormalized = normalizeBreakEvents(source)
        if (breakNormalized.isEmpty()) return emptyList()

        val originalFinalEnd = breakNormalized.last().endSeconds

        // FUN_180007a90 removes exact "-" labels, then assigns each retained
        // event the start position of the next retained event. The last retained
        // event inherits the end of the final pre-filter row.
        val withoutRestMarkers = breakNormalized.filterNot { it.phoneLabel == "-" }
        if (withoutRestMarkers.isEmpty()) return emptyList()

        val endpointAdjusted = withoutRestMarkers.mapIndexed { index, event ->
            val nextEnd = withoutRestMarkers.getOrNull(index + 1)
                ?.startSeconds
                ?: originalFinalEnd
            event.copy(
                endSeconds = nextEnd
            )
        }

        // FUN_180007ec0 retains intervals whose duration is >= float32(0.001).
        return endpointAdjusted.filter {
            it.durationSeconds >= MIN_INTERVAL_SECONDS
        }
    }

    private fun normalizeBreakEvents(source: List<VsmPhoneEvent>): List<VsmPhoneEvent> {
        val result = ArrayList<VsmPhoneEvent>(source.size)
        for (event in source) {
            if (event.phoneLabel != "br") {
                result += event
                continue
            }

            if (result.isEmpty()) {
                // A leading break is materialized as an explicit silence row.
                result += event.copy(phoneLabel = "sil")
                continue
            }

            val previous = result.last()
            if (previous.phoneLabel == "sil") {
                // Consecutive break after silence extends the existing silence.
                result[result.lastIndex] = previous.copy(
                    endSeconds = event.endSeconds
                )
            } else {
                result += event.copy(phoneLabel = "sil")
            }
        }
        return result
    }
}
