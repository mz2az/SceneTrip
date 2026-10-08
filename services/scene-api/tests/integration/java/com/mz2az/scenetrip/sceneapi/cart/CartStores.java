package com.mz2az.scenetrip.sceneapi.cart;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 다른 패키지의 통합 테스트가 {@link CartStore} 를 만들 수 있게 하는 통로({@code PlaceStores} 와 같은 이유 — 생성자는 패키지 전용이고
 * 앱에서는 Spring 이 주입한다).
 */
public final class CartStores {

  private CartStores() {}

  public static CartStore create(JdbcClient jdbc) {
    return new CartStore(jdbc);
  }
}
