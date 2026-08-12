# 엔지니어링 문서

이 저장소에서 일하는 방법.

| 문서 | 목적 |
| --- | --- |
| `onboarding.md` | 첫날: 세팅, 첫 빌드, 첫 변경 |
| `bazel-guide.md` | 빌드 그래프, 타깃 이름, 태그, 격리, 문제 해결 |
| `just-guide.md` | 명령 목록과 확장하는 법 |
| `git-workflow.md` | 브랜치, 커밋, 리뷰, 머지 |
| `coding-standards.md` | 언어별 컨벤션과 품질 기준 |
| `troubleshooting.md` | 자주 겪는 빌드·테스트 실패와 해결책 |
| `ios-to-android-port.md` | iOS 화면을 안드로이드로 옮길 때 걸리는 함정 |

규칙의 정본은 [AGENTS.md](../../AGENTS.md) 이고, 여기 문서는 그것을 풀어 설명하고
예를 든다. 둘이 어긋나면 AGENTS.md 가 이기고 문서를 고친다.

## 빠른 시작

```bash
just setup     # 세팅
just doctor    # 도구 확인
just --list    # 명령 탐색
just check     # 모든 변경이 통과해야 하는 게이트
```
