# G2P static findings — first decompilation pass

## Scope and evidence

The observations below are from the Ghidra 12.2 headless reports generated for the six allow-listed G2P DLLs in `Editor dll.zip`. Analysis was static: the PE modules were imported into Ghidra, but no target DLL was executed.

The original report artifact is attached to GitHub Actions run 44430339143 as `VEA-G2P-decompilation-reports`. The newer report pass adds factory-referenced vtable recovery and is tracked separately.

## Shared manager loader

`G2PAManager.dll` is a Windows host-side adapter/loader rather than the language phonemizer itself.

Static decompilation of its `createManager` path shows this sequence:

1. Select a language-specific module using an integer language key.
2. Construct a language DLL filename beneath a supplied wide-character path.
3. Load the DLL with `LoadLibraryW`.
4. Resolve `G2PACreateManager` using `GetProcAddress`.
5. Cache the module handle and factory function pointer in a language-keyed tree/map.
6. Invoke the factory and return its manager object to the host wrapper.

The recovered language key to filename mapping is:

| Key | DLL |
|---:|---|
| 0 | `G2PAManager_JPN.dll` |
| 1 | `G2PAManager_ENG.dll` |
| 2 | `G2PAManager_KOR.dll` |
| 3 | `G2PAManager_ESP.dll` |
| 4 | `G2PAManager_CHS.dll` |

This mapping is visible in the switch and string references in the decompiled loader. The loader also adds a trailing path separator when needed. Its use of the Windows DLL loader means this host wrapper cannot be reused as an Android library.

## Common manager vtable contract

The exported `VIS_G2PAManager_WG2PAManager_*` wrappers dispatch through a language-manager object's virtual function table. The recovered byte offsets are:

| Vtable byte offset | Wrapper method |
|---:|---|
| `0x00` | Destroy |
| `0x10` | Default lyric |
| `0x18` | Inputting-lyrics state |
| `0x20` | Set lyrics and phonemes |
| `0x28` | Can convert |
| `0x30` | Set phonemes |
| `0x38` | Set syllable-segment array |
| `0x40` | Delete note |
| `0x48` | Normalize all phonemes |
| `0x50` | Normalize ranged phonemes |
| `0x58` | Normalize all lyrics |

These are confirmed dispatch offsets in the common wrapper code. They are not yet a complete method-signature specification. Several wrapper exports use opaque pointer-like arguments, and Ghidra cannot reliably infer the source-level C++ types, reference qualifiers, ownership, or all stack arguments from these short thunks alone.

## Language-specific factory observations

The decompiler output shows distinct object-construction patterns:

| DLL | Allocation size | Factory initialization visible in first pass |
|---|---:|---|
| ENG | `0x18` bytes | Installs the `G2PA5::G2PAManager` vtable, stores a 32-bit argument, and performs one-time synchronized initialization of static language data |
| JPN | `0x18` bytes | Installs the same-named manager vtable and stores a 32-bit argument |
| KOR | `0x48` bytes | Installs the manager vtable, stores a 32-bit argument, and copies a supplied wide-character string into the object |
| CHS | `0x48` bytes | Same broad constructor shape as KOR, including a copied wide-character string |

The byte sizes and field writes are decompiler observations, not a confirmed public class layout. The matching class name does not prove the four DLLs share a binary-compatible C++ ABI. The KOR/CHS path field in particular must be understood before any port assumes those factories are interchangeable with ENG/JPN.

## Native port implications

- Do not call these Windows objects from Android JNI.
- Do not copy their MSVC vtable layout into a Kotlin-visible API.
- Build a new Android-side C ABI around owned UTF-8 inputs and explicit result buffers after the phonemizer method signatures and semantics are recovered.
- Treat each language backend separately until the shared implementation and state model are demonstrated.
- Keep the current Kotlin mapper as an explicit fallback only; it is not Yamaha G2PA.

The next static-analysis gate is to recover the factory-referenced vtables and decompile the virtual method bodies. The first report only follows direct calls from factory/export seeds; C++ virtual calls are indirect and are not necessarily in that call closure. A second report pass has therefore been added to read pointer runs referenced by the language factory functions and include resolved virtual-method targets.
