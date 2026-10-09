# 아키텍처 결정 기록 (ADR)

결정 하나당 파일 하나. 순번을 붙여 `NNNN-kebab-제목.md` 로 둔다.

```bash
just adr-new "Bazel 원격 캐시 도입"
just adr-list
```

## 규칙

- ADR 은 **추가만 한다.** 결정을 바꾸려면 기존 것을 대체하는 새 ADR 을 쓰고, 옛 것의
  `status` 와 `superseded-by` 를 갱신한다. 역사를 고쳐 쓰지 않는다.
- **대체(supersede)와 보정(amend)은 다르다.** 결정 자체가 뒤집히면 대체다. 결정은
  유효한데 배경에 적힌 전제만 달라졌다면 보정이며, 옛 ADR 의 `amended-by` 에 새 번호를
  적고 본문 맨 위에 안내 인용문 한 단락을 덧붙인다 — 예:
  [0001](./0001-bazel-as-the-single-build-system-and-just-as-the-single-command-surface.md)
  은 [0002](./0002-product-stack-spring-python-native-mobile.md) 가 보정했다.
- **초기 세팅 기간의 예외.** 저장소 골격을 세우며 생긴 사실오류(쓰지도 않는 언어를
  전제로 적어 둔 것 등)는 그 기간에 한해 제자리에서 고쳤다. 틀린 전제를 남겨 두는
  쪽이 더 해롭기 때문이다. **초기 세팅이 끝난 뒤에는 이 예외를 쓰지 않는다** —
  이후의 모든 변경은 새 ADR 로만 한다.
- 상태: `proposed` → `accepted` → (`superseded` | `deprecated`)
- 기각한 대안과 그 이유를 반드시 남긴다. 나중에 읽는 사람에게 필요한 건 그 부분이다.
- 검증 절을 포함한다 — 이 결정이 옳았는지 어떻게 알 것인가.

`template.md` 가 `just adr-new` 가 렌더링하는 시작점이다.

최근 결정: [0022 — 편의시설은 출처 번호를 여럿 갖고 지우지 않는다, 영업 여부는 상가정보가 판단](0022-poi-multiple-source-ids-and-never-delete.md),
[0021 — 편의시설 네이버 링크는 장소 번호로, 링크용 검색만 비공식으로 되살린다](0021-poi-naver-link-by-place-id-lookup.md),
[0020 — 편의시설 카드는 공공데이터와 네이버 지도 링크로, 비공식 호출을 내린다](0020-poi-card-public-data-and-naver-link.md),
[0019 — DEV API 를 인터넷 전체에 연다](0019-dev-api-is-public.md),
[0018 — 로그인은 구글·애플 소셜 로그인과 JWT](0018-social-login-with-jwt.md).
