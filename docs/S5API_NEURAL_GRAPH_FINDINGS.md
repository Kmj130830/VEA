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
