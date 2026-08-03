# API 문서

SceneTrip API 를 사용하는 쪽을 위한 안내: 인증, 페이지네이션, 오류 의미,
요청 제한, 버전 정책.

**명세는 여기가 아니라 [`contracts/`](../../contracts/README.md) 에 있습니다.**
이 디렉터리는 API 를 *어떻게 쓰는지* 설명하고, `contracts/` 는 API 를 *정의하며*
빌드 입력이 된다.

| 문서 | 목적 |
| --- | --- |
| `versioning.md` | 호환성 보장과 폐기 일정 |
| `auth.md` | 인증·인가 모델 |
| `errors.md` | 오류 형식과 상태 코드 의미 |
| `<서비스>-guide.md` | 서비스별 사용 가이드와 예제 |

```bash
just docs-api    # contracts/ 로부터 레퍼런스 문서 생성
```
