"""계약 스키마 검사기 — 시험 전용, 표준 라이브러리만 쓴다.

정본 `contracts/schemas/guide/effects.schema.json`(JSON Schema 2020-12)이 **실제로 쓰는 키워드만**
안다: type(하나 또는 목록, "null" 포함), properties, required, enum, const, items, minimum,
`#/$defs/...` 를 가리키는 $ref. 설명용 키워드(description·title·$id·$schema·examples·설명·예시)는
무시한다. 그 밖의 키워드를 만나면 **조용히 통과시키지 않고** 실패한다 — 스키마가 새 키워드를 쓰기
시작했는데 검사기가 모른 척하면 시험이 아무것도 지키지 못한다.

jsonschema 패키지를 쓰지 않는 이유: 이 모듈은 표준 라이브러리만 쓴다(agents/trip-guide/README.md).
"""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

SCHEMA_REL = Path("contracts/schemas/guide/effects.schema.json")

_ANNOTATIONS = frozenset(
    {"description", "title", "$id", "$schema", "examples", "설명", "예시"}
)
_KNOWN = frozenset(
    {
        "type",
        "properties",
        "required",
        "enum",
        "const",
        "items",
        "minimum",
        "$ref",
        "$defs",
    }
)


class SchemaViolation(AssertionError):
    """첫 번째 위반. `path` 는 JSON 포인터 모양(`/effects/0/placeId`)이다."""

    def __init__(self, path: str, message: str) -> None:
        super().__init__(f"{path or '/'}: {message}")
        self.path = path or "/"
        self.message = message


def schema_path() -> Path:
    """Bazel 런파일에서 먼저 찾고, 없으면 소스 트리에서 찾는다."""
    srcdir, workspace = os.environ.get("TEST_SRCDIR"), os.environ.get("TEST_WORKSPACE")
    if srcdir and workspace:
        candidate = Path(srcdir) / workspace / SCHEMA_REL
        if candidate.is_file():
            return candidate
    # tests/ → trip-guide/ → agents/ → 저장소 뿌리. 런파일 트리도 같은 모양이다.
    candidate = Path(__file__).absolute().parent.parent.parent.parent / SCHEMA_REL
    if candidate.is_file():
        return candidate
    raise FileNotFoundError(f"계약 스키마를 찾을 수 없다: {SCHEMA_REL}")


def load_schema() -> dict[str, Any]:
    return json.loads(schema_path().read_text(encoding="utf-8"))


def _type_ok(name: str, value: Any) -> bool:
    if name == "null":
        return value is None
    if name == "boolean":
        return isinstance(value, bool)
    if name == "integer":
        # bool 은 int 의 하위형이다 — True 가 placeId 로 통과하면 안 된다.
        return isinstance(value, int) and not isinstance(value, bool)
    if name == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    if name == "string":
        return isinstance(value, str)
    if name == "array":
        return isinstance(value, list)
    if name == "object":
        return isinstance(value, dict)
    raise SchemaViolation("", f"검사기가 모르는 type: {name!r}")


def _resolve(root: dict[str, Any], ref: str) -> dict[str, Any]:
    prefix = "#/$defs/"
    if not ref.startswith(prefix):
        raise SchemaViolation("", f"검사기가 모르는 $ref: {ref!r}")
    defs = root.get("$defs") or {}
    name = ref[len(prefix) :]
    if name not in defs:
        raise SchemaViolation("", f"없는 정의: {ref!r}")
    return defs[name]


def _same(a: Any, b: Any) -> bool:
    """enum·const 비교. True == 1 처럼 형이 다른 값을 같게 보지 않는다."""
    return type(a) is type(b) and a == b


def _check(root: dict[str, Any], schema: dict[str, Any], value: Any, path: str) -> None:
    unknown = set(schema) - _KNOWN - _ANNOTATIONS
    if unknown:
        raise SchemaViolation(path, f"검사기가 모르는 키워드: {sorted(unknown)}")

    if "$ref" in schema:
        _check(root, _resolve(root, schema["$ref"]), value, path)

    if "type" in schema:
        names = schema["type"] if isinstance(schema["type"], list) else [schema["type"]]
        if not any(_type_ok(n, value) for n in names):
            raise SchemaViolation(
                path, f"형이 {names} 이어야 하는데 {type(value).__name__} ({value!r})"
            )

    if "enum" in schema and not any(_same(value, v) for v in schema["enum"]):
        raise SchemaViolation(path, f"{value!r} 는 {schema['enum']} 중 하나가 아니다")

    if "const" in schema and not _same(value, schema["const"]):
        raise SchemaViolation(path, f"{value!r} 는 {schema['const']!r} 이어야 한다")

    if "minimum" in schema and _type_ok("number", value) and value < schema["minimum"]:
        raise SchemaViolation(path, f"{value!r} 는 {schema['minimum']} 이상이어야 한다")

    if isinstance(value, dict):
        for key in schema.get("required", []):
            if key not in value:
                raise SchemaViolation(path, f"필수 필드 {key!r} 가 없다")
        for key, sub in (schema.get("properties") or {}).items():
            if key in value:
                _check(root, sub, value[key], f"{path}/{key}")

    if isinstance(value, list) and "items" in schema:
        for i, item in enumerate(value):
            _check(root, schema["items"], item, f"{path}/{i}")


def validate(
    value: Any,
    schema: dict[str, Any] | None = None,
    *,
    root: dict[str, Any] | None = None,
) -> None:
    """`value` 가 `schema` 를 어기면 첫 위반을 `SchemaViolation` 으로 던진다.

    `schema` 를 생략하면 정본 전체(턴의 `{"effects", "ui"}`)로 검사한다. `root` 는 `$ref` 를
    풀 문서다 — 생략하면 `schema` 자신이다.
    """
    if schema is None:
        schema = load_schema()
    _check(root if root is not None else schema, schema, value, "")


def validate_def(value: Any, name: str, root: dict[str, Any] | None = None) -> None:
    """정본의 `$defs/<name>` 하나로 검사한다 (예: `guidePlan`)."""
    root = root if root is not None else load_schema()
    validate(value, {"$ref": f"#/$defs/{name}"}, root=root)
