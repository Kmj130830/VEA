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
