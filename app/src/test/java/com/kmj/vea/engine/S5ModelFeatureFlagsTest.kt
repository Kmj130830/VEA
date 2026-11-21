package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class S5ModelFeatureFlagsTest {
    @Test
    fun absentKeysDisableBothOptionalBranches() {
        assertEquals(
            S5ModelFeatureFlags(
                pairedOptionalObjects = false,
                recurrentBranch = false
            ),
            S5ModelFeatureFlagReader.decode(emptySet())
        )
    }

    @Test
    fun eachBranchIsEnabledOnlyByItsOwnKeyPresence() {
        assertEquals(
            S5ModelFeatureFlags(
                pairedOptionalObjects = true,
                recurrentBranch = false
            ),
            S5ModelFeatureFlagReader.decode(
                setOf(S5ModelFeatureFlagReader.PAIRED_OBJECT_KEY)
            )
        )
        assertEquals(
            S5ModelFeatureFlags(
                pairedOptionalObjects = false,
                recurrentBranch = true
            ),
            S5ModelFeatureFlagReader.decode(
                setOf(S5ModelFeatureFlagReader.RECURRENT_BRANCH_KEY)
            )
        )
    }

    @Test
    fun bothBranchesCanBeEnabledTogether() {
        assertEquals(
            S5ModelFeatureFlags(
                pairedOptionalObjects = true,
                recurrentBranch = true
            ),
            S5ModelFeatureFlagReader.decode(
                setOf(
                    S5ModelFeatureFlagReader.PAIRED_OBJECT_KEY,
                    S5ModelFeatureFlagReader.RECURRENT_BRANCH_KEY
                )
            )
        )
    }

    @Test
    fun decodesFlagsFromStreamingScanResult() {
        val scan = S5ModelJsonScanner.scanForObjectKeys(
            java.io.StringReader(
                """{"model":{"${S5ModelFeatureFlagReader.RECURRENT_BRANCH_KEY}":false}}"""
            ),
            setOf(
                S5ModelFeatureFlagReader.RECURRENT_BRANCH_KEY,
                S5ModelFeatureFlagReader.PAIRED_OBJECT_KEY
            )
        )

        assertEquals(
            S5ModelFeatureFlags(
                pairedOptionalObjects = false,
                recurrentBranch = true
            ),
            S5ModelFeatureFlagReader.decode(scan)
        )
    }
}
