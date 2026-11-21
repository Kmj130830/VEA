package com.kmj.vea.engine

/**
 * Lexical segmentation used by the recovered VSM Note lyric preparation path.
 * Only ASCII space (U+0020) terminates a token. This deliberately does not
 * perform language-specific G2P or convert tokens to Yamaha command bytes.
 */
object VsmLyricTokenizer {
    fun tokenize(lyric: String): List<String> {
        if (lyric.isEmpty()) return emptyList()

        val tokens = ArrayList<String>()
        var tokenStart = -1

        lyric.forEachIndexed { index, character ->
            if (character == ' ') {
                if (tokenStart >= 0) {
                    tokens += lyric.substring(tokenStart, index)
                    tokenStart = -1
                }
            } else if (tokenStart < 0) {
                tokenStart = index
            }
        }

        if (tokenStart >= 0) {
            tokens += lyric.substring(tokenStart)
        }
        return tokens
    }
}
