# 우리 S3 사진의 작은 사진 — 서버 배경 작업이 만들고, 목록·카드는 작은 것을 (MZ2AZ-561)

- **작성일**: 2026-11-13
- **티켓**: MZ2AZ-561
- **상태**: 구현 — 서버(V46·`PhotoThumbnailJob`·지우기·고아 청소·응답)·촬영지 도구(`just place-thumbnails`, 아직 올리지 않음 — TSV 는 머리줄뿐)·웹이 브랜치 `MZ2AZ-561-photo-thumbnails` 에 있다. 배포·올리기·실측(§6)은 아직
- **결정 기록**: [ADR 0030](../../architecture/adr/0030-photo-thumbnails-by-server-job.md)(제안)
- **앞선 문서**: [poster-disk-cache.md](./poster-disk-cache.md)(MZ2AZ-507, 포스터만 — 우리 S3 사진은 서버 쪽에서 따로 정하기로 함),
  [place-photo-credit.md](./place-photo-credit.md)(`place_image.thumbnail_url`, V42), [post-photo-deletion.md](./post-photo-deletion.md)(지울 목록·고아 청소)

---

## 1. 왜

| 사진 | 지금 | 그리는 크기(웹) |
| --- | --- | --- |
| 후기·리뷰 사진(사용자 버킷 `scenetrip-user-media-…`, 비공개, 한 시간 서명 주소) | 앱이 긴 변 2048px JPEG q0.82 로 올림(MZ2AZ-363) → 장당 약 700KB | 커뮤니티 목록 76×76, 홈 후기 줄 44×44, 리뷰 줄·사진첩 칸 |
| 촬영지 대표 사진(공용 버킷 `scenetrip-media-prod` `place/…`, 공개) | 1280×720, 장당 0.33~0.5MB | 홈 「오늘의 성지」 350×96, 「도시의 성지」 148×96, 검색 줄 54×54, 담기 44×44 |
| 관광공사 사진 | `thumbnail_url`(약 300px) 이미 있음 | 사진첩 칸은 이미 작은 것(`photoGalleryRules.imageUrl`) |

app.scenetrip.io 첫 방문(모바일 화면)에서 바깥 이미지가 2.76MB, 그중 촬영지 사진이 장당 약 0.3MB 다. 우리 S3 사진에는 작은 사진이 없다.

코드에서 확인한 사실(2026-11-13):

- 사용자 사진은 `POST /uploads` 서명 PUT → `uploads/tmp/…` → 글·리뷰 저장 때 서버가 `posts/<이름>`·`reviews/<이름>` 으로 옮긴다(`PostsController`·`ReviewsController`, `PhotoStorage.move`). 키는 `community_post_photo.storage_key`·`review_image.storage_key`.
- scene-api 파드의 권한은 사용자 버킷의 `uploads/tmp/*`·`reviews/*`·`posts/*` 에 Get/Put/Delete(`platform/terraform/bootstrap/template.json`). **공용 버킷 권한은 없다.**
- 주간 고아 청소(`OrphanPhotoSweep`)는 `reviews/`·`posts/` 아래에서 DB 가 가리키지 않는 7 일 지난 파일을 지운다 — 새 파일을 그 아래 두면 DB 가 가리켜야 한다.
- `place_image.thumbnail_url` 칸은 이미 있다(V42). 적재 SQL(`seed/candidates.sql`)이 `place_image_credits.tsv` 로 채운다. 사진첩(`Photo.thumbnailUrl`)은 이 칸을 내준다. 목록용 `imageUrl`(PlaceSummary·CartItem·CourseItem·MarketItem·TripPostCourseStop·GuidePlanStop)과 `CourseSummary`·`MarketCourseSummary.thumbnailUrl` 은 `pim.url`(원본)이다.
- MODULE.bazel 에 이미지 라이브러리가 없다. JDK `javax.imageio` 는 JPEG·PNG 를 읽고 JPEG 를 쓴다(WebP·HEIC 는 못 한다). scene-api 메모리는 1Gi(상한 가까이 쓴다).
- 앱 세 벌(iOS·Android·웹)은 언제나 JPEG 로 올린다. 서버는 PNG·HEIC·WebP 도 받는다.

## 2. 결정

### 2-1. 어디서 만드나 — scene-api 배경 작업(사용자 사진), 한 번 도는 도구(촬영지 사진)

