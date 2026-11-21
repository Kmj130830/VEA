package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class S5KoreanG2pPreprocessorTest {
    @Test
    fun appliesAllRecoveredYamahaKoreanReplacementsInNativeOrder() {
        val input = listOf(
            "디귿이", "디귿을", "디귿에",
            "지읒이", "지읒을", "지읒에",
            "치읓이", "치읓을", "치읓에",
            "키읔이", "키읔을", "키읔에",
            "티읕이", "티읕을", "티읕에",
            "피읖이", "피읖을", "피읖에",
            "히읗이", "히읗을", "히읗에",
            "의견란", "임진란", "생산량", "결단력", "공권력",
            "동원령", "상견례", "횡단로", "이원론", "입원료", "구근류"
        ).joinToString(" ")

        val expected = listOf(
            "디그시", "디그슬", "디그세",
            "지으시", "지으슬", "지으세",
            "치으시", "치으슬", "치으세",
            "키으기", "키으글", "키으게",
            "티으시", "티으슬", "티으세",
            "피으비", "피으블", "피으베",
            "히으시", "히으슬", "히으세",
            "의견난", "임진난", "생산냥", "결딴녁", "공꿘녁",
            "동원녕", "상견녜", "횡단노", "이원논", "이붠뇨", "구근뉴"
        ).joinToString(" ")

        assertEquals(expected, S5KoreanG2pPreprocessor.normalize(input))
    }

    @Test
    fun leavesUnmatchedTextAndSpacingUntouched() {
        val input = "안녕하세요  테스트!  ABC"
        assertEquals(input, S5KoreanG2pPreprocessor.normalize(input))
    }
}
