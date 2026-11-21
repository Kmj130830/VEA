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

## Exact ring interval calculation

The address arithmetic at S5API FUN_18012c740 was checked instruction by
instruction against the supplied S5API.dll. For one contribution index i,
define:

- N: FFT size at descriptor +0x08;
- O: output sample count at descriptor +0x00;
- G: geometry integer at descriptor +0x04;
- W: window offset at descriptor +0x10;
- H = floor(N/2);
- Q = floor(G/2);
- r = W - i.

The native code computes the source interval:

```text
sourceStart = max(0, H + min(Q, -r * O))
sourceEnd   = max(0, H + min(Q, (1 - r) * O))
```

The destination begins at:

```text
outputStart = 0                    when sourceEnd <= O
              O - sourceEnd        otherwise
```

It copies sourceEnd - sourceStart samples from the selected ring slot,
adding each sample into the destination. A non-positive copy count skips that
contribution. The ring slot is ((writeCursor - i - 1) & ringMask); the
current frame is stored at (oldWriteCursor & ringMask) before the cursor is
incremented.

Both source bounds clamp against Q = floor(G/2); the start bound does not clamp against H.

The Android implementation adds explicit bounds checks around these exact
source/destination intervals. These checks reject invalid transform
descriptors that would otherwise read or write outside the native arrays.

## Output descriptor geometry constructor

A second instruction-level pass over FUN_180127dc0 recovered the scalar
constructor inputs and derived fields. The constructor copies three inputs
independently:

- argument 2 -> descriptor +0x00 (outputSamples);
- argument 3 -> descriptor +0x04 (geometry);
- argument 4 -> descriptor +0x08 (fftSize).

It copies a float argument to +0x0C and three byte flags to +0x90, +0x91 and
+0x92. The geometry fields are then computed as follows:

```text
halfGeometry = floor(geometry / 2)
windowOffset = ceil(halfGeometry / outputSamples)
overlapContributionCount = windowOffset + 2
ringSlotCount = nextPowerOfTwo(windowOffset)
ringMask = ringSlotCount - 1
```

The nextPowerOfTwo operation is implemented by spreading the highest set
bit through shifts by 1, 2, 4, 8 and 16, then adding one. A zero windowOffset
still produces one ring slot, with mask zero. The contribution loop count is
independent of the ring capacity and can wrap the ring mask.

S5MegpoidOverlapAddDescriptorGeometryBuilder ports these integer formulas
and validates signed-32-bit overflow. It deliberately keeps geometry and FFT
size as separate parameters; the helper does not assume that their values are
equal merely because one observed caller may supply the same source value.

This creates a verified bridge between render-state scalar inputs and the
scalar geometry fields required by S5MegpoidSpectralOverlapAdd. The frame and
output window coefficients, transform-plan setup, and provenance of the
render-state scalar inputs remain separate tasks.

## Frame-window generator

Static disassembly of FUN_18012a9a0 recovers the following branch behavior:

- If `0 < coreLength < totalLength`, the destination remains zero outside a
  centered segment of `coreLength` values. The segment phase is
  `2*pi*index/coreLength`.
- With shape mode 1, the segment uses the four-term Blackman-Harris formula
  `0.35875 - 0.48829*cos(x) + 0.14128*cos(2*x) - 0.01168*cos(3*x)`.
- Other shape-mode values use periodic Hann, `0.5 - 0.5*cos(x)`.
- If coreLength is zero, negative, or at least totalLength, the function
  creates a full symmetric Hann window with denominator `totalLength - 1`.

`S5MegpoidFrameWindowBuilder` implements this isolated frame-window routine.
It does not infer the descriptor's coreLength or shapeMode: those remain
inputs supplied by the output-state constructor. The separate output window
at descriptor +0x60 is still being traced and is not substituted with this
frame window.

## Output-window normalization branches

The output-window code in FUN_180127dc0 uses the descriptor flags at +0x91
and +0x92. The disassembly establishes three distinct paths:

1. With both flags clear, it squares every frame-window coefficient,
   overlap-adds that squared vector into the output-length vector using the
   same interval calculation as the final renderer, then stores
   `1 / max(sum, 1e-8)` as the output window.
2. With +0x91 set and +0x92 clear, it forms a centered raised-cosine vector
   `0.5 * (cos(pi*x) + 1)`, where
   `x = (index - geometry/2) / outputSamples` and values with `abs(x) >= 1`
   become zero. It divides the original frame window by
   `max(originalFrameWindow, 1e-4)`, overlap-adds the raised-cosine vector,
   and stores the reciprocal overlap sum.
3. With +0x92 set, that branch takes precedence. It overlap-adds the current
   frame window itself and stores the reciprocal overlap sum.

`S5MegpoidOutputWindowBuilder` implements those three paths for the supplied
Megpoid geometry where `geometry == fftSize`. It returns a frame window
(possibly updated by the +0x91 path) and the complementary output window.
The two flags remain explicit inputs because the static caller passes
separate root-state bytes; their semantic product names are not inferred.

The frame-window and output-window builders are now independent components
that can be composed with `S5MegpoidOverlapAddDescriptorGeometry` and the
stateful `S5MegpoidSpectralOverlapAdd` executor. The complete transform-plan
constructor, root scalar provenance, and verified PCM boundary remain open.
