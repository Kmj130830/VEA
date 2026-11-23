package com.kmj.vea.engine

/**
 * Executes one decoded VTMG layer with an already recovered S5API primitive.
 * Implementations receive plans, not raw model JSON or renderer state.
 */
interface S5VtmgGraphOperatorExecutor {
    fun projectBanked(
        plan: S5VtmgBankedProjectionPlan,
        input: FloatArray,
        positions: Int
    ): FloatArray

    fun projectStride2(
        plan: S5VtmgTemporalProjectionPlan,
        input: FloatArray,
        positions: Int
    ): FloatArray

    fun projectUpsample2(
        plan: S5VtmgTemporalProjectionPlan,
        input: FloatArray,
        positions: Int
    ): FloatArray
}

/** Default Android executor backed by the recovered JNI/C++ S5 operators. */
object NativeS5VtmgGraphOperatorExecutor : S5VtmgGraphOperatorExecutor {
    override fun projectBanked(
        plan: S5VtmgBankedProjectionPlan, input: FloatArray, positions: Int
    ): FloatArray = plan.project(input, positions)

    override fun projectStride2(
        plan: S5VtmgTemporalProjectionPlan, input: FloatArray, positions: Int
    ): FloatArray = plan.projectStride2(input, positions)

    override fun projectUpsample2(
        plan: S5VtmgTemporalProjectionPlan, input: FloatArray, positions: Int
    ): FloatArray = plan.projectUpsample2(input, positions)
}
/**
 * A name-matched convolution/conditioning pair from VOCALOID7.vtmg.
 *
 * The shared model contains conv1/cond1, conv2/cond2 and conv3/cond3.
 * This pair keeps their shared ordinal and output width together, but does
 * not assert that the pair maps to a particular c5440/c6390/c72d0 descriptor
 * or reverse-stage index in FUN_1800c7ad0.
 */
data class S5VtmgConditionalConvolutionPairPlan(
    val ordinal: Int,
    val convolution: S5VtmgRendererProjectionPlan,
    val conditioning: S5VtmgBankedProjectionPlan
) {
    init {
        require(ordinal in 1..3)
        require(convolution.layerName == "conv" + ordinal)
        require(convolution.banked.banks == 3)
        require(convolution.temporal.kernelWidth == 3)
        require(conditioning.inputFeatures == S5VtmgMegpoidGraphPlan.CONDITION_FEATURES)
        require(conditioning.outputFeatures == convolution.banked.outputFeatures)
        require(conditioning.banks == 1)
    }

    val convolutionTemporal: S5VtmgTemporalProjectionPlan
        get() = convolution.temporal

    val convolutionBanked: S5VtmgBankedProjectionPlan
        get() = convolution.banked
}

/**
 * Fully decoded and layout-prepared VTMG parameter set for the Megpoid base
 * graph. This is not a forward executor: recurrent/conditioning activation
 * order, descriptor-index binding, spectral synthesis and PCM reconstruction
 * are still separate integration stages.
 */
