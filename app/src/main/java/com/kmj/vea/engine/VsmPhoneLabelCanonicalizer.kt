package com.kmj.vea.engine

/**
 * Canonicalizes the exact special phone-label aliases recovered from
 * S5API FUN_180001010 / FUN_180074a50.
 *
 * This is the alias-to-canonical STRING layer only. It deliberately does not
 * assign the numeric category index returned by FUN_180074a50's separate
 * render-context phone map.
 */
object VsmPhoneLabelCanonicalizer {
    private val aliases: Map<String, String> = mapOf(
        "ts\u0060_h" to "ts",
        "ts\\_h" to "ts",
        "x" to "h",
        "s\u0060" to "sh",
        "ia+0" to "y",
        "iE_r+0" to "y",
        "yE_r+0" to "y",
        "ua+0" to "w",
        "uo+0" to "w",
        "iAU+0" to "y",
        "i@U+0" to "y",
        "uaI+0" to "w",
        "uei+0" to "w",
        "iAN+0" to "y",
        "iUN+0" to "y",
        "iE_n+0" to "y",
        "y{_n+0" to "y",
        "u@N+0" to "w",
        "u@_n+0" to "w",
        "ua_n+0" to "w",
        "uAN+0" to "w",
        "ia+1" to "ah",
        "iE_r+1" to "eh",
        "yE_r+1" to "y",
        "ua+1" to "ah",
        "uo+1" to "aa",
        "iAU+1" to "aw",
        "i@U+1" to "ow",
        "uaI+1" to "ay",
        "uei+1" to "ay",
        "iAN+1" to "aw",
        "iUN+1" to "uw",
        "iE_n+1" to "aw",
        "y{_n+1" to "aw",
        "u@N+1" to "ow",
        "u@_n+1" to "w",
        "ua_n+1" to "aw",
        "uAN+1" to "aw",
        "y|CH" to "i",
        "a|CH" to "a",
        "i|CH" to "i",
        "u|CH" to "u",
        "o|CH" to "o"
    )

    /** Returns the canonical phone label, or the original label if unmatched. */
    fun canonicalize(label: String): String = aliases[label] ?: label

    /** Applies the recovered aliases without trimming or changing case. */
    fun canonicalize(events: List<VsmPhoneEvent>): List<VsmPhoneEvent> =
        events.map { event ->
            val canonical = canonicalize(event.phoneLabel)
            if (canonical == event.phoneLabel) event
            else event.copy(phoneLabel = canonical)
        }

    val aliasCount: Int
        get() = aliases.size
}
