package com.kmj.vea.engine

import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5Vtb2RepeatedParameterLoaderTest {
    @Test
    fun decodesNineRepeatedVtb2RecordsByFullValuePath() {
        val modelKey = S5Vtb2RepeatedParameterLoader.MODEL_OBJECT_KEY
        val rootConfigKey = S5Vtb2RepeatedParameterLoader.ROOT_MODEL_CONFIG_OBJECT_KEY
        val configEntryKey = S5Vtb2RepeatedParameterLoader.MODEL_CONFIG_ENTRY_KEY
        val parameterKey = S5Vtb2RepeatedParameterLoader.MODEL_PARAMETER_OBJECT_KEY
        val arrayKey = S5Vtb2RepeatedParameterLoader.RECORD_ARRAY_KEY
        val tensor AKey = S5Vtb2RepeatedParameterLoader.TENSOR_A_OBJECT_KEY
        val singleKey = S5Vtb2RepeatedParameterLoader.TENSOR_C_OBJECT_KEY
        val weightKey = S5Vtb2RepeatedParameterLoader.WEIGHT_FIELD_KEY
        val biasKey = S5Vtb2RepeatedParameterLoader.BIAS_FIELD_KEY
        val blockCountKey = S5Vtb2RepeatedParameterLoader.DECLARED_RECORD_COUNT_KEY

        val entries = (0 until 9).joinToString(",") { index ->
            """{"$tensor AKey":{"$weightKey":"*F32b${index}_w","$biasKey":"*F32b${index}_b"},"$singleKey":{"$weightKey":"*F32b${index}_o"}}"""
        }
        val json = """{"$rootConfigKey":{"$configEntryKey":{"$blockCountKey":9}},"$modelKey":{"$parameterKey":{"$arrayKey":[$entries]}}}"""
        val scan = S5ModelJsonScanner.scanForObjectKeys(
            StringReader(json),
            setOf(rootConfigKey, modelKey)
        )

        val decoded = S5Vtb2RepeatedParameterLoader.decode(
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

        assertEquals(9, decoded.declaredRecordCount)
        assertEquals(9, decoded.records.size)
        assertEquals(296_064L, decoded.totalFloatCount)
        assertEquals(0, decoded.records.first().index)
        assertEquals(8, decoded.records.last().index)
        assertEquals(24_576, decoded.records[4].tensorAValues.size)
        assertEquals(128, decoded.records[4].biasValues.size)
        assertEquals(8_192, decoded.records[4].tensorCValues.size)
        assertEquals(1.0f, decoded.records[4].tensorAValues[0], 0.0f)
        assertEquals(2.0f, decoded.records[4].biasValues[0], 0.0f)
        assertEquals(3.0f, decoded.records[4].tensorCValues[0], 0.0f)
    }

    @Test
    fun rejectsAnExtraRepeatedRecordBeyondTheDeclaredNine() {
        val modelKey = S5Vtb2RepeatedParameterLoader.MODEL_OBJECT_KEY
        val rootConfigKey = S5Vtb2RepeatedParameterLoader.ROOT_MODEL_CONFIG_OBJECT_KEY
        val configEntryKey = S5Vtb2RepeatedParameterLoader.MODEL_CONFIG_ENTRY_KEY
        val parameterKey = S5Vtb2RepeatedParameterLoader.MODEL_PARAMETER_OBJECT_KEY
        val arrayKey = S5Vtb2RepeatedParameterLoader.RECORD_ARRAY_KEY
        val tensor AKey = S5Vtb2RepeatedParameterLoader.TENSOR_A_OBJECT_KEY
        val singleKey = S5Vtb2RepeatedParameterLoader.TENSOR_C_OBJECT_KEY
        val weightKey = S5Vtb2RepeatedParameterLoader.WEIGHT_FIELD_KEY
        val biasKey = S5Vtb2RepeatedParameterLoader.BIAS_FIELD_KEY
        val entries = (0 until 10).joinToString(",") { index ->
            """{"$tensor AKey":{"$weightKey":"*F32b${index}_w","$biasKey":"*F32b${index}_b"},"$singleKey":{"$weightKey":"*F32b${index}_o"}}"""
        }
        val json = """{"$rootConfigKey":{"$configEntryKey":{"${S5Vtb2RepeatedParameterLoader.DECLARED_RECORD_COUNT_KEY}":9}},"$modelKey":{"$parameterKey":{"$arrayKey":[$entries]}}}"""
        val scan = S5ModelJsonScanner.scanForObjectKeys(
            StringReader(json), setOf(rootConfigKey, modelKey)
        )
        assertThrows(IllegalArgumentException::class.java) {
            S5Vtb2RepeatedParameterLoader.decode(
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
        val modelKey = S5Vtb2RepeatedParameterLoader.MODEL_OBJECT_KEY
        val rootConfigKey = S5Vtb2RepeatedParameterLoader.ROOT_MODEL_CONFIG_OBJECT_KEY
        val configEntryKey = S5Vtb2RepeatedParameterLoader.MODEL_CONFIG_ENTRY_KEY
        val parameterKey = S5Vtb2RepeatedParameterLoader.MODEL_PARAMETER_OBJECT_KEY
        val arrayKey = S5Vtb2RepeatedParameterLoader.RECORD_ARRAY_KEY
        val tensor AKey = S5Vtb2RepeatedParameterLoader.TENSOR_A_OBJECT_KEY
        val singleKey = S5Vtb2RepeatedParameterLoader.TENSOR_C_OBJECT_KEY
        val weightKey = S5Vtb2RepeatedParameterLoader.WEIGHT_FIELD_KEY
        val biasKey = S5Vtb2RepeatedParameterLoader.BIAS_FIELD_KEY
        val entries = (0 until 9).joinToString(",") { index ->
            val quote = '"'
            val duplicate = if (index == 0) ", $quote$weightKey$quote:$quote*F32duplicate_w$quote" else ""
            """{"$tensor AKey":{"$weightKey":"*F32b${index}_w"$duplicate,"$biasKey":"*F32b${index}_b"},"$singleKey":{"$weightKey":"*F32b${index}_o"}}"""
        }
        val json = """{"$rootConfigKey":{"$configEntryKey":{"${S5Vtb2RepeatedParameterLoader.DECLARED_RECORD_COUNT_KEY}":9}},"$modelKey":{"$parameterKey":{"$arrayKey":[$entries]}}}"""
        val scan = S5ModelJsonScanner.scanForObjectKeys(
            StringReader(json), setOf(rootConfigKey, modelKey)
        )
        assertThrows(IllegalArgumentException::class.java) {
            S5Vtb2RepeatedParameterLoader.decode(
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
        val rootConfigKey = S5Vtb2RepeatedParameterLoader.ROOT_MODEL_CONFIG_OBJECT_KEY
        val configEntryKey = S5Vtb2RepeatedParameterLoader.MODEL_CONFIG_ENTRY_KEY
        val modelKey = S5Vtb2RepeatedParameterLoader.MODEL_OBJECT_KEY
        val parameterKey = S5Vtb2RepeatedParameterLoader.MODEL_PARAMETER_OBJECT_KEY
        val arrayKey = S5Vtb2RepeatedParameterLoader.RECORD_ARRAY_KEY
        val tensor AKey = S5Vtb2RepeatedParameterLoader.TENSOR_A_OBJECT_KEY
        val singleKey = S5Vtb2RepeatedParameterLoader.TENSOR_C_OBJECT_KEY
        val weightKey = S5Vtb2RepeatedParameterLoader.WEIGHT_FIELD_KEY
        val biasKey = S5Vtb2RepeatedParameterLoader.BIAS_FIELD_KEY
        val blockCountKey = S5Vtb2RepeatedParameterLoader.DECLARED_RECORD_COUNT_KEY
        val entries = (0 until 8).joinToString(",") { index ->
            """{"$tensor AKey":{"$weightKey":"*F32b${index}_w","$biasKey":"*F32b${index}_b"},"$singleKey":{"$weightKey":"*F32b${index}_o"}}"""
        }
        val json = """{"$rootConfigKey":{"$configEntryKey":{"$blockCountKey":9}},"$modelKey":{"$parameterKey":{"$arrayKey":[$entries]}}}"""
        val scan = S5ModelJsonScanner.scanForObjectKeys(StringReader(json), setOf(rootConfigKey, modelKey))

        assertThrows(IllegalArgumentException::class.java) {
            S5Vtb2RepeatedParameterLoader.decode(
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
