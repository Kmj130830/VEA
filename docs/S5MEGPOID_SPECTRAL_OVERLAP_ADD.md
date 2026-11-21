# Megpoid inverse transform and overlap-add stage

Date: 2026-11-21

## Static evidence

The disassembly of S5API FUN_18012c740 establishes a complete per-frame
spectral-to-output-buffer sequence:

1. Copy N/2 + 1 interleaved complex bins to descriptor-owned scratch storage.
2. If descriptor byte +0x90 is enabled, negate both real and imaginary
   components of bins 1, 3, 5, ... .
3. Invoke FUN_180040630 in inverse mode. Its inverse branch conjugates the
   imaginary component of every interior bin, packs the Nyquist real value,
   zeros the packed tail, doubles the first N float workspace values, and calls
   the FFT core with direction -1.
4. Divide the first N real samples by N.
5. Multiply the frame by the window at descriptor +0x48 and write it into the
   circular buffer at +0x78, using the masked cursor at +0x20.
6. Advance the ring cursor and accumulate the configured newest-to-oldest
   contributions into the caller's output block.
7. Multiply the accumulated output samples by the second window at +0x60.

The descriptor values used by this path are retained with neutral names:
output sample count at +0x00; geometry parameter at +0x04; FFT size at +0x08;
window offset at +0x10; overlap contribution count at +0x14; ring mask at
+0x1C; ring write cursor at +0x20; frame window at +0x48; output window at
+0x60; optional alternating-bin sign flag at +0x90.

## Android reference implementation

- S5MegpoidInverseRealFft implements the spectral-bin-to-real-frame reference.
- S5MegpoidOverlapAddParameters stores the exact scalar/vector inputs needed
  by the recovered overlap code without assigning unsupported semantic names
  to the +0x04 and +0x10 fields.
- S5MegpoidSpectralOverlapAdd keeps a persistent masked ring buffer, applies
  the frame window before storage, accumulates the selected ring intervals,
  and applies the output window after accumulation.
- processSpectrum() composes the inverse transform and overlap-add stage.
  processFrame() allows ring/window behavior to be tested independently.

The overlap interval calculations mirror the bounds visible in the
FUN_18012c740 disassembly. Invalid descriptor combinations fail explicitly
rather than reading or writing outside the frame/output buffers.

## Tests

Tests cover two-frame 50% overlap accumulation, ordering of frame and output
window application, composition with the inverse FFT, history reset, ring
mask validation, window dimensions, geometry bounds, and silence handling.

## Remaining integration boundary

The vendor transform descriptor constructor/populator has not yet been
ported. Therefore this stage requires FFT size, output length, geometry
parameters, ring mask, windows and sign flag to be supplied by a verified
descriptor. The model-to-spectrum producer, full note/phoneme conditioning,
sample-rate handling and final PCM/WAV conversion remain separate stages.

The reference FFT currently supports even power-of-two sizes only and has
not yet been numerically compared to the vendor FFT backend. No successful
Megpoid render is claimed by this component.
