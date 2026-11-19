package com.kmj.vea.engine

/**
 * Android-native VSM callback transport and float32 WAV capture adapter.
 *
 * This is the output boundary for the ongoing renderer port. It does not yet
 * execute Yamaha S5API neural inference or synthesize Megpoid V7 by itself.
 */
object NativeVsmCallbackBridge {
    const val STATUS_OK = 0
    const val STATUS_INVALID_ARGUMENT = 1
    const val STATUS_OUT_OF_MEMORY = 2
    const val STATUS_IO_ERROR = 3
    const val STATUS_RIFF_LIMIT_EXCEEDED = 4

    private val loaded: Boolean = try {
        System.loadLibrary("veavsm")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    } catch (_: SecurityException) {
        false
    }

    val isLoaded: Boolean
        get() = loaded

    val abiVersion: Int
        get() = if (loaded) nativeAbiVersion() else 0

    val defaultSampleRate: Int
        get() = if (loaded) nativeDefaultSampleRate() else 0

    /**
     * Creates a capture session. Returns null when the native library is absent
     * or the sample rate is invalid.
     */
    fun createCaptureSession(
        sampleRate: Int = defaultSampleRate
    ): CaptureSession? {
        if (!loaded || sampleRate !in 8_000..384_000) return null
        val handle = nativeCaptureCreate(sampleRate)
        return if (handle != 0L) CaptureSession(handle) else null
    }

    class CaptureSession internal constructor(
        private var handle: Long
    ) : AutoCloseable {
        val isClosed: Boolean
            @Synchronized get() = handle == 0L

        @Synchronized
        fun append(
            metadataU32: Int,
            metadataF64: Double,
            planeA: FloatArray,
            planeB: FloatArray
        ): Int {
            val current = handle
            if (current == 0L || planeA.isEmpty() || planeA.size != planeB.size) {
                return STATUS_INVALID_ARGUMENT
            }
            return nativeCaptureAppend(
                current, metadataU32, metadataF64, planeA, planeB
            )
        }

        @Synchronized
        fun sampleCount(): Long {
            val current = handle
            return if (current == 0L) 0L else nativeCaptureSampleCount(current)
        }

        @Synchronized
        fun blockCount(): Long {
            val current = handle
            return if (current == 0L) 0L else nativeCaptureBlockCount(current)
        }

        @Synchronized
        fun writeWavF32(absolutePath: String): Int {
            val current = handle
            if (current == 0L || absolutePath.isBlank()) {
                return STATUS_INVALID_ARGUMENT
            }
            return nativeCaptureWriteWavF32(current, absolutePath)
        }

        @Synchronized
        override fun close() {
            val current = handle
            if (current != 0L) {
                handle = 0L
                nativeCaptureDestroy(current)
            }
        }
    }

    private external fun nativeAbiVersion(): Int
    private external fun nativeDefaultSampleRate(): Int
    private external fun nativeCaptureCreate(sampleRate: Int): Long
    private external fun nativeCaptureAppend(
        handle: Long,
        metadataU32: Int,
        metadataF64: Double,
        planeA: FloatArray,
        planeB: FloatArray
    ): Int
    private external fun nativeCaptureSampleCount(handle: Long): Long
    private external fun nativeCaptureBlockCount(handle: Long): Long
    private external fun nativeCaptureWriteWavF32(handle: Long, absolutePath: String): Int
    private external fun nativeCaptureDestroy(handle: Long)
}
