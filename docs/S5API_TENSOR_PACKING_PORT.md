# S5API tensor packing port — first native implementation

Date: 2026-11-19

## Reverse-engineered contract implemented

The native implementation ports only the recovered tensor memory boundary, not the complete model loader or neural network.

### 32-byte arena

FUN_180006a80 advances an arena by `count * 4` bytes and rounds nonzero allocations up to 32-byte boundaries. FUN_180006b10 does the same for records of `count * 0x18` bytes. The implementation uses overflow-checked arithmetic and a 32-byte aligned backing allocation.

### 0x18-byte matrix descriptor

FUN_180064670 writes:
- +0x00: `param_5` (axis0 count)
- +0x04: `param_4` (axis1 count)
- +0x08: padded axis0 count, rounded up to a multiple of 8
- +0x10: pointer to float32 matrix storage

The C structure has a compile-time asserted size of 24 bytes and asserted offsets. Semantic axis names remain neutral.

### Interleaved source to padded matrix bank

The decompiled copy loop reads source index:
`((axis0 * axis1_count + axis1) * matrix_count + matrix)`

It writes to:
`matrix[matrix].values[axis1 * padded_axis0 + axis0]`

This is more specific than a generic row-major copy: values for the matrix bank are interleaved as the final source dimension, then distributed into individual padded matrices.

The C++ port implements this exact index mapping and zero-initializes padding so that SIMD-width reads cannot expose uninitialized memory. The logical matrix contents follow the recovered copy loop; zeroing padding is a safety refinement.

### Double-array conversion

FUN_180006c80's tag-4 branch checks each 16-byte entry's type tag at +0 and reads a double at +8, then casts it to float32. The new helper implements the numeric conversion after the outer tree parser has validated node and element tags.

## Files

- `app/src/main/cpp/include/vea_s5_tensor.h`
- `app/src/main/cpp/vea_s5_tensor.cpp`
- `app/src/main/cpp/tests/vea_s5_tensor_test.cpp`

The host test validates descriptor offsets/size, 32-byte alignment, float conversion, the interleaved-to-padded mapping, zeroed padding, bounds failure, and rollback behavior.

## Explicit limits

- The outer VTBR/VPIT/VHNV/VTMG container is not parsed here.
- The JSON-like typed object tree is not yet decoded from the model files.
- The MD5-based key derivation is not implemented until the exact input encoding and salt sequence are proven.
- GRU direction and naming evidence do not yet recover its complete gate ordering or execution equations.
- This library is a tensor memory/layout primitive, not an AI voice renderer. No Megpoid V7 audio is produced by this code.
