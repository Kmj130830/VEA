package com.kmj.vea.engine

import java.security.MessageDigest

/**
 * Recreates S5API's hashed tensor-member lookup key.
 *
 * Static trace:
 * - FUN_180020190 appends ".weight" to the layer base name.
 * - FUN_1800214c0 appends ".bias" to the layer base name.
 * - Both append the same 24-byte binary suffix before calling the MD5 helper.
 * - FUN_18001acc0 writes the four MD5 state words as zero-padded, lowercase,
 *   eight-digit hexadecimal unsigned integers.
 *
 * This matches the S5API key construction. It does not parse the model object
 * tree or read tensor values from a VTB2 file.
 */
object S5ModelTensorKey {
    enum class Member(val suffix: String) {
        WEIGHT(".weight"),
        BIAS(".bias")
    }

    private val digestSuffix = byteArrayOf(
        0x69, 0x23, 0x33, 0x65, 0x2e, 0x63, 0x5a, 0x54,
        0x25, 0x35, 0x6b, 0x39, 0x6a, 0x32, 0x64, 0x2c,
        0x32, 0x5a, 0x72, 0x53, 0x2f, 0x2b, 0x2f, 0x32
    )

    /**
     * Produces the 32-character key used by the S5API object lookup.
     * The caller passes the layer base, e.g. "rnn.weight_ih_l0".
     */
    fun derive(baseName: String, member: Member): String {
        require(baseName.isNotBlank()) { "Tensor base name must not be empty." }
        require(baseName.all { it.code in 0x20..0x7e }) {
            "S5API tensor base names must be printable ASCII."
        }
        return digestKey((baseName + member.suffix).toByteArray(Charsets.US_ASCII))
    }

    /**
     * Recurrent S5API code constructs the complete member name first
     * (e.g. ".weight_ih_l" + layer index), then appends the digest suffix
     * directly. It does not add another ".weight" or ".bias" after that name.
     */
    fun deriveParameterName(parameterName: String): String {
        require(parameterName.isNotBlank()) { "Tensor parameter name must not be empty." }
        require(parameterName.all { it.code in 0x20..0x7e }) {
            "S5API tensor parameter names must be printable ASCII."
        }
        return digestKey(parameterName.toByteArray(Charsets.US_ASCII))
    }

    private fun digestKey(nameBytes: ByteArray): String {
        val input = nameBytes + digestSuffix
        val digest = MessageDigest.getInstance("MD5").digest(input)
        check(digest.size == 16) { "Unexpected MD5 digest size." }

        // The VTMG tree stores the 16 MD5 digest bytes in canonical byte order.
        // Formatting each little-endian MD5 state word as an integer reverses
        // its four bytes and fails to match real keys such as pre1.weight.
        return buildString(32) {
            for (byte in digest) {
                append(((byte.toInt() ushr 4) and 0x0f).toString(16))
                append((byte.toInt() and 0x0f).toString(16))
            }
        }
    }
}
