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

## Separate configuration-object trace — 2026-11-25

A focused Ghidra pass added the following configuration path:

```text
FUN_1800591d0
  -> FUN_180050aa0
       -> FUN_18005cba0(config, descriptorOutput, allocator)
            -> FUN_18004d660(config)
            -> FUN_180007210(languageContext, tokenCount)
```

Observed data flow in `FUN_18005cba0`:

- Reads an integer count at `config + 0x04`.
- `FUN_18004d660` sums that many signed 32-bit values from the array pointer at `config + 0x58`.
- Stores the sum in output field `descriptorOutput[0]`.
- Stores the sum rounded up to a multiple of eight in `descriptorOutput[1]`.
- Derives additional power-of-two and storage fields from other values in the configuration object.
- Calls the Korean/selected-language G2P entry `FUN_180007210` with the configured token count and builds additional per-token work descriptors.

This establishes a real configuration-to-G2P-workspace path, but it does **not** yet prove that `descriptorOutput[0]` or the source integer array is the category-width field at `FUN_18006c720`'s `+0x10). The phone event builder is called with a different-looking context pointer (`Func_417eec3e` passes its own `param_1 + 0xD0` to `FUN_18003ab70`), and the ownership/transfer connecting these objects is not proven.

Accordingly, Android does not use the summed value as a phone feature width, nor infer duration bounds, context counts, or flags from it. The recovered `S5ApiDefaultPhoneFeatureDescriptor` remains a validated byte-layout parser only; the active context values and their construction path still need to be recovered.

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
constructor. A separate, offset-consistent render-context path has now been recovered:

```text
Func_6b47c126
  -> lVar7 = FUN_180012b40(...)
       -> FUN_180013410(lVar7 + 0x18)
            -> FUN_180011680(lVar7 + 0x20)

Func_6b47c126
  -> FUN_1800591d0(lVar7 + 0x18, ...)
       -> FUN_180050aa0(lVar7 + 0x20, ...)
            -> if param_7 == 2:
                 FUN_180059c90(lVar7 + 0xE8, ...)
```

The address arithmetic is confirmed by the recovered argument types:
`FUN_180012b40` takes a `uint *`, so its `param_1 + 6` passed to
`FUN_180013410` is +0x18 bytes. `FUN_180013410` takes a `longlong`,
so its `param_1 + 8` passed to `FUN_180011680` is +8 bytes, giving
`lVar7 + 0x20`. `FUN_1800591d0` takes an `int *` and passes
`param_1 + 2` to `FUN_180050aa0`, also yielding `lVar7 + 0x20`.
In the selected `param_7 == 2` branch, `FUN_180050aa0` passes
`param_1 + 0x32` to `FUN_180059c90`, another +0xC8 bytes, placing
that DSP/FFT subobject at `lVar7 + 0xE8`.

This is a strong candidate for the object whose subobject is later
addressed by `Func_417eec3e` at `param_1 + 0xD0`: the two addresses
coincide when the exported adapter receives `param_1 = lVar7 + 0x18`.
However, the VSM-to-S5API callsite that proves this exact first-argument
identity has not yet been recovered. The current evidence therefore proves
the S5API-side object construction and address arithmetic, but not yet the
cross-export object identity.

The body of `FUN_180059c90` begins by calling `FUN_18004f2d0`, which
selects CPU/SIMD function pointers from CPUID features. It then copies
configuration records and builds FFT/history workspaces at higher offsets
(starting around its own `+0x59C)); it does not directly assign the phone
descriptor values at `+0x10..+0x26`. Consequently, the phone-feature
descriptor's scalar source values are still unresolved. The next target is
the lower-level object initialization performed through
`FUN_180011680` and the exact VSM call that passes the context into
`Func_417eec3e`.

The Android byte decoder remains only a validated parser for an already
captured descriptor; it does not itself read a live Windows context.
