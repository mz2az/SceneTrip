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
import com.mz2az.scenetrip.sceneapi.api.model.RatingSummary;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.poi.PoiStore;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverLinks;
import com.mz2az.scenetrip.sceneapi.poi.naver.PoiCardService;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
 * {@code GET /pois} 의 새 쿼리 {@code categories} — 계약 1.31.0, docs/project/plans/keep-poi.md §1-5.
 *
 * <p>쉼표로 나눈 세부 종류(한국어 원본)가 {@link PoiStore.Criteria#categories()} 로 넘어간다 — 앞뒤 공백을 떼고 빈 조각은 버린 목록이다.
 * 파라미터가 없으면 거르지 않는다(null 또는 빈 목록). 10 개를 넘거나 조각이 하나도 남지 않으면 400 {@code INVALID_PARAMETER} 이고 스토어를
 * 부르지 않는다. SQL 은 통합 레인({@code poi.PoiCategoriesIntegrationTest})이 본다.
 */
@WebMvcTest(PoisController.class)
@Import(LanguageConfiguration.class)
class PoisCategoriesControllerTest {

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

  private static String names(int count) {
    return IntStream.rangeClosed(1, count).mapToObj(i -> "종류" + i).collect(Collectors.joining(","));
  }

  @Test
  @DisplayName("categories 의 조각들이 앞뒤 공백을 떼고 빈 조각을 버린 순서대로 스토어에 간다")
  void categoriesReachStoreTrimmed() throws Exception {
    mvc.perform(get("/pois").param("bbox", BBOX).param("categories", "쇼핑, 관광지 ,,문화시설"))
        .andExpect(status().isOk());

    assertThat(passed().categories()).containsExactly("쇼핑", "관광지", "문화시설");
  }

  @Test
  @DisplayName("categoryGroup 과 함께 주면 둘 다 스토어에 간다")
  void categoriesWithGroup() throws Exception {
    mvc.perform(
            get("/pois")
                .param("bbox", BBOX)
                .param("categoryGroup", "sight")
                .param("categories", "쇼핑"))
        .andExpect(status().isOk());

    PoiStore.Criteria c = passed();
    assertThat(c.categoryGroup()).isEqualTo(PoiCategoryGroup.SIGHT);
    assertThat(c.categories()).containsExactly("쇼핑");
  }

  @Test
  @DisplayName("categories 가 없으면 거르지 않는다 — null 또는 빈 목록")
  void absentCategoriesMeansNoFilter() throws Exception {
    mvc.perform(get("/pois").param("bbox", BBOX)).andExpect(status().isOk());

    List<String> categories = passed().categories();
    assertThat(categories == null || categories.isEmpty()).isTrue();
  }

  @Test
  @DisplayName("sort=spread 와 함께여도 categories 가 스토어에 간다(칸 순위는 거른 것 안에서)")
  void categoriesWithSpread() throws Exception {
    mvc.perform(
            get("/pois")
                .param("bbox", BBOX)
                .param("sort", "spread")
                .param("spreadZoom", "14")
                .param("categories", "쇼핑"))
        .andExpect(status().isOk());

    PoiStore.Criteria c = passed();
    assertThat(c.sort()).isEqualTo(PoiStore.Sort.SPREAD);
    assertThat(c.categories()).containsExactly("쇼핑");
  }

  @Test
  @DisplayName("10 개는 된다 — 경계")
  void tenCategoriesAreAccepted() throws Exception {
    mvc.perform(get("/pois").param("bbox", BBOX).param("categories", names(10)))
        .andExpect(status().isOk());

    assertThat(passed().categories()).hasSize(10);
  }

  @Test
  @DisplayName("11 개면 400 INVALID_PARAMETER — 스토어를 부르지 않는다")
  void elevenCategoriesAreRejected() throws Exception {
    mvc.perform(get("/pois").param("bbox", BBOX).param("categories", names(11)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

    verify(store, never()).list(any());
  }

  @Test
  @DisplayName("빈 조각을 버리면 하나도 남지 않을 때(,, · 공백뿐) 400 INVALID_PARAMETER")
  void onlyEmptyPiecesAreRejected() throws Exception {
    mvc.perform(get("/pois").param("bbox", BBOX).param("categories", ",,"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

    mvc.perform(get("/pois").param("bbox", BBOX).param("categories", " , "))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

    verify(store, never()).list(any());
  }

  @Test
  @DisplayName("빈 조각은 개수에 들지 않는다 — 10 개 + 빈 조각 여럿은 된다")
  void emptyPiecesDoNotCount() throws Exception {
    mvc.perform(get("/pois").param("bbox", BBOX).param("categories", names(10) + ",,,"))
        .andExpect(status().isOk());

    assertThat(passed().categories()).hasSize(10);
  }
}
