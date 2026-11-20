package com.kmj.vea.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class S5VoicebankModelPackageAssemblerTest {
    private val tree = S5ModelObjectTree.parseUtf8("""{"ok":true}""")

    private val expected = mapOf(
        "AI_Megpoid.vtb2" to "a".repeat(64),
        "AI_Megpoid.vpit" to "b".repeat(64),
        "COMMON7.vpit" to "c".repeat(64),
        "VOCALOID7.vhnv" to "d".repeat(64),
        "VOCALOID7.vtmg" to "e".repeat(64)
    )

    private fun member(name: String, hash: String) = S5ModelArchiveMember(
        entryName = name,
        magic = when (name) {
            "AI_Megpoid.vtb2" -> "VTBR"
            "AI_Megpoid.vpit", "COMMON7.vpit" -> "VPIT"
            "VOCALOID7.vhnv" -> "VHNV"
            else -> "VTMG"
        },
        formatWord = 16L,
        encodedSizeBytes = 160,
        decodedJsonSizeBytes = 12,
        sha256 = hash,
        tree = tree
    )

    @Test
    fun assemblesTheTwoPackagesIntoOneVerifiedVoicebankSet() {
        val bank = S5ModelArchive(
            mapOf(
                "AI_Megpoid.vtb2" to member("AI_Megpoid.vtb2", expected.getValue("AI_Megpoid.vtb2")),
                "AI_Megpoid.vpit" to member("AI_Megpoid.vpit", expected.getValue("AI_Megpoid.vpit"))
            )
        )
        val shared = S5ModelArchive(
            mapOf(
                "COMMON7.vpit" to member("COMMON7.vpit", expected.getValue("COMMON7.vpit")),
                "VOCALOID7.vhnv" to member("VOCALOID7.vhnv", expected.getValue("VOCALOID7.vhnv")),
                "VOCALOID7.vtmg" to member("VOCALOID7.vtmg", expected.getValue("VOCALOID7.vtmg"))
            )
        )

        val loaded = S5VoicebankModelPackageAssembler(expected).assemble(bank, shared)

        assertEquals("ai_megpoid_v7", loaded.voicebankId)
        assertEquals("BEAF64G3DYXLRECA", loaded.componentId)
        assertEquals(5, loaded.modelMemberCount)
        assertEquals("AI_Megpoid.vtb2", loaded.singerModel.entryName)
    }

    @Test
    fun rejectsAHashMismatchBeforeExposingTheModelSet() {
        val bank = S5ModelArchive(
            mapOf(
                "AI_Megpoid.vtb2" to member("AI_Megpoid.vtb2", expected.getValue("AI_Megpoid.vtb2")),
                "AI_Megpoid.vpit" to member("AI_Megpoid.vpit", expected.getValue("AI_Megpoid.vpit"))
            )
        )
        val shared = S5ModelArchive(
            mapOf(
                "COMMON7.vpit" to member("COMMON7.vpit", expected.getValue("COMMON7.vpit")),
                "VOCALOID7.vhnv" to member("VOCALOID7.vhnv", expected.getValue("VOCALOID7.vhnv")),
                "VOCALOID7.vtmg" to member("VOCALOID7.vtmg", "f".repeat(64))
            )
        )

        assertThrows(IllegalArgumentException::class.java) {
            S5VoicebankModelPackageAssembler(expected).assemble(bank, shared)
        }
    }

    @Test
    fun rejectsDuplicateMembersAcrossSingerAndSharedArchives() {
        val bank = S5ModelArchive(
            mapOf("AI_Megpoid.vtb2" to member("AI_Megpoid.vtb2", expected.getValue("AI_Megpoid.vtb2")))
        )
        val shared = S5ModelArchive(
            mapOf("AI_Megpoid.vtb2" to member("AI_Megpoid.vtb2", expected.getValue("AI_Megpoid.vtb2")))
        )

        assertThrows(IllegalArgumentException::class.java) {
            bank.mergedWith(shared)
        }
    }
}
