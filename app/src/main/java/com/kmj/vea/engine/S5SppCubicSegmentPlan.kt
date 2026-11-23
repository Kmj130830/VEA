package com.kmj.vea.engine

/**
 * Four neighboring values consumed by FUN_1801360c0 from the
 * FUN_1801314f0 pitch/phase curve sampler.
 */
class S5SppCubicSegmentPlan(controls: FloatArray) {
    private val controlData = controls.copyOf()

    val controls: FloatArray
        get() = controlData.copyOf()

    init {
        require(controlData.size == 4)
        require(controlData.all { it.isFinite() })
    }

    fun sample(
        position: Float,
        interpolate: (Float, FloatArray) -> Float =
            NativeS5TensorBridge::interpolateSppCubicSegment
    ): Float {
        require(position.isFinite() && position in 0.0f..1.0f)
        return interpolate(position, controlData.copyOf())
    }
}
