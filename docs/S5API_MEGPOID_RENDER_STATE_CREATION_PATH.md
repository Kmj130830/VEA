# AI Megpoid V7 render-state creation path

Date: 2026-11-21

## Evidence scope

This note records a static Ghidra trace of the supplied S5API.dll. The DLL was not executed. Function names are analyst-assigned Ghidra labels, and the evidence establishes call/dataflow relationships rather than runtime success for every voicebank or project.

## High-level initialization path

In the recovered high-level initializer `Func_47a12a73`, one branch performs the following operations:

1. Calls `FUN_180126940()` and stores the returned wrapper at owner offset `+0x20E8).
2. Resolves a model-object property using a generated lookup key and reads its value into a local string-like object.
3. Passes the created render-state wrapper and the resolved value to `FUN_180127220(root, value)`.
4. Calls `FUN_180127630(root, 0)` on the same wrapper.

The recovered pseudocode places these calls in the same branch. The semantic name of the hashed property and the branch predicate are not assigned here.

## Root allocation and initialization

`FUN_180126940` allocates an 8-byte wrapper and a `0x37A0`-byte root object, zeroes the root, and calls `FUN_1800bca40(root)` to initialize its embedded state. This is an empty root creation path, not a deep copy of a populated model.

The separate public copy path `FUN_1801269F0` calls `FUN_1800ccce0`. The latter allocates and initializes another root, resolves the source root through `FUN_1800c4730`, and copies its contents through `FUN_1800cdbb0`. It should therefore be treated as a clone/copy path, not as the initial model-population path.

## Model-value forwarding and residual block construction

The public wrapper `FUN_180127220(root, value)` packages its two arguments and calls `FUN_1800af170`.

The recovered body of `FUN_1800af170` shows this sequence:

```c
FUN_1800b8f60(parsed_value, value, temporary_state, 1, 0, 0);
FUN_1800ced10(root, parsed_value);
FUN_1800dc4b0(temporary_state, parsed_value);
```

The pseudocode's concrete local argument order is preserved in the Ghidra report. This establishes that the value supplied to `FUN_180127220` is first passed through `FUN_1800b8f60`, and the resulting temporary configuration is then passed to `FUN_1800ced10`, the routine already traced as the builder of the render-root residual-block records and associated workspaces.

The exact conversion performed by `FUN_1800b8f60`, the ownership/cleanup semantics of `FUN_1800dc4b0`, and the full model-property provenance for every render descriptor are not yet established by this call-chain note.

## Follow-up configuration

`FUN_180127630(root, value)` is a thin wrapper which forwards the root and 32-bit value to `FUN_1800af230`. In the high-level initialization branch above, the supplied value is zero.

The semantic effect of `FUN_1800af230` still requires its dedicated decompilation and dataflow review. It is intentionally not labelled as a completed render-state finalizer until its field accesses and call targets are reconciled with the root-relative descriptor map.

## Relationship to the forward path

The separately recovered forward path is:

```text
Func_92864e29
  -> FUN_180126c20
  -> FUN_1800aec40
  -> FUN_1800c7ad0
```

`FUN_1800c7ad0` consumes the runtime descriptors and residual block/state vectors owned by the render-state root. The root-relative layout is documented in `S5API_RENDER_STATE_DESCRIPTOR_LAYOUT.md`; do not infer a field's meaning from a matching numeric offset in a different object's base.

## Android porting implications

The Android implementation already parses and hash-validates the five Megpoid/shared model members, indexes the eight VTB2 residual-block property families, prepares the eight residual blocks, and packs the nine VTMG named-layer parameter sets. These are input/parameter preparation stages.

The newly mapped native path provides the missing high-level connection between a model-object value and `FUN_1800ced10`. Android still needs a behaviorally equivalent implementation of the configuration conversion at `FUN_1800b8f60`, the complete population of all root descriptors consumed by `FUN_1800c7ad0`, and validation of `FUN_1800af230`. The full forward graph, phoneme/event preparation, output-domain conversion, and verified PCM rendering remain incomplete.

No successful Megpoid vocal render or WAV output is claimed by this analysis.
