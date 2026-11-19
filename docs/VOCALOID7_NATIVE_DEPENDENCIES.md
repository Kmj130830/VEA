# VOCALOID7 native runtime dependency findings

## Scope

This report is based on the ILSpy-decompiled managed metadata of the supplied `VOCALOID7.dll` and its P/Invoke declarations. The binary is a PE32+ AMD64, CLR IL-only assembly. The recovered C# project is used only as static evidence; the native DLL modules declared by P/Invoke were not executed.

The current `Editor dll.zip` contains ten Windows x64 DLLs. It is a selected editor-DLL bundle, not the complete native runtime dependency set referenced by the managed assembly.

## P/Invoke module inventory

Counting declarations in the recovered project gives:

| Native module name | P/Invoke declarations | C# files | Role inferred from managed API surface | In supplied Editor dll.zip |
|---|---:|---:|---|---|
| `vsm` | 786 | 42 | Sequence manager, notes/parts, phoneme service, asynchronous renderer, audio-buffer callbacks | **Missing** |
| `vsstyle` | 122 | 16 | Voice style and parameter preset management | **Missing** |
| `vdm` | 104 | 12 | Voicebank/database/voice-type metadata access | **Missing** |
| `VAE` | 90 | 3 | Audio device, playback, mixer, mixdown and audio-placement interface | **Missing** |
| `EffectManager` | 74 | 3 | Audio effect manager and effect blocks | **Missing** |
| `G2PAManager` | 25 | 4 | Common G2P manager interface and helper arrays | Present |
| `WaveOfflineRenderer` | 19 | 4 | Audio normalization, resampling, time-stretch and observer helpers | Present |
| `dse` | 18 | 4 | DSE manager, credentials and license-result queries | **Missing** |
| `user32.dll` | 14 | 6 | Windows UI integration | OS dependency |
| `winmm.dll` | 7 | 2 | Windows MIDI input | OS dependency |
| `AudioTranscoder` | 6 | 2 | WAV/MP3 transcoding | Present |
| `vlc` | 3 | 1 | Native Lua/controller integration module (exact package identity still to verify) | Missing |
| `user32` | 2 | 1 | Windows UI integration | OS dependency |

The G2P language-specific modules are loaded dynamically by the common G2P manager and are also included in the supplied bundle: `G2PAManager_ENG.dll`, `_JPN.dll`, `_KOR.dll`, `_CHS.dll`, `_ESP.dll`, plus `G2PA_ESP.dll`.

P/Invoke declaration counts are a static source-level inventory. They do not establish that every module is needed for a minimal singing-render test, nor do they reveal all transitive native dependencies of those modules.

## Main sequence/render boundary: VSM

The managed layer identifies `vsm` as the principal sequence and rendering module:

1. `WVSMModuleIF.CreateManager(appID, appVersion)` calls `VIS_VSM_WVSMModuleIF_createManager`.
2. It returns a `WIVSMSequenceManager`.
3. The application attaches its database manager, DSE manager and YVS interface using `SetDatabaseManager`, `SetDSEManager` and `SetYvs`.
4. The manager exposes a `PhonemeService`, which receives the common G2P interface and per-language G2P manager objects.
5. A sequence is created through `VIS_VSM_WIVSMSequenceManager_createSequence`.
6. The application registers a `WIVSMRendererObserver`, calls `StartAsyncRendering()`, then calls `CreatePartialRenderer()`.
7. The native renderer reports progress and rendered blocks through the observer callback context.

Key native declarations visible in the managed bindings include:

- `VIS_VSM_WVSMModuleIF_createManager`
- `VIS_VSM_WIVSMSequenceManager_createSequence`
- `VIS_VSM_PhonemeService_setLyricsAndPhonemesByLangID`
- `VIS_VSM_PhonemeService_setPhonemesByLangID`
- `VIS_VSM_WIVSMSequence_startAsyncRendering`
- `VIS_VSM_WIVSMSequence_createPartialRenderer`
- `VIS_VSM_WIVSMSequence_destroyPartialRenderer`
- `VIS_VSM_RendererObserver_create`
- `VIS_VSM_RendererObserver_setContext`

