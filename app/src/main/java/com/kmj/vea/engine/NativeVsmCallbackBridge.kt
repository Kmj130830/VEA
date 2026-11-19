package com.kmj.vea.engine

/**
 * Loads the Android-native VSM callback transport library.
 *
 * This library implements the recovered output callback boundary and WAV
 * capture adapter. It does not yet execute Yamaha's S5API AI model pipeline.
 */
object NativeVsmCallbackBridge {
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

    private external fun nativeAbiVersion(): Int
    private external fun nativeDefaultSampleRate(): Int
}
