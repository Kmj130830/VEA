# V7 outer container header finding

Static inspection of the supplied model assets shows the same fixed 136-byte prefix in all five tested files:

| Member | Magic | File bytes | little-endian +4 | file size − 136 | +4 matches | +8 word |
|---|---|---:|---:|---:|---|---:|
| AI_Megpoid.vtb2 | VTBR | 23,541,731 | 23,541,595 | 23,541,595 | yes | 16 |
| AI_Megpoid.vpit | VPIT | 16,754 | 16,618 | 16,618 | yes | 16 |
| COMMON7.vpit | VPIT | 3,925 | 3,789 | 3,789 | yes | 6 |
| VOCALOID7.vhnv | VHNV | 5,485,182 | 5,485,046 | 5,485,046 | yes | 3 |
| VOCALOID7.vtmg | VTMG | 148,942 | 148,806 | 148,806 | yes | 3 |

## Recovered prefix layout

| Offset | Size | Observation |
|---:|---:|---|
| 0x00 | 4 | ASCII magic: VTBR, VPIT, VHNV, or VTMG |
| 0x04 | 4 | little-endian payload byte count; equals total file size minus 136 |
| 0x08 | 4 | opaque word; observed values are 3, 6, and 16 |
| 0x0c | 124 | opaque high-entropy metadata region |

The final 124 bytes have not been decoded or interpreted. No encryption, signature, checksum, or key semantics are assumed. The +8 word is called format_word without assuming what it means.

## Native implementation

The new parser:
- recognizes only the four observed magic values;
- decodes the payload count as little-endian;
- requires payload_size == total_file_size - 136;
- preserves the 124 opaque bytes verbatim;
- rejects truncated, oversized, wrong-magic, and inconsistent-length headers.

The parser does not read files itself and does not attempt to transform the opaque region. The application must pass the first 136 bytes and actual file length after ordinary file I/O.

Unit tests cover all four magic values and invalid length/magic cases. This is a container-boundary parser only; it does not decode the remaining proprietary payload.
