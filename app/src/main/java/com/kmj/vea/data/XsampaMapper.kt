package com.kmj.vea.data

import java.text.Normalizer

/**
 * Lightweight lyric-to-X-SAMPA display converter for the currently supported
 * editor languages. It is a UI-side phoneme suggestion, not a voicebank G2P engine.
 */
object XsampaMapper {
    private val japaneseMora = mapOf(
        "あ" to "a", "い" to "i", "う" to "M", "え" to "e", "お" to "o",
        "か" to "k a", "き" to "k' i", "く" to "k M", "け" to "k e", "こ" to "k o",
        "が" to "g a", "ぎ" to "g' i", "ぐ" to "g M", "げ" to "g e", "ご" to "g o",
        "さ" to "s a", "し" to "S i", "す" to "s M", "せ" to "s e", "そ" to "s o",
        "ざ" to "z a", "じ" to "dZ i", "ず" to "z M", "ぜ" to "z e", "ぞ" to "z o",
        "た" to "t a", "ち" to "tS i", "つ" to "ts M", "て" to "t e", "と" to "t o",
        "だ" to "d a", "ぢ" to "dZ i", "づ" to "z M", "で" to "d e", "ど" to "d o",
        "な" to "n a", "に" to "J i", "ぬ" to "n M", "ね" to "n e", "の" to "n o",
        "は" to "h a", "ひ" to "C i", "ふ" to "p\\ M", "へ" to "h e", "ほ" to "h o",
        "ば" to "b a", "び" to "b' i", "ぶ" to "b M", "べ" to "b e", "ぼ" to "b o",
        "ぱ" to "p a", "ぴ" to "p' i", "ぷ" to "p M", "ぺ" to "p e", "ぽ" to "p o",
        "ま" to "m a", "み" to "m' i", "む" to "m M", "め" to "m e", "も" to "m o",
        "や" to "j a", "ゆ" to "j M", "よ" to "j o",
        "ら" to "4 a", "り" to "4' i", "る" to "4 M", "れ" to "4 e", "ろ" to "4 o",
        "わ" to "w a", "を" to "o", "ん" to "N",
        "きゃ" to "k' a", "きゅ" to "k' M", "きょ" to "k' o",
        "しゃ" to "S a", "しゅ" to "S M", "しょ" to "S o",
        "ちゃ" to "tS a", "ちゅ" to "tS M", "ちょ" to "tS o",
        "にゃ" to "J a", "にゅ" to "J M", "にょ" to "J o",
        "ひゃ" to "C a", "ひゅ" to "C M", "ひょ" to "C o",
        "みゃ" to "m' a", "みゅ" to "m' M", "みょ" to "m' o",
        "りゃ" to "4' a", "りゅ" to "4' M", "りょ" to "4' o",
        "じゃ" to "dZ a", "じゅ" to "dZ M", "じょ" to "dZ o"
    )

    private val koOnset = listOf(
        "k", "k*", "n", "t", "t*", "4", "m", "p", "p*", "s_h", "s*", "",
        "ts\\", "ts\\*", "ts\\_h", "k_h", "t_h", "p_h", "h"
    )
    private val koVowel = listOf(
        "a", "E", "ja", "jE", "V", "e", "jV", "je", "o", "wa", "wE", "we",
        "jo", "u", "wV", "we", "wi", "ju", "M", "Mi", "i"
    )
    private val koCoda = listOf(
        "", "k", "k", "k", "n", "n", "n", "t", "l", "lk", "lm", "lp", "lt",
        "lp", "l", "m", "p", "p", "p", "t", "t", "N", "t", "t", "k", "t", "p", "t"
    )

    private val englishWords = mapOf(
        "i" to "aI", "you" to "j u:", "the" to "D @", "a" to "@",
        "hello" to "h @ 'l oU", "world" to "w 3: l d", "love" to "l V v",
        "music" to "'m j u: z I k", "vocaloid" to "'v oU k @ l OI d",
        "voice" to "v OI s", "sing" to "s I N", "song" to "s O: N"
    )

    fun convert(text: String, language: String): String {
        val normalized = Normalizer.normalize(text.trim(), Normalizer.Form.NFC)
        if (normalized.isEmpty()) return ""
        return when (language.lowercase()) {
            "jpn", "ja", "jp" -> japanese(normalized)
            "kor", "ko", "kr" -> korean(normalized)
            "eng", "en" -> english(normalized)
            else -> normalized
        }
    }

    private fun japanese(input: String): String {
        val hira = input.map { ch ->
            if (ch in 'ァ'..'ヶ') (ch.code - 0x60).toChar() else ch
        }.joinToString("")
        val output = mutableListOf<String>()
        var index = 0
        while (index < hira.length) {
            val current = hira[index].toString()
            if (current == "っ") {
                val next = hira.getOrNull(index + 1)?.toString().orEmpty()
                val nextPhoneme = japaneseMora[next].orEmpty().substringBefore(" ")
                if (nextPhoneme.isNotBlank()) output += nextPhoneme
                index++
                continue
            }
            val pair = hira.substring(index, (index + 2).coerceAtMost(hira.length))
            val triple = hira.substring(index, (index + 3).coerceAtMost(hira.length))
            val matched = when {
                japaneseMora.containsKey(triple) -> triple
                japaneseMora.containsKey(pair) -> pair
                japaneseMora.containsKey(current) -> current
                current == "ー" -> ""
                current.first().isWhitespace() -> ""
                else -> current
            }
            japaneseMora[matched]?.let { output += it }
            index += matched.length.coerceAtLeast(1)
        }
        return output.joinToString(" ")
    }

    private fun korean(input: String): String {
        val result = mutableListOf<String>()
        input.forEach { char ->
            if (char in '가'..'힣') {
                val code = char.code - 0xAC00
                val onset = code / 588
                val vowel = (code % 588) / 28
                val coda = code % 28
                val phones = listOf(koOnset[onset], koVowel[vowel], koCoda[coda])
                    .filter { it.isNotBlank() }
                result += phones
            } else if (char.isWhitespace()) {
                result += "|"
            } else {
                result += char.toString()
            }
        }
        return result.joinToString(" ").replace(" | ", " | ")
    }

    private fun english(input: String): String {
        val words = input.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.joinToString(" | ") { word ->
            englishWords[word] ?: word
                .replace("th", "T").replace("sh", "S").replace("ch", "tS")
                .replace("ng", "N").replace("ee", "i:").replace("oo", "u:")
                .replace("ai", "eI").replace("ay", "eI").replace("ow", "aU")
                .replace("oi", "OI").replace("a", "{").replace("e", "E")
                .replace("i", "I").replace("o", "Q").replace("u", "V")
                .map { ch ->
                    when (ch) {
                        'r' -> "r"
                        'j' -> "dZ"
                        else -> ch.toString()
                    }
                }.joinToString(" ")
        }
    }
}
