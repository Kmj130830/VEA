package com.kmj.vea.engine

/**
 * Voicebank-neutral float-vector history ring used by S5API
 * FUN_180076080's initial operator list and the nested stage operator lists.
 *
 * Native descriptor words observed at the call site:
 * - word +0: copied element count for one frame;
 * - word +1: per-slot float stride;
 * - word +2: monotonically advanced write cursor;
 * - word +4: slot mask (capacity - 1);
 * - word +6: ring data pointer.
 *
 * The native read expression after the append is:
 *   ((writeCursor - operatorIndex - 1) & mask) * slotStride
 * Therefore operator index zero reads the vector just appended; index one
 * reads the immediately preceding frame, and so on.
 *
 * This class only owns the ring data contract. It does not execute the
 * vtable-backed operators or infer their model weights.
 */
class S5ApiFrameHistoryRing(
    val elementCount: Int,
    val slotStride: Int,
    val historySlots: Int
) {
    private val mask: Int
    private val storage: FloatArray
    private var writeCursor = 0L

    init {
        require(elementCount > 0) {
            "Frame history element count must be positive."
        }
        require(slotStride >= elementCount) {
            "Frame history slot stride must be at least elementCount."
        }
        require(historySlots >= 2 && (historySlots and (historySlots - 1)) == 0) {
            "Frame history slot count must be a power of two and at least two."
        }
        val storageSize = slotStride.toLong() * historySlots.toLong()
        require(storageSize <= Int.MAX_VALUE.toLong()) {
            "Frame history allocation exceeds the Kotlin FloatArray limit."
        }
        mask = historySlots - 1
        storage = FloatArray(storageSize.toInt())
    }

    val cursor: Long
        @Synchronized get() = writeCursor

    /** Writes one frame at cursor & mask, then advances the native cursor. */
    @Synchronized
    fun append(frame: FloatArray): Long {
        require(frame.size == elementCount) {
            "Frame history append requires exactly $elementCount values."
        }
        require(frame.all { it.isFinite() }) {
            "Frame history append contains a non-finite value."
        }
        check(writeCursor < Long.MAX_VALUE) {
            "Frame history cursor overflowed."
        }

        val slot = (writeCursor and mask.toLong()).toInt()
        val offset = slot * slotStride
        storage.fill(0f, offset, offset + slotStride)
        frame.copyInto(storage, destinationOffset = offset)
        val writtenSequence = writeCursor
        writeCursor++
        return writtenSequence
    }

    /**
     * Reads the frame at the native delayed slot after the latest append:
     * age zero is the just-appended frame, age one the previous frame.
     *
     * Unwritten startup history remains zero-filled, matching the native
     * zero-initialized ring allocation.
     */
    @Synchronized
    fun readDelayed(operatorIndex: Int): FloatArray {
        require(operatorIndex >= 0) {
            "Frame history operator index cannot be negative."
        }
        val slot = ((writeCursor - operatorIndex.toLong() - 1L) and mask.toLong()).toInt()
        val offset = slot * slotStride
        return storage.copyOfRange(offset, offset + elementCount)
    }

    /** Reads a physical ring slot by index, independent of the current cursor. */
    @Synchronized
    fun readSlot(slotIndex: Int): FloatArray {
        require(slotIndex in 0 until historySlots)
        val offset = slotIndex * slotStride
        return storage.copyOfRange(offset, offset + elementCount)
    }

    /** Returns ring slots oldest-to-newest relative to the next write slot. */
    @Synchronized
    fun copyHistoryOldestToNewest(): FloatArray {
        val result = FloatArray(storage.size)
        val nextSlot = (writeCursor and mask.toLong()).toInt()
        for (age in 0 until historySlots) {
            val sourceSlot = (nextSlot + age) and mask
            val destinationOffset = age * slotStride
            storage.copyInto(
                destination = result,
                destinationOffset = destinationOffset,
                startIndex = sourceSlot * slotStride,
                endIndex = sourceSlot * slotStride + slotStride
            )
        }
        return result
    }

    @Synchronized
    fun reset() {
        storage.fill(0f)
        writeCursor = 0L
    }
}
