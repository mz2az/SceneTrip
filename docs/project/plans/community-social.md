# 커뮤니티 보강 — 좋아요·댓글·게시판·글 검색·커서 페이지

- **작성일**: 2026-11-09
- **티켓**: MZ2AZ-538 (2026-11-09 댓글로 범위 추가)
- **상태**: 진행
- **앞선 문서**: [community-post.md](./community-post.md)(여행후기), [web-proto-apply.md](./web-proto-apply.md) §3 B6
- **결정 뒤집기**: 2026-10-03 팀 회의 「여행후기 하나」 → 자유게시판을 다시 연다(2026-11-09 사용자 위임으로 진행). 옛 앱의 뜻은 지킨다(§2-3)

---

## 1. 왜

웹 시안에서 글 끝 👍 좋아요·댓글, 목록의 좋아요·댓글 수, 「여행후기 · 자유게시판」 글자 탭, 글 검색, 무한 스크롤을 넣었다. 서버에 없어서
시험용 웹에서는 숨겨 두었다(MZ2AZ-539). 이 일로 서버를 채우고 웹 2단계(MZ2AZ-544)에서 붙인다.

## 2. 계약 (1.24.0 — 모두 더하기만, 옛 앱의 요청·답은 그대로)

### 2-1. 좋아요

- `PUT /posts/{postId}/like` → 204, `DELETE /posts/{postId}/like` → 204. **멱등**(두 번 눌러도 하나). 없는 글·내려진 글·차단한 사람의 글은 404 `POST_NOT_FOUND`.
- 누가: **로그인한 사용자**(토큰, 401 `SIGN_IN_REQUIRED`). 처음엔 비회원(`X-Install-Id`)도 받으려 했으나, 목록·상세에서 `liked` 를 채우려면 읽을 때마다 설치 번호로 계정을 찾아야 하고 처음 보는 설치면 비회원 계정이 생긴다 — 읽기만 해도 계정이 쌓이지 않게 토큰으로 한정했다(2026-11-09).
- `TripPostSummary`·`TripPostDetail` 에 `likeCount`(int), `commentCount`(int), `liked`(bool, 이 요청자가 눌렀는가).

### 2-2. 댓글

- `GET /posts/{postId}/comments?limit&offset` → `TripPostCommentList { items, total }`. **오래된 순**(대화 순서). 누구나 읽는다.
- `POST /posts/{postId}/comments { body }` → 201 `TripPostComment`. **가입한 사용자만**(글쓰기와 같다, 401 `SIGN_IN_REQUIRED`). 1~500자, 쓸 수 없는 말은 400 `TEXT_NOT_ALLOWED`.
- `DELETE /posts/{postId}/comments/{commentId}` → 204. 내 댓글만. 남의 것·없는 것은 404 `COMMENT_NOT_FOUND`.
- `TripPostComment { id, body, author?(TripPostAuthor), createdAt, isMine }`.
- 로그인했으면 내가 차단한 사람의 댓글은 목록·수에서 빠진다. 글이 내려지면 댓글도 안 보인다. 탈퇴하면 그 사람의 댓글은 지운다(글과 같다, MZ2AZ-466).

### 2-3. 게시판

- `TripPostBoard` = `review`(여행후기) | `free`(자유게시판). 글쓰기에 `board`(없으면 `review`). 요약·상세에 `board`.
- `GET /posts?board=` — **없으면 `review` 만** 준다. 옛 앱은 게시판을 모르고 모든 글을 「여행후기」 로 보이므로, 자유게시판 글이 옛 앱에 섞이지 않게 한다.
- 자유게시판 글도 사진·코스를 붙일 수 있다(규칙 같음).

### 2-4. 글 검색

- `GET /posts?q=` — 제목·본문 부분 일치(대소문자 무시), 2자 이상(아니면 400). 게시판 조건과 함께 쓴다.

### 2-5. 커서 페이지

- `GET /posts?cursor=` · 응답 `nextCursor`(더 없으면 null). 커서는 마지막 글의 `(createdAt, id)` 를 감싼 불투명 문자열이다 — 스크롤 중에 새 글이
  올라와도 겹치거나 건너뛰지 않는다. `offset` 은 옛 앱을 위해 그대로 받는다(둘 다 오면 400).

### 2-6. 신고·차단 대상에 댓글

- `ReportTargetType` 에 `comment`. 신고·차단(`POST /reports`, `POST /me/blocks`)의 `targetId` 가 댓글 번호면 그 댓글·그 작성자.
- 옛 앱은 `comment` 를 보내지 않으므로 영향 없다. **응답에 `ReportTargetType` 가 나오는 곳**이 있으면 옛 앱이 모르는 값을 받을 수 있다 — 구현 때 확인한다.

## 3. DB (V44 — V43 은 편의시설 이름 검색 색인)

- `community_post.board TEXT NOT NULL DEFAULT 'review' CHECK (board IN ('review','free'))` — 기존 글은 전부 여행후기.
- `community_post_like (post_id → CASCADE, user_id → app_user CASCADE, created_at, PK(post_id, user_id))`.
- `community_comment (id, post_id → CASCADE, user_id → app_user CASCADE, body 1~500, created_at, removed_at)` + 색인 `(post_id, created_at, id)`.
- 목록 정렬 색인에 게시판: `(board, created_at DESC, id DESC) WHERE removed_at IS NULL`.
- 신고 대상 CHECK 가 있으면 `comment` 를 더한다.

## 4. 시험

| 갈래 | 무엇 |
| --- | --- |
| 단위 | 좋아요 비로그인 401, 댓글 쓰기 로그인 필요(401), 1자 미만·500자 초과 400, `q` 1자 400, `cursor`+`offset` 400, `board` 잘못된 값 400 |
| 통합(실제 PostgreSQL, 컨텍스트 기동 포함) | 좋아요 멱등·수, `liked`, 댓글 오래된 순·수, 내 댓글만 지움, 차단한 사람 댓글 빠짐, 내린 글의 댓글 안 보임, 탈퇴 시 댓글 지움, 게시판 없는 목록은 review 만, 커서로 끝까지 겹침·빠짐 없음(사이에 새 글), 검색, V43 마이그레이션이 기존 글을 review 로 |
| 실제 요청 | `just update scene-api` 뒤 curl — 정상과 약속한 오류들 |

## 5. 웹 (MZ2AZ-544)

`protoPostSocial`(브라우저 저장)을 서버 호출로 바꾸고, `showsLocalOnlyFeatures` 로 숨겼던 게시판 탭·검색·좋아요·댓글을 연다.
