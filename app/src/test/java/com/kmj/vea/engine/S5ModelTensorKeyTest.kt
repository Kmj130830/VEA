package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class S5ModelTensorKeyTest {
    @Test
    fun derivesWeightKeyUsingRecoveredSaltAndWordOrder() {
        assertEquals(
            "f90416e60ca521a1535cf7a215d428d2",
            S5ModelTensorKey.derive(
                "rnn.weight_ih_l0",
                S5ModelTensorKey.Member.WEIGHT
            )
        )
    }

    @Test
    fun derivesReverseDirectionAndBiasKeys() {
        assertEquals(
            "cc38b0fa8083f006fbc6646106c7a4f0",
            S5ModelTensorKey.derive(
                "rnn.weight_hh_l0_reverse",
                S5ModelTensorKey.Member.WEIGHT
            )
        )
        assertEquals(
            "94c24a1d9ec46d329f3465589c14fe6c",
            S5ModelTensorKey.derive(
                "rnn.bias_ih_l0",
                S5ModelTensorKey.Member.BIAS
            )
        )
    }

    @Test
    fun hashesCompleteRecurrentParameterNameWithoutAppendingWeightSuffix() {
        assertEquals(
            "1e0577234b8e20db52a0071bad105312",
            S5ModelTensorKey.deriveParameterName("rnn.weight_ih_l0")
        )
        assertEquals(
            "5a92bcaf58307df6ddc6de4a0ec338fe",
            S5ModelTensorKey.deriveParameterName("rnn.bias_hh_l0")
        )
    }

    @Test
    fun appendsParameterSuffixBeforeBinaryDigestSuffix() {
        assertEquals(
            "f8b15a70e9fe50b828e5803435580c63",
            S5ModelTensorKey.derive("pre1", S5ModelTensorKey.Member.WEIGHT)
        )
        assertEquals(
            "bf1fb08e8ffbab205dbb90120465e856",
            S5ModelTensorKey.derive("conv1", S5ModelTensorKey.Member.BIAS)
        )
    }

    @Test
    fun derivesCompleteRecurrentNamesWithoutAppendingAnotherMemberSuffix() {
        assertEquals(
            "1e0577234b8e20db52a0071bad105312",
            S5ModelTensorKey.deriveParameterName("rnn.weight_ih_l0")
        )
        assertEquals(
            "c3708ea7cca98884f43627288fb35f83",
            S5ModelTensorKey.deriveParameterName("rnn.weight_hh_l0_reverse")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonAsciiBaseName() {
        S5ModelTensorKey.derive("한글", S5ModelTensorKey.Member.WEIGHT)
    }
}
