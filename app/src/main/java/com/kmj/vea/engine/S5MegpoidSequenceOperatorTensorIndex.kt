package com.kmj.vea.engine

import java.util.Base64

enum class S5MegpoidSequenceOperatorFactory(val nativeFunction: String, val role: String) {
    TEMPORAL_BIAS("FUN_1800e76c0", "Temporal optional bias"),
    TEMPORAL_WEIGHTS("FUN_1800e8620", "Temporal weights"),
    BANKED_LINEAR_BIAS("FUN_180122880", "Banked-linear optional bias"),
    BANKED_LINEAR_WEIGHTS("FUN_1801256a0", "Banked-linear weights"),
    ALTERNATE_BIAS("FUN_1801237e0", "Alternate transition bias"),
    ALTERNATE_WEIGHTS("FUN_180124740", "Alternate transition weights")
}

data class S5MegpoidSequenceOperatorFactoryKey(
    val factory: S5MegpoidSequenceOperatorFactory,
    val sequenceIndex: Int,
    val lookupKey: String
) {
    init {
        require(sequenceIndex in 0 until TABLE_SIZE)
        require(lookupKey.matches(Regex("[0-9a-f]{32}")))
    }
    companion object { const val TABLE_SIZE = 16 }
}

/** Exact sixteen-entry lookup-key tables extracted from the six native factory initializers. */
object S5MegpoidSequenceOperatorFactoryKeys {
    private val tables = mapOf(
        S5MegpoidSequenceOperatorFactory.TEMPORAL_BIAS to listOf(
            "c3d133918cd850510369fb60a88ef3f2","2af1e6a511f0619d0663d5839c3d765a",
            "18376bdfab9e577289bf3e2035c12e24","179a1cb37202fb120b1555be4d797185",
            "ffea7d35aa23f5e8976b26e09c7ea3fe","e4a4f0f5761065c848e7e3c124345a77",
            "5acc4ccef4b05c234506f04cf8f404da","76359804750301207e3215a73dfbc074",
            "f431adccf1fddb614a576bbe75b06ec4","4b41b9673b0bfdcc83faf1ee45eaa18f",
            "a7ccd7a8c76a3f4cb2a831f1537b5e0c","e5b92babb81037bbee9c1b72e97155f9",
            "ac91a0cc172e07a20a8a1e13299f2c2f","5d2eaeb233dd112c08d12545a777dec3",
            "fcf2464a6650e9d3c0fe33b767fd223e","821660b77266b923c6f26cf0a34f55db"
        ),
        S5MegpoidSequenceOperatorFactory.TEMPORAL_WEIGHTS to listOf(
            "df046af48cc4ee1a7c4e19bf5ca56393","71e41c862d3bbf03ea35c2d79c6c155a",
            "d118775fc1f3a7dfdacb4a0152c90a71","e12f7c28a4db587896d105aaaa3cb55b",
            "6c475eddcc7ab43c06224855b7a1bc44","1694e9ab2ff32fa789f876b47cdea883",
            "8cd757fa04f14a77b080c9fc95472b62","1311e34b638667d7e31e90dc575b7016",
            "0dd99fb7d95826757b1229d6339ed98f","368e62f30e010db4a4484294cbd7cd3f",
            "55db9d8e4ed3e2515229febc7674b6c3","82d4321bfdcf7cab83dd0f290000d2f4",
            "0356b696aafde17c3c3fdaa4d546f755","933df01c5d9fc66010d911a13b8100c9",
            "180b53a1ff08da49f472d0be416ef6bd","aee6601962cbe352017bd1523253fcde"
        ),
        S5MegpoidSequenceOperatorFactory.BANKED_LINEAR_BIAS to listOf(
            "2afb60a53c85da8f2b1430e47c9409cb","7c41db20ea76dfa768ff5575a5780a34",
            "2a2b3911ef2f67f4b349fbeb307100b8","ce03250b929cc0d0ae9dcc926b840d61",
            "b5f04200ee07f5308dab46e05585c86e","992b4674aa9c668b339ed98188a12068",
            "447c4f02a6b2e8c178117713fd93e1e6","a5e01030ac4b71f76361c63cd14f15b4",
            "b929aac95005eed9b5c49164e94209f0","d405a532fa1294d018c8d8af12cbdbce",
            "1c7183f752e45e98b716730a2e6450a7","e6b1ef3233a3804e357915139d027464",
            "aff7065ca9890bf40f9863d144616f4a","d35a8d28b433295a180702dad487fd88",
            "d1cc15b9e9583f768cb58d68770716d8","25f5cf113cf54824d173103d2714ec47"
        ),
        S5MegpoidSequenceOperatorFactory.BANKED_LINEAR_WEIGHTS to listOf(
            "650c34b31190b6797bb78a3bdf852de4","dcf177eb8872f1c2ba4e6e694ae06acd",
            "fafd6be5bd78c9f48baf54480ad2a287","c671ddfbe86c95e6e6a00683dc98c6ce",
            "312296f651f2953b5ad20d4be8b2846c","3e0a1e7dea365926c123ad618bdf4312",
            "f97e5f4cddc11f4bb6d22ac61f5c2a68","a37c794ee74490b6b90f3a8786048cd1",
            "9ffaf2fea54dc2dc01c65a851cb4d65b","a67cba900c2243577909fe9b08283446",
            "2aa189d3720d0313978dea2d92db5319","cea75ab04eef6e5e8019c226510eebf7",
            "8b2be9d2e52691fdfaa65cfe5868856d","eb748a2fb483ddbe2cbaf8231d008166",
            "eae6cf094ce72d8dc65b47837626819c","6ed385ecca338448577e59be9b9b97de"
        ),
        S5MegpoidSequenceOperatorFactory.ALTERNATE_BIAS to listOf(
            "c421b5b1e692490d409f4d207f0bc0af","ec05f600a222ac4dc04f61ea42c1c7fd",
            "6adc7ef02d1f4145b8f9462c6e055468","a28d17f7ef315ce48370f4c3e289eb27",
            "60ea688b6be1c5b562739afd79cde34b","8ca4c8af84361905dec3ccdd351ae2a1",
            "d6e64ba311162bfeb053509a4d43b7d6","76a2e1ac6dc49bd3c67ee60ea2164823",
            "5a6cfede34022d460ef6af4a7b0b6159","93f398ab6a366448d31e673d0a75a21e",
            "c45d54c4e17a77f5ae444b43a97a41cd","2a6f68892ca0600ecd3da58d5c56a0d3",
            "9c40cc3af4169f0719a0c5bcbed1af10","d9e069b75f6242de2db2b9c1f4e8e75f",
            "115697d72b5c6451f5468efdfbd91430","ca4d39803fedc22fa47e33d438a5a9dd"
        ),
        S5MegpoidSequenceOperatorFactory.ALTERNATE_WEIGHTS to listOf(
            "ec08ca0fc855e98713bf187c2f512695","2a108b083af585a47e2e2fa02ca2ed3c",
            "7dde778a3aef086e3f200391131a03aa","3258db4d35ef43df3014b446712503bd",
            "49a1ea71d7cced3b8484a670a5ce577f","0f3cdd87392c8574947a1dd37821cbe7",
            "0663e9172cb93d45f8fec6030603e8ca","f52e8e26b202dfe4150561c6cef29cd1",
            "2e713705de48b49e9766f6c27a79b062","d03474c72f1e4edbf7935ec0e5047ef0",
            "feadf3a34ca06b82bcf5ea9ba1e812f3","314a945c383057f02b4542b01b88972d",
            "1ef1bb3a4e6bbb8af57e4c4a99ce85db","29fc29dd81b6596e8ff67ac9999e82b4",
            "30f636a1f2dcbd62cc3c34baba0ba868","9a8a40b8133a8e21399a9b76d5e834c3"
        )
    )

