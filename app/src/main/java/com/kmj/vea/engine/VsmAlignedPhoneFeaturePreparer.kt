package com.kmj.vea.engine

data class VsmAlignedPhoneFeature(
    val event: VsmPhoneEvent,
    val normalizedDuration: Float,
    val categoryIndex: Int,
    val hasLeadingBang: Boolean
) {
    /** Compatibility view for code displaying only the four explicit overrides. */
    val fixedCategoryIndex: Int?
        get() = S5ApiPhoneCategorySpecialCases.fixedIndexOrNull(event)
}

/** Prepares already aligned phones; it never invents per-phone timing. */
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
                categoryIndex = S5ApiPhoneCategoryMap.resolve(event.phoneLabel),
                hasLeadingBang = event.phoneLabel.firstOrNull() == '!'
            )
        }
    }
}
