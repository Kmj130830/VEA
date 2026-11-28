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

## Native delayed-frame ring contract and Android port

The history descriptors used by `FUN_180076080` share a compact ring
contract. The write path uses element count, per-slot stride, a monotonic
cursor, a power-of-two slot mask and a data pointer. Immediately after
appending a frame, the initial operator loop reads:

```text
slot = (writeCursor - operatorIndex - 1) & slotMask
source = ringData + slot * slotStride
```

Thus operator index zero sees the frame just written, index one sees the
previous frame, and the ring's zero-filled allocation supplies startup history
until those older frames have been written.

`S5ApiFrameHistoryRing` now ports this generic buffer behavior. Its
`elementCount`, `slotStride`, and power-of-two `historySlots` explicitly
represent the native descriptor rather than guessing dimensions from a
Megpoid VTB2 tensor. The tests cover delayed reads, zero-filled startup,
wraparound, oldest-to-newest snapshots, per-slot padding and reset.

The separate parameter-owned frame history at `param_1 + 0x14` follows a
different rule. When `param_8 < 0`, native code copies the physical history
frame selected by `param_7`. Otherwise it blends frame `param_7` with frame
`int(param_8)`, using `param_9` clamped to `[0,1]`. Android now exposes
this as `S5ApiFrameHistoryInterpolator`, with separate direct-selection and
blended-selection paths and tests for both endpoints and clamping.

Neither helper invokes the native operator objects or runs the nested
0x38-byte stage descriptors. Those remain separate stateful parts of
`FUN_180076080`.

## Android implementation added

The native first/hidden projection helper `FUN_18007a0a0` was also
recovered. Its scalar path reads the descriptor's dense matrix in
`[inputFeature][outputFeature]` order with `outputFeatures` as the row
stride, and accumulates one input vector into the output vector. When the
output width is divisible by 64 it dispatches through the aligned function
pointer stored at state offset +0x398. Android now provides
`S5ApiFrameInputMajorDenseVectorOperator` for the exact input-major layout;
an optional `S5ApiFrameAlignedDenseKernel` can supply that accelerated
branch when its constructor and ABI are recovered. The default scalar path
matches the native non-aligned accumulation order.

The fast activation helper `FUN_18003be50` was recovered as well. It
approximates exp, constructs an IEEE-754 float bit pattern in its low 32-bit
return value, and is used by the `FUN_180076080/FUN_180077520` vector
activation path. `S5ApiFrameMath` now preserves the double-literal
polynomial, final integer conversion and float-bit reinterpretation. This is
kept separate from the `tanhf` calls in `FUN_18003e920`, which use
`S5ApiFrameMath.standardTanh()`.

Two reusable components now port the arithmetic around this stateful
frame path:

- `S5ApiFrameHistoryRing.kt` ports the native append and delayed-slot read
  contract for a power-of-two vector ring.
- `S5ApiFramePreProjectionChain.kt` implements the two
  `FUN_18007a0a0` projection calls from `FUN_18003e920`, including the
  separate residual vector and tanh after each projection. It then appends
  the caller-supplied tail, multiplies only that tail by the supplied scale,
  and optionally appends the scale as the final sentinel element.
- `S5ApiFrameInputMajorDenseVectorOperator.kt` implements the native
  `[inputFeature][outputFeature]` matrix layout and optional 64-wide dispatch.
- `S5ApiFrameMath.kt` ports the fast exponential/bit-pattern activation used
  by 076080/077520 separately from platform tanhf.
- `S5ApiFrameProjectionStack.kt` implements the vector-stack arithmetic from
  `FUN_180077520` as a voicebank-neutral component:
  - `S5ApiFrameVectorOperator` is the operator boundary for native
    vtable-backed layers.
  - `S5ApiFrameDenseVectorOperator` provides a row-major dense implementation
    using the existing GEMM contract.
  - `S5ApiFrameProjectionLayer` owns optional bias handling.
  - `S5ApiFrameProjectionStack` executes projection, bias, hidden activation,
    final projection, optional final bias and optional final tanh in native order.

Unit tests cover two residual/tanh pre-projections, tail-only scaling and the
optional scale sentinel, delayed ring slots, wraparound and startup zeros; first/hidden vector projections, the 0.01 leaky-ReLU
and clamp mode, a stack with no hidden projection, disabled bias, dimension
validation and non-finite inputs.

These classes intentionally stop at operator injection. They do not yet load
the native vtable objects or infer their weights from a same-named Megpoid or
VTMG tensor.

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


## Event-row gathering into frame order — Android implementation

The timed alignment arrays identify the source phone-event row for every
render frame. VEA now exposes that gather operation as
`S5ApiFrameAlignedPhoneFeatureMatrixBuilder`:

- Input: the row-major event feature matrix from `FUN_18006c720` and the
  `eventIndexByFrame` array from the recovered timing map.
- Output: a row-major frame matrix in which each frame receives a copy of
  the feature row belonging to its selected source event.
- The builder validates every selected event index against the event-matrix
  row count and checks allocation geometry before copying.
- `toFeatureMajor()` exposes the same data in feature-by-frame order for
  downstream vector/matrix operators.

The optional `PreparedMegpoidExplicitPhoneSequenceResult.frameAlignedPhoneFeatureMatrix`
property makes this intermediate result available only when the caller
provided the native frame period and event-control value. Relative start,
event duration and event control remain separate timing metadata; this
builder does not fold them into phone-feature rows. It does not execute the
stateful `FUN_180076080` operators, does not map their descriptors to VTB2
weights, and does not claim an acoustic spectrum or PCM output.


## Multi-ring history workspace and atomic delayed-input snapshots

The native `FUN_180076080` path receives a workspace descriptor area with
an input history ring at `param_2`, a following ring at `param_2 + 8`, and
additional rings used by intermediate stages. Android now provides
`S5ApiFrameHistoryWorkspace` to own multiple independent rings using the
verified per-ring element count, slot stride and power-of-two capacity.

`appendAndReadDelayed` performs the input-ring append and captures the
requested delayed frames under one synchronized operation. Its returned
indices match the recovered native history read rule: operator index 0 gets
the frame just appended, index 1 gets the preceding frame, and older startup
slots remain zero-filled. The snapshot owns its arrays, preventing a
caller/operator from modifying either the ring or another operator's input.
The workspace also resets all ring cursors together at an utterance boundary.

This is the verified buffer orchestration boundary only. It does not decode
the nested 0x38-byte stage descriptors, execute their vtable operators, or
map their parameters to the nine VTMG layers. Those mappings remain required
before this workspace can be used as a complete `FUN_180076080` port.
