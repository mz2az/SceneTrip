package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.api.model.PoiCategoryGroup;
import com.mz2az.scenetrip.sceneapi.api.model.PoiDetail;
import com.mz2az.scenetrip.sceneapi.api.model.PoiSummary;
import com.mz2az.scenetrip.sceneapi.place.Bbox;
import com.mz2az.scenetrip.sceneapi.poi.PoiStore;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 편의시설 조회의 HTTP 계층. 검증하는 것은 <b>파라미터들 사이의 관계</b>와 그것이 {@link PoiStore.Criteria} 로 옮겨지는 방식이다. DB 는 없다
 * — SQL 은 통합 레인이 본다.
 */
@WebMvcTest(PoisController.class)
@Import(LanguageConfiguration.class)
class PoisControllerTest {

  @Autowired private MockMvc mvc;
  @MockitoBean private PoiStore store;

  private static PoiSummary poi(long id, String name) {
    return new PoiSummary(id, name, "한식", PoiCategoryGroup.FOOD, 37.498, 127.027)
        .distanceMeters(12);
  }

  private void givenPois(int total, PoiSummary... items) {
    when(store.list(any())).thenReturn(new PoiStore.Page(List.of(items), total));
  }

  @Test
  @DisplayName("뷰포트 + 중심 — 200, bbox 가 파싱되고 기본 정렬은 거리순")
  void viewportWithOrigin() throws Exception {
    givenPois(4027, poi(1, "서초강산스토리"), poi(2, "포490"));

    mvc.perform(
            get("/pois")
                .param("bbox", "127.017,37.489,127.037,37.507")
                .param("lat", "37.498")
                .param("lng", "127.027")
                .param("categoryGroup", "food"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Language", "ko"))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].name").value("서초강산스토리"))
        .andExpect(jsonPath("$.items[0].categoryGroup").value("food"))
        .andExpect(jsonPath("$.total").value(4027))
        .andExpect(jsonPath("$.limit").value(30))
        .andExpect(jsonPath("$.offset").value(0));

    ArgumentCaptor<PoiStore.Criteria> captor = ArgumentCaptor.forClass(PoiStore.Criteria.class);
    verify(store).list(captor.capture());
    PoiStore.Criteria c = captor.getValue();
    assertThat(c.bbox()).isEqualTo(new Bbox(127.017, 37.489, 127.037, 37.507));
    assertThat(c.lat()).isEqualTo(37.498);
    assertThat(c.categoryGroup()).isEqualTo(PoiCategoryGroup.FOOD);
    assertThat(c.sort()).isEqualTo(PoiStore.Sort.DISTANCE);
    assertThat(c.limit()).isEqualTo(30);
  }

  @Test
  @DisplayName("뷰포트만 — 기준점이 없으면 기본 정렬은 이름순")
  void viewportWithoutOriginDefaultsToAlphabetical() throws Exception {
    givenPois(0);

    mvc.perform(get("/pois").param("bbox", "127.017,37.489,127.037,37.507"))
        .andExpect(status().isOk());

    ArgumentCaptor<PoiStore.Criteria> captor = ArgumentCaptor.forClass(PoiStore.Criteria.class);
    verify(store).list(captor.capture());
    assertThat(captor.getValue().sort()).isEqualTo(PoiStore.Sort.ALPHABETICAL);
    assertThat(captor.getValue().lat()).isNull();
  }

  @Test
  @DisplayName("반경 + 중심 — bbox 없이도 영역 조건이다")
  void radiusWithOrigin() throws Exception {
    givenPois(0);

    mvc.perform(
            get("/pois")
                .param("lat", "37.498")
                .param("lng", "127.027")
                .param("radiusMeters", "300"))
        .andExpect(status().isOk());

    ArgumentCaptor<PoiStore.Criteria> captor = ArgumentCaptor.forClass(PoiStore.Criteria.class);
    verify(store).list(captor.capture());
    assertThat(captor.getValue().bbox()).isNull();
    assertThat(captor.getValue().radiusMeters()).isEqualTo(300);
  }

