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
        require(spectrumShape.fftSize == overlapAdd.fftSizeSamples) {
            "Spectrum shape FFT size does not match the overlap-add processor."
        }
        require(outputSamples == overlapAdd.outputSampleCount) {
            "Declared output block length does not match the overlap-add processor."
        }
    }

    /** Number of complex bins expected in each native one-sided spectrum frame. */
    val complexBinCount: Int get() = spectrumShape.complexBinCount

    /** FFT size used by the current overlap-add session. */
    val fftSize: Int get() = spectrumShape.fftSize

    /** Number of PCM samples emitted by each processed spectral frame. */
    val outputSampleCount: Int get() = outputSamples

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

    /**
     * Converts the terminal pair of model feature planes to an interleaved
     * complex spectrum using an explicitly selected native output encoding,
     * then runs the same inverse-FFT/overlap-add path as processSpectrumFrame.
     */
    @Synchronized
    fun processModelOutputPlanes(
        firstFeaturePlane: FloatArray,
        secondFeaturePlane: FloatArray,
        encoding: S5MegpoidSpectrumFrameEncoding
    ): FloatArray {
        val spectrum = S5MegpoidSpectrumFrameEncoder.encode(
            firstFeaturePlane = firstFeaturePlane,
            secondFeaturePlane = secondFeaturePlane,
            shape = spectrumShape,
            encoding = encoding
        )
        return processSpectrumFrame(spectrum)
    }

    /**
     * Uses the spectrum representation read from the selected VTB2
     * runtime-options object. This is the preferred AI Megpoid entry point;
     * no encoding mode is hard-coded in the output session.
     */
    @Synchronized
    fun processModelOutputPlanes(
        firstFeaturePlane: FloatArray,
        secondFeaturePlane: FloatArray,
        runtimeOptions: S5MegpoidRuntimeOptionsDescriptor
    ): FloatArray = processModelOutputPlanes(
        firstFeaturePlane = firstFeaturePlane,
        secondFeaturePlane = secondFeaturePlane,
        encoding = runtimeOptions.terminalSpectrumEncoding
    )

    /** Clears overlap history and starts a fresh utterance-local frame count. */
    @Synchronized
    fun reset() {
        overlapAdd.resetHistory()
        processedFrameCountValue = 0L
    }
}