**사용자 사진:** scene-api 안 1 분 작업 `PhotoThumbnailJob` 이 작은 사진이 없는 줄을 골라 만든다. `StorageDeletionJob` 과 같은 모양 —
트랜잭션 advisory lock(`hashtext('photo_thumbnail')`)으로 한 대만, 한 차례에 최대 20 장, 한 장씩.

1. `review_image`·`community_post_photo` 에서 `thumbnail_key IS NULL AND thumbnail_attempts < 3` 인 줄을 새것부터.
2. 원본을 받는다(`PhotoStorage` 에 `read(key)` 추가) → `ImageIO` 로 **줄여 읽기**(`ImageReadParam.setSourceSubsampling`, 2048 → 1024 쯤에서 풀어 메모리 약 4MB) → 긴 변 720 으로 줄여 JPEG q0.80 로 쓴다(EXIF 없음).
3. 올린다(`PhotoStorage.put(key, bytes, "image/jpeg")` 추가) → 같은 줄에 `thumbnail_key` 를 적는다. 그 사이 글이 지워졌으면 올린 파일을 지울 목록에 넣는다.
4. 실패(못 읽는 형식·CMYK·저장소 오류)는 `thumbnail_attempts + 1`. 3 번 실패하면 더 하지 않는다 — 응답은 원본으로 간다.

새 글의 작은 사진은 저장 뒤 1 분 안에 생긴다. 그동안 응답의 작은 사진 칸은 `null` 이고 앱은 원본을 그린다. **같은 작업이 이미 있는 사진의 백필이다** — 배포하면 옛 사진부터 차례로 채운다(따로 도는 백필 없음).

**촬영지 사진:** 공용 버킷은 파드가 쓸 수 없고, 사진은 적재 데이터로 들어온다. 그래서 사람이 한 번 도는 도구 — 새 레시피 `just place-thumbnails`(`tools/just/` 에 추가, scene-api 의 `java_binary`, 위와 **같은 줄이기 클래스**) — 가:

1. `seed/candidates.csv` 의 `place_image_url` 중 `scenetrip-media-prod` 주소만 모아(중복 제외 약 960 장) 공개 주소로 받고,
2. 같은 규칙으로 줄여 `place/thumb-720/<원래 경로에서 place/ 뒤>` 에 **덮어쓰기 없이**(`If-None-Match: *`) 올리고,
3. `seed/place_image_thumbnails.tsv`(`url`, `thumbnail_url`)를 쓴다.

적재 SQL 은 출처 TSV 를 적용한 **뒤에** 이 TSV 로 `thumbnail_url IS NULL` 인 줄만 채운다(관광공사 썸네일을 덮지 않는다). 올리는 일은 공용 버킷 쓰기라 사용자 승인 뒤 사람의 AWS 자격으로 돈다. 장면 스틸(`scene/…`)은 이번에 하지 않는다.

**고르지 않은 것** — S3 이벤트 Lambda(새 배포 단위·IAM·Bazel 밖 코드, DB 에 결과를 알릴 길이 따로 필요), CloudFront/Lambda@Edge 변환(새 인프라, 사용자 사진은 서명 주소라 캐시가 안 됨), Cloudflare 이미지 변환(Pages 는 권호 계정, 서명 주소가 매번 달라 변환 수가 늘어남), 요청 때 만들기(첫 요청이 느리고 1Gi 파드에서 동시에 풀면 위험), 올릴 때 앱이 두 장 올리기(iOS 1.0.0 동결 — 옛 앱 사진은 안 생김). 자세한 이유는 ADR 0030.

### 2-2. 크기·형식·키

- **한 가지 크기: 긴 변 720px, JPEG q0.80, 키우지 않음.** 가장 큰 목록 칸(350×96, 사진은 cover)이 화면 2 배에서 700px 폭을 원한다. 1280×720 → 720×405 약 60~80KB, 2048 폰 사진 → 약 80~110KB 로 본다(구현 때 표본 20 장으로 실측해 이 문서에 적는다).
- **WebP 는 쓰지 않는다** — iOS 웹뷰(14+)는 그리지만 JDK 가 쓰지 못한다. 쓰려면 네이티브 라이브러리(JNI)를 MODULE.bazel 에 들여야 하고 밀폐 빌드가 깨진다. 이득(같은 화질 약 25%)이 2 주 안에 그 비용만 못하다.
- **키:** 사용자 `posts/thumb-720/<원래 이름>`·`reviews/thumb-720/<원래 이름>`(지금 권한 `posts/*`·`reviews/*` 안 — 인프라 변경 없음), 촬영지 `place/thumb-720/<…>`. 크기를 이름에 넣어 나중에 다른 크기를 같은 자리에 더할 수 있다.

