# V7 model container and S5API payload decoder

Date: 2026-11-20

## Five supplied model files: confirmed decoded JSON roots

Static analysis of the authorized S5API.dll identified `FUN_18002aa90`, the byte-preparation routine called immediately before the model JSON parser. The routine has now been ported independently to Python and portable C++ and compared byte-for-byte against all five supplied model members.

| Member | Magic | File bytes | +0x04 value | C++ decoded bytes | JSON root entries | Native == Python |
|---|---|---:|---:|---:|---:|---|
| AI_Megpoid.vtb2 | VTBR | 23,541,731 | 23,541,595 | 23,541,587 | 8 | yes |
| AI_Megpoid.vpit | VPIT | 16,754 | 16,618 | 16,610 | 1 | yes |
| COMMON7.vpit | VPIT | 3,925 | 3,789 | 3,781 | 1 | yes |
| VOCALOID7.vhnv | VHNV | 5,485,182 | 5,485,046 | 5,485,038 | 3 | yes |
| VOCALOID7.vtmg | VTMG | 148,942 | 148,806 | 148,798 | 3 | yes |

The decoded output from every file is valid UTF-8 JSON with an object root. The verification reads ZIP members in memory and reports only sizes and a few short root-key samples; it does not print or publish tensor contents.

## Recovered header and byte ranges

The common fields used by the decoder are:

| Offset | Size | Observation |
|---:|---:|---|
| 0x00 | 4 | Magic. Observed target members use VTBR, VPIT, VHNV or VTMG. The decoder also recognizes VTMB and VPNV. |
| 0x04 | 4 | Little-endian length value. For each supplied file it equals `file_size - 136`. |
| 0x08 | 4 | Opaque format/version word. Supplied files use 3, 6 or 16. |
| 0x0c | 4 | 32-bit seed consumed by the payload transform. |
| 0x10 | variable | Encoded byte stream consumed by the transform. |

This corrects the earlier interpretation that bytes +0x0c through +0x87 were all opaque metadata. Only the +0x08 word remains uninterpreted here. The seed and encoded stream are used by the recovered transform.

For recognized magic values, `FUN_18002aa90` computes an exclusive input end as `little_endian_u32(+0x04) + 8`, clamping it to the supplied input size when it is below 16 or beyond the available input. It then processes bytes beginning at +0x10. For all five supplied files the decoded byte count is exactly `file_size - 144`; the final 128 bytes are outside this JSON input range. Their separate meaning has not been established.

## Recovered transformation

The routine initializes three 32-bit state words:

- `A = 0x159A55E5`
- `B = 0x1F123BB5`
- `C = 0x05491333`

The seed is read from +0x0c. For each complete 4-byte input word, it applies the recovered 32-bit xorshift/feedback update, XORs the resulting state word with the little-endian input word and writes a little-endian output word. A separate final 1–3-byte path handles the remaining input bytes.

For inputs shorter than 16 bytes the function returns an empty range. For inputs of at least 16 bytes with an unrecognized magic, it copies the bytes unchanged. No vendor DLL is executed by the port.

## Android implementation

The portable implementation is exposed as:

- C ABI: `vea_s5_decode_model_payload`
- JNI/Kotlin: `NativeS5TensorBridge.decodeModelPayload`
- JSON integration: `S5ModelObjectTree.parseModelPayload`

The native API supports a size-query call, checks output capacity before writing, and preserves the input for the non-magic copy path. Tests cover a multiword vector, a word-plus-tail vector, insufficient output capacity, unrecognized-magic passthrough and short input.

CI also compiles the host C++ implementation and differentially compares its full output bytes with the independent Python reference for all five actual supplied files. The current verification reports five matching native outputs and five JSON roots.

## Remaining boundaries

This milestone prepares the JSON byte range and parses the JSON object tree. It does not yet map the model's hashed tree entries into complete tensor/layer instances, execute the full neural graph, run the recurrent forward equations, reconstruct conditioning/feature inputs, or perform spectral-to-waveform synthesis. No Android Megpoid singing render has been produced yet.
