# S5API model payload decode and JSON object-tree port

Date: 2026-11-20

## Scope

This change ports two bounded stages of the S5API model-loading path:

1. Prepare the encoded model byte range using the recovered `FUN_18002aa90` routine.
2. Parse the resulting UTF-8 JSON document into the typed value tree used by subsequent parameter lookups.

It does not instantiate the complete Megpoid model or synthesize audio.

## Static basis

The supplied authorized `S5API.dll` was analyzed statically, not executed. The recovered model constructor calls `FUN_18002aa90` and passes its byte range to a JSON parser. The parser dispatches objects, arrays, strings, numbers, booleans and null values; consumers use a type tag at object offset +0x40 and a payload/value at +0x48.

The payload transform and JSON root were verified against all five supplied model members: `AI_Megpoid.vtb2`, `AI_Megpoid.vpit`, `COMMON7.vpit`, `VOCALOID7.vhnv` and `VOCALOID7.vtmg`. The portable C++ implementation produces byte-for-byte identical output to the independent Python reference for every file, and each decoded result parses as a JSON object.

Recurrent parameters are located using the salted MD5 lookup helper already ported in `NativeS5TensorBridge`. The recurrent property family includes `weight_ih_l{layer}`, `weight_hh_l{layer}`, `bias_ih_l{layer}` and `bias_hh_l{layer}`, with `_reverse` variants. Typed strings use `*F32`, `*F16` and `*S32` markers.

## Android implementation

- `vea_s5_decode_model_payload` is the portable C ABI implementation of the recovered byte transform.
- `NativeS5TensorBridge.decodeModelPayload` exposes it through JNI.
- `S5ModelObjectTree.parseModelPayload` connects decoding to strict UTF-8 JSON parsing.
- The parser supports objects, arrays, strings/escapes, finite numbers, booleans and null.
- Duplicate object keys, malformed UTF-8, malformed numbers, unpaired Unicode surrogates and trailing content are rejected.
- Default parser limits are 128 MiB input, depth 128 and two million parsed values.
- Parameter names can be hashed with the existing native salted-key helper; tests inject a deterministic hasher and typed tensor decoder.

CI's native differential check compares complete decoded byte arrays for all five actual model files. It logs only member names, sizes, root-entry counts and short key samples, not tensor contents.

## Decoded AI Megpoid tree inventory and unresolved parameter names

The validated AI_Megpoid.vtb2 JSON object tree contains 258 objects, 108 arrays, 299 strings, 626 numbers, 87 booleans, one null, 859 32-character hex keys, 282 *F32 strings and one *S32 string. The inventory found no *F16 strings. These are structural counts only; tensor contents are not printed by CI.

The static generic RNN constructor supplies base rnn and suffixes including .weight_ih_l, .weight_hh_l, .bias_ih_l and .bias_hh_l. None of the direct layer-zero salted-key candidates, including reverse variants, matched a key in the supplied five decoded model trees.

The two model feature checks are now resolved against those actual trees. FUN_18004e3b0 queries the fixed object-tree key 69d61aca6f87b9618d4c65b9560cb5be; FUN_18004e1e0 queries 9f174dc2b1ab89f96f4577e17d5e568a. Both keys are absent from all five supplied decoded members, including AI_Megpoid.vtb2. These are existence checks in the typed object tree, so the corresponding flags evaluate false for the supplied model set. The second flag is copied to the model context at +0xf11 and is passed into the model-layer setup path. This confirms that the conditional path guarded by that flag is not enabled by the supplied AI Megpoid model. It does not by itself identify the alternate model graph or prove that no other recurrent implementation exists elsewhere.

The parser and salted-key helper must therefore not be described as having loaded Megpoid's RNN weights. The next target is to trace the active non-conditional model path from the VDM voicebank metadata into the S5API/VSM model execution path, rather than forcing the generic RNN parameter names onto this model.

## Android ZIP archive reader

`S5ModelArchiveReader` now accepts a ZIP input stream and loads the known model members used by the supplied AI Megpoid package. It validates the member name-to-magic mapping and the 136-byte header length field, invokes the native payload decoder, and parses each decoded JSON root into an `S5ModelObjectTree`.

The reader requires `AI_Megpoid.vtb2` and also recognizes `AI_Megpoid.vpit`, `COMMON7.vpit`, `VOCALOID7.vhnv` and `VOCALOID7.vtmg`. Artwork, manifest and unrelated ZIP members are skipped. The full ZIP stream is nevertheless subject to decompression limits (128 entries, 128 MiB per entry, 256 MiB total by default); duplicate recognized basenames are rejected. The payload decoder is injectable so JVM tests can exercise archive/header/JSON wiring without loading JNI.

This gives Android a tested archive-to-object-tree loading path. It does not infer the model graph or turn its tensor values into an executable singer.

## Deliberate limits

- The payload transform is not a full voicebank/runtime loader; the final 128 bytes of the supplied members are outside the JSON range produced by the recovered function and their role remains unknown.
- The typed `*F16` path still requires the per-model +0x208 conversion callback for complete backend compatibility; PR #4 contains a callback injection point on a separate branch which has not yet been reconciled with this branch.
- Recurrent storage has three gate blocks, but gate ordering, forward equations, activation placement, bias semantics and sequence direction math remain unverified. This is not a GRU implementation.
- The remaining model graph, shape propagation, complete conditioning/feature generation and spectral decoder are not implemented.
- The VSM callback bridge remains a transport/capture boundary. No Android Megpoid V7 PCM output is generated by this change.

Passing parser tests and native byte-differential tests proves these two loading stages only, not complete model inference or singing synthesis.