### 2-3. 지우기·청소

- 글·리뷰를 지우거나 리뷰 사진을 뺄 때(`PostStore`·`ReviewStore`·`UserStore` 의 `INSERT INTO storage_deletion`) `thumbnail_key` 도 같이 올린다.
- `OrphanPhotoSweep` 의 「가리키는 키」 질의에 `thumbnail_key` 두 칸을 더한다 — 안 그러면 7 일 뒤 작은 사진이 고아로 지워진다.
- PRD 사용자 버킷은 버전 관리가 켜져 있어 지운 작은 사진도 30 일 뒤 영구 삭제된다(원본과 같다).

### 2-4. 계약 (1.29.0, 더하기만 — 모두 nullable, 없으면 원본)

| 스키마 | 더하는 칸 | 값 |
| --- | --- | --- |
| `ReviewPhoto` | `thumbnailUrl` | 작은 사진의 한 시간 서명 주소 |
| `Photo`(사진첩) | — (칸은 있음) | 리뷰 사진도 채운다. 설명만 고친다 |
| `TripPostSummary` | `coverThumbnailUrl` | 첫 사진의 작은 것 |
| `TripPostDetail` | `photoThumbnailUrls` | `photoUrls` 와 같은 길이·순서, 항목 nullable(목록 대표 사진이 만료돼 상세로 새 주소를 받을 때 쓴다) |
| `PlaceSummary`(→ `PlaceDetail`)·`CartItem`·`CourseItem`·`MarketItem`·`TripPostCourseStop`·`GuidePlanStop` | `imageThumbnailUrl` | 대표 사진 줄의 `place_image.thumbnail_url` |
| `CourseSummary`·`MarketCourseSummary` | — | `thumbnailUrl` 값을 `COALESCE(thumbnail_url, url)` 로(이름 그대로 작은 사진). 설명만 고친다 |

`imageUrl` 의 뜻은 바꾸지 않는다 — `PlaceDetail` 이 `PlaceSummary` 를 이어받아 iOS 1.0.0 상세 머리 사진이 그 칸을 쓴다.

### 2-5. 웹

작은 것을 쓰는 곳(공용 함수 `smallImage(thumb, full)` = `webUrl(thumb) ?? full` 하나로):

| 파일 | 칸 |
| --- | --- |
| `features/home/HomeCards.tsx` | 오늘의 성지(350×96) — 처음엔 원본(§8-2), §8-6 부터 작은 사진 |
| `features/home/HomeCitySpots.tsx` | 도시의 성지 카드(148×96) |
| `features/search/ui/Rows.tsx` | 검색 줄(54×54) |
| `features/profile/sheets/CartSheet.tsx`·`features/search/ui/CartSheet.tsx`·`features/route/editor/CartSheet.tsx` | 담기 줄 |
| `features/route/editor/StopRow.tsx`·`EditorPanel.tsx`·`SearchSheet.tsx`, `features/route/guide/guidePlan.ts`, `features/route/store.ts`·`draft.ts`·`bridge.ts` | 코스·가이드 일정 줄(모델에 칸을 실어 나른다) |
| `features/proto/protoParts.tsx`·`cityPlan.ts` 등 시안 화면 | 도시 촬영지 칸 |
| `features/community/model.ts`·`CoverPhoto.tsx`, `features/community/CommunityTab.tsx`·`HomeCards.tsx` 후기 줄 | 대표 사진 — `coverThumbnailUrl`, 만료 갱신 때 `photoThumbnailUrls[0]` |
| `features/reviews/ReviewRow.tsx`·`PhotoSections.tsx`(`ReviewPhotoThumbs`)·`profile/sheets/MyReviewsSheet.tsx` | 리뷰 사진 칸 — `ReviewPhoto.thumbnailUrl` |
| `features/reviews/photoGalleryRules.ts` | 이미 `thumbnailUrl` 을 칸에 쓴다 — 바꿀 것 없음(서버가 채우면 저절로) |
| `src/devMock.ts` | 가짜 응답에 새 칸 |

