package com.mz2az.scenetrip.sceneapi.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PlaceDetail;
import com.mz2az.scenetrip.sceneapi.api.model.PlaceSummary;
import com.mz2az.scenetrip.sceneapi.api.model.RatingSummary;
import com.mz2az.scenetrip.sceneapi.api.model.Scene;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.place.PlaceStore;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 계약 1.32.0 의 촬영지 칸이 JSON 에 나오는지 — {@code Scene.sceneImageThumbnailUrls}·{@code
 * sceneImageThumbnailUrl}, {@code PlaceSummary.nameKo}·{@code sceneImageThumbnailUrl}(계획 {@code
 * web-ad-landing.md} §10-2). 값을 정하는 SQL 은 통합 레인({@code SceneThumbnailIntegrationTest})이 본다.
 */
@WebMvcTest(PlacesController.class)
@Import(LanguageConfiguration.class)
class PlacesSceneThumbnailControllerTest {

  @MockitoBean private AccessTokens rateLimitTokens;

  @Autowired private MockMvc mvc;

  @MockitoBean private PlaceStore store;

  @MockitoBean private ReviewViews reviews;

  private static final URI FULL1 = URI.create("https://media.example/scene/a/1.png");
  private static final URI FULL2 = URI.create("https://media.example/scene/a/2.png");
  private static final URI THUMB1 = URI.create("https://media.example/scene/thumb-720/a/1.jpg");

  @BeforeEach
  void noReviews() {
    when(reviews.summary(any(), anyLong()))
        .thenReturn(new RatingSummary(0, List.of(0, 0, 0, 0, 0)));
    when(reviews.gallery(any(), anyLong(), anyInt(), anyInt()))
        .thenReturn(new ReviewViews.Gallery(List.of(), 0));
  }

  @Test
  @DisplayName(
      "상세의 장면 — sceneImageThumbnailUrls 는 원본과 같은 길이·순서, sceneImageThumbnailUrl 은 첫 장. 없으면 [] 와 빠짐")
  void detailScenesCarryThumbnails() throws Exception {
    PlaceDetail detail =
        new PlaceDetail(2L, "송광사", 35.0, 127.2)
            .scenes(
                List.of(
                    new Scene(1L, "두 장", List.of(FULL1, FULL2))
                        .sceneImageUrl(FULL1)
                        .sceneImageThumbnailUrls(List.of(THUMB1, FULL2))
                        .sceneImageThumbnailUrl(THUMB1),
                    new Scene(2L, "없음", List.of()).sceneImageThumbnailUrls(List.of())));
    when(store.findDetail(eq(2L), any(), any(), any()))
        .thenReturn(Optional.of(new PlaceStore.Detail(detail, Lang.KO)));

    mvc.perform(get("/places/2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scenes[0].sceneImageThumbnailUrls.length()").value(2))
        .andExpect(jsonPath("$.scenes[0].sceneImageThumbnailUrls[0]").value(THUMB1.toString()))
        .andExpect(jsonPath("$.scenes[0].sceneImageThumbnailUrls[1]").value(FULL2.toString()))
        .andExpect(jsonPath("$.scenes[0].sceneImageThumbnailUrl").value(THUMB1.toString()))
        .andExpect(jsonPath("$.scenes[1].sceneImageThumbnailUrls.length()").value(0))
        .andExpect(jsonPath("$.scenes[1].sceneImageThumbnailUrl").doesNotExist());
  }

  @Test
  @DisplayName("목록 — nameKo 가 나오고, 없으면 빠진다")
  void listCarriesNameKo() throws Exception {
    when(store.list(any()))
        .thenReturn(
            new PlaceStore.Page(
                List.of(
                    new PlaceSummary(1L, "Yeouido Park", 37.5, 126.9).nameKo("여의도공원"),
                    new PlaceSummary(2L, "English Only", 37.6, 127.0)),
                2,
                Set.of(Lang.EN)));

    mvc.perform(get("/places").header("Accept-Language", "en"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].name").value("Yeouido Park"))
        .andExpect(jsonPath("$.items[0].nameKo").value("여의도공원"))
        .andExpect(jsonPath("$.items[1].nameKo").doesNotExist());
  }
}
