#!/usr/bin/env python3
"""Statically verify the recovered S5API model-payload byte transform.

Reads the supplied ZIP members as data only. It never runs vendor code, writes
model payloads to disk, or prints parameter/tensor contents.
"""
from __future__ import annotations

import argparse
import base64
import ctypes
import hashlib
import json
import struct
import zipfile
from pathlib import Path

MAGICS = {b"VTMB", b"VTBR", b"VTMG", b"VPIT", b"VPNV", b"VHNV"}
MASK32 = 0xFFFFFFFF


def read_u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def xorshift11(value: int) -> int:
    return (((value << 11) & MASK32) ^ value) & MASK32


def decode_payload(data: bytes) -> bytes:
    """Port FUN_18002aa90 for recognized V7 model chunk magics."""
    if len(data) < 16:
        return b""
    if data[:4] not in MAGICS:
        return data

    end = read_u32(data, 4) + 8
    if end < 16 or len(data) < end:
        end = len(data)
    output_size = end - 16
    word_count, tail_count = divmod(output_size, 4)

    seed = read_u32(data, 12)
    state_a = 0x159A55E5
    state_b = 0x1F123BB5
    state_c = 0x05491333
    out = bytearray(output_size)
    last_seed_source = seed

    for index in range(word_count):
        last_seed_source = state_a
        previous_c = state_c
        stepped_seed = xorshift11(seed)
        state_c = (
            previous_c
            ^ (((previous_c >> 11) ^ stepped_seed) >> 8)
            ^ stepped_seed
        ) & MASK32
        cipher_word = read_u32(data, 16 + index * 4)
        struct.pack_into("<I", out, index * 4, state_c ^ cipher_word)

        seed = state_a
        state_a = state_b
        state_b = previous_c

    if tail_count:
        tail_offset = 16 + word_count * 4
        packed_tail = 0
        for index in range(tail_count):
            packed_tail |= data[tail_offset + index] << (index * 8)

        tail_state = xorshift11(last_seed_source)
        mixed = (
            ((((state_c >> 11) ^ tail_state) >> 8)
             ^ tail_state ^ state_c ^ packed_tail)
            & MASK32
        )
        for index in range(tail_count):
            out[word_count * 4 + index] = (mixed >> (index * 8)) & 0xFF

    return bytes(out)


# Fixed model-tree keys queried by FUN_18004e3b0 and FUN_18004e1e0.
# These are already-formatted four-word hex strings, not salted parameter names.
MODEL_FLAG_LOOKUP_KEYS = {
    "flag_18004e3b0": "69d61aca6f87b9618d4c65b9560cb5be",
    "flag_18004e1e0": "9f174dc2b1ab89f96f4577e17d5e568a",
}


# Additional fixed keys observed in the S5API model configuration constructors.
# Function/address labels are used because the original semantic field names are unknown.
MODEL_STATIC_LOOKUP_KEYS = {
    "fun_180056f40_key_1": "3fecf2d78547692b4157889e04a498f0",
    "fun_180056f40_key_2": "2aff5930df33d03ad1f33c3091886f13",
    "fun_180068190_key_1": "16560fbd272d1f5059d620dc7e15a5aa",
    "fun_180068190_key_2": "a0716df263a40c427d460246c4c079f3",
    "fun_180068190_key_3": "3c77d931295c40d4d75d971398fee8a1",
    "fun_180068190_key_4": "888fa825a5384e08aee475f9baf75ebe",
    "fun_1800537e0_key_1": "30c16498ff30815c43d58f610a91f938",
    "fun_1800537e0_key_2": "c169d8ef47cc23f097ff312d08a6f829",
    "fun_1800537e0_key_3": "9e4170a67e795e687b0df130d8086421",
    "fun_1800537e0_key_4": "6eaa6d5161e42effbaa006bb0cd3f587",
    "fun_1800537e0_key_5": "eedbb0e6fd15547ef093d893e7cf42f8",
    "fun_1800537e0_key_6": "987646cc1b7b3d17fdb85ccaeaed092e",
    "fun_1800537e0_key_7": "4fd7be386f8043b7cdcf1e69d16579c8",
    "fun_1800537e0_key_8": "830bb9b50d0881b9373eb1baa7bf7313",
    "fun_1800537e0_key_9": "dcef88c51681aa4ed5dee8a40b692534",
}


