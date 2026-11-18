# VEA — Vocal Editor for Android

VEA is an Android-first vocal editing workstation. Its interface is based on the two supplied references:

- Arrangement view: tracks and voice/audio clips on a shared timeline.
- Piano-roll view: opens in landscape and edits notes, lyrics, phonemes, and per-note pitch points.

## Current milestone: editor skeleton

Implemented in this repository:
- Android application project targeting Android 16 (API 36).
- Portrait arrangement view with track lanes and timeline clips.
- Portrait and landscape piano-roll layouts that adapt without forcing device rotation.
- A monophonic note rule: note time intervals cannot overlap, regardless of pitch.
- Note pitch and duration controls, lyric editing, and a pitch-curve lane.
- Local JSON project save/load in app-private storage.
- A synthesis-engine contract and an explicit unavailable adapter for VOCALOID AI.
- Starts with an empty project: no default tracks, clips, or notes.

Not implemented yet:
- Actual VOCALOID6 / VOCALOID7 synthesis.
- Voicebank parsing/import and authentication.
- WAV rendering, MIDI/VPRX/VSQX import/export, effects, and realtime audio.

## Important: VOCALOID AI engine boundary

VOCALOID7 was released on 2026-11-11. Yamaha's published VOCALOID7 specifications currently list Windows and macOS, not Android. VEA therefore does not pretend that desktop voicebank files alone can synthesize on Android. The app contains an engine interface only; a real V6/V7 AI backend requires a legally usable runtime/SDK or a separately authorized implementation. See docs/ENGINE_AND_COMPATIBILITY.md.

## Build

Requirements:
- Android Studio with Android SDK Platform 36 and Build Tools 36.x.
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
- Tap an unoccupied time cell to create a note. Overlapping notes and chords are rejected.
- Tap an existing note to open its lyric editor and audition its pitch.
- Use the note controls to change pitch, length, lyric, or delete it.
- Tap the pitch lane to add or replace a pitch point.
- BACK returns to the arrangement.

Project data is stored in the app's private files directory as vea-project.json.


## Editing gestures and history

- Single tap a clip to select it; double-tap the clip to open its piano roll.
- Single tap an empty piano-roll cell to add a note.
- The lyric text is rendered inside the note; X-SAMPA appears immediately above it.
- Drag the right-edge handle of a note to resize it.
- Resize and note-entry snapping follow the selected grid (1/1, 1/2, 1/4, 1/8, 1/16, 1/32).
- Changing the grid resolution changes only the visual grid and future snapping. Existing note positions and lengths remain stored in ticks and are not rewritten.
- The editor is monophonic: time intervals cannot overlap, regardless of pitch. Rejected placements/resizes are ignored silently.
- Undo and redo share a project snapshot history (up to 200 states).


## Note-entry and lyric editing update

- New notes start with the Japanese lyric `あ`.
- Tap a note to open the compact lyric editor. It contains an X-SAMPA field, singer-language selector (JPN/KOR/ENG), lock toggle, lyric field, clear, previous/next, cancel, and confirm controls.
- X-SAMPA suggestions are displayed above each note. The included mapper is a lightweight language-aware suggestion table; it is not the licensed VOCALOID voicebank's own G2P engine and may need voicebank-specific review.
- Moving a note previews its destination as a translucent block before the edit is committed.
- Tapping a note plays a short locally synthesized, decaying piano-like preview through Android AudioTrack. This preview is for pitch checking; it is not VOCALOID singing synthesis.
- The piano-roll timeline starts compactly and keeps only a small amount of look-ahead after the last note.
- Overlap rejection is silent. No toast or Logcat error is emitted for a rejected same-time note.
- Project snapshots are maintained in a dedicated undo/redo history (up to 200 edits).


## Romaji and note lyric rendering

- Japanese lyric input accepts kana and common Hepburn/Kunrei-style romaji.
- Examples covered by unit tests: `ra → ら → 4 a`, `shi → し → S i`, `konnichiwa`, and `gakkou`.
- The lyric is preserved as typed and displayed inside the note. X-SAMPA is derived separately and displayed above the note.
- This mapper is a deterministic editor-side fallback, not Yamaha's proprietary G2PA implementation. Voicebank-specific phoneme validation remains an engine integration task.
- CI runs `testDebugUnitTest` before assembling the APK.

## Supplied VOCALOID7 package inspection

The three supplied ZIP archives are checked statically in CI. A compact metadata catalog for AI Megpoid V7 is bundled at `app/src/main/assets/voicebanks/megpoid_v7_catalog.json`. It includes the manifest identity, 13 style names, and SHA-256 model references, but does not bundle Windows DLLs or model weights. See docs/SUPPLIED_V7_ASSETS.md for the compatibility findings.
