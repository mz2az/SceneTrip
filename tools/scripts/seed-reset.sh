#!/usr/bin/env bash
# 작품·촬영지를 지우고 CSV 로 처음부터 다시 적재한다. 같은 곳 연결(place_poi_link)까지 다시 돈다.
# 사용법: seed-reset.sh [CSV 경로]   호출: just seed-reset [CSV 경로]
#
# `just seed` 는 키로 갱신·추가만 하고 지우지 않는다(CSV 에서 빠진 것은 숨긴다). 수집 쪽이 키를 다시 정하면(2026-10-12,
# 네이버 번호를 바로잡으며 17 곳) 옛 키의 숨긴 행이 쌓이고, 옛 키를 새 키에 잇는 다리가 엉뚱한 곳을 이을 수 있다.
# 그때 이것으로 갈아엎는다.
#
# **지우는 것**: content · place 와 이것을 가리키는 것 — 번역·사진·별칭·작품-촬영지 짝·출연, 그리고 이 둘을 가리키는
# 사용자 데이터(장바구니·작품 하트·코스의 촬영지 항목·촬영지 리뷰·후기 사본의 촬영지 줄·마켓). 외래 키가 CASCADE 또는
# SET NULL 이라 따라간다. **남는 것**: 편의시설(poi·번역·사진·네이버 링크)·계정·코스 자체·편의시설 리뷰·인물(적재가 통째로
# 다시 넣는다).
#
# 로컬 kind 에서만 돈다 — 사용자 데이터를 지우므로 DEV·PRD 에서 부르지 않는다(DEV 는 올릴 때마다 빈 DB 다).
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

[ -z "${SCENETRIP_DB_HOST:-}" ] || die "seed-reset 은 로컬 kind 에서만 돈다 — SCENETRIP_DB_HOST 를 비우고 다시 실행하세요"
require_kind_context

log "작품·촬영지를 지웁니다 (편의시설·계정은 남습니다)"
./tools/scripts/db-psql.sh "BEGIN; DELETE FROM content; DELETE FROM place; COMMIT;" \
  || die "작품·촬영지를 지우지 못했습니다"

./tools/scripts/seed.sh "$@"
./tools/scripts/place-poi-link.sh
log "다시 적재 완료"
