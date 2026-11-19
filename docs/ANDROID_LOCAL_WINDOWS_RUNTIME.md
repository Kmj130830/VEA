# Android-local Windows runtime experiment

This document records the first feasibility pass for running the supplied VOCALOID7 Windows components locally on Android. The required user workflow is standalone Android: no tethered PC, remote rendering, or cloud synthesis.

## Why the DLL cannot simply become a .so

The supplied DLLs are PE/AMD64 Windows binaries. Android native libraries are ELF and must match an Android ABI such as `arm64-v8a`. The CPU architecture and file format are only part of the difference: Windows imports, calling conventions, C++ runtime, loader behavior, and system APIs must also be satisfied.

Renaming `foo.dll` to `libfoo.so` cannot change any of these properties.

## Compatibility-layer option

Wine implements Windows API compatibility; Box64 translates x86-64 user-mode instructions for ARM64. Winlator is an existing Android application demonstrating that Windows x86-64 applications can run locally through a Wine/Box64 stack.

For VEA, this could be integrated as an isolated local helper runtime:
- VEA Compose UI and song model remain native Android.
- A helper process runs inside a private Wine/Box64 environment.
- VEA passes a render job and model paths through a local IPC protocol.
- The helper returns progress, errors, and a rendered WAV file.
- The app copies/streams the completed WAV into its own audio engine.

This avoids requiring a PC, but it is not equivalent to calling a DLL from Kotlin. The runtime, root filesystem, helper process, audio routing, cancellation, and storage permissions all need integration and testing. No such runtime has been packaged in VEA yet.

## Evidence in the three supplied archives

### Editor dll.zip

The static CI inspection found 10 PE AMD64 DLLs.

The language-specific G2P DLLs expose `G2PACreateManager`; the manager wrapper exposes lyric/phoneme normalization and syllable-segmentation helpers. These are plausible first compatibility-test targets, but the supplied archive does not include API headers or complete function signatures. The factory must not be called with guessed arguments.

`WaveOfflineRenderer.dll` exposes normalization, resampling, and time-stretch APIs. It is an audio post-processing utility, not an evident AI singing renderer.

`VOCALOID7.dll` is present as a 5.6 MB PE AMD64 DLL, but the current static export scan found no named exports. This does not prove the DLL contains no synthesis implementation; it means a callable render entry point has not been identified from this package.

### AI Model.zip and megpoidV7.zip

The archives contain the common V7 model payloads and AI Megpoid V7 bank payload/manifest. The manifest hashes match the supplied NModel/PModel/TModel payloads. These binary files are not ONNX models and do not constitute a renderer by themselves.

## Smallest useful experiments

1. Package or build an ARM64 Wine/Box64 test environment outside the main APK first.
2. Run a harmless x64 Windows console test program. Verify process launch, filesystem access, stdout/stderr, and clean shutdown.
3. Test one language G2P component only after its real API signature and initialization sequence are established.
4. Identify the V7 vocal synthesis host/API and full dependency set from authorized runtime materials.
5. Attempt an offline render with the supplied AI Megpoid model and verify that the output is actual singing, not a tone or audio post-processing result.
6. Measure peak RAM, sustained CPU load, render time, thermal throttling, and crash behavior on the target phone.
7. Integrate the local runtime into VEA only after steps 1–6 pass repeatably.

## Current conclusion

A local Windows compatibility route is technically plausible for some supplied x64 components, especially G2P. It is not yet proven for the V7 singing renderer. The present three archives do not expose a known callable vocal-render API, and `WaveOfflineRenderer.dll` must not be mistaken for that engine.

No activation, encryption, or licensing checks are bypassed. The app must continue to report the V7 backend unavailable until a real, authorized Megpoid V7 render succeeds on Android itself.
