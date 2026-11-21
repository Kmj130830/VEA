package com.kmj.vea.engine

/**
 * Raw scalar inputs passed by S5API FUN_1800d5a00 into the output-transform
 * descriptor constructor FUN_1800b8d20 -> FUN_180127d00 -> FUN_180127dc0.
 *
 * The property names intentionally retain their render-state byte offsets.
 * In the recovered AI Megpoid V7 caller:
 * - root +0x0C supplies outputSamples;
 * - root +0x04 supplies both geometry and fftSize;
 * - root +0x10 supplies the float field stored at descriptor +0x0C;
 * - root +0x61C supplies the frame-window core length;
 * - root +0x620 supplies the alternating-bin sign flag (+0x90);
 * - root +0x1F74 supplies descriptor flag +0x91;
 * - root +0x3418 supplies the 32-bit frame-window shape mode;
 * - root +0x1F75 supplies descriptor flag +0x92.
 *
 * The +0x10 float is preserved but not consumed by the currently ported
 * geometry/window/OLA code. It belongs to the native descriptor ABI and
 * must not be silently discarded when reconstructing the full transform.
 */
data class S5MegpoidOutputTransformInputs(
    val outputSamplesAtRoot0C: Int,
    val geometryAndFftSizeAtRoot04: Int,
    val floatParameterAtRoot10: Float,
    val frameWindowCoreLengthAtRoot61C: Int,
    val alternatingBinSignAtRoot620: Boolean,
    val flagAtRoot1F74: Boolean,
    val frameWindowShapeModeAtRoot3418: Int,
    val flagAtRoot1F75: Boolean
) {
    init {
        require(outputSamplesAtRoot0C > 0)
        require(geometryAndFftSizeAtRoot04 > 0)
        require(floatParameterAtRoot10.isFinite())
    }

    /**
     * Reconstructs the descriptor's scalar geometry using the actual
     * AI Megpoid V7 caller relationship: geometry and FFT size share root+0x04.
     */
    fun buildDescriptorGeometry(): S5MegpoidOverlapAddDescriptorGeometry =
        S5MegpoidOverlapAddDescriptorGeometryBuilder.build(
            outputSamples = outputSamplesAtRoot0C,
            geometry = geometryAndFftSizeAtRoot04,
            fftSize = geometryAndFftSizeAtRoot04
        )

    /** Builds the complete frame/output window pair and OLA parameter carrier. */
    fun buildOverlapAddParameters(): S5MegpoidOverlapAddParameters =
        buildDescriptorGeometry().buildOverlapAddParameters(
            coreWindowLength = frameWindowCoreLengthAtRoot61C,
            frameWindowShapeMode = frameWindowShapeModeAtRoot3418,
            alternatingBinSign = alternatingBinSignAtRoot620,
            flagAt91 = flagAtRoot1F74,
            flagAt92 = flagAtRoot1F75
        )

    /** Creates the stateful inverse-FFT and overlap-add executor. */
    fun createSpectralOverlapAdd(): S5MegpoidSpectralOverlapAdd =
        S5MegpoidSpectralOverlapAdd(buildOverlapAddParameters())
}