    fun lookupKey(factory: S5MegpoidSequenceOperatorFactory, index: Int): String {
        require(index in 0 until S5MegpoidSequenceOperatorFactoryKey.TABLE_SIZE)
        return tables.getValue(factory)[index]
    }
    fun keys(factory: S5MegpoidSequenceOperatorFactory) =
        tables.getValue(factory).mapIndexed { index, value ->
            S5MegpoidSequenceOperatorFactoryKey(factory, index, value)
        }
    fun all() = S5MegpoidSequenceOperatorFactory.entries.flatMap(::keys)
}

class S5MegpoidSequenceOperatorTensorReference internal constructor(
    val factoryKey: S5MegpoidSequenceOperatorFactoryKey,
    val objectPath: String,
    val marker: String,
    /** Exact model metadata dimensions; empty only for legacy unshaped fixtures. */
    val descriptorMetadata: List<Int>,
    val elementCount: Int,
    private val encodedValue: S5ModelValue.StringValue
) {
    fun decodeFloatArray(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): FloatArray {
        val decoded = encodedValue.asFloatArray(tensorDecoder)
        check(decoded.size == elementCount)
        return decoded
    }
}

data class S5MegpoidAlternateTransitionTensorPair(
    val weights: S5MegpoidSequenceOperatorTensorReference,
    val bias: S5MegpoidSequenceOperatorTensorReference
)

