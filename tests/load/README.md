# tests/load

처리량·지연·장시간 테스트. k6 시나리오는 어떤 게이트에도 없고 의도적으로만 실행한다(`fake/` 의 단위 시험만 빠른 레인에 든다). 계획: [load-test.md](../../docs/project/plans/load-test.md) (MZ2AZ-502),
기록: [docs/qa/loadtest/](../../docs/qa/loadtest/README.md).

| 파일 | 내용 |
| --- | --- |
| `BUILD.bazel` | `:k6` — MODULE.bazel 에 버전·SHA-256 을 고정한 k6(맥 arm64·리눅스 amd64) |
| `smoke.js` | 연기 확인 — 1 명 10 초, 대상과 붙어 있는지만 |
| `scenario.js` | 실제 앱 흐름 4 개(둘러보기 60·담기 15·여행 15·챗봇 10), 생각하는 시간·도착률·무작위 데이터. `PROFILE=smoke·baseline·stress·spike·soak`, 회원 흐름은 `TOKENS` + 시험 모드 |
| `mint_google_tokens.py` | 시험 회원의 구글 ID 토큰 묶음(`[{sub, idToken, nonce}]`)과 가짜에 줄 공개 JWK. 비밀키 파일은 저장소 밖에 둔다 — `just load-tokens` |
| `fake/` | 가짜 바깥 서버([`platform/helm/scenetrip/files/fake_external.py`](../../platform/helm/scenetrip/files/fake_external.py))와 토큰 도구의 단위 시험(`unit`, 빠른 레인) |

실행: `just load-run smoke.js -e BASE_URL=https://api-dev.scenetrip.io/v1`

가짜 바깥 서버를 이 기계에서 띄우기: `just load-fake`(설정은 `FAKE_*` 환경변수 — 파일 머리말의 표). 시험: `just test //tests/load/fake:unit_test`

레인 규율과 태그 규칙은 [tests/README.md](../README.md) 참조.
