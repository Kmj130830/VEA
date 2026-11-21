# Megpoid inverse real-spectrum transform

Date: 2026-11-21

## Scope

VEA contains a portable radix-2 inverse real-spectrum reference kernel in
S5MegpoidInverseRealFft. It accepts N/2 + 1 complex bins in interleaved
float32 form, reconstructs a Hermitian full spectrum, executes an inverse
transform using the S5API external-spectrum sign convention, and returns N
normalized real samples.

## Evidence-to-implementation mapping

The Ghidra static pseudocode for S5API FUN_180040630 establishes the wrapper
operations:

- Forward mode calls the FFT core with direction +1, then flips the sign bit
  of the imaginary component of every interior one-sided complex bin.
- Inverse mode first flips the sign bit of the imaginary component of every
  interior one-sided complex bin, packs the Nyquist real value into the real
  FFT workspace slot, zeros the packed tail, doubles the workspace values,
  and calls the FFT core with direction -1.
- The higher-level output conversion FUN_18012c740 then divides the first N
  transformed real samples by N before overlap-add.

This reference implementation folds the inverse wrapper's imaginary-sign
restoration and negative-direction packed real FFT into a direct positive-
exponent complex inverse over a Hermitian full spectrum. DC and Nyquist are
self-conjugate; their imaginary values are ignored. It does not emulate the
vendor's packed workspace byte-for-byte.

## Test coverage

The unit tests verify reconstruction of DC, cosine, sine under S5API's
conjugated forward-spectrum convention, and Nyquist frames. They also check
that imaginary DC/Nyquist values are ignored, malformed/non-finite spectra
are rejected, and mixed spectra produce finite samples.

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
