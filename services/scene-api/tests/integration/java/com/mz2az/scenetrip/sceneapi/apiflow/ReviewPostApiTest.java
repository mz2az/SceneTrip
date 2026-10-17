package com.mz2az.scenetrip.sceneapi.apiflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * 리뷰·여행후기 — 사진 올리기 → 글에 붙이기 → 목록·상세 → 지우기 → 사진이 지울 목록({@code storage_deletion})에 오른다 (계약 {@code
 * createUpload}·{@code putMyPlaceReview}·{@code createPost}, post-photo-deletion.md §2·§6-2, 계획
 * §2).
 */
@DisplayName("API 통합 — 리뷰·여행후기")
class ReviewPostApiTest extends ApiIntegrationTest {

  @Test
  @DisplayName("리뷰: 사진 올리기 → 쓰기 → 목록·요약·내 리뷰 → 지우기 → 사진 키가 지울 목록에, 목록은 빈다")
  void reviewFlow() throws Exception {
    UUID me = member("TEST_rv" + tag.substring(0, 6));
    long p = place("TEST-리뷰장소-" + tag, SEA_LNG, SEA_LAT);
    String uploadKey = upload(me, "review");

    JsonNode saved =
        body(
            mvc.perform(
                    put("/places/{id}/reviews/me", p)
                        .header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            JSON.writeValueAsString(
                                Map.of(
                                    "rating",
                                    4,
                                    "body",
                                    "TEST 좋았다",
                                    "photoKeys",
                                    List.of(uploadKey)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rating").value(4))
                .andExpect(jsonPath("$.body").value("TEST 좋았다"))
                .andExpect(jsonPath("$.isMine").value(true))
                .andExpect(jsonPath("$.photos.length()").value(1))
                .andReturn());
    String photoKey = saved.path("photos").get(0).path("key").asString();

    mvc.perform(get("/places/{id}/reviews", p).header("Authorization", bearer(me)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].isMine").value(true))
        .andExpect(jsonPath("$.items[0].author.nickname").value("TEST_rv" + tag.substring(0, 6)))
        .andExpect(jsonPath("$.summary.count").value(1))
        .andExpect(jsonPath("$.summary.average").value(4.0))
        .andExpect(jsonPath("$.summary.distribution[3]").value(1));
    mvc.perform(get("/places/{id}/reviews", p))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].isMine").value(false));
    mvc.perform(get("/me/reviews").header("Authorization", bearer(me)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].target.type").value("place"))
        .andExpect(jsonPath("$.items[0].target.id").value(p));

    mvc.perform(delete("/places/{id}/reviews/me", p).header("Authorization", bearer(me)))
        .andExpect(status().isNoContent());

    assertThat(pendingDeletion(photoKey)).as("지운 리뷰의 사진 키가 지울 목록에").isTrue();
    mvc.perform(get("/places/{id}/reviews", p))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0))
        .andExpect(jsonPath("$.summary.count").value(0));
    mvc.perform(get("/places/{id}/reviews/me", p).header("Authorization", bearer(me)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
  }

  @Test
  @DisplayName("비회원(설치 UUID 만)은 리뷰를 쓸 수 없다 — 401 SIGN_IN_REQUIRED")
  void guestCannotReview() throws Exception {
    long p = place("TEST-비회원리뷰-" + tag, SEA_LNG, SEA_LAT);

    mvc.perform(
            put("/places/{id}/reviews/me", p)
                .header("X-Install-Id", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("rating", 5, "photoKeys", List.of()))))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));
  }

  @Test
  @DisplayName("내가 올리지 않은 사진 키는 400 REVIEW_PHOTO_INVALID — 리뷰는 생기지 않는다")
  void foreignPhotoKeyIsRejected() throws Exception {
    UUID me = member("TEST_fk" + tag.substring(0, 6));
    UUID other = member("TEST_ot" + tag.substring(0, 6));
    long p = place("TEST-남의사진-" + tag, SEA_LNG, SEA_LAT);
    String othersKey = upload(other, "review");

    mvc.perform(
            put("/places/{id}/reviews/me", p)
                .header("Authorization", bearer(me))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    JSON.writeValueAsString(Map.of("rating", 3, "photoKeys", List.of(othersKey)))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("REVIEW_PHOTO_INVALID"));
  }

  @Test
  @DisplayName("여행후기: 사진 올리기 → 쓰기 201 → 목록·상세 → 남은 못 지움 404 → 지우기 204 → 사진 키가 지울 목록에, 상세 404")
  void postFlow() throws Exception {
    UUID me = member("TEST_po" + tag.substring(0, 6));
    UUID other = member("TEST_px" + tag.substring(0, 6));
    String uploadKey = upload(me, "post");

    JsonNode created =
        body(
            mvc.perform(
                    post("/posts")
                        .header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            JSON.writeValueAsString(
                                Map.of(
                                    "title",
                                    "TEST-후기-" + tag,
                                    "body",
                                    "TEST 본문",
                                    "photoKeys",
                                    List.of(uploadKey)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("TEST-후기-" + tag))
                .andExpect(jsonPath("$.isMine").value(true))
                .andExpect(jsonPath("$.photoUrls.length()").value(1))
                .andReturn());
    long postId = created.path("id").asLong();
    List<String> storedKeys =
        jdbc.sql("SELECT storage_key FROM community_post_photo WHERE post_id = :p")
            .param("p", postId)
            .query(String.class)
            .list();
    assertThat(storedKeys).hasSize(1);

    mvc.perform(get("/me/posts").header("Authorization", bearer(me)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(postId))
        .andExpect(jsonPath("$.items[0].isMine").value(true));
    mvc.perform(get("/posts/{id}", postId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.body").value("TEST 본문"))
        .andExpect(jsonPath("$.isMine").value(false));

    mvc.perform(delete("/posts/{id}", postId).header("Authorization", bearer(other)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));

    mvc.perform(delete("/posts/{id}", postId).header("Authorization", bearer(me)))
        .andExpect(status().isNoContent());
    assertThat(pendingDeletion(storedKeys.get(0))).as("지운 후기의 사진 키가 지울 목록에").isTrue();
    mvc.perform(get("/posts/{id}", postId))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
  }

  /** 사진 올릴 주소를 받는다(저장소는 가짜). 돌아온 key 를 글에 붙인다. */
  private String upload(UUID user, String purpose) throws Exception {
    JsonNode ticket =
        body(
            mvc.perform(
                    post("/uploads")
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            JSON.writeValueAsString(
                                Map.of(
                                    "purpose",
                                    purpose,
                                    "contentType",
                                    "image/jpeg",
                                    "bytes",
                                    1234))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uploadUrl").exists())
                .andExpect(jsonPath("$.requiredHeaders").exists())
                .andExpect(jsonPath("$.expiresAt").exists())
                .andReturn());
    return ticket.path("key").asString();
  }

  private boolean pendingDeletion(String key) {
    return jdbc.sql("SELECT count(*) FROM storage_deletion WHERE storage_key = :k")
            .param("k", key)
            .query(Long.class)
            .single()
        == 1;
  }
}
