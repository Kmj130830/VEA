# VSM to S5API renderer ABI map

Date: 2026-11-19

## VSM to S5API dynamic import table

The VSM resolver at FUN_180086750 performs GetProcAddress lookups in this exact sequence. This is the order names are written into the VSM module function-pointer table. The VSM struct stores the loaded module handle at offset +0x150 and the 28 resolved API pointers at offsets +0x158 through +0x230 (8-byte stride).

| Slot | S5API export | S5API RVA | Recovered signature |
|---:|---|---:|---|
| 0 | Func_4cd84bb7 | 0x8d470 | uint32* (void*, int) |
| 1 | Func_7b0b2f07 | 0x92500 | void (void*) |
| 2 | Func_ab21a6d2 | 0x97fa0 | uint64 (int64, uint32*) |
| 3 | Func_9cbce37f | 0x97c20 | uint64 (int64, int64) |
| 4 | Func_cfc85a30 | 0x99fe0 | uint64 (int64, int64, int64*) |
| 5 | Func_5fd62874 | 0x8e350 | int64 (void*, int) |
| 6 | Func_68617beb | 0x914f0 | void (void*) |
| 7 | Func_95d13855 | 0x97ba0 | uint64 (int64, int32*) |
| 8 | Func_efe389ac | 0x9aa60 | int (int64, int64*) |
| 9 | Func_417eec3e | 0x85d10 | uint64 (int64, int64*, int64*) |
| 10 | Func_236a1ecf | 0x84f00 | uint32* (int64, void**) |
| 11 | Func_95031259 | 0x97b60 | void (void*) |
| 12 | Func_52f85f15 | 0x8e340 | uint32 (int64) |
| 13 | Func_2db29822 | 0x85540 | uint64 (int64, int64*, float*) |
| 14 | Func_47a12a73 | 0x873a0 | int64 (uint64, int) |
| 15 | Func_34c88d60 | 0x85bf0 | uint64 (int64, int32*) |
| 16 | Func_b8d5dd29 | 0x989a0 | uint32 (int64, int64*) |
| 17 | Func_e7f8fafc | 0x9a270 | uint64 (int64, int64*, void**) |
| 18 | Func_2c6cd280 | 0x854e0 | void (void*) |
| 19 | Func_b2d96da8 | 0x97fb0 | int (int64, int64*) |
| 20 | Func_133d38ab | 0x84040 | uint64 (int64, int64*, int64*) |
| 21 | Func_6b47c126 | 0x91610 | int64 (int64, void**, int64*) |
| 22 | Func_0425ac3f | 0x83ec0 | void (void*) |
| 23 | Func_a5e8ddbb | 0x97e00 | int (int64) |
| 24 | Func_92864e29 | 0x925f0 | uint64 (int64, int64*, float*) |
| 25 | Func_0ecc5890 | 0x83ff0 | void** (uint64, uint32) |
| 26 | Func_2c8b9800 | 0x85510 | void (int64*) |
| 27 | Func_2b8131bb | 0x85460 | uint32 (void) |

RVA values are relative to PE image base 0x180000000, not file offsets. Prototypes are Ghidra-recovered and should be treated as provisional until call sites confirm argument and ownership semantics.

## Render-path observations

### Main sample-block processor
Func_92864e29 is the strongest candidate for the S5API render hot path:
- Recovered signature: uint64 Func_92864e29(int64 context, int64* event, float* block).
- It reads a block length from the render context at context + 0x10 + 0x58.
- It accesses two float planes at block[i] and block[i + 0x100].
- It updates output planes in fixed 256-sample addressing patterns (0x100 floats per plane).
- It performs pitch/power trace paths and invokes FUN_1801361f0.
- The two planes' channel semantics, interleaving, and final VSM audio-buffer format are not yet proven. Do not label them left/right until callback ABI is verified.

### Input/event preparation
Func_417eec3e transforms records from a 0x28-byte input stride into internal 0x38- and 0x58-byte records, copies lyric strings, and invokes ordering/normalization helpers. It allocates a 16 MiB aligned scratch region. This is upstream feature/event preparation, not the final audio buffer writer.

### Render-context setup
Func_6b47c126 allocates and initializes a large render context, copies pitch/event/control arrays, configures timing/power fields, and sets up biquad coefficients. It also contains optional high-pass and silence-gate configuration. This is context creation/setup, not standalone synthesis.

### Model/configuration initialization
Func_47a12a73 creates an approximately 0x21d8-byte object and parses a hierarchical configuration structure with many string-key lookups. Exact schema and association with .vtb2/.vpit/.vhnv/.vtmg remain open.

## Porting boundary status
- Original VSM and S5API modules are Windows PE32+ AMD64.
- VSM dynamic function order and S5API RVA/signature inventory are recovered at Ghidra decompiler level.
- A portable Android implementation still needs verified field layouts, function semantics, model tensor contracts, and audio callback data lifetime.
- No binary PE-to-ELF conversion is attempted. A working Android arm64-v8a renderer must be rebuilt from portable source or reimplemented and validated against reference renders.
