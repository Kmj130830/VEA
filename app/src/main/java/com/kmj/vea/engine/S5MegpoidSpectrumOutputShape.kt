package com.kmj.vea.engine

/**
 * Shape of the model-produced one-sided complex spectrum stored by S5API
 * at render-state root +0x4F8.
 *
 * The native runtime stores complexBinCount 8-byte complex values. The
 * Android/JNI boundary uses interleaved float32 values (real, imaginary).
 */
data class S5MegpoidSpectrumOutputShape(
    val fftSize: Int,
    val complexBinCount: Int
) {
    init {
        require(fftSize >= 2 && (fftSize and (fftSize - 1)) == 0) {
            "Megpoid spectral FFT size must be a positive power of two."
        }
        require(complexBinCount == fftSize / 2 + 1) {
            "One-sided real spectrum requires fftSize / 2 + 1 complex bins."
        }
        require(complexBinCount <= Int.MAX_VALUE / 2) {
            "Interleaved spectrum exceeds the JVM array limit."
        }
    }

    val interleavedFloatCount: Int
        get() = complexBinCount * 2

    /** Returns a fresh zeroed [real0, imag0, real1, imag1, ...] frame. */
    fun newInterleavedFrame(): FloatArray = FloatArray(interleavedFloatCount)

    fun requireInterleavedFrame(frame: FloatArray) {
        require(frame.size == interleavedFloatCount) {
            "Model spectrum frame must contain exactly $interleavedFloatCount float32 values."
        }
        require(frame.all { it.isFinite() }) {
            "Model spectrum frame contains a non-finite value."
        }
    }
}
