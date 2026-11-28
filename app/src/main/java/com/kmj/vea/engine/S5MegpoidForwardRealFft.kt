package com.kmj.vea.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Portable radix-2 real-to-complex FFT used as an Android reference for the
 * S5API forward excitation transform boundary.
 *
 * Input is a complete, already windowed real frame. Output is N/2+1
 * interleaved complex float32 bins in [real0,imag0,real1,imag1,...] order.
 *
 * Native static tracing establishes that FUN_18012b150 forms a real frame,
 * applies its state-owned window/history, then invokes FUN_180040630 with
 * forward direction and copies N/2+1 bins. This Kotlin kernel supplies the
 * mathematical transform only: native FFT-plan table layout, exact rounding,
 * frame-history construction, and vendor bit-parity remain unverified.
 */
class S5MegpoidForwardRealFft(val fftSize: Int) {
    val binCount: Int

    init {
        require(fftSize >= 2 && fftSize % 2 == 0) {
            "The forward real transform requires an even FFT size of at least two."
        }
        require((fftSize and (fftSize - 1)) == 0) {
            "The reference forward transform currently supports radix-2 FFT sizes only."
        }
        binCount = fftSize / 2 + 1
    }

    /**
     * Transforms one complete real-valued frame without applying a window or
     * normalization. Windowing belongs to the caller's frame/history stage;
     * the forward DFT convention is X[k] = sum(x[n] * exp(-j*2*pi*k*n/N)).
     */
    fun transform(realFrame: FloatArray): FloatArray {
        require(realFrame.size == fftSize) {
            "The real input frame must contain exactly $fftSize samples."
        }
        require(realFrame.all { it.isFinite() }) {
            "The real input frame contains a non-finite value."
        }

        val real = realFrame.copyOf()
        val imaginary = FloatArray(fftSize)
        forwardComplexInPlace(real, imaginary)

        val output = FloatArray(checkedProduct(binCount, 2))
        for (bin in 0 until binCount) {
            output[bin * 2] = real[bin]
            output[bin * 2 + 1] = if (bin == 0 || bin == fftSize / 2) {
                // A real input has real-only DC and Nyquist coefficients.
                0f
            } else {
                imaginary[bin]
            }
        }
        require(output.all { it.isFinite() }) {
            "The forward real transform produced a non-finite spectrum."
        }
        return output
    }

    private fun forwardComplexInPlace(real: FloatArray, imaginary: FloatArray) {
        // Iterative bit-reversal permutation.
        var reversed = 0
        for (index in 1 until fftSize) {
            var bit = fftSize shr 1
            while ((reversed and bit) != 0) {
                reversed = reversed xor bit
                bit = bit shr 1
            }
            reversed = reversed xor bit

            if (index < reversed) {
                val realValue = real[index]
                real[index] = real[reversed]
                real[reversed] = realValue

                val imaginaryValue = imaginary[index]
                imaginary[index] = imaginary[reversed]
                imaginary[reversed] = imaginaryValue
            }
        }

        // Negative exponent is the conventional forward DFT direction.
        var blockSize = 2
        while (blockSize <= fftSize) {
            val halfBlock = blockSize / 2
            val angle = -2.0 * PI / blockSize.toDouble()
            val stepReal = cos(angle).toFloat()
            val stepImaginary = sin(angle).toFloat()

            for (blockStart in 0 until fftSize step blockSize) {
                var twiddleReal = 1f
                var twiddleImaginary = 0f

                for (offset in 0 until halfBlock) {
                    val evenIndex = blockStart + offset
                    val oddIndex = evenIndex + halfBlock

                    val oddReal =
                        twiddleReal * real[oddIndex] -
                            twiddleImaginary * imaginary[oddIndex]
                    val oddImaginary =
                        twiddleReal * imaginary[oddIndex] +
                            twiddleImaginary * real[oddIndex]

                    val evenReal = real[evenIndex]
                    val evenImaginary = imaginary[evenIndex]

                    real[evenIndex] = evenReal + oddReal
                    imaginary[evenIndex] = evenImaginary + oddImaginary
                    real[oddIndex] = evenReal - oddReal
                    imaginary[oddIndex] = evenImaginary - oddImaginary

                    val nextTwiddleReal =
                        twiddleReal * stepReal - twiddleImaginary * stepImaginary
                    twiddleImaginary =
                        twiddleReal * stepImaginary + twiddleImaginary * stepReal
                    twiddleReal = nextTwiddleReal
                }
            }

            if (blockSize == fftSize) break
            blockSize = blockSize shl 1
        }
    }

    private fun checkedProduct(a: Int, b: Int): Int {
        val result = a.toLong() * b.toLong()
        require(a > 0 && b > 0 && result <= Int.MAX_VALUE) {
            "Forward real-spectrum allocation exceeds the supported FloatArray size."
        }
        return result.toInt()
    }
}
