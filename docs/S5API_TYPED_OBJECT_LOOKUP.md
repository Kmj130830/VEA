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

The exact textual representation emitted by `FUN_180083610` is not yet
established. VEA must not assume lowercase hex, uppercase hex, Base64, or raw
binary key storage until that formatter is inspected.

`FUN_18000b100` traverses an ordered tree and compares the stored key string
against the requested key using byte comparison plus a length tie-break.
The key string is stored in the node's string-like field beginning at
`+0x20`. `FUN_180026f60` is the associated lexicographic comparison helper.

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
four-byte marker values before decoding the payload; the marker bytes and
their semantics remain unresolved. One accepted path also calls a conversion
callback through the model context at offset `+0x208`.

`FUN_180064670` is a higher-level matrix/tensor loader. Its allocations and
loops use the requested row/feature dimensions and construct float buffers
from the typed object values.

## Confirmed inline tensor payload markers

The raw constants consumed by `FUN_1800656e0` are:

| Address | Bytes | ASCII | Observed branch |
|---|---|---|---|
| `DAT_18019b770` | `2a 46 33 32` | `*F32` | decode following Base64 text into float32 values |
| `DAT_18019b778` | `2a 46 31 36` | `*F16` | decode following Base64 text into 16-bit values, then call the model-context converter at offset `+0x208` for each scalar |

For either marker, the four-byte prefix is removed before calling
`FUN_18000f5f0`; that function decodes the remaining Base64 text.
The `*F16` branch does not inline a fixed IEEE-754 conversion. It invokes a
per-model callback loaded from the model context at `+0x208`. VEA's existing
half-float conversion therefore remains a candidate fallback, not yet a
verified match for every S5API backend.

The C++ typed-array parser already recognizes `*F32` and `*F16` payload
prefixes, but it currently decodes the values directly. Integrating the
per-model `+0x208` callback semantics is a separate follow-up.

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

1. What exact text encoding does `FUN_180083610` use for the 16-byte digest?
2. What are the two four-byte tag-3 marker values at `DAT_18019b770` and
   `DAT_18019b778`?
3. What is the full grammar and dimensional metadata format accepted by
   `FUN_18000f5f0` / `FUN_18000f6d0`?
4. How is the root object tree populated from the outer model container?
5. Which consumer invokes the recurrent module after these tensors are loaded?
