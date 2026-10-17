package com.mz2az.scenetrip.sceneapi.apiflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * 작품·촬영지·검색 — {@code GET /places}·{@code /places/{id}}·{@code /contents}·{@code
 * /contents/{id}/places} 를 실제 DB 까지(계약의 「검색 범위」·「언어」, 계획 §2).
 */
@DisplayName("API 통합 — 작품·촬영지·검색")
class ContentPlaceSearchApiTest extends ApiIntegrationTest {

  private static final String SEA_BBOX = "124.49,33.99,124.51,34.01";

  @Test
  @DisplayName("q 없이 지도 범위만 주면 그 안의 촬영지가 이름과 좌표로 나온다")
  void placesInBboxWithoutQuery() throws Exception {
    long p = place("TEST-범위-" + tag, SEA_LNG, SEA_LAT);

    JsonNode body =
        body(
            mvc.perform(get("/places").param("bbox", SEA_BBOX).header("Accept-Language", "ko"))
                .andExpect(status().isOk())
                .andReturn());

    JsonNode item = itemWithId(body, p);
    assertThat(item).as("범위 안의 시험 촬영지").isNotNull();
    assertThat(item.path("name").asString()).isEqualTo("TEST-범위-" + tag);
    assertThat(item.path("latitude").asDouble()).isEqualTo(SEA_LAT);
    assertThat(item.path("longitude").asDouble()).isEqualTo(SEA_LNG);
    assertThat(body.path("total").asInt()).isGreaterThanOrEqualTo(1);
  }

  @Test
  @DisplayName("q 가 장소 설명에 걸리면 장소 탭에 그 장소, 작품 탭에 거기서 찍은 작품이 나온다 (대칭)")
  void queryOnPlaceDescriptionFillsBothTabs() throws Exception {
    String token = "TESTqz" + tag;
    long p = place("TEST-설명-" + tag, SEA_LNG, SEA_LAT);
    jdbc.sql("UPDATE place_i18n SET description = :d WHERE place_id = :p")
        .param("d", "바다 위 시험 장소 " + token + " 입니다")
        .param("p", p)
        .update();
    long c = content("TEST-작품-" + tag, null);
    link(p, c);

    JsonNode places =
        body(mvc.perform(get("/places").param("q", token)).andExpect(status().isOk()).andReturn());
    assertThat(ids(places)).containsExactly(p);

    JsonNode contents =
        body(
            mvc.perform(get("/contents").param("q", token).header("Accept-Language", "ko"))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(ids(contents)).containsExactly(c);
    assertThat(contents.path("items").get(0).path("title").asString()).isEqualTo("TEST-작품-" + tag);
  }

  @Test
  @DisplayName("요청 언어의 이름이 없으면 en, en 도 없으면 ko 로 폴백한다")
  void languageFallsBackToEnglishThenKorean() throws Exception {
    long both = place("TEST-한글-" + tag, SEA_LNG, SEA_LAT);
    placeI18n(both, "en", "TEST-english-" + tag, null);
    long koOnly = place("TEST-한글만-" + tag, SEA_LNG, SEA_LAT);

    mvc.perform(get("/places/{id}", both).header("Accept-Language", "ja"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("TEST-english-" + tag));
    mvc.perform(get("/places/{id}", both).header("Accept-Language", "ko"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("TEST-한글-" + tag));
    mvc.perform(get("/places/{id}", koOnly).header("Accept-Language", "ja"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("TEST-한글만-" + tag));
  }

  @Test
  @DisplayName("지도 범위의 경도가 ±180 끝이어도 200 — 500 이 아니다 (MZ2AZ-182)")
  void worldBboxIsNotServerError() throws Exception {
    String token = "TESTqw" + tag;
    long p = place("TEST-세계-" + tag, SEA_LNG, SEA_LAT);
    jdbc.sql("UPDATE place_i18n SET description = :d WHERE place_id = :p")
        .param("d", token)
        .param("p", p)
        .update();

    JsonNode body =
        body(
            mvc.perform(get("/places").param("bbox", "-180,-90,180,90").param("q", token))
                .andExpect(status().isOk())
                .andReturn());

    assertThat(ids(body)).containsExactly(p);
  }

  @Test
  @DisplayName("아무것도 걸리지 않는 검색은 200 · 빈 items · total 0")
  void noMatchIsEmptyList() throws Exception {
    String token = "TESTnone" + tag;

    mvc.perform(get("/places").param("q", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty())
        .andExpect(jsonPath("$.total").value(0));
    mvc.perform(get("/contents").param("q", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty())
        .andExpect(jsonPath("$.total").value(0));
  }

  @Test
  @DisplayName("작품의 촬영지 목록에 그 작품 기준 장면 설명이 실린다")
  void contentPlacesCarrySceneDescription() throws Exception {
    long p = place("TEST-장면-" + tag, SEA_LNG, SEA_LAT);
    long c = content("TEST-장면작품-" + tag, null);
    long pc =
        jdbc.sql("INSERT INTO place_content (place_id, content_id) VALUES (:p, :c) RETURNING id")
            .param("p", p)
            .param("c", c)
            .query(Long.class)
            .single();
    jdbc.sql(
            "INSERT INTO place_content_i18n (place_content_id, lang, relation_description)"
                + " VALUES (:pc, 'ko', :d)")
        .param("pc", pc)
        .param("d", "TEST 2화의 바닷가 장면")
        .update();

    mvc.perform(get("/contents/{id}/places", c).header("Accept-Language", "ko"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].id").value(p))
        .andExpect(jsonPath("$.items[0].sceneDescription").value("TEST 2화의 바닷가 장면"));
  }

  @Test
  @DisplayName("없는 촬영지·작품은 404 와 각자의 코드")
  void missingTargetsAreNotFound() throws Exception {
    mvc.perform(get("/places/{id}", Long.MAX_VALUE))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PLACE_NOT_FOUND"));
    mvc.perform(get("/contents/{id}", Long.MAX_VALUE))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CONTENT_NOT_FOUND"));
  }

  static List<Long> ids(JsonNode list) {
    List<Long> ids = new ArrayList<>();
    list.path("items").forEach(n -> ids.add(n.path("id").asLong()));
    return ids;
  }

  static JsonNode itemWithId(JsonNode list, long id) {
    for (JsonNode n : list.path("items")) {
      if (n.path("id").asLong() == id) {
        return n;
      }
    }
    return null;
  }
}
