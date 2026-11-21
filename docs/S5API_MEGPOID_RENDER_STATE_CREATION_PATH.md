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

The decompilation of `FUN_1800b8f60` now identifies the conversion more precisely: it treats the value passed by the wrapper as a NUL-terminated string, rejects an empty string with a JSON-parse error, and calls `FUN_1800b9200` to parse the text into S5API's typed object/value tree. It then transfers the parsed value into the destination object initialized at `param_1` through `FUN_1800e06f0`. In the observed caller, the parser flags are `1, 0, 0`.

The complete observed sequence in `FUN_1800af170` is therefore:

```c
FUN_1800b8f60(parsed_tree, json_text, temporary_state, 1, 0, 0);
FUN_1800ced10(root, parsed_tree);
FUN_1800dc4b0(parsed_tree, parsed_tree.type_tag);
```

This establishes that the value supplied to `FUN_180127220` is JSON text, not an already-materialized model tree. `FUN_1800ced10` consumes the parsed tree to populate the root's residual-block records and associated workspaces. `FUN_1800dc4b0` is the recursive typed-value cleanup routine: it walks allocated object/array contents and frees the dynamic string/container storage according to the node type tag. It is cleanup after population, not a second model conversion.

The selected property key can now be reconstructed from the call site. `FUN_18001acc0` formats the four 32-bit words embedded at the high-level initializer into a 32-character lowercase hexadecimal key: `154c806ba4d274fad9c7484acdc1779e`. The model-tree audit finds this key at `$/5be3f5d6495f68286523e230e2d0d9a4/154c806ba4d274fad9c7484acdc1779e`, and all eight residual-block factory families are matched below the descendant path `43726e4ce752ea6cc300dc2fca3e8372`. This ties the high-level lookup key to the actual AI_Megpoid.vtb2 branch containing the residual-block properties. The remaining uncertainty is the exact conversion performed by `FUN_18000ee30` between resolving that value and supplying the resulting text to `FUN_1800b8f60`; a dedicated decompilation of `FUN_18000ee30` and `FUN_180081940` is in progress. The Android model loader already has the UTF-8 JSON parser and typed-tree implementation, but the loader still needs to select the same subtree/value and reproduce the vendor's conversion contract.

## Follow-up configuration

`FUN_180127630(root, value)` is a thin wrapper which forwards the root and 32-bit value to `FUN_1800af230`. In the high-level initialization branch above, the supplied value is zero.

The decompilation of `FUN_1800af230` shows that it writes a boolean at root-relative offset `+0x618`, setting it to `(value != 0)`. When root field `+0x78` is non-null, it creates a replacement object through `FUN_180129240`, passing values read from root offsets `+0x0C`, `+0x04` (twice), `+0x10`, `+0x618`, `+0x61C`, `+0x620`, and `+0x3418`; it then releases the previous object via `FUN_180129A50` and frees its allocation. The semantic names of these fields and the object returned by `FUN_180129240` are not established yet.

Thus the observed `FUN_180127630(root, 0)` call sets the root flag at `+0x618` to false and conditionally refreshes the object at `+0x78`. It is not safe to describe the routine as a generic finalizer without further field/consumer tracing.

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
