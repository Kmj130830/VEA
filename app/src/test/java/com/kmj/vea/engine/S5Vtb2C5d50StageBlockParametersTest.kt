package com.kmj.vea.engine

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5Vtb2C5d50StageBlockParameterLoaderTest {
    @Test
    fun decodesAllEightStageBlockDescriptorsFromFullJsonPaths() {
        val root = S5Vtb2C5d50StageBlockParameterLoader.ROOT_OBJECT_KEY
        val wrapper = S5Vtb2C5d50StageBlockParameterLoader.CONFIG_WRAPPER_KEY
        val config = S5Vtb2C5d50StageBlockParameterLoader.RENDER_CONFIG_KEY
        val stageConfig = S5Vtb2C5d50StageBlockParameterLoader.STAGE_BLOCK_CONFIG_KEY
        val hopKey = S5Vtb2C5d50StageBlockParameterLoader.HOP_SIZE_KEY
        val rateKey = S5Vtb2C5d50StageBlockParameterLoader.SAMPLE_RATE_KEY
        val stageCountKey = S5Vtb2C5d50StageBlockParameterLoader.STAGE_COUNT_KEY
        val blockCountKey = S5Vtb2C5d50StageBlockParameterLoader.BLOCKS_PER_STAGE_KEY
        val dimensionsKey = S5Vtb2C5d50StageBlockParameterLoader.DIMENSIONS_FIELD_KEY
        val tensorKey = S5Vtb2C5d50StageBlockParameterLoader.TENSOR_FIELD_KEY
        val configJson = "\"$hopKey\":320,\"$rateKey\":32000," +
            "\"$stageCountKey\":4,\"$blockCountKey\":2"

        val tensorObjects = mutableListOf<String>()
        for (stage in 0 until 4) {
            for (block in 0 until 2) {
                val keys = S5Vtb2C5d50StageBlockParameterLoader.stageBlockKeys[stage][block]
                val values = listOf(
                    keys.depthwiseKernel to listOf(32, 1, 7, 7),
                    keys.blockVectorA to listOf(32),
                    keys.blockVectorB to listOf(32),
                    keys.expansionWeights to listOf(64, 32, 1, 1),
                    keys.expansionBias to listOf(64),
                    keys.projectionWeights to listOf(32, 64, 1, 1),
                    keys.projectionBias to listOf(32),
                    keys.blockVectorC to listOf(1, 32, 1, 1)
                )
                values.forEachIndexed { index, pair ->
                    val dimensions = pair.second.joinToString(",")
                    val payload = "stage" + stage + "_block" + block + "_tensor" + index
                    tensorObjects +=
                        "\""+pair.first+"\":{\""+dimensionsKey+"\":["+dimensions+"],"+
                            "\""+tensorKey+"\":\"*F32"+payload+"\"}"
                }
            }
        }

        val json = buildString {
            append("{\"").append(root).append("\":{\"").append(wrapper).append("\":{")
            append("\"").append(config).append("\":{").append(configJson).append("},")
            append("\"").append(stageConfig).append("\":{")
            append(tensorObjects.joinToString(","))
            append("}}}}")
        }
        val scan = S5ModelJsonScanner.scanForObjectKeys(StringReader(json), setOf(root))
        val decoded = S5Vtb2C5d50StageBlockParameterLoader.decode(
            scan,
            decodeFloatArray = { encoded ->
                val name = encoded.removePrefix("*F32")
                when {
                    name.endsWith("tensor0") -> FloatArray(1_568) { 0.125f }
                    name.endsWith("tensor1") -> FloatArray(32) { 0.25f }
                    name.endsWith("tensor2") -> FloatArray(32) { 0.5f }
                    name.endsWith("tensor3") -> FloatArray(2_048) { 0.75f }
                    name.endsWith("tensor4") -> FloatArray(64) { 1.0f }
                    name.endsWith("tensor5") -> FloatArray(2_048) { 1.25f }
                    name.endsWith("tensor6") -> FloatArray(32) { 1.5f }
                    name.endsWith("tensor7") -> FloatArray(32) { 1.75f }
                    else -> error("Unknown tensor payload: " + name)
                }
            }
        )
        assertEquals(4, decoded.stageCount)
        assertEquals(2, decoded.blocksPerStage)
        assertEquals(8, decoded.blocks.size)
        assertEquals(46_848L, decoded.totalFloatCount)
        assertEquals(0, decoded.blocks.first().stageIndex)
        assertEquals(0, decoded.blocks.first().blockIndex)
        assertEquals(3, decoded.blocks.last().stageIndex)
        assertEquals(1, decoded.blocks.last().blockIndex)
        val first = decoded.requireBlock(0, 0)
        assertEquals(1_568, first.depthwiseKernel.size)
        assertEquals(2_048, first.expansionWeights.size)
        assertEquals(64, first.expansionBias.size)
        assertEquals(2_048, first.projectionWeights.size)
        assertEquals(32, first.projectionBias.size)
        assertEquals(0.125f, first.depthwiseKernel[0], 0.0f)
        assertEquals(1.75f, first.blockVectorC[0], 0.0f)
    }

    @Test
    fun rejectsWrongRendererBlockCountBeforeTensorDecode() {
        val root = S5Vtb2C5d50StageBlockParameterLoader.ROOT_OBJECT_KEY
        val wrapper = S5Vtb2C5d50StageBlockParameterLoader.CONFIG_WRAPPER_KEY
        val config = S5Vtb2C5d50StageBlockParameterLoader.RENDER_CONFIG_KEY
        val hopKey = S5Vtb2C5d50StageBlockParameterLoader.HOP_SIZE_KEY
        val rateKey = S5Vtb2C5d50StageBlockParameterLoader.SAMPLE_RATE_KEY
        val stageKey = S5Vtb2C5d50StageBlockParameterLoader.STAGE_COUNT_KEY
        val blockKey = S5Vtb2C5d50StageBlockParameterLoader.BLOCKS_PER_STAGE_KEY
        val json = "{\""+root+"\":{\""+wrapper+"\":{\""+config+"\":{"+
            "\""+hopKey+"\":320,\""+rateKey+"\":32000,"+
            "\""+stageKey+"\":4,\""+blockKey+"\":9}}}}"
        val scan = S5ModelJsonScanner.scanForObjectKeys(StringReader(json), setOf(root))
        assertThrows(IllegalArgumentException::class.java) {
            S5Vtb2C5d50StageBlockParameterLoader.decode(scan, decodeFloatArray = { floatArrayOf() })
        }
    }
}
