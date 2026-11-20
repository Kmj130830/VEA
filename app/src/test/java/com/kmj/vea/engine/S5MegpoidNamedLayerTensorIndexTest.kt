package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class S5MegpoidNamedLayerTensorIndexTest {
    @Test
    fun indexesAllNineNamedLayersFromSharedModelTree() {
        val tree = modelTree()
        val index = S5MegpoidNamedLayerTensorIndex.build(tree, ::lookupKey)

        assertEquals(9, index.layers.size)
        assertEquals(2624, index.requireLayer(S5MegpoidNamedLayer.PRE1).weight.elementCount)
        assertEquals(32, index.requireLayer(S5MegpoidNamedLayer.PRE1).bias.elementCount)
        assertEquals(1024, index.requireLayer(S5MegpoidNamedLayer.PRE2).weight.elementCount)
        assertEquals(6144, index.requireLayer(S5MegpoidNamedLayer.CONV1).weight.elementCount)
        assertEquals(64, index.requireLayer(S5MegpoidNamedLayer.CONV1).bias.elementCount)
        assertEquals(1664, index.requireLayer(S5MegpoidNamedLayer.COND1).weight.elementCount)
        assertEquals(96, index.requireLayer(S5MegpoidNamedLayer.LINEAR).weight.elementCount)
        assertEquals(3, index.requireLayer(S5MegpoidNamedLayer.LINEAR).bias.elementCount)
    }

    @Test
    fun recoversNamedLayerAxisDimensionsAndPaddedAxis0() {
        val index = S5MegpoidNamedLayerTensorIndex.build(modelTree(), ::lookupKey)

        val pre1 = index.requireLayer(S5MegpoidNamedLayer.PRE1).shapeOrNull()
        assertNotNull(pre1)
        assertEquals(
            S5MegpoidNamedLayerShape(
                matrixCount = 1,
                axis0Count = 32,
                axis1Count = 82,
                paddedAxis0Count = 32,
                weightElementsPerMatrix = 2624,
                biasElementCount = 32
            ),
            pre1
        )

        val conv1 = index.requireLayer(S5MegpoidNamedLayer.CONV1).shapeOrNull()
        assertNotNull(conv1)
        assertEquals(
            S5MegpoidNamedLayerShape(
                matrixCount = 3,
                axis0Count = 64,
                axis1Count = 32,
                paddedAxis0Count = 64,
                weightElementsPerMatrix = 2048,
                biasElementCount = 64
            ),
            conv1
        )

        val cond1 = index.requireLayer(S5MegpoidNamedLayer.COND1).shapeOrNull()
        assertNotNull(cond1)
        assertEquals(26, cond1?.axis1Count)

        val linear = index.requireLayer(S5MegpoidNamedLayer.LINEAR).shapeOrNull()
        assertNotNull(linear)
        assertEquals(32, linear?.axis1Count)
    }

    @Test
    fun packsNamedWeightsUsingRecoveredDescriptorAxesAndPadding() {
        val index = S5MegpoidNamedLayerTensorIndex.build(modelTree(), ::lookupKey)
        val layer = index.requireLayer(S5MegpoidNamedLayer.CONV1)
        var receivedAxes: List<Int>? = null

        val packed = layer.packWeightMatrixBank(TestDecoder()) { values, axis0, axis1, matrices ->
            receivedAxes = listOf(axis0, axis1, matrices)
            assertEquals(6144, values.size)
            FloatArray(axis1 * ((axis0 + 7) / 8 * 8) * matrices)
        }

        assertEquals(listOf(64, 32, 3), receivedAxes)
        assertEquals(S5MegpoidNamedLayerShape(3, 64, 32, 64, 2048, 64), packed.shape)
        assertEquals(32 * 64 * 3, packed.packedValues.size)
    }

    @Test
    fun preparesAllNineNamedLayerWeightsAndBiasesForTheNativeBoundary() {
        val index = S5MegpoidNamedLayerTensorIndex.build(modelTree(), ::lookupKey)
        val prepared = index.packAllLayers(
            tensorDecoder = TestDecoder(),
            matrixPacker = { _, axis0, axis1, matrices ->
                FloatArray(axis1 * ((axis0 + 7) / 8 * 8) * matrices)
            },
            biasPacker = { _, axis0, axis1 ->
                assertEquals(1, axis1)
                FloatArray((axis0 + 7) / 8 * 8)
            }
        )

        assertEquals(9, prepared.size)
        assertEquals(S5MegpoidNamedLayer.PRE1, prepared.first().layer)
        assertEquals(S5MegpoidNamedLayer.LINEAR, prepared.last().layer)
        assertEquals(6144, prepared.firstOrNull { it.layer == S5MegpoidNamedLayer.CONV1 }
            ?.packedWeightValues?.size)
        assertEquals(64, prepared.first { it.layer == S5MegpoidNamedLayer.CONV1 }.biasValues.size)
        assertEquals(64, prepared.first { it.layer == S5MegpoidNamedLayer.CONV1 }.packedBiasValues.size)
        assertEquals(3, prepared.first { it.layer == S5MegpoidNamedLayer.LINEAR }.biasValues.size)
        assertEquals(8, prepared.first { it.layer == S5MegpoidNamedLayer.LINEAR }.packedBiasValues.size)
    }

    @Test
    fun decodesNamedLayerTensorOnlyWhenRequested() {
        val index = S5MegpoidNamedLayerTensorIndex.build(modelTree(), ::lookupKey)
        val weight = index.requireLayer(S5MegpoidNamedLayer.CONV1).weight

        assertEquals("*F32", weight.marker)
        assertEquals(6144, weight.decodeFloatArray(TestDecoder()).size)
        assertTrue(weight.lookupKey.matches(Regex("[0-9a-f]{32}")))
    }

    private fun modelTree(): S5ModelObjectTree {
        val properties = listOf(
            "pre1.weight" to Pair("f8b15a70e9fe50b828e5803435580c63", 2624),
            "pre1.bias" to Pair("e0b8e91e6062dd010960e012a5410dfc", 32),
            "pre2.weight" to Pair("5f070ac57c6e37c82ef5338f3325fdb0", 1024),
            "pre2.bias" to Pair("a236e71e934a1ef9421a3aa58dc4a32d", 32),
            "conv1.weight" to Pair("220316df91912792cb42bc663a8df636", 6144),
            "conv1.bias" to Pair("bf1fb08e8ffbab205dbb90120465e856", 64),
            "cond1.weight" to Pair("edf0ab9aee552c38c05ca173eee22ac1", 1664),
            "cond1.bias" to Pair("696e7aab6b19e87bda4d64d30787f024", 64),
            "conv2.weight" to Pair("7352c2e9486a73ce344f167dfad31876", 6144),
            "conv2.bias" to Pair("c968ce1719cfab911f40b224b5bb28df", 64),
            "cond2.weight" to Pair("d2513a339c1cd73dccef828453a4d23a", 1664),
            "cond2.bias" to Pair("f6b8236c129bd838ccee88b5e54ab949", 64),
            "conv3.weight" to Pair("7646bc7c9e90a59c368ef2b779853dff", 6144),
            "conv3.bias" to Pair("68b10aa33dc35fe0030cd4bcf63d69b1", 64),
            "cond3.weight" to Pair("aa0c15b96de654347f8cf7f3d318d537", 1664),
            "cond3.bias" to Pair("84560ce6291c9ad58de7fcace89954cf", 64),
            "linear.weight" to Pair("ecf71a24e4595ed754ce7c2a519102f0", 96),
            "linear.bias" to Pair("e7e58da68e9b65d684a141ccf7071bca", 3)
        )
        val json = properties.joinToString(
            prefix = "{",
            postfix = "}"
        ) { (_, entry) ->
            "\"${entry.first}\":\"*F32${encodedZeros(entry.second)}\""
        }
        return S5ModelObjectTree.parseUtf8(json)
    }

    private fun lookupKey(parameterName: String): String =
        propertyKeys[parameterName]
            ?: throw IllegalArgumentException("Unexpected model parameter: $parameterName")

    private fun encodedZeros(elements: Int): String =
        Base64.getEncoder().encodeToString(ByteArray(elements * 4))

    private class TestDecoder : S5TypedTensorDecoder {
        override fun decodeFloatArray(encoded: String): FloatArray {
            val bytes = Base64.getDecoder().decode(encoded.substring(4))
            return FloatArray(bytes.size / 4)
        }

        override fun decodeInt32Array(encoded: String): IntArray =
            error("No integer tensor is expected in this test.")
    }

    private companion object {
        val propertyKeys = mapOf(
            "pre1.weight" to "f8b15a70e9fe50b828e5803435580c63",
            "pre1.bias" to "e0b8e91e6062dd010960e012a5410dfc",
            "pre2.weight" to "5f070ac57c6e37c82ef5338f3325fdb0",
            "pre2.bias" to "a236e71e934a1ef9421a3aa58dc4a32d",
            "conv1.weight" to "220316df91912792cb42bc663a8df636",
            "conv1.bias" to "bf1fb08e8ffbab205dbb90120465e856",
            "cond1.weight" to "edf0ab9aee552c38c05ca173eee22ac1",
            "cond1.bias" to "696e7aab6b19e87bda4d64d30787f024",
            "conv2.weight" to "7352c2e9486a73ce344f167dfad31876",
            "conv2.bias" to "c968ce1719cfab911f40b224b5bb28df",
            "cond2.weight" to "d2513a339c1cd73dccef828453a4d23a",
            "cond2.bias" to "f6b8236c129bd838ccee88b5e54ab949",
            "conv3.weight" to "7646bc7c9e90a59c368ef2b779853dff",
            "conv3.bias" to "68b10aa33dc35fe0030cd4bcf63d69b1",
            "cond3.weight" to "aa0c15b96de654347f8cf7f3d318d537",
            "cond3.bias" to "84560ce6291c9ad58de7fcace89954cf",
            "linear.weight" to "ecf71a24e4595ed754ce7c2a519102f0",
            "linear.bias" to "e7e58da68e9b65d684a141ccf7071bca"
        )
    }
}