data class S5MegpoidPreparedAlternateTransition(
    val sequenceIndex: Int,
    val weightMetadata: List<Int>,
    val biasMetadata: List<Int>,
    val weights: FloatArray,
    val bias: FloatArray
) {
    init {
        require(sequenceIndex in 0..2)
        require(weightMetadata == listOf(32, 32, 3, 1))
        require(biasMetadata == listOf(32))
        require(weights.size == 32 * 32 * 3)
        require(bias.size == 32)
        require(weights.all { it.isFinite() } && bias.all { it.isFinite() })
    }

    fun copyWeights(): FloatArray = weights.copyOf()
    fun copyBias(): FloatArray = bias.copyOf()
}

/**
 * Recursive index for sequence-indexed S5API model properties.
 * The active sequence count and all operator dimensions remain render metadata.
 */
class S5MegpoidSequenceOperatorTensorIndex private constructor(
    private val tensors: Map<S5MegpoidSequenceOperatorFactory, Map<Int, S5MegpoidSequenceOperatorTensorReference>>
) {
    fun tensorOrNull(factory: S5MegpoidSequenceOperatorFactory, index: Int): S5MegpoidSequenceOperatorTensorReference? {
        require(index in 0 until S5MegpoidSequenceOperatorFactoryKey.TABLE_SIZE)
        return tensors[factory]?.get(index)
    }

    fun requireTensor(factory: S5MegpoidSequenceOperatorFactory, index: Int): S5MegpoidSequenceOperatorTensorReference =
        tensorOrNull(factory, index) ?: throw IllegalArgumentException(
            "Missing " + factory.nativeFunction + " property for sequence index " + index +
                " (key " + S5MegpoidSequenceOperatorFactoryKeys.lookupKey(factory, index) + ")."
        )

    fun availableIndices(factory: S5MegpoidSequenceOperatorFactory): Set<Int> =
        tensors[factory].orEmpty().keys

    fun buildTemporalProjection(
        index: Int, inputFeatures: Int, outputFeatures: Int, kernelSize: Int,
        biasEnabled: Boolean, decoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): S5MegpoidTemporalStridedConvolution {
        val weights = requireTensor(S5MegpoidSequenceOperatorFactory.TEMPORAL_WEIGHTS, index)
        val expected = checkedProduct(checkedProduct(inputFeatures, kernelSize), outputFeatures)
        require(weights.elementCount == expected) { "Temporal weight tensor shape mismatch." }
        val bias = if (biasEnabled) {
            val reference = requireTensor(S5MegpoidSequenceOperatorFactory.TEMPORAL_BIAS, index)
            require(reference.elementCount == outputFeatures) { "Temporal bias shape mismatch." }
            reference.decodeFloatArray(decoder)
        } else null
        return S5MegpoidTemporalStridedConvolution(
            inputFeatures, outputFeatures, kernelSize, weights.decodeFloatArray(decoder), bias
        )
    }

    fun buildBankedLinearProjection(
        index: Int, inputFeatures: Int, outputFeatures: Int, banks: Int,
        biasEnabled: Boolean, decoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): S5MegpoidBankedLinearProjection {
        val weights = requireTensor(S5MegpoidSequenceOperatorFactory.BANKED_LINEAR_WEIGHTS, index)
        val expected = checkedProduct(checkedProduct(inputFeatures, outputFeatures), banks)
        require(weights.elementCount == expected) { "Banked-linear weight tensor shape mismatch." }
        val bias = if (biasEnabled) {
            val reference = requireTensor(S5MegpoidSequenceOperatorFactory.BANKED_LINEAR_BIAS, index)
            require(reference.elementCount == outputFeatures) { "Banked-linear bias shape mismatch." }
            reference.decodeFloatArray(decoder)
        } else null
        return S5MegpoidBankedLinearProjection(
            inputFeatures, outputFeatures, banks, weights.decodeFloatArray(decoder), bias
        )
    }

    fun alternateTransitionPairOrNull(index: Int): S5MegpoidAlternateTransitionTensorPair? {
        val weights = tensorOrNull(S5MegpoidSequenceOperatorFactory.ALTERNATE_WEIGHTS, index) ?: return null
        val bias = tensorOrNull(S5MegpoidSequenceOperatorFactory.ALTERNATE_BIAS, index) ?: return null
        return S5MegpoidAlternateTransitionTensorPair(weights, bias)
    }

    /** Available Alternate transition slots backed by both weights and bias tensors. */
    fun availableAlternateTransitionIndices(): Set<Int> {
        val weightIndices = availableIndices(S5MegpoidSequenceOperatorFactory.ALTERNATE_WEIGHTS)
        val biasIndices = availableIndices(S5MegpoidSequenceOperatorFactory.ALTERNATE_BIAS)
        return weightIndices.intersect(biasIndices)
    }

    /**
     * Requires the supplied AI Megpoid V7 Alternate tensor shape without
     * assigning an unverified arithmetic interpretation to its axes.
     */
    fun requireMegpoidV7AlternateTransitionPair(
        index: Int
    ): S5MegpoidAlternateTransitionTensorPair {
        val pair = alternateTransitionPairOrNull(index)
            ?: throw IllegalArgumentException(
                "AI Megpoid V7 has no complete Alternate transition pair at index $index."
            )
        require(pair.weights.descriptorMetadata == listOf(32, 32, 3, 1)) {
            "AI Megpoid V7 Alternate weights must retain native metadata [32,32,3,1]."
        }
        require(pair.bias.descriptorMetadata == listOf(32)) {
            "AI Megpoid V7 Alternate bias must retain native metadata [32]."
        }
        require(pair.weights.elementCount == 32 * 32 * 3 && pair.bias.elementCount == 32) {
            "AI Megpoid V7 Alternate transition payload count does not match its native metadata."
        }
        return pair
    }

    /** Requires the three populated transition pairs used by the four-slot Megpoid V7 graph. */
    fun requireMegpoidV7AlternateTransitionSet(): List<S5MegpoidAlternateTransitionTensorPair> {
        val expectedIndices = setOf(0, 1, 2)
        val actualIndices = availableAlternateTransitionIndices()
        require(actualIndices == expectedIndices) {
            "AI Megpoid V7 Alternate transition indices must be $expectedIndices, but were $actualIndices."
        }
        return expectedIndices.sorted().map(::requireMegpoidV7AlternateTransitionPair)
    }

    /**
     * Decodes the exact three Megpoid Alternate tensor pairs while preserving
     * their original metadata. No operator math or axis reinterpretation is
     * performed here.
     */
    fun decodeMegpoidV7AlternateTransitionSet(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): List<S5MegpoidPreparedAlternateTransition> =
        requireMegpoidV7AlternateTransitionSet().mapIndexed { index, pair ->
            S5MegpoidPreparedAlternateTransition(
                sequenceIndex = index,
                weightMetadata = pair.weights.descriptorMetadata.toList(),
                biasMetadata = pair.bias.descriptorMetadata.toList(),
                weights = pair.weights.decodeFloatArray(tensorDecoder),
                bias = pair.bias.decodeFloatArray(tensorDecoder)
            )
        }

    companion object {
        fun build(tree: S5ModelObjectTree): S5MegpoidSequenceOperatorTensorIndex {
            val entriesByKey = S5MegpoidSequenceOperatorFactoryKeys.all().groupBy { it.lookupKey }
            data class TensorLeaf(
                val path: String,
                val value: S5ModelValue.StringValue,
                val metadata: List<Int>
            )
            val leavesByKey = mutableMapOf<String, MutableList<TensorLeaf>>()

            fun parseMetadata(value: S5ModelValue?): List<Int> {
                val array = value as? S5ModelValue.ArrayValue ?: return emptyList()
                return array.values.mapIndexed { index, item ->
                    val number = (item as? S5ModelValue.NumberValue)?.value
                        ?: throw IllegalArgumentException(
                            "Sequence tensor metadata contains a non-numeric dimension at $index."
                        )
                    require(number.isFinite() && number % 1.0 == 0.0 &&
                        number in 1.0..Int.MAX_VALUE.toDouble()) {
                        "Sequence tensor metadata contains an invalid dimension at $index."
                    }
                    number.toInt()
                }
            }

            fun collectLeaves(
                value: S5ModelValue,
                path: String,
                output: MutableList<TensorLeaf>,
                inheritedMetadata: List<Int> = emptyList()
            ) {
                when (value) {
                    is S5ModelValue.StringValue -> if (
                        value.value.startsWith("*F32") || value.value.startsWith("*F16")
                    ) {
                        output += TensorLeaf(path, value, inheritedMetadata)
                    }
                    is S5ModelValue.ObjectValue -> {
                        val localMetadata = parseMetadata(
                            value.fields[S5MegpoidCarrierFusionModelPath.SHAPE_METADATA_KEY]
                        ).ifEmpty { inheritedMetadata }
                        value.fields.forEach { (key, child) ->
                            collectLeaves(child, path + "/" + key, output, localMetadata)
                        }
                    }
                    is S5ModelValue.ArrayValue -> value.values.forEachIndexed { i, child ->
                        collectLeaves(child, path + "[" + i + "]", output, inheritedMetadata)
                    }
                    else -> Unit
                }
            }
            fun walk(value: S5ModelValue, path: String) {
                when (value) {
                    is S5ModelValue.ObjectValue -> value.fields.forEach { (key, child) ->
                        val childPath = path + "/" + key
                        if (entriesByKey.containsKey(key)) collectLeaves(child, childPath, leavesByKey.getOrPut(key) { mutableListOf() })
                        walk(child, childPath)
                    }
                    is S5ModelValue.ArrayValue -> value.values.forEachIndexed { i, child ->
                        walk(child, path + "[" + i + "]")
                    }
                    else -> Unit
                }
            }
            walk(tree.root, "$")

            val resolved = S5MegpoidSequenceOperatorFactory.entries.associateWith {
                mutableMapOf<Int, S5MegpoidSequenceOperatorTensorReference>()
            }
            for (entry in S5MegpoidSequenceOperatorFactoryKeys.all()) {
                val leaves = leavesByKey[entry.lookupKey].orEmpty()
                if (leaves.isEmpty()) continue
                require(leaves.size == 1) {
                    "Duplicate tensor leaves for " + entry.factory.nativeFunction + "[" + entry.sequenceIndex + "]."
                }
                val leaf = leaves.single()
                val encoded = leaf.value.value
                val marker = encoded.take(4)
                val body = encoded.substring(4)
                val bytes = try { Base64.getDecoder().decode(body).size }
                    catch (error: IllegalArgumentException) {
                        throw IllegalArgumentException("Malformed sequence tensor at " + leaf.path, error)
                    }
                val scalarBytes = if (marker == "*F16") 2 else 4
                require(bytes > 0 && bytes % scalarBytes == 0) {
                    "Invalid tensor alignment at " + leaf.path
                }
                val elementCount = bytes / scalarBytes
                if (leaf.metadata.isNotEmpty()) {
                    val metadataCount = leaf.metadata.fold(1L) { product, dimension ->
                        require(product <= Int.MAX_VALUE.toLong() / dimension.toLong()) {
                            "Sequence tensor metadata exceeds the supported tensor size at " + leaf.path
                        }
                        product * dimension.toLong()
                    }
                    require(metadataCount == elementCount.toLong()) {
                        "Sequence tensor metadata declares $metadataCount values but stores $elementCount at " +
                            leaf.path + "."
                    }
                }
                resolved.getValue(entry.factory)[entry.sequenceIndex] =
                    S5MegpoidSequenceOperatorTensorReference(
                        factoryKey = entry,
                        objectPath = leaf.path,
                        marker = marker,
                        descriptorMetadata = leaf.metadata,
                        elementCount = elementCount,
                        encodedValue = leaf.value
                    )
            }
            return S5MegpoidSequenceOperatorTensorIndex(resolved.mapValues { it.value.toMap() })
        }

        private fun checkedProduct(a: Int, b: Int): Int {
            val product = a.toLong() * b.toLong()
            require(a > 0 && b > 0 && product <= Int.MAX_VALUE)
            return product.toInt()
        }
    }
}

