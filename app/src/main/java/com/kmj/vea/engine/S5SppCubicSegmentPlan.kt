package com.kmj.vea.engine

/**
 * Four neighboring values consumed by FUN_1801360c0 from the
 * FUN_1801314f0 pitch/phase curve sampler.
 */
data class S5SppCubicSegmentPlan(
    val controls: FloatArray
) {
    init {
        require(controls.size == 4)
        require(controls.all { it.isFinite() })
    }

    fun sample(
        position: Float,
        interpolate: (Float, FloatArray) -> Float =
            NativeS5TensorBridge::interpolateSppCubicSegment
    ): Float {
        require(position.isFinite() && position in 0.0f..1.0f)
        return interpolate(position, controls.copyOf())
    }
}
