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
import re
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
    # Func_47a12a73 assigns these to original descriptor slots [0], [1], [5].
    "descriptor_param0": "15bd765152e6fc7b4b901f843d716944",
    "descriptor_param1": "0322875158f9b1bff9162ba2fa7dd262",
    "descriptor_param5": "d7226a031351dcf206e02f41499ed9b6",
}


S5_KEY_SUFFIX = bytes.fromhex(
    "69 23 33 65 2e 63 5a 54 25 35 6b 39 6a 32 64 2c 32 5a 72 53 2f 2b 2f 32"
)


def s5_lookup_key(parameter_name: str) -> str:
    # JSON object property names store the MD5 digest bytes in canonical hex
    # order. FUN_18001acc0 formats four uint32 words for static-key lookup
    # constants, but the dynamic parameter-key strings retain digest.hex().
    return hashlib.md5(parameter_name.encode("utf-8") + S5_KEY_SUFFIX).hexdigest()


def summarize_model_tree(
    root: dict,
) -> tuple[dict[str, int], set[str], list[str], list[str], int, dict[str, list[object]]]:
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

    # FUN_18005ebe0 forms one GRU parameter property for each layer
    # index and each configured direction. Scan a conservative full range,
    # rather than checking only l0, so a later layer is not misreported absent.
    rnn_layer_scan_count = 16
    rnn_parameter_member_count = 4
    rnn_direction_variants = 2
    candidates = [
        f"rnn.{member}_l{layer}{direction}"
        for layer in range(rnn_layer_scan_count)
        for member in ("weight_ih", "weight_hh", "bias_ih", "bias_hh")
        for direction in ("", "_reverse")
    ]
    rnn_candidate_count = (
        rnn_layer_scan_count * rnn_parameter_member_count * rnn_direction_variants
    )
    matching_parameters = [
        name for name in candidates if s5_lookup_key(name) in all_keys
    ]
    module_names = [
        "pre1", "pre2", "conv1", "cond1", "conv2", "cond2",
        "conv3", "cond3", "linear",
    ]
    matching_modules = [name for name in module_names if s5_lookup_key(name) in all_keys]
    return (
        counts, all_keys, matching_parameters, matching_modules,
        rnn_candidate_count, key_values
    )


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

    direct_child_keys = sorted(value.keys()) if isinstance(value, dict) else []
    integer_metadata_arrays: list[dict[str, object]] = []
    metadata_stack: list[tuple[object, str, int]] = [(value, "$", 0)]
    while metadata_stack and len(integer_metadata_arrays) < 32:
        current, path, depth = metadata_stack.pop()
        if depth > 16:
            continue
        if isinstance(current, dict):
            for key, child in reversed(list(current.items())):
                metadata_stack.append((child, f"{path}/{key}", depth + 1))
        elif isinstance(current, list):
            flat_values: list[int] = []
            flatten_stack = [current]
            all_integers = True
            while flatten_stack:
                item = flatten_stack.pop()
                if isinstance(item, list):
                    flatten_stack.extend(item)
                elif isinstance(item, int) and not isinstance(item, bool):
                    flat_values.append(item)
                else:
                    all_integers = False
                    break
            if all_integers and 0 < len(flat_values) <= 32:
                integer_metadata_arrays.append({
                    "path": path,
                    "shape": uniform_array_shape(current),
                    "values": flat_values,
                })
            for index, child in reversed(list(enumerate(current))):
                if isinstance(child, (dict, list)):
                    metadata_stack.append((child, f"{path}[{index}]", depth + 1))

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
            items = list(current.items())
            for key, child in reversed(items):
                stack.append((child, f"{path}/{key}", depth + 1))
        elif isinstance(current, list):
            for index, child in reversed(list(enumerate(current))):
                stack.append((child, f"{path}[{index}]", depth + 1))

    return {
        "node_kind": json_value_kind(value),
        "direct_children": len(child_values),
        "direct_child_kinds": child_kinds,
        "direct_child_keys": direct_child_keys[:64],
        "direct_child_keys_truncated": len(direct_child_keys) > 64,
        "integer_metadata_arrays": integer_metadata_arrays,
        "integer_metadata_arrays_truncated": bool(metadata_stack),
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


NAMED_MODEL_LAYER_BANK_COUNTS = {
    # Recovered from the nine FUN_180068190 -> FUN_180064670 call sites.
    "pre1": 1,
    "pre2": 1,
    "conv1": 3,
    "cond1": 1,
    "conv2": 3,
    "cond2": 1,
    "conv3": 3,
    "cond3": 1,
    "linear": 1,
}


def inspect_vtmg_typed_root_objects(member: str, root: dict) -> None:
    """List hashed names and sizes of immediate typed-tensor fields in VTMG."""
    for root_key, value in sorted(root.items()):
        if not isinstance(value, dict):
            continue
        typed_fields = []
        for child_key, child_value in sorted(value.items()):
            tensors = typed_tensor_lengths(child_value)
            if tensors:
                typed_fields.append(
                    {
                        "key": child_key,
                        "value_kind": describe_value(child_value),
                        "tensor_count": len(tensors),
                        "elements": [item.get("elements") for item in tensors],
                        "types": [item.get("type") for item in tensors],
                    }
                )
        if typed_fields:
            print(
                "VTMG_TYPED_ROOT_OBJECT",
                f"member={member}",
                f"root_key={root_key}",
                f"typed_field_count={len(typed_fields)}",
                f"fields={typed_fields}",
            )


def inspect_named_layer_container_keys(
    member: str, key_values: dict[str, list[object]]
) -> None:
    """Report hashed child-key names under FUN_180068190's layer container."""
    container_key = MODEL_STATIC_LOOKUP_KEYS["fun_180068190_key_3"]
    for occurrence_index, value in enumerate(key_values.get(container_key, [])):
        if isinstance(value, dict):
            print(
                "MODEL_NAMED_LAYER_CONTAINER_KEYS",
                f"member={member}",
                f"occurrence={occurrence_index}",
                f"key_count={len(value)}",
                f"keys={sorted(value.keys())}",
                f"value_kinds={{{', '.join(repr(k) + ': ' + repr(describe_value(v)) for k, v in sorted(value.items()))}}}",
            )


def inspect_megpoid_named_layer_parameters(
    member: str, key_values: dict[str, list[object]]
) -> None:
    """Audit hashed .weight/.bias members for FUN_180068190's named layers."""
    for layer, matrix_count in NAMED_MODEL_LAYER_BANK_COUNTS.items():
        discovered: dict[str, tuple[int, list[dict[str, object]]]] = {}
        for suffix in ("weight", "bias"):
            parameter_name = f"{layer}.{suffix}"
            lookup_key = s5_lookup_key(parameter_name)
            occurrences = key_values.get(lookup_key, [])
            tensors = [
                tensor
                for occurrence in occurrences
                for tensor in typed_tensor_lengths(occurrence)
            ]
            discovered[suffix] = (len(occurrences), tensors)
            print(
                "MODEL_NAMED_LAYER_PARAMETER",
                f"member={member}",
                f"layer={layer}",
                f"parameter={suffix}",
                f"lookup_key={lookup_key}",
                f"occurrences={len(occurrences)}",
                f"typed_tensor_count={len(tensors)}",
                f"typed_tensor_elements={[item.get('elements') for item in tensors]}",
                f"typed_tensor_types={[item.get('type') for item in tensors]}",
            )

        weight_occurrences, weight_tensors = discovered["weight"]
        bias_occurrences, bias_tensors = discovered["bias"]
        classification = "unresolved"
        axis0_count = None
        axis1_count = None
        padded_axis0_count = None
        if (
            weight_occurrences == 1
            and bias_occurrences == 1
            and len(weight_tensors) == 1
            and len(bias_tensors) == 1
        ):
            weight_elements = weight_tensors[0].get("elements")
            bias_elements = bias_tensors[0].get("elements")
            if (
                isinstance(weight_elements, int)
                and isinstance(bias_elements, int)
                and weight_elements > 0
                and bias_elements > 0
            ):
                # FUN_180064670 writes param_5 to axis0, param_4 to axis1,
                # and param_6 to the number of BlockMatrix objects.
                denominator = bias_elements * matrix_count
                if denominator > 0 and weight_elements % denominator == 0:
                    candidate_axis1 = weight_elements // denominator
                    if candidate_axis1 > 0:
                        axis0_count = bias_elements
                        axis1_count = candidate_axis1
                        padded_axis0_count = ((axis0_count + 7) // 8) * 8
                        classification = "descriptor_axes_consistent"
        print(
            "MODEL_NAMED_LAYER_SHAPE",
            f"member={member}",
            f"layer={layer}",
            f"matrix_count={matrix_count}",
            f"axis0_count={axis0_count}",
            f"axis1_count={axis1_count}",
            f"padded_axis0_count={padded_axis0_count}",
            f"classification={classification}",
        )


def inspect_megpoid_block_descriptor_fields(
    member: str, root: dict, key_values: dict[str, list[object]]
) -> None:
    """Report scalar descriptor slots and their JSON paths, never tensor values."""
    occurrences_by_key: dict[str, list[tuple[str, object]]] = {
        key: [] for key in MODEL_BLOCK_DESCRIPTOR_KEYS.values()
    }
    stack: list[tuple[object, str]] = [(root, "$")]
    while stack:
        current, path = stack.pop()
        if isinstance(current, dict):
            for key, child in current.items():
                if key in occurrences_by_key:
                    occurrences_by_key[key].append((path + "/" + key, child))
                stack.append((child, path + "/" + key))
        elif isinstance(current, list):
            for index, child in enumerate(current):
                stack.append((child, f"{path}[{index}]"))

    values_by_label: dict[str, list[int | float]] = {}
    for label, key in MODEL_BLOCK_DESCRIPTOR_KEYS.items():
        occurrences = occurrences_by_key[key]
        numeric = [
            value for _, value in occurrences
            if isinstance(value, (int, float)) and not isinstance(value, bool)
        ]
        values_by_label[label] = numeric
        print(
            "MODEL_BLOCK_DESCRIPTOR_FIELD",
            f"member={member}",
            f"field={label}",
            f"key={key}",
            f"occurrences={len(occurrences)}",
            f"values_and_paths={[(path, value if isinstance(value, (int, float)) and not isinstance(value, bool) else describe_value(value)) for path, value in occurrences]}",
        )

    p0 = values_by_label.get("descriptor_param0", [])
    p1 = values_by_label.get("descriptor_param1", [])
    if p0 and p1:
        candidate_ratios = sorted({
            int((value / 2) * width)
            for value in p0
            for width in p1
            if value % 2 == 0 and value > 0 and width > 0
        })
        print(
            "MODEL_BLOCK_DESCRIPTOR_RATIO_CANDIDATES",
            f"member={member}",
            f"param0_values={sorted(set(p0))}",
            f"param1_values={sorted(set(p1))}",
            f"candidate_ratios={(candidate_ratios)}",
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
    repeated_block_shape_count = 0
    repeated_block_banks: dict[int, int] = {}
    single_bank_entry_candidate_count = 0
    root_group_stats: dict[str, dict[str, object]] = {}
    while stack:
        current, path = stack.pop()
        if isinstance(current, dict):
            if first_key in current and second_key in current:
                first = typed_tensor_lengths(current[first_key])
                second = typed_tensor_lengths(current[second_key])
                pair_count += 1
                top_group = path.split("/", 2)[1] if "/" in path else "$"
                group_stats = root_group_stats.setdefault(
                    top_group,
                    {
                        "pairs": 0,
                        "repeated": 0,
                        "single_bank_candidate": 0,
                        "repeated_banks": {},
                    },
                )
                group_stats["pairs"] = int(group_stats["pairs"]) + 1
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
                        # FUN_1800537e0 callsite 0x180053e13 passes:
                        # columns = descriptor[+0x10], rows =
                        # 2*descriptor[+0x14]*descriptor[+0x08], bankCount = 1.
                        # Even KEY_2 length is only arithmetic compatibility;
                        # it cannot associate this object with that callsite.
                        if (
                            secondary_elements >= 2
                            and secondary_elements % 2 == 0
                            and primary_elements == secondary_elements * ratio
                        ):
                            single_bank_entry_candidate_count += 1
                            group_stats["single_bank_candidate"] = (
                                int(group_stats["single_bank_candidate"]) + 1
                            )
                        # FUN_180052f90 callsite 0x180053421 passes
                        # columns = descriptor[+4], rows = 2*descriptor[+4],
                        # matrixCount = descriptor[+0].
                        if secondary_elements >= 2 and secondary_elements % 2 == 0:
                            columns = secondary_elements // 2
                            if ratio % columns == 0:
                                matrix_count = ratio // columns
                                expected = secondary_elements * columns * matrix_count
                                if matrix_count > 0 and expected == primary_elements:
                                    repeated_block_shape_count += 1
                                    repeated_block_banks[matrix_count] = (
                                        repeated_block_banks.get(matrix_count, 0) + 1
                                    )
                                    group_stats["repeated"] = int(group_stats["repeated"]) + 1
                                    group_banks = group_stats["repeated_banks"]
                                    assert isinstance(group_banks, dict)
                                    group_banks[matrix_count] = (
                                        int(group_banks.get(matrix_count, 0)) + 1
                                    )
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
        f"repeated_block_shape_pairs={repeated_block_shape_count}",
        f"repeated_block_matrix_counts={dict(sorted(repeated_block_banks.items()))}",
        f"single_bank_entry_shape_candidates={single_bank_entry_candidate_count}",
        f"single_bank_only_candidates={single_bank_entry_candidate_count - repeated_block_shape_count}",
        f"root_group_stats={dict(sorted(root_group_stats.items()))}",
    )


MEGPOID_BLOCK_FACTORY_FIELDS = (
    ("FUN_1800f1110", "residual_scale_at_+0xB8", "residual scale input (param9)"),
    ("FUN_180114660", "projection_optional_bias_at_+0xA8", "optional bias for descriptor +0x88 (param8)"),
    ("FUN_18011b770", "projection_weights_at_+0x98", "weights for descriptor +0x88 (param7)"),
    ("FUN_180106440", "depthwise_optional_bias_at_+0x78", "optional bias for descriptor +0x58 (param6)"),
    ("FUN_18010d550", "depthwise_descriptor_weights_at_+0x68", "weights for descriptor +0x58 (param5)"),
    ("FUN_1800f8220", "shared_descriptor_vector_at_+0x48", "second shared descriptor input (param4)"),
    ("FUN_1800ff330", "shared_descriptor_vector_at_+0x38", "first shared descriptor input (param3)"),
    ("FUN_1800ea000", "transposed_3d_tensor_at_+0x20", "source tensor transposed on its final two axes (param2)"),
)


def extract_megpoid_factory_keys(report_path: Path) -> list[dict[str, object]]:
    """Recover the 8x256 fixed keys from static Ghidra decompilation.

    Each initializer writes four uint32 constants, formats them through
    FUN_18001acc0 as 8-digit hexadecimal words, and stores the resulting
    32-character key in its indexed 0x20-byte table slot using FUN_1800e63b0.
    """
    report = report_path.read_text(encoding="utf-8")
    pattern = re.compile(
        r"local_28 = (0x[0-9a-fA-F]+);\s*"
        r"local_24 = (0x[0-9a-fA-F]+);\s*"
        r"local_20 = (0x[0-9a-fA-F]+);\s*"
        r"local_1c = (0x[0-9a-fA-F]+);.*?"
        r"FUN_1800e63b0\(local_128,(&DAT_[0-9a-fA-F]+)\);",
        re.S,
    )
    tables: list[dict[str, object]] = []
    for function_name, target_field, role in MEGPOID_BLOCK_FACTORY_FIELDS:
        sections = list(re.finditer(rf"^### \d+\. {function_name} at ", report, re.M))
        if not sections:
            raise ValueError(f"Ghidra report is missing decompilation for {function_name}.")
        start = sections[-1].start()
        end = report.find("\n### ", start + 5)
        section = report[start:end if end >= 0 else len(report)]
        matches = list(pattern.finditer(section))
        if len(matches) != 256:
            raise ValueError(
                f"{function_name} must contain 256 initialized table keys; found {len(matches)}."
            )
        entries = []
        first_address = int(matches[0].group(5).replace("&DAT_", ""), 16)
        for index, match in enumerate(matches):
            words = [int(value, 16) for value in match.groups()[:4]]
            lookup_key = "".join(f"{word:08x}" for word in words)
            table_address = int(match.group(5).replace("&DAT_", ""), 16)
            if table_address != first_address + index * 0x20:
                raise ValueError(
                    f"{function_name} table slot {index} is not at the expected 0x20-byte stride."
                )
            entries.append({
                "index": index,
                "row": index // 16,
                "column": index % 16,
                "lookup_key": lookup_key,
            })
        tables.append({
            "factory": function_name,
            "target_field": target_field,
            "role": role,
            "base_address": f"0x{first_address:08X}",
            "entry_count": len(entries),
            "entries": entries,
        })
    return tables


def inspect_megpoid_dynamic_block_keys(
    member: str, root: dict, factory_tables: list[dict[str, object]]
) -> None:
    """Match recovered factory keys against the model tree without exposing tensor values."""
    keys_to_factories: dict[str, list[tuple[dict[str, object], dict[str, object]]]] = {}
    for table in factory_tables:
        for entry in table["entries"]:
            keys_to_factories.setdefault(str(entry["lookup_key"]), []).append((table, entry))

    matches_by_factory = {str(table["factory"]): 0 for table in factory_tables}
    stack: list[tuple[object, str]] = [(root, "$")]
    while stack:
        current, path = stack.pop()
        if isinstance(current, dict):
            for key, child in current.items():
                for table, entry in keys_to_factories.get(key, []):
                    matches_by_factory[str(table["factory"])] += 1
                    print(
                        "MODEL_BLOCK_DYNAMIC_KEY_MATCH",
                        f"member={member}",
                        f"factory={table['factory']}",
                        f"target_field={table['target_field']}",
                        f"role={table['role']}",
                        f"index={entry['index']}",
                        f"row={entry['row']}",
                        f"column={entry['column']}",
                        f"lookup_key={key}",
                        f"parent_path={path}",
                        "value_layout=" + json.dumps(model_value_layout(child), sort_keys=True),
                    )
                stack.append((child, path + "/" + key))
        elif isinstance(current, list):
            for index, child in enumerate(current):
                stack.append((child, f"{path}[{index}]"))

    for table in factory_tables:
        factory = str(table["factory"])
        print(
            "MODEL_BLOCK_DYNAMIC_KEY_SUMMARY",
            f"member={member}",
            f"factory={factory}",
            f"target_field={table['target_field']}",
            f"matched_entries={matches_by_factory[factory]}",
            f"candidate_entries={table['entry_count']}",
            f"base_address={table['base_address']}",
        )


def inspect_archive(path: Path, native_decoder: NativeDecoder | None, factory_tables: list[dict[str, object]] | None = None) -> int:
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
                counts, all_keys, parameter_matches, module_matches, rnn_candidate_count, key_values = summarize_model_tree(root)
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
                inspect_megpoid_block_descriptor_fields(item.filename, root, key_values)
                if item.filename == "AI_Megpoid.vtb2":
                    inspect_megpoid_layer_property_pairs(root, item.filename)
                    if factory_tables is not None:
                        inspect_megpoid_dynamic_block_keys(item.filename, root, factory_tables)
                if item.filename.lower().endswith(".vtmg"):
                    inspect_vtmg_typed_root_objects(item.filename, root)
                    inspect_named_layer_container_keys(item.filename, key_values)
                # The name-based parameter loader takes the current model object
                # as input; audit all archive members rather than assuming VTMG.
                inspect_megpoid_named_layer_parameters(item.filename, key_values)
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
                    f"matched={len(parameter_matches)}/{rnn_candidate_count}",
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
    parser.add_argument(
        "--ghidra-report",
        type=Path,
        default=None,
        help="Optional S5API Megpoid block-shape Ghidra report used to recover the eight 16x16 model-key tables.",
    )
    args = parser.parse_args()
    native_decoder = NativeDecoder(args.native_library) if args.native_library else None
    factory_tables = (
        extract_megpoid_factory_keys(args.ghidra_report)
        if args.ghidra_report is not None else None
    )
    if factory_tables is not None:
        print(
            "MODEL_BLOCK_DYNAMIC_KEY_CANDIDATES",
            f"factories={len(factory_tables)}",
            f"candidate_keys={sum(int(table['entry_count']) for table in factory_tables)}",
            f"ghidra_report={args.ghidra_report}",
        )

    total = 0
    for archive in (Path("megpoidV7.zip"), Path("AI Model.zip")):
        total += inspect_archive(archive, native_decoder, factory_tables)
    print("S5_JSON_OBJECTS_RECOVERED", total)
    if native_decoder is not None:
        print("S5_NATIVE_DIFFERENTIAL", "PASS")


if __name__ == "__main__":
    main()
