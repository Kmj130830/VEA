# S5API recurrent parameter layout

Date: 2026-11-19

## Static evidence

The decompiled S5API model constructor FUN_18005ebe0 (RVA 0x5ebe0) initializes a recurrent module record from scalar configuration fields and loads four named arrays for each layer/direction:

- .weight_ih_l{layer}
- .weight_hh_l{layer}
- .bias_ih_l{layer}
- .bias_hh_l{layer}

The reverse-direction path appends _reverse to the same parameter names.

Within the loader:

- param_5 * 3 is used as the gate-vector width.
- The input-projection weight allocation is input_features * (3 * hidden_size).
- The hidden/recurrent weight allocation is hidden_size * (3 * hidden_size).
- When the bias flag is enabled, each bias vector has 3 * hidden_size values.
- The first layer uses the configured external input width.
- Subsequent layers use hidden_size * direction_count as their input width.
- The layer stride is 0xC0; each direction sub-record is 0x60 bytes. Four 24-byte vector descriptors occupy offsets +0x00, +0x18, +0x30, and +0x48 within each direction record.

The 0x60-byte per-direction layout follows the decompiled addressing: layer_index * 0xC0 plus direction_index * 0x60. The four field offsets correspond to the four separately managed C++ vector triples.

## Model-constructor call-site confirmation

The call-site instruction window around `FUN_18005ebe0` at RVA `0x5ebe0` now confirms the configuration field mapping:

- `R9D = [RBX + 0x68]` becomes parameter 4, the first-layer input width.
- `ECX = [RBX + 0x6c]` is copied to stack argument 5, the hidden width.
- Stack argument 6 is the constant `1`: one recurrent layer.
- Stack argument 7 is the constant `1`: bias arrays enabled.
- Stack argument 8 is the constant `1`: bidirectional mode enabled.

The same constructor, `FUN_180056f40`, reads a third scalar at `[RBX + 0x70]`, but it passes that value to the separate `FUN_18005df50` and `FUN_180065510` matrix-preparation calls. It is not the hidden width for this RNN call.

The relevant x64 sequence immediately before the call is:

```asm
mov ecx, dword ptr [rbx + 0x6c]
mov dword ptr [rsp + 0x20], ecx
mov dword ptr [rsp + 0x28], 1
mov dword ptr [rsp + 0x30], 1
mov byte ptr  [rsp + 0x38], 1
mov r9d, dword ptr [rbx + 0x68]
lea r8, [rbp + 0x50]
mov rdx, r14
lea rcx, [rbx + 0x10]
call FUN_18005ebe0
```

This establishes the parameter ordering and the single-layer / bias-enabled / bidirectional settings in this constructor. It does not reveal the literal runtime values stored in the two dimension fields, nor the recurrent forward equations.

## Android implementation

VEA adds vea_s5_rnn_direction_layout and Kotlin NativeS5TensorBridge.rnnDirectionLayout. They compute and validate the flattened element counts for one requested layer/direction, with overflow checks.

Example with input size 80, hidden size 128, three layers, bias enabled, and bidirectional mode:

| Layer | Input features | Gate width | weight_ih | weight_hh | Each bias |
|---:|---:|---:|---:|---:|---:|
| 0 | 80 | 384 | 30,720 | 49,152 | 384 |
| 1–2 | 256 | 384 | 98,304 | 49,152 | 384 |

Each layer has forward and reverse parameter sets in bidirectional mode.

## Recurrent property-name construction and model cross-check

The constructor caller FUN_180056f40 at 0x180057459 supplies the literal three-character base string rnn to FUN_18005ebe0. The recurrent loader prepares suffix fragments including .weight_ih_l, .weight_hh_l, .bias_ih_l and .bias_hh_l. It calls FUN_180024a90 with that base, one suffix fragment and the layer index; FUN_180083c50 formats the layer index as ordinary decimal text, and the reverse-direction path appends _reverse. The expected first-layer name is therefore rnn.weight_ih_l0 (and corresponding hh/bias/reverse forms).

A recursive search of the five decoded supplied model JSON trees did not find the salted hashes for the simple candidates rnn.weight_ih_l0, rnn.weight_hh_l0, rnn.bias_ih_l0, rnn.bias_hh_l0 or their _reverse variants. Short configuration-key candidates such as pre1, pre2, conv1, cond1, conv2, cond2, conv3, cond3 and linear also did not match. The recurrent setup is conditional: FUN_180056d60 calls FUN_180056f40 only when its third flag parameter is nonzero. The observed caller Func_47a12a73 passes that flag from the model-derived byte at context offset +0xf11, populated by FUN_18004e1e0. The flag value for AI Megpoid has not yet been matched to the decoded model tree. Therefore the generic recurrent loader is statically present, but it may be disabled for the supplied model; the direct parameter-key no-match is consistent with that possibility, though it is not proof.

## Deliberate limits

Three gate blocks are consistent with a GRU-style parameterization, but the recovered loader alone does not prove gate order, reset/update/new equations, activation placement, bias addition order, hidden-state layout, or sequence direction math. The Android project does not label this as a completed GRU implementation, and no Megpoid V7 waveform is generated by this change.

The model-object tree parser and recurrent forward kernel remain separate required milestones.
