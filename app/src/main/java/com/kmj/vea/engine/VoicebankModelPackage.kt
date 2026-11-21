package com.kmj.vea.engine

/**
 * Common identity contract for model packages from different synthesis
 * families. Vendor-specific model formats stay in their own package types.
 */
interface VoicebankModelPackage {
    val voicebankId: String
    val engineFamily: String
}

/** A loader for one or more installed voicebank package formats. */
interface VoicebankModelPackageLoader {
    fun supports(voicebank: VoicebankDescriptor): Boolean
    fun load(voicebank: VoicebankDescriptor): VoicebankModelPackage
}

/**
 * Dispatches voicebank package loading without coupling the editor to a
 * single singer or vendor. New loaders can be added without changing the
 * generic SynthesisEngine interface.
 */
class VoicebankModelPackageRegistry(
    private val loaders: List<VoicebankModelPackageLoader>
) {
    init {
        require(loaders.isNotEmpty()) { "At least one voicebank model loader is required." }
    }

    fun load(voicebank: VoicebankDescriptor): VoicebankModelPackage {
        val matches = loaders.filter { it.supports(voicebank) }
        require(matches.size == 1) {
            when (matches.size) {
                0 -> "No model-package loader is registered for voicebank '${voicebank.id}'."
                else -> "More than one model-package loader accepts voicebank '${voicebank.id}'."
            }
        }

        val modelPackage = matches.single().load(voicebank)
        require(modelPackage.voicebankId == voicebank.id) {
            "The package loader returned voicebank '${modelPackage.voicebankId}' for '${voicebank.id}'."
        }
        require(modelPackage.engineFamily == voicebank.engineFamily) {
            "The package engine family '${modelPackage.engineFamily}' does not match '${voicebank.engineFamily}'."
        }
        return modelPackage
    }

    companion object {
        /** Initial built-in provider; other voicebanks can be registered alongside it later. */
        fun withMegpoidV7(): VoicebankModelPackageRegistry =
            VoicebankModelPackageRegistry(listOf(S5MegpoidV7ModelPackageLoader()))
    }
}
