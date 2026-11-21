package com.kmj.vea.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Contract-level inverse real-spectrum transform for the Megpoid output path.
 *
 * S5API's real-transform wrapper stores the interior one-sided spectrum with
 * the imaginary components conjugated relative to its internal forward FFT
 * convention. Its inverse path restores those signs and calls the FFT core in
 * the negative direction. This reference implementation folds those two
 * operations into a direct inverse using the positive exponential direction.
 *
 * Input is N/2 + 1 interleaved complex float32 bins. The output is N normalized
 * real samples. DC and Nyquist are self-conjugate and their imaginary values
 * are ignored.
 *
 * This is an independent Android reference kernel. It has not yet been
 * numerically compared with the vendor FFT backend and is not wired into
 * SynthesisEngine.render() until the transform/overlap descriptor is fully
 * populated and its numerical behavior is validated.
 */
class S5MegpoidInverseRealFft(val fftSize: Int) {
    val binCount: Int

    init {
        require(fftSize >= 2 && fftSize % 2 == 0) {
            "The inverse real transform requires an even FFT size of at least two."
        }
        require((fftSize and (fftSize - 1)) == 0) {
            "The reference inverse transform currently supports radix-2 FFT sizes only."
        }
        binCount = fftSize / 2 + 1
    }

    /**
     * Transforms interleaved [real, imaginary] bins for frequencies 0..N/2.
     *
     * The vendor wrapper's inverse operation conjugates every interior
     * imaginary bin before calling its negative-direction packed real FFT.
     * Using a positive-direction full complex inverse is mathematically
     * equivalent and avoids exposing a vendor-specific packed workspace.
     */
    fun transform(interleavedSpectrum: FloatArray): FloatArray {
        require(interleavedSpectrum.size.toLong() == binCount.toLong() * 2L) {
            "The spectrum must contain exactly N/2 + 1 interleaved complex bins."
        }
        require(interleavedSpectrum.all { it.isFinite() }) {
            "The complex spectrum contains a non-finite value."
        }

        val real = FloatArray(fftSize)
        val imaginary = FloatArray(fftSize)
        val lastBin = fftSize / 2

        for (bin in 0..lastBin) {
            real[bin] = interleavedSpectrum[bin * 2]
            // DC and Nyquist are real-valued bins in a real transform.
            imaginary[bin] = if (bin == 0 || bin == lastBin) {
                0f
            } else {
                interleavedSpectrum[bin * 2 + 1]
            }
        }

        // Complete the one-sided spectrum: X[N-k] = conjugate(X[k]).
        for (bin in 1 until lastBin) {
            real[fftSize - bin] = real[bin]
            imaginary[fftSize - bin] = -imaginary[bin]
        }

        inverseComplexInPlace(real, imaginary)

        // FUN_18012c740 divides the inverse-transform frame by N.
        val normalization = 1f / fftSize.toFloat()
        return FloatArray(fftSize) { index ->
            val sample = real[index] * normalization
            check(sample.isFinite()) {
                "The inverse transform produced a non-finite time-domain sample."
            }
            sample
        }
    }

    private fun inverseComplexInPlace(real: FloatArray, imaginary: FloatArray) {
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

        // Positive exponent reconstructs S5API's conjugated forward spectrum.
        var blockSize = 2
        while (blockSize <= fftSize) {
            val halfBlock = blockSize / 2
            val angle = 2.0 * PI / blockSize.toDouble()
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
}