data class S5VtmgMegpoidGraphPlan(
    val pre1: S5VtmgBankedProjectionPlan,
    val pre2: S5VtmgBankedProjectionPlan,
    val convolutionPairs: List<S5VtmgConditionalConvolutionPairPlan>,
    val linear: S5VtmgBankedProjectionPlan
) {
    init {
        require(pre1.inputFeatures == S5VtmgMegpoidGraphPlan.PHONEME_FEATURES)
        require(pre1.outputFeatures == S5VtmgMegpoidGraphPlan.HIDDEN_FEATURES)
        require(pre1.banks == 1)
        require(pre2.inputFeatures == S5VtmgMegpoidGraphPlan.HIDDEN_FEATURES)
        require(pre2.outputFeatures == S5VtmgMegpoidGraphPlan.HIDDEN_FEATURES)
        require(pre2.banks == 1)
        require(convolutionPairs.map { it.ordinal } == listOf(1, 2, 3))
        require(convolutionPairs.all {
            it.convolution.banked.inputFeatures == HIDDEN_FEATURES &&
                it.convolution.banked.outputFeatures == CONDITIONED_FEATURES &&
                it.conditioning.outputFeatures == CONDITIONED_FEATURES
        })
        require(linear.inputFeatures == HIDDEN_FEATURES)
        require(linear.outputFeatures == OUTPUT_FEATURES)
        require(linear.banks == 1)
    }

    /** Runs the named 82-to-32 pre-projection node. */
    fun projectPre1(
        input: FloatArray,
        positions: Int,
        executor: S5VtmgGraphOperatorExecutor = NativeS5VtmgGraphOperatorExecutor
    ): FloatArray = executor.projectBanked(pre1, input, positions)

    /** Runs the named 32-to-32 pre-projection node. */
    fun projectPre2(
        input: FloatArray,
        positions: Int,
        executor: S5VtmgGraphOperatorExecutor = NativeS5VtmgGraphOperatorExecutor
    ): FloatArray = executor.projectBanked(pre2, input, positions)

    /** Runs one name-matched 26-to-64 conditioning node independently. */
    fun projectConditioning(
        ordinal: Int,
        input: FloatArray,
        positions: Int,
        executor: S5VtmgGraphOperatorExecutor = NativeS5VtmgGraphOperatorExecutor
    ): FloatArray = executor.projectBanked(
        requireConvolutionPair(ordinal).conditioning, input, positions
    )

    /**
     * Runs one convN node with an explicit operator selection. The selected
     * S5API operator must come from the recovered descriptor setup; this
     * method intentionally does not infer it from the layer suffix.
     */
    fun projectConvolution(
        ordinal: Int,
        operator: S5RendererProjectionOperator,
        input: FloatArray,
        positions: Int,
        executor: S5VtmgGraphOperatorExecutor = NativeS5VtmgGraphOperatorExecutor
    ): FloatArray {
        val temporal = requireConvolutionPair(ordinal).convolution.temporal
        return when (operator) {
            S5RendererProjectionOperator.STRIDE2_CONV1D_C5440 ->
                executor.projectStride2(temporal, input, positions)
            S5RendererProjectionOperator.BANKED_LINEAR_C6390 ->
                executor.projectBanked(requireConvolutionPair(ordinal).convolution.banked, input, positions)
            S5RendererProjectionOperator.UPSAMPLE_2X_C72D0 ->
                executor.projectUpsample2(temporal, input, positions)
        }
    }

    /** Runs the final named 32-to-3 linear node independently. */
    fun projectLinear(
        input: FloatArray,
        positions: Int,
        executor: S5VtmgGraphOperatorExecutor = NativeS5VtmgGraphOperatorExecutor
    ): FloatArray = executor.projectBanked(linear, input, positions)
    fun requireConvolutionPair(ordinal: Int): S5VtmgConditionalConvolutionPairPlan =
        convolutionPairs.singleOrNull { it.ordinal == ordinal }
            ?: throw IllegalArgumentException("No VTMG conv/cond pair with ordinal " + ordinal + ".")

    /** All nine original layer names represented by this plan. */
    val layerNames: Set<String>
        get() = buildSet {
            add("pre1")
            add("pre2")
            convolutionPairs.forEach {
                add(it.convolution.layerName)
                add("cond" + it.ordinal)
            }
            add("linear")
        }

    companion object {
        const val PHONEME_FEATURES = 82
        const val HIDDEN_FEATURES = 32
        const val CONDITION_FEATURES = 26
        const val CONDITIONED_FEATURES = 64
        const val OUTPUT_FEATURES = 3
    }
}

/**
 * Converts all nine packed VTMG layers into the operator layouts required by
 * the recovered S5 primitives. The converter is injectable for JVM tests;
 * Android production uses NativeS5TensorBridge.
 */
object S5VtmgMegpoidGraphPlanBuilder {
    fun build(
        packed: S5VtmgPackedBaseGraphWeights,
        convertBanked: (FloatArray, Int, Int, Int, Int) -> FloatArray =
            NativeS5TensorBridge::convertVtmgBankWeightsForV0Naive
    ): S5VtmgMegpoidGraphPlan {
        val expectedNames = S5VtmgGraphTensorCatalog.layers.map { it.layerName }.toSet()
        val providedNames = packed.layers.map { it.layerName }
        require(providedNames.size == expectedNames.size && providedNames.toSet() == expectedNames) {
            "The packed VTMG graph does not contain exactly one of each of the nine Megpoid layers."
        }

        val pre1 = packed.requireLayer("pre1").toBankedProjectionPlan(convertBanked)
        val pre2 = packed.requireLayer("pre2").toBankedProjectionPlan(convertBanked)
        val pairs = (1..3).map { ordinal ->
            val convolution = packed.requireLayer("conv" + ordinal)
                .toRendererProjectionPlan(convertBanked)
            val conditioning = packed.requireLayer("cond" + ordinal)
                .toBankedProjectionPlan(convertBanked)
            S5VtmgConditionalConvolutionPairPlan(
                ordinal = ordinal,
                convolution = convolution,
                conditioning = conditioning
            )
        }
        val linear = packed.requireLayer("linear").toBankedProjectionPlan(convertBanked)

        return S5VtmgMegpoidGraphPlan(
            pre1 = pre1,
            pre2 = pre2,
            convolutionPairs = pairs,
            linear = linear
        )
    }
}
