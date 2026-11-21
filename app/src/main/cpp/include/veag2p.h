#ifndef VEA_G2P_H
#define VEA_G2P_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define VEA_G2P_ABI_VERSION 0x00010000u

typedef enum VeaG2pStatus {
    VEA_G2P_STATUS_OK = 0,
    VEA_G2P_STATUS_INVALID_ARGUMENT = 1,
    VEA_G2P_STATUS_INVALID_UTF8 = 2,
    VEA_G2P_STATUS_BUFFER_TOO_SMALL = 3,
    VEA_G2P_STATUS_NOT_IMPLEMENTED = 4
} VeaG2pStatus;

/*
 * Returns the version of the VEA native bridge ABI.
 * This does not identify or emulate a Yamaha library version.
 */
uint32_t vea_g2p_bridge_abi_version(void);

/*
 * Returns a bit mask of complete language phonemizers available in this build.
 * A zero mask means no language phonemizer is ready.
 */
uint32_t vea_g2p_supported_language_mask(void);

/*
 * Looks up the packed onset/vowel/coda indices used by the Korean manager
 * for one precomposed modern Hangul syllable (U+AC00..U+D7A3).
 *
 * The 16-bit result uses:
 *   bits 10..14: onset table index
 *   bits  5.. 9: vowel table index
 *   bits  0.. 4: coda table index
 *   bit      15: valid Hangul-table marker
 *
 * This is an exact intermediate lookup primitive, not a phoneme converter.
 * Returns INVALID_ARGUMENT for non-Hangul input or a null output pointer.
 */
VeaG2pStatus vea_g2p_korean_hangul_packed_indices(
    uint32_t codepoint,
    uint16_t* packed_indices
);

/*
 * Reverse lookup for the Korean packed onset/vowel/coda representation.
 * Mirrors FUN_180008660's lookup behavior for modern Hangul:
 * - exact packed values map back to their precomposed syllable;
 * - a requested coda index of zero matches the table's empty-coda entry
 *   (packed low bits 1).
 * Returns INVALID_ARGUMENT when no modern Hangul table entry matches.
 */
VeaG2pStatus vea_g2p_korean_hangul_from_packed_indices(
    uint16_t packed_indices,
    uint32_t* codepoint
);

/*
 * Applies two isolated single-syllable rewrites recovered from
 * FUN_180008730:
 * - packed vowel 12 -> 10 unless onset index is 7 or 13;
 * - packed vowel 28 -> 29 unless onset index is 13.
 *
 * Other syllables are returned unchanged. Adjacent-syllable transformations
 * and marker-dependent behavior are not implemented by this helper.
 */
VeaG2pStatus vea_g2p_korean_apply_single_syllable_rules(
    uint32_t codepoint,
    uint32_t* transformed_codepoint
);

/*
 * Base phoneme labels looked up by the Korean manager for a modern Hangul
 * syllable. These are table components before adjacent-syllable rules,
 * marker processing, and syllable-level output assembly.
 *
 * The strings are null-terminated ASCII labels from the supplied DLL tables;
 * they are not asserted to be standard X-SAMPA or final spoken phonemes.
 */
typedef struct VeaKoreanPhonemeComponents {
    char onset[4];
    char vowel[3];
    char coda[3];
} VeaKoreanPhonemeComponents;

VeaG2pStatus vea_g2p_korean_syllable_components(
    uint32_t codepoint,
    VeaKoreanPhonemeComponents* components
);

/*
 * Applies the recovered Korean input-preprocessing subset:
 * - U+30FC and U+2212 are replaced with U+002D.
 * - 32 ordered, length-preserving Korean lexical replacements are applied.
 *
 * This does not implement the Hangul phonological transformation or G2P
 * syllable/phoneme generation stages.
 *
 * Input and output are standard UTF-8 byte sequences. output_size is an
 * in/out parameter: supply the output capacity; on BUFFER_TOO_SMALL it is set
 * to the required byte count (excluding a NUL terminator). On success it is
 * set to the number of bytes written. The output is not NUL-terminated.
 *
 * This is only a verified preprocessing primitive, not the Yamaha G2P engine.
 */
VeaG2pStatus vea_g2p_normalize_korean_input_utf8(
    const char* input,
    size_t input_size,
    char* output,
    size_t* output_size
);

#ifdef __cplusplus
}
#endif

#endif