# Numeric descriptor properties resolved in Func_47a12a73 before FUN_1800589d0.
# The values become FUN_180052f90's dim0 (param_8) and dim1 (param_6) fields.
MODEL_BLOCK_DESCRIPTOR_KEYS = {
    "block_dim1": "0322875158f9b1bff9162ba2fa7dd262",
    "block_dim0": "d7226a031351dcf206e02f41499ed9b6",
}


S5_KEY_SUFFIX = bytes.fromhex(
    "69 23 33 65 2e 63 5a 54 25 35 6b 39 6a 32 64 2c 32 5a 72 53 2f 2b 2f 32"
)


def s5_lookup_key(parameter_name: str) -> str:
    digest = hashlib.md5(parameter_name.encode("utf-8") + S5_KEY_SUFFIX).digest()
    words = struct.unpack("<4I", digest)
    return "".join(f"{word:08x}" for word in words)


def summarize_model_tree(
    root: dict,
) -> tuple[dict[str, int], set[str], list[str], list[str], dict[str, list[object]]]:
    counts = {
        "objects": 0,
        "arrays": 0,
        "strings": 0,
        "numbers": 0,
        "booleans": 0,
        "nulls": 0,
        "hashed_keys_32hex": 0,
        "typed_f32": 0,
        "typed_f16": 0,
        "typed_s32": 0,
    }
    all_keys: set[str] = set()
    key_values: dict[str, list[object]] = {}
    stack: list[object] = [root]
    while stack:
        value = stack.pop()
        if isinstance(value, dict):
            counts["objects"] += 1
            for key, child in value.items():
                all_keys.add(key)
                key_values.setdefault(key, []).append(child)
                if len(key) == 32 and all(ch in "0123456789abcdef" for ch in key):
                    counts["hashed_keys_32hex"] += 1
                stack.append(child)
        elif isinstance(value, list):
            counts["arrays"] += 1
            stack.extend(value)
        elif isinstance(value, str):
            counts["strings"] += 1
            if value.startswith("*F32"):
                counts["typed_f32"] += 1
            elif value.startswith("*F16"):
                counts["typed_f16"] += 1
            elif value.startswith("*S32"):
                counts["typed_s32"] += 1
        elif isinstance(value, bool):
            counts["booleans"] += 1
        elif isinstance(value, (int, float)):
            counts["numbers"] += 1
        elif value is None:
            counts["nulls"] += 1

    candidates = [
        f"rnn.{member}_l0{direction}"
        for member in ("weight_ih", "weight_hh", "bias_ih", "bias_hh")
        for direction in ("", "_reverse")
    ]
    matching_parameters = [
        name for name in candidates if s5_lookup_key(name) in all_keys
    ]
    module_names = [
        "pre1", "pre2", "conv1", "cond1", "conv2", "cond2",
        "conv3", "cond3", "linear",
    ]
    matching_modules = [name for name in module_names if s5_lookup_key(name) in all_keys]
    return counts, all_keys, matching_parameters, matching_modules, key_values


def describe_value(value: object) -> str:
    if isinstance(value, dict):
        return f"object:{len(value)}"
    if isinstance(value, list):
        return f"array:{len(value)}"
    if isinstance(value, str):
        if value.startswith("*F32"):
            return "typed_f32"
        if value.startswith("*F16"):
            return "typed_f16"
        if value.startswith("*S32"):
            return "typed_s32"
        return f"string:{len(value)}"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, (int, float)):
        return "number"
    if value is None:
        return "null"
    return type(value).__name__


