package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.PoiCategoryGroup;
import com.mz2az.scenetrip.sceneapi.api.model.PoiDetail;
import com.mz2az.scenetrip.sceneapi.api.model.RatingSummary;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.poi.PoiStore;
import com.mz2az.scenetrip.sceneapi.poi.naver.CardFiller;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverCard;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverLinks;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverMatcher;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverPlaceClient;
import com.mz2az.scenetrip.sceneapi.poi.naver.PoiCardFetcher;
import com.mz2az.scenetrip.sceneapi.poi.naver.PoiCardService;
import com.mz2az.scenetrip.sceneapi.poi.naver.PoiNaverStore;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /pois/{poiId}} 의 {@code naverPlaceUrl} — 링크 스위치({@code
 * scenetrip.naver.link-lookup.enabled})를 끈 경우. ADR 0021 「문제가 되면 앞의 것만 끈다」 — 끄면 검색도 상세도 나가지 않고, 상세는
 * 그대로 200 이다. 나머지 설정은 배포되는 {@code application.yaml}.
 *
 * <p>컨트롤러 → {@link NaverLinks} → {@link PoiCardFetcher} → {@link NaverPlaceClient} 를 진짜로 잇고 스위치·시간
 * 값은 배포되는 {@code application.yaml} 의 것을 그대로 읽는다. 네이버 주소만 이 테스트의 가짜 서버로 바꾼다. 가짜로 두는 것은 DB 쪽(POI 표,
 * {@code poi_naver} 표 — 메모리 맵)과 리뷰뿐이다.
 */
@WebMvcTest(PoisController.class)
@Import({
  LanguageConfiguration.class,
  PoiCardService.class,
  PoiCardFetcher.class,
  NaverLinks.class,
  NaverPlaceClient.class
})
@TestPropertySource(properties = "scenetrip.naver.link-lookup.enabled=false")
@DisplayName("상세 naverPlaceUrl — 링크 스위치를 끄면 비고, 네이버를 부르지 않는다 (ADR 0021)")
class PoiDetailNaverLinkOffTest {

  private static final String PLACE = "https://map.naver.com/p/entry/place/";

  /** 분당 상한 필터(RequestRateLimitFilter)가 Bearer 토큰을 읽는다 — 이 시험은 토큰을 보내지 않는다. */
  @MockitoBean private AccessTokens rateLimitTokens;

  private static HttpServer server;
  private static final AtomicInteger searchHits = new AtomicInteger();
  private static final AtomicInteger detailHits = new AtomicInteger();
  private static final AtomicInteger status = new AtomicInteger(200);

  /** 바로 옆의 「정아각 본점」 — 검색이 나가면 찾는다. */
  private static final String FOUND =
      """
      {"data":{"placeList":{"businesses":{"total":1,"items":[
        {"id":"5784380","name":"정아각 본점","coordinate":{"latitude":37.4375,"longitude":126.7819}}
      ]}}}}
      """;

  @BeforeAll
  static void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          if (exchange.getRequestURI().getPath().startsWith("/graphql")) {
            searchHits.incrementAndGet();
          } else {
            detailHits.incrementAndGet();
          }
          exchange.getRequestBody().readAllBytes();
          byte[] bytes = FOUND.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status.get(), bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
  }

  @AfterAll
  static void stop() {
    server.stop(0);
  }

  @DynamicPropertySource
  static void fakeNaver(DynamicPropertyRegistry registry) {
    registry.add(
        "scenetrip.naver.search-url",
        () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/graphql");
    registry.add(
        "scenetrip.naver.detail-url",
        () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/summary/{id}");
  }

  @Autowired private MockMvc mvc;
  @MockitoBean private PoiStore pois;
  @MockitoBean private PoiNaverStore naverStore;
  @MockitoBean private CardFiller filler;
  @MockitoBean private ReviewViews reviews;

  private final Map<Long, NaverCard> rows = new ConcurrentHashMap<>();

  @BeforeEach
  void setUp() {
    searchHits.set(0);
    detailHits.set(0);
    status.set(200);
    rows.clear();

    PoiDetail poi =
        new PoiDetail(7L, "정아각 본점", "중식", PoiCategoryGroup.FOOD, 37.4375, 126.7819, List.of())
            .region("경기")
            .city("시흥시");
    when(pois.findDetail(eq(7L), any(Lang.class), any(), any()))
        .thenReturn(Optional.of(new PoiStore.Detail(poi, Lang.KO)));
    when(reviews.summary(any(), anyLong()))
        .thenReturn(new RatingSummary(0, List.of(0, 0, 0, 0, 0)));
    when(reviews.gallery(any(), anyLong(), anyInt(), anyInt()))
        .thenReturn(new ReviewViews.Gallery(List.of(), 0));

    when(naverStore.find(anyLong(), anyString()))
        .thenAnswer(
            inv ->
                Optional.ofNullable(rows.get((Long) inv.getArgument(0)))
                    .filter(r -> r.ruleVersion().equals(inv.getArgument(1))));
    when(naverStore.save(any()))
        .thenAnswer(
            inv -> {
              NaverCard c = inv.getArgument(0);
              rows.put(c.poiId(), c);
              return c;
            });
  }

  @AfterEach
  void neverDetail() {
    assertThat(detailHits.get()).as("상세(사진·평점·영업시간) 호출 — 배포 설정에서는 0").isZero();
  }

  @Test
  @DisplayName("꺼져 있으면 naverPlaceUrl 은 비고 상세는 200 — 검색도 상세도 나가지 않는다")
  void switchOffGivesNoUrlAndNoCalls() throws Exception {
    mvc.perform(get("/pois/7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("정아각 본점"))
        .andExpect(jsonPath("$.naverPlaceUrl").doesNotExist());
    mvc.perform(get("/pois/7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.naverPlaceUrl").doesNotExist());

    assertThat(searchHits.get()).isZero();
    verify(naverStore, never()).save(any());
  }

  @Test
  @DisplayName("꺼져 있으면 표에 장소 번호가 있어도 싣지 않는다 — 끄면 링크가 빠진다")
  void switchOffIgnoresStoredLink() throws Exception {
    rows.put(7L, NaverCard.linkOnly(7L, "5784380", NaverMatcher.RULE_VERSION, PLACE + "5784380"));

    mvc.perform(get("/pois/7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.naverPlaceUrl").doesNotExist());
    assertThat(searchHits.get()).isZero();
  }
}
