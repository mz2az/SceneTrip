package com.mz2az.scenetrip.sceneapi.apiflow;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * 스프링 시작 + 실제 DB + <b>Flyway 를 켠 채</b> — 장바구니의 편의시설(계약 1.31.0, docs/project/plans/keep-poi.md §2
 * 「시험」).
 *
 * <p>다른 API 통합 시험({@link ApiIntegrationTest})은 미리 올린 스키마를 쓰므로 Flyway 를 끈다. 그러면 「새 마이그레이션이 앱 기동 때 돌지
 * 않았다」 는 실패를 아무 레인도 보지 못한다(CLAUDE.md §3 「Test lanes」 의 틈). 이 시험은 같은 바탕에서 Flyway 만 켠다 — 기동이 마이그레이션을
 * 올리고, 그 뒤 {@code GET /cart} 가 {@code saved_poi} 를 실제로 읽어 {@code pois: []}·{@code poiCount: 0} 을
 * 주는지 본다.
 *
 * <p>주의: Flyway 는 시험 트랜잭션 밖(기동 때)에서 돌므로, 아직 올라가지 않은 마이그레이션은 이 시험이 붙는 DB(로컬 kind·CI 서비스 컨테이너)에
 * <b>실제로 적용된다</b>. 운영 기동과 같은 동작이다.
 */
@TestPropertySource(properties = "spring.flyway.enabled=true")
@DisplayName("API 통합 — 장바구니 편의시설 (Flyway 켬)")
class CartPoiFlywayApiTest extends ApiIntegrationTest {

  private final String install = UUID.randomUUID().toString();

  @Test
  @DisplayName("Flyway 를 켜고 띄운 앱에서 GET /cart 는 200, 본문에 pois: [] · poiCount: 0")
  void emptyCartCarriesPoisAfterFlywayStartup() throws Exception {
    mvc.perform(get("/cart").header("X-Install-Id", install).header("Accept-Language", "ko"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isEmpty())
        .andExpect(jsonPath("$.totalCount").value(0))
        .andExpect(jsonPath("$.pois").isArray())
        .andExpect(jsonPath("$.pois").isEmpty())
        .andExpect(jsonPath("$.poiCount").value(0));
  }

  @Test
  @DisplayName("담기 201 → GET /cart 의 pois 에 있음(items 아님) → 빼기 204 → 또 빼기 404")
  void addListRemoveThroughTheApp() throws Exception {
    long poi = poi("TEST-편의시설담기-" + tag, SEA_LNG, SEA_LAT);

    mvc.perform(
            post("/cart/pois")
                .header("X-Install-Id", install)
                .header("Accept-Language", "ko")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"poiId\":" + poi + "}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(poi))
        .andExpect(jsonPath("$.name").value("TEST-편의시설담기-" + tag))
        .andExpect(jsonPath("$.addedAt").exists())
        .andExpect(jsonPath("$.closed").value(false));

    mvc.perform(get("/cart").header("X-Install-Id", install))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalCount").value(0))
        .andExpect(jsonPath("$.items").isEmpty())
        .andExpect(jsonPath("$.poiCount").value(1))
        .andExpect(jsonPath("$.pois[0].id").value(poi));

    mvc.perform(delete("/cart/pois/{id}", poi).header("X-Install-Id", install))
        .andExpect(status().isNoContent());
    mvc.perform(delete("/cart/pois/{id}", poi).header("X-Install-Id", install))
        .andExpect(status().isNotFound());

    mvc.perform(
            post("/cart/pois")
                .header("X-Install-Id", install)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"poiId\":" + Long.MAX_VALUE + "}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POI_NOT_FOUND"));
  }
}
