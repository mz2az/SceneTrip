package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.model.GuidePlanRequest;

/**
 * 일정짜기 마법사({@code POST /guide/plan})가 무엇으로 짜는가 — 작품({@code titles}), 도시({@code city}), 담은
 * 촬영지({@code placeIds}) 중 정확히 하나(1.28.0 MZ2AZ-548 에 도시, 1.33.0 MZ2AZ-601 에 담은 촬영지).
 *
 * <p>서로 사이의 관계라 OpenAPI 로 표현할 수 없어 설명에만 적혀 있다({@link PlaceQueryRules} 와 같은 이유). 에이전트에 넘기기 전에 여기서
 * 400 으로 끊는다 — 에이전트까지 가면 한도를 한 번 쓴다.
 */
final class GuidePlanTarget {

  private GuidePlanTarget() {}

  /** 하나도 없거나 둘 이상이면 400 {@code INVALID_PARAMETER}. 빈 목록·공백뿐인 도시는 없는 것으로 본다. */
  static void check(GuidePlanRequest request) {
    boolean hasTitles = request.getTitles() != null && !request.getTitles().isEmpty();
    boolean hasCity = request.getCity() != null && !request.getCity().isBlank();
    boolean hasPlaces = request.getPlaceIds() != null && !request.getPlaceIds().isEmpty();
    int given = (hasTitles ? 1 : 0) + (hasCity ? 1 : 0) + (hasPlaces ? 1 : 0);
    if (given != 1) {
      throw ApiException.badRequest(
          "INVALID_PARAMETER",
          given == 0
              ? "titles, city, placeIds 중 하나가 필요합니다"
              : "titles, city, placeIds 는 함께 보낼 수 없습니다");
    }
  }
}