원본을 그대로 쓰는 곳: 확대 보기(`PhotoViewers.tsx`), 후기 본문 사진(`PostView.tsx`, 화면 폭), 촬영지 상세 머리·장면 사진, 시안 도시 머리(고정 사진이 없을 때만 쓰이는 대체).

서명 주소 만료 갱신(`renewal.ts` 의 `unsigned`)은 키로 같은 사진을 가른다 — 작은 사진은 키가 달라 따로 갱신된다. 그대로 둔다.

## 3. 데이터·마이그레이션

- `V46__photo_thumbnail.sql`(번호는 머지 때 dev 최신으로): `review_image`·`community_post_photo` 에 `thumbnail_key TEXT NULL`, `thumbnail_attempts INT NOT NULL DEFAULT 0`, 부분 인덱스 `WHERE thumbnail_key IS NULL AND thumbnail_attempts < 3`. 더하기만.
- `place_image` 는 칸을 더하지 않는다(V42 의 `thumbnail_url`).
- `seed/place_image_thumbnails.tsv` 새 파일, `seed/candidates.sql` 에 적용 줄과 끝 집계(`place_image(작은 사진)`).

## 4. 순서와 되돌리기

| 단계 | 무엇 | 어디 |
| --- | --- | --- |
| 1 | 계약 1.29.0 + 서버(마이그레이션·작업·응답·청소/지우기) | PR → dev → main(릴리스 PR) → `aws-deploy.yml` dev `apply` → DEV 에서 백필 끝까지 확인 |
| 2 | 촬영지 작은 사진 올리기(`just place-thumbnails`, 사용자 승인) + TSV 커밋 | 공용 버킷 새 경로만 쓴다(세 환경이 같이 보는 버킷이지만 덮어쓰기 없음) |
| 3 | 적재 | 로컬 `just data-load-local` → DEV `data-load` → PRD `data-load` |
| 4 | 웹 | 칸이 없으면 원본이라 서버와 순서 무관. dev 머지 = dev 웹, main 머지 = app.scenetrip.io |
| 5 | PRD 서버 | `aws-deploy.yml` prd `apply`(승인). DEV 에서 하루 지켜본 뒤 |

데모(11/28) 전 목표: 11/20 DEV 전부, 11/24 PRD.

되돌리기:

- 웹: 되돌리는 커밋 하나. 서버 칸이 남아 있어도 원본으로 돌아갈 뿐.
- 서버: 앞 SHA 로 `apply`. 마이그레이션은 더한 칸뿐이라 남겨도 옛 코드가 무시한다. **단, 옛 코드의 고아 청소는 `thumbnail_key` 를 몰라 7 일 뒤 작은 사진을 지운다** — 다시 올릴 때 `UPDATE … SET thumbnail_key = NULL, thumbnail_attempts = 0` 로 다시 만들게 한다(런북에 한 줄).
- 촬영지: TSV 를 비우고 다시 적재하면 `thumbnail_url` 이 원래대로(NULL). S3 의 `place/thumb-720/` 는 남겨도 해가 없다.

## 5. 시험

| 갈래 | 무엇 |
| --- | --- |
| 단위(`just test`) | 줄이기 — 긴 변 720·비율·키우지 않음·가로/세로·PNG 투명 → 흰 바탕·EXIF 없음·못 읽는 바이트는 실패(시험 안에서 `ImageIO` 로 그림을 만든다, 바이너리 고정 파일 없음). 응답 변환 — 키가 있으면 서명 주소, 없으면 `null`. `@WebMvcTest` 로 새 칸이 JSON 에 나오고 없으면 `null`. 웹 vitest — `smallImage`, 각 줄이 작은 것을 고르고 없으면 원본, 대표 사진 갱신이 `photoThumbnailUrls[0]` 을 쓴다 |
| 통합(`just test-integration`, 실제 PostgreSQL + 메모리 가짜 저장소) | 작업이 키 없는 줄만 고르고 키를 적는다, 3 번 실패하면 멈춘다, 두 차례가 겹치면 한쪽만 돈다(advisory lock), 그 사이 지워진 글의 작은 사진은 지울 목록으로, 글 삭제가 원본·작은 사진 둘 다 지울 목록에, 고아 청소가 작은 사진을 지우지 않는다, 목록 질의(`PlaceStore`·`CartStore`·`CourseStore`·`MarketStore`·`PostStore`)가 `thumbnail_url` 을 낸다. 새 마이그레이션이라 `//tools/aws/integration:database_test` 도 |
| SMOKE(로컬 kind, MinIO) | `just update scene-api` → 사진 달린 후기를 쓰고 1 분 뒤 `GET /v1/posts` 의 `coverThumbnailUrl` 을 받아 JPEG·긴 변 720 확인, `GET /v1/posts/{id}` 의 `photoThumbnailUrls` 길이가 `photoUrls` 와 같음, 리뷰 `photos[].thumbnailUrl`, 적재 뒤 `GET /v1/places?city=서울` 의 `imageThumbnailUrl`. 빈 200·`null` 만 오는 것은 실패 |

