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
## Descriptor owner and constructor offset — 2026-11-25

The `Func_417eec3e` export calls `FUN_18003ab70` with
`param_1 + 0xD0` as the phone feature descriptor. The descriptor fields
consumed downstream by `FUN_18006c720` are relative to that pointer:
category width at +0x10, minimum duration at +0x14, maximum duration at
+0x18, following/preceding context counts at +0x1C/+0x20, optional flag
pair at +0x24, and layout mode at +0x25.

The constructor chain has a matching subobject location:

```text
FUN_180013410(owner)
  -> FUN_180011680(owner + 0x08)
       -> FUN_180013830((owner + 0x08) + 200)
```

The decimal offset 200 is 0xC8, so the final constructor target is exactly
`owner + 0xD0`. This is an offset-level match to the descriptor pointer
passed by `Func_417eec3e`. The constructor target is now explicitly included
in the Ghidra render-path export so its initialized fields and later
overwrites can be distinguished.

This establishes a concrete constructor candidate for the descriptor; it
does not yet prove that every caller passes the same owner type or that the
constructor's defaults are the final active render values. In particular,
the category width, duration limits, context counts, optional flag and mode
must be traced past constructor initialization before they can be used to
prepare Megpoid input.