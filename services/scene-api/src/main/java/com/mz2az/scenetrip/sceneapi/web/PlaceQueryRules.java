package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.place.Bbox;
import com.mz2az.scenetrip.sceneapi.place.PlaceStore;

/**
 * 명세가 글로 적어 둔 조합 규칙을 검사한다.
 *
 * <p>파라미터 하나씩의 제약(길이·범위)은 생성된 인터페이스의 표식이 이미 본다. 여기서 보는 것은 <b>파라미터들 사이의 관계</b>다 — 그것은 OpenAPI 로 표현할
 * 수 없어 설명에만 적혀 있고, 검사하지 않으면 명세와 서버가 조용히 갈라진다.
 *
 * <p>두 엔드포인트가 같은 규칙을 쓰기 때문에 한곳에 모았다. {@code GET /places} 와 {@code GET
 * /contents/&#123;id&#125;/places} 가 기준점 규칙을 공유한다.
 */
final class PlaceQueryRules {

  private PlaceQueryRules() {}

  /**
   * 기준점은 짝이다.
   *
   * <p>하나만 보내는 것은 거의 항상 실수다. 조용히 무시하면 {@code distanceMeters} 가 전부 {@code null} 로 나오고, 클라이언트는 서버가 거리
   * 계산을 지원하지 않는다고 오해한다.
   */
  static void requireCompleteOrigin(Double lat, Double lng) {
    if ((lat == null) != (lng == null)) {
      throw ApiException.badRequest("INCOMPLETE_ORIGIN", "lat 과 lng 는 짝으로 보내야 합니다 — 하나만 보낼 수 없습니다");
    }
  }

  /**
   * 영역 조건은 하나만 쓴다.
   *
   * <p>둘을 함께 주면 교집합인지 합집합인지가 정해지지 않는다. 서버가 임의로 정하면 클라이언트마다 다른 것을 기대하게 되므로 거부한다.
   */
  static Bbox resolveArea(String rawBbox, Integer radiusMeters) {
    boolean hasBbox = rawBbox != null && !rawBbox.isBlank();
    if (hasBbox && radiusMeters != null) {
      throw ApiException.badRequest(
          "CONFLICTING_AREA_FILTER", "bbox 와 radiusMeters 는 함께 쓸 수 없습니다 — 영역 조건은 하나만 쓰세요");
    }
    if (!hasBbox) {
      return null;
    }
    return Bbox.parse(rawBbox)
        .orElseThrow(
            () ->
                ApiException.badRequest(
                    "INVALID_BBOX",
                    "bbox 는 minLng,minLat,maxLng,maxLat 순서의 숫자 4개여야 하고 최소가 최대보다 작아야 합니다"));
  }

  /** {@code sort=distance} 는 기준점이 있을 때만 뜻이 있다. */
  static PlaceStore.Sort resolveSort(String sort, Double lat, Double lng) {
    if (sort == null || "popularity".equals(sort)) {
      return PlaceStore.Sort.POPULARITY;
    }
    if (!"distance".equals(sort)) {
      throw ApiException.badRequest("INVALID_PARAMETER", "sort 는 popularity 또는 distance 입니다");
    }
    if (lat == null || lng == null) {
      throw ApiException.badRequest("INVALID_SORT", "sort=distance 는 lat·lng 기준점이 있어야 합니다");
    }
    return PlaceStore.Sort.DISTANCE;
  }
}