/** Reproduces FUN_1800d47c0: [input][output][bank] -> [bank][output][input]. */
class S5MegpoidBankedLinearProjection internal constructor(
    val inputFeatures: Int,
    val outputFeatures: Int,
    val banks: Int,
    sourceWeights: FloatArray,
    bias: FloatArray?
) {
    private val packedWeights: FloatArray
    private val biasValues: FloatArray?
    private val bankStride: Int

    init {
        val count = checkedProduct(checkedProduct(inputFeatures, outputFeatures), banks)
        require(sourceWeights.size == count)
        require(bias == null || bias.size == outputFeatures)
        bankStride = checkedProduct(inputFeatures, outputFeatures)
        packedWeights = FloatArray(count)
        for (bank in 0 until banks) for (out in 0 until outputFeatures) for (input in 0 until inputFeatures) {
            val src = (input * outputFeatures + out) * banks + bank
            val dst = bank * bankStride + out * inputFeatures + input
            packedWeights[dst] = sourceWeights[src]
        }
        biasValues = bias?.copyOf()
    }

    fun copyPackedWeights(): FloatArray = packedWeights.copyOf()
    fun copyBias(): FloatArray? = biasValues?.copyOf()

    fun project(input: FloatArray, positions: Int): FloatArray {
        require(positions > 0 && input.size == checkedProduct(inputFeatures, positions))
        return NativeS5TensorBridge.applyBankedLinearProjection(
            packedWeights, inputFeatures, bankStride, input, biasValues,
            inputFeatures, outputFeatures, positions, banks
        )
    }

    private fun checkedProduct(a: Int, b: Int): Int {
        val product = a.toLong() * b.toLong()
        require(a > 0 && b > 0 && product <= Int.MAX_VALUE)
        return product.toInt()
    }
}
