# VDM voicebank database investigation

Static Ghidra decompilation of the supplied VDM.dll is run by the VEA VDM voicebank and model-loader analysis workflow. The report traces the exported database manager, voicebank lookup/accessors, language and voice-type metadata, and a bounded direct-call closure.

The aim is to map the V7 bank package metadata to runtime database/model paths, not to bypass license or activation logic. The supplied model files remain opaque until the exact loader/container path is verified. The Android implementation must remain an independently authored ARM64 reader and must be tested against the authorized reference implementation.

Current export-name inventory is in docs/VDM_VOICEBANK_EXPORTS.md. The workflow's generated report is the next evidence source for path resolution, object ownership, model component lookup, and metadata layout.
