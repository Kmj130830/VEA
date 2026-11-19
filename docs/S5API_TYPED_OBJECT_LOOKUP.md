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
32 lowercase hexadecimal characters. The four-word textual order is known;
whether those words are serialized in the same byte order as the canonical
RFC MD5 digest string is not yet established.

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
The `*F16` branch does not inline a fixed IEEE-754 conversion. It invokes an
indirect conversion target stored at offset `+0x208` relative to the first
argument of `FUN_1800656e0`. The call site in `FUN_180060270` passes its
tensor-processing context as that first argument. The target's initializer,
calling convention beyond the observed 16-bit scalar input, and exact numeric
mapping remain unresolved.

A separate function, `FUN_180045130`, builds 0x50-byte records from a
different hashed object-tree entry and moves that array to offset `+0x208`
of the outer object in `Func_47a12a73`. That destination is a different base
address from the `FUN_1800656e0` processing context (the latter is passed as
an embedded context at outer-object offset `+0x898`). Current evidence does
not connect those records to the F16 scalar conversion; the two uses of
offset `+0x208` must not be conflated.

The C++ typed-array parser already recognizes the confirmed `*F32` and
`*F16` prefixes. PR #4 adds an injectable F16 conversion callback to that
parser while preserving standard IEEE-754 binary16 as the fallback. This
provides the extension point without claiming to match the vendor mapping.

## Implementation implications

- Implement field-name construction, salted digest calculation, digest text
  formatting, ordered-tree lookup, typed-value decoding, and outer container
  parsing as separate modules.
- Add exact tests for the 24-byte suffix and MD5 digest bytes before wiring
  model key lookup.
- Do not infer the two tag-3 marker strings from their purpose or appearance.
  Export their raw bytes and inspect the callback path first.
- Do not label this layer as a VTB2 parser. It is the typed model-object
  representation consumed after the outer payload is available.

## Outstanding static questions

1. Are the four MD5 state words rendered in canonical digest byte order or
   word-wise numeric hexadecimal order?
2. Which constructor installs the F16 conversion target at processing-context
   offset `+0x208`, and what numeric mapping does it implement?
3. What is the full grammar and dimensional metadata format accepted by
   `FUN_18000f5f0` / `FUN_18000f6d0`?
4. How is the root object tree populated from the outer model container?
5. Which consumer invokes the recurrent module after these tensors are loaded?
