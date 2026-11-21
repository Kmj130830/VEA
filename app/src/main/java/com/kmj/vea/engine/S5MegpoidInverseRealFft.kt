package com.kmj.vea.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Contract-level inverse real-spectrum transform for the Megpoid output path.
 *
 * The native trace identifies N/2 + 1 complex bins as the input to the inverse
 * transform and shows the result normalized by N. It also identifies direction
 * -1 for this inverse call. This implementation expands the one-sided spectrum
 * to a Hermitian full spectrum, runs a radix-2 complex transform with that
 * direction, and returns the N real samples.
 *
 * This is an independent Android reference kernel. It has not yet been
 * numerically compared with the vendor FFT backend and is not wired into
 * SynthesisEngine.render() until the native transform/overlap descriptor is
 * fully populated.
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
     * DC and Nyquist are self-conjugate bins; their imaginary components are
     * ignored when constructing the real-valued spectrum, as required by a
     * real inverse transform.
     *
     * alternatingBinParity is null when the native sign-flip flag is disabled.
     * When enabled, pass 0 to negate even-indexed bins or 1 to negate
     * odd-indexed bins. The caller must supply the parity established by the
     * transform descriptor; this class does not guess which parity is selected.
     */
    fun transform(
        interleavedSpectrum: FloatArray,
        alternatingBinParity: Int? = null
    ): FloatArray {
        require(interleavedSpectrum.size.toLong() == binCount.toLong() * 2L) {
            "The spectrum must contain exactly N/2 + 1 interleaved complex bins."
        }
        require(alternatingBinParity == null || alternatingBinParity in 0..1) {
            "Alternating-bin parity must be null, zero, or one."
        }
        require(interleavedSpectrum.all { it.isFinite() }) {
            "The complex spectrum contains a non-finite value."
        }

        val real = FloatArray(fftSize)
        val imaginary = FloatArray(fftSize)
        val lastBin = fftSize / 2

        for (bin in 0..lastBin) {
            var binReal = interleavedSpectrum[bin * 2]
            var binImaginary = interleavedSpectrum[bin * 2 + 1]

            if (alternatingBinParity != null && bin % 2 == alternatingBinParity) {
                binReal = -binReal
                binImaginary = -binImaginary
            }

            real[bin] = binReal
            imaginary[bin] = if (bin == 0 || bin == lastBin) 0f else binImaginary
        }

        // Complete the one-sided real spectrum: X[N-k] = conjugate(X[k]).
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

        // The recovered S5API inverse call uses transform direction -1.
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
}
