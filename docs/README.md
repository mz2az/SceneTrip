# SceneTrip 문서

모든 문서에는 자리가 하나씩만 있다. 새 문서를 만들기 전에 여기서 자리를 정한다 —
저장소 루트에 마크다운을 흩뿌리지 않는다.

| 디렉터리 | 내용 | 주로 읽는 사람 |
| --- | --- | --- |
| [product/](product/) | 비전, PRD, 요구사항, 페르소나, 로드맵 | 전원 |
| [architecture/](architecture/) | 시스템 설계, 다이어그램, 데이터 모델 | 엔지니어 |
| [architecture/adr/](architecture/adr/) | 아키텍처 결정 기록(ADR) | 엔지니어 |
| [api/](api/) | API 사용 가이드와 버전 정책 | 사용하는 쪽 |
| [engineering/](engineering/) | 온보딩, Bazel, just, 컨벤션 | 기여자 |
| [installs/](installs/) | 로컬 환경 설치: Kubernetes(kind), SigNoz | 기여자 |
| [education/](education/) | 강의 자료와 슬라이드 | 강사, 신규 입사자 |
| [qa/](qa/) | 테스트 전략, 계획, 커버리지 정책 | 엔지니어, QA |
| [ops/](ops/) | 런북, SLO, 장애, 배포 | 온콜 |
| [ai/](ai/) | 에이전트 설계, 프롬프트, 평가 결과 | 에이전트 엔지니어 |
| [project/](project/) | 계획, 현황, 결정 로그, 회고 | 전원 |

명세는 문서가 **아니다.** protobuf·OpenAPI·AsyncAPI·JSON Schema 는 빌드 입력이므로
[`contracts/`](../contracts/README.md) 에 있다.

## 관례

- 파일명은 `kebab-case.md`. 읽는 순서가 중요할 때만 숫자를 앞에 붙인다.
- 디렉터리마다 실제 내용을 반영하는 `README.md` 인덱스를 둔다.
- 다이어그램은 마크다운 안의 Mermaid — 그래야 diff 가 된다.
- 존재하는 것을 현재형으로 쓴다. 계획은 `project/` 로 옮긴다.
- 코드와 문서가 어긋나면 같은 커밋에서 둘 다 고친다.
- 문서는 한글로 쓴다. `AGENTS.md` 와 `CLAUDE.md` 만 영문을 유지한다(AGENTS.md §8).

```bash
just docs-lint      # 스타일·링크 검사
just adr-new "<제목>"
just docs-serve
```
