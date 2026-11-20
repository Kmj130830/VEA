package com.kmj.vea.engine

/**
 * Fixed object-tree lookup keys recovered from the S5API FUN_1800537e0
 * model-block setup path. The labels intentionally remain KEY_1..KEY_9:
 * the binary analysis has not established semantic parameter names.
 */
enum class S5MegpoidBlockLookupSlot(val lookupKey: String) {
    /** Primary typed-matrix property consumed by FUN_180063ca0. */
    KEY_1("30c16498ff30815c43d58f610a91f938"),
    /** Secondary property consumed by FUN_180064e90 when its descriptor enables it. */
    KEY_2("c169d8ef47cc23f097ff312d08a6f829"),
    KEY_3("9e4170a67e795e687b0df130d8086421"),
    KEY_4("6eaa6d5161e42effbaa006bb0cd3f587"),
    KEY_5("eedbb0e6fd15547ef093d893e7cf42f8"),
    KEY_6("987646cc1b7b3d17fdb85ccaeaed092e"),
    KEY_7("4fd7be386f8043b7cdcf1e69d16579c8"),
    KEY_8("830bb9b50d0881b9373eb1baa7bf7313"),
    KEY_9("dcef88c51681aa4ed5dee8a40b692534")
}

/**
 * A typed tensor discovered below one of FUN_1800537e0's fixed lookup keys.
 * The original encoded value is retained in the parsed model tree and decoded
 * only when the renderer asks for it.
 */
/**
 * Two layer-property tensors found in the same containing object. Static code
 * shows that the first property becomes a BlockMatrix array and the second is
 * handled by a separate consumer. Their semantic roles are intentionally not
 * guessed.
 */
data class S5MegpoidModelPropertyPair(
    val containingObjectPath: String,
    val first: S5MegpoidModelTensorReference,
    val second: S5MegpoidModelTensorReference
)

class S5MegpoidModelTensorReference internal constructor(
    val slot: S5MegpoidBlockLookupSlot,
    val objectPath: String,
    val marker: String,
    val elementCount: Int,
    val encodedPayloadBytes: Int,
    private val encodedValue: S5ModelValue.StringValue
) {
    fun decodeFloatArray(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): FloatArray {
        require(marker == "*F32" || marker == "*F16") {
            "Model tensor '$objectPath' is not a floating-point tensor."
        }
        val decoded = encodedValue.asFloatArray(tensorDecoder)
        check(decoded.size == elementCount) {
            "Decoded tensor size does not match its indexed element count."
        }
        return decoded
    }
}

/**
 * Lazy index of the fixed model-block keys and all nested *F32 and *F16 tensors
 * they reference. This is a model-data binding layer; it does not run a
 * forward pass or claim to be an audio renderer.
 */
