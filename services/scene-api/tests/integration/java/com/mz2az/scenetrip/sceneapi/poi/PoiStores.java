package com.mz2az.scenetrip.sceneapi.poi;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 다른 패키지의 테스트가 {@link PoiStore} 를 만들 수 있게 하는 통로 — {@code place.PlaceStores} 와 같은 이유.
 *
 * <p>{@code PoiStore} 의 생성자는 패키지 전용이다. 리뷰 흐름 테스트({@code web.ReviewFlowIntegrationTest})가 편의시설 상세에
 * 별점·사진첩이 실리는지를 진짜 Store 로 보려면 이것이 필요하다. 생산 코드의 가시성을 넓히는 대신 테스트 트리에 둔다.
 */
public final class PoiStores {

  private PoiStores() {}

  public static PoiStore create(JdbcClient jdbc) {
    return new PoiStore(jdbc);
  }
}
