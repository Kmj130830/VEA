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
        assertEquals(27619L, graph.totalFloatCount)
        assertEquals(2624, graph.requireLayer("pre1").weight.size)
        assertEquals(32, graph.requireLayer("pre1").bias.size)
        assertEquals(6144, graph.requireLayer("conv1").weight.size)
        assertEquals(1664, graph.requireLayer("cond3").weight.size)
        assertEquals(96, graph.requireLayer("linear").weight.size)
        assertEquals(3, graph.requireLayer("linear").bias.size)
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
}
