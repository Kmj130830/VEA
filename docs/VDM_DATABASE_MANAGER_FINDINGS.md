# VDM database manager and voicebank metadata findings

Date: 2026-11-20

## Scope

This note summarizes the completed Ghidra 12.2 static reports:

- `VEA-VDM-voicebank-analysis`
- `VEA-VDM-database-internals`

The supplied `VDM.dll` was not executed. No registry was read on the host and no license or activation checks were bypassed.

## Database-manager construction

Two exported entry points converge on the same internal constructor:

- `VDM_createDatabaseManager` at RVA `0x5d60`
- `CreateDatabaseManager` at RVA `0xbcf0`

The first validates its first wide-string argument, obtains a process-wide singleton, and delegates to `FUN_18000be40`. The second obtains the same singleton and delegates to the same constructor. The recovered internal signature is:

```c
FUN_18000be40(manager_singleton, wchar_t* path_a, wchar_t* path_b, uint32_t* status)
```

The parameter semantics are not fully named in the binary, so VEA should preserve them as two distinct path inputs until VSM call sites establish their exact roles.

The constructor locks the singleton, rejects a duplicate `path_a` already registered in the manager, creates a `VDM5::DatabaseManager` object, and populates its database/component collections. It also returns status values through the optional output pointer. This is a metadata/database-manager construction path, not the S5 neural inference engine.

## Registry-backed database discovery

`FUN_1800e6ae0` maps version indices to these registry roots:

| Index | Registry subkey |
|---:|---|
| 0 | `SOFTWARE\\VOCALOID7` |
| 1 | `SOFTWARE\\VOCALOID6` |
| 2 | `SOFTWARE\\VOCALOID5` |
| 3, 4 | `SOFTWARE\\Wow6432Node\\VOCALOID4` |
| 5 | `SOFTWARE\\Wow6432Node\\VOCALOID3` |

`FUN_1800074c0` queries the version-specific registry roots and builds a collection of installed database entries. `FUN_180007c20` enumerates additional per-version component information. These routines explain why the Windows VDM manager depends on registry-backed installation metadata; they do not establish a portable Android file format.

Two related registry branches were also recovered:

- `FUN_1800e64c0` opens `Common\\X-Synth` and enumerates X-Synth group metadata.
- `FUN_1800e6170` opens `AttackReleaseLib\\Components` and enumerates Attack/Release component metadata.

These are metadata discovery paths. They are not evidence that VDM itself decodes the AI Megpoid `.vtb2` tensor payload.

## Voicebank API surface

The export table and decompiled thunks establish that the public voicebank methods are mostly thin virtual dispatchers. Relevant slots include:

| Export | Virtual slot offset | Observed role |
|---|---:|---|
| `VDM_VoiceBank_compID` | `+0x10` | component identifier getter |
| `VDM_VoiceBank_path` | `+0x30` | path getter |
| `VDM_VoiceBank_isSynthesizableVersion` | `+0xc8` | compatibility predicate |
| `VDM_VoiceBank_isAi` | `+0x100` | AI-bank classification predicate |

The export thunks check for a null object and then invoke the corresponding vtable slot. They do not parse model files themselves. The path getter is therefore a metadata boundary to trace at its call sites, not a model loader implementation.

## Implications for VEA

1. VDM supplies the voicebank/database metadata layer that VSM expects when creating a sequence manager.
2. Its database construction path is coupled to Windows registry discovery. An Android implementation must not attempt to emulate Windows registry calls; it should consume the known V7 package/manifest and provide the minimal metadata contract required by the renderer.
3. The VDM report does not identify a loader for `AI_Megpoid.vtb2`, `AI_Megpoid.vpit`, `VOCALOID7.vhnv`, or `VOCALOID7.vtmg`.
4. The next required trace is the VSM call path from a VDM VoiceBank object (especially its component ID and path) into the S5API model-object constructor and the audio render sequence. This will identify which layer owns model-file selection and whether the model path is resolved by VSM or another module.

## Deliberate limits

- No database-manager object has been recreated yet.
- No VDM license/activation behavior is reimplemented or bypassed.
- The export inventory and registry trace do not establish the full V7 model-file path.
- No Android Megpoid V7 vocal waveform is produced by this work.
