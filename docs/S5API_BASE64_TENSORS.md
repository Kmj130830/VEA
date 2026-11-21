# S5API Base64 tensor encodings

## Static evidence

The S5API.dll string table contains the decoder error message:
invalid number of symbols in last base64 block: found 1, expected 2 or 3.

Disassembly at FUN_18000f5f0 shows it reserves output in groups of three bytes, then calls FUN_18000f6d0. The latter indexes a 256-entry decode table using each input byte, combines four 6-bit symbols into a 24-bit word, and emits three bytes. It also handles the final 2- or 3-symbol block and rejects a one-symbol remainder. The lookup table uses the standard Base64 alphabet A-Z a-z 0-9 + /, with = as padding and other bytes invalid.

In the tensor parser:
- FUN_1800656e0 checks the *F32 and *F16 prefix, decodes the remaining string via FUN_18000f5f0, then copies or converts the decoded payload to float32.
- Another model-array routine checks *S32 and passes the decoded 4-byte values as signed integers.
- *F16 decodes little-endian 16-bit values and calls a virtual converter at object offset +0x208 for float32 output.

This establishes typed Base64 arrays inside the model object's JSON-like representation. It does not imply that the outer VTB2/VPIT/VHNV/VTMG binary container is itself Base64 or JSON.

## Native port

vea_s5_typed_array.cpp now implements:
- standard Base64 decode with strict alphabet/padding and support for unpadded 2-/3-symbol final groups;
- *F32: little-endian IEEE-754 binary32 to float32;
- *F16: little-endian IEEE-754 binary16 to float32, including subnormal, infinity, and NaN handling;
- *S32: little-endian signed int32 values.

All size arithmetic is checked before arena allocation. Data remains in the VEA-owned 32-byte aligned arena. This is still a decoded-string-to-array primitive; the outer model container and JSON-like object tree are not yet parsed.

## Tests

Native tests cover F32 [1, 2], F16 [1, -2], a positive half-float subnormal, S32 [-123456, 42], unpadded Base64 input, malformed payload size, wrong type tag, and invalid alphabet.

The half-to-float operation is implemented using the IEEE binary16 bit layout. It must still be compared against the exact vendor converter once the vtable target at +0x208 has been recovered.
