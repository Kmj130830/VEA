# VOCALOID7 engine and CLR analysis

## Objective

Find the actual runtime boundary that receives VOCALOID7 notes/phonemes, loads AI Megpoid V7 model data, and produces audio. The final implementation must be a native Android ARM64 port/reimplementation. Wine, Box64, remote rendering, and PC render handoff are out of scope.

## Input binaries

The workflow extracts and SHA-256 checks these files from `Editor dll.zip`:

- `VOCALOID7.dll`: 5,604,104 bytes, PE32+ AMD64.
- `WaveOfflineRenderer.dll`: 116,000 bytes, PE32+ AMD64.

Both are analyzed statically. Neither is executed.

## Critical format finding

The PE optional header of `VOCALOID7.dll` has a CLR COM Descriptor directory:
- CLR header RVA: `0x2000`, size: 72 bytes.
- CLR runtime header version fields: `2.5`.
- metadata RVA: `0x131f10`, metadata size: 1,758,128 bytes.
- CLI flags: `0x00000001` (`ILONLY`).
- runtime metadata string: `v4.0.30319`.
- metadata root signature: `BSJB`.

This is strong evidence that `VOCALOID7.dll` is a managed IL-only assembly, not a normal native x64 rendering library. The CLR metadata occupies a large portion of the file.

The earlier Ghidra report labelled numerous function regions as ".NET CLR Managed Code" and produced invalid native-like pseudocode for those methods. Those decompiler fragments must not be interpreted as actual x64 instructions or used as a basis for a native port.

By contrast, `WaveOfflineRenderer.dll` has no CLR COM Descriptor directory in the inspected PE header. It is a native AMD64 DLL with exports for audio normalization, resampling, time-stretch and observer support.

## Analysis pipeline

The repository workflow `.github/workflows/ghidra-v7-engine-analysis.yml` now combines:
1. Ghidra 12.2 static PE import and symbol/string reference analysis;
2. a standard-library PE parser for the CLR COM Descriptor and metadata-root signature;
3. ILSpy command-line type listing, project decompilation and IL dump attempts for `VOCALOID7.dll` and `WaveOfflineRenderer.dll`.

The ILSpy run is required to determine:
- managed namespaces, types, method signatures and IL bodies;
- assembly references and P/Invoke declarations;
- calls from managed methods into external native modules;
- whether rendering is orchestrated by managed code while the actual acoustic engine lives in a different native/runtime component.

The workflow's first ILSpy attempt identified the CLR metadata correctly but stopped because the report output directory was not created before writing. This was corrected; the subsequent run must be checked for type-list/decompilation status and logs.

## What is known vs unknown

Known:
- VOCALOID7.dll is a PE32+ AMD64 image and a CLR IL-only assembly.
- Its CLR metadata root is valid and uses the .NET v4 metadata string.
- The earlier Ghidra native-looking decompilation of CLR code is not valid evidence.
- WaveOfflineRenderer.dll is a separate native DLL exposing audio utility methods; it is not itself identified as the AI singing renderer.

Unknown until the ILSpy report is reviewed:
- exact managed renderer-facing classes and method signatures;
- all referenced assemblies and native P/Invoke dependencies;
- the actual native AI renderer module, if separately provided;
- how VPIT/VHNV/VTMG/VTBR assets are loaded and passed to the inference runtime;
- the PCM render callback/offline render boundary and parameter mapping.

## Porting boundary

G2P and singing synthesis remain separate subsystems. The current `libveag2p.so` contains only the recovered Korean text canonicalization primitive, not Yamaha phoneme conversion or vocal rendering.

The renderer cannot be ported until the true acoustic engine boundary and every required dependency are identified. A UI method called `OnRender` is not sufficient evidence: it may belong to a managed graphics/control layer rather than audio synthesis.

## Android verification gate

Do not mark the singing renderer available until all are demonstrated on Android ARM64:

1. AI Megpoid model resources load successfully from the supplied assets.
2. A real note/phoneme/pitch request reaches the renderer.
3. It returns synthesized PCM/audio, not a piano preview or post-processed external WAV.
4. Timing, pitch trajectories, phoneme transitions and voice style can be compared against the authorized reference.
5. Repeated renders are stable within Android memory, thermal and cancellation constraints.
