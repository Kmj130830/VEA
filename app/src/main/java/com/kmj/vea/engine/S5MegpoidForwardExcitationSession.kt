package com.kmj.vea.engine

/**
 * Stateful bridge from periodic/noise excitation chunks to one-sided complex
 * spectra. It keeps the excitation generator, circular sample history and
 * forward FFT in one utterance-local object.
 *
 * The caller supplies hopSamples and the complete FFT window explicitly.
 * AI Megpoid's model has a 1024-point FFT and a 320-sample output block, but
 * the static trace does not yet prove that the input excitation hop equals the
 * final output block size. Do not silently infer one from the other.
 *
 * initialHistory is chronological [oldest..newest], with exactly fftSize
 * samples. The default is zero history as a deterministic startup policy;
 * live native parity still requires checking the vendor constructor state and
 * the frame-validity gate.
 */
class S5MegpoidForwardExcitationSession(
    private val excitationGenerator: S5MegpoidExcitationGenerator,
    val fftSize: Int,
    val hopSamples: Int,
    frameWindow: FloatArray,
    initialHistory: FloatArray = FloatArray(fftSize)
) {
    private val fft = S5MegpoidForwardRealFft(fftSize)
    private val window = frameWindow.copyOf()
    private val history = initialHistory.copyOf()
    private var writeCursor = 0
    private var processedFrameCountValue = 0L

    init {
        require(hopSamples in 1..fftSize) {
            "Excitation hop must be between one sample and the FFT size."
        }
        require(window.size == fftSize) {
            "Excitation frame window must contain exactly $fftSize coefficients."
        }
        require(initialHistory.size == fftSize) {
            "Initial excitation history must contain exactly $fftSize samples."
        }
        require(window.all { it.isFinite() } && history.all { it.isFinite() }) {
            "Excitation window or initial history contains a non-finite value."
        }
    }

    val processedFrameCount: Long
        @Synchronized get() = processedFrameCountValue

    /**
     * Generates exactly one hop, appends it to circular history, reads the
     * chronological N-sample frame beginning at the next-write cursor,
     * applies the explicit window, then runs the real forward FFT.
     *
     * The returned spectrum has N/2+1 interleaved complex bins. It is an
     * excitation spectrum only; no claim is made that it is the model's final
     * output spectrum or the missing eight-plane active neural output.
     */
    @Synchronized
    fun processFrame(
        targetF0: Float,
        targetNoiseGain: Float,
        targetHarmonicGain: Float
    ): FloatArray {
        require(targetF0.isFinite() && targetF0 >= 0f) {
            "Target F0 must be a finite non-negative value."
        }
        require(targetNoiseGain.isFinite() && targetHarmonicGain.isFinite()) {
            "Excitation gains must be finite."
        }
        check(processedFrameCountValue < Long.MAX_VALUE) {
            "Excitation frame counter overflowed."
        }

        val chunk = excitationGenerator.generate(
            sampleCount = hopSamples,
            targetF0 = targetF0,
            targetNoiseGain = targetNoiseGain,
            targetHarmonicGain = targetHarmonicGain
        )
        for (sample in chunk) {
            history[writeCursor] = sample
            writeCursor++
            if (writeCursor == fftSize) writeCursor = 0
        }

        // writeCursor points at the oldest retained sample / next write slot.
        val windowedFrame = FloatArray(fftSize) { index ->
            history[(writeCursor + index) % fftSize] * window[index]
        }
        require(windowedFrame.all { it.isFinite() }) {
            "Windowed excitation frame contains a non-finite sample."
        }

        val spectrum = fft.transform(windowedFrame)
        processedFrameCountValue++
        return spectrum
    }

    /** Returns a defensive copy of the chronological ring history. */
    @Synchronized
    fun copyHistoryOldestToNewest(): FloatArray =
        FloatArray(fftSize) { index -> history[(writeCursor + index) % fftSize] }

    /** Resets excitation dynamics and optionally replaces the initial history. */
    @Synchronized
    fun reset(
        initialPhase: Float = 0f,
        initialHistory: FloatArray = FloatArray(fftSize)
    ) {
        require(initialPhase.isFinite())
        require(initialHistory.size == fftSize)
        require(initialHistory.all { it.isFinite() })
        excitationGenerator.reset(initialPhase)
        initialHistory.copyInto(history)
        writeCursor = 0
        processedFrameCountValue = 0L
    }
}