def value_shape(value: object) -> dict[str, int]:
    counts = {
        "objects": 0, "arrays": 0, "strings": 0, "numbers": 0,
        "booleans": 0, "nulls": 0, "typed_f32": 0, "typed_f16": 0, "typed_s32": 0,
    }
    stack = [value]
    while stack:
        current = stack.pop()
        if isinstance(current, dict):
            counts["objects"] += 1
            stack.extend(current.values())
        elif isinstance(current, list):
            counts["arrays"] += 1
            stack.extend(current)
        elif isinstance(current, str):
            counts["strings"] += 1
            if current.startswith("*F32"):
                counts["typed_f32"] += 1
            elif current.startswith("*F16"):
                counts["typed_f16"] += 1
            elif current.startswith("*S32"):
                counts["typed_s32"] += 1
        elif isinstance(current, bool):
            counts["booleans"] += 1
        elif isinstance(current, (int, float)):
            counts["numbers"] += 1
        elif current is None:
            counts["nulls"] += 1
    return counts


def try_json_object(payload: bytes) -> dict | None:
    # The native JSON parser receives a byte range, not necessarily a
    # NUL-terminated C string. Permit only trailing JSON whitespace/NUL padding.
    candidates = [payload]
    trimmed = payload.rstrip(b"\x00 \t\r\n")
    if trimmed != payload:
        candidates.append(trimmed)
    for candidate in candidates:
        try:
            text = candidate.decode("utf-8-sig", errors="strict")
            value = json.loads(text)
            if isinstance(value, dict):
                return value
        except (UnicodeDecodeError, json.JSONDecodeError):
            continue
    return None


class NativeDecoder:
    """Call the independently compiled Android-port C ABI for byte comparison."""

    def __init__(self, library_path: Path) -> None:
        self.library = ctypes.CDLL(str(library_path))
        self.decode = self.library.vea_s5_decode_model_payload
        byte_pointer = ctypes.POINTER(ctypes.c_uint8)
        self.decode.argtypes = [
            byte_pointer,
            ctypes.c_size_t,
            byte_pointer,
            ctypes.c_size_t,
            ctypes.POINTER(ctypes.c_size_t),
        ]
        self.decode.restype = ctypes.c_int

    def __call__(self, data: bytes) -> bytes:
        source_type = ctypes.c_uint8 * len(data)
        source = source_type.from_buffer_copy(data) if data else None
        source_pointer = ctypes.cast(source, ctypes.POINTER(ctypes.c_uint8)) if source else None

        required = ctypes.c_size_t()
        status = self.decode(source_pointer, len(data), None, 0, ctypes.byref(required))
        if status not in (0, 4):
            raise RuntimeError("Native S5 decoder size query failed: status=" + str(status))

        output_type = ctypes.c_uint8 * required.value
        output = output_type()
        output_pointer = ctypes.cast(output, ctypes.POINTER(ctypes.c_uint8)) if required.value else None
        status = self.decode(
            source_pointer,
            len(data),
            output_pointer,
            required.value,
            ctypes.byref(required),
        )
        if status != 0:
            raise RuntimeError("Native S5 decoder failed: status=" + str(status))
        return bytes(output)



def json_value_kind(value: object) -> str:
    if isinstance(value, dict):
        return "object"
    if isinstance(value, list):
        return "array"
    if isinstance(value, str):
        if value.startswith("*F32"):
            return "typed_f32"
        if value.startswith("*F16"):
            return "typed_f16"
        if value.startswith("*S32"):
            return "typed_s32"
        return "string"
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, (int, float)):
        return "number"
    if value is None:
        return "null"
    return type(value).__name__


