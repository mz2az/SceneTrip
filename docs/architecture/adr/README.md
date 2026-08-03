# 아키텍처 결정 기록 (ADR)

결정 하나당 파일 하나. 순번을 붙여 `NNNN-kebab-제목.md` 로 둔다.

```bash
just adr-new "Bazel 원격 캐시 도입"
just adr-list
```

## 규칙

- ADR 은 **추가만 한다.** 결정을 바꾸려면 기존 것을 대체하는 새 ADR 을 쓰고, 옛 것의
  `status` 와 `superseded-by` 를 갱신한다. 역사를 고쳐 쓰지 않는다.
- 상태: `proposed` → `accepted` → (`superseded` | `deprecated`)
- 기각한 대안과 그 이유를 반드시 남긴다. 나중에 읽는 사람에게 필요한 건 그 부분이다.
- 검증 절을 포함한다 — 이 결정이 옳았는지 어떻게 알 것인가.

`template.md` 가 `just adr-new` 가 렌더링하는 시작점이다.
