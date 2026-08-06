package com.mz2az.scenetrip.sceneapi.place;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 다른 패키지의 테스트가 {@link PlaceStore} 를 만들 수 있게 하는 통로.
 *
 * <p>{@code PlaceStore} 의 생성자는 패키지 전용이다 — 앱에서는 Spring 이 주입하므로 공개할 이유가 없다. 검색 대칭 테스트는 장소와 작품 두 Store
 * 를 한자리에서 봐야 해서 어느 한쪽 패키지에도 들어갈 수 없다. 그래서 생산 코드의 가시성을 넓히는 대신, 테스트 트리에 이 통로를 둔다.
 */
public final class PlaceStores {

  private PlaceStores() {}

  public static PlaceStore create(JdbcClient jdbc) {
    return new PlaceStore(jdbc);
  }
}
