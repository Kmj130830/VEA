# VEA — Vocal Editor for Android

VEA is an Android-first vocal editing workstation. Its interface is based on the two supplied references:

- Arrangement view: tracks and voice/audio clips on a shared timeline.
- Piano-roll view: opens in landscape and edits notes, lyrics, phonemes, and per-note pitch points.

## Current milestone: editor skeleton

Implemented in this repository:
- Android application project targeting Android 16 (API 36).
- Portrait arrangement view with track lanes and timeline clips.
- Landscape piano-roll view with a touch-to-add/select note grid.
- Note pitch and duration controls, lyric editing, and a pitch-curve lane.
- Local JSON project save/load in app-private storage.
- A synthesis-engine contract and an explicit unavailable adapter for VOCALOID AI.
- Demo arrangement and notes so the screens are testable immediately.

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
- Jetpack Compose BOM: 2026.09.00

Package: com.kmj.vea

## Initial controls

Arrangement:
- Tap a track name to select it.
- Tap a clip to open its piano roll.
- Add Track and Add Clip create editable items.
- SAVE stores the current project locally.

Piano roll:
- Tap an empty grid cell to create a note.
- Tap an existing note to select it.
- Use the note controls to change pitch, length, lyric, or delete it.
- Tap the pitch lane to add or replace a pitch point.
- BACK returns to the arrangement.

Project data is stored in the app's private files directory as vea-project.json.
