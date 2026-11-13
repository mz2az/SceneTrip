# 운영자 내리기와 신고 알림 (MZ2AZ-569)

- **상태**: 구현 중(2026-11-14)
- **배포**: 서버는 PRD 로 배포한다. 웹은 dev 에서 사용자가 확인한 뒤 main 으로 올린다.
- **왜**:
  - 11/11 멘토가 커뮤니티 글 26번을 신고했다.
    - SigNoz 알람은 Slack `#신고-알림` 에 「1건」만 알렸다. 무슨 글인지와 링크가 없었다.
    - 알림에 붙은 링크는 `http://localhost:8080` 이었다.
  - 자유게시판에 아무 글자로 된 글(22·23·24)이 올라왔다. 그런데 글은 작성자만 지울 수 있다.
  - 사용자 요청: 「내 학교 계정을 운영자 계정으로, 헛글을 지울 권한이 있어야 한다.」
- **앞 계획**: [community-moderation.md](community-moderation.md) — 신고, 차단, `removed_at`(「운영자가 내린 때」, 칸만 있고 쓰는 곳이 없었다).

## 1. 운영자 — 누가인가 (`moderation/OperatorDirectory`)

시험은 §1~§4 만 보고 쓴다.

- **설정**: `scenetrip.moderation.operator-email-sha256` 에 소문자 16진수 64자 목록을 둔다.
  - 각 값은 `sha256(lower(trim(email)))` 이다. 메일 원문은 저장소에 적지 않는다(V47 의 팀원 메일과 같은 방식).
  - 처음 넣을 값은 정승길 학교 구글 계정 메일의 해시다(원문은 저장소에 적지 않는다 — 공개 저장소다).
  - 환경변수 `SCENETRIP_MODERATION_OPERATOR_EMAIL_SHA256` 에 쉼표로 이어 써도 된다. 비어 있으면 운영자는 없다.
- **판정** `isOperator(UUID user)`: 그 계정에 붙은 `user_identity` 중 `email` 이 있는 것을 본다. 그 메일의 해시가 목록에 하나라도 있으면 `true` 다.
  - `user` 가 `null` 이면 `false` 다.
  - 메일이 없거나 계정이 없어도 `false` 다.
- **같은 판정을 쓰는 곳**: `GET /me` 의 `isOperator`(없으면 `false`)와 §2 의 허가.

## 2. 내리기 — `POST /moderation/removals` (계약 1.30.0)

| 경우 | 응답 |
| --- | --- |
| 토큰 없음 · 비회원 | 401 `SIGN_IN_REQUIRED` (다른 창구와 같은 `requireMember`) |
| 운영자가 아님 | 403 `OPERATOR_ONLY` |
| 대상 행이 없음(지워졌다) | 404 `REMOVAL_TARGET_NOT_FOUND` |
| 내렸다, 또는 이미 내려 있다 | 204 |

- **무엇을 바꾸나**: 대상 행의 `removed_at` 을 `now()` 로 채운다. 이미 차 있으면 그대로 둔다. 행은 지우지 않는다 — 되돌릴 수 있다.

  | `targetType` | 표 |
  | --- | --- |
  | `post` | `community_post` |
  | `comment` | `community_comment` |
  | `review` | `review` |

- **기록** `moderation_action`(V48):
  - 칸: `id`, `operator_id`(→ `app_user`, 지우면 NULL), `target_type`, `target_id`, `action`(지금은 `'remove'`), `excerpt`(본문 앞 200자, 글은 「제목 — 본문」), `created_at`
  - **새로 내렸을 때만** 한 줄을 남긴다. 이미 내려 있으면 남기지 않는다.
  - 이때 서버 로그 `[운영자] 내림 target={} id={} operator={}` 를 한 줄 남긴다(warn).
- **보이는 곳**: 내린 것은 목록·상세·평균·사진첩·댓글에서 빠진다. 이미 `removed_at IS NULL` 로 거르고 있으니 여기서 바꿀 것은 없다.
- **게이트웨이**: PRD nginx 허용 경로(`platform/helm/scenetrip/templates/config.yaml`)에 `moderation` 을 더한다.

## 3. 신고 알림에 내용을 싣는다 — Slack 웹훅 (`moderation/ReportNotifier`)

- **설정**: `scenetrip.moderation.report-slack-webhook-url`(환경변수 `SCENETRIP_REPORT_SLACK_WEBHOOK_URL`).
  - **비밀값**이다. 저장소에 두지 않고 Secrets Manager `/scenetrip/<env>/scene-api` 에 둔다.
  - 비어 있으면 보내지 않는다.
- **언제**: `ReportStore.report` 가 `RECORDED` 일 때만. 이미 신고한 글(`ALREADY`)에는 다시 보내지 않는다.
- **어떻게 보내나**:
  - 신고 응답(204)을 기다리게 하지 않는다. 별도 스레드에서 보낸다.
  - 실패하면 로그만 남긴다(`[신고] 알림 실패 …`). 신고 응답은 그대로 204 다.
