package com.mz2az.scenetrip.sceneapi.apiflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.moderation.OperatorDirectory;
import com.mz2az.scenetrip.sceneapi.moderation.RemovalStore;
import com.mz2az.scenetrip.sceneapi.moderation.ReportStore;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * 운영자 내리기(계약 1.30.0 {@code removeContent}, 계획 {@code moderation-operator.md} §1·§2, MZ2AZ-569) — 앱
 * 전체를 실제 DB 위에 띄워 본다.
 *
 * <p>설정 바인딩도 함께 본다: 운영자 목록 {@code scenetrip.moderation.operator-email-sha256} 를 <b>쉼표로 이은
 * 문자열</b>(환경변수와 같은 모양)로 넣는다 — 가짜 주소 두 개의 해시. 진짜 메일은 이 파일에 없다. 기록 표 {@code moderation_action} 은 V48
 * 이 만든다.
 *
 * <p>바탕({@link ApiIntegrationTest})처럼 시험마다 한 트랜잭션 안에서 돌고 되돌린다. 그래서 {@code now()} 가 시험 안에서 한 값이다 —
 * 「이미 내린 것은 그대로 둔다」 는 과거 시각을 미리 넣어 두고 본다.
 */
@TestPropertySource(
    properties =
        "scenetrip.moderation.operator-email-sha256="
            // 아무와도 맞지 않는 자리 값, sha256("op@example.test")(가짜 주소) — 쉼표로 이은 두 값.
            + ModerationRemovalApiTest.OTHER_OPERATOR_HASH
            + ","
            + ModerationRemovalApiTest.OPERATOR_HASH)
@DisplayName("API 통합 — 운영자 내리기")
class ModerationRemovalApiTest extends ApiIntegrationTest {

  /** 운영자 메일(가짜). 신분에는 대문자를 섞어 넣는다 — 해시는 소문자로 바꾼 주소에서 낸다. */
  static final String OPERATOR_EMAIL = "op@example.test";

  /** sha256("op@example.test") — {@link OperatorDirectory#emailSha256} 와 같아야 한다(아래 시험이 확인). */
  static final String OPERATOR_HASH =
      "a243f9406e3eff1dbf3c09278db35289ceb835fb92685818c4acc841b65130d7";

  /** 아무 메일과도 맞지 않는 자리 값 — 목록에 두 개 이상이 들어가는 모양만 보려는 값이다. */
  static final String OTHER_OPERATOR_HASH =
      "0000000000000000000000000000000000000000000000000000000000000000";

  @Autowired RemovalStore removals;
  @Autowired OperatorDirectory operators;

  // ── 설정 ─────────────────────────────────────────────────────────

  @Test
  @DisplayName("위 고정 해시가 OperatorDirectory.emailSha256(op@example.test) 와 같다 — 설정이 정말 그 주소를 가리킨다")
  void fixtureHashMatches() {
    assertThat(OperatorDirectory.emailSha256(OPERATOR_EMAIL)).isEqualTo(OPERATOR_HASH);
  }

  // ── GET /me isOperator ─────────────────────────────────────────

