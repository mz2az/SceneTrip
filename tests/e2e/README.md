# tests/e2e

사용자 대면 표면을 관통하는 전체 스택 테스트. 'e2e' 태그이며 느려서 별도 레인에서 돈다 — 빠른 레인·통합 레인·CI 에 안 들어간다.
레인 규율과 태그 규칙은 [tests/README.md](../README.md) 참조. 계획: [api-e2e.md](../../docs/project/plans/api-e2e.md) (MZ2AZ-496).

## 돌리기

```
just image scene-api   # 지금 코드로 이미지를 굽는다(평소 로컬 서버는 그대로)
just e2e-up            # scene-api-e2e 를 kind 에 띄우고 localhost:18082 로 연다
just test-e2e          # 전부. 파일 하나만: just test-e2e --test_env=E2E_ONLY=test_auth
just e2e-down          # 내린다
```

## 무엇이 진짜이고 무엇이 가짜인가

| 진짜 | 가짜(시험이 호스트 0.0.0.0:18900 에 띄움) |
| --- | --- |
| 컨테이너로 뜬 scene-api(`platform/kubernetes/scene-api-e2e`), PostgreSQL, MinIO | 카카오 길찾기 `/kakao` · 구글 JWKS `/google` · 네이버 `/naver` · 챗봇 에이전트 `/guide-agent` |

로그인은 진짜 `POST /auth/google` 을 탄다 — 시험이 만든 RSA 키의 공개키를 가짜가 JWKS 로 내고, 같은 키로 ID 토큰을 서명한다.

| 파일 | 내용 |
| --- | --- |
| `support.py` | 가짜 바깥 서버(`FAKE`) · RS256 서명 · `call()` · `sign_in_google()` · `E2ETestCase` |
| `run_all.py` | `test_*.py` 를 한 프로세스에서 돈다(가짜 서버 포트가 하나라서). 파일 잠금으로 실행이 겹치지 않는다 |
| `test_*.py` | 기능 묶음별 시험 |
