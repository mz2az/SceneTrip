package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.PlacesApi;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PlaceList;
import com.mz2az.scenetrip.sceneapi.place.Bbox;
import com.mz2az.scenetrip.sceneapi.place.PlaceStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 촬영지 목록·검색·지도 조회.
 *
 * <p>엔드포인트 하나가 목업의 세 상황을 담당한다 — 장소 탭 검색, 지도 뷰포트 안의 핀, 현 지도 내 성지 검색(반경). 셋을 나누지 않은 이유는 조건이 붙는 자리만
 * 다르고 응답과 정렬·페이지네이션이 모두 같기 때문이다.
 */
@RestController
class PlacesController implements PlacesApi {

  private final PlaceStore store;

  PlacesController(PlaceStore store) {
    this.store = store;
  }

  @Override
  public ResponseEntity<PlaceList> listPlaces(
      Lang acceptLanguage,
      String q,
      Long contentId,
      String bbox,
      Double lat,
      Double lng,
      Integer radiusMeters,
      String sort,
      Integer limit,
      Integer offset) {

    PlaceQueryRules.requireCompleteOrigin(lat, lng);
    Bbox area = PlaceQueryRules.resolveArea(bbox, radiusMeters);
    PlaceStore.Sort order = PlaceQueryRules.resolveSort(sort, lat, lng);

    PlaceStore.Page page =
        store.list(
            new PlaceStore.Criteria(
                (q == null || q.isBlank()) ? null : q.strip(),
                contentId,
                area,
                lat,
                lng,
                radiusMeters,
                order,
                acceptLanguage,
                limit,
                offset));

    PlaceList body = new PlaceList(page.items(), page.total(), limit, offset);
    return Responses.ok(body, Responses.used(acceptLanguage, page.anyInRequestedLang()));
  }
}
