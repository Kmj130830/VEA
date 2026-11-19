# S5API scalar DSP operator trace

Date: 2026-11-19

## 1. Depthwise convolution dispatch

`FUN_1800c67e0` is a caller of `FUN_18012e0d0`. It selects the special path only when its two kernel dimensions are both 7. The selected code pointer is called once per output row/channel using seven input-row buffers.

`FUN_18012e0d0` checks `MRW_DWCONV_SIMD`:

- leading character `0`: selects scalar `FUN_18012f280`;
- AVX2 feature path: selects `FUN_180130110`;
- otherwise selects `FUN_18012e220` or scalar fallback according to `FUN_18003cbe0`.

The kernel entry sequence confirms a 10-argument function: output, seven input row pointers, 49-float filter pointer, and width. The decompiler's indirect call prototype at the caller omitted stack arguments, so the arguments were checked against the callee prologue rather than inferred from that decompiler line alone.

See `docs/S5API_DWCONV_PORT.md` for the scalar contract, border rule and Android implementation.

## 2. Layer-normalization statistics

`FUN_1800c5d50` invokes the selected function pointer returned by `FUN_180130b70`. The dispatcher checks `MRW_LNSTATS_SIMD`:

- leading character `0`: selects the scalar code label at `0x180130ce0`;
- AVX2 feature path: selects `FUN_180130d90`;
- otherwise retains the scalar label.

The scalar label's machine instructions directly establish the argument order and calculations:

- RCX: input float array;
- EDX: element count;
- R8: output sum pointer;
- R9: output sum-of-squares pointer;
- XMM2 accumulates the sum;
- XMM3 accumulates the sum of squares;
- the implementation processes four elements per loop and then a scalar tail.

The final stores are `[R9] = sum_squares` and `[R8] = sum`. This matches `vea_s5_reduce_sum_sumsq_f32` in `app/src/main/cpp/vea_s5_stats.cpp`.

The original dispatcher and scalar target use Windows x86/x64 CPU paths. The Android implementation is a fresh portable scalar C++ function; it does not reuse Windows machine code.

## 3. Activation operator

The ACT operator at `FUN_18012d460` clamps both planes to [-8, 8] and uses the approximation routine at `FUN_18003be50`. The native port is implemented in `vea_s5_activation.cpp` and has bit-pattern tests in `vea_s5_tensor_test.cpp`.

## 4. Verification boundary

CI has passed the host native test executable, Android debug APK assembly, and ARM64 ELF extraction for the commit that introduced the 7x7 scalar convolution. These results validate the new native primitives and packaging. They do not prove that the complete Megpoid V7 model can be loaded or synthesized yet.
