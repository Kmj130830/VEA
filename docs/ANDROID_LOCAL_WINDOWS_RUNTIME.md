# Archived investigation: Windows compatibility runtime

This document is retained as historical context only. The Wine/Box64 approach described in earlier revisions is **not the selected implementation path** and must not be added to the VEA application.

The project requirement is native, standalone Android operation. The current path is:
1. statically analyze the supplied Windows PE G2P libraries using Ghidra;
2. recover and verify their callable behavior and ownership rules;
3. implement the confirmed behavior in C++ for Android arm64-v8a;
4. expose it through JNI and validate it against the authorized reference implementation;
5. investigate the VOCALOID7 singing-render boundary separately.

See docs/G2P_REVERSE_ENGINEERING.md for the active workflow and docs/ENGINE_AND_COMPATIBILITY.md for the native binary/ABI boundary.

A Windows PE DLL cannot be loaded as an Android ELF shared library by renaming it. No Wine/Box64 runtime, PE loader, remote renderer, or PC handoff is part of the final design.