def uniform_array_shape(value: object, depth: int = 0) -> list[object] | None:
    """Return a JSON numeric/list shape when rectangular; never expose values."""
    if not isinstance(value, list):
        return [] if isinstance(value, (int, float)) and not isinstance(value, bool) else None
    if depth > 16:
        return ["depth-limit"]
    if not value:
        return [0]
    child_shapes = [uniform_array_shape(child, depth + 1) for child in value]
    if any(shape is None for shape in child_shapes):
        return [len(value), "mixed"]
    first = child_shapes[0]
    if any(shape != first for shape in child_shapes[1:]):
        return [len(value), "ragged"]
    return [len(value)] + (first or [])


def decode_typed_payload_layout(value: str) -> dict[str, object]:
    marker = value[:4]
    encoded = value[4:]
    padded = encoded + ("=" * ((-len(encoded)) % 4))
    try:
        raw = base64.b64decode(padded, validate=True)
        scalar_bytes = 2 if marker == "*F16" else 4
        return {
            "marker": marker,
            "payload_bytes": len(raw),
            "elements": len(raw) // scalar_bytes,
            "element_bytes": scalar_bytes,
            "aligned": len(raw) % scalar_bytes == 0,
        }
    except (ValueError, base64.binascii.Error):
        return {"marker": marker, "payload_bytes": None, "elements": None, "invalid_base64": True}


def model_value_layout(value: object, max_tensors: int = 96) -> dict[str, object]:
    """Summarize model config object structure and tensor lengths without data."""
    shape = uniform_array_shape(value)
    child_values = list(value.values()) if isinstance(value, dict) else value if isinstance(value, list) else []
    child_kinds = {}
    for child in child_values:
        kind = json_value_kind(child)
        child_kinds[kind] = child_kinds.get(kind, 0) + 1

    tensors: list[dict[str, object]] = []
    stack: list[tuple[object, str, int]] = [(value, "$", 0)]
    visited = 0
    while stack and len(tensors) < max_tensors and visited < 20000:
        current, path, depth = stack.pop()
        visited += 1
        if depth > 24:
            continue
        if isinstance(current, str) and current[:4] in {"*F32", "*F16", "*S32"}:
            tensors.append({"path": path, **decode_typed_payload_layout(current)})
        elif isinstance(current, dict):
            items = list(current.values())
            for index in range(len(items) - 1, -1, -1):
                stack.append((items[index], f"{path}/field{index}", depth + 1))
        elif isinstance(current, list):
            for index in range(len(current) - 1, -1, -1):
                stack.append((current[index], f"{path}[{index}]", depth + 1))

    return {
        "node_kind": json_value_kind(value),
        "direct_children": len(child_values),
        "direct_child_kinds": child_kinds,
        "rectangular_numeric_shape": shape,
        "recursive_value_counts": value_shape(value),
        "typed_payloads": tensors,
        "typed_payloads_truncated": bool(stack) or len(tensors) >= max_tensors,
    }


def typed_tensor_lengths(value: object) -> list[dict[str, object]]:
    """Return typed tensor scalar counts, never tensor contents."""
    layouts: list[dict[str, object]] = []
    stack = [value]
    while stack:
        current = stack.pop()
        if isinstance(current, str) and current[:4] in {"*F32", "*F16"}:
            layouts.append(decode_typed_payload_layout(current))
        elif isinstance(current, dict):
            stack.extend(current.values())
        elif isinstance(current, list):
            stack.extend(current)
    return layouts


def inspect_megpoid_block_descriptor_fields(
    member: str, key_values: dict[str, list[object]]
) -> None:
    """Report only scalar metadata feeding the block shape descriptor."""
    for label, key in MODEL_BLOCK_DESCRIPTOR_KEYS.items():
        values = key_values.get(key, [])
        numeric = [
            value for value in values
            if isinstance(value, (int, float)) and not isinstance(value, bool)
        ]
        kinds = sorted({describe_value(value) for value in values})
        print(
            "MODEL_BLOCK_DESCRIPTOR_FIELD",
            f"member={member}",
            f"field={label}",
            f"key={key}",
            f"occurrences={len(values)}",
            f"numeric_values={sorted(set(numeric))}",
            f"value_kinds={kinds}",
        )


