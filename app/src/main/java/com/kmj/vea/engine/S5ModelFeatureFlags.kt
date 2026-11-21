package com.kmj.vea.engine

/**
 * Optional model branches selected by S5API from the parsed VTB2 root object.
 *
 * The original helpers FUN_18004e3b0 and FUN_18004e1e0 test whether their
 * respective hashed child key exists; they do not read a boolean payload.
 */
data class S5ModelFeatureFlags(
    val pairedOptionalObjects: Boolean,
    val recurrentBranch: Boolean
)

object S5ModelFeatureFlagReader {
    /** FUN_18004e3b0 -> Func_47a12a73 field +0xf10. */
    const val PAIRED_OBJECT_KEY = "69d61aca6f87b9618d4c65b9560cb5be"

    /** FUN_18004e1e0 -> Func_47a12a73 field +0xf11. */
    const val RECURRENT_BRANCH_KEY = "9f174dc2b1ab89f96f4577e17d5e568a"

    fun decode(presentObjectKeys: Set<String>): S5ModelFeatureFlags =
        S5ModelFeatureFlags(
            pairedOptionalObjects = PAIRED_OBJECT_KEY in presentObjectKeys,
            recurrentBranch = RECURRENT_BRANCH_KEY in presentObjectKeys
        )

    fun decode(scan: S5ModelJsonScanResult): S5ModelFeatureFlags =
        decode(scan.matchedObjectKeys)
}