## 6. 성공의 잣대

- **app.scenetrip.io 첫 방문, 모바일 화면(390×844), 캐시 끔, 홈 탭**: 우리 S3 사진 바이트 합계. 지금 바깥 이미지 2.76MB(포스터 포함), 촬영지 사진 장당 약 0.3MB.
  목표 — 우리 S3 사진은 장당 **100KB 이하**, 합계 **70% 이상 감소**. 포스터는 이 일의 범위가 아니다(MZ2AZ-508 에서 이미 줄임).
- 커뮤니티 탭 첫 화면도 같은 방식으로 잰다(지금 대표 사진 장당 약 700KB).
- 잰 값(전·후)은 이 문서 끝 「결과」 에 덧붙인다. DEV 에서 한 번, PRD 에서 한 번.

## 7. 하지 않는 것

- WebP·여러 크기, 장면 스틸 작은 사진(→ 2026-11-15 §10 에서 한다), iOS·Android 앱 코드(iOS 는 웹뷰로 간다, Android 중단).
- 서명 주소가 응답마다 달라 브라우저 캐시가 안 맞는 문제 — 작은 사진이 줄여 주는 몫과 별개다(아래 「정할 것」).

## 8. 정한 것 (사용자, 2026-11-13)

1. **담당** — 서버·계약·웹 모두 이 티켓에서 함께 한다(11/13 부터 권호는 광고 담당).
2. **크기** — 긴 변 720 하나. 단 홈 「오늘의 성지」 큰 카드(350×96) 한 장만 원본을 쓴다.
3. **촬영지 도구 실행** — 사용자의 AWS 콘솔(SSO) 권한으로 한 번. 실행 전에 올릴 목록·경로를 보여 주고 확인받는다.
4. 관광공사 썸네일이 흐린 문제 — 2 와 같이, 큰 카드만 원본이라 해결된다. 나머지 칸은 작은 것.
5. 서명 주소 캐시 — 별도 티켓으로, 이 일 뒤에. → **MZ2AZ-562** 로 했다: 보여 줄 주소의 서명 시각을 한 시간 창의 시작으로 내려 창 안에서는 같은 주소, 만료는 창 + 한 시간(적어도 한 시간·최대 두 시간, 계약 1.29.1), `response-cache-control=private, max-age=3600`. 창을 하루로 못 잡는 까닭은 DEV·PRD 자격 증명이 EKS Pod Identity 임시 키라 몇 시간마다 바뀌어 주소가 하루 동안 같을 수 없어서다(설정 `scenetrip.media.view-window`, scene-api README 「사진 주소」).
6. **(같은 날 저녁 변경)** 「오늘의 성지」 큰 카드도 작은 사진(720)으로. 운영 측정에서 첫 방문 이미지 중 가장 큰 한 장(375 KB)이었다. 칸은 `object-fit: cover` 로 꽉 채우므로 사진 크기가 줄어도 여백이 생기지 않는다.

## 9. 구현 세부 (시험 작성 뒤 정함, 2026-11-13)

