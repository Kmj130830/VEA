package com.kmj.vea.engine

/**
 * Recovered post-inverse-FFT frame-buffer step from FUN_1801334b0.
 *
 * Confirmed order:
 * 1. FUN_180040630(..., direction = 1) writes the inverse real frame;
 * 2. FUN_1801397d0 multiplies all frameSize values by 1/frameSize;
 * 3. FUN_180133fd0 circularly rotates the buffer by -offset.
 *
 * The later partial/window descriptor accumulation in FUN_1801334b0 is not
 * included here because its state fields have not yet been bound to the
 * corresponding VEA model/render objects.
 */
data class S5SppInverseFramePlan(
    val fftSize: Int,
    val offset: Int
) {
    init {
        require(fftSize in 4..65536 && (fftSize and (fftSize - 1)) == 0) {
            "SPP inverse frame size must be a supported power of two."
        }
        require(offset != Int.MIN_VALUE) {
            "The SPP frame offset cannot be negated safely."
        }
    }

    val normalizationScale: Float
        get() = 1.0f / fftSize.toFloat()

    val rotationShift: Int
        get() = -offset

    fun process(
        packedSpectrum: FloatArray,
        inverseTransform: (Int, Int, FloatArray) -> FloatArray =
            NativeS5TensorBridge::applySppRealFft,
        normalize: (FloatArray, Float) -> FloatArray =
            NativeS5TensorBridge::scaleSppBuffer,
        rotate: (FloatArray, Int) -> FloatArray =
            NativeS5TensorBridge::rotateSppCircularBuffer
    ): FloatArray {
        require(packedSpectrum.size == fftSize)
        require(packedSpectrum.all { it.isFinite() })

        val inverseFrame = inverseTransform(fftSize, 1, packedSpectrum.copyOf())
        require(inverseFrame.size == fftSize) {
            "The SPP inverse transform returned an invalid frame length."
        }
        require(inverseFrame.all { it.isFinite() })

        val normalized = normalize(inverseFrame, normalizationScale)
        require(normalized.size == fftSize) {
            "The SPP frame normalizer returned an invalid frame length."
        }
        require(normalized.all { it.isFinite() })

        val shifted = rotate(normalized, rotationShift)
        require(shifted.size == fftSize) {
            "The SPP circular shifter returned an invalid frame length."
        }
        require(shifted.all { it.isFinite() })
        return shifted
    }
}
