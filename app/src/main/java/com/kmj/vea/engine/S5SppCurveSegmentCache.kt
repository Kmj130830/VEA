package com.kmj.vea.engine

/**
 * The cached cubic fast path in FUN_1801314f0.
 *
 * The source uses the cached controls only when the cached segment index
 * equals the selected curve segment and the local segment position is
 * in [0,1). A cache miss returns null so the caller can use the normal
 * curve-table interpolation and boundary handling.
 */
class S5SppCurveSegmentCache(
    val segmentIndex: Int,
    controls: FloatArray
) {
    private val cubic = S5SppCubicSegmentPlan(controls)

    val controls: FloatArray
        get() = cubic.controls

    fun sampleIfHit(
        requestedSegmentIndex: Int,
        localPosition: Float,
        interpolate: (Float, FloatArray) -> Float =
            NativeS5TensorBridge::interpolateSppCubicSegment
    ): Float? {
        require(localPosition.isFinite())
        if (requestedSegmentIndex != segmentIndex ||
            localPosition < 0.0f || localPosition >= 1.0f) {
            return null
        }
        return cubic.sample(localPosition, interpolate)
    }
}
