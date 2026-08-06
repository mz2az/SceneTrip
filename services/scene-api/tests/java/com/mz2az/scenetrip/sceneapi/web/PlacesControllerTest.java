package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.api.model.PlaceSummary;
import com.mz2az.scenetrip.sceneapi.place.Bbox;
import com.mz2az.scenetrip.sceneapi.place.PlaceStore;
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
 * 촬영지 조회의 HTTP 계층.
 *
 * <p>여기서 검증하는 것의 대부분은 <b>파라미터들 사이의 관계</b>다. 명세가 글로만 적어 둔 규칙이라 OpenAPI 로 표현되지 않고, 검사하지 않으면 명세와 서버가
 * 조용히 갈라진다.
 */
@WebMvcTest(PlacesController.class)
@Import(LanguageConfiguration.class)
class PlacesControllerTest {

  @Autowired private MockMvc mvc;

  @MockitoBean private PlaceStore store;

  private void givenPlaces(PlaceSummary... items) {
    when(store.list(any())).thenReturn(new PlaceStore.Page(List.of(items), items.length, true));
  }

  @Test
  @DisplayName("lat 만 보내면 400 — 기준점은 짝이다")
  void incompleteOriginIsRejected() throws Exception {
    givenPlaces();
    mvc.perform(get("/places").param("lat", "37.5"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INCOMPLETE_ORIGIN"));
  }

  @Test
  @DisplayName("bbox 와 radiusMeters 를 함께 보내면 400")
  void conflictingAreaFilterIsRejected() throws Exception {
    givenPlaces();
    // 둘을 함께 주면 교집합인지 합집합인지가 정해지지 않는다. 서버가 임의로 정하면
    // 클라이언트마다 다른 것을 기대하게 된다.
    mvc.perform(
            get("/places")
                .param("bbox", "126.9,37.5,127.1,37.6")
                .param("lat", "37.5")
                .param("lng", "126.9")
                .param("radiusMeters", "5000"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("CONFLICTING_AREA_FILTER"));
  }

  @Test
  @DisplayName("sort=distance 인데 기준점이 없으면 400")
  void distanceSortWithoutOriginIsRejected() throws Exception {
    givenPlaces();
    mvc.perform(get("/places").param("sort", "distance"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_SORT"));
  }

  @Test
  @DisplayName("뜻이 통하지 않는 bbox 는 400")
  void meaninglessBboxIsRejected() throws Exception {
    givenPlaces();
    // 숫자 4개라는 문법은 맞지만 최소가 최대보다 크다. 조회하면 언제나 0 건이라
    // 사용자에게는 "검색 결과가 없다" 로 보인다 — 오류로 알려 주는 편이 낫다.
    mvc.perform(get("/places").param("bbox", "127.1,37.6,126.9,37.5"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_BBOX"));
  }

  @Test
  @DisplayName("bbox 를 경도-위도 순서로 읽는다")
  void parsesBboxInLongitudeLatitudeOrder() throws Exception {
    givenPlaces();

    mvc.perform(get("/places").param("bbox", "126.9,37.5,127.1,37.6")).andExpect(status().isOk());

    ArgumentCaptor<PlaceStore.Criteria> captor = ArgumentCaptor.forClass(PlaceStore.Criteria.class);
    verify(store).list(captor.capture());
    // GeoJSON 과 같은 순서다. 위도를 먼저 쓰는 관습과 반대라 뒤집어 보내기 쉬운데,
    // 뒤집으면 오류 없이 엉뚱한 영역이 조회된다.
    assertThat(captor.getValue().bbox()).isEqualTo(new Bbox(126.9, 37.5, 127.1, 37.6));
  }

  @Test
  @DisplayName("장소 목록과 페이지네이션을 돌려준다")
  void returnsPlaces() throws Exception {
    givenPlaces(new PlaceSummary(2L, "북촌한옥마을", 37.5826, 126.9831).type("한옥마을").distanceMeters(955));

    mvc.perform(get("/places").param("lat", "37.5759").param("lng", "126.9769"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].name").value("북촌한옥마을"))
        .andExpect(jsonPath("$.items[0].latitude").value(37.5826))
        .andExpect(jsonPath("$.items[0].longitude").value(126.9831))
        .andExpect(jsonPath("$.items[0].distanceMeters").value(955))
        .andExpect(jsonPath("$.total").value(1));
  }

  @Test
  @DisplayName("빈 bbox 문자열은 없는 것으로 본다")
  void blankBboxIsIgnored() throws Exception {
    // 지도 화면이 아직 좌표를 모를 때 빈 값을 보낼 수 있다. 거부하면 첫 진입이 막힌다.
    assertThat(Bbox.parse("")).isEmpty();
    assertThat(Bbox.parse(null)).isEmpty();
    assertThat(Bbox.parse("126.9,37.5,127.1")).isEmpty();
    assertThat(Bbox.parse("126.9,37.5,127.1,37.6"))
        .isEqualTo(Optional.of(new Bbox(126.9, 37.5, 127.1, 37.6)));
  }
}
