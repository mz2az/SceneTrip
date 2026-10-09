package com.mz2az.scenetrip.sceneapi.poi.naver;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 다른 패키지의 테스트가 {@link NaverLinks} 를 만들 수 있게 하는 통로 — {@code poi.PoiStores} 와 같은 이유.
 *
 * <p>{@code web.ReviewFlowIntegrationTest} 가 {@code PoisController} 를 손으로 조립하는데, 컨트롤러가 상세의 {@code
 * naverPlaceUrl}(ADR 0021) 때문에 {@code NaverLinks} 를 받는다. 통합 레인은 네이버를 부르지 않으므로 <b>링크 스위치를 끈</b> 것만
 * 준다 — 주소도 닿지 않는 로컬 포트다.
 */
public final class NaverLinksForTests {

  private NaverLinksForTests() {}

  /** 꺼진 링크 — 언제나 비어 있고 네이버도 표도 보지 않는다. */
  public static NaverLinks disabled(JdbcClient jdbc) {
    NaverPlaceClient client =
        new NaverPlaceClient(
            false, false, "http://127.0.0.1:9/graphql", "http://127.0.0.1:9/summary/{id}", 700);
    return new NaverLinks(false, new PoiNaverStore(jdbc), new PoiCardFetcher(client), 60);
  }
}
