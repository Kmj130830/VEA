# VDM.dll voicebank/database export map

Date: 2026-11-19

## Scope

This map is based on the export-name table of the user-supplied `Editor.zip` `VDM.dll` (PE32+ AMD64). It is a static export inventory; export names provide semantic hints but do not by themselves establish parameter types, object ownership, file formats, or runtime behavior. A focused Ghidra decompilation workflow is being added separately.

## Key entry points

- `CreateDatabaseManager`
- `HasDatabaseManager`
- `VDM_DatabaseManager_destroy`

The manager exposes voicebank and database enumeration/accessors, including:
- `VDM_DatabaseManager_numVoiceBanks`
- `VDM_DatabaseManager_voiceBankByIndex`
- `VDM_DatabaseManager_voiceBankByCompID`
- `VDM_DatabaseManager_voiceBankByBSPC`
- `VDM_DatabaseManager_defaultVoiceBank`
- `VDM_DatabaseManager_defaultVoiceBankByDnn`
- `VDM_DatabaseManager_defaultVoiceBankByDse`
- `VDM_DatabaseManager_isXSynthCompatibleVoiceBank`

## Voicebank metadata fields

The export table exposes the following high-value metadata accessors:

| Export | Intended metadata (inferred from name) |
|---|---|
| `VDM_VoiceBank_compID` | Component ID |
| `VDM_VoiceBank_componentName` | Component name |
| `VDM_VoiceBank_date` | Build/release date |
| `VDM_VoiceBank_defaultStyleID` | Default style ID |
| `VDM_VoiceBank_defaultVoiceTypeID` | Default voice-type ID |
| `VDM_VoiceBank_drp` | DRP-related metadata; semantics require decompilation |
| `VDM_VoiceBank_groupName` | Voicebank group |
| `VDM_VoiceBank_hasOwnTimingModel` | Whether the bank declares its own timing model |
| `VDM_VoiceBank_isAi` | AI-bank classification flag |
| `VDM_VoiceBank_isAvailableInSequence` | Sequence availability flag |
| `VDM_VoiceBank_isDefault` | Default-bank flag |
| `VDM_VoiceBank_isSynthesizableVersion` | Compatibility/synthesizability check |
| `VDM_VoiceBank_isVersionTooNew` / `isVersionTooOld` | Version compatibility checks |
| `VDM_VoiceBank_langIDByIndex` / `langIDSize` | Supported language IDs |
| `VDM_VoiceBank_license` / `numLicenses` | License metadata accessors |
| `VDM_VoiceBank_name` | Display name |
| `VDM_VoiceBank_nativeLangID` | Native language ID |
| `VDM_VoiceBank_path` | Resolved voicebank path |
| `VDM_VoiceBank_singerID` | Singer ID |
| `VDM_VoiceBank_version` | Voicebank format/version |
| `VDM_VoiceBank_voiceTypeByIndex` / `voiceTypeByVoiceTypeID` | Voice-type lookup |

The `VDM_VoiceType_*` exports expose:
- voice type ID and display name;
- parameter count and indexed parameter access;
- NP, pitch, and timbre indices;
- vibrato gain.

The `VDM_DvqmDB_*` and `VDM_DvqmProperty_*` exports expose database IDs, language IDs, paths, versions, parent relationships, voicebank IDs, and property/keyword/level-name metadata.

## Why this matters for the Android renderer

The VSM sequence manager accepts a database-manager object before sequence creation. The VDM export surface therefore provides the next bridge between the imported Megpoid V7 bank package and the VSM/S5API render path.

The immediate analysis targets are:
1. Determine how `CreateDatabaseManager` resolves the expression-library/database path.
2. Trace `VDM_VoiceBank_isAi`, `hasOwnTimingModel`, and `isSynthesizableVersion`.
3. Determine how `VDM_VoiceBank_path`, voice-type IDs, language IDs, and component IDs map to the supplied V7 bank package and its `.vtb2`, `.vpit`, `.vhnv`, and `.vtmg` resources.
4. Identify file parsing, hash/checksum validation, and metadata ownership/lifetime.
5. Recreate only the required database/model-loading surface in portable C++ after the contracts are established.

## Limitations

- This DLL is still Windows AMD64 PE; it is not an Android library.
- This export inventory is not a completed decompilation or a functioning database loader.
- License-related exports and metadata are documented only as API surface. This work does not bypass license, activation, or entitlement checks.
- No ARM64 `.so` or full Megpoid V7 audio render has been produced by this analysis.
