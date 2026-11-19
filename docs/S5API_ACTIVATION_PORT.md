# S5API ACT operator port

## Reference evidence

FUN_18012d460 is selected by MRW_ACT_SIMD=0 and is the baseline ACT operator. It accepts two contiguous float planes, clamps both to [-8, 8], updates the first plane, and leaves the second as the clamped gate plane.

FUN_18003be50 is the custom approximate exponential used by this ACT function. Disassembly shows the following float32 sequence:

1. multiply input by 1.4426950216293335;
2. clamp the log2-domain value at -126, with a +1 fractional correction for negative inputs (truncation toward zero);
3. compute 27.728023529052734 / (4.842525482177734 - fraction);
4. add 121.27405548095703 and subtract fraction * 1.4901291131973267;
5. multiply by 8388608, truncate to int32, then reinterpret the integer bits as float32.

This is an approximation and is not interchangeable with std::exp/expf. The port uses volatile float intermediates to preserve scalar binary32 rounding and avoid reassociation.

## Activation formulas

- mode 0: sigmoid(gate) * tanh(value)
- nonzero mode: tanh(gate) * sigmoid(value)

where sigmoid(x)=1/(1+S5ApproxExp(-x)) and tanh(x)=2/(1+S5ApproxExp(-2*x))-1.

## Android port

Added vea_s5_activation.cpp with the approximate exponential and the gated activation. JNI and Kotlin expose NativeS5TensorBridge.applyGatedActivation().

Tests assert a known binary32 result for the (1,0) gate/value pair in both modes and check clamp behavior. This is the first neural arithmetic kernel ported from S5API; it is not yet connected to a recovered recurrent layer or acoustic graph.