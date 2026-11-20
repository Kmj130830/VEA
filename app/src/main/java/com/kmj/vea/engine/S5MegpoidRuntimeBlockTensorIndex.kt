package com.kmj.vea.engine

/**
 * The eight independent key factories used by S5API FUN_1800ced10.
 *
 * These are deliberately separate from S5MegpoidBlockLookupSlot, which
 * describes the different FUN_1800537e0 loader path.
 */
enum class S5MegpoidRuntimeBlockFactory(
    val nativeFunction: String,
    val destinationOffset: Int,
    val description: String
) {
    DEPTHWISE_FILTER("FUN_1800ea000", 0x20, "3-D filter tensor; native record permutation is [C,H,W] -> [W,C,H]"),
    SHARED_DESCRIPTOR_A("FUN_1800ff330", 0x38, "First shared descriptor vector"),
    SHARED_DESCRIPTOR_B("FUN_1800f8220", 0x48, "Second shared descriptor vector"),
    EXPANSION_WEIGHTS("FUN_18010d550", 0x68, "Expansion matrix attached to the C -> C*E descriptor at +0x58"),
    EXPANSION_OPTIONAL_BIAS("FUN_180106440", 0x78, "Expansion-layer bias attached to the C -> C*E descriptor at +0x58"),
    PROJECTION_WEIGHTS("FUN_18011b770", 0x98, "Weights attached to the descriptor at +0x88"),
    PROJECTION_OPTIONAL_BIAS("FUN_180114660", 0xA8, "Optional vector attached to the descriptor at +0x88"),
    RESIDUAL_SCALE("FUN_1800f1110", 0xB8, "Residual-scale shared vector")
}

/**
 * One property-key slot from a recovered 16x16 S5API factory table.
 *
 * row maps to the caller's iVar14 outer loop; column maps to iVar15.
 */
data class S5MegpoidRuntimeBlockFactoryKey(
    val factory: S5MegpoidRuntimeBlockFactory,
    val row: Int,
    val column: Int,
    val lookupKey: String
) {
    init {
        require(row in 0 until TABLE_SIZE) { "Factory key row must be in 0..15." }
        require(column in 0 until TABLE_SIZE) { "Factory key column must be in 0..15." }
        require(lookupKey.matches(LOOKUP_KEY_PATTERN)) {
            "Factory lookup keys must be 32 lowercase hexadecimal characters."
        }
    }

    companion object {
        const val TABLE_SIZE = 16
        private val LOOKUP_KEY_PATTERN = Regex("[0-9a-f]{32}")
    }
}

/** A typed float leaf discovered below a FUN_1800ced10 factory key. */
class S5MegpoidRuntimeBlockTensorReference internal constructor(
    val factoryKey: S5MegpoidRuntimeBlockFactoryKey,
    val objectPath: String,
    val marker: String,
    val elementCount: Int,
    val encodedPayloadBytes: Int,
    val descriptorMetadata: List<Int>,
    private val encodedValue: S5ModelValue.StringValue
) {
    init {
        require(marker == "*F32" || marker == "*F16")
        require(elementCount >= 0 && encodedPayloadBytes >= 0)
    }

    fun decodeFloatArray(
        tensorDecoder: S5TypedTensorDecoder = NativeS5TypedTensorDecoder
    ): FloatArray {
        val decoded = encodedValue.asFloatArray(tensorDecoder)
        check(decoded.size == elementCount) {
            "Decoded tensor size does not match indexed element count at $objectPath."
        }
        return decoded
    }
}

/**
 * Recursive index of the model tensors referenced by FUN_1800ced10.
 *
 * It receives the eight tables as data so generated/static key tables can be
 * tested and updated independently of the parser. It never assumes that a
 * tensor with a matching element count has the correct model role.
 */
