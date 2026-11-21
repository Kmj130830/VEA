# S5API 16-byte container prefix and body transform

Date: 2026-11-21

## Observed model-file envelope

The five checked-in AI Megpoid/shared VOCALOID7 assets all begin with a 16-byte S5API prefix. The little-endian value at +0x04 equals `file_size - 136` for these catalog files.

| Member | Magic | File bytes | +0x04 declared span | +0x08 format word | +0x0c seed |
|---|---|---:|---:|---:|---:|
| AI_Megpoid.vtb2 | VTBR | 23,541,731 | 23,541,595 | 16 | file-specific |
| AI_Megpoid.vpit | VPIT | 16,754 | 16,618 | 16 | file-specific |
| COMMON7.vpit | VPIT | 3,925 | 3,789 | 6 | file-specific |
| VOCALOID7.vhnv | VHNV | 5,485,182 | 5,485,046 | 3 | file-specific |
| VOCALOID7.vtmg | VTMG | 148,942 | 148,806 | 3 | file-specific |

The seed field is a 32-bit little-endian value and differs by file; it is not an opaque 124-byte metadata region.

## Prefix fields and the body range

Static decompilation of S5API `FUN_18002aa90` establishes the following prefix layout:

| Offset | Size | Meaning |
|---:|---:|---|
| 0x00 | 4 | ASCII magic. The routine accepts VTMB, VTBR, VTMG, VPIT, VNPV, and VHNV. |
| 0x04 | 4 | Little-endian declared span. The routine adds 8 to this value before clamping. |
| 0x08 | 4 | Format/version word. It is preserved but not consumed by this transform. |
| 0x0c | 4 | Little-endian 32-bit transform seed. |

The decoded body end is computed as `min(declared_span + 8, file_size)`, except that a value below the 16-byte prefix boundary falls back to the actual file size. The output starts at file offset 16 and ends at that clamped boundary.

For the five checked-in assets, `declared_span == file_size - 136`. Therefore the transformed body length is `file_size - 144`: after the 16-byte prefix and decoded body, 128 trailing bytes remain untouched by this routine. That 128-byte tail is preserved as uninterpreted file data; no checksum, signature, or key role is assumed.

## Recovered byte transform

The complete decompiler output for `FUN_18002aa90` reveals a seeded 32-bit XOR stream transform. For each complete little-endian 32-bit input word, the routine updates a xorshift-like state and XORs the resulting word with the encrypted input. The last one to three bytes use a separate folded-state path rather than the regular full-word loop.

VEA now implements this transform in:
- `app/src/main/cpp/vea_s5_container.cpp` — portable C++ prefix parser and body transform;
- `app/src/main/cpp/vea_s5_tensor_jni.cpp` — JNI byte-array bridge;
- `NativeS5TensorBridge.decodeContainerPayload()` — Kotlin entry point.

The native implementation follows the recovered state update and tail-byte expression. It does not hard-code the 128-byte catalog tail into the generic transform; it follows the vendor's declared-span clamp. The package installer separately checks the known catalog invariant `declared_span == file_size - 136` in addition to the catalog SHA-256.

## Tests and current boundary

The native regression fixture uses a 16-byte VTBR prefix, a deterministic seed, 13 encrypted body bytes (three complete words plus a one-byte remainder group), and a nonzero 128-byte trailer. Its decoded output is checked byte-for-byte, including the special remainder path and the untouched trailer boundary. All six observed magic values are accepted by the prefix parser.

This is the first executable model-payload decoding stage, not the model tensor loader. The output still needs to be parsed as the JSON-like object tree, hashed weight/bias keys need exact derivation, typed arrays need to be extracted, and the model graph still needs to execute before audio can be rendered.
