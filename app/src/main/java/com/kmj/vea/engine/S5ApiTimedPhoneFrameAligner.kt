package com.kmj.vea.engine

/**
 * Native per-frame phone ownership/timing arrays produced inside
 * S5API.dll FUN_18003ab70 after the event-level FUN_18006c720 feature matrix
 * has been prepared.
 *
 * The arrays are frame-major:
 * - eventIndexByFrame[f] identifies the source phone event;
 * - relativeStartSecondsByFrame[f] is frameTime - eventStartTime;
 * - eventDurationSecondsByFrame[f] is the owning event's full duration;
 * - eventControlByFrame[f] is -1 for native categories 0/1, otherwise the
 *   explicit caller-supplied param_8 value.
 *
 * This is the exact timing/index plan consumed by the later native
 * FUN_180076080/FUN_180077520 stateful feature path. It is not itself the
 * output of those stateful VTMG operators.
 */
data class S5ApiTimedPhoneFrameAlignment(
    val framePeriodSeconds: Float,
    val frameCount: Int,
    val eventIndexByFrame: IntArray,
    val relativeStartSecondsByFrame: FloatArray,
    val eventDurationSecondsByFrame: FloatArray,
    val eventControlByFrame: FloatArray
) {
    init {
        require(framePeriodSeconds.isFinite() && framePeriodSeconds > 0f)
        require(frameCount >= 0)
        require(eventIndexByFrame.size == frameCount)
        require(relativeStartSecondsByFrame.size == frameCount)
        require(eventDurationSecondsByFrame.size == frameCount)
        require(eventControlByFrame.size == frameCount)
        require(relativeStartSecondsByFrame.all { it.isFinite() })
        require(eventDurationSecondsByFrame.all { it.isFinite() && it > 0f })
        require(eventControlByFrame.all { it.isFinite() })
        require(eventIndexByFrame.all { it >= 0 })
    }

    fun copyEventIndices(): IntArray = eventIndexByFrame.copyOf()
    fun copyRelativeStartSeconds(): FloatArray = relativeStartSecondsByFrame.copyOf()
    fun copyEventDurationSeconds(): FloatArray = eventDurationSecondsByFrame.copyOf()
    fun copyEventControls(): FloatArray = eventControlByFrame.copyOf()
}

/**
 * Reproduces the frame-to-phone assignment in S5API FUN_18003ab70.
 *
 * Native totalFrameCount is int(lastEvent.endSeconds / framePeriodSeconds),
 * i.e. truncation toward zero for the positive timeline. Each event then owns
 * frames from the previous event's end-frame cursor up to
 * min(totalFrameCount, ceil(event.endSeconds / framePeriodSeconds)).
 *
 * For every owned frame f:
 *   relativeStart = float(float(f - eventStartFrameCursor) * framePeriod)
 *                   + float(eventStartFrameCursor * framePeriod - eventStart)
 *   eventDuration = float(eventEnd - eventStart)
 *
 * The relative-start expression preserves the native float32 local-offset
 * multiply and double-precision base-time calculation before narrowing.
 */
object S5ApiTimedPhoneFrameAligner {
    fun align(
        events: List<VsmPhoneEvent>,
        descriptor: S5ApiDefaultPhoneFeatureDescriptor,
        eventControlForCategories2To13: Float
    ): S5ApiTimedPhoneFrameAlignment = align(
        events = events,
        framePeriodSeconds = descriptor.requireFramePeriodSeconds(),
        eventControlForCategories2To13 = eventControlForCategories2To13
    )

    fun align(
        source: VsmNotePhoneAlignmentResult,
        descriptor: S5ApiDefaultPhoneFeatureDescriptor,
        eventControlForCategories2To13: Float
    ): S5ApiTimedPhoneFrameAlignment = align(
        events = source.events,
        descriptor = descriptor,
        eventControlForCategories2To13 = eventControlForCategories2To13
    )

