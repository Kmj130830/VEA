package com.kmj.vea.engine

/**
 * Stateful bridge from model-produced one-sided complex spectra to time-domain
 * output blocks. The FFT/window/overlap state is owned by one render session;
 * create a new session for a new utterance or call reset() between utterances.
 */
class PreparedMegpoidSpectralOutputSession(
    private val spectrumShape: S5MegpoidSpectrumOutputShape,
    private val outputSamples: Int,
    private val overlapAdd: S5MegpoidSpectralOverlapAdd
) {
    private var processedFrameCountValue: Long = 0L

    init {
        require(outputSamples > 0) { "Spectral output block length must be positive." }
    }

    /** Number of spectrum frames successfully consumed since the last reset. */
    val processedFrameCount: Long
        @Synchronized get() = processedFrameCountValue

    /**
     * Consumes exactly one [real, imaginary] interleaved spectrum frame and
     * returns one overlap-add output block. Failed validation does not advance
     * the frame counter or mutate the overlap history.
     */
    @Synchronized
    fun processSpectrumFrame(interleavedSpectrum: FloatArray): FloatArray {
        spectrumShape.requireInterleavedFrame(interleavedSpectrum)
        val output = overlapAdd.processSpectrum(interleavedSpectrum)
        check(output.size == outputSamples) {
            "Spectral overlap-add returned ${output.size} samples; expected $outputSamples."
        }
        check(processedFrameCountValue < Long.MAX_VALUE) {
            "Spectral output frame counter overflowed."
        }
        processedFrameCountValue += 1L
        return output
    }

    /** Clears overlap history and starts a fresh utterance-local frame count. */
    @Synchronized
    fun reset() {
        overlapAdd.resetHistory()
        processedFrameCountValue = 0L
    }
}
