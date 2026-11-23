package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class S5SppCurveSegmentCacheTest {
    @Test
    fun `cache hit uses cubic interpolation only for matching segment and local range`() {
        val cache = S5SppCurveSegmentCache(
            segmentIndex = 7,
            controls = floatArrayOf(2f, 4f, 8f, 16f)
        )
        var calls = 0
        val interpolate: (Float, FloatArray) -> Float = { position, controls ->
            calls += 1
            val squared = position * position
            val basis0 = ((position + position) - 3f) * squared + 1f
            val basis1 = (position - 2f) * squared + position
            basis1 * controls[1] + basis0 * controls[0] +
                (1f - basis0) * controls[2] + (position - 1f) * squared * controls[3]
        }

        assertNull(cache.sampleIfHit(6, 0.5f, interpolate))
        assertNull(cache.sampleIfHit(7, -0.01f, interpolate))
        assertNull(cache.sampleIfHit(7, 1.0f, interpolate))
        assertEquals(0, calls)
        assertEquals(3.5f, cache.sampleIfHit(7, 0.5f, interpolate)!!, 0f)
        assertEquals(1, calls)
    }

    @Test
    fun `curve cache owns its cubic controls`() {
        val controls = floatArrayOf(1f, 2f, 3f, 4f)
        val cache = S5SppCurveSegmentCache(1, controls)
        controls[0] = 90f
        val exposed = cache.controls
        exposed[0] = -20f
        assertEquals(1f, cache.controls[0], 0f)
    }
}
