# Editor.zip: VSM / S5API static-analysis findings

Date: 2026-11-19

## Scope and evidence

The user-supplied `Editor.zip` contains 11 PE modules. Ten are Windows AMD64 native modules; `UriTemplate.Core.dll` is the only CLR-managed candidate. The analysis was performed statically with Ghidra 12.2 and GNU `objdump`; none of the supplied binaries was executed.

Primary reports are produced by the `editor-zip-analysis.yml` workflow:
- `ghidra/VSM_RENDER_BOUNDARY.md`
- `ghidra/S5API_EXPORT_ANALYSIS.md`
- `pe/*.txt`
- `INVENTORY.md`

## High-confidence findings

### 1. VSM is the renderer-facing native boundary

`VSM.dll` is a 4,412,160-byte PE32+ AMD64 native DLL. It exports a recognizable sequence/rendering interface, including:

- `VIS_VSM_WVSMModuleIF_createManager`
- `VIS_VSM_WIVSMSequenceManager_createSequence`
- `VIS_VSM_WIVSMSequenceManager_setDatabaseManager`
- `VIS_VSM_WIVSMSequenceManager_setDSEManager`
- `VIS_VSM_WIVSMSequenceManager_setYvs`
- `VIS_VSM_WIVSMSequence_startAsyncRendering` / `stopAsyncRendering`
- `VIS_VSM_WIVSMSequence_createPartialRenderer` / `destroyPartialRenderer`
- `VIS_VSM_WIVSMSequence_addRendererObserver` / `removeRendererObserver`
- `VIS_VSM_WIVSMMidiPart_render` / `renderPartiallyAsync`
- `VIS_VSM_WIVSMAudioBuffer_create` and audio-buffer-list accessors
- phoneme service methods for G2PA and language-ID based lyrics/phoneme assignment

The sequence sampling-rate accessor returns 44100 when the sequence is null or does not match the expected runtime type. The setter forwards the supplied rate through the sequence vtable. This is evidence of a 44.1 kHz default, not proof that every internal path is fixed to 44.1 kHz.

### 2. VSM dynamically loads S5API.dll

A VSM initialization path constructs the filename `S5API.dll`, resolves it relative to the VSM module directory, and loads it with `LoadLibraryExW(..., LOAD_WITH_ALTERED_SEARCH_PATH)`.

A subsequent resolver calls `GetProcAddress` for 29 opaque exports (names in the form `Func_<8 hex digits>`). These include:

```
Func_4cd84bb7  Func_7b0b2f07  Func_ab21a6d2  Func_9cbce37f
Func_cfc85a30  Func_5fd62874  Func_68617beb  Func_95d13855
Func_efe389ac  Func_417eec3e  Func_236a1ecf  Func_95031259
Func_52f85f15  Func_2db29822  Func_47a12a73  Func_34c88d60
Func_b8d5dd29  Func_e7f8fafc  Func_2c6cd280  Func_b2d96da8
Func_133d38ab  Func_6b47c126  Func_0425ac3f  Func_a5e8ddbb
Func_92864e29  Func_0ecc5890  Func_2c8b9800  Func_2b8131bb
```

The VSM-to-S5API function table is a concrete ABI boundary to recover. Do not assign semantic names to these exports until call sites and data flow support the mapping.

### 3. S5API contains substantial signal-processing logic

`S5API.dll` is a 1,898,240-byte PE32+ AMD64 native DLL with 53 opaque exported functions. The focused Ghidra report decompiles the 53 exports and a bounded call closure of up to 140 functions.

The decompiled code contains diagnostic/configuration strings including:
- `MRW_DYN_POWER_OFFSET`
- `MRW_PC_CLAMPED_DIFF`
- `MRW_FLOW_BATCH`
- `MRW_DUMP_PITCH_TRACE`
- `MRW_DUMP_POWER_TRACE`
- `MRW_DUMP_COND`
- `MRW_DISABLE_SPP`
- `MRW_PC_LEGACY_DELAY`
- `MRW_POWER_OFFSET`
- `MRW_EPS_HP_FC`, `MRW_EPS_HP_FS`, `MRW_EPS_HP_STAGES`, `MRW_EPS_HP_GAIN`
- `S5_SIL_GATE` and related silence-gate controls
- `S5_ENCP_AS_DECODER`

These are strong signs that S5API contains pitch/power/conditioning and audio-processing stages. The names alone do not establish the exact algorithm or prove that S5API alone performs the full neural voice rendering.

The inspected S5API import table is mostly Windows synchronization plus the Microsoft C/C++ runtime and CRT. It does not show a dependency on ONNX Runtime, CUDA, or another separately imported inference framework. This does not rule out statically linked inference code or inference implemented internally.

### 4. Model-directory handling is Windows-specific

A VSM helper constructs the relative path `VOCALOID7/Model`, converts separators to backslashes, obtains a Windows known-folder path through `SHGetFolderPathW` (CSIDL argument `0x2B`), and joins the two. The exact Android storage mapping must be designed explicitly; the Windows folder lookup cannot be carried over unchanged.

### 5. Other modules have distinct roles / platform dependencies

- `VDM.dll` imports BCrypt and Windows file/registry APIs; investigate it as a database/model-management dependency.
- `VAE.dll` imports DirectSound, WinMM, COM, and Windows UI APIs; its audio-device layer needs replacement on Android.
- `VLC.dll` imports Windows process, filesystem, and shell APIs; do not assume it is the synthesis kernel.
- `G2PAManager.dll` is a separate small native pronunciation/phoneme manager.

## Android ARM64 port plan

1. Recover the exact VSM-to-S5API ABI: export prototypes, calling conventions, object ownership, error/status values, callback threading, and the 29 function-table slots.
2. Map VSM's database-manager, DSE-manager, YVS, and G2PA dependencies to their corresponding supplied modules and voice/model data.
3. Determine the model files and runtime tensors consumed by S5API; correlate file access, parser code, and the voicebank package contents.
4. Port or reimplement the required algorithms in portable C++ and build an ELF `arm64-v8a/libveavsm.so` with Android NDK. Replace Windows paths, threading, synchronization, audio output, and file APIs with Android-compatible equivalents.
5. Validate against reference renders at the same sample rate and compare waveform, duration, pitch contour, and spectral error.

## Important limitation

A PE/AMD64 DLL cannot be converted into an Android ELF/ARM64 `.so` by renaming, wrapping, or changing its header. The current reports are static reverse-engineering outputs, not a functioning Android renderer. No full Megpoid V7 synthesis or quality-equivalent Android `.so` has been produced yet.