def inspect_megpoid_layer_property_pairs(root: dict, member: str) -> None:
    """Find objects that contain both layer-property keys and report only lengths."""
    first_key = MODEL_STATIC_LOOKUP_KEYS["fun_1800537e0_key_1"]
    second_key = MODEL_STATIC_LOOKUP_KEYS["fun_1800537e0_key_2"]
    stack: list[tuple[object, str]] = [(root, "$")]
    pair_count = 0
    ambiguous_count = 0
    integer_ratio_count = 0
    ratio_counts: dict[int, int] = {}
    while stack:
        current, path = stack.pop()
        if isinstance(current, dict):
            if first_key in current and second_key in current:
                first = typed_tensor_lengths(current[first_key])
                second = typed_tensor_lengths(current[second_key])
                pair_count += 1
                ratio = None
                if len(first) != 1 or len(second) != 1:
                    ambiguous_count += 1
                    classification = "ambiguous"
                elif first[0].get("invalid_base64") or second[0].get("invalid_base64"):
                    classification = "invalid"
                else:
                    primary_elements = first[0].get("elements")
                    secondary_elements = second[0].get("elements")
                    classification = "paired"
                    if (
                        isinstance(primary_elements, int)
                        and isinstance(secondary_elements, int)
                        and primary_elements > 0
                        and secondary_elements > 0
                        and primary_elements % secondary_elements == 0
                    ):
                        ratio = primary_elements // secondary_elements
                        integer_ratio_count += 1
                        ratio_counts[ratio] = ratio_counts.get(ratio, 0) + 1
                print(
                    "MODEL_LAYER_PROPERTY_PAIR",
                    f"member={member}",
                    f"parent={path}",
                    f"first_count={len(first)}",
                    f"first_elements={[item.get('elements') for item in first]}",
                    f"second_count={len(second)}",
                    f"second_elements={[item.get('elements') for item in second]}",
                    f"primary_to_secondary_ratio={ratio}",
                    f"classification={classification}",
                )
            for key, child in current.items():
                stack.append((child, path + "/" + key))
        elif isinstance(current, list):
            for index, child in enumerate(current):
                stack.append((child, f"{path}[{index}]"))
    print(
        "MODEL_LAYER_PROPERTY_PAIR_SUMMARY",
        f"member={member}",
        f"pair_objects={pair_count}",
        f"ambiguous_objects={ambiguous_count}",
        f"integer_ratio_pairs={integer_ratio_count}",
        f"ratio_counts={dict(sorted(ratio_counts.items()))}",
    )


