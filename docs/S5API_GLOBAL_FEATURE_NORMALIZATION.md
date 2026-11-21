# S5API global feature normalization — FUN_1800c7920

Date: 2026-11-21

## Call-site

In FUN_1800c7ad0, FUN_1800c7920 is called immediately after the first dense
projection FUN_1800c5260. The call receives a normalization descriptor at
the model context's +0x228 region and the projected feature buffer.

## Recovered tensor dimensions and parameters

The function reads:

- N from input descriptor byte offset +0x1c;
- C from the 32-bit field at input descriptor byte offset +0x18;
- input data from the pointer at descriptor offset +0x00;
- epsilon from normalization descriptor byte offset +0x04;
- one per-feature vector through the pointer pair at normalization offset +0x08;
- the other per-feature vector through the pointer pair at normalization offset +0x18.

It reduces exactly C*N input values to a global sum and sum-of-squares,
calculates mean and population variance, clamps negative variance to zero,
adds epsilon and calculates inverse standard deviation.

The subsequent per-feature/per-position loop applies:

mean = sum(input) / (C*N)
variance = max(sumSquares(input) / (C*N) - mean*mean, 0)
inverseStd = 1 / sqrt(variance + epsilon)

scaleFactor[c] = inverseStd * scale[c]
offset[c] = bias[c] - scaleFactor[c] * mean
output[c,n] = scaleFactor[c] * input[c,n] + offset[c]

This is a single global statistics domain across all C features and N
positions, followed by feature-specific affine parameters. It is not
independent per-feature normalization.

## Android implementation

VEA adds S5MegpoidGlobalFeatureNormalization. Its production statistics
backend reuses:

- NativeS5TensorBridge.reduceSumAndSquares;
- NativeS5TensorBridge.computeMeanAndInverseStd.

The Kotlin affine pass preserves the recovered float32 expression order and
uses separate per-feature scale and bias vectors. A scalar statistics backend
supports unit testing without JNI.

Tests cover global mean/variance, per-feature affine application, constant
input with epsilon, and shape/parameter validation.

## Integration boundary

The operator's arithmetic and tensor layout are now represented. Its
descriptor-to-model-tensor provenance has not yet been mapped to one of the
nine VTMG named layers or to another exact model key. It is therefore a
generic operator port and is not yet a full graph connection or PCM renderer.
