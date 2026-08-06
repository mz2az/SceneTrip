package com.mz2az.scenetrip.sceneapi.content;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 다른 패키지의 테스트가 {@link ContentStore} 를 만들 수 있게 하는 통로.
 *
 * <p>이유는 {@code PlaceStores} 와 같다 — 생성자가 패키지 전용이고, 검색 대칭 테스트는 두 Store 를 함께 봐야 한다.
 */
public final class ContentStores {

  private ContentStores() {}

  public static ContentStore create(JdbcClient jdbc) {
    return new ContentStore(jdbc);
  }
}
