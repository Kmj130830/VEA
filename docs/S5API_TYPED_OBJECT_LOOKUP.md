# S5API typed model-object lookup and tensor decoding

Date: 2026-11-20

## Scope

This note records static observations from the Ghidra 12.2 report
`S5API_MODEL_OBJECT_TREE.md`. The Windows DLL was not executed. Function names
are Ghidra labels, not vendor symbols. These observations describe the model
object layer; they do not define the outer VTB2/container format.

## Lookup pipeline

The recurrent model constructor `FUN_18005ebe0` does not pass parameter names
directly to the object tree. It builds parameter-name fragments such as
`.weight_hh_l` and `.bias_ih_l`, uses `FUN_180024a90` to form a complete
name from the model/module context and layer information, then calls
`FUN_18001b1f0` before looking up the result through `FUN_18000b100`.

The visible operations in `FUN_18001b1f0` are:

1. Copy the supplied key bytes.
2. Append a fixed 24-byte suffix:
   `69 23 33 65 2e 63 5a 54 25 35 6b 39 6a 32 64 2c 32 5a 72 53 2f 2b 2f 32`
   (ASCII: `i#3e.cZT%5k9j2d,2ZrS/+/2`).
3. Pass the concatenated bytes to `FUN_180010260`. Its state initialization
   and padding/length handling match an MD5 computation.
4. Pass the result to `FUN_180083610` through a stream helper to construct the
   string used by the lookup path.

`FUN_18001acc0` formats the four 32-bit digest words as four groups of
eight hexadecimal digits, zero-padded with `setw(8)`. It sets the stream's
hexadecimal basefield and does not enable uppercase, so the emitted key is
32 lowercase hexadecimal characters. The values are formatted word-wise;
this is not the canonical RFC MD5 byte-string rendering.

`FUN_18000b100` traverses an ordered tree and compares the stored key string
against the requested key using byte comparison plus a length tie-break.
The key string is stored in the node's string-like field beginning at
`+0x20`. `FUN_180026f60` is the associated lexicographic comparison helper.

## Recovered digest text layout

The output stream setup in `FUN_18001b1f0` selects hexadecimal formatting,
and `FUN_18001acc0` iterates over four 32-bit values, applying zero fill and
width 8 to each. The resulting lookup key is 32 lowercase hexadecimal
characters. This is a word-wise representation of the four MD5 state words;
it is not the usual canonical MD5 digest string, which serializes each word
as four little-endian bytes.

VEA adds `vea_s5_hash_parameter_key` as an isolated helper that accepts an
explicit byte length, appends the recovered 24-byte suffix, computes the
standard MD5 state, and emits each state word as eight lowercase hex digits.
It is unit-tested with a fixed salted test vector. It is not yet wired into
the full model parser; a binary-model cross-check remains advisable before
using it to resolve production voicebank parameters.

## Typed object node

The observed typed-value access convention is:

| Offset | Observed role |
|---:|---|
| `+0x40` | runtime type tag |
| `+0x48` | type-specific value/payload |

The functions inspected use these tags:

| Tag | Observed meaning |
|---:|---|
| 2 | scalar double |
| 3 | string-like payload |
| 4 | sequence of tagged scalar values |
| 5 | nested object |

The tag meanings are local observations from the accessed call paths. They
should not be treated as a complete schema for every S5API object.

## Numeric tensor conversion

`FUN_180006c80` handles at least two payload cases:

- Tag 4: reads 16-byte scalar records, requires tag 2 for each entry, reads
  each double at record offset `+8`, and converts it to float32.
- Tag 3: extracts the string payload after its four-byte prefix and sends it
  through `FUN_18000f5f0`, which produces a numeric float buffer.

`FUN_1800656e0` writes a two-dimensional float32 tensor buffer. For tag 4 it
reads the tagged-double sequence row by row. Its destination row stride is
rounded up to a multiple of eight elements. For tag 3 it checks for one of two
four-byte marker values before decoding the payload. One accepted path
calls an indirect scalar-conversion target through the processing context
passed as `FUN_1800656e0`'s first argument, at that context's offset `+0x208`.

`FUN_180064670` is a higher-level matrix/tensor loader. Its allocations and
loops use the requested row/feature dimensions and construct float buffers
from the typed object values.

## Confirmed inline tensor payload markers

The raw constants consumed by `FUN_1800656e0` are:

| Address | Bytes | ASCII | Observed branch |
|---|---|---|---|
| `DAT_18019b770` | `2a 46 33 32` | `*F32` | decode following Base64 text into float32 values |
| `DAT_18019b778` | `2a 46 31 36` | `*F16` | decode following Base64 text into 16-bit values, then call the processing-context converter at offset `+0x208` for each scalar |

For either marker, the four-byte prefix is removed before calling
`FUN_18000f5f0`; that function decodes the remaining Base64 text.

### F16 callback registration

