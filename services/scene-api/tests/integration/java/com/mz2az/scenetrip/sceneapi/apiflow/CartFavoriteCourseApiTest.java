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

/** 장바구니·찜·코스 — 비회원이 설치 UUID 하나로 담기 → 조회 → 지우기까지(계약 「인증」 2, 계획 §2). 남의 설치본에는 보이지 않는다. */
@DisplayName("API 통합 — 장바구니·찜·코스 (비회원)")
class CartFavoriteCourseApiTest extends ApiIntegrationTest {

  private final String install = UUID.randomUUID().toString();

  @Test
  @DisplayName("장바구니: 담기 201 → 조회에 있음 → 빼기 204 → 또 빼기 404 → 조회에 없음 → 다시 담기 201 → 또 담기 409")
  void cartFlow() throws Exception {
    long p = place("TEST-담기-" + tag, SEA_LNG, SEA_LAT);

    mvc.perform(addToCart(install, p))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.placeId").value(p))
        .andExpect(jsonPath("$.name").value("TEST-담기-" + tag))
        .andExpect(jsonPath("$.addedAt").exists());

    mvc.perform(get("/cart").header("X-Install-Id", install).header("Accept-Language", "ko"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalCount").value(1))
        .andExpect(jsonPath("$.items[0].placeId").value(p));

    mvc.perform(delete("/cart/items/{id}", p).header("X-Install-Id", install))
        .andExpect(status().isNoContent());
    mvc.perform(delete("/cart/items/{id}", p).header("X-Install-Id", install))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CART_ITEM_NOT_FOUND"));

    mvc.perform(get("/cart").header("X-Install-Id", install))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalCount").value(0))
        .andExpect(jsonPath("$.items").isEmpty());

    // 중복은 DB 기본키가 막는다. 제약 위반이 난 뒤 PostgreSQL 은 그 트랜잭션의 다음 문장을 받지 않으므로(시험은 한 트랜잭션)
    // 409 확인은 맨 끝에 둔다 — 운영에서는 요청마다 트랜잭션이 따로라 상관없다.
    mvc.perform(addToCart(install, p)).andExpect(status().isCreated());
    mvc.perform(addToCart(install, p))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_CART_ITEM"));
  }

  @Test
  @DisplayName("다른 설치본의 장바구니에는 보이지 않는다")
  void cartIsPerInstall() throws Exception {
    long p = place("TEST-남의것-" + tag, SEA_LNG, SEA_LAT);
    mvc.perform(addToCart(install, p)).andExpect(status().isCreated());

    mvc.perform(get("/cart").header("X-Install-Id", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalCount").value(0));
  }

  @Test
  @DisplayName("없는 촬영지는 404 PLACE_NOT_FOUND, 목록에서 빠진 촬영지는 409 PLACE_DELISTED")
  void cartRejectsMissingAndDelisted() throws Exception {
    mvc.perform(addToCart(install, Long.MAX_VALUE))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"));

    long hidden = place("TEST-숨김-" + tag, SEA_LNG, SEA_LAT);
    jdbc.sql("UPDATE place SET hidden_at = now() WHERE id = :p").param("p", hidden).update();
    mvc.perform(addToCart(install, hidden))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PLACE_DELISTED"));
  }

  @Test
  @DisplayName("X-Install-Id 가 없거나 UUID 가 아니면 400 MISSING_INSTALL_ID")
  void cartNeedsInstallId() throws Exception {
    mvc.perform(get("/cart"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_INSTALL_ID"));
    mvc.perform(get("/cart").header("X-Install-Id", "TEST-not-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_INSTALL_ID"));
  }

  @Test
  @DisplayName("찜: 찜하기 204(두 번째도 204) → 목록에 있음 → 해제 204 → 목록에 없음")
  void favoriteFlow() throws Exception {
    long c = content("TEST-찜-" + tag, null);

    mvc.perform(favorite(install, c)).andExpect(status().isNoContent());
    mvc.perform(favorite(install, c)).andExpect(status().isNoContent());

    assertThat(favoriteIds(install)).containsExactly(c);

    mvc.perform(delete("/favorites/contents/{id}", c).header("X-Install-Id", install))
        .andExpect(status().isNoContent());
    assertThat(favoriteIds(install)).isEmpty();
  }

  @Test
  @DisplayName("코스: 만들기 201 → 장소 담기(PUT) → 상세에 그 장소 → 남의 설치본은 404 → 지우기 204 → 404")
  void courseFlow() throws Exception {
    long p = place("TEST-코스장소-" + tag, SEA_LNG, SEA_LAT);

    JsonNode created =
        body(
            mvc.perform(
                    post("/courses")
                        .header("X-Install-Id", install)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            JSON.writeValueAsString(
                                Map.of(
                                    "dayCount", 2, "origin", "self", "title", "TEST-코스-" + tag))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("TEST-코스-" + tag))
                .andExpect(jsonPath("$.dayCount").value(2))
                .andExpect(jsonPath("$.days.length()").value(2))
                .andReturn());
    long courseId = created.path("id").asLong();

    String replace =
        JSON.writeValueAsString(
            Map.of(
                "title",
                "TEST-코스-" + tag,
                "days",
                List.of(
                    Map.of("items", List.of(Map.of("placeId", p))), Map.of("items", List.of()))));
    mvc.perform(
            put("/courses/{id}", courseId)
                .header("X-Install-Id", install)
                .contentType(MediaType.APPLICATION_JSON)
                .content(replace))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.placeCount").value(1));

    mvc.perform(
            get("/courses/{id}", courseId)
                .header("X-Install-Id", install)
                .header("Accept-Language", "ko"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.days[0].items[0].placeId").value(p))
        .andExpect(jsonPath("$.days[0].items[0].name").value("TEST-코스장소-" + tag))
        .andExpect(jsonPath("$.days[1].items").isEmpty());

    mvc.perform(get("/courses/{id}", courseId).header("X-Install-Id", UUID.randomUUID().toString()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COURSE_NOT_FOUND"));

    mvc.perform(delete("/courses/{id}", courseId).header("X-Install-Id", install))
        .andExpect(status().isNoContent());
    mvc.perform(get("/courses/{id}", courseId).header("X-Install-Id", install))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("COURSE_NOT_FOUND"));
  }

  private static org.springframework.test.web.servlet.RequestBuilder addToCart(
      String install, long placeId) throws Exception {
    return post("/cart/items")
        .header("X-Install-Id", install)
        .contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(Map.of("placeId", placeId)));
  }

  private static org.springframework.test.web.servlet.RequestBuilder favorite(
      String install, long contentId) throws Exception {
    return post("/favorites/contents")
        .header("X-Install-Id", install)
        .contentType(MediaType.APPLICATION_JSON)
        .content(JSON.writeValueAsString(Map.of("contentId", contentId)));
  }

  private List<Long> favoriteIds(String install) throws Exception {
    JsonNode body =
        body(
            mvc.perform(get("/favorites/contents").header("X-Install-Id", install))
                .andExpect(status().isOk())
                .andReturn());
    return ContentPlaceSearchApiTest.ids(body);
  }
}
