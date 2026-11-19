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

The goal is standalone Android operation: no PC connection, remote renderer, or cloud synthesis.

The supplied `Editor dll.zip` contains Windows AMD64 PE DLLs, not Android ARM64 ELF shared objects. Android's native loader cannot load those DLLs directly. However, a second route is being investigated: an on-device Windows compatibility environment using Wine plus Box64, similar in principle to Winlator. This would run compatible Windows x64 components locally on the phone rather than converting PE files by renaming them.

The first target is to prove whether the supplied G2P DLLs can run under such a local compatibility environment. The actual V7 singing-renderer entry point is not yet established: `WaveOfflineRenderer.dll` exports audio normalization, resampling, and time-stretch functions, not an evident vocal-synthesis API; `VOCALOID7.dll` has no named exports in the current static scan. See:
- `docs/ANDROID_LOCAL_WINDOWS_RUNTIME.md`
- `docs/SUPPLIED_V7_ASSETS.md`
- `docs/ENGINE_AND_COMPATIBILITY.md`

No Android-native renderer, working Wine/Box64 integration, or successful V7 singing render is claimed at this milestone. The app must continue to report synthesis as unavailable until an actual local render is verified.

## Build

Requirements:
- Android Studio with Android SDK Platform 36 and Build Tools 36.x.
- JDK 17.
- Gradle 8.13.
- Internet access for Gradle dependency resolution.

Open the repository as a Gradle project in Android Studio and sync. If your environment needs a wrapper generated, run `gradle wrapper --gradle-version 8.13` from the project root using an installed Gradle 8.13 distribution.

The app module uses:
- compileSdk / targetSdk: 36
- minSdk: 28
- Android Gradle Plugin: 8.13.2
- Kotlin: 2.4.21
- Jetpack Compose BOM: 2026.06.00

Package: `com.kmj.vea`

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

Project data is stored in the app's private files directory as `vea-project.json`.

## Editing gestures and history

- Single tap a clip to select it; double-tap the clip to open its piano roll.
- The lyric text is rendered inside the note; X-SAMPA appears immediately above it.
- Resize and note-entry snapping follow the selected grid (1/1, 1/2, 1/4, 1/8, 1/16, 1/32).
- Changing grid resolution changes only the visual grid and future snapping. Existing note positions and lengths remain stored in ticks.
- Overlap rejection is silent.
- Undo and redo share a project snapshot history (up to 200 states).

## G2P and Korean input

- New notes start with Japanese lyric `あ`.
- Japanese lyric input accepts kana and common Hepburn/Kunrei-style romaji.
- English suggestions use a curated lexicon with alternate pronunciations and an approximate spelling-to-phoneme fallback.
- Korean compatibility jamo from Android IMEs are composed into precomposed Hangul syllables before phoneme lookup.
- The editor-side mapper is a fallback, not Yamaha's proprietary G2PA implementation.

## Supplied VOCALOID7 assets

The three supplied ZIP archives are checked statically in CI. AI Megpoid V7 metadata, the 8 official Voice Types, artwork, and model-reference hashes are catalogued. The archive payloads are retained in the repository for compatibility research; they are not currently packaged as if Android could execute them.

The archives contain:
- `Editor dll.zip`: 10 Windows AMD64 PE DLLs.
- `AI Model.zip`: `COMMON7.vpit`, `VOCALOID7.vhnv`, and `VOCALOID7.vtmg`.
- `megpoidV7.zip`: AI Megpoid bank data, manifest, styles, and artwork.

AI Megpoid's official Voice Types are Native, Whisper, Cute, Downer, Power, Soft, Vivid, and Ballade. The product languages are Japanese, English, and Chinese.

## Local singer artwork and build

The CI prepares only allow-listed artwork from `megpoidV7.zip`:

```bash
python3 tools/prepare_voicebank_assets.py
gradle --no-daemon testDebugUnitTest assembleDebug
```

The app can select the AI Megpoid identity and Voice Type metadata. This does not imply that singing synthesis is already available.

## Playback and pitch preview

- The piano roll offers DRAW, SELECT, and PITCH tools, bounded zoom (65–230%), and note resizing.
- New notes receive a smooth default pitch contour in `#E76B22`.
- PLAY/STOP currently audition notes using a local piano-like tone. This is not VOCALOID singing synthesis.
- Android safe-drawing insets keep controls clear of the status and navigation bars.
- The lyric popup starts with phoneme lock off and puts the text cursor at the end of the lyric.
- Moving a note previews its destination as a translucent block.

## Optional MIDI interchange

VEA can export Standard MIDI with note timing, pitch, tempo, time signature, and lyric events. This is an optional interoperability feature only; it is not the target rendering workflow. The primary goal remains local Android synthesis.
