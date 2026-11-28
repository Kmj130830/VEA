# S5API VSM frame post-processing trace

Date: 2026-11-28

## Scope

This note records a static trace of the generic S5API/VSM frame post-processing
functions `FUN_180076080` and `FUN_180077520`. The observations come from
Ghidra decompilation of the supplied S5API module and are not runtime audio
measurements. These functions must not be confused with the AI Megpoid VTB2
four-part spectral head at `FUN_1800c7ad0`; no direct mapping between their
operator objects and the nine named `VOCALOID7.vtmg` tensors has been proven.

## Call boundary

Two distinct call contexts were recovered:

- `FUN_18003ab70` calls `FUN_180076080` while building the S5API timed
  phone/event feature path.
- `FUN_18003e920` first runs two `FUN_18007a0a0` projections, adding their
  residual vectors and applying tanh after each. It then appends the supplied
  frame feature tail, applies the caller scale, and calls
  `FUN_180076080` with the state at `param_1 + 0x1c`. When its optional final
  output pointer is non-null, it follows with `FUN_180077520`.

The latter wrapper is therefore a concrete producer/consumer boundary for the
stateful frame graph; it is not simply another spelling of the VTB2
post-residual output head.

## FUN_180076080: stateful feature preparation

The static body shows this sequence:

1. Copy the incoming feature vector into the first circular buffer described
   by the workspace at `param_2`, then advance that ring's write cursor.
2. Run the `param_1[4]` operators referenced through `param_1 + 0x1c`.
   Each is called through vtable slot `+0x30`, receiving the state/config
   block at `param_1 + 0x46`, a delayed source position in the first ring,
   the configured feature widths and a scratch output buffer.
3. When `param_1[0x1f]` is nonzero, add the configured residual vector from
   `param_1 + 0x22`. Append the resulting vector to the next circular
   buffer descriptor at `param_2 + 8`.
4. Copy the supplied frame/control vector into a temporary buffer. When the
   frame selector argument is negative, select the indexed history frame
   directly from the state buffer at `param_1 + 0x14`. Otherwise, blend the
   two selected history frames using the interpolation weight clamped to
   `[0,1]`.
5. If the flag at `param_1[0xc]` is set, run the optional operator at
   `param_1 + 0x30` through vtable slot `+0x28`.
6. Iterate `param_1[2]` stage descriptors. Each descriptor has a 0x38-byte
   stride and contains an operator list, an optional residual, additional
   side-input/skip references, and a terminal operator. The inner operators
   use vtable slot `+0x30`; the terminal operator is dispatched through
   `+0x28` or `+0x30` according to the native mode field.
7. Intermediate results are copied into additional workspace history rings.
   The body also applies branch-selected vector transforms and writes the
   final prepared frame state.

Important state fields visible in the decompilation include:

| State | Native location / evidence |
|---|---|
| Initial operator list | pointer at `param_1 + 0x1c`, count `param_1[4]` |
| Stage descriptor list | pointer at `param_1 + 0x24`, count `param_1[2]`, stride 0x38 |
| Optional pre-stage operator | pointer at `param_1 + 0x30`, enabled by `param_1[0xc]` |
| History/config block | `param_1 + 0x14`, `param_1 + 0x46` |
| Workspace ring descriptors | `param_2`, `param_2 + 8`, and later ring slots |

The exact byte/element semantics of every descriptor member are not fully
recovered. These offsets are retained as observations, not renamed into
unverified semantic field names.

## FUN_180077520: vector projection stack

This function is a separate vector-to-vector stack which consumes the prepared
frame vector from `FUN_180076080`.

1. Select the first projection object for the requested branch index from the
   pointer table at `param_1 + 0xc8`; call its vtable slot `+0x28`.
2. If the first descriptor's bias-enable flag at `+0x0c` is set, add its
   bias vector at `+0x18`.
3. Apply the configured activation:
   - `param_1 + 0xf4 == 1`: `2 / (exp(-2*x) + 1) - 1` (tanh form);
   - otherwise: leaky ReLU with slope 0.01;
   - if `param_1 + 0xfc` is set, cap the leaky-ReLU result at 4.
4. For each remaining hidden projection (count = first-stage count minus one),
   call the branch-indexed operator, add its optional bias, and apply the same
   hidden activation.
5. Run the branch-indexed final projection from `param_1 + 0xd0`, add the
   optional final bias when enabled, and apply tanh only when
   `param_1 + 0xf8 == 1`.
6. Copy the resulting output vector into the caller's final output buffer.

## Android implementation added

`S5ApiFrameProjectionStack.kt` now ports the vector-stack arithmetic as a
voicebank-neutral component:

- `S5ApiFrameVectorOperator` is the operator boundary for native vtable-backed
  layers.
- `S5ApiFrameDenseVectorOperator` provides a row-major dense implementation
  using the existing GEMM contract.
- `S5ApiFrameProjectionLayer` owns optional bias handling.
- `S5ApiFrameProjectionStack` executes projection, bias, hidden activation,
  final projection, optional final bias and optional final tanh in native order.

Unit tests cover a first plus hidden layer, the 0.01 leaky-ReLU and clamp mode,
a stack with no hidden projection, disabled bias, dimension validation and
non-finite inputs.

## Remaining integration work

1. Recover the constructors/loaders that populate the native operator lists
   at `param_1 + 0x1c`, the 0x38-byte stage descriptors at `param_1 + 0x24`,
   and the branch-indexed vector stack at `param_1 + 0xc8/+0xd0`.
2. Identify the exact VTB2/VTMG tensor references, shapes, transpositions and
   branch indices for those native operator objects. Do not assume that
   matching names or generic Dense dimensions establish that mapping.
3. Port the full stateful `FUN_180076080` history, interpolation and nested
   operator orchestration against those recovered descriptors.
4. Establish whether and where this VSM path is required by the selected
   Megpoid render route, then compare prepared vectors against authorized
   native reference vectors before connecting it to PCM output.

No end-to-end synthesis or native numeric parity is claimed by this component.
