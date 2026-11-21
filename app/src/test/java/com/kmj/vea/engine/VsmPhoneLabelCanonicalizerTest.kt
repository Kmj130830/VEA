package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class VsmPhoneLabelCanonicalizerTest {
    @Test
    fun mapsAll43RecoveredAliasesToCanonicalLabels() {
        val expected = mapOf(
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

        assertEquals(43, expected.size)
        assertEquals(43, VsmPhoneLabelCanonicalizer.aliasCount)
        expected.forEach { (alias, canonical) ->
            assertEquals("Alias $alias", canonical, VsmPhoneLabelCanonicalizer.canonicalize(alias))
        }
    }

    @Test
    fun leavesCanonicalAndUnknownLabelsUnchanged() {
        val labels = listOf("sil", "br", "a", "iy", "junk", "unknown", "TS", "x ")
        labels.forEach { label ->
            assertEquals(label, VsmPhoneLabelCanonicalizer.canonicalize(label))
        }
    }

    @Test
    fun preservesSourceFieldsWhenCanonicalizingEvents() {
        val original = VsmPhoneEvent(
            startSeconds = 0.12,
            endSeconds = 0.24,
            phoneLabel = "ia+1",
            opaqueSourceU32 = 0x10203040
        )

        val mapped = VsmPhoneLabelCanonicalizer.canonicalize(listOf(original)).single()

        assertEquals(0.12, mapped.startSeconds, 0.0)
        assertEquals(0.24, mapped.endSeconds, 0.0)
        assertEquals("ah", mapped.phoneLabel)
        assertEquals(0x10203040, mapped.opaqueSourceU32)
    }
}
