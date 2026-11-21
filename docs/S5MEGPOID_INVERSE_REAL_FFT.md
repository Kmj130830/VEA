# Megpoid inverse real-spectrum transform

Date: 2026-11-21

## Scope

VEA contains a portable radix-2 inverse real-spectrum reference kernel in
S5MegpoidInverseRealFft. It accepts N/2 + 1 complex bins in interleaved
float32 form, reconstructs a Hermitian full spectrum, executes an inverse
transform using the S5API external-spectrum sign convention, and returns N
normalized real samples.

## Evidence-to-implementation mapping

Static analysis of S5API establishes two distinct sign operations:

1. In FUN_18012c740, the output converter copies N/2 + 1 complex bins into
   scratch storage. When the transform descriptor byte at +0x90 is enabled,
   it negates both the real and imaginary components of bins 1, 3, 5, ... .
   This is an optional alternating-bin sign operation on the complex values.
2. FUN_180040630 performs the real-transform packing. In inverse mode it
   negates only the imaginary component of every interior bin (1 through
   N/2 - 1), moves the Nyquist real value into the packed workspace slot,
   zeros the packed tail, doubles the first N workspace floats, and calls the
   FFT core with direction -1.
3. FUN_18012c740 divides the first N transformed real samples by N before
   overlap-add.

The reference implementation represents operation 1 using the
alternatingBinSign parameter. It folds operation 2's imaginary conjugation
and negative-direction packed real FFT into a direct positive-exponent
complex inverse over a Hermitian full spectrum. DC and Nyquist are
self-conjugate; their imaginary values are ignored. The code does not emulate
the vendor's packed workspace byte-for-byte.

## Test coverage

The unit tests verify reconstruction of DC, cosine and sine frames under the
S5API external-spectrum convention, the optional alternating-bin sign effect
on both real and imaginary components of odd bins, Nyquist reconstruction,
ignored DC/Nyquist imaginary values, malformed/non-finite spectrum rejection,
and finite output for a mixed spectrum.

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
