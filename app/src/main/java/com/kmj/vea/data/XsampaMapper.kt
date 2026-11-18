package com.kmj.vea.data

import java.text.Normalizer
import java.util.Locale

/**
 * Display-oriented lyric-to-X-SAMPA helper.
 *
 * Japanese input accepts both kana and Hepburn/Kunrei-style roman letters
 * (e.g. "ra", "konnichiwa", "gakkou"). The returned lyric itself is not
 * rewritten; this helper only creates the phoneme string shown in the editor.
 * This is not the official VOCALOID G2P or a voicebank-specific phoneme engine.
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
        "ぎゃ" to "g' a", "ぎゅ" to "g' M", "ぎょ" to "g' o",
        "しゃ" to "S a", "しゅ" to "S M", "しょ" to "S o",
        "じゃ" to "dZ a", "じゅ" to "dZ M", "じょ" to "dZ o",
        "ちゃ" to "tS a", "ちゅ" to "tS M", "ちょ" to "tS o",
        "にゃ" to "J a", "にゅ" to "J M", "にょ" to "J o",
        "ひゃ" to "C a", "ひゅ" to "C M", "ひょ" to "C o",
        "びゃ" to "b' a", "びゅ" to "b' M", "びょ" to "b' o",
        "ぴゃ" to "p' a", "ぴゅ" to "p' M", "ぴょ" to "p' o",
        "みゃ" to "m' a", "みゅ" to "m' M", "みょ" to "m' o",
        "りゃ" to "4' a", "りゅ" to "4' M", "りょ" to "4' o"
    )

    private val romanToKana = mapOf(
        // Three-letter combinations.
        "kya" to "きゃ", "kyu" to "きゅ", "kyo" to "きょ",
        "gya" to "ぎゃ", "gyu" to "ぎゅ", "gyo" to "ぎょ",
        "sha" to "しゃ", "shu" to "しゅ", "sho" to "しょ",
        "sya" to "しゃ", "syu" to "しゅ", "syo" to "しょ",
        "ja" to "じゃ", "ju" to "じゅ", "jo" to "じょ",
        "jya" to "じゃ", "jyu" to "じゅ", "jyo" to "じょ",
        "cha" to "ちゃ", "chu" to "ちゅ", "cho" to "ちょ",
        "tya" to "ちゃ", "tyu" to "ちゅ", "tyo" to "ちょ",
        "nya" to "にゃ", "nyu" to "にゅ", "nyo" to "にょ",
        "hya" to "ひゃ", "hyu" to "ひゅ", "hyo" to "ひょ",
        "bya" to "びゃ", "byu" to "びゅ", "byo" to "びょ",
        "pya" to "ぴゃ", "pyu" to "ぴゅ", "pyo" to "ぴょ",
        "mya" to "みゃ", "myu" to "みゅ", "myo" to "みょ",
        "rya" to "りゃ", "ryu" to "りゅ", "ryo" to "りょ",
        // Standard syllables and common aliases.
        "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",
        "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
        "ga" to "が", "gi" to "ぎ", "gu" to "ぐ", "ge" to "げ", "go" to "ご",
        "sa" to "さ", "shi" to "し", "si" to "し", "su" to "す", "se" to "せ", "so" to "そ",
        "za" to "ざ", "ji" to "じ", "zi" to "じ", "zu" to "ず", "ze" to "ぜ", "zo" to "ぞ",
        "ta" to "た", "chi" to "ち", "ti" to "ち", "tsu" to "つ", "tu" to "つ",
        "te" to "て", "to" to "と",
        "da" to "だ", "di" to "ぢ", "du" to "づ", "de" to "で", "do" to "ど",
        "na" to "な", "ni" to "に", "nu" to "ぬ", "ne" to "ね", "no" to "の",
        "ha" to "は", "hi" to "ひ", "fu" to "ふ", "hu" to "ふ", "he" to "へ", "ho" to "ほ",
        "ba" to "ば", "bi" to "び", "bu" to "ぶ", "be" to "べ", "bo" to "ぼ",
        "pa" to "ぱ", "pi" to "ぴ", "pu" to "ぷ", "pe" to "ぺ", "po" to "ぽ",
        "ma" to "ま", "mi" to "み", "mu" to "む", "me" to "め", "mo" to "も",
        "ya" to "や", "yu" to "ゆ", "yo" to "よ",
        "ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ",
        "wa" to "わ", "wo" to "を",
        "va" to "ゔぁ", "vi" to "ゔぃ", "vu" to "ゔ", "ve" to "ゔぇ", "vo" to "ゔぉ"
    )

    private val romanKeys = romanToKana.keys.sortedByDescending { it.length }

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
        return when (language.lowercase(Locale.ROOT)) {
            "jpn", "ja", "jp" -> japanese(normalized)
            "kor", "ko", "kr" -> korean(normalized)
            "eng", "en" -> english(normalized)
            else -> normalized
        }
    }

    /**
     * Converts Japanese romaji to hiragana for pronunciation lookup.
     * The original lyric text remains untouched in the note model and UI.
     */
    fun romanToHiragana(input: String): String {
        val source = input.lowercase(Locale.ROOT)
        val result = StringBuilder()
        var index = 0

        while (index < source.length) {
            val current = source[index]

            if (current.isWhitespace()) {
                result.append(current)
                index++
                continue
            }

            if (current == 'n') {
                val next = source.getOrNull(index + 1)
                when {
                    next == '\'' -> {
                        result.append('ん')
                        index += 2
                        continue
                    }
                    next == 'n' -> {
                        val afterDoubleN = source.getOrNull(index + 2)
                        result.append('ん')
                        // "konnichiwa" is ko + n + ni: consume only the first n
                        // when the second n begins the following vowel syllable.
                        index += if (afterDoubleN != null && afterDoubleN in "aiueoy") 1 else 2
                        continue
                    }
                    next == null -> {
                        result.append('ん')
                        index++
                        continue
                    }
                    next !in "aiueoy" -> {
                        result.append('ん')
                        index++
                        continue
                    }
                }
            }

            if (current in 'a'..'z' &&
                index + 1 < source.length &&
                source[index + 1] == current &&
                current !in "aeioun"
            ) {
                result.append('っ')
                index++
                continue
            }

            val match = romanKeys.firstOrNull { key ->
                source.startsWith(key, index)
            }
            if (match != null) {
                result.append(romanToKana.getValue(match))
                index += match.length
                continue
            }

            result.append(current)
            index++
        }
        return result.toString()
    }

    private fun japanese(input: String): String {
        val romanized = if (input.any { it in 'A'..'Z' || it in 'a'..'z' }) {
            romanToHiragana(input)
        } else {
            input
        }
        val hira = romanized.map { ch ->
            when {
                ch in 'ァ'..'ヶ' -> (ch.code - 0x60).toChar()
                ch in 'ぁ'..'ゖ' -> ch
                else -> ch
            }
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
            val matched = when {
                japaneseMora.containsKey(pair) -> pair
                japaneseMora.containsKey(current) -> current
                current == "ー" || current.first().isWhitespace() -> ""
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
        return result.joinToString(" ")
    }

    private fun english(input: String): String {
        val words = input.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
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
