# S5API ACT activation primitive

## Recovered operation

FUN_18012d460 has the signature `void(float* planes, int count, char mode)` and modifies the first plane in place.

The function first clamps all 2*count float values in the two adjacent planes to [-8, +8]. The loop then evaluates one of two gated activation combinations:

- mode 0: sigmoid(plane1) * tanh(plane0)
- nonzero mode: tanh(plane1) * sigmoid(plane0)

With x = plane0 and y = plane1, the formulas are:

- mode 0: (1 / (1 + exp(-y))) * (2 / (1 + exp(-2*x)) - 1)
- mode 1: (2 / (1 + exp(-2*y)) - 1) * (1 / (1 + exp(-x)))

The second plane is clamped but retained as the gate input. Only the first plane is overwritten.

## Numerical dependency

The reference calls FUN_18003be50 for the exponential term rather than a visibly named CRT expf import. This could be an exponential implementation or approximation. The Android port should not substitute std::exp until this helper is decompiled and compared. The next Ghidra pass includes FUN_18003be50 and the AVX2 counterpart FUN_18012dc60.

## Implementation status

No ACT kernel is yet claimed as a vendor-compatible port. This report captures the scalar math and clamping behavior, while the exact exponential helper is being recovered before coding. It is one neural operator, not a complete RNN graph or singing renderer.