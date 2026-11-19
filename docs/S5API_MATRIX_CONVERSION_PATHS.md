# S5API matrix conversion paths

Three independent copy functions are now represented:

| S5API function | Input traversal | Output traversal |
|---|---|---|
| FUN_180064670 | interleaved [axis0][axis1][matrix] | one padded [axis1][axis0] matrix per matrix index |
| FUN_180065510 | contiguous [axis1][axis0] | padded [axis1][axis0] |
| FUN_18005df50 | contiguous [axis0][axis1] | transposed padded [axis1][axis0] |

All use a 24-byte descriptor, padded axis0 aligned up to 8, and 32-byte aligned arena allocations.

The FUN_18005df50 path does not have a matrix-bank dimension. Its source traversal increments through axis1 inside each axis0 row, while its destination index is axis1 * padded_axis0 + axis0. The Android implementation exposes this separately as packTransposed.

Tests use a non-square 3 x 2 matrix to make a transpose error observable, and verify the padded tail remains zero. This prevents accidental reuse of the row-major copy path for model nodes that need a true transpose.