package com.kmj.vea.engine

import java.io.File

/**
 * Eight VTB2 tensor inputs passed by FUN_1800ced10 to FUN_1800d4bf0
 * for one (stage, block) pair.
 *
 * Recovered builder grouping:
 * - param_2: depthwise kernel, transposed into the descriptor's channel-first
 *   7x7 history layout;
 * - param_3/param_4: conditioning vectors A/B, stored by FUN_1800d4a90;
 * - param_5/param_6: expansion matrix and optional affine bias;
 * - param_7/param_8: projection matrix and optional affine bias;
 * - param_9: channel scale vector C.
 *
 * FUN_1800ced10 appends the resulting 0x100-byte descriptor to the
 * stage-major renderer vector at slot +0xb8 (byte offset 0x2e0).
 */
data class S5Vtb2C5d50StageBlockParameters(
    val stageIndex: Int,
    val blockIndex: Int,
    val depthwiseKernel: FloatArray,
    val blockVectorA: FloatArray,
    val blockVectorB: FloatArray,
    val expansionWeights: FloatArray,
    val expansionBias: FloatArray,
    val projectionWeights: FloatArray,
    val projectionBias: FloatArray,
    val blockVectorC: FloatArray
) {
    init {
        require(stageIndex in 0 until STAGE_COUNT)
        require(blockIndex in 0 until BLOCKS_PER_STAGE)
        require(depthwiseKernel.size == DEPTHWISE_VALUE_COUNT)
        require(blockVectorA.size == CHANNEL_COUNT)
        require(blockVectorB.size == CHANNEL_COUNT)
        require(expansionWeights.size == EXPANSION_WEIGHT_COUNT)
        require(expansionBias.size == EXPANDED_CHANNEL_COUNT)
        require(projectionWeights.size == PROJECTION_WEIGHT_COUNT)
        require(projectionBias.size == CHANNEL_COUNT)
        require(blockVectorC.size == CHANNEL_COUNT)
        require(
            depthwiseKernel.all { it.isFinite() } &&
                blockVectorA.all { it.isFinite() } &&
                blockVectorB.all { it.isFinite() } &&
                expansionWeights.all { it.isFinite() } &&
                expansionBias.all { it.isFinite() } &&
                projectionWeights.all { it.isFinite() } &&
                projectionBias.all { it.isFinite() } &&
                blockVectorC.all { it.isFinite() }
        ) {
            "c5d50 parameters at stage " + stageIndex + " block " + blockIndex +
                " contain a non-finite value."
        }
    }

    val floatCount: Int
        get() = depthwiseKernel.size +
            blockVectorA.size + blockVectorB.size +
            expansionWeights.size + expansionBias.size +
            projectionWeights.size + projectionBias.size +
            blockVectorC.size

    companion object {
        const val CHANNEL_COUNT = 32
        const val EXPANDED_CHANNEL_COUNT = 64
        const val DEPTHWISE_VALUE_COUNT = 32 * 7 * 7
        const val EXPANSION_WEIGHT_COUNT = 64 * 32
        const val PROJECTION_WEIGHT_COUNT = 32 * 64
        const val STAGE_COUNT = 4
        const val BLOCKS_PER_STAGE = 2
    }
}

data class S5Vtb2C5d50StageBlockParameterSet(
    val sampleRate: Int,
    val hopSize: Int,
    val stageCount: Int,
    val blocksPerStage: Int,
    val blocks: List<S5Vtb2C5d50StageBlockParameters>
) {
    init {
        require(sampleRate == EXPECTED_SAMPLE_RATE) {
            "VTB2 c5d50 sample rate is " + sampleRate + "; expected " + EXPECTED_SAMPLE_RATE + "."
        }
        require(hopSize == EXPECTED_HOP_SIZE) {
            "VTB2 c5d50 hop size is " + hopSize + "; expected " + EXPECTED_HOP_SIZE + "."
        }
        require(stageCount == S5Vtb2C5d50StageBlockParameters.STAGE_COUNT) {
            "VTB2 c5d50 stage count is " + stageCount + "; expected 4."
        }
        require(blocksPerStage == S5Vtb2C5d50StageBlockParameters.BLOCKS_PER_STAGE) {
            "VTB2 c5d50 blocks per stage is " + blocksPerStage + "; expected 2."
        }
        val expectedDescriptorCount = stageCount * blocksPerStage
        require(blocks.size == expectedDescriptorCount) {
            "VTB2 c5d50 config requires " + expectedDescriptorCount +
                " descriptors; decoded " + blocks.size + "."
        }
        val expectedOrder = (0 until stageCount).flatMap { stage ->
            (0 until blocksPerStage).map { block -> stage to block }
        }
        require(blocks.map { it.stageIndex to it.blockIndex } == expectedOrder) {
            "VTB2 c5d50 stage/block descriptors are missing or out of order."
        }
    }

    fun requireBlock(stageIndex: Int, blockIndex: Int): S5Vtb2C5d50StageBlockParameters =
        blocks.singleOrNull { it.stageIndex == stageIndex && it.blockIndex == blockIndex }
            ?: throw IllegalArgumentException(
                "No c5d50 VTB2 parameters for stage " + stageIndex + " block " + blockIndex + "."
            )

    val totalFloatCount: Long
        get() = blocks.sumOf { it.floatCount.toLong() }

    companion object {
        const val EXPECTED_SAMPLE_RATE = 32_000
        const val EXPECTED_HOP_SIZE = 320
    }
}

