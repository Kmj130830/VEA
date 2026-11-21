package com.kmj.vea.engine

/**
 * Fully checked, reusable model-side state prepared for an AI Megpoid render.
 *
 * The feature tensor passed into S5MegpoidSequenceTransitionPipeline and the
 * final spectral-to-PCM path are deliberately separate: this object is not
 * itself a completed singing renderer.
 */
data class PreparedMegpoidRuntime(
    val modelPackage: S5VoicebankModelPackage,
    val namedLayerParameters: List<S5MegpoidPackedNamedLayer>,
    val residualBlocks: List<S5MegpoidCompiledRuntimeBlock>,
    val runtimeOptions: S5MegpoidRuntimeOptionsDescriptor,
    val inputSchedules: List<VsmRenderSchedule>
) {
    val scheduledNotes: List<VsmScheduledNote>
        get() = inputSchedules.flatMap { schedule ->
            schedule.chunks.flatMap { chunk -> chunk.noteStarts }
        }

    /** Lexical token view; it does not turn editor phoneme text into timed phones. */
    val lyricTokensByTrackAndNote: Map<String, List<String>>
        get() = scheduledNotes.associate { note ->
            "${note.trackId}:${note.noteId}" to note.lyricTokens
        }

    val scheduledNoteCount: Int
        get() = scheduledNotes.size

    fun prepareAlignedPhoneFeatures(
        phoneEvents: List<VsmPhoneEvent>,
        minimumPhoneSeconds: Float,
        maximumPhoneSeconds: Float
    ): List<VsmAlignedPhoneFeature> =
        VsmAlignedPhoneFeaturePreparer.prepare(
            source = phoneEvents,
            minimumPhoneSeconds = minimumPhoneSeconds,
            maximumPhoneSeconds = maximumPhoneSeconds
        )

    fun createResidualBlockExecutor(
        firstInputWorkspace: S5MegpoidWorkspaceSlot = S5MegpoidWorkspaceSlot.FIRST
    ): S5MegpoidResidualBlockExecutor =
        S5MegpoidResidualBlockExecutor.fromCompiledBlocks(
            blocks = residualBlocks,
            firstInputWorkspace = firstInputWorkspace
        )
}
