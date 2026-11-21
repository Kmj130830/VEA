# S5API shared VTMG base-graph context

Date: 2026-11-21

## Evidence scope

This note correlates the supplied VSM.dll and S5API.dll through static Ghidra
analysis. Neither PE module was executed. The observations describe the
resolved export table and function-call/dataflow visible in these builds;
they are not a public Yamaha ABI specification.

## VSM-to-S5API module boundary

`VSM.dll` constructs the S5API module path below the VOCALOID7 module area
and loads `S5API.dll` with `LoadLibraryExW`. Its function-table initializer
`FUN_180086750` resolves `Func_4cd84bb7` with `GetProcAddress`, along with
the additional exports used by the VSM host. This establishes that
`Func_4cd84bb7` is part of the active VSM/S5API integration surface.

The VSM resolver list does **not** request `Func_4e22ccf5`. That export is
present in the S5API PE export table and has a substantial constructor body,
but no VSM `GetProcAddress` entry for it was found in the inspected resolver.
It must therefore remain a separate, unassigned S5API entry point; it is not
used as evidence for the active AI Megpoid graph.

## Func_4cd84bb7 and the nine named VTMG layers

`Func_4cd84bb7` at S5API address `0x18008d470`:

1. Allocates a 0x200-byte owner/context record.
2. Allocates a 0x40000-byte aligned scratch arena with 32-byte alignment.
3. Initializes four linked-list heads in the owner record. Each list head
   uses a 0x48-byte node/header allocation and a self-linked empty state.
4. Calls `FUN_180068190(owner + 0x10, modelObject, selector, owner)`.

`FUN_180068190` then reads the nine literal named layer entries from the
selected model/configuration object:

- `pre1`, `pre2`
- `conv1` / `cond1`
- `conv2` / `cond2`
- `conv3` / `cond3`
- `linear`

For each named layer, the loader resolves `.weight` and `.bias` members and
passes typed tensor payloads and dimension arguments to `FUN_180064670`.
The latter builds `NVGen::BlockMatrix16` / `BlockMatrix32` storage. This is
strong evidence that `Func_4cd84bb7` prepares the shared named VTMG layer
parameter context, rather than merely scanning or listing tensor strings.

The current Android `S5VtmgBaseGraphLoader` and
`S5MegpoidNamedLayerTensorIndex` decode and pack the same nine named
weight/bias pairs. The exact relationship between every resulting BlockMatrix
and each active forward operator callsite is still being traced; do not infer
the execution topology from the layer names alone.

## Separate Func_4e22ccf5 constructor

`Func_4e22ccf5` at `0x18008d650` allocates a 0x2e8-byte object and a
0x2000000-byte aligned arena, also with 32-byte alignment. It parses an
object from its input, selects several hashed child properties (including
two numeric fields copied to float slots), transfers another nested object,
and calls `FUN_180060270` to construct a subobject.

These observations establish a separate configuration/parameter object
builder, but the semantic names of its hashed properties and the exact
runtime consumer of the result are not established by the inspected caller
graph. In particular, it is not safe to label it the Megpoid neural graph or
to merge its fields into `S5VtmgBaseGraphWeights` without further evidence.

## Android implementation boundary

- The nine named VTMG tensors have a validated Android parser/index/packing
  path and are owned by the voicebank package.
- The 4x2 residual-block and Dense/normalization/Temporal/Banked preparation
  paths are separate VTB2-derived components.
- No verified call/dataflow currently maps every named VTMG layer to a
  concrete active operator in the Megpoid `FUN_1800c7ad0` forward pass.
- `Func_4e22ccf5` remains unported and is intentionally not consumed by the
  Megpoid runtime package.

No complete VTMG forward pass or successful singing render is claimed.
