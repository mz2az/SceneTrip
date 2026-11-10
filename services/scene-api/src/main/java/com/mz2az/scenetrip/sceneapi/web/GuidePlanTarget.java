package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.api.model.GuidePlanRequest;

/**
 * 일정짜기 마법사({@code POST /guide/plan})가 무엇으로 짜는가 — 작품({@code titles})과 도시({@code city}) 중 정확히
 * 하나(1.28.0, MZ2AZ-548).
 *
 * <p>둘 사이의 관계라 OpenAPI 로 표현할 수 없어 설명에만 적혀 있다({@link PlaceQueryRules} 와 같은 이유). 에이전트에 넘기기 전에 여기서 400
 * 으로 끊는다 — 에이전트까지 가면 한도를 한 번 쓴다.
 */
final class GuidePlanTarget {

  private GuidePlanTarget() {}

  /** 둘 다 오거나 둘 다 없으면 400 {@code INVALID_PARAMETER}. 빈 목록·공백뿐인 도시는 없는 것으로 본다. */
  static void check(GuidePlanRequest request) {
    boolean hasTitles = request.getTitles() != null && !request.getTitles().isEmpty();
    boolean hasCity = request.getCity() != null && !request.getCity().isBlank();
    if (hasTitles == hasCity) {
      throw ApiException.badRequest(
          "INVALID_PARAMETER",
          hasTitles ? "titles 와 city 는 함께 보낼 수 없습니다" : "titles 또는 city 가 필요합니다");
    }
  }
}
