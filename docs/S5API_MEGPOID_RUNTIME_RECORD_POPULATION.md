# AI Megpoid V7 runtime block-record population trace

Date: 2026-11-21

## Scope and evidence

This note records a static Ghidra trace of the supplied S5API.dll in the
VEA GitHub Actions workflow. The DLL was not executed. The first successful
export is workflow run
[44773123856](https://github.com/Kmj130830/VEA/actions/runs/44773123856),
artifact `14648034005`.

## Confirmed parent construction path

### FUN_1800ced10

The decompilation at `0x1800ced10` shows:

1. The routine computes a required count for the block-data vector at
   `+0x2E0`. Its begin/end difference is divided by `0x100`; insufficient
   capacity is passed to `FUN_1800b3130(param_1 + 0xB8, &local_2f0)`.
2. It manages the companion vector at `+0x2F8`. The element count is
   `param_1[5] * param_1[6]`; its begin/end difference is divided by
   `0x88`. Depending on capacity, the routine trims, initializes, or grows
   that vector.
3. For each nested model entry, it reads multiple properties from the parsed
   model object and prepares temporary descriptors.
4. It initializes the temporary block descriptor with
   `FUN_1800bc5e0`, then calls
   `FUN_1800d4bf0` to assemble its vectors and nested records.
5. The resulting object is appended to the `+0x2E0` vector. If there is no
   spare slot, `FUN_1800b28a0` constructs/appends the record; otherwise
   `FUN_1800bc0c0` copies it into the current end slot and the end pointer
   advances by exactly `0x100` bytes.
6. After the model-block loop, `FUN_1800d5a00(param_1)` prepares the
   associated frame/workspace vectors.

This is a direct constructor-to-vector connection: `FUN_1800ced10` writes
the block records using the same field and stride later consumed by
`FUN_1800c7ad0`.

| Model context field | Element stride | Runtime use |
|---|---:|---|
| `+0x2E0` | `0x100` bytes | block-data records consumed by `FUN_1800c7ad0` |
| `+0x2F8` | `0x88` bytes | companion history/workspace records consumed by `FUN_1800c7ad0` |

## Partial field-construction trace: FUN_1800d4bf0

The function at `0x1800d4bf0` receives a destination block record followed
by one tensor-like float vector, three pairs of descriptor inputs, and one
additional float-vector input. The Ghidra call-site instructions establish
that `FUN_1800ced10` passes eight temporary values in addition to the
destination record; the decompiler's high-level call expression omits the
stack-passed arguments, so these are not renamed semantically yet.

The following operations are direct observations from the decompilation:

- The first input vector is copied into an owned float buffer stored through
  the record's `+0x20` shared-pointer field. The buffer has
  `param_1[4] * param_1[5] * param_1[6]` elements. Its traversal changes
  source order from
  `((axisA * dimB + axisB) * dimC + axisC)` to
  `((axisA * dimC + axisC) * dimB + axisB)`; in other words, the final two
  axes are transposed.
- `FUN_1800d4a90` populates a nested descriptor beginning at destination
  offset `+0x30` from the first descriptor pair.
- `FUN_1800d4610` populates nested descriptors beginning at `+0x58` and
  `+0x88` from the next two descriptor pairs.
- `FUN_1800c0320` attaches the final shared float-vector input at
  destination offset `+0xB8`.
- The destination field at `+0xC8` is allocated as a float vector with
  `C * E * C` elements, where `C = *param_1` and
  `E = param_1[1]).
- The destination fields at `+0xD8` and `+0xE8` are each allocated as
  float vectors with `C * E` elements.
- The body sets the scalar at `+0xF8` to bit pattern `0x358637bd`,
  which is IEEE-754 float32 `1.0e-6`.

The computed vector lengths are important for the Android residual depthwise
MLP kernel: the expansion matrix storage has the expected
`(C * E) * C` size, and its two expanded-feature arrays have length `C * E`.
This also corrects the earlier rough note that described the normalization
arrays as length `C`; the builder's actual allocation loop uses `C * E`.

The +0x20 source vector's axis order is also now explicit. It is a
three-dimensional tensor transpose, not a plain flat copy. The descriptor
inputs at +0x30, +0x58, +0x88, and the vector attached at +0xB8 still need
their property names and exact consumer roles mapped before wiring Android
code to these fields.

## Existing runtime field consumers

The residual runtime operator `FUN_1800c5d50` consumes the established
block-record fields as follows:

| Record offset | Observed consumer | Current interpretation |
|---:|---|---|
| `+0x00` | residual operator | channel count `C` |
| `+0x04` | residual operator | expansion multiplier `E` |
| `+0x10` to `+0x20` | history/depthwise helper | depthwise descriptor and coefficient pointer |
| `+0x90` | residual operator | optional residual-bias presence flag |
| `+0x98` | second linear projection | projection matrix pointer |
| `+0xA8` | residual merge | optional residual-bias pointer |
| `+0xB8` | residual merge | residual-scale pointer |
| `+0xC8` | first linear projection | expansion matrix pointer |
| `+0xD8` | normalization/GELU affine step | expanded-feature gamma array |
| `+0xE8` | normalization/GELU affine step | expanded-feature beta array |
| `+0xF8` | normalization | epsilon, now confirmed as `1.0e-6` by the builder |

The `+0x88` companion record is a separate workspace/history structure.
Its internal pointers and history substructure are not part of the model
matrix bank.

## Next required mapping

The follow-up Ghidra export now includes:

- `FUN_1800bc5e0`, which initializes the scalar dimensions before the
  per-block builder;
- `FUN_1800d4a90`, `FUN_1800d4610`, and `FUN_1800c0320`, which populate
  the nested descriptor fields;
- eight model-property key factories called by `FUN_1800ced10`:
  `FUN_1800f1110`, `FUN_180114660`, `FUN_18011b770`,
  `FUN_180106440`, `FUN_18010d550`, `FUN_1800f8220`,
  `FUN_1800ff330`, and `FUN_1800ea000`.

These functions are the next step for identifying the exact
`AI_Megpoid.vtb2` keys and matching each source vector to the block fields.
Until that key-to-field provenance is proven, the Android implementation
must not substitute similarly sized tensors by arithmetic coincidence.

No end-to-end neural forward pass or synthesized PCM output is claimed.
