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

## Memory orientation

The native phone-feature routine writes row-major [position][feature] values. The Megpoid Dense operator consumes [feature][position]. S5ApiDefaultPhoneFeatureMatrix.toFeatureMajor() performs the transpose, and S5MegpoidSequenceTransitionPipeline.runPhoneFeatureMatrix() checks the Dense input width before invoking the existing sequence pipeline.

## Remaining work

The app can build this matrix only after timed VsmPhoneEvent objects and the active S5ApiDefaultPhoneFeatureLayout have been supplied. Note-level phoneme display text is not yet converted into aligned phone events, and active render-state descriptor fields are not fully populated.