The `+0x208` callback is installed by CPU dispatch, not by the separate
model-configuration record loader:

1. `FUN_180060270` receives its processing context as `int* param_1` and
   calls `FUN_18004f2d0(param_1 + 0x46)`. Pointer arithmetic on `int*`
   places the CPU function table at byte offset `0x46 * 4 = 0x118`.
2. `FUN_18004f2d0` receives that table as `undefined8* param_1` and stores
   its selected scalar-conversion callback in `param_1[0x1e]`, byte offset
   `0x1e * 8 = 0xf0`.
3. The combined address is `0x118 + 0xf0 = 0x208`, exactly the indirect-call
   field used by the `*F16` readers in `FUN_1800656e0` and
   `FUN_18005e120`.

The CPU dispatch selects one of two observed implementations:

| CPU path | Installed target | Observed conversion |
|---|---|---|
| AVX2 + FMA + F16C feature branch | `FUN_1800a58b0` | Broadcasts the input 16-bit word and executes `VCVTPH2PS YMM0, XMM0`; the scalar caller consumes the low float lane |
| Baseline fallback | `LAB_18003cb70` | Software bit conversion using sign, exponent and mantissa fields |

The accelerated branch is AVX2/FMA/F16C, not AVX-512: the dispatch checks CPUID leaf-7 EBX bit 5 (AVX2) and leaf-1 ECX bits 12 (FMA) and 29 (F16C); the conversion instruction writes a YMM register.

### Scalar fallback semantics

`LAB_18003cb70` implements the following mapping:

- Sign: moves half bit 15 to float bit 31.
- Zero exponent: converts the 10-bit mantissa to float and multiplies by
  `2^-24`, yielding signed zero or the half subnormal value.
- Nonzero exponent: applies the ordinary half-to-float exponent bias
  adjustment and shifts the mantissa into float32 position.

The scalar routine does not contain a special case for exponent bits
`0x1f`. Consequently, half encodings for infinity and NaN are mapped through
the ordinary exponent path (to finite float bit patterns), unlike the F16C
instruction, which performs IEEE binary16-to-binary32 conversion. For all
finite half values with exponent 0 through 30, the two observed paths agree.
This is an observed backend difference; it is not evidence that production
voicebank tensors contain infinity or NaN values.

VEA's current `HalfToFloat` uses standard IEEE-754 handling. It matches the
F16C path and agrees with the scalar fallback for finite values, but not for
the scalar fallback's exponent-`0x1f` edge case. PR #4's injectable callback
remains useful when a caller needs to select or test an exact backend policy.

A separate function, `FUN_180045130`, builds 0x50-byte records from a
different hashed object-tree entry and moves that array to offset `+0x208`
of the outer object in `Func_47a12a73`. That is a distinct object base and
is not the CPU callback slot described above; the two uses of `+0x208`
must not be conflated.

## Real model tree cross-check

A recursive inventory of the supplied V7 model members shows that AI_Megpoid.vtb2 contains 258 object nodes, 108 arrays, 299 strings, 626 numeric values, 859 32-character hex keys and 282 *F32 strings. It contains no *F16 strings in this inventory. The two supplied VPIT files, VHNV and VTMG were also parsed as JSON object trees.

The current generic RNN constructor path passes the literal base string rnn and suffix fragments such as .weight_ih_l and .weight_hh_l to FUN_180024a90. However, a recursive search of all five decoded model trees found no match for the direct salted-key candidates rnn.weight_ih_l0, rnn.weight_hh_l0, rnn.bias_ih_l0, rnn.bias_hh_l0 or their reverse variants. This does not invalidate the recovered hash function or generic RNN parameter layout, but it means the generic loader's expected simple names have not been cross-validated against AI Megpoid's actual parameter namespace. A separate model graph or additional namespace construction may be involved.

## Implementation implications

- Implement field-name construction, salted digest calculation, digest text
  formatting, ordered-tree lookup, typed-value decoding, and outer container
  parsing as separate modules.
- Keep key hashing, ordered-tree lookup, typed-value decoding, and outer
  container parsing as separate modules. The salted-key helper is unit-tested
  for both single- and multi-block input, but still needs a known-key
  cross-check against a real model file.
- Keep the two F16 CPU paths explicit in tests: IEEE/F16C behavior and the
  finite-only-equivalent scalar fallback. Do not silently assume they agree
  for exponent-`0x1f` inputs.
- Do not label this layer as a VTB2 parser. It is the typed model-object
  representation consumed after the outer payload is available.

## Outstanding static questions

1. Can the salted key implementation be verified against at least one
   known parameter key extracted from a real voicebank model?
2. What is the full grammar and dimensional metadata format accepted by
   `FUN_18000f5f0` / `FUN_18000f6d0`?
3. How is the root object tree populated from the outer model container?
4. Which consumer invokes the recurrent module after these tensors are loaded?
