# S5API row-major tensor packing path

Ghidra decompilation of FUN_180065510 recovers a second matrix-copy path.

- axis0 = param_4
- axis1 = param_3
- padded_axis0 = align_up(axis0, 8)
- source is consumed as contiguous [axis1][axis0]
- destination is [axis1][padded_axis0]
- each row is copied at its own padded destination stride

This is distinct from FUN_18005df50, which consumes source values by a transpose-like loop. Both paths share the descriptor layout, but their source iteration order is different.

Implemented as:
- vea_s5_pack_row_major_f32 in vea_s5_tensor.cpp
- JNI method nativePackRowMajor
- Kotlin API NativeS5TensorBridge.packRowMajor

Tests include padded (3-column) and already aligned (8-column) dimensions, plus arena-exhaustion rollback. This only handles arrays after the outer container/object parser has supplied decoded float32 values.
