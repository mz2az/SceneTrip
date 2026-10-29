#!/usr/bin/env python3
"""iOS 와 Android 의 Maestro 스크린샷을 단계 이름별로 짝지어 나란히 붙인다.

사용: compare.py <iOS 출력 폴더> <Android 출력 폴더> <결과 폴더>
출력 폴더는 `maestro test --test-output-dir` 로 만든 것이다. 흐름 이름/단계 이름이
같은 컷끼리 `<흐름>__<단계>.jpg` 한 장으로 합친다(왼쪽 iOS, 오른쪽 Android).
한쪽에만 있는 컷(그 앱에서 흐름이 중간에 멈춘 곳)은 빈 칸으로 둔다.
"""

import sys
from pathlib import Path

from PIL import Image, ImageDraw


def shots(root: Path) -> dict[tuple[str, str], Path]:
    found = {}
    for png in root.glob("*/*/takeScreenshot/*.png"):
        found[(png.parents[1].name, png.stem)] = png
    return found


def main() -> None:
    ios, android, out = (Path(a) for a in sys.argv[1:4])
    out.mkdir(parents=True, exist_ok=True)
    a, b = shots(ios), shots(android)
    for key in sorted(set(a) | set(b)):
        panes = []
        for src in (a, b):
            if key in src:
                im = Image.open(src[key]).convert("RGB")
                im.thumbnail((480, 1040))
            else:
                im = Image.new("RGB", (480, 1040), "#dddddd")
                ImageDraw.Draw(im).text((20, 20), "(없음)", fill="black")
            panes.append(im)
        sheet = Image.new(
            "RGB",
            (panes[0].width + panes[1].width + 12, max(p.height for p in panes)),
            "white",
        )
        sheet.paste(panes[0], (0, 0))
        sheet.paste(panes[1], (panes[0].width + 12, 0))
        sheet.save(out / f"{key[0]}__{key[1]}.jpg", quality=82)
    print(f"{len(set(a) | set(b))}장 → {out}")


if __name__ == "__main__":
    main()
