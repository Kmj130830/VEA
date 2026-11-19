# S5API.dll — static dependency findings

Date: 2026-11-19

## Why this DLL is significant

The new `Editor.zip` contains `S5API.dll`, a native Windows AMD64 PE32+ DLL. Static analysis of `VSM.dll` proves that it is not merely an unrelated companion library: VSM explicitly constructs a path to `S5API.dll`, loads it with `LoadLibraryExW`, and resolves a table of hashed exports by name with `GetProcAddress`.

This is direct evidence of a runtime dependency from the VSM layer to S5API. It is not yet proof that every S5API function is acoustic inference; their roles must be established from their callers and implementation.

## S5API file facts

- File: `S5API.dll`
- Product/company metadata: Yamaha Corporation / S5API
- File and product version: `15.28.0`
- Architecture: AMD64 PE32+
- CLR directory: absent (native DLL)
- Export count: 53
- Import dependencies include MSVCP140, VCRUNTIME140 / VCRUNTIME140_1, UCRT API sets, and Windows synchronization APIs.
- SHA-256: `9ec85e74cedb09a858170b321cfaeabc450644ef9269ea42398bf3290ce82908`

All exports have opaque names of the form `Func_<8 hex digits>`. The names alone do not identify their semantics.

## VSM runtime-loading sequence

Ghidra decompilation of `VSM.dll` function `FUN_18003b380` (image address `0x18003b380`) shows:

1. VSM calls `GetModuleFileNameW` to obtain the loaded module's full path.
2. It extracts the containing directory.
3. It appends the literal filename `S5API.dll`.
4. It calls `LoadLibraryExW(path, NULL, 8)` and stores the resulting module handle at VSM object offset `+0x238`.
5. On successful load it calls `FUN_180086750` to resolve the required API table.

The loader's decompiled body explicitly calls `GetProcAddress` for the following 28 exported names, storing each returned function pointer into adjacent slots of an internal dispatch table:

| Slot | S5API export |
|---:|---|
| 0x01 | `Func_4cd84bb7` |
| 0x02 | `Func_7b0b2f07` |
| 0x03 | `Func_ab21a6d2` |
| 0x04 | `Func_9cbce37f` |
| 0x05 | `Func_cfc85a30` |
| 0x06 | `Func_5fd62874` |
| 0x07 | `Func_68617beb` |
| 0x08 | `Func_95d13855` |
| 0x09 | `Func_efe389ac` |
| 0x0A | `Func_417eec3e` |
| 0x0B | `Func_236a1ecf` |
| 0x0C | `Func_95031259` |
| 0x0D | `Func_52f85f15` |
| 0x0E | `Func_2db29822` |
| 0x0F | `Func_47a12a73` |
| 0x11 | `Func_34c88d60` |
| 0x12 | `Func_b8d5dd29` |
| 0x13 | `Func_e7f8fafc` |
| 0x10 | `Func_2c6cd280` |
| 0x14 | `Func_b2d96da8` |
| 0x15 | `Func_133d38ab` |
| 0x16 | `Func_6b47c126` |
| 0x17 | `Func_0425ac3f` |
| 0x18 | `Func_a5e8ddbb` |
| 0x19 | `Func_92864e29` |
| 0x1A | `Func_0ecc5890` |
| 0x1B | `Func_2c8b9800` |
| 0x1C | `Func_2b8131bb` |

The non-sequential slot assignment is reproduced from the native loader's writes, not sorted export ordinal order. The resolved table includes 28 entries.

## Other S5 evidence in VSM

UTF-16 read-only strings in VSM include:

- `aiVoice`
- `s5Air`
- `s5Character`
- `s5Expression`
- `secondaryAiVoice`
- `aiCurveModel`
- `s5Attack`

These strings are consistent with V7 AI voice/expression support. They do not independently prove that S5API performs neural-network inference; that conclusion depends on function-level data flow.

## Android consequences

`S5API.dll` is still an AMD64 Windows PE binary. It imports MSVC/UCRT runtime components and Windows synchronization APIs. It is not an Android ARM64 library and cannot be loaded in VEA by changing its extension.

The static-analysis target is now much more precise:

1. Map the 28 VSM-requested S5API exports to their decompiled function bodies.
2. Recover the native signatures, data structures, ownership rules, and call relationships on both sides of the VSM/S5API boundary.
3. Determine which functions load or consume `.vtb2`, `.vpit`, `.vtmg`, or related model resources.
4. Identify required CPU/GPU, runtime, threading, and memory behavior.
5. Reimplement only the verified public behavior in Android NDK C++ and validate actual AI Megpoid output against the authorized Windows reference.

No model decryption, activation bypass, or licence bypass is performed. This report is based on PE metadata, strings, imports/exports, and Ghidra decompilation only.
