package com.kmj.vea.engine

/**
 * Portable representation of the opaque double-table config consumed by
 * FUN_180139870:
 * - scale: config[0]
 * - fallback: config[2]
 * - segmentCount: int32 at byte offset 0x18
 * - table: config[4], with segmentCount + 1 double values
 *
 * The object does not assume where or how the renderer-state factory
 * populates this configuration. It can be constructed after that source
 * field has been recovered.
 */
data class S5SppWindowLookupPlan(
    val table: DoubleArray,
    val segmentCount: Int,
    val scale: Double,
    val fallbackValue: Double
) {
    init {
        require(segmentCount in 0 until Int.MAX_VALUE)
        require(table.size.toLong() >= segmentCount.toLong() + if (segmentCount > 0) 1L else 0L) {
            "SPP window table requires segmentCount + 1 values."
        }
        require(table.all { it.isFinite() })
        require(scale.isFinite() && scale >= 0.0)
        require(fallbackValue.isFinite())
    }

    fun coefficient(
        x: Double,
        lookup: (DoubleArray, Int, Double, Double, Double) -> Double =
            NativeS5TensorBridge::lookupSppWindowTable
    ): Double {
        require(x.isFinite())
        return lookup(table, segmentCount, scale, fallbackValue, x)
    }
}