internal data class S5Vtb2C5d50StageBlockTensorKeys(
    val depthwiseKernel: String,
    val blockVectorA: String,
    val blockVectorB: String,
    val expansionWeights: String,
    val expansionBias: String,
    val projectionWeights: String,
    val projectionBias: String,
    val blockVectorC: String
)

/**
 * Loads the eight per-(stage, block) tensor inputs consumed by
 * FUN_1800ced10 -> FUN_1800d4bf0. Tensor lookups use complete JSON paths.
 */
object S5Vtb2C5d50StageBlockParameterLoader {
    const val ROOT_OBJECT_KEY = "5be3f5d6495f68286523e230e2d0d9a4"
    const val CONFIG_WRAPPER_KEY = "154c806ba4d274fad9c7484acdc1779e"
    const val RENDER_CONFIG_KEY = "a4ba655a061e6151f80574e6088492a0"
    const val STAGE_BLOCK_CONFIG_KEY = "43726e4ce752ea6cc300dc2fca3e8372"

    const val HOP_SIZE_KEY = "591a7b0ec57fd6f02c56b9f281cd5484"
    const val SAMPLE_RATE_KEY = "3ddd265ebb678e8eb166c668d192730c"
    const val STAGE_COUNT_KEY = "dc077a67cada0b3a08bcf186cf933f04"
    const val BLOCKS_PER_STAGE_KEY = "e24517240ee66c01234cb4511909ebe6"

    const val DIMENSIONS_FIELD_KEY = "4ba18a32e4fa1a2f3b293713692a3a2e"
    const val TENSOR_FIELD_KEY = "0ed3f4c15ca6d021d59b2b1038226a7f"

