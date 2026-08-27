# brand — 마스코트 피노와 온보딩 캔버스

캔버스: <https://claude.ai/code/artifact/a2aeef5c-9355-4881-8794-928526d10d45>
결정 문서: [prd/onboarding.md](../../prd/onboarding.md)

| 파일 | 무엇 |
| --- | --- |
| `Main.dc.html` | 스플래시 (CSS 로 실제로 움직인다) |
| `Mascot.dc.html` | 마스코트 시트 — 만든 과정, 포즈 다섯, 이름, 색 |
| `Tutorial1–4.dc.html` | 사용법 넉 장 |
| `canvas.json` | 아트보드 배치와 메모 |
| `_gen.mjs` | 튜토리얼 넉 장을 찍어 내는 스크립트. **넉 장은 여기서 고친다** |
| `scenetrip-mascot.html` | 발행본 (씨앗 스크립트가 만든다 — 직접 고치지 않는다) |

## 고치는 순서

```
1. _gen.mjs 나 Main/Mascot.dc.html 을 고친다
2. node _gen.mjs                       # 튜토리얼을 다시 찍는다
3. seed-canvas.mjs 로 다시 씨앗을 심는다
4. 같은 URL 로 다시 발행한다
```

## 앱이 정본이다

이 캔버스는 앱보다 먼저 그려졌지만, 지금은 **앱을 따라간다.** 시뮬레이터에서 보고 고친
것(꼬리 방향, 앞발에 붙인 팔, 말풍선 자리, ③ 마스코트 크기, 흰 바탕에서의 몸 색)이
여기에도 반영돼 있다. 둘이 어긋나면 앱을 믿는다.

좌표계는 앱과 같다 — `viewBox="0 0 130 160"`, 몸통은 `PinImage.numbered` 의 물방울
(머리 원 r14 @ (19,17), 꼬리 (19,45))을 세 배로 키운 것.
