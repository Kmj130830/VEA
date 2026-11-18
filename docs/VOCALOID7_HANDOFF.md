# VOCALOID7 desktop rendering hand-off

## Why this is the supported route with the supplied files

The current supplied renderer and G2P DLLs are PE AMD64 Windows binaries. The supplied AI Megpoid model files are proprietary VOCALOID7 formats, and the ZIPs contain no Android ELF renderer or public Android embedding API. VEA therefore does not load those desktop DLLs or reinterpret the proprietary model payloads.

Yamaha's VOCALOID7 Reference Manual documents MIDI (SMF) import, including note data, tempo, time signature, and lyrics. For MIDI imports, the user can choose whether the imported vocal track is a VOCALOID:AI or VOCALOID track and select lyric character encoding. VEA uses this documented interchange format to hand notes and lyrics to the real renderer.

## VEA export

In Arrangement, press **V7 MIDI**. VEA creates a Standard MIDI File with:
- project tempo and time signature;
- vocal track names and clip start-bar offsets;
- note pitch, start time, and duration;
- lyric text as MIDI Lyric meta events (0x05), encoded UTF-8.

Japanese romaji is converted to hiragana before export (for example `konnichiwa` becomes `こんにちわ`). Korean compatibility jamo are composed to Hangul syllables before export. English and Chinese lyrics are preserved as entered.

The format does not carry Yamaha's proprietary voicebank/Voice Type selection metadata. Choose AI Megpoid and one of its 8 Voice Types in VOCALOID7 after import.

## VOCALOID7 import and render

1. Transfer the exported `.mid` file to the licensed Windows/macOS machine with VOCALOID7 and AI Megpoid V7 installed and authorized.
2. In VOCALOID7, use **File → Import** and select the MIDI file.
3. Enable lyric import and choose **UTF-8** for lyric character encoding.
4. For each vocal track, choose **VOCALOID:AI** as the imported track type.
5. Select **AI Megpoid** and the desired Voice Type: Native, Whisper, Cute, Downer, Power, Soft, Vivid, or Ballade.
6. Let the official VOCALOID7 G2P and AI engine process the notes and lyrics. Export the resulting audio as WAV using VOCALOID7's Audio Mixdown.

## Pitch and quality boundary

VEA's orange `#E76B22` curve is an editor-side preview, not a clone of Yamaha's pitch model. It is not exported as pitch-bend events by the V7 hand-off. That is deliberate: imported notes and lyrics remain available for VOCALOID:AI to generate its own note transitions, vibrato, expression, and automatic pitch. Thus the actual singing quality comes from the original VOCALOID7 engine, not from VEA's piano preview or approximate G2P.

This workflow requires a licensed VOCALOID7 editor and an authorized AI Megpoid voicebank on the desktop system. It does not provide offline Android rendering. No activation/licensing checks are bypassed and no Windows DLLs are loaded on Android.