class S5MegpoidBlockTensorIndex private constructor(
    private val tensorsBySlot: Map<S5MegpoidBlockLookupSlot, List<S5MegpoidModelTensorReference>>,
    val matchedLookupSlots: Set<S5MegpoidBlockLookupSlot>
) {
    fun tensors(slot: S5MegpoidBlockLookupSlot): List<S5MegpoidModelTensorReference> =
        tensorsBySlot[slot].orEmpty()

    val totalTensorReferences: Int
        get() = tensorsBySlot.values.sumOf { it.size }

    fun summary(): Map<S5MegpoidBlockLookupSlot, Int> =
        S5MegpoidBlockLookupSlot.entries.associateWith { tensors(it).size }

    /**
     * Pairs the two matrix-property keys only when both typed tensor leaves
     * share the same immediate containing object. Incomplete or ambiguous
     * groups are omitted rather than guessed.
     */
    fun modelPropertyPairs(): List<S5MegpoidModelPropertyPair> {
        val firstByParent = tensors(S5MegpoidBlockLookupSlot.KEY_1)
            .groupBy { it.objectPath.substringBeforeLast('/', missingDelimiterValue = "") }
        val secondByParent = tensors(S5MegpoidBlockLookupSlot.KEY_2)
            .groupBy { it.objectPath.substringBeforeLast('/', missingDelimiterValue = "") }
        return firstByParent.keys.intersect(secondByParent.keys).sorted().mapNotNull { parent ->
            val first = firstByParent[parent].orEmpty()
            val second = secondByParent[parent].orEmpty()
            if (first.size == 1 && second.size == 1) {
                S5MegpoidModelPropertyPair(parent, first.single(), second.single())
            } else {
                null
            }
        }
    }

    companion object {
        fun build(modelTree: S5ModelObjectTree): S5MegpoidBlockTensorIndex {
            val slotsByKey = S5MegpoidBlockLookupSlot.entries.associateBy { it.lookupKey }
            val matches = S5MegpoidBlockLookupSlot.entries.associateWith {
                mutableListOf<S5MegpoidModelTensorReference>()
            }

            fun collectTensorLeaves(
                slot: S5MegpoidBlockLookupSlot,
                value: S5ModelValue,
                path: String
            ) {
                when (value) {
                    is S5ModelValue.StringValue -> {
                        val marker = value.value.take(4)
                        if (marker == "*F32" || marker == "*F16") {
                            val layout = encodedFloatLayout(value.value)
                            matches.getValue(slot) += S5MegpoidModelTensorReference(
                                slot = slot,
                                objectPath = path,
                                marker = marker,
                                elementCount = layout.first,
                                encodedPayloadBytes = layout.second,
                                encodedValue = value
                            )
                        }
                    }
                    is S5ModelValue.ObjectValue -> value.fields.forEach { (key, child) ->
                        collectTensorLeaves(slot, child, "$path/${key}")
                    }
                    is S5ModelValue.ArrayValue -> value.values.forEachIndexed { index, child ->
                        collectTensorLeaves(slot, child, "$path[$index]")
                    }
                    else -> Unit
                }
            }

            fun walk(value: S5ModelValue, path: String) {
                when (value) {
                    is S5ModelValue.ObjectValue -> value.fields.forEach { (key, child) ->
                        val childPath = "$path/${key}"
                        val slot = slotsByKey[key]
                        if (slot != null) {
                            collectTensorLeaves(slot, child, childPath)
                        }
                        walk(child, childPath)
                    }
                    is S5ModelValue.ArrayValue -> value.values.forEachIndexed { index, child ->
                        walk(child, "$path[$index]")
                    }
                    else -> Unit
                }
            }

            walk(modelTree.root, "$")
            val immutable = matches.mapValues { (_, values) -> values.toList() }
            return S5MegpoidBlockTensorIndex(
                tensorsBySlot = immutable,
                matchedLookupSlots = immutable.filterValues { it.isNotEmpty() }.keys
            )
        }

        /**
         * Computes the decoded byte count from Base64 length without allocating
         * the tensor payload. The actual Base64 bytes are validated on decode.
         */
        private fun encodedFloatLayout(encodedValue: String): Pair<Int, Int> {
            require(encodedValue.length >= 4 && encodedValue.all { it.code in 0x00..0x7f }) {
                "S5 typed tensor data must be ASCII."
            }
            val body = encodedValue.substring(4)
            val padding = body.takeLastWhile { it == '=' }.length
            require(padding <= 2 && body.dropLast(padding).none { it == '=' }) {
                "Malformed Base64 padding in model tensor."
            }

            val byteCount = if (body.length % 4 == 0) {
                (body.length / 4) * 3 - padding
            } else {
                require(padding == 0) { "Padded Base64 tensor has an invalid length." }
                val groups = body.length / 4
                val remainder = body.length % 4
                require(remainder == 2 || remainder == 3) {
                    "Malformed Base64 length in model tensor."
                }
                groups * 3 + if (remainder == 2) 1 else 2
            }

            val scalarBytes = if (encodedValue.startsWith("*F16")) 2 else 4
            require(byteCount >= 0 && byteCount % scalarBytes == 0) {
                "Model tensor payload is not aligned to its scalar type."
            }
            val elements = byteCount / scalarBytes
            require(elements <= Int.MAX_VALUE) { "Model tensor contains too many elements." }
            return elements to byteCount
        }
    }
}
