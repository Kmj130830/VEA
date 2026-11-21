# S5API temporal stride-2 projection — FUN_1800c5440

Date: 2026-11-21

## Call-site evidence

The forward dispatcher FUN_1800c7ad0 calls FUN_1800c5440 once for each
adjacent sequence pair. At the callsite, it passes:

- the per-layer descriptor at layerBase + sequenceIndex * 0x30;
- the current sequence feature descriptor at featureBase + sequenceIndex * 0x20;
- the next sequence feature descriptor at featureBase + (sequenceIndex + 1) * 0x20;
- a 0x18-byte workspace descriptor at workspaceBase + sequenceIndex * 0x18.

The outer call loop executes sequenceCount - 1 times. This is separate from
the later residual-block loop, which executes the 8 residual blocks for each
sequence index and swaps its two workspace descriptors after each block.

## Recovered descriptor contract

The decompilation of FUN_1800c5440 uses the descriptor fields as follows:

| Descriptor field | Observed use |
|---|---|
| param_1[0] | Input feature count C |
| param_1[1] | Output feature count M |
| param_1[2] | Temporal kernel width K |
| param_1[3] | Optional-bias enable flag |
| pointer pair at param_1 + 4 | Weight matrix descriptor |
| pointer pair at param_1 + 8 | Bias vector descriptor |

The current input descriptor stores its position count N at byte offset
+0x1c. The routine calculates the output position count as:

N_out = floor((N + 2*floor(K/2) - K) / 2) + 1

This is a stride-2 temporal convolution with left padding floor(K/2).
It allocates a workspace of C * K * N_out float values.

## Im2col/window construction

For each input feature c, kernel tap t, and output position p, the source
temporal index is:

s(p,t) = 2*p - floor(K/2) + t

The generated matrix is laid out as [inputFeature * kernelTap][outputPosition]:

X_col[c*K+t,p] =
    X[c,s(p,t)]  when 0 <= s(p,t) < N
    0             otherwise

This zero-fills temporal positions outside the input descriptor. The
specialized K == 3 path is an optimized implementation of the same
window-building stage; the generic path covers the other kernel widths.

## Projection

After the window matrix is prepared, the routine calls FUN_1800c3740 with:

- M = outputFeatures;
- N = N_out;
- K_gemm = inputFeatures * kernelWidth;
- weights as a row-major M x K_gemm matrix;
- the generated K_gemm x N_out window matrix;
- alpha 1 and beta 0.

If param_1[3] is nonzero, it adds the output-feature bias across all
N_out positions.

## Android port

S5MegpoidTemporalStridedConvolution implements this operator contract.
It creates the same row-major window matrix, invokes the existing GEMM
abstraction, and applies optional bias broadcast. Tests cover:

- stride-2 output position counts for odd and even kernel widths;
- centered indexing and zero padding at the sequence boundaries;
- flattening multiple input features before the GEMM;
- optional bias and input/weight shape validation.

The default backend reuses NativeS5TensorBridge.gemmRowMajor; unit tests use
a scalar reference GEMM to make the temporal indexing independently
testable.

## Integration boundary

This identifies the operator and its input/output layouts at the dispatch
callsite. It does not yet prove which specific named VTMG layer or runtime
parameter object supplies this descriptor's weights. The port is therefore a
reusable operator primitive, not a claim that the full VOCALOID feature graph
is now wired. The neighboring scheduler, subsequent transforms, recurrent
or spectral stages, and final PCM rendering remain separate implementation
steps.