    internal val stageBlockKeys: List<List<S5Vtb2C5d50StageBlockTensorKeys>> = listOf(
        listOf(
            S5Vtb2C5d50StageBlockTensorKeys(
                depthwiseKernel = "8abf41425bad2554193a661080753070",
                blockVectorA = "36dd5b75d38a5b3dfe3805db6cddb63d",
                blockVectorB = "778daaef71320c69cab546db59bafbf3",
                expansionWeights = "2be3c647a25538e9536d6757202f82c4",
                expansionBias = "135887eb4ccd6fe76c02f50cd4136bfe",
                projectionWeights = "95163c06f04036f8bc060bc46f080a2b",
                projectionBias = "5fbd15e46a32df16513dd0a946c7e441",
                blockVectorC = "64bd498ceb9692194e89336e38535fbb"
            ),
            S5Vtb2C5d50StageBlockTensorKeys(
                depthwiseKernel = "fbaf01274f7d7328e85fe2606636e54a",
                blockVectorA = "4146a9890f80b0e466efaad297040ace",
                blockVectorB = "36be567ee41552defe67342665462a18",
                expansionWeights = "de282d6c923b674399f1c1c72c628baf",
                expansionBias = "331bcb363ac37635e09bbf49465fce07",
                projectionWeights = "6aea11b3bfb6872be87637dbb3d565ec",
                projectionBias = "8f07de84219e72944da87cec6ed1e329",
                blockVectorC = "618d5833c02b39af0ca94f3e179a6e27"
            )
        ),
        listOf(
            S5Vtb2C5d50StageBlockTensorKeys(
                depthwiseKernel = "78ab983f61c5c58ff84d45113026ad8a",
                blockVectorA = "0e97b335eca2d346c757e3410fb4180c",
                blockVectorB = "c4f5f1d08c007b8c3163b38fbe6d7411",
                expansionWeights = "b1465ea6660173b4a49fc88d6cebf333",
                expansionBias = "7674c8681480dc34260d17042649238c",
                projectionWeights = "67372791373089d17d460a7bd030ec1e",
                projectionBias = "ae16d33318e89dcea06e6fbf0d160163",
                blockVectorC = "8afa9aa13ccaebc07385c3bcf1d05a46"
            ),
            S5Vtb2C5d50StageBlockTensorKeys(
                depthwiseKernel = "0f551cb9b37ff307437be80b2f80961d",
                blockVectorA = "7517590eeef10c40c4ee0e48e597f3fd",
                blockVectorB = "e39a3c39836a1e0faf59da6783841ea0",
                expansionWeights = "a15cd1ac0be5abbba27a53fe03786820",
                expansionBias = "bd81ba739a9e44aee0ddfeb881be711a",
                projectionWeights = "f0d02899d01cfb0efd5f2e6a06a550ad",
                projectionBias = "3b47b1151b78baefc8e68dbfb09eb3a1",
                blockVectorC = "2b773c5e9c68efefbd4972040f84f0c8"
            )
        ),
        listOf(
            S5Vtb2C5d50StageBlockTensorKeys(
                depthwiseKernel = "771075dad8a161731f1ec7849ffccccc",
                blockVectorA = "15b41b328bb4aa3f2a8935e4a270407f",
                blockVectorB = "5c25c8dd335e1dbe89db3e62d15a5955",
                expansionWeights = "207f5586069747df3328b61d143fd74c",
                expansionBias = "3d4183d6d0af36f1a05079ae5bacf5c4",
                projectionWeights = "aec7b5500e3262add4f57ee5de9abe7d",
                projectionBias = "15270cd102eae9cca7e434d40d1c2472",
                blockVectorC = "13c0375847aa6ad9788a73cf71a4dade"
            ),
            S5Vtb2C5d50StageBlockTensorKeys(
                depthwiseKernel = "0beaee9f704f4ac85d8f87a10cfeadc1",
                blockVectorA = "0c5abbc7260dc62397be78aa9d085e1f",
                blockVectorB = "8290915c5ce891220d1c05dd9f1721a5",
                expansionWeights = "633af40060c0bd27f5637d7a495522a6",
                expansionBias = "7fef04e53887aedced3b65f9ba776f9d",
                projectionWeights = "c8f5f8fcf6264e899af565d76ec8f306",
                projectionBias = "6c727933c8fafba4e41da5f80547c8e6",
                blockVectorC = "42be1806ecd6ed716e3ae096f27e6b4a"
            )
        ),
        listOf(
            S5Vtb2C5d50StageBlockTensorKeys(
                depthwiseKernel = "b639196457944060e60e66b234e2a5e1",
                blockVectorA = "9b88ad439704ac9bd8863c32b5f7df02",
                blockVectorB = "550ad26a29b9ca4ea07fe88dd6da8c16",
                expansionWeights = "763f264fbcf91eb0ee4f198e3c7668f4",
                expansionBias = "b890f396f8087a6cb069b6a16e404d4f",
                projectionWeights = "cc871bacb5c0b026f2506cafe3c8b361",
                projectionBias = "018084cc55995b2f418513aa90421227",
                blockVectorC = "41d59043f468b3a742e8e31cd79cfd27"
            ),
            S5Vtb2C5d50StageBlockTensorKeys(
                depthwiseKernel = "3294d2ee186d6fedf65089d86b78b302",
                blockVectorA = "1e8f2ad78e8a5d82d80d0f82bcfae5cf",
                blockVectorB = "e2bb98d17af85162fef6dcdbec612d11",
                expansionWeights = "224d0c06603f0018052fa9e634fafde1",
                expansionBias = "66492256d7c08ca02bbbf74b3faae62c",
                projectionWeights = "56fb99ecd4f79c9224e79e9a6cb258f4",
                projectionBias = "51a35fc1c5b62c6d6f7acb326c75d44a",
                blockVectorC = "21c0b1a1c0c2eab11a722f618343e9db"
            )
        )
    )