  @Test
  @DisplayName("GET /me — 운영자 메일 신분이 있으면 isOperator=true, 다른 회원은 false")
  void meCarriesIsOperator() throws Exception {
    UUID op = operator();
    UUID other = member("TEST_no" + tag.substring(0, 6));
    identity(other, "member-" + tag + "@example.test");

    mvc.perform(get("/me").header("Authorization", bearer(op)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isOperator").value(true));
    mvc.perform(get("/me").header("Authorization", bearer(other)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isOperator").value(false));
  }

  // ── POST /moderation/removals ─────────────────────────────────────

  @Test
  @DisplayName("운영자가 글을 내리면 204 — removed_at 이 차고 상세는 404, 기록 한 줄. 다시 내려도 204 이고 기록은 늘지 않는다")
  void operatorRemovesPost() throws Exception {
    UUID op = operator();
    UUID author = member("TEST_au" + tag.substring(0, 6));
    long postId = postRow(author, "TEST 제목", "TEST 본문");

    mvc.perform(removal(op, "post", postId)).andExpect(status().isNoContent());

    assertThat(removedAt("community_post", postId)).isNotNull();
    mvc.perform(get("/posts/{id}", postId)).andExpect(status().isNotFound());
    assertThat(actions("post", postId)).isEqualTo(1);
    Map<String, Object> row = action("post", postId);
    assertThat(row.get("operator_id")).isEqualTo(op);
    assertThat(row.get("action")).isEqualTo("remove");
    assertThat(row.get("excerpt")).isEqualTo("TEST 제목 — TEST 본문");
    assertThat(row.get("created_at")).isNotNull();

    mvc.perform(removal(op, "post", postId)).andExpect(status().isNoContent());
    assertThat(actions("post", postId)).isEqualTo(1);
  }

  @Test
  @DisplayName("운영자가 아닌 회원은 403 OPERATOR_ONLY — 글은 그대로, 기록 없음")
  void nonOperatorIsForbidden() throws Exception {
    UUID author = member("TEST_au" + tag.substring(0, 6));
    UUID other = member("TEST_ot" + tag.substring(0, 6));
    identity(other, "member-" + tag + "@example.test");
    long postId = postRow(author, "TEST 제목", "TEST 본문");

    mvc.perform(removal(other, "post", postId))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("OPERATOR_ONLY"));
    assertThat(removedAt("community_post", postId)).isNull();
    assertThat(actions("post", postId)).isZero();
  }

  @Test
  @DisplayName("토큰 없이(설치 id 만) 내리면 401 SIGN_IN_REQUIRED")
  void guestIsUnauthorized() throws Exception {
    UUID author = member("TEST_au" + tag.substring(0, 6));
    long postId = postRow(author, "TEST 제목", "TEST 본문");

    mvc.perform(
            post("/moderation/removals")
                .header("X-Install-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("targetType", "post", "targetId", postId))))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
    assertThat(removedAt("community_post", postId)).isNull();
  }

  @Test
  @DisplayName("없는 글·댓글·리뷰는 404 REMOVAL_TARGET_NOT_FOUND")
  void missingTargetIs404() throws Exception {
    UUID op = operator();
    for (String type : new String[] {"post", "comment", "review"}) {
      mvc.perform(removal(op, type, Long.MAX_VALUE))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("REMOVAL_TARGET_NOT_FOUND"));
    }
  }

  // ── RemovalStore 를 직접 ─────────────────────────────────────────

  @Test
  @DisplayName("댓글 — REMOVED, community_comment.removed_at 이 차고 기록의 excerpt 는 댓글 본문")
  void removesComment() {
    UUID op = operator();
    UUID author = member("TEST_au" + tag.substring(0, 6));
    long postId = postRow(author, "TEST 제목", "TEST 본문");
    long commentId = commentRow(postId, author, "TEST 댓글");

    assertThat(removals.remove(op, ReportStore.Target.COMMENT, commentId))
        .isEqualTo(RemovalStore.Outcome.REMOVED);
    assertThat(removedAt("community_comment", commentId)).isNotNull();
    assertThat(removedAt("community_post", postId)).isNull();
    Map<String, Object> row = action("comment", commentId);
    assertThat(row.get("excerpt")).isEqualTo("TEST 댓글");
    assertThat(row.get("operator_id")).isEqualTo(op);
  }

  @Test
  @DisplayName("리뷰 — REMOVED, review.removed_at 이 차고 기록의 excerpt 는 리뷰 본문")
  void removesReview() {
    UUID op = operator();
    UUID author = member("TEST_au" + tag.substring(0, 6));
    long p = place("TEST-내리기장소-" + tag, SEA_LNG, SEA_LAT);
    long reviewId = reviewRow(p, author, "TEST 리뷰 본문");

    assertThat(removals.remove(op, ReportStore.Target.REVIEW, reviewId))
        .isEqualTo(RemovalStore.Outcome.REMOVED);
    assertThat(removedAt("review", reviewId)).isNotNull();
    assertThat(action("review", reviewId).get("excerpt")).isEqualTo("TEST 리뷰 본문");
  }

  @Test
  @DisplayName("이미 내려 있던 것은 ALREADY — removed_at 을 그대로 두고 기록을 남기지 않는다")
  void alreadyRemovedKeepsTimestamp() {
    UUID op = operator();
    UUID author = member("TEST_au" + tag.substring(0, 6));
    long postId = postRow(author, "TEST 제목", "TEST 본문");
    jdbc.sql(
            "UPDATE community_post SET removed_at = TIMESTAMPTZ '2001-01-01 00:00:00+00'"
                + " WHERE id = :id")
        .param("id", postId)
        .update();

    assertThat(removals.remove(op, ReportStore.Target.POST, postId))
        .isEqualTo(RemovalStore.Outcome.ALREADY);
    assertThat(removedAt("community_post", postId).toInstant())
        .isEqualTo(OffsetDateTime.parse("2001-01-01T00:00:00Z").toInstant());
    assertThat(actions("post", postId)).isZero();
  }

  @Test
  @DisplayName("없는 번호는 NOT_FOUND — 기록 없음")
  void missingIsNotFound() {
    UUID op = operator();
    assertThat(removals.remove(op, ReportStore.Target.POST, Long.MAX_VALUE))
        .isEqualTo(RemovalStore.Outcome.NOT_FOUND);
    assertThat(removals.remove(op, ReportStore.Target.COMMENT, Long.MAX_VALUE))
        .isEqualTo(RemovalStore.Outcome.NOT_FOUND);
    assertThat(removals.remove(op, ReportStore.Target.REVIEW, Long.MAX_VALUE))
        .isEqualTo(RemovalStore.Outcome.NOT_FOUND);
    assertThat(actions("post", Long.MAX_VALUE)).isZero();
  }

  @Test
  @DisplayName("기록의 excerpt 는 앞 200 자 — 글은 「제목 — 본문」 을 이어 자른다")
  void excerptIsFirst200Chars() {
    UUID op = operator();
    UUID author = member("TEST_au" + tag.substring(0, 6));
    String body = "가".repeat(300);
    long postId = postRow(author, "TEST", body);

    removals.remove(op, ReportStore.Target.POST, postId);
    String excerpt = (String) action("post", postId).get("excerpt");
    assertThat(excerpt).hasSize(200).isEqualTo(("TEST — " + body).substring(0, 200));
  }

  @Test
  @DisplayName("운영자 계정이 지워져도 기록은 남고 operator_id 만 NULL 이 된다")
  void operatorDeletionKeepsAction() {
    UUID op = operator();
    UUID author = member("TEST_au" + tag.substring(0, 6));
    long postId = postRow(author, "TEST 제목", "TEST 본문");
    removals.remove(op, ReportStore.Target.POST, postId);

    jdbc.sql("DELETE FROM app_user WHERE id = :id").param("id", op).update();

    assertThat(actions("post", postId)).isEqualTo(1);
    assertThat(action("post", postId).get("operator_id")).isNull();
  }

  // ── 픽스처 ──────────────────────────────────────────────────────

  /** 운영자 메일 신분이 붙은 회원. 저장된 메일은 대소문자·앞뒤 공백이 섞여 있다. */
  private UUID operator() {
    UUID id = member("TEST_op" + tag.substring(0, 6));
    identity(id, "  OP@Example.Test ");
    return id;
  }

  private void identity(UUID user, String email) {
    jdbc.sql(
            "INSERT INTO user_identity (provider, subject, user_id, email)"
                + " VALUES ('google', 'TEST-' || gen_random_uuid(), :u, :e)")
        .param("u", user)
        .param("e", email)
        .update();
  }

  private long postRow(UUID author, String title, String body) {
    return jdbc.sql(
            "INSERT INTO community_post (user_id, title, body) VALUES (:u, :t, :b) RETURNING id")
        .param("u", author)
        .param("t", title)
        .param("b", body)
        .query(Long.class)
        .single();
  }

  private long commentRow(long postId, UUID author, String body) {
    return jdbc.sql(
            "INSERT INTO community_comment (post_id, user_id, body) VALUES (:p, :u, :b)"
                + " RETURNING id")
        .param("p", postId)
        .param("u", author)
        .param("b", body)
        .query(Long.class)
        .single();
  }

  private long reviewRow(long placeId, UUID author, String body) {
    return jdbc.sql(
            "INSERT INTO review (place_id, user_id, rating, body) VALUES (:p, :u, 3, :b)"
                + " RETURNING id")
        .param("p", placeId)
        .param("u", author)
        .param("b", body)
        .query(Long.class)
        .single();
  }

  private OffsetDateTime removedAt(String table, long id) {
    // 표 이름은 이 파일의 상수 셋 중 하나다 — 바깥 입력이 아니다.
    return jdbc.sql("SELECT removed_at FROM " + table + " WHERE id = :id")
        .param("id", id)
        .query(OffsetDateTime.class)
        .optional()
        .orElse(null);
  }

  private long actions(String type, long id) {
    return jdbc.sql(
            "SELECT count(*) FROM moderation_action WHERE target_type = :t AND target_id = :id")
        .param("t", type)
        .param("id", id)
        .query(Long.class)
        .single();
  }

  private Map<String, Object> action(String type, long id) {
    return jdbc.sql(
            "SELECT operator_id, action, excerpt, created_at FROM moderation_action"
                + " WHERE target_type = :t AND target_id = :id")
        .param("t", type)
        .param("id", id)
        .query()
        .singleRow();
  }

  private RequestBuilder removal(UUID user, String type, long id) throws Exception {
    return post("/moderation/removals")
        .header("Authorization", bearer(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(Map.of("targetType", type, "targetId", id)));
  }
}
