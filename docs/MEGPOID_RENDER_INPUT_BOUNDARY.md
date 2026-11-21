# Megpoid V7 render-input boundary (static trace)

This note records the verified boundary between the high-level S5API render path and the native sequence model. It deliberately does not assign meanings to opaque arguments or structure offsets without a complete data-flow proof.

## Confirmed call chain

- `Func_92864e29` at `0x1800925f0` is a high-level S5API render function (body size 21,864 bytes in the analyzed build).
- It obtains a model/context handle through `FUN_1801269f0`; that wrapper allocates a pointer slot and stores the result of `FUN_1800ccce0`.
- The high-level function invokes `FUN_180127330` and then `FUN_180126c20` on that handle.
- `FUN_180127330` validates the handle and forwards to `FUN_1800aeea0`. The latter writes a 32-bit value to offset `+0x3678) of the underlying context. The semantic name of this value is not yet proven.
- `FUN_180126c20` validates its handle and forwards to `FUN_1800aec40`.
- `FUN_1800aec40` resolves the underlying context, obtains the active render length through `FUN_1800c4730`, copies the value at context offset `+0x624), then calls `FUN_1800c7ad0`.
- `FUN_1800c7ad0` is the actual native model/render execution routine. Its second argument is retained as the destination buffer and is passed to `FUN_18012c740` near the end of the function, confirming that this argument is the final output area rather than the neural input feature tensor.
- At entry, `FUN_1800c7ad0` also calls `FUN_18012b150`, `FUN_18012b470` and `FUN_18012b450` using pointers stored in the render context. Their exact roles and the provenance of those context buffers are the next data-flow targets.

## Important distinction

The third argument passed by the high-level caller to `FUN_180126c20` points to the caller's output area at `this + 0x1a130`; the native render routine later writes the generated output through the output-copy helper. It is therefore not evidence that this address contains the neural input feature tensor.

The current Kotlin sequence-transition pipeline starts from an already-prepared feature tensor and implements the recovered Dense/normalization, Temporal, Banked and Residual stages. It is not yet connected to the native note/phoneme-to-feature preparation path or the final output/audio path.

## Next reverse-engineering targets

1. Trace the writes to the underlying render-context fields consumed by `FUN_1800c7ad0` before inference.
2. Identify the public setters that populate note, phoneme, duration, pitch and language/voice conditioning data.
3. Recover the exact tensor layout and chunk/overlap contract at the point where the native sequence model consumes those features.
4. Only then add a Kotlin feature builder and connect it to `S5MegpoidSequenceTransitionPipeline`.

## Evidence

Static decompilation of `S5API.dll`:
- `Func_92864e29`: `S5API_RENDER_HOT_PATH.md`
- `FUN_180127330`, `FUN_180126c20`: `S5API_RENDER_HOT_PATH.md`
- `FUN_1800aeea0`, `FUN_1800aec40`, `FUN_1800c7ad0`: `S5API_MODEL_RUNTIME.md`

No target native code was executed. Opaque offsets and argument roles remain provisional unless explicitly stated above.
