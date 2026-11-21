# VOCALOID7 VTMG shared base-graph tensor layout

Date: 2026-11-21

## Scope

This note records the tensor dimensions and matrix-bank storage recovered for
the nine shared layers loaded from \`VOCALOID7.vtmg\`. It describes parameter
lookup and packing only. It does **not** claim that the nine-layer forward
graph, voicebank conditioning, phoneme generation, or acoustic waveform
decoder is implemented.

## Evidence and dimension recovery

The S5API function \`FUN_180068190\` passes the named layer objects to
\`FUN_180064670\`. The latter receives:

- \`param_5\`: logical axis0 count;
- \`param_4\`: logical axis1 count;
- \`param_6\`: matrix/bank count.

The call-site expressions were resolved from the caller's register and stack
arguments. Matching those dimensions against the actual typed tensor lengths
in the shared VTMG object resolves the two common dimensions as 32, 82 and 26.
The convolution/conditioning layers use axis0 count 64.

The bias copy path consumes \`param_5\` values, so each layer bias has
\`axis0Count\` elements.


The VTMG object stores the 18 member keys as conventional lowercase MD5 digest
bytes for `layerName + ".weight"` / `layerName + ".bias"` followed by the
recovered 24-byte salt. This byte-order representation is distinct from the
separate S5API lookup helper that formats four MD5 state words. VEA therefore
uses `S5ModelTensorKey.deriveDigestHex()` specifically for VTMG layer fields
and keeps the other formatter unchanged for its existing callers.

| Layer | axis0 | axis1 | banks | Weight values | Bias values |
|---|---:|---:|---:|---:|---:|
| pre1 | 32 | 82 | 1 | 2,624 | 32 |
| pre2 | 32 | 32 | 1 | 1,024 | 32 |
| conv1 | 64 | 32 | 3 | 6,144 | 64 |
| cond1 | 64 | 26 | 1 | 1,664 | 64 |
| conv2 | 64 | 32 | 3 | 6,144 | 64 |
| cond2 | 64 | 26 | 1 | 1,664 | 64 |
| conv3 | 64 | 32 | 3 | 6,144 | 64 |
| cond3 | 64 | 26 | 1 | 1,664 | 64 |
| linear | 3 | 32 | 1 | 96 | 3 |
| **Total** | | | | **27,168** | **451** |

The total raw parameter count is 27,619 float32 values.

Axis names are intentionally neutral. The descriptor layout and dimensions
are grounded in the loader call sites and payload lengths; this table does
not assign every axis to a higher-level acoustic feature semantic.

## S5API matrix-bank storage

The recovered \`FUN_180064670\` source traversal is:

\`\`\`text
source[(axis0 * axis1Count + axis1) * matrixCount + matrix]
\`\`\`

For each matrix index, the destination is laid out as
\`[axis1Count][paddedAxis0Count]\`, where:

\`\`\`text
paddedAxis0Count = align_up(axis0Count, 8)
destination[(matrix * axis1Count + axis1) * paddedAxis0Count + axis0]
\`\`\`

The destination allocation is 32-byte aligned. Columns between
\`axis0Count\` and \`paddedAxis0Count - 1\` are zero-filled. The total packed
weight count is 27,328 floats: the linear layer's axis0 width of 3 pads to 8,
adding 160 zero values. Including the 515 bias values, the packed graph
contains 27,779 float values.

## Android implementation

- \`S5VtmgGraphTensorCatalog\` stores the recovered dimensions and bank counts.
- \`S5VtmgBaseGraphLoader\` decodes and validates all nine raw weight/bias pairs.
- \`S5VtmgBaseGraphPacker\` converts each raw weight tensor through
  \`NativeS5TensorBridge.packMatrixBank\`, validates packed lengths and finite
  values, and checks that padded columns are zero.
- \`VocaloidAiEngineAdapter.prepareSharedVtmgBaseGraph()\` exposes the packed
  form to the render-preparation path.
- Unit tests check all dimensions, bank interleave, packed counts and padding.

## Explicit remaining work

The current scalar kernels and residual/depthwise/MLP primitive are individual
operator ports, not the complete shared VTMG execution graph. The actual model
forward schedule, full convolution/conditioning calls, voicebank-specific
VTB2 parameter wiring, Yamaha G2P, stateful spectral processing, and
waveform decoder remain incomplete. The app must continue to report singing
synthesis as unavailable and must not emit a generated-vocal success state
until a real Megpoid request produces PCM/WAV on Android and passes reference
validation.
