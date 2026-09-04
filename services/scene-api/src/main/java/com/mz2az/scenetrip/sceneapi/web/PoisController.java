package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.PoisApi;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PoiCategoryGroup;
import com.mz2az.scenetrip.sceneapi.api.model.PoiDetail;
import com.mz2az.scenetrip.sceneapi.api.model.PoiList;
import com.mz2az.scenetrip.sceneapi.place.Bbox;
import com.mz2az.scenetrip.sceneapi.poi.PoiStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * 편의시설(POI) 지도 조회·상세.
 *
 * <p>{@link PlacesController} 와 같은 규칙({@link PlaceQueryRules})을 쓰되 POI 만의 것이 둘 있다 — 영역 조건이 없으면
 * 거부한다(50 만 건을 전국 대상으로 돌려줄 정렬 기준이 없다), 정렬에 인기도가 없다.
 *
 * <p>응답 언어는 언제나 {@code ko} 다. 자료가 한국어뿐이라 요청 언어와 무관하다 — 명세가 그렇게 적어 두었고, 다국어가 생기면 서버만 바뀐다.
 */
@RestController
class PoisController implements PoisApi {

  private final PoiStore store;

  PoisController(PoiStore store) {
    this.store = store;
  }

  @Override
  public ResponseEntity<PoiList> listPois(
      Lang acceptLanguage,
      String bbox,
      Double lat,
      Double lng,
      Integer radiusMeters,
      PoiCategoryGroup categoryGroup,
      String sort,
      Integer limit,
      Integer offset) {

    PlaceQueryRules.requireCompleteOrigin(lat, lng);
    Bbox area = PlaceQueryRules.resolveArea(bbox, radiusMeters);
    boolean hasOrigin = lat != null;
    if (area == null && !(hasOrigin && radiusMeters != null)) {
      throw ApiException.badRequest(
          "MISSING_AREA_FILTER",
          "bbox 또는 lat·lng+radiusMeters 중 하나는 있어야 합니다 — 전국을 대상으로 돌려줄 정렬 기준이 없습니다");
    }
    PoiStore.Sort order = resolveSort(sort, hasOrigin);

    PoiStore.Page page =
        store.list(
            new PoiStore.Criteria(
                area, lat, lng, radiusMeters, categoryGroup, order, limit, offset));

    return Responses.ok(new PoiList(page.items(), page.total(), limit, offset), Lang.KO);
  }

  @Override
  public ResponseEntity<PoiDetail> getPoi(Long poiId, Lang acceptLanguage, Double lat, Double lng) {
    PlaceQueryRules.requireCompleteOrigin(lat, lng);

    PoiDetail detail =
        store
            .findDetail(poiId, lat, lng)
            .orElseThrow(
                () -> ApiException.notFound("POI_NOT_FOUND", "편의시설 " + poiId + " 이(가) 없습니다"));

    return Responses.ok(detail, Lang.KO);
  }

  /** 정렬. 기본은 기준점이 있으면 거리순, 없으면 이름순 — 명세 §pois. {@code distance} 를 기준점 없이 달라고 하면 거부한다. 인기도는 없다. */
  private static PoiStore.Sort resolveSort(String sort, boolean hasOrigin) {
    if (sort == null) {
      return hasOrigin ? PoiStore.Sort.DISTANCE : PoiStore.Sort.ALPHABETICAL;
    }
    return switch (sort) {
      case "alphabetical" -> PoiStore.Sort.ALPHABETICAL;
      case "distance" -> {
        if (!hasOrigin) {
          throw ApiException.badRequest("INVALID_SORT", "sort=distance 는 lat·lng 기준점이 있어야 합니다");
        }
        yield PoiStore.Sort.DISTANCE;
      }
      default ->
          throw ApiException.badRequest("INVALID_PARAMETER", "sort 는 distance 또는 alphabetical 입니다");
    };
  }
}