class S5MegpoidRuntimeBlockTensorIndex private constructor(
    private val tensorsByFactoryAndSlot:
        Map<S5MegpoidRuntimeBlockFactory, Map<Pair<Int, Int>, List<S5MegpoidRuntimeBlockTensorReference>>>,
    val matchedFactorySlots: Set<S5MegpoidRuntimeBlockFactory>
) {
    fun tensors(
        factory: S5MegpoidRuntimeBlockFactory,
        row: Int,
        column: Int
    ): List<S5MegpoidRuntimeBlockTensorReference> {
        require(row in 0 until S5MegpoidRuntimeBlockFactoryKey.TABLE_SIZE)
        require(column in 0 until S5MegpoidRuntimeBlockFactoryKey.TABLE_SIZE)
        return tensorsByFactoryAndSlot[factory]?.get(row to column).orEmpty()
    }

    fun uniqueTensorOrNull(
        factory: S5MegpoidRuntimeBlockFactory,
        row: Int,
        column: Int
    ): S5MegpoidRuntimeBlockTensorReference? {
        val matches = tensors(factory, row, column)
        require(matches.size <= 1) {
            "Factory ${factory.nativeFunction}[$row,$column] contains multiple typed tensors: " +
                matches.joinToString { it.objectPath }
        }
        return matches.singleOrNull()
    }

    fun summary(): Map<S5MegpoidRuntimeBlockFactory, Int> =
        S5MegpoidRuntimeBlockFactory.entries.associateWith { factory ->
            tensorsByFactoryAndSlot[factory].orEmpty().values.sumOf { it.size }
        }

    val totalTensorReferences: Int
        get() = tensorsByFactoryAndSlot.values.sumOf { slots ->
            slots.values.sumOf { it.size }
        }

    companion object {
        fun build(
            modelTree: S5ModelObjectTree,
            factoryKeys: Collection<S5MegpoidRuntimeBlockFactoryKey>
        ): S5MegpoidRuntimeBlockTensorIndex {
            val entriesByLookupKey =
                mutableMapOf<String, MutableList<S5MegpoidRuntimeBlockFactoryKey>>()
            val seenSlots = mutableSetOf<Triple<S5MegpoidRuntimeBlockFactory, Int, Int>>()
            factoryKeys.forEach { entry ->
                require(seenSlots.add(Triple(entry.factory, entry.row, entry.column))) {
                    "Duplicate key-table slot for ${entry.factory.nativeFunction}[${entry.row},${entry.column}]."
                }
                entriesByLookupKey.getOrPut(entry.lookupKey) { mutableListOf() } += entry
            }

            val matches = S5MegpoidRuntimeBlockFactory.entries.associateWith {
                mutableMapOf<Pair<Int, Int>, MutableList<S5MegpoidRuntimeBlockTensorReference>>()
            }

            fun integerMetadataFromObject(value: S5ModelValue.ObjectValue): List<Int>? {
                val candidates = value.fields.values.mapNotNull { child ->
                    val array = (child as? S5ModelValue.ArrayValue)?.values ?: return@mapNotNull null
                    if (array.isEmpty() || array.size > 32) return@mapNotNull null
                    val integers = array.map { item ->
                        val number = (item as? S5ModelValue.NumberValue)?.value
                            ?: return@mapNotNull null
                        if (!number.isFinite() || number % 1.0 != 0.0 ||
                            number < Int.MIN_VALUE.toDouble() || number > Int.MAX_VALUE.toDouble()) {
                            return@mapNotNull null
                        }
                        number.toInt()
                    }
                    integers
                }
                require(candidates.size <= 1) {
                    "Matched model property contains multiple integer metadata arrays."
                }
                return candidates.singleOrNull()
            }

            fun collectTypedLeaves(
                value: S5ModelValue,
                path: String,
                entry: S5MegpoidRuntimeBlockFactoryKey,
                inheritedMetadata: List<Int> = emptyList()
            ) {
                when (value) {
                    is S5ModelValue.StringValue -> {
                        val marker = value.value.take(4)
                        if (marker == "*F32" || marker == "*F16") {
                            val (elements, bytes) = encodedFloatLayout(value.value)
                            matches.getValue(entry.factory)
                                .getOrPut(entry.row to entry.column) { mutableListOf() }
                                .add(
                                    S5MegpoidRuntimeBlockTensorReference(
                                        factoryKey = entry,
                                        objectPath = path,
                                        marker = marker,
                                        elementCount = elements,
                                        encodedPayloadBytes = bytes,
                                        descriptorMetadata = inheritedMetadata,
                                        encodedValue = value
                                    )
                                )
                        }
                    }
                    is S5ModelValue.ObjectValue -> {
                        val metadata = integerMetadataFromObject(value) ?: inheritedMetadata
                        value.fields.forEach { (key, child) ->
                            collectTypedLeaves(child, "$path/$key", entry, metadata)
                        }
                    }
                    is S5ModelValue.ArrayValue -> value.values.forEachIndexed { index, child ->
                        collectTypedLeaves(child, "$path[$index]", entry, inheritedMetadata)
                    }
                    else -> Unit
                }
            }

            fun walk(value: S5ModelValue, path: String) {
                when (value) {
                    is S5ModelValue.ObjectValue -> value.fields.forEach { (key, child) ->
                        val childPath = "$path/$key"
                        entriesByLookupKey[key].orEmpty().forEach { entry ->
                            collectTypedLeaves(child, childPath, entry)
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
            val immutable = matches.mapValues { (_, slots) ->
                slots.mapValues { (_, references) -> references.toList() }
            }
            return S5MegpoidRuntimeBlockTensorIndex(
                tensorsByFactoryAndSlot = immutable,
                matchedFactorySlots = immutable.filterValues { it.isNotEmpty() }.keys
            )
        }

        private fun encodedFloatLayout(encoded: String): Pair<Int, Int> {
            require(encoded.length >= 4 && encoded.all { it.code in 0x00..0x7f }) {
                "S5 typed tensor data must be ASCII."
            }
            val body = encoded.substring(4)
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

            val scalarBytes = if (encoded.startsWith("*F16")) 2 else 4
            require(byteCount >= 0 && byteCount % scalarBytes == 0) {
                "Model tensor payload is not aligned to its scalar type."
            }
            val elements = byteCount / scalarBytes
            require(elements <= Int.MAX_VALUE) {
                "Model tensor contains too many elements."
            }
            return elements to byteCount
        }
    }
}
