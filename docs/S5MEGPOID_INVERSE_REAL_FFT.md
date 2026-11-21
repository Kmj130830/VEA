# Megpoid inverse real-spectrum transform

Date: 2026-11-21

## Scope

VEA now contains a portable radix-2 inverse real-spectrum reference kernel in
S5MegpoidInverseRealFft. It accepts N/2 + 1 complex bins in interleaved
float32 form, reconstructs the Hermitian full spectrum, executes a complex
inverse transform, and returns N normalized real samples.

## Evidence-to-implementation mapping

The static S5API trace in S5API_MEGPOID_SPECTRAL_OUTPUT.md establishes:

- the output conversion receives N/2 + 1 complex float32 bins;
- the call from FUN_18012c740 selects transform direction -1;
- the converted real frame is divided by N before overlap-add;
- a descriptor flag controls an alternating-bin sign change.

The implementation follows the recovered inverse direction and 1/N
normalization. The caller supplies the alternating-bin parity explicitly
(0 for even bins, 1 for odd bins) because the current descriptor report has
not yet established which parity the vendor flag selects.

## Test coverage

The unit tests verify reconstruction of DC, cosine, sine and Nyquist frames,
the caller-selected alternating-bin sign, spectrum-size validation, finite
input handling, and finite output for a mixed spectrum.

## Remaining integration boundary

This component is only the spectral-bin-to-real-frame transform. It is not
yet connected to the model's complex-spectrum producer. The vendor transform
plan's complete constructor parameters, exact FFT backend numerical behavior,
overlap ring geometry, window coefficients, and final PCM/WAV conversion are
still separate tasks.

The implementation requires an even power-of-two FFT size. This constraint
belongs to the current radix-2 reference implementation; the vendor's full
supported transform-size set has not been established. No successful
Megpoid render is claimed by this change.
