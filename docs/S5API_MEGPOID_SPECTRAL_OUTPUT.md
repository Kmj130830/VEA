# S5API Megpoid spectral output and overlap-add boundary

Date: 2026-11-21

## Evidence scope

This is a static Ghidra dataflow trace of the supplied S5API.dll. It does not execute the vendor renderer and does not claim a verified WAV export.

## Corrected public render argument role

The public wrapper path is:

```text
FUN_180126c20(context, outputFloatBuffer, controlValue, modeFlag)
  -> FUN_1800aec40
  -> FUN_1800c7ad0(renderState, outputFloatBuffer, controlValue, modeFlag)
```

In `FUN_1800c7ad0`, the second argument is saved as `local_188` and passed to the output conversion routine near the end of the function. It is therefore an output destination, not the note/phoneme feature tensor.

The neural render state and its descriptor/workspace arrays are owned by the first argument. The forward network consumes those root-owned tensors and writes a complex-valued spectral frame to the root-owned output-spectrum buffer.

## Spectral output conversion

At the end of `FUN_1800c7ad0`, the renderer calls:

```c
FUN_18012c740(
    *(undefined8 *)(renderState + 0x20),
    *(undefined8 *)(renderState + 0x4F8),
    outputFloatBuffer
);
```

The third argument is the caller's float output buffer. The second argument is the model-produced complex spectrum. The first argument is a separate transform/overlap descriptor.

The decompiled `FUN_18012c740` establishes these operations:

1. It copies `N/2 + 1` complex values (two float32 values per bin, `(N/2+1)*8` bytes) from the model spectrum into descriptor-owned scratch storage.
2. If descriptor flag `+0x90` is enabled (descriptor word index `0x24`), it flips the sign bit of both float components for alternating complex bins.
3. It calls `FUN_180040630(plan, descriptorScratch, N, 1)`. The transform helper's exact direction/normalization contract is still being decompiled; the surrounding dataflow is a complex-spectrum-to-real-frame conversion.
4. It divides the first N real samples by N.
5. It writes the transformed frame into a circular overlap buffer using the descriptor's current ring index, frame count, hop/overlap geometry and ring mask.
6. It advances the ring index.
7. It copies/accumulates the relevant overlap window into the caller's output buffer and multiplies by the descriptor-owned window coefficients.

This is a time-domain frame reconstruction/overlap-add stage. It is more specific than the earlier neutral description of the two output float buffers, but sample-rate conversion, channel layout, final gain convention and the exact WAV boundary are not yet established.

## Confirmed transform direction and input-side frame preparation

The added Ghidra trace of `FUN_180040630` shows that it calls the core transform with a direction value of `+1` when its final argument is zero and `-1` when nonzero. The surrounding call sites establish the direction:

- `FUN_18012b150` calls `FUN_180040630(..., N, 0)`: forward transform. It first obtains a real frame through `FUN_18012ad70`, maintains a circular history, applies a window, transforms it, and copies `N/2+1` complex values to its output pointer.
- `FUN_18012c740` calls `FUN_180040630(..., N, 1)`: inverse transform. It copies the model-produced `N/2+1` complex bins to scratch, applies the optional alternating-bin sign convention, transforms, normalizes by `N`, and overlap-adds into the caller's float buffer.

This confirms the output transform direction; it is no longer merely inferred from the surrounding dataflow.

Inside `FUN_1800c7ad0`, the transform calls are made using the root-owned transform object at `renderState + 0xF0` (word index `0x1e`). The forward-frame transform writes its spectrum to the root-owned pointer at `+0x110` (word index `0x22`). Two further transform-state arrays are copied by `FUN_18012b470` and `FUN_18012b450` into root-owned pointers at `+0x140` and `+0x170` (word indices `0x28` and `0x2e`). Their exact feature semantics are still under investigation.

The higher-level render path also calls `FUN_180127330`, which forwards a per-frame 32-bit value to `FUN_1800aeea0`. That setter writes the value to render-state offset `+0x3678`. The caller sets it to `1.0f` when the rounded frame index is out of range or the selected frame entry is zero, otherwise `0.0f`. This is a verified frame-validity gate value; its vendor field name is unknown.

## Descriptor fields observed in FUN_18012c740

The function treats the first argument as a pointer to an object whose first word is a descriptor pointer. Within that descriptor:

| Word index | Byte offset | Observed use |
|---:|---:|---|
| 0 | +0x00 | Output frame/hop-related loop bound |
| 1 | +0x04 | Window/FFT geometry |
| 2 | +0x08 | Transform size N |
| 4 | +0x10 | Overlap/window offset |
| 5 | +0x14 | Number of overlap contributions |
| 7 | +0x1C | Circular-buffer mask |
| 8 | +0x20 | Circular-buffer write index |
| 10 | +0x28 | Transform plan pointer |
| 12 | +0x30 | Transform scratch / plan workspace |
| 14 | +0x38 | Complex scratch pointer |
| 18 | +0x48 | Window pointer |
| 30 | +0x78 | Circular overlap-buffer pointer |
| 36 | +0x90 | Alternating-bin sign-flip flag |

These names are descriptive observations, not vendor ABI field names. The constructor and all write sites must be traced before using the offsets as a portable public contract.

## Porting implications

- Do not pass note/phoneme features through the public output-buffer argument.
- Keep the existing model forward graph's output as complex spectral bins until the transform stage is implemented.
- Port the transform plan and overlap-add descriptor only after `FUN_180040630`, descriptor construction, and history reset semantics are verified.
- The Android implementation must preserve the ring-buffer state across sequential audio chunks and reset it at utterance boundaries.
- The caller output length is owned by the render state; do not infer it from the VTB2 model tensor sizes.

## Remaining work

1. Decompile `FUN_180040630` and its selected backend implementation to establish transform direction, scaling and data layout.
2. Trace the constructor/population sites for the transform descriptor at render-state root +0x20.
3. Trace the root-owned neural input feature buffers and the upstream note/phoneme-to-feature path. The output argument is now excluded from that search.
4. Verify the sample-rate and final PCM/WAV boundary in the higher-level VSM caller.

No successful Megpoid vocal render or WAV output is claimed.