- **보내는 본문**: Slack `{"text": …}`. 줄은 다음 순서다. 없는 값의 줄은 뺀다.

  1. `🚨 신고 접수 — <종류> #<id> · 이유 <reason 한국어>` — 종류는 글·댓글·리뷰
  2. `작성자: <닉네임>`(탈퇴했으면 `(탈퇴)`)
  3. `내용: <본문 앞 200자>` — 신고 당시의 사본(`content_report.excerpt`)
  4. `설명: <신고자가 쓴 설명>`
  5. `바로가기: <앱 주소>` — 아래 표. 앱 주소는 `scenetrip.moderation.app-base-url`(기본 `https://app.scenetrip.io`)

  | 종류 | 앱 주소 |
  | --- | --- |
  | 글 | `/community/post/<id>` |
  | 댓글 | 그 댓글이 달린 글의 `/community/post/<postId>` |
  | 리뷰 | 장소 리뷰는 `/search/place/<placeId>`. 편의시설 리뷰는 링크 없음 |

- **사용자 글 이스케이프(검증 뒤 추가)**: 작성자·내용·설명의 `&`·`<`·`>` 는 `&amp;`·`&lt;`·`&gt;` 로 바꾼다. 그대로 두면 Slack 이 `<!channel>`·`<링크|글>` 문법으로 해석한다.
- **웹훅 주소**: 처음 한 번만 읽는다. 비었거나 https 가 아니면 알림을 끄고, 주소는 로그에 적지 않는다.
- **함께 두는 것**: SigNoz 알람용 로그 `[신고] 접수 …` 는 그대로 둔다. 알림 경로가 둘이다.

## 4. 웹

- **`rules.ts`**:
  - `postMenuActions({ isMine, onServer, hasAuthor, isOperator })` — 자기 글이 아니고 `isOperator` 면 끝에 `'remove'` 를 더한다.
  - 리뷰·댓글의 기본 메뉴도 같다. 새 순수 함수 `menuActions({ canBlock, isOperator })` 가 `['report', ('block'), ('remove')]` 를 준다.
- **`client.ts`**: `remove(target)` 가 `POST /moderation/removals` 를 부른다(`targetType`, `targetId`).
- **`controller.ts`**:
  - `askRemove(target)` 은 확인 창을 띄운다(`confirmingRemove`).
  - `remove(target)` 은 성공하면 `true` 를 주고 `events.changed()` 를 부른다. 그러면 목록이 다시 받는다.
  - 결과 문구 `removeMessage(outcome)`:

    | `outcome` | 문구 |
    | --- | --- |
    | `removed` | 내렸어요 |
    | `notOperator` | 운영자만 할 수 있어요 |
    | `gone` | 이미 없는 글이에요 |
    | `failed` | 내리지 못했어요. 잠시 뒤 다시 해 주세요 |

- **메뉴 글**: 「내리기(운영자)」 / 「Remove (operator)」. 파괴적 동작 색이다. 확인 창은 「이 글을 내릴까요? 다른 사람에게 보이지 않게 돼요.」
- **운영자 여부**: `useAccount().me?.isOperator === true`. 비회원이나 `/me` 전에는 `false` 다.

## 5. SigNoz 알림 링크

`platform/helm/signoz/values-{prd,dev}.yaml` 에 `signoz.env.signoz_alertmanager_signoz_external__url` 을 둔다.

- PRD: `https://signoz.scenetrip.io`
- DEV: `https://signoz-dev.scenetrip.io`

차트 기본값이 `http://localhost:8080` 이라 Slack 링크가 열리지 않았다.

## 6. 사람이 할 일

1. 서버가 PRD 에 배포된 **뒤에** Slack 웹훅 주소를 Secrets Manager `/scenetrip/prd/scene-api` JSON 에 `SCENETRIP_REPORT_SLACK_WEBHOOK_URL` 로 더한다.
   - 이미 있는 키는 그대로 둔다.
   - 웹훅은 SigNoz 의 `slack-신고` 채널과 같은 것을 써도 된다.
2. `aws-deploy` apply 를 한 번 더 돌린다. 비밀값은 배포 때만 파드에 들어간다.

   키를 코드보다 먼저 넣으면 배포가 실패한다. 배포가 허용 목록 밖 키를 거른다(`tools/aws/config.py`).

## 7. 나중에 — 메일(support@scenetrip.io)

AWS SNS 토픽에 support@ 를 구독시키고 서버가 거기로 보내는 방법이다. 지금은 하지 않는다. 다음이 필요하기 때문이다.

- 관리자 자격으로 토픽 만들기(`platform/terraform/alerts/template.json`)
- 서버 역할에 `sns:Publish` 를 더하는 bootstrap 적용
- AWS SDK `sns` 의존성 추가

Slack 로 먼저 받고, 메일이 필요하면 따로 티켓으로 한다.

## 8. 하지 않는 것

- 운영자 화면(신고 목록, 내린 것 되돌리기). 되돌리기는 SQL 로 `removed_at` 을 비운다.
- 사용자 정지
- iOS 앱 — 웹뷰로 간다.
