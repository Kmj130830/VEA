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
