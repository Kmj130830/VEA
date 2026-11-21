package com.kmj.vea.engine

/**
 * Phone-event features that are independently supported by the recovered
 * S5API preprocessing path. A null fixedCategoryIndex means the active
 * render-context phone-category table is still required.
 */
data class VsmAlignedPhoneFeature(
    val event: VsmPhoneEvent,
    val normalizedDuration: Float,
    val fixedCategoryIndex: Int?
)

/**
 * Prepares already aligned phone events for the known portion of the S5API
 * default feature row.
 *
 * Input must already be per-phone and time-aligned. This class deliberately
 * does not split VeaNote.phoneme strings, invoke G2P, or guess per-phone
 * boundaries. It returns the duration scalar and only the fixed category
 * exceptions whose numeric IDs were recovered exactly.
 */
object VsmAlignedPhoneFeaturePreparer {
    fun prepare(
        source: List<VsmPhoneEvent>,
        minimumPhoneSeconds: Float,
        maximumPhoneSeconds: Float
    ): List<VsmAlignedPhoneFeature> {
        val preprocessed = VsmPhoneEventPreprocessor.preprocess(source)
        val canonical = VsmPhoneLabelCanonicalizer.canonicalize(preprocessed)

        return canonical.map { event ->
            VsmAlignedPhoneFeature(
                event = event,
                normalizedDuration = S5ApiPhoneDurationFeature.encode(
                    event = event,
                    minimumSeconds = minimumPhoneSeconds,
                    maximumSeconds = maximumPhoneSeconds
                ),
                fixedCategoryIndex = S5ApiPhoneCategorySpecialCases.fixedIndexOrNull(event)
            )
        }
    }
}
