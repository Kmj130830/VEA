package com.kmj.vea.engine

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Frame-continuous periodic + Gaussian excitation generator recovered from
 * S5API.dll FUN_18012ad70.
 *
 * The transform-state constructor still supplies the exact native scales and
 * threshold. Their physical units are not yet confirmed; do not infer Hz from
 * the field names or model tensor dimensions.
 */
internal class S5MegpoidExcitationGenerator(
    private val phaseScale: Float,
    private val periodScale: Float,
    private val sineDenominatorThreshold: Float,
    private val wrapPhase: Boolean,
    private val gaussianNoise: () -> Float
) {
    private var previousF0 = 0f
    private var previousNoiseGain = 0f
    private var previousHarmonicGain = 0f
    private var phase = 0f

    init {
        require(phaseScale > 0f)
        require(periodScale > 0f)
        require(sineDenominatorThreshold >= 0f)
    }

    fun reset(initialPhase: Float = 0f) {
        previousF0 = 0f
        previousNoiseGain = 0f
        previousHarmonicGain = 0f
        phase = initialPhase
    }

    fun generate(
        sampleCount: Int,
        targetF0: Float,
        targetNoiseGain: Float,
        targetHarmonicGain: Float
    ): FloatArray {
        require(sampleCount > 0)
        val inverseCount = if (sampleCount < 2) 0f else 1f / sampleCount
        val f0Step = (targetF0 - previousF0) * inverseCount
        val oldF0 = previousF0
        val oldNoiseGain = previousNoiseGain
        val oldHarmonicGain = previousHarmonicGain
        val phaseStepScale = TWO_PI / phaseScale
        val phases = FloatArray(sampleCount)

        var nextPhase = phase
        for (i in 0 until sampleCount) {
            nextPhase += (i * f0Step + oldF0) * phaseStepScale
            if (wrapPhase) {
                nextPhase %= TWO_PI
                if (nextPhase < 0f) nextPhase += TWO_PI
            }
            phases[i] = nextPhase
        }

        phase = nextPhase
        previousF0 = targetF0
        previousNoiseGain = targetNoiseGain
        previousHarmonicGain = targetHarmonicGain

        val output = FloatArray(sampleCount)
        for (i in 0 until sampleCount) {
            val alpha = i * inverseCount
            val f0 = i * f0Step + oldF0
            val safeF0 = max(f0, MIN_F0)
            var period = floor(periodScale / safeF0)
            if (period <= 1f) period = 1f

            val currentPhase = phases[i]
            val halfPhase = currentPhase * 0.5f
            val sinHalf = sin(halfPhase)
            val twiceSinHalf = sinHalf + sinHalf
            val periodicShape = if (abs(twiceSinHalf) <= sineDenominatorThreshold) {
                0f
            } else {
                (cos(halfPhase) - cos((period + 0.5f) * currentPhase)) / twiceSinHalf
            }

            val periodicScale = sqrt(2f / period)
            val noiseGain = alpha * (targetNoiseGain - oldNoiseGain) + oldNoiseGain
            val harmonicGain = alpha * (targetHarmonicGain - oldHarmonicGain) + oldHarmonicGain
            val voicedGate = if (f0 > 0f) 1f else 0f

            val periodic = periodicScale * harmonicGain * periodicShape * voicedGate
            val noise = gaussianNoise() * noiseGain
            output[i] = periodic + noise
        }
        return output
    }

    internal val phaseForTesting: Float
        get() = phase

    internal companion object {
        /**
         * Creates an excitation source using the recovered S5API MT19937 /
         * cached-pair Gaussian path. Phase and period scales remain explicit
         * because their values belong to the transform-state constructor.
         */
        fun withRecoveredRandomSource(
            phaseScale: Float,
            periodScale: Float,
            sineDenominatorThreshold: Float,
            wrapPhase: Boolean,
            randomSeed: Int = S5MegpoidNativeMersenneTwister.DEFAULT_SEED
        ): S5MegpoidExcitationGenerator {
            val noise = S5MegpoidGaussianNoise.fromNativeSeed(randomSeed)
            return S5MegpoidExcitationGenerator(
                phaseScale = phaseScale,
                periodScale = periodScale,
                sineDenominatorThreshold = sineDenominatorThreshold,
                wrapPhase = wrapPhase,
                gaussianNoise = noise::nextSample
            )
        }

        const val TWO_PI = 6.2831855f
        const val MIN_F0 = 1e-5f
    }
}

/**
 * Marsaglia polar normal sampler matching the native cached-pair strategy.
 * The underlying uniform PRNG is injectable so the renderer can later replace
 * java.util.Random with the exact native MT19937 seed/state contract.
 */
internal class S5MegpoidGaussianNoise(
    private val nextUniformMinusOneToOne: () -> Float
) {
    private var hasCachedSample = false
    private var cachedSample = 0f

    fun nextSample(): Float {
        if (hasCachedSample) {
            hasCachedSample = false
            return cachedSample
        }

        var x: Float
        var y: Float
        var radiusSquared: Float
        do {
            x = nextUniformMinusOneToOne()
            y = nextUniformMinusOneToOne()
            radiusSquared = x * x + y * y
        } while (radiusSquared >= 1f || radiusSquared == 0f || x == 0f || y == 0f)

        // The native routine rescales exceptionally small radii before logf
        // to avoid precision loss, then compensates the logarithm by ln(4)*e.
        var sampleX = x
        var sampleY = y
        var normalizationRadiusSquared = radiusSquared
        var logRadiusSquared = kotlin.math.ln(radiusSquared)
        if (radiusSquared <= 0.0001f) {
            val exponent = Math.getExponent(radiusSquared)
            sampleX = Math.scalb(x, -exponent)
            sampleY = Math.scalb(y, -exponent)
            normalizationRadiusSquared = sampleX * sampleX + sampleY * sampleY
            logRadiusSquared = kotlin.math.ln(normalizationRadiusSquared) +
                exponent.toFloat() * 1.3862944f
        }

        val scale = sqrt((-2f * logRadiusSquared) / normalizationRadiusSquared)
        cachedSample = sampleY * scale
        hasCachedSample = true
        return sampleX * scale
    }

    companion object {
        /**
         * S5API constructs this stream with seed 0x2a (42). A zero seed is
         * replaced by the native default in FUN_180127a40.
         */
        fun fromNativeSeed(seed: Int = S5MegpoidNativeMersenneTwister.DEFAULT_SEED): S5MegpoidGaussianNoise {
            val random = S5MegpoidNativeMersenneTwister(seed)
            return S5MegpoidGaussianNoise(random::nextUniformMinusOneToOne)
        }
    }
}