    fun align(
        events: List<VsmPhoneEvent>,
        framePeriodSeconds: Float,
        eventControlForCategories2To13: Float
    ): S5ApiTimedPhoneFrameAlignment {
        require(framePeriodSeconds.isFinite() && framePeriodSeconds > 0f) {
            "Native phone frame period must be finite and positive."
        }
        require(eventControlForCategories2To13.isFinite()) {
            "The caller-supplied phone event control must be finite."
        }
        require(events.isNotEmpty()) {
            "FUN_18003ab70 requires at least one timed phone event."
        }

        var previousStart = -1.0
        var previousEnd = -1.0
        for ((index, event) in events.withIndex()) {
            require(event.startSeconds.isFinite() && event.endSeconds.isFinite()) {
                "Phone event $index has a non-finite endpoint."
            }
            require(event.startSeconds >= 0.0 && event.endSeconds > event.startSeconds) {
                "Phone event $index must have a non-negative start and positive duration."
            }
            require(event.startSeconds >= previousStart && event.endSeconds >= previousEnd) {
                "Phone events must be ordered by non-decreasing start and end times."
            }
            previousStart = event.startSeconds
            previousEnd = event.endSeconds
        }

        val framePeriodDouble = framePeriodSeconds.toDouble()
        val totalFrameRatio = events.last().endSeconds / framePeriodDouble
        require(totalFrameRatio.isFinite() &&
            totalFrameRatio <= Int.MAX_VALUE.toDouble()) {
            "Native phone frame count exceeds the supported Int range."
        }
        // The native cast to signed int truncates toward zero; all times are
        // non-negative, so this is floor(total end / frame period).
        val frameCount = totalFrameRatio.toInt()

        val eventIndices = IntArray(frameCount) { -1 }
        val relativeStarts = FloatArray(frameCount)
        val eventDurations = FloatArray(frameCount)
        val eventControls = FloatArray(frameCount)

        var frameCursor = 0
        events.forEachIndexed { eventIndex, event ->
            val eventEndRatio = event.endSeconds / framePeriodDouble
            require(eventEndRatio.isFinite() &&
                eventEndRatio <= Int.MAX_VALUE.toDouble()) {
                "Phone event $eventIndex ends beyond the supported frame range."
            }
            val ceilEndFrame = ceil(eventEndRatio).toLong()
            val endFrame = minOf(frameCount.toLong(), ceilEndFrame).toInt()

            if (frameCursor < endFrame) {
                val categoryIndex = S5ApiPhoneCategoryMap.resolve(event.phoneLabel)
                val eventControl = if (categoryIndex < 2) {
                    -1f
                } else {
                    eventControlForCategories2To13
                }
                val eventDuration = (event.endSeconds - event.startSeconds).toFloat()
                require(eventDuration.isFinite() && eventDuration > 0f) {
                    "Phone event $eventIndex duration is outside the supported float32 range."
                }

                for (frame in frameCursor until endFrame) {
                    val frameOffset = frame - frameCursor
                    val localOffset = frameOffset.toFloat() * framePeriodSeconds
                    val baseRelativeStart =
                        frameCursor.toDouble() * framePeriodDouble - event.startSeconds
                    val relativeStart =
                        (localOffset.toDouble() + baseRelativeStart).toFloat()
                    require(relativeStart.isFinite()) {
                        "Phone event $eventIndex produced a non-finite frame-relative start."
                    }

                    eventIndices[frame] = eventIndex
                    relativeStarts[frame] = relativeStart
                    eventDurations[frame] = eventDuration
                    eventControls[frame] = eventControl
                }
            }
            frameCursor = endFrame
        }

        // Native tail behavior: any frames left after the final event reuse the
        // final phone row with relativeStart=0, duration=1 and control=-1.
        if (frameCursor < frameCount) {
            val lastEventIndex = events.lastIndex
            for (frame in frameCursor until frameCount) {
                eventIndices[frame] = lastEventIndex
                relativeStarts[frame] = 0f
                eventDurations[frame] = 1f
                eventControls[frame] = -1f
            }
        }

        check(eventIndices.all { it >= 0 }) {
            "Native phone frame alignment left an unassigned frame."
        }

        return S5ApiTimedPhoneFrameAlignment(
            framePeriodSeconds = framePeriodSeconds,
            frameCount = frameCount,
            eventIndexByFrame = eventIndices,
            relativeStartSecondsByFrame = relativeStarts,
            eventDurationSecondsByFrame = eventDurations,
            eventControlByFrame = eventControls
        )
    }
}
