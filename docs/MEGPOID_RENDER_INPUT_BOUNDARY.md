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


## Newly traced upstream event-preparation path — 2026-11-21

The VSM S5API resolver loads Func_417eec3e in function-table slot 9. Its
exported body consumes a 0x28-stride source-record vector and constructs
two owned record vectors with 0x38 and 0x58 strides. In the branch where
the context mode at param_1 + 0xCE4 is zero, it calls:

```text
Func_417eec3e
  -> build 0x38-stride records
  -> FUN_18003ab70(context + 0xD0, ..., records, recordCount)
  -> FUN_18006c720(...)
  -> FUN_180076080(...) / FUN_180077520(...) for frame/history processing
```

The call sites establish this as an upstream event-to-intermediate-data
path. The names and exact tensor meaning of the intermediate arrays are
not proven yet. The alternate mode branch follows a different helper path.

Separately, FUN_1801361f0 invokes Func_92864e29 with a record pointer
advancing by 0x28 bytes and a float output destination advancing by
0x800 bytes per work item. The matching 0x28 stride does not, by itself,
prove that this render-dispatch record is the same structure as the
Func_417eec3e input record.

### Android status

VEA currently implements the app-owned note timeline and the observed
ASCII-space lyric tokenization. It does not yet build the 0x28 VSM/S5API
source record, call Func_417eec3e, or construct the intermediate feature
arrays. Pointer ownership and the mapping between source lyric tokens,
phoneme commands, render-context fields and the four model feature slots
remain unresolved.

### Next trace targets

1. Recover FUN_18006c720's feature construction inputs, output layout and
   position/frame geometry.
2. Recover FUN_180076080 and FUN_180077520's workspace/history contracts,
   including state reset and buffer lifetime.
3. Connect the resulting feature tensors to the existing Dense/Global
   Normalization -> Temporal -> Residual pipeline only after their
   dimensions and ordering are confirmed.

## Confirmed semantics of the seven-double row: interval + five category keys

The decompiled callers now support a narrower interpretation of the 0x38-byte
intermediate record passed to `FUN_18003ab70`:

- `row[0]` is the interval start position.
- `row[1]` is the interval end position.
- `row[2..6]` is a five-value categorical key consumed by
  `FUN_180074a50(row + 2, context, ...)`.

`FUN_18003ab70` calculates the frame count from the final row's `row[1]`
divided by the context time step at `context + 0x30`. For every row it
computes `ceil(row[1] / timeStep)` as the ending frame index, then fills
the frame coverage array for that interval. It also calculates the
interval duration as `row[1] - row[0]`.

`FUN_18006c720` independently confirms that same interval interpretation:
it reads the first two doubles as start/end, takes their difference,
applies a logarithm and normalizes the result against the configured
range (`param_7`, `param_8`) into `[-1, +1]`. That duration feature is
followed by optional state channels and one-hot categorical entries.

The five values at `row[2..6]` are not yet individually named. They are
passed together to `FUN_180074a50`, which returns an integer category index.
The helper is now an explicit Ghidra seed so its comparison/mapping table
can be recovered next.

Android should therefore preserve event interval start/end separately from
the categorical key. It should not fill the five category values from
pitch, phoneme, lyric, or style until `FUN_180007740` / `FUN_180007a90` /
`FUN_180007ec0` and `FUN_180074a50` confirm which source fields and units
they represent.