def inspect_archive(path: Path, native_decoder: NativeDecoder | None) -> int:
    if not path.is_file():
        print("MISSING_ARCHIVE", path)
        return 0

    recovered = 0
    with zipfile.ZipFile(path) as archive:
        bad_member = archive.testzip()
        if bad_member:
            raise SystemExit("ZIP CRC failure: " + bad_member)

        for item in archive.infolist():
            if item.is_dir():
                continue
            data = archive.read(item)
            if data[:4] not in MAGICS:
                continue

            decoded = decode_payload(data)
            if native_decoder is not None:
                native_decoded = native_decoder(data)
                if native_decoded != decoded:
                    raise SystemExit(
                        "NATIVE_DIFFERENTIAL_MISMATCH "
                        + f"archive={path.name}!/{item.filename}"
                    )
                print(
                    "NATIVE_MATCH",
                    f"archive={path.name}!/{item.filename}",
                    f"decoded_bytes={len(native_decoded)}",
                )
            root = try_json_object(decoded)
            if root is not None:
                keys = list(root.keys())
                sample = [str(key)[:32] for key in keys[:4]]
                counts, all_keys, parameter_matches, module_matches, key_values = summarize_model_tree(root)
                print(
                    "JSON_OK",
                    f"archive={path.name}!/{item.filename}",
                    f"magic={data[:4].decode('ascii')}",
                    f"file_bytes={len(data)}",
                    f"decoded_bytes={len(decoded)}",
                    f"root_entries={len(root)}",
                    f"key_sample={sample}",
                )
                print("TREE_SUMMARY", f"member={item.filename}", counts)
                inspect_megpoid_block_descriptor_fields(item.filename, key_values)
                if item.filename == "AI_Megpoid.vtb2":
                    inspect_megpoid_layer_property_pairs(root, item.filename)
                for root_key, root_value in root.items():
                    shape = value_shape(root_value)
                    direct_fields = len(root_value) if isinstance(root_value, (dict, list)) else 0
                    root_type = (
                        "object" if isinstance(root_value, dict) else
                        "array" if isinstance(root_value, list) else
                        "string" if isinstance(root_value, str) else
                        "boolean" if isinstance(root_value, bool) else
                        "number" if isinstance(root_value, (int, float)) else
                        "null"
                    )
                    print(
                        "ROOT_SHAPE",
                        f"member={item.filename}",
                        f"key={root_key}",
                        f"type={root_type}",
                        f"direct_fields={direct_fields}",
                        f"shape={shape}",
                    )
                print(
                    "RNN_PARAMETER_LOOKUP_CANDIDATES",
                    f"member={item.filename}",
                    f"matched={len(parameter_matches)}/8",
                    f"names={parameter_matches}",
                )
                print(
                    "MODEL_CONFIG_LOOKUP_CANDIDATES",
                    f"member={item.filename}",
                    f"matched={len(module_matches)}/9",
                    f"names={module_matches}",
                )
                for flag_name, lookup_key in MODEL_FLAG_LOOKUP_KEYS.items():
                    print(
                        "MODEL_FEATURE_FLAG",
                        f"member={item.filename}",
                        f"name={flag_name}",
                        f"present={lookup_key in all_keys}",
                        f"values={[describe_value(value) for value in key_values.get(lookup_key, [])]}",
                        f"lookup_key={lookup_key}",
                    )
                for function_key, lookup_key in MODEL_STATIC_LOOKUP_KEYS.items():
                    matches = key_values.get(lookup_key, [])
                    print(
                        "MODEL_STATIC_KEY",
                        f"member={item.filename}",
                        f"name={function_key}",
                        f"present={lookup_key in all_keys}",
                        f"values={[describe_value(value) for value in matches]}",
                        f"lookup_key={lookup_key}",
                    )
                    if function_key.startswith("fun_1800537e0"):
                        for match_index, matched_value in enumerate(matches):
                            print(
                                "MODEL_STATIC_LAYOUT",
                                f"member={item.filename}",
                                f"name={function_key}",
                                f"match_index={match_index}",
                                "layout=" + json.dumps(model_value_layout(matched_value), sort_keys=True),
                            )
                recovered += 1
            else:
                prefix = decoded[:24]
                safe = "".join(chr(b) if 0x20 <= b <= 0x7E else "." for b in prefix)
                print(
                    "JSON_NOT_CONFIRMED",
                    f"archive={path.name}!/{item.filename}",
                    f"magic={data[:4].decode('ascii')}",
                    f"file_bytes={len(data)}",
                    f"decoded_bytes={len(decoded)}",
                    f"decoded_prefix_hex={prefix.hex()}",
                    f"decoded_prefix_ascii={safe}",
                )
    return recovered


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--native-library",
        type=Path,
        default=None,
        help="Optional host build of libveas5tensor.so for byte-for-byte differential checks.",
    )
    args = parser.parse_args()
    native_decoder = NativeDecoder(args.native_library) if args.native_library else None

    total = 0
    for archive in (Path("megpoidV7.zip"), Path("AI Model.zip")):
        total += inspect_archive(archive, native_decoder)
    print("S5_JSON_OBJECTS_RECOVERED", total)
    if native_decoder is not None:
        print("S5_NATIVE_DIFFERENTIAL", "PASS")


if __name__ == "__main__":
    main()
