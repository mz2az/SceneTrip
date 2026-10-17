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
 * 편의시설 — {@code GET /pois}·{@code /pois/{id}} 를 실제 DB 까지. 표출 중단된 관광공사 번호의 안내·사진은 응답에서 빠진다
 * (poi-load-cleanup.md §3, 계획 §2).
 */
@DisplayName("API 통합 — 편의시설")
class PoiApiTest extends ApiIntegrationTest {

  @Test
  @DisplayName("지도 범위 안의 편의시설이 한국어 이름·분류·갈래와 함께 나온다")
  void listInBbox() throws Exception {
    long p = poi("TEST-편의-" + tag, SEA_LNG, SEA_LAT);

    JsonNode body =
        body(
            mvc.perform(
                    get("/pois")
                        .param("bbox", "124.49,33.99,124.51,34.01")
                        .header("Accept-Language", "en"))
                .andExpect(status().isOk())
                .andReturn());

    JsonNode item = null;
    for (JsonNode n : body.path("items")) {
      if (n.path("id").asLong() == p) {
        item = n;
      }
    }
    assertThat(item).as("범위 안의 시험 편의시설").isNotNull();
    assertThat(item.path("name").asString()).isEqualTo("TEST-편의-" + tag);
    assertThat(item.path("category").asString()).isEqualTo("관광지");
    assertThat(item.path("categoryGroup").asString()).isEqualTo("sight");
  }

  @Test
  @DisplayName("관광공사 번호가 살아 있으면 상세에 안내·사진이 있고, 표출 중단되면 둘 다 빠진다 — 번호 없는 사진은 남는다")
  void inactiveTourNumberHidesInfoAndPhotos() throws Exception {
    long p = poi("TEST-관광-" + tag, SEA_LNG, SEA_LAT);
    String no = "TEST" + tag;
    jdbc.sql(
            "INSERT INTO poi_source (source_id, poi_id, source, active) VALUES (:s, :p, 'tour',"
                + " true)")
        .param("s", "tour-" + no)
        .param("p", p)
        .update();
    jdbc.sql(
            "INSERT INTO poi_detail (poi_id, lang, overview, tour_content_id)"
                + " VALUES (:p, 'ko', 'TEST 살아 있는 개요', :c)")
        .param("p", p)
        .param("c", no)
        .update();
    image(p, "https://img.test/" + tag + "/tour.jpg", 10, no);
    image(p, "https://img.test/" + tag + "/own.jpg", 20, null);

    mvc.perform(get("/pois/{id}", p).header("Accept-Language", "ko"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.info.overview.text").value("TEST 살아 있는 개요"));
    assertThat(imageUrls(p))
        .containsExactly(
            "https://img.test/" + tag + "/tour.jpg", "https://img.test/" + tag + "/own.jpg");

    jdbc.sql("UPDATE poi_source SET active = false WHERE source_id = :s")
        .param("s", "tour-" + no)
        .update();

    mvc.perform(get("/pois/{id}", p).header("Accept-Language", "ko"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.info").doesNotExist())
        .andExpect(jsonPath("$.name").value("TEST-관광-" + tag));
    assertThat(imageUrls(p)).containsExactly("https://img.test/" + tag + "/own.jpg");
  }

  @Test
  @DisplayName("폐업한 편의시설의 상세는 404 POI_NOT_FOUND, 없는 번호도 같다")
  void closedOrMissingPoiIsNotFound() throws Exception {
    long p = poi("TEST-폐업-" + tag, SEA_LNG, SEA_LAT);
    mvc.perform(get("/pois/{id}", p)).andExpect(status().isOk());

    jdbc.sql("UPDATE poi SET closed_at = now() WHERE id = :p").param("p", p).update();

    mvc.perform(get("/pois/{id}", p))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POI_NOT_FOUND"));
    mvc.perform(get("/pois/{id}", Long.MAX_VALUE))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POI_NOT_FOUND"));
  }

  @Test
  @DisplayName("영역 조건이 없으면 400 MISSING_AREA_FILTER")
  void listWithoutAreaIsBadRequest() throws Exception {
    mvc.perform(get("/pois"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_AREA_FILTER"));
  }

  private void image(long poiId, String url, int sort, String tourContentId) {
    jdbc.sql(
            "INSERT INTO poi_image (poi_id, url, sort_order, source, tour_content_id)"
                + " VALUES (:p, :u, :o, 'tour_api', :c)")
        .param("p", poiId)
        .param("u", url)
        .param("o", sort)
        .param("c", tourContentId)
        .update();
  }

  private List<String> imageUrls(long poiId) throws Exception {
    JsonNode body =
        body(mvc.perform(get("/pois/{id}", poiId)).andExpect(status().isOk()).andReturn());
    List<String> urls = new ArrayList<>();
    body.path("images").forEach(n -> urls.add(n.path("url").asString()));
    return urls;
  }
}
