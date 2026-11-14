package com.mz2az.scenetrip.sceneapi.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PlaceSummary;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.content.ContentStore;
import com.mz2az.scenetrip.sceneapi.place.PlaceStore;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /contents/{id}/places} 의 1.32.0 칸 — 항목마다 그 작품 장면 첫 장의 작은 판({@code
 * sceneImageThumbnailUrl})과 한국어 이름({@code nameKo}). 랜딩이 촬영지마다 상세를 따로 부르지 않게(계획 {@code
 * web-ad-landing.md} §10-2).
 */
@WebMvcTest(ContentsController.class)
@Import(LanguageConfiguration.class)
class ContentPlacesSceneThumbnailControllerTest {

  @MockitoBean private AccessTokens rateLimitTokens;

  @Autowired private MockMvc mvc;

  @MockitoBean private ContentStore store;

  @MockitoBean private PlaceStore placeStore;

  @Test
  @DisplayName("항목에 sceneImageThumbnailUrl·nameKo 가 나오고, 장면 사진이 없는 항목은 빠진다")
  void contentPlacesCarrySceneThumbnailAndNameKo() throws Exception {
    URI thumb = URI.create("https://media.example/scene/thumb-720/c/1.jpg");
    when(store.exists(7L)).thenReturn(true);
    when(placeStore.list(any()))
        .thenReturn(
            new PlaceStore.Page(
                List.of(
                    new PlaceSummary(1L, "Namsan Tower", 37.55, 126.98)
                        .nameKo("남산서울타워")
                        .sceneImageThumbnailUrl(thumb),
                    new PlaceSummary(2L, "No Scene", 37.56, 126.99).nameKo("장면 없음")),
                2,
                Set.of(Lang.EN)));

    mvc.perform(get("/contents/7/places").header("Accept-Language", "en"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].sceneImageThumbnailUrl").value(thumb.toString()))
        .andExpect(jsonPath("$.items[0].nameKo").value("남산서울타워"))
        .andExpect(jsonPath("$.items[1].sceneImageThumbnailUrl").doesNotExist())
        .andExpect(jsonPath("$.items[1].nameKo").value("장면 없음"));
  }
}