  @Test
  @DisplayName("영역 조건이 하나도 없으면 400 — /places 와 다른 POI 만의 규칙")
  void missingAreaIsRejected() throws Exception {
    mvc.perform(get("/pois"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_AREA_FILTER"));

    // 기준점만 있고 반경이 없는 것도 영역 조건이 아니다.
    mvc.perform(get("/pois").param("lat", "37.498").param("lng", "127.027"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_AREA_FILTER"));
  }

  @Test
  @DisplayName("bbox 와 radiusMeters 를 함께 보내면 400")
  void conflictingAreaIsRejected() throws Exception {
    mvc.perform(
            get("/pois")
                .param("bbox", "127.017,37.489,127.037,37.507")
                .param("lat", "37.498")
                .param("lng", "127.027")
                .param("radiusMeters", "300"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFLICTING_AREA_FILTER"));
  }

  @Test
  @DisplayName("lat 만 보내면 400 — 기준점은 짝이다")
  void incompleteOriginIsRejected() throws Exception {
    mvc.perform(get("/pois").param("bbox", "127.017,37.489,127.037,37.507").param("lat", "37.498"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INCOMPLETE_ORIGIN"));
  }

  @Test
  @DisplayName("bbox 문법은 맞는데 최소가 최대보다 크면 400")
  void invalidBboxIsRejected() throws Exception {
    mvc.perform(get("/pois").param("bbox", "127.037,37.489,127.017,37.507"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_BBOX"));
  }

  @Test
  @DisplayName("sort=distance 인데 기준점이 없으면 400")
  void distanceSortWithoutOriginIsRejected() throws Exception {
    mvc.perform(
            get("/pois").param("bbox", "127.017,37.489,127.037,37.507").param("sort", "distance"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_SORT"));
  }

  @Test
  @DisplayName("모르는 sort 값은 400 — popularity 도 없다")
  void unknownSortIsRejected() throws Exception {
    mvc.perform(
            get("/pois").param("bbox", "127.017,37.489,127.037,37.507").param("sort", "popularity"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
  }

  @Test
  @DisplayName("모르는 categoryGroup 은 400 — 조용히 필터가 빠지면 안 된다")
  void unknownCategoryGroupIsRejected() throws Exception {
    mvc.perform(
            get("/pois")
                .param("bbox", "127.017,37.489,127.037,37.507")
                .param("categoryGroup", "foods"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
  }

  @Test
  @DisplayName("limit 이 상한(200)을 넘으면 400 — 생성된 인터페이스의 @Max")
  void limitAboveMaxIsRejected() throws Exception {
    mvc.perform(get("/pois").param("bbox", "127.017,37.489,127.037,37.507").param("limit", "500"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
  }

  @Test
  @DisplayName("Accept-Language: en 을 보내도 Content-Language 는 ko — 자료가 한국어뿐이다")
  void alwaysAnswersInKorean() throws Exception {
    givenPois(0);

    mvc.perform(
            get("/pois")
                .param("bbox", "127.017,37.489,127.037,37.507")
                .header("Accept-Language", "en"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Language", "ko"));
  }

  @Test
  @DisplayName("상세 — 200, 기준점이 스토어로 전달된다")
  void detailFound() throws Exception {
    PoiDetail detail =
        new PoiDetail(7L, "모슬포호텔", "호텔", PoiCategoryGroup.STAY, 33.2177, 126.2506)
            .tel("064-794-3355");
    when(store.findDetail(eq(7L), anyDouble(), anyDouble())).thenReturn(Optional.of(detail));

    mvc.perform(get("/pois/7").param("lat", "33.2").param("lng", "126.25"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Language", "ko"))
        .andExpect(jsonPath("$.name").value("모슬포호텔"))
        .andExpect(jsonPath("$.tel").value("064-794-3355"));
  }

  @Test
  @DisplayName("없는 id 는 404 POI_NOT_FOUND")
  void detailMissing() throws Exception {
    when(store.findDetail(anyLong(), any(), any())).thenReturn(Optional.empty());

    mvc.perform(get("/pois/999"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POI_NOT_FOUND"));
  }
}
