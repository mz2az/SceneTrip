package com.mz2az.scenetrip.sceneapi.apiflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * 커뮤니티 보강 — 좋아요·댓글·게시판·글 검색·커서 페이지·댓글 신고/차단 (계약 1.24.0, 계획 {@code community-social.md} §2·§3·§4 통합
 * 갈래).
 *
 * <p>앱 전체를 띄우고(컨텍스트 기동 포함) 실제 PostgreSQL 에 요청한다 — 마이그레이션(V44)이 올라가 있어야 하는 표·열, 탈퇴의 CASCADE, 차단 거르기가
 * 실제 질의로 맞는지 본다. 시험마다 한 트랜잭션이고 끝나면 되돌린다({@link ApiIntegrationTest}). 전체 개수에 기대지 않고 자기가 만든 글({@code
 * tag} 가 든 제목·본문)만 본다.
 */
@DisplayName("API 통합 — 여행후기 좋아요·댓글·게시판·검색·커서")
class PostSocialApiTest extends ApiIntegrationTest {

  // ═════════════ 좋아요 ═════════════

  @Test
  @DisplayName(
      "좋아요 — 두 번 눌러도 204 이고 likeCount 1. liked 는 누른 사람에게만 true(다른 회원·비회원 false). 목록 요약도 같다")
  void likeIsIdempotentAndCounted() throws Exception {
    UUID author = member("TEST_la" + tag.substring(0, 6));
    UUID liker = member("TEST_lb" + tag.substring(0, 6));
    UUID other = member("TEST_lc" + tag.substring(0, 6));
    long postId = createPost(author, "TEST-좋아요-" + tag, "본문 " + tag, null);

    mvc.perform(put("/posts/{id}/like", postId).header("Authorization", bearer(liker)))
        .andExpect(status().isNoContent());
    mvc.perform(put("/posts/{id}/like", postId).header("Authorization", bearer(liker)))
        .andExpect(status().isNoContent());

    mvc.perform(get("/posts/{id}", postId).header("Authorization", bearer(liker)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.likeCount").value(1))
        .andExpect(jsonPath("$.liked").value(true));
    mvc.perform(get("/posts/{id}", postId).header("Authorization", bearer(other)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.likeCount").value(1))
        .andExpect(jsonPath("$.liked").value(false));
    mvc.perform(get("/posts/{id}", postId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.likeCount").value(1))
        .andExpect(jsonPath("$.liked").value(false));

    JsonNode mine = summaryOf(list(Map.of("q", tag), bearer(liker)), postId);
    assertThat(mine.path("likeCount").asInt(-1)).isEqualTo(1);
    assertThat(mine.path("liked").isBoolean()).as("요약에 liked 가 있다").isTrue();
    assertThat(mine.path("liked").asBoolean()).isTrue();
    JsonNode theirs = summaryOf(list(Map.of("q", tag), bearer(other)), postId);
    assertThat(theirs.path("liked").isBoolean()).isTrue();
    assertThat(theirs.path("liked").asBoolean()).isFalse();

    // 둘째 사람도 누르면 2 — 글쓴이 자신도 누를 수 있다
    mvc.perform(put("/posts/{id}/like", postId).header("Authorization", bearer(author)))
        .andExpect(status().isNoContent());
    mvc.perform(get("/posts/{id}", postId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.likeCount").value(2));
    assertThat(likeRows(postId)).isEqualTo(2);
  }

  @Test
  @DisplayName("좋아요 취소 — 두 번 취소해도 204, 누르지 않았어도 204. likeCount 0, liked false")
  void unlikeIsIdempotent() throws Exception {
    UUID author = member("TEST_ua" + tag.substring(0, 6));
    UUID liker = member("TEST_ub" + tag.substring(0, 6));
    long postId = createPost(author, "TEST-취소-" + tag, "본문", null);

    // 누르지 않은 채 취소
    mvc.perform(delete("/posts/{id}/like", postId).header("Authorization", bearer(liker)))
        .andExpect(status().isNoContent());

    mvc.perform(put("/posts/{id}/like", postId).header("Authorization", bearer(liker)))
        .andExpect(status().isNoContent());
    mvc.perform(delete("/posts/{id}/like", postId).header("Authorization", bearer(liker)))
        .andExpect(status().isNoContent());
    mvc.perform(delete("/posts/{id}/like", postId).header("Authorization", bearer(liker)))
        .andExpect(status().isNoContent());

    mvc.perform(get("/posts/{id}", postId).header("Authorization", bearer(liker)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.likeCount").value(0))
        .andExpect(jsonPath("$.liked").value(false));
    assertThat(likeRows(postId)).isZero();
  }

  @Test
  @DisplayName("좋아요 — 비로그인 401 SIGN_IN_REQUIRED, 없는 글·내려진 글·차단한 사람의 글은 404 POST_NOT_FOUND")
  void likeErrors() throws Exception {
    UUID author = member("TEST_le" + tag.substring(0, 6));
    UUID viewer = member("TEST_lf" + tag.substring(0, 6));
    long removed = createPost(author, "TEST-내릴글-" + tag, "본문", null);
    long blockedPost = createPost(author, "TEST-차단글-" + tag, "본문", null);

    mvc.perform(put("/posts/{id}/like", blockedPost))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
    mvc.perform(
            put("/posts/{id}/like", blockedPost)
                .header("X-Install-Id", UUID.randomUUID().toString()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));

    mvc.perform(put("/posts/{id}/like", Long.MAX_VALUE).header("Authorization", bearer(viewer)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));

    removePost(removed);
    mvc.perform(put("/posts/{id}/like", removed).header("Authorization", bearer(viewer)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
    mvc.perform(delete("/posts/{id}/like", removed).header("Authorization", bearer(viewer)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));

    mvc.perform(block(viewer, "post", blockedPost)).andExpect(status().isCreated());
    mvc.perform(put("/posts/{id}/like", blockedPost).header("Authorization", bearer(viewer)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
    assertThat(likeRows(blockedPost)).isZero();
  }

  // ═════════════ 댓글 ═════════════

  @Test
  @DisplayName(
      "댓글 — 201 TripPostComment(앞뒤 공백 뗌, isMine, 글쓴이 닉네임). 목록은 오래된 순, isMine 은 보는 사람 기준."
          + " commentCount 가 는다")
  void commentsOldestFirstWithIsMine() throws Exception {
    String aNick = "TEST_ca" + tag.substring(0, 6);
    UUID author = member(aNick);
    UUID b = member("TEST_cb" + tag.substring(0, 6));
    long postId = createPost(author, "TEST-댓글-" + tag, "본문", null);

    JsonNode first =
        body(
            mvc.perform(comment(b, postId, "  첫 댓글이에요  "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("첫 댓글이에요"))
                .andExpect(jsonPath("$.isMine").value(true))
                .andExpect(jsonPath("$.author.nickname").value("TEST_cb" + tag.substring(0, 6)))
                .andExpect(jsonPath("$.createdAt").exists())
                .andReturn());
    long c1 = first.path("id").asLong();
    long c2 = commentId(author, postId, "답글 고마워요");
    long c3 = commentId(b, postId, "또 왔어요");

    JsonNode asB = comments(postId, bearer(b));
    assertThat(commentIds(asB)).containsExactly(c1, c2, c3);
    assertThat(asB.path("total").asInt()).isEqualTo(3);
    assertThat(isMine(asB)).containsExactly(true, false, true);
    assertThat(asB.path("items").get(1).path("author").path("nickname").asString())
        .isEqualTo(aNick);

    JsonNode asAuthor = comments(postId, bearer(author));
    assertThat(isMine(asAuthor)).containsExactly(false, true, false);

    JsonNode asGuest = comments(postId, null);
    assertThat(commentIds(asGuest)).containsExactly(c1, c2, c3);
    assertThat(isMine(asGuest)).containsExactly(false, false, false);

    // limit·offset 이 오래된 순 위에서 쪽을 나눈다
    JsonNode page2 =
        body(
            mvc.perform(
                    get("/posts/{id}/comments", postId).param("limit", "2").param("offset", "2"))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(commentIds(page2)).containsExactly(c3);
    assertThat(page2.path("total").asInt()).isEqualTo(3);

    mvc.perform(get("/posts/{id}", postId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.commentCount").value(3));
    JsonNode summary = summaryOf(list(Map.of("q", tag), null), postId);
    assertThat(summary.path("commentCount").asInt(-1)).isEqualTo(3);
  }

  @Test
  @DisplayName(
      "댓글 쓰기 오류 — 비회원 401 SIGN_IN_REQUIRED, 공백뿐 400, 금칙어 400 TEXT_NOT_ALLOWED, 없는 글 404"
          + " POST_NOT_FOUND — 줄이 생기지 않는다")
  void commentCreateErrors() throws Exception {
    UUID author = member("TEST_ce" + tag.substring(0, 6));
    UUID b = member("TEST_cf" + tag.substring(0, 6));
    long postId = createPost(author, "TEST-댓글오류-" + tag, "본문", null);

    mvc.perform(
            post("/posts/{id}/comments", postId)
                .header("X-Install-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("body", "비회원 댓글"))))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
    mvc.perform(comment(b, postId, "   "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    mvc.perform(comment(b, postId, "씨발 별로"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TEXT_NOT_ALLOWED"));
    mvc.perform(comment(b, Long.MAX_VALUE, "없는 글에"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
    mvc.perform(get("/posts/{id}/comments", Long.MAX_VALUE))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));

    assertThat(commentRows(postId)).isZero();
  }

  @Test
  @DisplayName(
      "댓글 지우기 — 내 것은 204 이고 목록·수에서 빠진다. 남의 것·없는 것·이미 지운 것은 404 COMMENT_NOT_FOUND(남의 것은 그대로)")
  void deleteOwnCommentOnly() throws Exception {
    UUID author = member("TEST_da" + tag.substring(0, 6));
    UUID b = member("TEST_db" + tag.substring(0, 6));
    long postId = createPost(author, "TEST-댓글지우기-" + tag, "본문", null);
    long mine = commentId(b, postId, "지울 댓글");
    long theirs = commentId(author, postId, "남길 댓글");

    mvc.perform(
            delete("/posts/{p}/comments/{c}", postId, theirs).header("Authorization", bearer(b)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COMMENT_NOT_FOUND"));
    mvc.perform(
            delete("/posts/{p}/comments/{c}", postId, Long.MAX_VALUE)
                .header("Authorization", bearer(b)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COMMENT_NOT_FOUND"));

    mvc.perform(delete("/posts/{p}/comments/{c}", postId, mine).header("Authorization", bearer(b)))
        .andExpect(status().isNoContent());
    mvc.perform(delete("/posts/{p}/comments/{c}", postId, mine).header("Authorization", bearer(b)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COMMENT_NOT_FOUND"));

    JsonNode list = comments(postId, null);
    assertThat(commentIds(list)).containsExactly(theirs);
    assertThat(list.path("total").asInt()).isEqualTo(1);
    mvc.perform(get("/posts/{id}", postId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.commentCount").value(1));
  }

  @Test
  @DisplayName("차단한 사람의 댓글은 차단한 사람의 목록·total·commentCount 에서 빠진다 — 비회원은 다 본다")
  void blockedAuthorsCommentsHidden() throws Exception {
    UUID author = member("TEST_ba" + tag.substring(0, 6));
    UUID rude = member("TEST_bb" + tag.substring(0, 6));
    UUID viewer = member("TEST_bc" + tag.substring(0, 6));
    long postId = createPost(author, "TEST-차단댓글-" + tag, "본문", null);
    long rudePost = createPost(rude, "TEST-무례한사람글-" + tag, "본문", null);
    long fine = commentId(author, postId, "괜찮은 댓글");
    long rudeComment = commentId(rude, postId, "무례한 댓글");

    mvc.perform(block(viewer, "post", rudePost)).andExpect(status().isCreated());

    JsonNode asViewer = comments(postId, bearer(viewer));
    assertThat(commentIds(asViewer)).containsExactly(fine);
    assertThat(asViewer.path("total").asInt()).isEqualTo(1);
    mvc.perform(get("/posts/{id}", postId).header("Authorization", bearer(viewer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.commentCount").value(1));

    JsonNode asGuest = comments(postId, null);
    assertThat(commentIds(asGuest)).containsExactly(fine, rudeComment);
    mvc.perform(get("/posts/{id}", postId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.commentCount").value(2));
  }

  @Test
  @DisplayName("운영자가 내린 글 — 댓글 목록·댓글 쓰기 모두 404 POST_NOT_FOUND")
  void removedPostHidesComments() throws Exception {
    UUID author = member("TEST_ra" + tag.substring(0, 6));
    UUID b = member("TEST_rb" + tag.substring(0, 6));
    long postId = createPost(author, "TEST-내린글댓글-" + tag, "본문", null);
    commentId(b, postId, "내려지기 전 댓글");

    removePost(postId);

    mvc.perform(get("/posts/{id}/comments", postId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
    mvc.perform(get("/posts/{id}/comments", postId).header("Authorization", bearer(b)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
    mvc.perform(comment(b, postId, "내려진 뒤 댓글"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
  }

  @Test
  @DisplayName("탈퇴(DELETE /me) — 그 사람의 댓글·좋아요가 지워지고 남의 글의 commentCount·likeCount 가 준다")
  void accountDeletionRemovesCommentsAndLikes() throws Exception {
    UUID author = member("TEST_wa" + tag.substring(0, 6));
    UUID leaving = member("TEST_wb" + tag.substring(0, 6));
    long postId = createPost(author, "TEST-탈퇴-" + tag, "본문", null);
    long kept = commentId(author, postId, "남는 댓글");
    commentId(leaving, postId, "떠나는 사람 댓글");
    mvc.perform(put("/posts/{id}/like", postId).header("Authorization", bearer(leaving)))
        .andExpect(status().isNoContent());

    mvc.perform(delete("/me").header("Authorization", bearer(leaving)))
        .andExpect(status().isNoContent());

    assertThat(
            jdbc.sql("SELECT count(*) FROM community_comment WHERE user_id = :u")
                .param("u", leaving)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            jdbc.sql("SELECT count(*) FROM community_post_like WHERE user_id = :u")
                .param("u", leaving)
                .query(Long.class)
                .single())
        .isZero();
    mvc.perform(get("/posts/{id}", postId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.commentCount").value(1))
        .andExpect(jsonPath("$.likeCount").value(0));
    assertThat(commentIds(comments(postId, null))).containsExactly(kept);
  }

  // ═════════════ 게시판 ═════════════

  @Test
  @DisplayName("게시판 — board 없이 쓰면 review. board 없는 목록은 review 만, board=free 는 free 만. 요약·상세에 board")
  void boardFilter() throws Exception {
    UUID me = member("TEST_bo" + tag.substring(0, 6));
    long review = createPost(me, "TEST-여행후기판-" + tag, "본문", null);
    long free = createPost(me, "TEST-자유판-" + tag, "본문", "free");
    long explicitReview = createPost(me, "TEST-명시후기판-" + tag, "본문", "review");

    mvc.perform(get("/posts/{id}", review))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.board").value("review"));
    mvc.perform(get("/posts/{id}", free))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.board").value("free"));
    assertThat(boardColumn(review)).isEqualTo("review");
    assertThat(boardColumn(free)).isEqualTo("free");

    JsonNode defaults = list(Map.of("limit", "100"), null);
    assertThat(ids(defaults)).contains(review, explicitReview).doesNotContain(free);
    defaults.path("items").forEach(n -> assertThat(n.path("board").asString()).isEqualTo("review"));

    JsonNode reviews = list(Map.of("limit", "100", "board", "review"), null);
    assertThat(ids(reviews)).contains(review, explicitReview).doesNotContain(free);

    JsonNode frees = list(Map.of("limit", "100", "board", "free"), null);
    assertThat(ids(frees)).contains(free).doesNotContain(review, explicitReview);
    frees.path("items").forEach(n -> assertThat(n.path("board").asString()).isEqualTo("free"));
  }

  @Test
  @DisplayName("V44 — board 열 없이 넣은 글(마이그레이션 전 글의 모습)은 review 가 되고 board 없는 목록에 나온다")
  void legacyRowsBecomeReview() throws Exception {
    UUID me = member("TEST_lg" + tag.substring(0, 6));
    long id =
        jdbc.sql(
                "INSERT INTO community_post (user_id, title, body) VALUES (:u, :t, '옛 본문')"
                    + " RETURNING id")
            .param("u", me)
            .param("t", "TEST-옛글-" + tag)
            .query(Long.class)
            .single();

    assertThat(boardColumn(id)).isEqualTo("review");
    assertThat(ids(list(Map.of("q", tag), null))).containsExactly(id);
  }

  // ═════════════ 검색 ═════════════

  @Test
  @DisplayName("검색 q — 제목·본문 부분 일치, 대소문자 무시. 게시판 조건과 함께 걸린다")
  void searchByTitleOrBody() throws Exception {
    UUID me = member("TEST_sq" + tag.substring(0, 6));
    long titleHit = createPost(me, "TEST 제주 " + tag + "SUNSET 후기", "평범한 본문", null);
    long bodyHit = createPost(me, "TEST 평범한 제목 " + tag, "숨은 " + tag + "BodyGem 이 있어요", null);
    long miss = createPost(me, "TEST 다른 글 " + tag, "아무 말", null);
    long freeHit = createPost(me, "TEST 자유 " + tag + "Sunset", "본문", "free");

    assertThat(ids(list(Map.of("q", tag + "sunset"), null))).containsExactly(titleHit);
    assertThat(ids(list(Map.of("q", tag.toUpperCase() + "SuNsEt"), null)))
        .containsExactly(titleHit);
    assertThat(ids(list(Map.of("q", tag + "bodygem"), null))).containsExactly(bodyHit);
    assertThat(ids(list(Map.of("q", "  " + tag + "BODYGEM  "), null))).containsExactly(bodyHit);
    assertThat(ids(list(Map.of("q", tag + "sunset", "board", "free"), null)))
        .containsExactly(freeHit);
    assertThat(ids(list(Map.of("q", tag), null)))
        .containsExactlyInAnyOrder(titleHit, bodyHit, miss);
    assertThat(list(Map.of("q", tag), null).path("total").asInt()).isEqualTo(3);

    mvc.perform(get("/posts").param("q", " x "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
  }

  // ═════════════ 커서 ═════════════

  @Test
  @DisplayName(
      "커서 — nextCursor 로 끝까지 걸으면 겹침·빠짐 없이 최신순 전부, 중간에 새 글이 올라와도 그대로. 마지막 쪽 nextCursor 는 null")
  void cursorWalksWithoutGapsOrDuplicates() throws Exception {
    UUID me = member("TEST_cu" + tag.substring(0, 6));
    List<Long> created = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      created.add(createPost(me, "TEST 커서 " + tag + " " + i, "본문 " + i, null));
    }
    List<Long> newestFirst = new ArrayList<>(created);
    Collections.reverse(newestFirst);

    List<Long> seen = new ArrayList<>();
    JsonNode page = list(Map.of("q", tag, "limit", "2"), null);
    seen.addAll(ids(page));
    assertThat(page.path("nextCursor").isString()).as("첫 쪽 nextCursor").isTrue();

    // 스크롤하는 사이 새 글이 올라온다
    long late = createPost(me, "TEST 커서 늦은 글 " + tag, "본문", null);

    int pages = 1;
    while (!page.path("nextCursor").isNull() && !page.path("nextCursor").isMissingNode()) {
      String cursor = page.path("nextCursor").asString();
      page = list(Map.of("q", tag, "limit", "2", "cursor", cursor), null);
      seen.addAll(ids(page));
      assertThat(++pages).as("끝나지 않는 커서").isLessThanOrEqualTo(5);
    }

    assertThat(seen).as("겹침 없음").doesNotHaveDuplicates();
    assertThat(seen).as("새 글은 앞쪽이라 끼어들지 않는다").doesNotContain(late);
    assertThat(seen).containsExactlyElementsOf(newestFirst);
    assertThat(pages).isEqualTo(3);
    // 계약 1.24.0: 더 없으면 nextCursor 칸이 빠진다(null 과 같다 — 응답은 null 인 선택 칸을 내보내지 않는다, application.yaml).
    assertThat(page.path("nextCursor").isMissingNode() || page.path("nextCursor").isNull())
        .isTrue();

    // 첫 쪽부터 다시 받으면 새 글이 맨 앞
    assertThat(ids(list(Map.of("q", tag, "limit", "1"), null))).containsExactly(late);
  }

  @Test
  @DisplayName("커서 — 잘못된 cursor 는 400 INVALID_PARAMETER, cursor 와 offset 을 함께 주면 400")
  void cursorErrors() throws Exception {
    mvc.perform(get("/posts").param("cursor", "garbage-" + tag))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

    UUID me = member("TEST_ce2" + tag.substring(0, 5));
    createPost(me, "TEST 커서오류 " + tag + " 1", "본문", null);
    createPost(me, "TEST 커서오류 " + tag + " 2", "본문", null);
    String cursor = list(Map.of("q", tag, "limit", "1"), null).path("nextCursor").asString();
    assertThat(cursor).isNotBlank();
    mvc.perform(get("/posts").param("q", tag).param("cursor", cursor).param("offset", "1"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
  }

  // ═════════════ 댓글 신고·차단 ═════════════

  @Test
  @DisplayName("댓글 신고 — 204, content_report 에 comment 줄(작성자·본문 앞부분). 같은 사람이 다시 신고해도 204 이고 줄은 하나")
  void reportComment() throws Exception {
    UUID author = member("TEST_rp" + tag.substring(0, 6));
    UUID commenter = member("TEST_rq" + tag.substring(0, 6));
    UUID reporter = member("TEST_rr" + tag.substring(0, 6));
    long postId = createPost(author, "TEST-댓글신고-" + tag, "본문", null);
    long commentId = commentId(commenter, postId, "광고 문의는 010-0000-0000");

    mvc.perform(report(reporter, commentId, "spam")).andExpect(status().isNoContent());
    mvc.perform(report(reporter, commentId, "abuse")).andExpect(status().isNoContent());

    List<Map<String, Object>> rows =
        jdbc.sql(
                "SELECT reporter_id, author_id, excerpt FROM content_report"
                    + " WHERE target_type = 'comment' AND target_id = :i")
            .param("i", commentId)
            .query()
            .listOfRows();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).get("reporter_id")).isEqualTo(reporter);
    assertThat(rows.get(0).get("author_id")).isEqualTo(commenter);
    assertThat((String) rows.get(0).get("excerpt")).contains("광고 문의는");
  }

  @Test
  @DisplayName("댓글로 차단 — 201 이고 그 댓글 작성자가 차단된다. 이후 그 사람의 댓글·글이 차단한 사람에게서 빠진다")
  void blockViaComment() throws Exception {
    String rudeNick = "TEST_bv" + tag.substring(0, 6);
    UUID author = member("TEST_bu" + tag.substring(0, 6));
    UUID rude = member(rudeNick);
    UUID viewer = member("TEST_bw" + tag.substring(0, 6));
    long postId = createPost(author, "TEST-댓글차단-" + tag, "본문", null);
    long rudePost = createPost(rude, "TEST-무례글-" + tag, "본문", null);
    long fine = commentId(author, postId, "좋은 댓글");
    long rudeComment = commentId(rude, postId, "무례한 댓글");

    mvc.perform(block(viewer, "comment", rudeComment))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.nickname").value(rudeNick));

    assertThat(commentIds(comments(postId, bearer(viewer)))).containsExactly(fine);
    mvc.perform(get("/posts/{id}", rudePost).header("Authorization", bearer(viewer)))
        .andExpect(status().isNotFound());
    assertThat(ids(list(Map.of("q", tag), bearer(viewer))))
        .contains(postId)
        .doesNotContain(rudePost);
    // 다른 사람에게는 그대로
    assertThat(commentIds(comments(postId, null))).containsExactly(fine, rudeComment);
  }

  // ───────────── 도우미 ─────────────

  private long createPost(UUID user, String title, String body, String board) throws Exception {
    Map<String, Object> in = new HashMap<>();
    in.put("title", title);
    in.put("body", body);
    in.put("photoKeys", List.of());
    if (board != null) {
      in.put("board", board);
    }
    JsonNode created =
        body(
            mvc.perform(
                    post("/posts")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(in)))
                .andExpect(status().isCreated())
                .andReturn());
    return created.path("id").asLong();
  }

  private void removePost(long postId) {
    jdbc.sql("UPDATE community_post SET removed_at = now() WHERE id = :id")
        .param("id", postId)
        .update();
  }

  private MockHttpServletRequestBuilder comment(UUID user, long postId, String text)
      throws Exception {
    return post("/posts/{id}/comments", postId)
        .header("Authorization", bearer(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(Map.of("body", text)));
  }

  private long commentId(UUID user, long postId, String text) throws Exception {
    return body(mvc.perform(comment(user, postId, text))
            .andExpect(status().isCreated())
            .andReturn())
        .path("id")
        .asLong();
  }

  private JsonNode comments(long postId, String auth) throws Exception {
    var b = get("/posts/{id}/comments", postId);
    if (auth != null) {
      b.header("Authorization", auth);
    }
    return body(mvc.perform(b).andExpect(status().isOk()).andReturn());
  }

  private JsonNode list(Map<String, String> params, String auth) throws Exception {
    var b = get("/posts");
    params.forEach(b::param);
    if (auth != null) {
      b.header("Authorization", auth);
    }
    return body(mvc.perform(b).andExpect(status().isOk()).andReturn());
  }

  private static JsonNode summaryOf(JsonNode list, long postId) {
    for (JsonNode n : list.path("items")) {
      if (n.path("id").asLong() == postId) {
        return n;
      }
    }
    throw new AssertionError("목록에 글 " + postId + " 이 없다: " + list);
  }

  private static List<Long> ids(JsonNode list) {
    List<Long> ids = new ArrayList<>();
    list.path("items").forEach(n -> ids.add(n.path("id").asLong()));
    return ids;
  }

  private static List<Long> commentIds(JsonNode list) {
    return ids(list);
  }

  private static List<Boolean> isMine(JsonNode list) {
    List<Boolean> out = new ArrayList<>();
    list.path("items").forEach(n -> out.add(n.path("isMine").asBoolean()));
    return out;
  }

  private MockHttpServletRequestBuilder block(UUID user, String type, long targetId)
      throws Exception {
    return post("/me/blocks")
        .header("Authorization", bearer(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(Map.of("targetType", type, "targetId", targetId)));
  }

  private MockHttpServletRequestBuilder report(UUID user, long commentId, String reason)
      throws Exception {
    return post("/reports")
        .header("X-Install-Id", UUID.randomUUID().toString())
        .header("Authorization", bearer(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            JSON.writeValueAsString(
                Map.of("targetType", "comment", "targetId", commentId, "reason", reason)));
  }

  private long likeRows(long postId) {
    return jdbc.sql("SELECT count(*) FROM community_post_like WHERE post_id = :p")
        .param("p", postId)
        .query(Long.class)
        .single();
  }

  private long commentRows(long postId) {
    return jdbc.sql("SELECT count(*) FROM community_comment WHERE post_id = :p")
        .param("p", postId)
        .query(Long.class)
        .single();
  }

  private String boardColumn(long postId) {
    return jdbc.sql("SELECT board FROM community_post WHERE id = :p")
        .param("p", postId)
        .query(String.class)
        .single();
  }
}
