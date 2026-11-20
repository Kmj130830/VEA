# AI Megpoid V7 runtime block-record population trace

Date: 2026-11-21

## Scope and evidence

This note follows the static Ghidra trace produced for S5API.dll by workflow
run [44771889519](https://github.com/Kmj130830/VEA/actions/runs/44771889519)
from PR #7 head `b10f0b5b85f00287c4a0ff342cb1aed370368753`.
Its uploaded artifact was `VEA-Megpoid-block-shape-map` (artifact
`14648775164`). The DLL was analyzed statically and was not executed.

The new trace closes the object-construction gap between the runtime record
arrays used by `FUN_1800c7ad0` and the model-configuration loader.

## Confirmed population path

### Parent model configuration: FUN_1800ced10

The decompilation at `0x1800ced10` shows the following operations:

1. The routine calculates a required element count for the block-data vector
   at byte offset `+0x2E0`. Its vector begin/end difference is divided by
   `0x100`, and insufficient capacity is passed to
   `FUN_1800b3130(param_1 + 0xB8, &local_2f0)`.
2. It manages the companion vector at byte offset `+0x2F8`. The element
   count is `param_1[5] * param_1[6]`; the begin/end difference is divided
   by `0x88`. Depending on capacity, the routine trims, initializes, or
   grows that vector.
3. For each nested model entry, it reads multiple properties from the parsed
   model object and prepares temporary descriptors.
4. It calls
   `FUN_1800d4bf0(local_2a8, &local_2d8, local_150, &local_110)`
   to construct a temporary per-block object at `local_2a8`.
5. The resulting object is appended to the `+0x2E0` vector. If the vector
   has no spare slot, `FUN_1800b28a0` constructs/appends the record;
   otherwise `FUN_1800bc0c0` copies it into the current end slot and the
   end pointer advances by exactly `0x100` bytes.
6. After the model-block loop, `FUN_1800d5a00(param_1)` prepares the
   associated frame/workspace vectors.

The source expressions establish the storage stride and the call that builds
each temporary record. They do not yet identify the semantic role of every
input argument to `FUN_1800d4bf0`.

### Alignment with the forward dispatcher

This population path matches the runtime dispatcher's record-array geometry:

| Model context field | Element stride | Runtime use |
|---|---:|---|
| `+0x2E0` | `0x100` bytes | block-data records consumed by `FUN_1800c7ad0` |
| `+0x2F8` | `0x88` bytes | companion history/workspace records consumed by `FUN_1800c7ad0` |

This is a direct constructor-to-vector connection, not just an offset coincidence:
`FUN_1800ced10` writes the block records using the same vector field and
stride later consumed by `FUN_1800c7ad0`.

## Existing 0x100-byte record contract

The separate runtime-operator trace establishes these offsets inside each
block-data record:

| Record offset | Observed consumer | Current interpretation |
|---:|---|---|
| `+0x00` | residual operator | channel count `C` |
| `+0x04` | residual operator | expansion multiplier `E` |
| `+0x10` to `+0x20` | history/depthwise helper | depthwise descriptor and coefficient pointer |
| `+0x90` | residual operator | optional residual-bias presence flag |
| `+0x98` | projection call | projection matrix pointer |
| `+0xA8` | residual merge | optional residual bias pointer |
| `+0xB8` | residual merge | residual scale pointer |
| `+0xC8` | expansion call | expansion matrix pointer |
| `+0xD8` | normalization/GELU affine step | gamma pointer |
| `+0xE8` | normalization/GELU affine step | beta pointer |
| `+0xF8` | normalization | epsilon |

The `+0x88` companion record remains a workspace/history structure. Its
internal pointers and history substructure are separate from the matrix bank.

## Next trace target

The immediate next target is `FUN_1800d4bf0`, called once for each temporary
block-data record. The follow-up Ghidra export now includes that function,
its direct callers, and the vector helpers `FUN_1800b3130`,
`FUN_1800b28a0`, `FUN_1800bc0c0`, `FUN_1800b0b30`, and
`FUN_1800b36d0`.

The required output from that trace is a field-by-field provenance map:

- which parsed model property supplies each pointer at `+0x20`,
  `+0x98`, `+0xA8`, `+0xB8`, `+0xC8`, `+0xD8), and `+0xE8);
- how the scalar dimensions and epsilon at `+0x00`, `+0x04), and
  `+0xF8` are derived;
- which `AI_Megpoid.vtb2` key is associated with each source tensor;
- whether the companion history record is initialized by the same builder
  or a separate constructor.

Only after that provenance is established should Android code bind decoded
voicebank tensors to the residual block kernel. The existing scalar kernel
and named-layer descriptor preparation are not, by themselves, an end-to-end
VOCALOID forward pass. This note does not claim synthesized PCM output.