    fun load(
        vtb2File: File,
        decodeFloatArray: (String) -> FloatArray = NativeS5TensorBridge::decodeTypedFloatArray,
        decodeInt32Array: (String) -> IntArray = NativeS5TensorBridge::decodeTypedInt32Array
    ): S5Vtb2C5d50StageBlockParameterSet {
        require(vtb2File.isFile) { "The Megpoid VTB2 resource does not exist." }
        check(NativeS5TensorBridge.isLoaded) {
            "The native S5API container and typed-tensor decoders are not loaded."
        }
        val scan = S5ModelContainerScanner.scanVtb2ForObjectKeys(
            vtb2File,
            setOf(ROOT_OBJECT_KEY)
        )
        return decode(scan, decodeFloatArray, decodeInt32Array)
    }

    fun decode(
        scan: S5ModelJsonScanResult,
        decodeFloatArray: (String) -> FloatArray,
        decodeInt32Array: (String) -> IntArray = {
            throw IllegalArgumentException("The c5d50 stage/block tensors must use float32.")
        }
    ): S5Vtb2C5d50StageBlockParameterSet {
        require(ROOT_OBJECT_KEY in scan.matchedObjectKeys) {
            "The VTB2 shared renderer configuration object was not found."
        }

        val countPrefix = "$.$ROOT_OBJECT_KEY.$CONFIG_WRAPPER_KEY.$RENDER_CONFIG_KEY"
        val configValues = scan.matchedValues[ROOT_OBJECT_KEY].orEmpty()

        fun requireInt(path: String, expected: Int): Int {
            val matchedValue = configValues.singleOrNull { it.valuePath == path }
                ?: throw IllegalArgumentException("Missing or duplicate c5d50 renderer config value '$path'.")
            require(matchedValue.kind == S5JsonScalarKind.NUMBER) {
                "c5d50 renderer config '$path' must be numeric."
            }
            val value = matchedValue.value?.toIntOrNull()
                ?: throw IllegalArgumentException("Invalid integer c5d50 config value at '$path'.")
            require(value == expected) {
                "c5d50 renderer config '$path' declares " + value + "; expected " + expected + "."
            }
            return value
        }

        val sampleRate = requireInt(
            "$countPrefix.$SAMPLE_RATE_KEY",
            S5Vtb2C5d50StageBlockParameterSet.EXPECTED_SAMPLE_RATE
        )
        val hopSize = requireInt(
            "$countPrefix.$HOP_SIZE_KEY",
            S5Vtb2C5d50StageBlockParameterSet.EXPECTED_HOP_SIZE
        )
        val stageCount = requireInt(
            "$countPrefix.$STAGE_COUNT_KEY",
            S5Vtb2C5d50StageBlockParameters.STAGE_COUNT
        )
        val blockCount = requireInt(
            "$countPrefix.$BLOCKS_PER_STAGE_KEY",
            S5Vtb2C5d50StageBlockParameters.BLOCKS_PER_STAGE
        )

        val tensorRootPrefix = "$.$ROOT_OBJECT_KEY.$CONFIG_WRAPPER_KEY.$STAGE_BLOCK_CONFIG_KEY"
        val tensorMatchesByPath = scan.matchedTensorStrings[ROOT_OBJECT_KEY].orEmpty()
            .groupBy { it.valuePath }
        val duplicateTensorPaths = tensorMatchesByPath.filterValues { it.size != 1 }.keys
        require(duplicateTensorPaths.isEmpty()) {
            "VTB2 c5d50 config contains duplicate tensor paths: " +
                duplicateTensorPaths.sorted().joinToString() + "."
        }
        val tensorMatches = tensorMatchesByPath.mapValues { it.value.single() }
        val values = scan.matchedValues[ROOT_OBJECT_KEY].orEmpty()
        val stageBlockPrefix = "$tensorRootPrefix."
        val dimensionScalarValues = values.filter {
            it.valuePath.startsWith(stageBlockPrefix) &&
                it.valuePath.contains(".$DIMENSIONS_FIELD_KEY[")
        }

        fun decodeTensor(
            key: String,
            expectedDimensions: List<Int>,
            label: String,
            stage: Int,
            block: Int
        ): FloatArray {
            val objectPath = "$tensorRootPrefix.$key"
            val dimensionPath = "$objectPath.$DIMENSIONS_FIELD_KEY"
            val dimensionMatches = dimensionScalarValues.filter {
                it.valuePath.startsWith("$dimensionPath[")
            }
            require(dimensionMatches.size == expectedDimensions.size) {
                label + " dimensions for stage " + stage + " block " + block +
                    " are missing or malformed."
            }
            val actualDimensions = dimensionMatches.map { matchedValue ->
                val index = matchedValue.valuePath.substringAfterLast('[')
                    .substringBefore(']').toIntOrNull()
                    ?: throw IllegalArgumentException(label + " has a malformed dimension index.")
                require(matchedValue.kind == S5JsonScalarKind.NUMBER) {
                    label + " dimension at index " + index + " is not numeric."
                }
                val dimension = matchedValue.value?.toIntOrNull()
                    ?: throw IllegalArgumentException(label + " has a non-integer dimension.")
                index to dimension
            }.sortedBy { it.first }
            require(actualDimensions.map { it.first } == expectedDimensions.indices.toList()) {
                label + " dimensions for stage " + stage + " block " + block + " have missing indices."
            }
            val dimensions = actualDimensions.map { it.second }
            require(dimensions == expectedDimensions) {
                label + " dimensions for stage " + stage + " block " + block +
                    " are " + dimensions + "; expected " + expectedDimensions + "."
            }

            val tensorPath = "$objectPath.$TENSOR_FIELD_KEY"
            val tensorMatch = tensorMatches[tensorPath]
                ?: throw IllegalArgumentException(
                    "Missing " + label + " tensor for stage " + stage + " block " + block + "."
                )
            require(tensorMatch.typeTag == "*F32") {
                label + " tensor for stage " + stage + " block " + block +
                    " uses " + tensorMatch.typeTag + "; expected *F32."
            }
            val decoded = S5ModelTensorDecoder.decodeMatches(
                matches = listOf(tensorMatch),
                decodeFloatArray = decodeFloatArray,
                decodeInt32Array = decodeInt32Array
            ).single()
            require(decoded.kind == S5DecodedTensorKind.FLOAT32) {
                label + " tensor for stage " + stage + " block " + block + " is not F32."
            }
            val floats = requireNotNull(decoded.floatValues)
            val expectedCount = expectedDimensions.fold(1L) { total, size -> total * size.toLong() }
            require(floats.size.toLong() == expectedCount) {
                label + " tensor for stage " + stage + " block " + block +
                    " contains " + floats.size + " values; expected " + expectedCount + "."
            }
            require(floats.all { it.isFinite() }) {
                label + " tensor for stage " + stage + " block " + block + " contains a non-finite value."
            }
            return floats
        }

        val blocks = buildList {
            for (stage in 0 until stageCount) {
                for (block in 0 until blockCount) {
                    val keys = stageBlockKeys[stage][block]
                    add(
                        S5Vtb2C5d50StageBlockParameters(
                            stageIndex = stage,
                            blockIndex = block,
                            depthwiseKernel = decodeTensor(
                                keys.depthwiseKernel, listOf(32, 1, 7, 7), "depthwise", stage, block
                            ),
                            blockVectorA = decodeTensor(keys.blockVectorA, listOf(32), "block vector A", stage, block),
                            blockVectorB = decodeTensor(keys.blockVectorB, listOf(32), "block vector B", stage, block),
                            expansionWeights = decodeTensor(keys.expansionWeights, listOf(64, 32, 1, 1), "expansion weights", stage, block),
                            expansionBias = decodeTensor(keys.expansionBias, listOf(64), "expansion bias", stage, block),
                            projectionWeights = decodeTensor(keys.projectionWeights, listOf(32, 64, 1, 1), "projection weights", stage, block),
                            projectionBias = decodeTensor(keys.projectionBias, listOf(32), "projection bias", stage, block),
                            blockVectorC = decodeTensor(keys.blockVectorC, listOf(1, 32, 1, 1), "block vector C", stage, block)
                        )
                    )
                }
            }
        }

        val expectedTensorPaths = stageBlockKeys.flatMap { stageKeys ->
            stageKeys.flatMap { keys ->
                listOf(
                    keys.depthwiseKernel, keys.blockVectorA, keys.blockVectorB,
                    keys.expansionWeights, keys.expansionBias, keys.projectionWeights,
                    keys.projectionBias, keys.blockVectorC
                )
            }
        }.map { key -> "$tensorRootPrefix.$key.$TENSOR_FIELD_KEY" }.toSet()
        require(expectedTensorPaths.all { it in tensorMatches }) {
            "The VTB2 c5d50 tensor path set is incomplete."
        }

        return S5Vtb2C5d50StageBlockParameterSet(
            sampleRate = sampleRate,
            hopSize = hopSize,
            stageCount = stageCount,
            blocksPerStage = blockCount,
            blocks = blocks
        )
    }
}
