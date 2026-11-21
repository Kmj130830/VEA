package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class S5ModelTensorKeyTest {
    @Test
    fun derivesWeightKeyUsingRecoveredSaltAndWordOrder() {
        assertEquals(
            "e61604f9a121a50ca2f75c53d228d415",
            S5ModelTensorKey.derive(
                "rnn.weight_ih_l0",
                S5ModelTensorKey.Member.WEIGHT
            )
        )
    }

    @Test
    fun derivesReverseDirectionAndBiasKeys() {
        assertEquals(
            "fab038cc06f083806164c6fbf0a4c706",
            S5ModelTensorKey.derive(
                "rnn.weight_hh_l0_reverse",
                S5ModelTensorKey.Member.WEIGHT
            )
        )
        assertEquals(
            "1d4ac294326dc49e5865349f6cfe149c",
            S5ModelTensorKey.derive(
                "rnn.bias_ih_l0",
                S5ModelTensorKey.Member.BIAS
            )
        )
    }

    @Test
    fun hashesCompleteRecurrentParameterNameWithoutAppendingWeightSuffix() {
        assertEquals(
            "2377051edb208e4b1b07a052125310ad",
            S5ModelTensorKey.deriveParameterName("rnn.weight_ih_l0")
        )
        assertEquals(
            "afbc925af67d30584adec6ddfe38c30e",
            S5ModelTensorKey.deriveParameterName("rnn.bias_hh_l0")
        )
    }

    @Test
    fun appendsParameterSuffixBeforeBinaryDigestSuffix() {
        assertEquals(
            "705ab1f8b850fee93480e528630c5835",
            S5ModelTensorKey.derive("pre1", S5ModelTensorKey.Member.WEIGHT)
        )
        assertEquals(
            "8eb01fbf20abfb8f1290bb5d56e86504",
            S5ModelTensorKey.derive("conv1", S5ModelTensorKey.Member.BIAS)
        )
    }

    @Test
    fun derivesCompleteRecurrentNamesWithoutAppendingAnotherMemberSuffix() {
        assertEquals(
            "2377051edb208e4b1b07a052125310ad",
            S5ModelTensorKey.deriveParameterName("rnn.weight_ih_l0")
        )
        assertEquals(
            "a78e70c38488a9cc282736f4835fb38f",
            S5ModelTensorKey.deriveParameterName("rnn.weight_hh_l0_reverse")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonAsciiBaseName() {
        S5ModelTensorKey.derive("한글", S5ModelTensorKey.Member.WEIGHT)
    }
}
