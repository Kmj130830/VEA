package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VoicebankModelPackageRegistryTest {
    private data class TestPackage(
        override val voicebankId: String,
        override val engineFamily: String
    ) : VoicebankModelPackage

    private class TestLoader(
        private val id: String,
        private val family: String
    ) : VoicebankModelPackageLoader {
        override fun supports(voicebank: VoicebankDescriptor): Boolean =
            voicebank.id == id && voicebank.engineFamily == family

        override fun load(voicebank: VoicebankDescriptor): VoicebankModelPackage =
            TestPackage(id, family)
    }

    @Test
    fun dispatchesToTheMatchingVoicebankProvider() {
        val registry = VoicebankModelPackageRegistry(
            listOf(TestLoader("voice_a", "engine_a"), TestLoader("voice_b", "engine_b"))
        )
        val descriptor = VoicebankDescriptor(
            id = "voice_b",
            displayName = "Voice B",
            engineFamily = "engine_b",
            sourceUri = "file:///voice-b"
        )

        val loaded = registry.load(descriptor)
        assertEquals("voice_b", loaded.voicebankId)
        assertEquals("engine_b", loaded.engineFamily)
    }

    @Test
    fun rejectsAnUnregisteredVoicebank() {
        val registry = VoicebankModelPackageRegistry(listOf(TestLoader("voice_a", "engine_a")))
        val descriptor = VoicebankDescriptor(
            id = "voice_c",
            displayName = "Voice C",
            engineFamily = "engine_c",
            sourceUri = "file:///voice-c"
        )

        assertThrows(IllegalArgumentException::class.java) {
            registry.load(descriptor)
        }
    }

    @Test
    fun rejectsAmbiguousProviders() {
        val registry = VoicebankModelPackageRegistry(
            listOf(TestLoader("voice_a", "engine_a"), TestLoader("voice_a", "engine_a"))
        )
        val descriptor = VoicebankDescriptor(
            id = "voice_a",
            displayName = "Voice A",
            engineFamily = "engine_a",
            sourceUri = "file:///voice-a"
        )

        assertThrows(IllegalArgumentException::class.java) {
            registry.load(descriptor)
        }
    }
}
