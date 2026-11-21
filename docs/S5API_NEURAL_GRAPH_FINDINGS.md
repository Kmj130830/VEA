# S5API neural model graph findings

Date: 2026-11-19

This report records static evidence from the supplied Editor.zip S5API.dll. It distinguishes directly observed implementation details from architecture hypotheses.

## 1. Model/configuration loader

The function at RVA `0x68190` (`FUN_180068190`) reads the following named child entries from the model/configuration object and passes them to a shared tensor loader (`FUN_180064670`):

- `pre1`
- `pre2`
- `conv1` / `cond1`
- `conv2` / `cond2`
- `conv3` / `cond3`
- `linear`

The call sites pass output pointers and dimension/count arguments. The names are therefore not just unused debug strings: they are actual configuration keys consumed by the layer construction path.

`FUN_180020190` reads a `.weight` member. `FUN_1800214c0` reads a `.bias` member. Both are JSON-tree/object accessors and data-copy routines. The exact numeric dtype, tensor layout, and whether data is nested or flattened must be confirmed from `FUN_180064670` and the lower-level tensor readers before reproducing them.

## 2. Recurrent layer

`FUN_18005ebe0` retrieves parameter names `weight_ih_l` and `weight_hh_l`, and uses `FUN_180024a90` to form the indexed/directional member names. The code computes `param_5 * 3` for a recurrent gate-width factor. Three gates are consistent with a GRU parameter layout, whereas a standard LSTM uses four gates.

The presence of `_reverse` in the same family of model-member names indicates a reverse-direction parameter path. Together these are strong evidence for a bidirectional GRU-style block, but the graph order, reset/update/new gate ordering, activation functions, bias handling, and sequence packing are not yet verified. Treat "bidirectional GRU" as the current best structural hypothesis, not a fully recovered graph.

## 3. Streaming/spectral state diagnostics

`FUN_1800c7ad0` and `FUN_1800c1a30` call `FUN_1800ccdb0` with names including:

- `prior1`, `prior2`, `excitation`
- `reset_real`, `reset_imag`, `theta_ctr`, `vuv_gate`
- `noise_prerot_real`, `noise_prerot_imag`
- `Xcarrier_real`, `Xcarrier_imag`, `X_real`, `X_imag`
- `free_real`, `free_imag`, `ana_cosP`, `ana_sinP`, `waveform`
- `delta`, `Xh_real`, `Xh_imag`, `Xn_real`, `Xn_imag`

The names are diagnostic dump labels, not model-file keys. The helper at RVA `0xccdb0` uses the label to construct a `.bin` file name and writes a float buffer when the runtime dump flag at context offset `+0x36c9` is enabled. The labeled buffers belong to the internal spectral/phase-processing path; their values, equations and tensor axes still need to be recovered. Do not use these labels as evidence of the .vtb2 model tensor schema.

## 4. CPU-specific operator dispatch

Three dispatch functions were identified:

- `FUN_18012d350`: selects the activation-kernel implementation (`MRW_ACT_SIMD`).
- `FUN_18012e0d0`: selects the depthwise-convolution implementation (`MRW_DWCONV_SIMD`).
- `FUN_180130b70`: selects the layer-normalization-statistics implementation (`MRW_LNSTATS_SIMD`).

Their dispatch logic checks x86 CPUID/XCR0 and selects scalar, AVX-family or AVX2-capable implementations. These machine-code kernels cannot be reused as Android ARM64 code. A native port must implement functionally equivalent scalar and/or NEON kernels and verify numeric agreement.

## 5. External runtime dependencies

The PE import table for S5API.dll imports Windows synchronization, Kernel32 file/thread APIs, the Microsoft C/C++ runtime, and the CRT math/heap/string/filesystem libraries. The import list contains no ONNX Runtime, TensorFlow, PyTorch, DirectML, CUDA, or other third-party neural inference runtime.

This is a bounded observation about the static PE import table, not proof that no runtime component is dynamically loaded elsewhere. However, it indicates that the model parsing and inference kernels visible in S5API are implemented inside the module rather than delegated through a normal imported inference SDK. Android therefore needs a portable replacement for these internal routines, not merely an Android build of a missing vendor inference library.

## 6. What is now a suitable porting target

The analysis has separated the engine into these recoverable boundaries:

1. Model object and tensor parsing: `FUN_180068190`, `FUN_180020190`, `FUN_1800214c0`, `FUN_180064670`.
2. Recurrent parameter loading: `FUN_18005ebe0`, `FUN_180024a90`.
3. Convolution/conditioning layer setup: `pre1/pre2`, `conv1/cond1`, `conv2/cond2`, `conv3/cond3`, `linear`.
4. Stateful spectral processing and diagnostics: `FUN_1800c7ad0`, `FUN_1800c1a30`, `FUN_1800ccdb0` (diagnostic dump helper).
5. Numeric kernels and dispatch: activation, depthwise convolution, layer-normalization statistics, plus the associated non-dispatch worker bodies.

