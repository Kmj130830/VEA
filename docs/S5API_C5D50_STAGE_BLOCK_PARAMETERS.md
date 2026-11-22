# AI Megpoid c5d50 stage/block parameter tensors

Date: 2026-11-22

## Renderer configuration

The VTB2 object `5be3f5d6495f68286523e230e2d0d9a4` contains wrapper
`154c806ba4d274fad9c7484acdc1779e`, which holds renderer scalar config
`a4ba655a061e6151f80574e6088492a0` and stage/block tensor config
`43726e4ce752ea6cc300dc2fca3e8372`.

The scalar config declares `hopSize=320`, `sampleRate=32000`,
`stageCount=4`, and `blocksPerStage=2`. These values match
`FUN_1800ced10`'s assignments to `param_1[3..6]` and
`FUN_1800c7ad0`'s stage/block loops. The tensor object therefore contains
eight (stage, block) parameter sets.

## Descriptor-builder argument mapping

`FUN_1800ced10` resolves eight per-(stage, block) property keys and calls
`FUN_1800d4bf0` with these arguments:

| d4bf0 argument | Key helper | Parameter field | VTB2 dimensions | Values |
|---|---|---|---:|---:|
| param2 | `FUN_1800ea000` | depthwise kernel | `[32,1,7,7]` | 1,568 |
| param3 | `FUN_1800ff330` | block vector A | `[32]` | 32 |
| param4 | `FUN_1800f8220` | block vector B | `[32]` | 32 |
| param5 | `FUN_18010d550` | expansion weights | `[64,32,1,1]` | 2,048 |
| param6 | `FUN_180106440` | expansion bias | `[64]` | 64 |
| param7 | `FUN_18011b770` | projection weights | `[32,64,1,1]` | 2,048 |
| param8 | `FUN_180114660` | projection bias | `[32]` | 32 |
| param9 | `FUN_1800f1110` | block vector C | `[1,32,1,1]` | 32 |

`FUN_1800d4a90` places param3/param4 in two vector descriptor members.
`FUN_1800d4610` copies matrix/bias pairs for expansion and projection.
The depthwise weight is copied into its descriptor, and the last vector
is stored through `FUN_1800c0320`.

The tensor object path is indexed by the stage/block key and each object
contains a dimension array plus an F32 tensor leaf. The eight property-key
tables select entries with `stage*16 + block`, covering stage 0..3 and
block 0..1.

## Runtime loader

`S5Vtb2C5d50StageBlockParameterLoader` validates the four renderer scalars,
all eight stage/block pairs, every dimension array, each F32 payload length,
and finite tensor values. It returns source arrays in the argument order
expected by the recovered descriptor builder.

The loader supplies source data for c5d50 descriptor construction. It does
not yet execute the complete four-stage neural forward graph; Yamaha G2P,
the remaining VTMG schedule, spectral synthesis and waveform output remain
incomplete.
