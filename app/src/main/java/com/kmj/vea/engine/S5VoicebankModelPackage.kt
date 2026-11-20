package com.kmj.vea.engine

/**
 * A validated AI Megpoid V7 model set assembled from the singer-bank archive
 * and the shared VOCALOID7 model archive.
 *
 * The member hashes are checked against the hashes recorded by the supplied
 * AI Megpoid V7 manifest/catalog. A successful result means the five model
 * members were read, decoded and parsed into object trees; it does not mean
 * that neural inference or audio rendering is available.
 */
data class S5VoicebankModelPackage(
    val voicebankId: String,
    val componentId: String,
    val models: S5ModelArchive
) {
    val singerModel: S5ModelArchiveMember
        get() = models.singerModel

    val modelMemberCount: Int
        get() = models.members.size

    /**
     * Lazily indexes the fixed S5API model-block tensor references contained
     * in AI_Megpoid.vtb2. Values remain encoded until explicitly requested.
     */
    val modelBlockTensorIndex: S5MegpoidBlockTensorIndex by lazy(LazyThreadSafetyMode.PUBLICATION) {
        S5MegpoidBlockTensorIndex.build(singerModel.tree)
    }

    /**
     * Lazily indexes the nine named neural layers in the shared VOCALOID7.vtmg.
     * This resolves actual model tensors but does not yet execute their network.
     */
    val namedLayerTensorIndex: S5MegpoidNamedLayerTensorIndex by lazy(
        LazyThreadSafetyMode.PUBLICATION
    ) {
        val sharedModel = models.members["VOCALOID7.vtmg"]
            ?: throw IllegalStateException("Shared VOCALOID7.vtmg model member is missing.")
        S5MegpoidNamedLayerTensorIndex.build(sharedModel.tree)
    }

    /**
     * Decodes and packs all nine shared named-layer weight/bias pairs into
     * the native BlockMatrix representation for a later inference stage.
     */
    fun prepareNamedLayerParameters(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder,
        matrixPacker: (FloatArray, Int, Int, Int) -> FloatArray =
            NativeS5TensorBridge::packMatrixBank,
        biasPacker: (FloatArray, Int, Int) -> FloatArray =
            NativeS5TensorBridge::packRowMajor
    ): List<S5MegpoidPackedNamedLayer> =
        namedLayerTensorIndex.packAllLayers(tensorDecoder, matrixPacker, biasPacker)


    /**
     * Creates a persistent native descriptor table for all nine named layers.
     * The caller owns the returned handle and must close it when the voicebank
     * is unloaded. This prepares BlockMatrix storage; it does not run inference.
     */
    fun prepareNativeNamedLayerParameterSet(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): S5MegpoidNativeParameterSet =
        namedLayerTensorIndex.prepareNativeParameterSet(tensorDecoder)
}

class S5VoicebankModelPackageAssembler(
    private val expectedSha256: Map<String, String> = AI_MEGPOID_V7_MODEL_SHA256
) {
    fun assemble(
        singerBankArchive: S5ModelArchive,
        sharedModelArchive: S5ModelArchive
    ): S5VoicebankModelPackage {
        val requiredBankMembers = setOf(
            "AI_Megpoid.vtb2",
            "AI_Megpoid.vpit"
        )
        val requiredSharedMembers = setOf(
            "COMMON7.vpit",
            "VOCALOID7.vhnv",
            "VOCALOID7.vtmg"
        )

        require(singerBankArchive.members.keys.containsAll(requiredBankMembers)) {
            "The singer archive must contain AI_Megpoid.vtb2 and AI_Megpoid.vpit."
        }
        require(sharedModelArchive.members.keys.containsAll(requiredSharedMembers)) {
            "The shared archive must contain COMMON7.vpit, VOCALOID7.vhnv and VOCALOID7.vtmg."
        }

        val combined = singerBankArchive.mergedWith(sharedModelArchive)
        require(combined.members.keys.containsAll(expectedSha256.keys)) {
            "The combined model set is missing one or more required AI Megpoid V7 members."
        }

        expectedSha256.forEach { (fileName, expectedHash) ->
            val actualHash = combined.members[fileName]?.sha256
                ?: throw IllegalArgumentException("Model member '$fileName' is missing.")
            require(actualHash.equals(expectedHash, ignoreCase = true)) {
                "Model member '$fileName' does not match the AI Megpoid V7 manifest hash."
            }
        }

        return S5VoicebankModelPackage(
            voicebankId = AI_MEGPOID_V7_ID,
            componentId = AI_MEGPOID_V7_COMPONENT_ID,
            models = combined
        )
    }

    companion object {
        const val AI_MEGPOID_V7_ID = "ai_megpoid_v7"
        const val AI_MEGPOID_V7_COMPONENT_ID = "BEAF64G3DYXLRECA"

        val AI_MEGPOID_V7_MODEL_SHA256: Map<String, String> = mapOf(
            "AI_Megpoid.vtb2" to "f09cad94a1c56c3ea5e96e77ae13887edbcb9cd52f0cd4167731f8d539c42f6a",
            "AI_Megpoid.vpit" to "40affbf4f76331655866821c2f6b589cc02cb0b0964c21bf48c92f283c0e846f",
            "VOCALOID7.vtmg" to "3384cc85fa5ccea1c3203b4ae7aee687a059ec67a7d3a9ebecb8c57170d2b36d",
            "COMMON7.vpit" to "2611bf3c7079ef97851f4103f7d88b9d900a1cbf7e4cffe61ae8d904c2273751",
            "VOCALOID7.vhnv" to "d8de8bfbf6ef4c9829431e09a29e4b105524d22faaada9001552cd89cb61d570"
        )
    }
}