The next implementation dependency is the low-level tensor reader called from the layer loader. Do not hard-code tensor shapes or invent GRU gate order before those functions and the model data are reconciled.

## 7. Port status

- `libveavsm.so` currently implements the Android-side render callback transport/capture boundary and float32 WAV export adapter.
- It does **not** yet implement these neural layers, the spectral decoder, or Megpoid V7 voice generation.
- The original modules remain Windows PE/AMD64. The ARM64 renderer must be rebuilt from portable C++/NEON source; no PE-to-ELF conversion is claimed.

## 8. Verified scalar convolution and statistics kernels

A focused follow-up analysis connected the 7x7 depthwise convolution to its actual caller:

- `FUN_1800c67e0` selects `FUN_18012e0d0` only when both convolution dimensions are 7.
- `FUN_18012e0d0` can choose `FUN_18012f280` as the scalar fallback, `FUN_18012e220` as the vector path, or `FUN_180130110` as the AVX2 path.
- The scalar kernel accepts an output pointer, seven input-row pointers, 49 float weights, and an integer width. It writes the same number of output samples as the input width and uses reflect-without-edge-repeat horizontal borders.
- VEA now contains a portable ARM64-compatible scalar implementation, `vea_s5_depthwise_conv7x7_reflect_f32`, with edge-index tests. It is not yet a NEON optimization.

The same trace recovered the scalar target behind `FUN_180130b70`: `MRW_LNSTATS_SIMD` selects the label at `0x180130ce0` when forced to scalar, or `FUN_180130d90` for the AVX2 path. The scalar routine accumulates both `sum(input)` and `sum(input * input)`; its register arguments and output order match `vea_s5_reduce_sum_sumsq_f32`.

See `docs/S5API_CORE_OPERATOR_TRACE.md` and `docs/S5API_DWCONV_PORT.md` for details. This proves two DSP primitives, not the full neural inference graph.

## 9. GELU, statistics and residual merge in the convolution block

The complete decompiler trace of `FUN_1800c5d50` clarifies the block order: depthwise convolution → sum/sum-square statistics → mean/variance and epsilon/std calculation → GELU → residual affine merge. The variance calculation is `max(sumSquares / N - mean * mean, 0)`, followed by model epsilon addition and square root.

The GELU dispatcher `FUN_18012cb50` selects AVX `FUN_18012cff0`, vector `FUN_18012cbc0`, or scalar `FUN_18012ca50`. The scalar body performs `x = scale * input + bias`, then `x / (1 + exp(-((x*x*x*0.044715 + x)*1.5957692)))`. This is a GELU approximation, not the two-plane ACT gate.

VEA has added the scalar GELU implementation `vea_s5_apply_gelu_f32` and Kotlin/JNI entrypoint `NativeS5TensorBridge.applyGelu`. The residual affine merge remains a separate kernel with `dst = (dst + bias) * scale + residual`. See `docs/S5API_NORMALIZATION_PORT.md`.


## 10. Temporal stride-2 projection in the sequence dispatcher

The caller trace for `FUN_1800c7ad0` shows one `FUN_1800c5440` call
for each adjacent sequence pair, before the residual-block loop. The four
arguments are the current layer descriptor, current sequence input descriptor,
next sequence output descriptor, and a per-sequence workspace descriptor.

The function's descriptor fields are used as:

- `param_1[0]`: input feature count;
- `param_1[1]`: output feature count;
- `param_1[2]`: temporal kernel width;
- `param_1[3]`: optional-bias enable flag;
- pointer pair at `param_1 + 4`: row-major output-by-flattened-input weights;
- pointer pair at `param_1 + 8`: output-feature bias vector.

The routine derives its output position count as
`floor((N + 2*floor(K/2) - K) / 2) + 1`. For each input feature and
temporal kernel tap, it materializes a stride-2 window with zero padding at
the sequence boundaries. The column matrix is laid out as
`[inputFeature * kernelTap][outputPosition]`, then passed to the existing
linear operator with GEMM dimensions
`M=outputFeatures`, `N=outputPositions`, and
`K=inputFeatures*kernelWidth`. Optional bias is broadcast over all output
positions. The specialized kernel-width-3 branch and the generic branch build
the same logical window matrix.

VEA now contains `S5MegpoidTemporalStridedConvolution`, which implements
that shape/indexing contract and reuses the native row-major GEMM bridge.
Scalar reference tests cover stride, zero-padding, multi-feature flattening,
and bias broadcast. See
`docs/S5API_TEMPORAL_STRIDED_PROJECTION.md` for the detailed callsite and
tensor-layout evidence.

This is an isolated generic operator port. The actual runtime descriptor's
weights have not yet been proven to be any specific named layer in
`VOCALOID7.vtmg`; the new primitive is not yet wired into a full model
forward pass. It does not produce PCM audio by itself.
