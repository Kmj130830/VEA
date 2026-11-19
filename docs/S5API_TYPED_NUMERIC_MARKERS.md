# S5API typed numeric string encodings

Static data and decompilation identify three four-byte ASCII prefixes used by S5API's typed-array conversion code:

| RVA | Bytes | Marker |
|---:|---|---|
| 0x19b770 | 2a 46 33 32 | `*F32` |
| 0x19b778 | 2a 46 31 36 | `*F16` |
| 0x19b780 | 2a 53 33 32 | `*S32` |

Evidence:
- `FUN_1800656e0` compares a string node's first four bytes to `*F32` and `*F16`.
- On the `*F16` branch, it passes the substring after the four-byte prefix to `FUN_18000f5f0`, then reads 16-bit elements and calls a virtual conversion routine at object offset +0x208 before storing float32 output.
- `FUN_18001e360` contains a parallel `*F32` / `*F16` handling path for another tensor access route.
- Another model-array conversion path compares the prefix with `*S32`, calls the same string parser, and transfers the parsed values as 32-bit integers.

These markers indicate typed numeric text representations within the internal model-object tree. They do not mean the outer VTBR/VPIT/HVNV/VTMG files are plain text; the user-supplied model payloads remain high-entropy binary data.

The next analysis pass targets `FUN_18000f5f0` (the shared text-to-numeric parser), `FUN_180054b50` (16-bit conversion object setup), the virtual converter call site, and the actual scalar DSP fallbacks. No numeric string parser has been reimplemented until its grammar and bounds behavior are known.
