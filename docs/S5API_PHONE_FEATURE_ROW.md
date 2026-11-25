# S5API default phone feature row

FUN_18006c720 mode 0 converts ordered, already aligned phone events into one float32 feature row per event. The Android implementation is S5ApiDefaultPhoneFeatureMatrixBuilder. It does not generate G2P output or invent phone timing.

## Row layout

1. Duration scalar at feature offset 0.
2. Optional two-value leading-bang flag pair at offsets 1 and 2 when descriptor +0x24 is nonzero. FUN_180074a50 sets the flag when the original phone label starts with '!'. The row stores [1,0] for a marked label and [0,1] otherwise.
3. Centre-phone one-hot category vector.
4. Preceding phone categories, oldest to nearest.
5. Following phone categories, nearest to farthest.

Each category segment has descriptor +0x10 floats. Its width must be at least 14 to hold all recovered category indices; the actual width remains runtime data.

## Context windows

- Descriptor +0x20 is passed as the preceding-context count (native param_10). Its first segment uses event index centreIndex - leftCount, followed by increasing event indices.
- Descriptor +0x1C is passed as the following-context count (native param_9). Its first segment uses centreIndex + 1, followed by increasing event indices.
- Neighbour indices clamp to the first and last event at sequence boundaries.
- Segment order is centre, preceding contexts, then following contexts.

Descriptor +0x25 selects the feature-layout branch. Only mode 0 is implemented. Other modes are rejected rather than silently treated as mode 0.

## Active mode-0 descriptor offsets

The render-context descriptor consumed by `FUN_18003ab70` is forwarded to
`FUN_18006c720` with these exact fields:

| Offset | Mode-0 role |
|---:|---|
| `+0x10` | Category-vector stride/width |
| `+0x14` | Minimum phone duration in seconds (float32) |
| `+0x18` | Maximum phone duration in seconds (float32) |
| `+0x1C` | Following-context event count |
| `+0x20` | Preceding-context event count |
| `+0x24` | Enables the two-value leading-bang flag pair |
| `+0x25` | Feature-layout mode; mode 0 is ported |
| `+0x26` | Preserved auxiliary byte; not consumed by the recovered mode-0 branch |

The mode-0 allocation formula is
`1 + (flagPairEnabled ? 2 : 0) + categoryWidth * (preceding + 1 + following)`.
When the flag pair is enabled, the centre one-hot category starts at offset
3 rather than offset 1. The same +0x24 byte controls both allocation and
that offset, so the Android `S5ApiDefaultPhoneFeatureLayout` mirrors it.

The Android `S5ApiDefaultPhoneFeatureDescriptor` now keeps all of these
fields together with the +0x14/+0x18 duration bounds. It validates the
descriptor and only converts to the implemented mode-0 layout when
`layoutModeAt25 == 0`. These are value-field offsets from the decompiled
feature builder; their actual runtime values still need to be read from the
active S5API render context.

## Memory orientation

The native phone-feature routine writes row-major [position][feature] values. The Megpoid Dense operator consumes [feature][position]. S5ApiDefaultPhoneFeatureMatrix.toFeatureMajor() performs the transpose, and S5MegpoidSequenceTransitionPipeline.runPhoneFeatureMatrix() checks the Dense input width before invoking the existing sequence pipeline.

## Remaining work

The app can build this matrix only after timed VsmPhoneEvent objects and the active S5ApiDefaultPhoneFeatureLayout have been supplied. Note-level phoneme display text is not yet converted into aligned phone events, and active render-state descriptor fields are not fully populated.
## Constructor-path correction — 2026-11-25

The earlier proposed match between the phone descriptor at
`Func_417eec3e`'s `param_1 + 0xD0` and `FUN_180013830` was incorrect.
It omitted the `+0x18` pointer adjustment at the preceding callsite.

The actual constructor path recovered from `FUN_180012b40` is:

```text
FUN_180012b40(param_1 = lVar7)
  -> FUN_180013410(param_1 + 6)       // uint* arithmetic: lVar7 + 0x18
       -> FUN_180011680(param_1 + 8)  // lVar7 + 0x20
            -> FUN_180013830(param_1 + 200) // lVar7 + 0xE8
```

By contrast, `Func_417eec3e` passes its own `param_1 + 0xD0` to
`FUN_18003ab70`. The roots are not proven to be the same object, and the
computed offsets do not match: the large `FUN_180013830` subobject begins
at `lVar7 + 0xE8`, not `lVar7 + 0xD0`. Its observed constructor also
initializes a much larger DSP state object, including a 256-float generated
window, rather than establishing the phone descriptor's +0x10..+0x26
fields.

Therefore `FUN_180013830` is **not** evidence for the phone descriptor's
constructor. The actual owner and field-population path for the descriptor
passed to `FUN_18003ab70` remain unresolved. The byte decoder added in
Android is only a validated parser for the already recovered field offsets;
it does not claim to obtain those values from the live Windows context.
