package com.kmj.vea.engine

/**
 * Result of turning an already scheduled note stream into timed phone events.
 *
 * The phone labels are taken only from each note's explicit [VsmScheduledNote.phoneme]
 * text. This stage does not run G2P or infer phonemes from lyrics.
 */
data class VsmNotePhoneAlignmentResult(
    val events: List<VsmPhoneEvent>,
    val alignedNoteCount: Int,
    val explicitPhoneCount: Int,
    val silenceEventCount: Int,
    /** Labels that the recovered 14-category map routes to its native fallback category. */
    val fallbackPhoneLabels: List<String>
)

/**
 * Converts explicitly supplied note phoneme strings into sample-aligned phone events.
 *
 * Timing is deterministic and sample-exact:
 * - whitespace separates phone labels;
 * - standalone "." and "|" tokens are treated as syllable/word separators, not phones;
 * - each note duration is divided evenly across its phones;
 * - remainder samples are assigned from the first phone onward;
 * - gaps between notes become explicit "sil" events.
 *
 * Equal subdivision is an initial alignment policy, not Yamaha's G2P timing model.
 * The opaque source uint32 is set to zero because its native meaning is not yet
 * established; the current default feature builder does not consume it.
 */
object VsmNotePhoneAligner {
    private val phoneSeparators = setOf(".", "|")
    private const val SILENCE_LABEL = "sil"

    fun alignExplicitPhonemes(
        schedule: VsmRenderSchedule
    ): VsmNotePhoneAlignmentResult {
        require(schedule.sampleRate in 8_000..192_000) {
            "The scheduled sample rate is outside the supported range."
        }
        require(schedule.totalSampleCount >= 0L)

        val notes = schedule.chunks
            .flatMap { chunk -> chunk.noteStarts }
            .sortedWith(
                compareBy<VsmScheduledNote> { it.absoluteStartSample }
                    .thenBy { it.noteId }
            )

        if (schedule.totalSampleCount == 0L) {
            require(notes.isEmpty()) {
                "An empty render schedule cannot contain note events."
            }
            return VsmNotePhoneAlignmentResult(
                events = emptyList(),
                alignedNoteCount = 0,
                explicitPhoneCount = 0,
                silenceEventCount = 0,
                fallbackPhoneLabels = emptyList()
            )
        }

        val events = ArrayList<VsmPhoneEvent>()
        var cursorSample = 0L
        var explicitPhoneCount = 0
        var silenceEventCount = 0

        for (note in notes) {
            require(note.sampleRate == schedule.sampleRate) {
                "Note '${note.noteId}' uses ${note.sampleRate} Hz but the schedule uses ${schedule.sampleRate} Hz."
            }
            require(note.absoluteStartSample >= cursorSample) {
                "Overlapping scheduled notes are not supported by the monophonic phone aligner (note '${note.noteId}')."
            }

            val noteEndSample = Math.addExact(note.absoluteStartSample, note.durationSamples)
            require(noteEndSample <= schedule.totalSampleCount) {
                "Note '${note.noteId}' extends beyond the scheduled render duration."
            }

            if (note.absoluteStartSample > cursorSample) {
                events += event(
                    startSample = cursorSample,
                    endSample = note.absoluteStartSample,
                    sampleRate = schedule.sampleRate,
                    label = SILENCE_LABEL
                )
                silenceEventCount++
            }

            val phones = parseExplicitPhones(note.phoneme, note.noteId)
            require(note.durationSamples >= phones.size.toLong()) {
                "Note '${note.noteId}' is too short to assign at least one sample to each of its ${phones.size} phone labels."
            }

            val baseSamples = note.durationSamples / phones.size.toLong()
            val remainder = (note.durationSamples % phones.size.toLong()).toInt()
            var phoneStartSample = note.absoluteStartSample

            phones.forEachIndexed { index, phone ->
                val phoneDuration = baseSamples + if (index < remainder) 1L else 0L
                val phoneEndSample = Math.addExact(phoneStartSample, phoneDuration)
                events += event(
                    startSample = phoneStartSample,
                    endSample = phoneEndSample,
                    sampleRate = schedule.sampleRate,
                    label = phone
                )
                phoneStartSample = phoneEndSample
                explicitPhoneCount++
            }

            check(phoneStartSample == noteEndSample) {
                "Phone allocation did not cover note '${note.noteId}' exactly."
            }
            cursorSample = noteEndSample
        }

        if (cursorSample < schedule.totalSampleCount) {
            events += event(
                startSample = cursorSample,
                endSample = schedule.totalSampleCount,
                sampleRate = schedule.sampleRate,
                label = SILENCE_LABEL
            )
            silenceEventCount++
        }

        val fallbackLabels = events.asSequence()
            .map { it.phoneLabel }
            .filter { label ->
                S5ApiPhoneCategoryMap.resolve(label) ==
                    S5ApiPhoneCategoryMap.FALLBACK_CATEGORY_INDEX &&
                    VsmPhoneLabelCanonicalizer.canonicalize(label) != "junk"
            }
            .distinct()
            .toList()

        return VsmNotePhoneAlignmentResult(
            events = events,
            alignedNoteCount = notes.size,
            explicitPhoneCount = explicitPhoneCount,
            silenceEventCount = silenceEventCount,
            fallbackPhoneLabels = fallbackLabels
        )
    }

    private fun parseExplicitPhones(phonemeText: String, noteId: String): List<String> {
        require(phonemeText.isNotBlank()) {
            "Note '$noteId' has no explicit phoneme string. Automatic G2P is not connected yet."
        }

        val phones = phonemeText
            .trim()
            .split(Regex("\\s+"))
            .filter { token -> token.isNotEmpty() && token !in phoneSeparators }

        require(phones.isNotEmpty()) {
            "Note '$noteId' contains only syllable/word separators and no phone labels."
        }
        return phones
    }

    private fun event(
        startSample: Long,
        endSample: Long,
        sampleRate: Int,
        label: String
    ): VsmPhoneEvent {
        require(startSample >= 0L && endSample > startSample)
        return VsmPhoneEvent(
            startSeconds = startSample.toDouble() / sampleRate.toDouble(),
            endSeconds = endSample.toDouble() / sampleRate.toDouble(),
            phoneLabel = label,
            opaqueSourceU32 = 0
        )
    }
}
