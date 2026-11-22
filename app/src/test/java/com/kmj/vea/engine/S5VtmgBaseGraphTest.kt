package com.kmj.vea.engine

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5VtmgBaseGraphTest {
    @Test
    fun catalogMapsEveryLayerWeightAndBiasToVtmgKeys() {
        val expectedNames = listOf(
            "pre1", "pre2", "conv1", "cond1", "conv2",
            "cond2", "conv3", "cond3", "linear"
        )
        assertEquals(expectedNames, S5VtmgGraphTensorCatalog.layers.map { it.layerName })
        assertEquals(18, S5VtmgGraphTensorCatalog.expectedTensorKeys.size)

        val pre1 = S5VtmgGraphTensorCatalog.layers.first()
        assertEquals("f8b15a70e9fe50b828e5803435580c63", pre1.weightKey)
        assertEquals("e0b8e91e6062dd010960e012a5410dfc", pre1.biasKey)
    }

    @Test
    fun catalogContainsRecoveredAxisAndBankDimensions() {
        val expected = listOf(
            Triple("pre1", 32, 82) to 1,
            Triple("pre2", 32, 32) to 1,
            Triple("conv1", 64, 32) to 3,
            Triple("cond1", 64, 26) to 1,
            Triple("conv2", 64, 32) to 3,
            Triple("cond2", 64, 26) to 1,
            Triple("conv3", 64, 32) to 3,
            Triple("cond3", 64, 26) to 1,
            Triple("linear", 3, 32) to 1
        )
        val actual = S5VtmgGraphTensorCatalog.layers.map { spec ->
            (Triple(spec.layerName, spec.axis0Count, spec.axis1Count) to spec.matrixCount)
        }
        assertEquals(expected, actual)
        assertEquals(27_619L, S5VtmgGraphTensorCatalog.layers.sumOf {
            it.weightElementCount.toLong() + it.biasElementCount
        })
    }

    @Test
    fun decodesAllNineVtmgLayersAndChecksObservedElementCounts() {
        val objectKey = S5VtmgGraphTensorCatalog.BASE_GRAPH_OBJECT_KEY
        val fields = S5VtmgGraphTensorCatalog.layers.flatMap { spec ->
            listOf(
                "\"" + spec.weightKey + "\":\"*F32" + spec.layerName + "_weight\"",
                "\"" + spec.biasKey + "\":\"*F32" + spec.layerName + "_bias\""
            )
        }
        val json = "{\"" + objectKey + "\":{" + fields.joinToString(",") + "}}"
        val scan = S5ModelJsonScanner.scanForObjectKeys(
            StringReader(json),
            setOf(objectKey)
        )

        assertEquals(setOf(objectKey), scan.matchedObjectKeys)
        val graph = S5VtmgGraphTensorCatalog.decode(
            scan = scan,
            decodeFloatArray = { encoded ->
                val suffix = encoded.removePrefix("*F32")
                val spec = S5VtmgGraphTensorCatalog.layers.single { layer ->
                    suffix == layer.layerName + "_weight" ||
                        suffix == layer.layerName + "_bias"
                }
                if (suffix.endsWith("_weight")) {
                    FloatArray(spec.weightElementCount) { it.toFloat() }
                } else {
                    FloatArray(spec.biasElementCount) { -it.toFloat() }
                }
            }
        )

        assertEquals(9, graph.layers.size)
        assertEquals(27_619L, graph.totalFloatCount)
        assertEquals(2624, graph.requireLayer("pre1").weight.size)
        assertEquals(32, graph.requireLayer("pre1").bias.size)
        assertEquals(6144, graph.requireLayer("conv1").weight.size)
        assertEquals(1664, graph.requireLayer("cond3").weight.size)
        assertEquals(96, graph.requireLayer("linear").weight.size)
        assertEquals(3, graph.requireLayer("linear").bias.size)
    }

    @Test
    fun packsAllLayersIntoS5MatrixBankLayoutAndZeroPadsAxis0() {
        val rawLayers = S5VtmgGraphTensorCatalog.layers.map { spec ->
            S5VtmgGraphLayerWeights(
                layerName = spec.layerName,
                weight = FloatArray(spec.weightElementCount) { it.toFloat() },
                bias = FloatArray(spec.biasElementCount) { -it.toFloat() }
            )
        }
        val rawGraph = S5VtmgBaseGraphWeights(rawLayers)
        val packed = S5VtmgBaseGraphPacker.pack(rawGraph, ::referencePackMatrixBank)

        assertEquals(9, packed.layers.size)
        assertEquals(27_779L, packed.totalFloatCount)

        val conv1Spec = S5VtmgGraphTensorCatalog.layers.single { it.layerName == "conv1" }
        val conv1 = packed.requireLayer("conv1")
        assertEquals(3, conv1.matrixCount)
        assertEquals(64, conv1.paddedAxis0Count)
        assertEquals(conv1Spec.packedWeightElementCount, conv1.matrices.size)
        // Matrix 0 uses source index 96 for axis0=1, axis1=0.
        assertEquals(96.0f, conv1.matrices[1])
        // Matrix 1 begins at source matrix index 1.
        assertEquals(1.0f, conv1.matrices[conv1.matrixStride])

        val linear = packed.requireLayer("linear")
        assertEquals(3, linear.axis0Count)
        assertEquals(8, linear.paddedAxis0Count)
        assertEquals(256, linear.matrices.size)
        for (row in 0 until linear.axis1Count) {
            val rowStart = row * linear.paddedAxis0Count
            for (column in linear.axis0Count until linear.paddedAxis0Count) {
                assertEquals(0.0f, linear.matrices[rowStart + column])
            }
        }
        assertEquals(rawGraph.requireLayer("linear").bias.toList(), linear.bias.toList())
    }

    @Test
    fun packerRejectsWrongPackedLengthAndNonzeroPadding() {
        val rawLayers = S5VtmgGraphTensorCatalog.layers.map { spec ->
            S5VtmgGraphLayerWeights(
                spec.layerName,
                FloatArray(spec.weightElementCount),
                FloatArray(spec.biasElementCount)
            )
        }
        val graph = S5VtmgBaseGraphWeights(rawLayers)
        assertThrows(IllegalArgumentException::class.java) {
            S5VtmgBaseGraphPacker.pack(graph) { _, _, _, _ -> FloatArray(1) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            S5VtmgBaseGraphPacker.pack(graph) { source, axis0, axis1, banks ->
                val packed = referencePackMatrixBank(source, axis0, axis1, banks)
                if (axis0 == 3) packed[3] = 1.0f
                packed
            }
        }
    }

    @Test
    fun rejectsMissingBaseGraphObject() {
        val scan = S5ModelJsonScanner.scanForObjectKeys(
            StringReader("{\"other\":{\"value\":1}}"),
            setOf(S5VtmgGraphTensorCatalog.BASE_GRAPH_OBJECT_KEY)
        )
        assertThrows(IllegalArgumentException::class.java) {
            S5VtmgGraphTensorCatalog.decode(
                scan,
                decodeFloatArray = { floatArrayOf() }
            )
        }
    }

    @Test
    fun rejectsMissingLayerTensor() {
        val objectKey = S5VtmgGraphTensorCatalog.BASE_GRAPH_OBJECT_KEY
        val first = S5VtmgGraphTensorCatalog.layers.first()
        val json = "{\"" + objectKey + "\":{\"" + first.weightKey + "\":\"*F32pre1_weight\"}}"
        val scan = S5ModelJsonScanner.scanForObjectKeys(
            StringReader(json),
            setOf(objectKey)
        )
        assertThrows(IllegalArgumentException::class.java) {
            S5VtmgGraphTensorCatalog.decode(
                scan,
                decodeFloatArray = { FloatArray(2624) }
            )
        }
    }

    private fun referencePackMatrixBank(
        source: FloatArray,
        axis0Count: Int,
        axis1Count: Int,
        matrixCount: Int
    ): FloatArray {
        val paddedAxis0 = (axis0Count + 7) and 7.inv()
        val result = FloatArray(paddedAxis0 * axis1Count * matrixCount)
        for (matrix in 0 until matrixCount) {
            for (axis1 in 0 until axis1Count) {
                for (axis0 in 0 until axis0Count) {
                    val sourceIndex = (axis0 * axis1Count + axis1) * matrixCount + matrix
                    val targetIndex = (matrix * axis1Count + axis1) * paddedAxis0 + axis0
                    result[targetIndex] = source[sourceIndex]
                }
            }
        }
        return result
    }


    @Test
    fun packedVtmgLayerBuildsExactC6390ProjectionPlan() {
        val layer = S5VtmgPackedGraphLayerWeights(
            layerName = "conv-test",
            matrices = FloatArray(3 * 2 * 8) { index ->
                val bank = index / (2 * 8)
                val withinBank = index % (2 * 8)
                val input = withinBank / 8
                val output = withinBank % 8
                if (output < 2) bank * 100.0f + output * 10.0f + input else 0.0f
            },
            bias = floatArrayOf(0.25f, -0.5f),
            axis0Count = 2,
            axis1Count = 2,
            matrixCount = 3,
            paddedAxis0Count = 8
        )
        val plan = layer.toBankedProjectionPlan { source, output, input, padded, banks ->
            FloatArray(output * input * banks) { index ->
                val bank = index / (output * input)
                val outputFeature = (index % (output * input)) / input
                val inputFeature = index % input
                source[(bank * input + inputFeature) * padded + outputFeature]
            }
        }
        assertEquals(2, plan.inputFeatures)
        assertEquals(2, plan.outputFeatures)
        assertEquals(3, plan.banks)
        assertEquals(2, plan.weightRowStride)
        assertEquals(4, plan.weightBankStride)
        assertEquals(12, plan.weights.size)
        assertEquals(listOf(0.0f, 1.0f, 10.0f, 11.0f, 100.0f, 101.0f,
            110.0f, 111.0f, 200.0f, 201.0f, 210.0f, 211.0f), plan.weights.toList())
        assertEquals(listOf(0.25f, -0.5f), plan.bias.toList())
    }


    @Test
    fun packedVtmgProjectionProducesC6390FeaturePositionBankOrder() {
        val packed = FloatArray(3 * 2 * 8)
        for (bank in 0 until 3) {
            for (inputFeature in 0 until 2) {
                for (outputFeature in 0 until 2) {
                    packed[(bank * 2 + inputFeature) * 8 + outputFeature] =
                        bank * 100.0f + outputFeature * 10.0f + inputFeature
                }
            }
        }
        val layer = S5VtmgPackedGraphLayerWeights(
            layerName = "conv-test",
            matrices = packed,
            bias = floatArrayOf(0.5f, -1.0f),
            axis0Count = 2,
            axis1Count = 2,
            matrixCount = 3,
            paddedAxis0Count = 8
        )
        val plan = layer.toBankedProjectionPlan { source, output, input, padded, banks ->
            FloatArray(output * input * banks) { index ->
                val bank = index / (output * input)
                val out = (index % (output * input)) / input
                val inFeature = index % input
                source[(bank * input + inFeature) * padded + out]
            }
        }
        val x = floatArrayOf(1.0f, 2.0f, 3.0f, 4.0f) // [2 input features][2 positions]
        val result = plan.project(x, 2) { weights, rowStride, bankStride, input, bias,
            inFeatures, outFeatures, positions, banks ->
            val output = FloatArray(outFeatures * positions * banks)
            for (out in 0 until outFeatures) {
                for (position in 0 until positions) {
                    for (bank in 0 until banks) {
                        var sum = bias[out]
                        for (feature in 0 until inFeatures) {
                            val a = weights[bank * bankStride + out * rowStride + feature]
                            val b = input[feature * positions + position]
                            sum += a * b
                        }
                        output[(out * positions + position) * banks + bank] = sum
                    }
                }
            }
            output
        }
        assertEquals(12, result.size)
        assertEquals(
            listOf(4.5f, 304.5f, 604.5f, 6.5f, 606.5f, 1206.5f,
                31.0f, 331.0f, 631.0f, 43.0f, 643.0f, 1243.0f),
            result.toList()
        )
    }

    @Test
    fun packedLayerConvertsBankMajorWeightsToOutputInputTapOrder() {
        val outputFeatures = 2
        val inputFeatures = 2
        val kernelWidth = 3
        val paddedOutputFeatures = 8
        val packed = FloatArray(kernelWidth * inputFeatures * paddedOutputFeatures)
        for (tap in 0 until kernelWidth) {
            for (input in 0 until inputFeatures) {
                for (output in 0 until outputFeatures) {
                    packed[(tap * inputFeatures + input) * paddedOutputFeatures + output] =
                        tap * 100.0f + input * 10.0f + output
                }
            }
        }
        val layer = S5VtmgPackedGraphLayerWeights(
            layerName = "conv-test",
            matrices = packed,
            bias = floatArrayOf(0.0f, 0.0f),
            axis0Count = outputFeatures,
            axis1Count = inputFeatures,
            matrixCount = kernelWidth,
            paddedAxis0Count = paddedOutputFeatures
        )

        val converted = layer.toTemporalConv1dWeights()
        assertEquals(12, converted.size)
        assertEquals(
            listOf(0.0f, 100.0f, 200.0f, 10.0f, 110.0f, 210.0f),
            converted.take(6)
        )
        assertEquals(
            listOf(1.0f, 101.0f, 201.0f, 11.0f, 111.0f, 211.0f),
            converted.drop(6)
        )
        assertThrows(IllegalArgumentException::class.java) {
            layer.toTemporalConv1dWeights(kernelWidth = 2)
        }
    }

    @Test
    fun packedLayerCanConvertToV0NaiveGemmWeightOrder() {
        val outputFeatures = 2
        val inputFeatures = 3
        val paddedOutputFeatures = 8
        val banks = 2
        val packed = FloatArray(banks * inputFeatures * paddedOutputFeatures)
        for (bank in 0 until banks) {
            for (input in 0 until inputFeatures) {
                for (output in 0 until outputFeatures) {
                    packed[(bank * inputFeatures + input) * paddedOutputFeatures + output] =
                        bank * 100.0f + input * 10.0f + output
                }
            }
        }
        val layer = S5VtmgPackedGraphLayerWeights(
            layerName = "test",
            matrices = packed,
            bias = floatArrayOf(0.0f, 0.0f),
            axis0Count = outputFeatures,
            axis1Count = inputFeatures,
            matrixCount = banks,
            paddedAxis0Count = paddedOutputFeatures
        )
        val converted = layer.toV0NaiveGemmWeights { source, output, input, padded, count ->
            assertEquals(outputFeatures, output)
            assertEquals(inputFeatures, input)
            assertEquals(paddedOutputFeatures, padded)
            assertEquals(banks, count)
            FloatArray(count * output * input) { index ->
                val bank = index / (output * input)
                val outputFeature = (index % (output * input)) / input
                val inputFeature = index % input
                source[(bank * input + inputFeature) * padded + outputFeature]
            }
        }
        assertEquals(12, converted.size)
        assertEquals(0.0f, converted[0], 0.0f)
        assertEquals(10.0f, converted[1], 0.0f)
        assertEquals(20.0f, converted[2], 0.0f)
        assertEquals(1.0f, converted[3], 0.0f)
        assertEquals(100.0f, converted[6], 0.0f)
        assertEquals(121.0f, converted[11], 0.0f)
    }


}
