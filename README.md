# VEA — Vocal Editor for Android

VEA is an Android-first vocal editing workstation. Its interface is based on the two supplied references:

- Arrangement view: tracks and voice/audio clips on a shared timeline.
- Piano-roll view: portrait/landscape adaptive note editing with lyrics, phonemes, and per-note pitch points.

## Current milestone: editor skeleton

Implemented:
- Android application targeting Android 16 (API 36).
- Portrait arrangement view and adaptive piano-roll.
- Monophonic note rule: time intervals cannot overlap, regardless of pitch.
- Note pitch/duration editing, lyric editing, X-SAMPA display, and per-note pitch points.
- Local JSON project save/load in app-private storage.
- A synthesis-engine interface with explicit backend availability reporting.
- Empty project on first launch.

Not implemented yet:
- Actual VOCALOID6 / VOCALOID7 AI singing synthesis on Android.
- Direct loading of VOCALOID7 voicebank/model files into an Android-native renderer.
- VPRX/VSQX import, effects, and real-time VOCALOID audio.

## On-device synthesis direction

The required final workflow is standalone Android: no PC connection, Wine/Box64 runtime, remote renderer, or cloud synthesis.

The supplied Editor dll.zip contains Windows AMD64 PE DLLs, not Android ARM64 ELF shared objects. They cannot be loaded by Android as-is and cannot be converted by renaming. The current implementation path is:

1. Statically analyze the authorized Windows G2P DLLs with Ghidra to recover entry points, call paths, inferred prototypes, and relevant data structures.
2. Reimplement the confirmed G2P behavior as Android-native C++ for arm64-v8a, expose a narrow JNI/C ABI, and compare outputs against the licensed reference implementation.
3. Trace the VOCALOID7 host/renderer boundary and dependency closure separately. Only after the real rendering contract is understood can an ARM64 renderer port/reimplementation be scoped.
4. Validate actual Megpoid V7 WAV renders on the phone before reporting a synthesis backend as available.

G2P decompilation is being automated through the G2P static decompilation GitHub Actions workflow. It uses the official Ghidra 12.2 release and uploads reports as the VEA-G2P-decompilation-reports artifact. The first targets are G2PAManager.dll, its language-specific manager DLLs, and G2PA_ESP.dll.

The G2P work is not itself a singing synthesizer. WaveOfflineRenderer.dll exposes audio normalization, resampling, and time-stretch functions; it is not evidence of an AI vocal renderer. VOCALOID7.dll had no named exports in the initial PE scan, so its real entry point remains unknown. See:
- docs/G2P_REVERSE_ENGINEERING.md
- docs/G2P_STATIC_FINDINGS.md
- docs/NATIVE_G2P_BRIDGE.md
- docs/SUPPLIED_V7_ASSETS.md
- docs/VOCALOID7_NATIVE_DEPENDENCIES.md
- docs/ENGINE_AND_COMPATIBILITY.md

An Android ARM64 shared library (libveag2p.so) and JNI bridge are now included. The first native implementation covers only a recovered Korean input-normalization primitive; its language-ready mask is zero, so this is not yet Yamaha G2P phoneme conversion. No VOCALOID singing renderer is implemented. The app must continue to report synthesis as unavailable until language phonemizers and a real local singing render are verified.

## Build

Requirements:
- Android Studio with Android SDK Platform 36 and Build Tools 36.x.
- Android NDK 27.2.12479018 and CMake 3.22.1.
- JDK 17.
- Gradle 8.13.
- Internet access for Gradle dependency resolution.

Open the repository as a Gradle project in Android Studio and sync. If your environment needs a wrapper generated, run gradle wrapper --gradle-version 8.13 from the project root using an installed Gradle 8.13 distribution.

The app module uses:
- compileSdk / targetSdk: 36
- minSdk: 28
- Android Gradle Plugin: 8.13.2
- Kotlin: 2.4.21
- Jetpack Compose BOM: 2026.06.00

Package: com.kmj.vea

## Initial controls

Arrangement:
- Tap a track name to select it.
- Double-tap a clip to open its piano roll.
- Add Track and Add Clip create editable items.
- SAVE stores the current project locally.

Piano roll:
- Tap an unoccupied time cell to create a note.
- Tap an existing note once to select/audition it; double-tap it to open the lyric editor.
- Drag the right-edge handle to resize a note.
- Tap the pitch lane to add or replace a pitch point.
- BACK returns to the arrangement.

Project data is stored in the app's private files directory as vea-project.json.

## Editing gestures and history

- Single tap a clip to select it; double-tap the clip to open its piano roll.
- The lyric text is rendered inside the note; X-SAMPA appears immediately above it.
- Resize and note-entry snapping follow the selected grid (1/1, 1/2, 1/4, 1/8, 1/16, 1/32).
- Changing grid resolution changes only the visual grid and future snapping. Existing note positions and lengths remain stored in ticks.
- Overlap rejection is silent.
- Undo and redo share a project snapshot history (up to 200 states).

## G2P and Korean input

- New notes start with Japanese lyric あ.
- Japanese lyric input accepts kana and common Hepburn/Kunrei-style romaji.
- English suggestions use a curated lexicon with alternate pronunciations and an approximate spelling-to-phoneme fallback.
- Korean compatibility jamo from Android IMEs are composed into precomposed Hangul syllables before phoneme lookup.
- The editor-side mapper is a fallback, not Yamaha's proprietary G2PA implementation.

## Supplied VOCALOID7 assets

The three supplied ZIP archives are checked statically in CI. AI Megpoid V7 metadata, the 8 official Voice Types, artwork, and model-reference hashes are catalogued. The archive payloads are retained in the repository for compatibility research; they are not currently packaged as if Android could execute them.

The archives contain:
- Editor dll.zip: 10 Windows AMD64 PE DLLs.
- AI Model.zip: COMMON7.vpit, VOCALOID7.vhnv, and VOCALOID7.vtmg.
- megpoidV7.zip: AI Megpoid bank data, manifest, styles, and artwork.

AI Megpoid's official Voice Types are Native, Whisper, Cute, Downer, Power, Soft, Vivid, and Ballade. The product languages are Japanese, English, and Chinese.

## Local singer artwork and build

The CI prepares only allow-listed artwork from megpoidV7.zip:

    python3 tools/prepare_voicebank_assets.py
    gradle --no-daemon testDebugUnitTest assembleDebug

The app can select the AI Megpoid identity and Voice Type metadata. This does not imply that singing synthesis is already available.

## Playback and pitch preview

- The piano roll offers DRAW, SELECT, and PITCH tools, bounded zoom (65–230%), and note resizing.
- New notes receive a smooth default pitch contour in #E76B22.
- PLAY/STOP currently audition notes using a local piano-like tone. This is not VOCALOID singing synthesis.
- Android safe-drawing insets keep controls clear of the status and navigation bars.
- The lyric popup starts with phoneme lock off and puts the text cursor at the end of the lyric.
- Moving a note previews its destination as a translucent block.

## Optional MIDI interchange

VEA can export Standard MIDI with note timing, pitch, tempo, time signature, and lyric events. This is an optional interoperability feature only; it is not the target rendering workflow. The primary goal remains local Android synthesis.