1. **고르는 순서** — 표마다 `id` 내림차순(새로 올린 것부터). 한 차례(20 장)는 글 사진에서 먼저 채우고 남은 자리를 리뷰 사진으로.
2. **한 장 실패** — 그 사진만 시도 +1 하고 차례의 나머지는 계속한다. 저장소 자체 오류(읽기·쓰기 연결 실패)는 그 차례를 멈춘다(다음 분에 다시).
3. **키 확장자** — 작은 사진은 늘 JPEG 이라 키는 `thumb-720/<원래 이름에서 확장자를 뺀 것>.jpg`. 촬영지 `.png` 원본도 같다.
4. **`review_archive`** — 리뷰를 보관할 때 작은 사진 키는 보관하지 않고 지울 목록에 올린다(되살리면 작업이 다시 만든다).
5. **`GuidePlanStop.imageThumbnailUrl`** — 이번에는 채우지 않는다(에이전트 모듈 범위 밖, 칸만 nullable 로 있다). 웹은 없으면 원본을 쓴다.
6. **CoverPhoto 갱신** — 서명 만료로 다시 받을 때도 작은 사진(`photoThumbnailUrls[0]`)을 쓴다. 구현 쪽이 시험을 더한다.
7. **사진 없는 글** — `photoThumbnailUrls` 는 `photoUrls` 와 같게 빈 배열.
8. **장바구니·코스·마켓** — 통합 시험을 구현 쪽이 하나씩 더한다(`imageThumbnailUrl`, `CourseSummary.thumbnailUrl` 의 COALESCE).

## 10. 장면 사진 (2026-11-15, 계약 1.32.0 — [web-ad-landing.md](./web-ad-landing.md) §10-2)

느린 폰에서 광고 랜딩의 첫 장면이 원본 PNG(장당 2~3MB)라 늦게 떴다. 장면 사진도 촬영지 사진과 **같은 방식**으로 작은 판을 만든다.

- **도구**: `just place-thumbnails --scene`(미리 보기) · `--scene --sample-dir DIR`(앞 20 장 실측, 로컬에만) · `--scene --upload`(올리기).
  `seed/candidates.csv` 의 `scene_image_url` 중 공용 버킷 `scene/…` 주소만, 긴 변 720px JPEG q0.80 로 `scene/thumb-720/<scene/ 뒤 경로>.jpg`
  에 덮어쓰기 없이(`If-None-Match: *`, 이미 있는 키는 건너뜀) 올리고 `seed/scene_image_thumbnails.tsv`(`url`, `thumbnail_url`)를 쓴다.
  코드는 `PlaceThumbnails.Source`(`PLACE`·`SCENE` — 자리·CSV 칸·TSV)와 `ThumbnailRules.PREFIXES` 에 `scene/` 를 더한 것뿐이다.
- **DB**: `V50__scene_image_thumbnail.sql` — `place_content_image.thumbnail_url TEXT NULL`(촬영지의 `place_image.thumbnail_url` 과 같은 모양).
- **적재**: `seed/candidates.sql` 이 장면 사진 줄을 넣은 뒤 `/tmp/scene-image-thumbnails.tsv` 로 이번 적재 줄의 빈 칸만 채운다. 로컬
  `just seed`(`tools/scripts/seed.sh`)와 원격 적재 이미지(`seed/remote-load.sh`, `:data_load_seed_layer`)가 TSV 를 그 자리에 놓는다 —
  DEV·PRD 는 서버 배포 뒤 `aws-deploy.yml` 의 `data-load`(= `just aws-data-load <env>`)로 들어간다.
- **응답**: `Scene.sceneImageThumbnailUrls`(원본과 같은 순서·길이, 작은 판이 없는 장은 원본)·`sceneImageThumbnailUrl`(첫 장),
  작품을 특정한 촬영지 목록(`GET /contents/{id}/places`·`GET /places?contentId=`)의 `PlaceSummary.sceneImageThumbnailUrl`(그 작품
  장면 첫 장, 없으면 원본, 장면 사진이 없으면 null — 페이지의 촬영지에만 한 번 더 묻는다). 함께 `PlaceSummary.nameKo`·`CartItem.nameKo`
  (`place_i18n` 의 `ko` 이름, 기본키 한 번 찾기).
- **시험**: 단위 `SceneThumbnailsTest`(경로·대상·CSV 칸)·`PlacesSceneThumbnailControllerTest`·`ContentPlacesSceneThumbnailControllerTest`·
  `CartNameKoControllerTest`, 통합 `SceneThumbnailIntegrationTest`·`CartNameKoIntegrationTest`.

