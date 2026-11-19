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
        "", "k", "k", "k", "n", "n", "n", "t", "l", "lk", "lm", "lp", "ls",
        "lt", "lp", "lh", "m", "p", "ps", "s", "s", "N", "tS", "tS", "k", "t", "p", "h"
    )

    /**
     * Curated North-American English pronunciation lexicon.
     * Each word can carry multiple complete pronunciations. The notation uses
     * the VEA English-phone display convention (X-SAMPA-like); this is a
     * fallback, not Yamaha G2PA output.
     */
    private val englishLexicon: Map<String, List<String>> = mapOf(
        "a" to listOf("@"), "an" to listOf("{ n"), "the" to listOf("D @", "D i:"),
        "i" to listOf("aI"), "me" to listOf("m i:", "m e"),
        "you" to listOf("j u:"), "he" to listOf("h i:"), "she" to listOf("S i:"),
        "we" to listOf("w i:"), "they" to listOf("D eI"), "it" to listOf("I t"),
        "this" to listOf("D I s"), "that" to listOf("D { t"),
        "hello" to listOf("h @ . l oU"), "world" to listOf("w 3: l d"),
        "love" to listOf("l V v"), "music" to listOf("m j u: . z I k"),
        "voice" to listOf("v OI s"), "sing" to listOf("s I N"),
        "song" to listOf("s O: N"), "vocaloid" to listOf("v oU . k @ . l OI d"),
        "singing" to listOf("s I N . I N"), "singer" to listOf("s I N . 3:"),
        "beautiful" to listOf("b j u: . t I . f @ l"),
        "computer" to listOf("k @ m . p j u: . t 3:"),
        "water" to listOf("w O . t 3:"), "little" to listOf("l I . t @ l"),
        "coffee" to listOf("k O . f i:"), "people" to listOf("p i: . p @ l"),
        "friend" to listOf("f r\\ E n d"), "dream" to listOf("d r\\ i: m"),
        "night" to listOf("n aI t"), "day" to listOf("d eI"),
        "today" to listOf("t @ . d eI"), "tomorrow" to listOf("t @ . m A . r oU"),
        "rain" to listOf("r\\ eI n"), "rainbow" to listOf("r\\ eI n . b oU"),
        "apple" to listOf("{ . p @ l"), "orange" to listOf("O . r\\ @ n dZ"),
        "banana" to listOf("b @ . n { . n @"), "again" to listOf("@ . g E n", "@ . g eI n"),
        "because" to listOf("b I . k O z"), "often" to listOf("O . f @ n", "O . f t @ n"),
        "data" to listOf("d eI . t @", "d { . t @"),
        "tomato" to listOf("t @ . m eI . t oU", "t @ . m A . t oU"),
        "route" to listOf("r\\ u: t", "r\\ aU t"),
        "either" to listOf("i: . D @ r\\", "aI . D @ r\\"),
        "neither" to listOf("n i: . D @ r\\", "n aI . D @ r\\"),
        "read" to listOf("r\\ i: d", "r\\ e d"),
        "live" to listOf("l I v", "l aI v"),
        "wind" to listOf("w I n d", "w aI n d"),
        "lead" to listOf("l i: d", "l e d"),
        "use" to listOf("j u: z", "j u: s"),
        "close" to listOf("k l oU s", "k l oU z"),
        "tear" to listOf("t E r\\", "t I r\\"),
        "bow" to listOf("b aU", "b oU"),
        "bass" to listOf("b eI s", "b { s"),
        "desert" to listOf("d E . z @ r\\ t", "d I . z 3: t"),
        "refuse" to listOf("r\\ I . f j u: z", "r\\ @ . f j u: s"),
        "produce" to listOf("p r\\ @ . d u: s", "p r\\ oU . d u: s"),
        "record" to listOf("r\\ E . k 3: d", "r\\ I . k O: r\\ d"),
        "present" to listOf("p r\\ E . z @ n t", "p r\\ i: . z E n t"),
        "object" to listOf("A b . dZ E k t", "@ b . dZ E k t"),
        "minute" to listOf("m I . n I t", "m aI . n u: t"),
        "row" to listOf("r\\ oU", "r\\ aU"),
        "sow" to listOf("s oU", "s aU"),
        "wound" to listOf("w u: n d", "w aU n d"),
        "polish" to listOf("p A . l I S", "p oU . l I S"),
        "separate" to listOf("s E . p @ . r\\ @ t", "s E . p @ . r\\ eI t"),
        "house" to listOf("h aU s", "h aU z"),
        "resume" to listOf("r\\ E . z u: m", "r\\ I . z u: m eI"),
        "affect" to listOf("@ . f E k t", "{ . f E k t"),
        "contract" to listOf("k A n . t r\\ { k t", "k @ n . t r\\ { k t"),
        "content" to listOf("k A n . t E n t", "k @ n . t E n t"),
        "invalid" to listOf("I n . v { . l I d", "I n . v @ . l I d"),
        "permit" to listOf("p 3: . m I t", "p 3: . m I t"),
        "learned" to listOf("l 3: n d", "l 3: n I d"),
        "entrance" to listOf("{ n . t r\\ @ n s", "E n . t r\\ @ n s"),
        "bassoon" to listOf("b @ . s u: n"),
        "sugar" to listOf("S U . g 3:"),
        "sure" to listOf("S U r\\", "S O: r\\"),
        "schedule" to listOf("s k E . dZ u: l", "S E . dZ u: l"),
        "garage" to listOf("g @ . r\\ A Z", "g @ . r\\ A dZ"),
        "address" to listOf("{ . d r\\ E s", "@ . d r\\ E s"),
        "adult" to listOf("{ . d V l t", "@ . d V l t"),
        "mature" to listOf("m @ . t j U r\\", "m @ . tS U r\\"),
        "eitherway" to listOf("i: . D @ r\\ . w eI"),
        "though" to listOf("D oU"), "through" to listOf("T r\\ u:"),
        "thought" to listOf("T O: t"), "tough" to listOf("t V f"),
        "cough" to listOf("k O f"), "bough" to listOf("b aU"),
        "rough" to listOf("r\\ V f"), "enough" to listOf("I . n V f")
    )

    private val englishVowelPhones = setOf(
        "A", "{", "V", "O:", "aU", "aI", "e", "E", "3:", "eI",
        "I", "i:", "oU", "OI", "U", "u:", "@"
    )

    private val englishGraphemes = listOf(
        "tion" to "S @ n", "sion" to "Z @ n", "ture" to "tS 3:",
        "ough" to "V f", "eigh" to "eI", "igh" to "aI",
        "ph" to "f", "sh" to "S", "ch" to "tS", "th" to "T",
        "ng" to "N", "ck" to "k", "qu" to "k w", "wh" to "w",
        "wr" to "r\\", "kn" to "n", "gn" to "n", "mb" to "m",
        "ee" to "i:", "ea" to "i:", "ai" to "eI", "ay" to "eI",
        "oa" to "oU", "oe" to "oU", "oo" to "u:",
        "oi" to "OI", "oy" to "OI", "ou" to "aU", "ow" to "aU",
        "ar" to "A r\\", "er" to "3:", "ir" to "3:", "ur" to "3:",
        "or" to "O: r\\"
    ).sortedByDescending { it.first.length }

    private val englishSinglePhones = mapOf(
        'b' to "b", 'c' to "k", 'd' to "d", 'f' to "f", 'g' to "g",
        'h' to "h", 'j' to "dZ", 'k' to "k", 'l' to "l", 'm' to "m",
        'n' to "n", 'p' to "p", 'q' to "k", 'r' to "r\\", 's' to "s",
        't' to "t", 'v' to "v", 'w' to "w", 'x' to "k s", 'y' to "j",
        'z' to "z", 'a' to "{", 'e' to "E", 'i' to "I", 'o' to "Q",
        'u' to "V"
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

    // Compatibility-jamo order used by Korean mobile keyboards.
    private val koreanInitials = listOf(
        "ㄱ", "ㄲ", "ㄴ", "ㄷ", "ㄸ", "ㄹ", "ㅁ", "ㅂ", "ㅃ",
        "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅉ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"
    )
    private val koreanVowels = listOf(
        "ㅏ", "ㅐ", "ㅑ", "ㅒ", "ㅓ", "ㅔ", "ㅕ", "ㅖ", "ㅗ", "ㅘ",
        "ㅙ", "ㅚ", "ㅛ", "ㅜ", "ㅝ", "ㅞ", "ㅟ", "ㅠ", "ㅡ", "ㅢ", "ㅣ"
    )
    private val koreanFinals = listOf(
        "", "ㄱ", "ㄲ", "ㄳ", "ㄴ", "ㄵ", "ㄶ", "ㄷ", "ㄹ", "ㄺ",
        "ㄻ", "ㄼ", "ㄽ", "ㄾ", "ㄿ", "ㅀ", "ㅁ", "ㅂ", "ㅄ", "ㅅ",
        "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"
    )
    private val compoundVowels = mapOf(
        "ㅗㅏ" to "ㅘ", "ㅗㅐ" to "ㅙ", "ㅗㅣ" to "ㅚ",
        "ㅜㅓ" to "ㅝ", "ㅜㅔ" to "ㅞ", "ㅜㅣ" to "ㅟ",
        "ㅡㅣ" to "ㅢ"
    )
    private val compoundFinals = mapOf(
        "ㄱㅅ" to "ㄳ", "ㄴㅈ" to "ㄵ", "ㄴㅎ" to "ㄶ",
        "ㄹㄱ" to "ㄺ", "ㄹㅁ" to "ㄻ", "ㄹㅂ" to "ㄼ",
        "ㄹㅅ" to "ㄽ", "ㄹㅌ" to "ㄾ", "ㄹㅍ" to "ㄿ",
        "ㄹㅎ" to "ㅀ", "ㅂㅅ" to "ㅄ"
    )

    /**
     * Join compatibility jamo emitted by some IMEs into precomposed Hangul.
     * NFC alone does not combine compatibility jamo such as ㅇ + ㅡ + ㅁ.
     */
    fun composeKoreanJamo(input: String): String {
        val source = Normalizer.normalize(input, Normalizer.Form.NFC)
        val output = StringBuilder()
        var i = 0
        while (i < source.length) {
            val initial = koreanInitials.indexOf(source[i].toString())
            val vowelAt = i + 1
            val vowel = source.getOrNull(vowelAt)?.toString().orEmpty()
            val vowelIndex = koreanVowels.indexOf(vowel)
            if (initial < 0 || vowelIndex < 0) {
                output.append(source[i])
                i++
                continue
            }

            var resolvedVowel = vowel
            var consumed = 2
            val compound = source.substring(i + 1, (i + 3).coerceAtMost(source.length))
            compoundVowels[compound]?.let {
                resolvedVowel = it
                consumed = 3
            }
            val resolvedVowelIndex = koreanVowels.indexOf(resolvedVowel)
            var finalJamo = ""
            val finalAt = i + consumed
            val firstFinal = source.getOrNull(finalAt)?.toString().orEmpty()
            val firstFinalIndex = koreanFinals.indexOf(firstFinal)

            if (firstFinalIndex > 0) {
                val afterFinalIsVowel = source.getOrNull(finalAt + 1)?.toString()
                    ?.let { koreanVowels.contains(it) } == true
                if (!afterFinalIsVowel) {
                    finalJamo = firstFinal
                    consumed += 1
                    val secondFinal = source.getOrNull(finalAt + 1)?.toString().orEmpty()
                    val combined = compoundFinals[firstFinal + secondFinal]
                    val afterCombinedIsVowel = source.getOrNull(finalAt + 2)?.toString()
                        ?.let { koreanVowels.contains(it) } == true
                    if (combined != null && !afterCombinedIsVowel) {
                        finalJamo = combined
                        consumed += 1
                    }
                }
            }

            val finalIndex = koreanFinals.indexOf(finalJamo).coerceAtLeast(0)
            val syllableCode = 0xAC00 + ((initial * 21 + resolvedVowelIndex) * 28) + finalIndex
            output.append(syllableCode.toChar())
            i += consumed
        }
        return output.toString()
    }

    private fun korean(input: String): String {
        // Keep word boundaries explicit and normalize repeated spaces. Within a
        // word, use "." as the syllable boundary so adjacent Hangul syllables
        // are not displayed as one unsegmented phone stream.
        val words = input.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.joinToString(" | ") { rawWord ->
            val composed = composeKoreanJamo(rawWord)
            val syllableGroups = mutableListOf<String>()

            composed.forEach { char ->
                val group = if (char in '가'..'힣') {
                    val code = char.code - 0xAC00
                    val onset = code / 588
                    val vowel = (code % 588) / 28
                    val coda = code % 28
                    listOf(koOnset[onset], koVowel[vowel], koCoda[coda])
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                } else {
                    val jamo = char.toString()
                    val onsetIndex = koreanInitials.indexOf(jamo)
                    val vowelIndex = koreanVowels.indexOf(jamo)
                    when {
                        onsetIndex >= 0 -> koOnset[onsetIndex].takeIf { it.isNotBlank() }.orEmpty()
                        vowelIndex >= 0 -> koVowel[vowelIndex]
                        else -> jamo
                    }
                }
                if (group.isNotBlank()) syllableGroups += group
            }

            syllableGroups.joinToString(" . ")
        }
    }

    private fun englishWordSuggestions(wordInput: String): List<String> {
        val word = wordInput.lowercase(Locale.ROOT).trim()
        englishLexicon[word]?.let { return it }
        return listOf(englishFallback(word))
    }

    private fun englishFallback(word: String): String {
        if (word.isBlank()) return ""
        val out = mutableListOf<String>()
        var i = 0
        while (i < word.length) {
            val remaining = word.substring(i)
            val match = englishGraphemes.firstOrNull { remaining.startsWith(it.first) }
            if (match != null) {
                out += match.second.split(" ")
                i += match.first.length
                continue
            }
            if (word[i] == 'e' && i == word.lastIndex && i > 1) {
                i++
                continue
            }
            when (word[i]) {
                'c' -> out += if (word.getOrNull(i + 1)?.let { it in "eiy" } == true) "s" else "k"
                'g' -> out += if (word.getOrNull(i + 1)?.let { it in "eiy" } == true) "dZ" else "g"
                'a', 'e', 'i', 'o', 'u', 'b', 'd', 'f', 'h', 'j', 'k', 'l', 'm', 'n',
                'p', 'q', 'r', 's', 't', 'v', 'w', 'x', 'y', 'z' ->
                    englishSinglePhones[word[i]]?.split(" ")?.let { out += it }
            }
            i++
        }
        return out.joinToString(" ")
    }

    private fun english(input: String): String {
        return input.lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .joinToString(" | ") { englishWordSuggestions(it).firstOrNull().orEmpty() }
    }

    /**
     * Returns up to six phrase-level pronunciation variants. Alternative
     * readings are produced per word, then combined without losing word boundaries.
     */
    fun pronunciationSuggestions(text: String, language: String): List<String> {
        val normalized = text.trim().lowercase(Locale.ROOT)
        if (normalized.isBlank()) return emptyList()
        if (language.lowercase(Locale.ROOT) !in setOf("eng", "en")) {
            return listOf(convert(text, language)).filter { it.isNotBlank() }
        }

        val words = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }
        var combinations = listOf(emptyList<String>())
        for (word in words) {
            val choices = englishWordSuggestions(word).distinct().take(3)
            combinations = combinations.flatMap { existing ->
                choices.map { existing + it }
            }.take(6)
        }
        return combinations
            .map { it.joinToString(" | ") }
            .distinct()
            .filter { it.isNotBlank() }
            .take(6)
    }
}
