# 지역 추천 가이드의 로컬 적재와 재개

MZ2AZ-423. 대상은 [가이드 20개](../product/community-guides/README.md)다.
`just community-guides-local`은 Bazel의 수동 실행 대상이며 기본 실행은 자료
검증만 한다. `--apply`를 붙여야 로컬 편집 회원·코스·공개 글을 만든다.

## 실행

로컬 스택을 띄우고 `kind-scenetrip` 컨텍스트와 localhost의 API 8081·MinIO
9000을 확인한다. 저장소의 무시되는 `.env`에 로컬 JWT 서명 설정이 있어야 한다.
토큰은 메모리에서 한 시간용으로 만들어 쓰며 키·토큰을 출력하거나 커밋하지 않는다.
직접 DB 접속 환경변수와 다른 컨텍스트, 외부 API·사진 리다이렉트는 거절한다.

```sh
just community-guides-local docs/product/community-guides/regional-guides-2026-10.json ~/SceneTrip-backup/community-guides-2026-10
just community-guides-local docs/product/community-guides/regional-guides-2026-10.json ~/SceneTrip-backup/community-guides-2026-10 --download
just community-guides-local docs/product/community-guides/regional-guides-2026-10.json ~/SceneTrip-backup/community-guides-2026-10 --apply
just community-guides-local docs/product/community-guides/regional-guides-2026-10.json ~/SceneTrip-backup/community-guides-2026-10 --verify
```

한 자료에는 상태 폴더 하나를 계속 쓴다. 2026-10-13 작업의 실제 폴더는 Dropbox의
`SceneTrip_local/community-guides-2026-10-13`이며 별도의 새 폴더로 다시 실행하면
다른 편집 회원·중복 콘텐츠가 생길 수 있다. Android는 같은 로컬 API를 읽으며
같은 자료를 다시 적재하지 않는다.

## 검사와 중단 복구

- 자료의 지역·좌표·최대 거리·장소 ID·글 길이·사진 출처를 검증한다. 각 장소의
  현재 API 이름·좌표·작품 연결도 확인한다. 연결된 편의시설은 계약에 따라 촬영지로
  정규화한 뒤 기대값을 잡는다. 서버 응답을 새 기대값으로 덮어쓰지 않는다.
- 코스 생성·게시 전에 pending 상태를 먼저 저장하고, 일차·장소·순서·체류 시간을
  요청과 대조한다. 게시 후 공개 목록·본문·소유 여부·코스 사본과 사진 해시를 읽어
  검증한다. API 요청 간 간격을 두어 회원 호출 제한을 넘지 않는다.
- 응답이 끊기거나 5xx를 받으면 같은 상태로 실행해 편집 회원의 글·코스를 먼저
  조회한다. 결과가 하나로 확인되지 않으면 중복 생성하지 않고 멈춘다. 미확정 상태를
  지우거나 상태 폴더를 새로 만들지 않는다.
- 확정된 4xx 게시 거절은 응답 상태를 기록한다. 사진을 거절한 400은 임시 키를
  실패 이력에 보존하고 다음 실행에서 새 업로드를 준비한다. 버려진 임시 사진의
  정리는 서버의 만료 정책을 따른다. 연결 실패를 무제한 자동 재시도하지 않는다.
- 상태 폴더 잠금으로 동시 적재를 거절한다. `state.json`은 생성 ID·원본 자료 해시·
  업로드 키·사진 해시를 기록하고 임시 파일에서 교체한다. 폴더 700·상태 파일 600이다.
  사진 캐시도 임시 파일을 교체하며 크기·형식 종료·기록된 원본 해시를 검사한다.
  전체 이미지 디코딩을 대신하는 검사는 아니다. 이번 실제 사진은 별도 화면 검토와
  이미지 크기 조회를 했다.

## 되돌리기

앱 코드만 되돌리려면 이 PR의 변경을 되돌리는 PR을 만든다. 코드 되돌리기는
DB에 게시한 콘텐츠를 지우지 않는다. 글·코스 정리가 필요하면 상태 파일의
`user`, 각 `postId`·`courseId`와 로컬 API의 소유·제목·본문을 먼저 대조한다.
해당 편집 회원으로 `DELETE /v1/posts/{postId}`, `DELETE /v1/courses/{courseId}`만
호출한다. 다른 회원·기기 글·원래 코스는 대상에 넣지 않는다. 상태 파일을 보존한다.

글 삭제가 MinIO의 영구 사진 삭제까지 보장하지는 않는다. 남은 사진의 보관·삭제
정책은 MZ2AZ-406을 따른다. 공유 DB 정리나 AWS 반영은 이 로컬 명령의 범위가 아니다.

## 회귀 검사

```sh
just test //tools/scripts:community_guides_unit_test
just check
```
