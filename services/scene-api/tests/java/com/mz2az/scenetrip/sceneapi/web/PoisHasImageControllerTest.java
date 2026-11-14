package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PoiCategoryGroup;
import com.mz2az.scenetrip.sceneapi.api.model.PoiSummary;
import com.mz2az.scenetrip.sceneapi.api.model.RatingSummary;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.poi.PoiStore;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverLinks;
import com.mz2az.scenetrip.sceneapi.poi.naver.PoiCardService;
import java.net.URI;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /pois} 의 {@code hasImage} 와 항목의 {@code imageUrl} — 계약 1.31.0,
 * docs/project/plans/keep-poi.md §1-5b.
 *
 * <p>{@link PoiStore.Criteria} 의 마지막 칸이 {@code Boolean hasImage} 다({@code categories} 뒤). {@code
 * true} 일 때만 거른다 — {@code false} 나 없음은 null 또는 {@code FALSE} 로 넘어가고 거르지 않는다. SQL 은 통합 레인({@code
 * poi.PoiImageUrlIntegrationTest})이 본다.
 */
@WebMvcTest(PoisController.class)
@Import(LanguageConfiguration.class)
class PoisHasImageControllerTest {

  /** 분당 상한 필터(RequestRateLimitFilter)가 Bearer 토큰을 읽는다 — 이 시험은 토큰을 보내지 않는다. */
  @MockitoBean private AccessTokens rateLimitTokens;

  @Autowired private MockMvc mvc;
  @MockitoBean private PoiStore store;
  @MockitoBean private PoiCardService cards;
  @MockitoBean private NaverLinks naverLinks;
  @MockitoBean private ReviewViews reviews;

  private static final String BBOX = "127.017,37.489,127.037,37.507";

  @BeforeEach
  void stubs() {
    when(reviews.summary(any(), anyLong()))
        .thenReturn(new RatingSummary(0, List.of(0, 0, 0, 0, 0)));
    when(reviews.gallery(any(), anyLong(), anyInt(), anyInt()))
        .thenReturn(new ReviewViews.Gallery(List.of(), 0));
    when(store.list(any())).thenReturn(new PoiStore.Page(List.of(), 0, Set.of(Lang.KO)));
  }

  private PoiStore.Criteria passed() {
    ArgumentCaptor<PoiStore.Criteria> captor = ArgumentCaptor.forClass(PoiStore.Criteria.class);
    verify(store).list(captor.capture());
    return captor.getValue();
  }

  private static boolean notFiltering(Boolean hasImage) {
    return hasImage == null || !hasImage;
  }

  @Test
  @DisplayName("hasImage=true 가 스토어에 TRUE 로 간다")
  void trueReachesStore() throws Exception {
    mvc.perform(get("/pois").param("bbox", BBOX).param("hasImage", "true"))
        .andExpect(status().isOk());

    assertThat(passed().hasImage()).isTrue();
  }

  @Test
  @DisplayName("hasImage 가 없으면 거르지 않는다 — null 또는 FALSE")
  void absentMeansNoFilter() throws Exception {
    mvc.perform(get("/pois").param("bbox", BBOX)).andExpect(status().isOk());

    assertThat(notFiltering(passed().hasImage())).isTrue();
  }

  @Test
  @DisplayName("hasImage=false 도 거르지 않는다 — null 또는 FALSE")
  void falseMeansNoFilter() throws Exception {
    mvc.perform(get("/pois").param("bbox", BBOX).param("hasImage", "false"))
        .andExpect(status().isOk());

    assertThat(notFiltering(passed().hasImage())).isTrue();
  }

  @Test
  @DisplayName("hasImage=abc 는 400 INVALID_PARAMETER — 스토어를 부르지 않는다")
  void malformedIsRejected() throws Exception {
    mvc.perform(get("/pois").param("bbox", BBOX).param("hasImage", "abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

    verify(store, never()).list(any());
  }

  @Test
  @DisplayName("categories·categoryGroup·sort=spread 와 함께 주면 모두 스토어에 간다")
  void combinesWithOtherFilters() throws Exception {
    mvc.perform(
            get("/pois")
                .param("bbox", BBOX)
                .param("categoryGroup", "sight")
                .param("categories", "쇼핑")
                .param("sort", "spread")
                .param("spreadZoom", "14")
                .param("hasImage", "true"))
        .andExpect(status().isOk());

    PoiStore.Criteria c = passed();
    assertThat(c.hasImage()).isTrue();
    assertThat(c.categories()).containsExactly("쇼핑");
    assertThat(c.categoryGroup()).isEqualTo(PoiCategoryGroup.SIGHT);
    assertThat(c.sort()).isEqualTo(PoiStore.Sort.SPREAD);
  }

  @Test
  @DisplayName("응답 항목에 imageUrl 이 실린다 — 사진이 없는 항목에는 없다")
  void itemsCarryImageUrl() throws Exception {
    when(store.list(any()))
        .thenReturn(
            new PoiStore.Page(
                List.of(
                    new PoiSummary(1L, "경복궁", "관광지", PoiCategoryGroup.SIGHT, 37.5796, 126.977)
                        .imageUrl(URI.create("https://img.example/gyeongbokgung.jpg")),
                    new PoiSummary(2L, "동네김밥", "한식", PoiCategoryGroup.FOOD, 37.58, 126.978)),
                2,
                Set.of(Lang.KO)));

    mvc.perform(get("/pois").param("bbox", BBOX))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].imageUrl").value("https://img.example/gyeongbokgung.jpg"))
        .andExpect(jsonPath("$.items[1].imageUrl").doesNotExist());
  }
}