### Rendered audio callback

The managed renderer observer defines five callbacks: Started, BlockRendered, Progressed, Completed and Canceled.

The BlockRendered callback carries:
- the MIDI part being rendered;
- a `VSMRendererProgress` value;
- a `WIVSMAudioBufferList`;
- a `VSMScoreList`.

The audio-buffer list exposes its number of buffers, total sample count, and indexed audio buffers. Each audio buffer exposes a raw sample pointer and sample count, with explicit add-reference/release calls in the native API.

This is strong evidence for the managed/native renderer output boundary and a way to obtain generated audio blocks. It does **not** yet establish sample data type, interleaving, channel order, sample-rate conversion, or whether the callback contains final voice PCM or an intermediate render stage. Those details require the native `vsm` implementation and a reference render.

## Other module roles

### VAE

The `VAE` API includes engine initialize/terminate, audio-device configuration, start/stop playback, current-time/loop control, mixer gain/pan, audio placements, and mixdown-to-file. This is the application's audio output/playback and mixing layer. It is separate from the VSM sequence renderer callback.

### VDM and VSStyle

The application creates a VDM database manager using the expression-library path, then provides that manager to VSM. VDM wrappers expose database, voicebank, voice type and related voice data objects. The VSStyle module separately manages style presets and style parameters. Both are absent from the selected DLL archive, even though managed code calls them.

### DSE

The DSE wrappers shown in managed code create/initialize/terminate a DSE manager and query licenses, credentials, component IDs and license results. This is an authorization/credential service layer, not an audio renderer. No licensing or activation bypass is part of this project.

### WaveOfflineRenderer

The included `WaveOfflineRenderer.dll` is a native AMD64 module. Its exposed managed wrappers concern normalization, resampling and time-stretch. This is post-processing support, not the identified VSM singing-render boundary.

## Consequence for Android porting

The current supplied ZIP set is insufficient to reproduce the real V7 render pipeline. It includes the G2P modules and audio utility modules, but omits the primary `vsm` module and several adjacent dependencies used by the managed application.

The next required evidence set is the **complete native module directory from the authorized VOCALOID7 installation**, especially:

1. `vsm.dll` (essential for tracing sequence creation, phoneme service integration, async rendering and audio-buffer callbacks);
2. `vdm.dll` and `vsstyle.dll` (voicebank/database and voice-style access);
3. `VAE.dll` (audio output/mixdown);
4. `EffectManager.dll` and `dse.dll` (effects and authorization service);
5. all native DLLs loaded by or imported by those modules, including any AI inference/rendering runtime not referenced directly from the managed layer.

A ZIP of the complete application/runtime DLL directory is preferable to selecting files manually, because native modules may have transitive dependencies not visible in the managed P/Invoke list.

## Porting plan after the missing modules are available

- Perform PE import/export, CLR/native classification, and dependency closure inventory for the full runtime.
- Trace `vsm.dll` from `CreateManager` and `CreateSequence` to `CreatePartialRenderer`, then to the audio-buffer callback.
- Determine whether VSM contains the AI renderer directly or delegates model inference to one or more additional modules.
- Trace model-file opens and model resource mapping for `.vtb2`, `.vpit`, `.vhnv`, `.vtmg`.
- Recover the precise audio-buffer ABI (sample format, channels, ownership and lifetime) using native signatures and the authorized reference implementation.
- Implement the required engine surface as a new Android `arm64-v8a` library, or port the actual engine source where Yamaha provides source/porting material.
- Differential-test actual AI Megpoid V7 PCM output on Android before enabling synthesis in VEA.

Until this boundary is implemented and verified, `libveag2p.so` remains only a G2P bridge prototype and the application must continue to report singing synthesis unavailable.
