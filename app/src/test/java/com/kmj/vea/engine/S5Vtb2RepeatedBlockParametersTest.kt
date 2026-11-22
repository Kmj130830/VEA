package com.kmj.vea.engine

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5Vtb2RepeatedBlockParameterLoaderTest {
    @Test
    fun decodesNineRepeatedVtb2RecordsByFullValuePath() {
        val modelKey = S5Vtb2RepeatedBlockParameterLoader.MODEL_OBJECT_KEY
        val rootConfigKey = S5Vtb2RepeatedBlockParameterLoader.ROOT_MODEL_CONFIG_OBJECT_KEY
        val configEntryKey = S5Vtb2RepeatedBlockParameterLoader.MODEL_CONFIG_ENTRY_KEY
        val parameterKey = S5Vtb2RepeatedBlockParameterLoader.MODEL_PARAMETER_OBJECT_KEY
        val arrayKey = S5Vtb2RepeatedBlockParameterLoader.BLOCK_ARRAY_KEY
        val bankedKey = S5Vtb2RepeatedBlockParameterLoader.BANKED_PARAMETER_OBJECT_KEY
        val singleKey = S5Vtb2RepeatedBlockParameterLoader.SINGLE_PARAMETER_OBJECT_KEY
        val weightKey = S5Vtb2RepeatedBlockParameterLoader.WEIGHT_FIELD_KEY
        val biasKey = S5Vtb2RepeatedBlockParameterLoader.BIAS_FIELD_KEY
        val blockCountKey = S5Vtb2RepeatedBlockParameterLoader.DECLARED_BLOCK_COUNT_KEY

        val entries = (0 until 9).joinToString(",") { index ->
            """{"$bankedKey":{"$weightKey":"*F32b${index}_w","$biasKey":"*F32b${index}_b"},"$singleKey":{"$weightKey":"*F32b${index}_o"}}"""
        }
        val json = """{"$rootConfigKey":{"$configEntryKey":{"$blockCountKey":9}},"$modelKey":{"$parameterKey":{"$arrayKey":[$entries]}}}"""
        val scan = S5ModelJsonScanner.scanForObjectKeys(
            StringReader(json),
            setOf(rootConfigKey, modelKey)
        )

        val decoded = S5Vtb2RepeatedBlockParameterLoader.decode(
            scan = scan,
            decodeFloatArray = { encoded ->
                when {
                    encoded.contains("_w") -> FloatArray(24_576) { 1.0f }
                    encoded.contains("_b") -> FloatArray(128) { 2.0f }
                    encoded.contains("_o") -> FloatArray(8_192) { 3.0f }
                    else -> error("Unexpected tensor marker: $encoded")
                }
            }
        )

        assertEquals(9, decoded.declaredBlockCount)
        assertEquals(9, decoded.blocks.size)
        assertEquals(296_064L, decoded.totalFloatCount)
        assertEquals(0, decoded.blocks.first().index)
        assertEquals(8, decoded.blocks.last().index)
        assertEquals(24_576, decoded.blocks[4].bankedWeightValues.size)
        assertEquals(128, decoded.blocks[4].bankedBiasValues.size)
        assertEquals(8_192, decoded.blocks[4].singleWeightValues.size)
        assertEquals(1.0f, decoded.blocks[4].bankedWeightValues[0], 0.0f)
        assertEquals(2.0f, decoded.blocks[4].bankedBiasValues[0], 0.0f)
        assertEquals(3.0f, decoded.blocks[4].singleWeightValues[0], 0.0f)
    }

    @Test
    fun rejectsAnExtraRepeatedRecordBeyondTheDeclaredNine() {
        val modelKey = S5Vtb2RepeatedBlockParameterLoader.MODEL_OBJECT_KEY
        val rootConfigKey = S5Vtb2RepeatedBlockParameterLoader.ROOT_MODEL_CONFIG_OBJECT_KEY
        val configEntryKey = S5Vtb2RepeatedBlockParameterLoader.MODEL_CONFIG_ENTRY_KEY
        val parameterKey = S5Vtb2RepeatedBlockParameterLoader.MODEL_PARAMETER_OBJECT_KEY
        val arrayKey = S5Vtb2RepeatedBlockParameterLoader.BLOCK_ARRAY_KEY
        val bankedKey = S5Vtb2RepeatedBlockParameterLoader.BANKED_PARAMETER_OBJECT_KEY
        val singleKey = S5Vtb2RepeatedBlockParameterLoader.SINGLE_PARAMETER_OBJECT_KEY
        val weightKey = S5Vtb2RepeatedBlockParameterLoader.WEIGHT_FIELD_KEY
        val biasKey = S5Vtb2RepeatedBlockParameterLoader.BIAS_FIELD_KEY
        val entries = (0 until 10).joinToString(",") { index ->
            """{"$bankedKey":{"$weightKey":"*F32b${index}_w","$biasKey":"*F32b${index}_b"},"$singleKey":{"$weightKey":"*F32b${index}_o"}}"""
        }
        val json = """{"$rootConfigKey":{"$configEntryKey":{"${S5Vtb2RepeatedBlockParameterLoader.DECLARED_BLOCK_COUNT_KEY}":9}},"$modelKey":{"$parameterKey":{"$arrayKey":[$entries]}}}"""
        val scan = S5ModelJsonScanner.scanForObjectKeys(
            StringReader(json), setOf(rootConfigKey, modelKey)
        )
        assertThrows(IllegalArgumentException::class.java) {
            S5Vtb2RepeatedBlockParameterLoader.decode(
                scan,
                decodeFloatArray = { encoded ->
                    when {
                        encoded.contains("_w") -> FloatArray(24_576)
                        encoded.contains("_b") -> FloatArray(128)
                        else -> FloatArray(8_192)
                    }
                }
            )
        }
    }

    @Test
    fun rejectsDuplicateTensorFieldsInsideRepeatedArrayEntry() {
        val modelKey = S5Vtb2RepeatedBlockParameterLoader.MODEL_OBJECT_KEY
        val rootConfigKey = S5Vtb2RepeatedBlockParameterLoader.ROOT_MODEL_CONFIG_OBJECT_KEY
        val configEntryKey = S5Vtb2RepeatedBlockParameterLoader.MODEL_CONFIG_ENTRY_KEY
        val parameterKey = S5Vtb2RepeatedBlockParameterLoader.MODEL_PARAMETER_OBJECT_KEY
        val arrayKey = S5Vtb2RepeatedBlockParameterLoader.BLOCK_ARRAY_KEY
        val bankedKey = S5Vtb2RepeatedBlockParameterLoader.BANKED_PARAMETER_OBJECT_KEY
        val singleKey = S5Vtb2RepeatedBlockParameterLoader.SINGLE_PARAMETER_OBJECT_KEY
        val weightKey = S5Vtb2RepeatedBlockParameterLoader.WEIGHT_FIELD_KEY
        val biasKey = S5Vtb2RepeatedBlockParameterLoader.BIAS_FIELD_KEY
        val entries = (0 until 9).joinToString(",") { index ->
            val duplicate = if (index == 0) """, "$weightKey":"*F32duplicate_w"""" else ""
            """{"$bankedKey":{"$weightKey":"*F32b${index}_w"$duplicate,"$biasKey":"*F32b${index}_b"},"$singleKey":{"$weightKey":"*F32b${index}_o"}}"""
        }
        val json = """{"$rootConfigKey":{"$configEntryKey":{"${S5Vtb2RepeatedBlockParameterLoader.DECLARED_BLOCK_COUNT_KEY}":9}},"$modelKey":{"$parameterKey":{"$arrayKey":[$entries]}}}"""
        val scan = S5ModelJsonScanner.scanForObjectKeys(
            StringReader(json), setOf(rootConfigKey, modelKey)
        )
        assertThrows(IllegalArgumentException::class.java) {
            S5Vtb2RepeatedBlockParameterLoader.decode(
                scan,
                decodeFloatArray = { encoded ->
                    when {
                        encoded.contains("_w") -> FloatArray(24_576)
                        encoded.contains("_b") -> FloatArray(128)
                        else -> FloatArray(8_192)
                    }
                }
            )
        }
    }

    @Test
    fun rejectsMissingNinthRecord() {
        val rootConfigKey = S5Vtb2RepeatedBlockParameterLoader.ROOT_MODEL_CONFIG_OBJECT_KEY
        val configEntryKey = S5Vtb2RepeatedBlockParameterLoader.MODEL_CONFIG_ENTRY_KEY
        val modelKey = S5Vtb2RepeatedBlockParameterLoader.MODEL_OBJECT_KEY
        val parameterKey = S5Vtb2RepeatedBlockParameterLoader.MODEL_PARAMETER_OBJECT_KEY
        val arrayKey = S5Vtb2RepeatedBlockParameterLoader.BLOCK_ARRAY_KEY
        val bankedKey = S5Vtb2RepeatedBlockParameterLoader.BANKED_PARAMETER_OBJECT_KEY
        val singleKey = S5Vtb2RepeatedBlockParameterLoader.SINGLE_PARAMETER_OBJECT_KEY
        val weightKey = S5Vtb2RepeatedBlockParameterLoader.WEIGHT_FIELD_KEY
        val biasKey = S5Vtb2RepeatedBlockParameterLoader.BIAS_FIELD_KEY
        val blockCountKey = S5Vtb2RepeatedBlockParameterLoader.DECLARED_BLOCK_COUNT_KEY
        val entries = (0 until 8).joinToString(",") { index ->
            """{"$bankedKey":{"$weightKey":"*F32b${index}_w","$biasKey":"*F32b${index}_b"},"$singleKey":{"$weightKey":"*F32b${index}_o"}}"""
        }
        val json = """{"$rootConfigKey":{"$configEntryKey":{"$blockCountKey":9}},"$modelKey":{"$parameterKey":{"$arrayKey":[$entries]}}}"""
        val scan = S5ModelJsonScanner.scanForObjectKeys(StringReader(json), setOf(rootConfigKey, modelKey))

        assertThrows(IllegalArgumentException::class.java) {
            S5Vtb2RepeatedBlockParameterLoader.decode(
                scan,
                decodeFloatArray = { encoded ->
                    when {
                        encoded.contains("_w") -> FloatArray(24_576)
                        encoded.contains("_b") -> FloatArray(128)
                        else -> FloatArray(8_192)
                    }
                }
            )
        }
    }
}
