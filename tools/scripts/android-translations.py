"""iOS 영어 문구를 Android의 형식 지정자에 맞춰 다시 만든다."""

import json
import os
import re
from pathlib import Path


def kotlin_text(value: str) -> str:
    value = re.sub(r"%((?:\d+\$)?)[@]", r"%\1s", value)
    value = re.sub(r"%((?:\d+\$)?)lld", r"%\1d", value)
    return json.dumps(value, ensure_ascii=False).replace("$", "\\$")


def convert(source: str) -> str:
    pairs = re.findall(r'("(?:[^"\\]|\\.)*")\s*=\s*("(?:[^"\\]|\\.)*")\s*;', source)
    entries = {}
    for key, value in pairs:
        entries[kotlin_text(json.loads(key))] = kotlin_text(json.loads(value))
    if not entries:
        raise ValueError("번역 문구를 찾지 못했습니다")
    lines = [
        "// iOS 영어 문구에서 생성합니다. 수정 뒤 just android-translations를 실행하세요.",
        "package com.mz2az.scenetrip.data",
        "",
        "internal val TRANSLATIONS_EN: Map<String, String> =",
        "    mapOf(",
    ]
    for key, value in sorted(entries.items()):
        line = f"        {key} to {value},"
        if len(line) > 140:
            lines.extend([f"        {key} to", f"            {value},"])
        else:
            lines.append(line)
    lines.extend(["    ) + TRANSLATIONS_EN_EXTRA", ""])
    return "\n".join(lines)


if __name__ == "__main__":
    root = Path(os.environ["BUILD_WORKSPACE_DIRECTORY"])
    source = root / "apps/scenetrip-ios/resources/en.lproj/Localizable.strings"
    target = (
        root
        / "apps/scenetrip-android/src/main/kotlin/com/mz2az/scenetrip/data/Translations.kt"
    )
    target.write_text(convert(source.read_text()), encoding="utf-8")
    print("iOS 최신 영어 문구를 Android에 반영했습니다")
