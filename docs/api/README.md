# API 문서

SceneTrip API 를 사용하는 쪽을 위한 안내: 인증, 페이지네이션, 오류 의미,
요청 제한, 버전 정책.

**명세는 여기가 아니라 [`contracts/`](../../contracts/README.md) 에 있습니다.**
이 디렉터리는 API 를 *어떻게 쓰는지* 설명하고, `contracts/` 는 API 를 *정의하며*
빌드 입력이 된다.

| 문서 | 목적 |
| --- | --- |
| [`auth.md`](./auth.md) | 인증·인가 모델 — v1 은 인증이 없고 `X-Device-Id` 만 쓴다 |
| [`errors.md`](./errors.md) | 오류 형식과 상태 코드 의미 |
| [`scene-api-guide.md`](./scene-api-guide.md) | scene-api 사용 가이드 — 화면별 호출 패턴과 알려진 한계 |
| `<서비스>-guide.md` | 서비스가 늘면 같은 형태로 하나씩 |

**버전 정책은 여기 두지 않는다.** [`contracts/README.md`](../../contracts/README.md)
가 이미 규정한다 — 파괴적 변경은 메이저 버전을 올려 새 파일로 내고, 제자리 수정은
금지다. 같은 규칙을 두 곳에 적으면 한쪽만 고쳐져 어긋난다.

```bash
just docs-api    # contracts/ 로부터 레퍼런스 문서 생성
```
