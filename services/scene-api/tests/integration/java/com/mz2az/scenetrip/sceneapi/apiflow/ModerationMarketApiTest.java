package com.mz2az.scenetrip.sceneapi.apiflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * 신고·차단·마켓 — 차단한 사용자의 리뷰·여행후기가 내 화면에서 빠지고, 신고는 한 번으로 세며, 마켓의 남의 코스는 내릴 수 없다
 * (community-moderation.md, 계약 {@code createBlock}·{@code createReport}·{@code deleteMarketCourse},
 * 계획 §2).
 */
@DisplayName("API 통합 — 신고·차단·마켓")
class ModerationMarketApiTest extends ApiIntegrationTest {

  private final String install = UUID.randomUUID().toString();

  @Test
  @DisplayName("차단: 그 사람의 리뷰가 내 목록에서 빠지고(별점 요약은 그대로) 후기 상세는 404 — 남에게는 보이고, 풀면 다시 보인다")
  void blockHidesAuthorsContentForMeOnly() throws Exception {
    UUID viewer = member("TEST_vw" + tag.substring(0, 6));
    UUID author = member("TEST_au" + tag.substring(0, 6));
    long p = place("TEST-차단장소-" + tag, SEA_LNG, SEA_LAT);
    long reviewId = review(author, p, 2);
    long postId = writePost(author);

    JsonNode block =
        body(
            mvc.perform(block(viewer, reviewId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nickname").value("TEST_au" + tag.substring(0, 6)))
                .andReturn());
    long blockId = block.path("id").asLong();
    mvc.perform(block(viewer, reviewId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(blockId));

    JsonNode mine = reviews(p, bearer(viewer));
    assertThat(reviewIds(mine)).doesNotContain(reviewId);
    assertThat(mine.path("summary").path("count").asInt()).isEqualTo(1);
    assertThat(reviewIds(reviews(p, null))).containsExactly(reviewId);
    mvc.perform(get("/posts/{id}", postId).header("Authorization", bearer(viewer)))
        .andExpect(status().isNotFound());
    mvc.perform(get("/posts/{id}", postId)).andExpect(status().isOk());
    mvc.perform(get("/me/blocks").header("Authorization", bearer(viewer)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(blockId));

    mvc.perform(delete("/me/blocks/{id}", blockId).header("Authorization", bearer(viewer)))
        .andExpect(status().isNoContent());
    assertThat(reviewIds(reviews(p, bearer(viewer)))).containsExactly(reviewId);
    mvc.perform(delete("/me/blocks/{id}", blockId).header("Authorization", bearer(viewer)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("BLOCK_NOT_FOUND"));
  }

  @Test
  @DisplayName(
      "차단: 비회원은 401 SIGN_IN_REQUIRED, 없는 글은 404 BLOCK_TARGET_NOT_FOUND, 자기 글은 400"
          + " CANNOT_BLOCK_SELF")
  void blockErrors() throws Exception {
    UUID me = member("TEST_be" + tag.substring(0, 6));
    long p = place("TEST-차단오류-" + tag, SEA_LNG, SEA_LAT);
    long myReview = review(me, p, 5);

    mvc.perform(
            post("/me/blocks")
                .header("X-Install-Id", install)
                .contentType(MediaType.APPLICATION_JSON)
                .content(blockBody(myReview)))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
    mvc.perform(block(me, Long.MAX_VALUE))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("BLOCK_TARGET_NOT_FOUND"));
    mvc.perform(block(me, myReview))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CANNOT_BLOCK_SELF"));
  }

  @Test
  @DisplayName("신고: 비회원 신고 204, 같은 글 두 번째도 204 이고 한 줄 — 자기 글은 400 CANNOT_REPORT_OWN, 없는 글은 404")
  void reportFlow() throws Exception {
    UUID author = member("TEST_rp" + tag.substring(0, 6));
    long p = place("TEST-신고장소-" + tag, SEA_LNG, SEA_LAT);
    long reviewId = review(author, p, 1);

    mvc.perform(report(null, reviewId)).andExpect(status().isNoContent());
    mvc.perform(report(null, reviewId)).andExpect(status().isNoContent());
    assertThat(
            jdbc.sql(
                    "SELECT count(*) FROM content_report WHERE target_type = 'review' AND target_id"
                        + " = :r")
                .param("r", reviewId)
                .query(Long.class)
                .single())
        .isEqualTo(1);

    mvc.perform(report(author, reviewId))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CANNOT_REPORT_OWN"));
    mvc.perform(report(null, Long.MAX_VALUE))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("REPORT_TARGET_NOT_FOUND"));
  }

  @Test
  @DisplayName("마켓: 비회원 올리기 401, 회원 올리기 201 → 상세 → 남이 내리기 403 → 내가 내리기 204 → 404")
  void marketFlow() throws Exception {
    UUID me = member("TEST_mk" + tag.substring(0, 6));
    UUID other = member("TEST_mo" + tag.substring(0, 6));
    long p = place("TEST-마켓장소-" + tag, SEA_LNG, SEA_LAT);
    long courseId = courseWith(me, p);
    String publish =
        JSON.writeValueAsString(Map.of("courseId", courseId, "description", "TEST 마켓 코스"));

    mvc.perform(
            post("/market/courses")
                .header("X-Install-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(publish))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));

    long marketId =
        body(mvc.perform(asMember(me, post("/market/courses")).content(publish))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").value("TEST 마켓 코스"))
                .andExpect(jsonPath("$.placeCount").value(1))
                .andReturn())
            .path("id")
            .asLong();

    mvc.perform(get("/market/courses/{id}", marketId).header("X-Install-Id", install))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.days[0].items[0].placeId").value(p));
    mvc.perform(asMember(other, delete("/market/courses/{id}", marketId)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("NOT_MARKET_COURSE_AUTHOR"));
    mvc.perform(asMember(me, delete("/market/courses/{id}", marketId)))
        .andExpect(status().isNoContent());
    mvc.perform(get("/market/courses/{id}", marketId).header("X-Install-Id", install))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("MARKET_COURSE_NOT_FOUND"));
  }

  @Test
  @DisplayName("마켓: 이미 올라간 코스를 또 올리면 409 COURSE_ALREADY_PUBLISHED")
  void marketRepublishConflict() throws Exception {
    UUID me = member("TEST_mr" + tag.substring(0, 6));
    long courseId = courseWith(me, place("TEST-재게시-" + tag, SEA_LNG, SEA_LAT));
    String publish =
        JSON.writeValueAsString(Map.of("courseId", courseId, "description", "TEST 마켓 코스"));

    mvc.perform(asMember(me, post("/market/courses")).content(publish))
        .andExpect(status().isCreated());
    // 제약 위반 뒤에는 이 트랜잭션에 더 보낼 수 없다(장바구니 중복과 같은 이유) — 맨 끝.
    mvc.perform(asMember(me, post("/market/courses")).content(publish))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("COURSE_ALREADY_PUBLISHED"));
  }

  // ── 돕는 것 ────────────────────────────────────────────────────────────

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder asMember(
      UUID user, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b) {
    return b.header("Authorization", bearer(user))
        .header("X-Install-Id", UUID.randomUUID().toString())
        .contentType(MediaType.APPLICATION_JSON);
  }

  private long review(UUID author, long placeId, int rating) throws Exception {
    return body(mvc.perform(
                put("/places/{id}/reviews/me", placeId)
                    .header("Authorization", bearer(author))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        JSON.writeValueAsString(
                            Map.of("rating", rating, "body", "TEST 리뷰", "photoKeys", List.of()))))
            .andExpect(status().isOk())
            .andReturn())
        .path("id")
        .asLong();
  }

  private long writePost(UUID author) throws Exception {
    return body(mvc.perform(
                post("/posts")
                    .header("Authorization", bearer(author))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        JSON.writeValueAsString(
                            Map.of(
                                "title",
                                "TEST-차단후기-" + tag,
                                "body",
                                "TEST",
                                "photoKeys",
                                List.of()))))
            .andExpect(status().isCreated())
            .andReturn())
        .path("id")
        .asLong();
  }

  private long courseWith(UUID user, long placeId) throws Exception {
    long id =
        body(mvc.perform(
                    asMember(user, post("/courses"))
                        .content(JSON.writeValueAsString(Map.of("dayCount", 1, "origin", "self"))))
                .andExpect(status().isCreated())
                .andReturn())
            .path("id")
            .asLong();
    mvc.perform(
            asMember(user, put("/courses/{id}", id))
                .content(
                    JSON.writeValueAsString(
                        Map.of(
                            "title",
                            "TEST-마켓코스-" + tag,
                            "days",
                            List.of(Map.of("items", List.of(Map.of("placeId", placeId))))))))
        .andExpect(status().isOk());
    return id;
  }

  private RequestBuilder block(UUID user, long reviewId) throws Exception {
    return post("/me/blocks")
        .header("Authorization", bearer(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content(blockBody(reviewId));
  }

  private static String blockBody(long reviewId) throws Exception {
    return JSON.writeValueAsString(Map.of("targetType", "review", "targetId", reviewId));
  }

  private RequestBuilder report(UUID user, long reviewId) throws Exception {
    var b =
        post("/reports")
            .header("X-Install-Id", install)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                JSON.writeValueAsString(
                    Map.of("targetType", "review", "targetId", reviewId, "reason", "spam")));
    if (user != null) {
      b.header("Authorization", bearer(user));
    }
    return b;
  }

  private JsonNode reviews(long placeId, String auth) throws Exception {
    var b = get("/places/{id}/reviews", placeId);
    if (auth != null) {
      b.header("Authorization", auth);
    }
    return body(mvc.perform(b).andExpect(status().isOk()).andReturn());
  }

  private static List<Long> reviewIds(JsonNode list) {
    List<Long> ids = new ArrayList<>();
    list.path("items").forEach(n -> ids.add(n.path("id").asLong()));
    return ids;
  }
}
