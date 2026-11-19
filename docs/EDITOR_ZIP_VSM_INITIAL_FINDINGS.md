# Editor.zip initial native inventory and VSM findings

Date: 2026-11-19

## Archive integrity and PE inventory

The repository-root `Editor.zip` was read from GitHub Actions, CRC-checked, inventoried, and its PE modules extracted for static inspection. The fast inventory artifact is `VEA-Editor-zip-inventory`.

- ZIP entries: 11
- PE modules: 11
- AMD64 PE32+ modules: 10
- x86 PE32 / CLR candidate: 1 (`UriTemplate.Core.dll`)
- Total extracted module bytes: 10,116,454

| Module | Size | Architecture / format | SHA-256 |
|---|---:|---|---|
| `VSM.dll` | 4,412,160 | AMD64 PE32+ native | `75e4b64ec41aae80037a0be8f5d6500c9fb50a6571b483d3b05456577df6ce5b` |
| `VDM.dll` | 1,689,344 | AMD64 PE32+ native | `690c89954aa4fa92d25181cf813eba5ba1e0c51903af5b36932a75e2d63218b9` |
| `VAE.dll` | 1,040,640 | AMD64 PE32+ native | `201d46d5041f87c9ef278b3600a075925277eba99092731012dd3393b0d0519c` |
| `S5API.dll` | 1,898,240 | AMD64 PE32+ native | `9ec85e74cedb09a858170b321cfaeabc450644ef9269ea42398bf3290ce82908` |
| `VSStyle.dll` | 203,528 | AMD64 PE32+ native | `9f0a442356d87836121754bc5395c641989666ee52bab3483d7f4a8736e8bbc2` |
| `EffectManager.dll` | 101,136 | AMD64 PE32+ native | `6632c444f1b66f38ff5506ddf7fc1d87b427146e9fbc97bf2fed02cb62ec428b` |
| `VOCALOID7.exe` | 254,728 | AMD64 PE32+ native launcher | `0c7c8e17515d7dff548a0db4329c44134bb9c7fff2ddaf20255996fa481b3398` |
| `VLC.dll` | 315,648 | AMD64 PE32+ native | `82d955740746b87a24696e7081155878ffb4a92f132d0d2e9fbe579d10ccfd54` |
| `AudioTranscoder.dll` | 84,760 | AMD64 PE32+ native | `f62b88e7cdc8007304a9a9d26aa8d399c6c98108237bf4ff8edfc7a9dbfeae55` |
| `G2PAManager.dll` | 52,496 | AMD64 PE32+ native | `1bdd00fdf5b572373ac6b7db17c35dd316a05aa540c71c6c13dbd624127d2655` |
| `UriTemplate.Core.dll` | 57,624 | x86 PE32, CLR directory present | `15a00c6bc49e069a9cdb1475e91ed9d2ddabdfc6cf4fe8324dbd163da11ee795` |

All 10 native code modules are Windows AMD64 binaries. The one CLR candidate is a separate 32-bit assembly. None of these files is an Android ARM64 ELF shared object.

## VSM is now present

This archive includes the previously missing `VSM.dll`. Its export table contains 984 named exports, including explicit entry points for the sequence manager, phonemizer service, singing render path, renderer observers, and returned audio buffers.

Selected exports:

- Manager creation: `VIS_VSM_WVSMModuleIF_createManager`
- Sequence creation: `VIS_VSM_WIVSMSequenceManager_createSequence`
- Phoneme service: `VIS_VSM_PhonemeService_setLyricsAndPhonemesByLangID`, `VIS_VSM_PhonemeService_setPhonemesByLangID`
- Async render control: `VIS_VSM_WIVSMSequence_startAsyncRendering`, `VIS_VSM_WIVSMSequence_stopAsyncRendering`
- Partial renderer: `VIS_VSM_WIVSMSequence_createPartialRenderer`, `VIS_VSM_WIVSMSequence_destroyPartialRenderer`
- MIDI part rendering: `VIS_VSM_WIVSMMidiPart_render`, `VIS_VSM_WIVSMMidiPart_renderPartiallyAsync`
- Audio buffers: `VIS_VSM_WIVSMAudioBufferList_audioBuffer`, `VIS_VSM_WIVSMAudioBufferList_numAudioBuffers`, `VIS_VSM_WIVSMAudioBuffer_samples`, `VIS_VSM_WIVSMAudioBuffer_numSamples`

This substantially improves the available evidence: VSM exposes the native renderer-facing contract that the previous Editor dll.zip did not provide. The exported audio-buffer API gives a concrete investigation route for sample pointers, sample counts, ownership/ref-counting and callback data.

## Static import evidence and remaining unknowns

`VSM.dll` imports Windows system/runtime libraries, including BCrypt, Kernel32, Advapi32, Shell32, OLE32, MSVCP140 and VCRUNTIME140. It imports `LoadLibraryExW` and `GetProcAddress`, so its complete runtime dependency set cannot be determined from the ordinary import table alone. Runtime-loaded modules and model-engine handoffs must be identified statically before defining the Android replacement.

The archive also includes:

- `VDM.dll`: exports database and voicebank catalog APIs; its UTF-16 strings include the V7 manifest schema and `*.vtb2` / `*.vtbr` file patterns.
- `VSStyle.dll`: style and preset metadata APIs.
- `VAE.dll`: playback, mixdown and audio-device functions; imports Windows multimedia/audio APIs (`WINMM.dll`, `DSOUND.dll`), which need Android audio replacements.
- `EffectManager.dll`: track/master effect-chain APIs.
- `S5API.dll`: 53 exported functions named `Func_<hex>`; their purposes are not identifiable from names alone. It is a priority for further static analysis, not yet proven to be the acoustic model runtime.
- `VLC.dll`: controller create/destroy/error functions; the name alone is not enough to assign it an audio-rendering role.

VSM's binary import table does not directly name S5API, but VSM's dynamic loading APIs mean that this does not rule out runtime loading. A focused Ghidra report is being generated for the renderer and audio-buffer export call paths.

## Porting consequence

The new archive removes the previous blocker of having no VSM binary to inspect. It does **not** make a Windows DLL convertible to `.so`: the modules remain AMD64 PE images, depend on Windows APIs and the MSVC runtime, and are not Android loadable.

The next steps are:

1. Decompile VSM's manager, phoneme, rendering, and audio-buffer exports and trace their direct call closures.
2. Identify VSM's dynamic `LoadLibraryExW` targets and model/inference handoff; analyze `S5API.dll` as a separate candidate.
3. Establish the renderer input contract (notes, phonemes, pitch, AI expression, voice/style IDs), model file access, output sample format, channels, sample rate, and buffer lifetime.
4. Reimplement or port those contracts to Android `arm64-v8a` C/C++ and build an ELF `lib*.so` with the NDK.
5. Differential-test actual AI Megpoid V7 output on-device before making the backend available in VEA.

No activation or license checks are bypassed. No Windows binary is copied, renamed, or represented as an Android library.
