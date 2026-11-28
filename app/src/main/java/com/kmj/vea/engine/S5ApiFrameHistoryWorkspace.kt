package com.kmj.vea.engine

/**
 * Verified geometry for one circular frame-history buffer in FUN_180076080.
 *
 * A render may use several independent rings: the input ring, intermediate
 * operator-history rings, and rings owned by nested stage descriptors. The
 * native pointers/descriptors are not exposed to Android; callers provide
 * each ring's observed element count, slot stride and power-of-two capacity.
 */
data class S5ApiFrameHistoryRingSpec(
    val elementCount: Int,
    val slotStride: Int,
    val historySlots: Int
) {
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
        require(slotStride.toLong() * historySlots.toLong() <= Int.MAX_VALUE.toLong()) {
            "Frame history allocation exceeds the Kotlin FloatArray limit."
        }
    }

    fun createRing(): S5ApiFrameHistoryRing =
        S5ApiFrameHistoryRing(elementCount, slotStride, historySlots)
}

/**
 * Owns the independent circular history rings passed through the workspace
 * descriptor array of FUN_180076080.
 *
 * This class ports ring ownership and access only. It deliberately does not
 * decode the native 0x38-byte stage records or infer which VTMG tensor backs
 * a vtable operator.
 */
class S5ApiFrameHistoryWorkspace(
    ringSpecs: List<S5ApiFrameHistoryRingSpec>
) {
    private val specs = ringSpecs.toList()
    private val rings = specs.map(S5ApiFrameHistoryRingSpec::createRing)

    init {
        require(specs.isNotEmpty()) {
            "A frame-history workspace must own at least one ring."
        }
    }

    val ringCount: Int
        get() = rings.size

    fun ringSpec(ringIndex: Int): S5ApiFrameHistoryRingSpec {
        requireRingIndex(ringIndex)
        return specs[ringIndex]
    }

    /** Append one frame to one independent native-style workspace ring. */
    @Synchronized
    fun append(ringIndex: Int, frame: FloatArray): Long {
        requireRingIndex(ringIndex)
        return rings[ringIndex].append(frame)
    }

    /**
     * Append a frame, then atomically capture the delayed inputs used by one
     * operator list. The snapshot indices follow native operator-index order.
     */
    @Synchronized
    fun appendAndReadDelayed(
        ringIndex: Int,
        frame: FloatArray,
        delayedFrameCount: Int
    ): S5ApiFrameHistoryReadBatch {
        requireRingIndex(ringIndex)
        return rings[ringIndex].appendAndReadDelayed(frame, delayedFrameCount)
    }

    /** Read one delayed frame: 0 is newest, 1 is previous, etc. */
    @Synchronized
    fun readDelayed(ringIndex: Int, operatorIndex: Int): FloatArray {
        requireRingIndex(ringIndex)
        return rings[ringIndex].readDelayed(operatorIndex)
    }

    /** Read one physical ring slot, independent of the write cursor. */
    @Synchronized
    fun readSlot(ringIndex: Int, physicalSlotIndex: Int): FloatArray {
        requireRingIndex(ringIndex)
        return rings[ringIndex].readSlot(physicalSlotIndex)
    }

    /** Copy one ring's slots oldest-to-newest. */
    @Synchronized
    fun copyHistoryOldestToNewest(ringIndex: Int): FloatArray {
        requireRingIndex(ringIndex)
        return rings[ringIndex].copyHistoryOldestToNewest()
    }

    @Synchronized
    fun cursor(ringIndex: Int): Long {
        requireRingIndex(ringIndex)
        return rings[ringIndex].cursor
    }

    @Synchronized
    fun copyCursors(): LongArray =
        LongArray(rings.size) { rings[it].cursor }

    /** Clears every ring and returns all write cursors to zero. */
    @Synchronized
    fun reset() {
        rings.forEach(S5ApiFrameHistoryRing::reset)
    }

    private fun requireRingIndex(index: Int) {
        require(index in rings.indices) {
            "Frame-history ring index $index is outside 0 until ${rings.size}."
        }
    }
}
