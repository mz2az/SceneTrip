-- 탈퇴하면 그 사람이 쓴 리뷰·여행후기도 지운다 — DB 규칙을 지금 방침에 맞춘다 (MZ2AZ-500).
--
-- V21(review)·V27(community_post) 의 user_id 는 「탈퇴해도 글은 남고 글쓴이만 끊는다」(ON DELETE SET NULL) 방침으로 만들었다.
-- 그 뒤 MZ2AZ-466 이 「탈퇴하면 글도 지운다」 로 바꿨고(App Store 5.1.1), 탈퇴(UserStore.delete)가 글을 직접 지운다. 그런데 탈퇴가
-- 글을 지운 직후에 들어온 글(동시 요청)은 그 삭제를 못 보고, 계정 줄이 지워질 때 SET NULL 로 글쓴이만 비워진 채 남아 모두에게
-- 보였다(로컬 재현: 리뷰 1/15, 여행후기 7/15). CASCADE 면 계정 줄과 함께 사라진다. 쓰는 쪽은 계정 줄을 공유 잠금으로 잡아(ReviewStore·
-- PostStore) 탈퇴가 그 글이 끝날 때까지 기다리게 한다.
--
-- 이미 글쓴이가 비어 있는 옛 행(MZ2AZ-466 이전 탈퇴분)은 건드리지 않는다 — 외래키 동작은 앞으로의 삭제에만 걸린다.
-- 사진 파일은 탈퇴가 지울 목록에 올린다(그대로). 경합으로 CASCADE 에 지워진 글의 사진은 주 1회 고아 사진 청소가 거둔다.

ALTER TABLE review DROP CONSTRAINT review_user_id_fkey;
ALTER TABLE review
    ADD CONSTRAINT review_user_id_fkey FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE;

ALTER TABLE community_post DROP CONSTRAINT community_post_user_id_fkey;
ALTER TABLE community_post
    ADD CONSTRAINT community_post_user_id_fkey FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE;
