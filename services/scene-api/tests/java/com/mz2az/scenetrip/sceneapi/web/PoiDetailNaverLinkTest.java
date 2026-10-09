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
import java.time.OffsetDateTime;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /pois/{poiId}} 의 {@code naverPlaceUrl} — 계약 scene-api 1.6.0, ADR 0021 (MZ2AZ-373).
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
@DisplayName("상세 naverPlaceUrl — 배포 설정 그대로, 가짜 네이버 (ADR 0021)")
class PoiDetailNaverLinkTest {

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
  @DisplayName("처음 열면 검색으로 찾아 그 가게의 장소 화면을 싣고, 다시 열면 네이버를 부르지 않는다")
  void firstOpenLooksUpThenServesStored() throws Exception {
    mvc.perform(get("/pois/7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.naverPlaceUrl").value(PLACE + "5784380"));
    assertThat(searchHits.get()).isEqualTo(1);
    assertThat(rows.get(7L).naverId()).isEqualTo("5784380");

    mvc.perform(get("/pois/7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.naverPlaceUrl").value(PLACE + "5784380"));
    assertThat(searchHits.get()).as("두 번째 열 때 검색 수").isEqualTo(1);
  }

  @Test
  @DisplayName("네이버가 실패(5xx)해도 상세는 200 — naverPlaceUrl 만 비고 저장하지 않는다")
  void naverFailureStillServesDetail() throws Exception {
    status.set(502);

    mvc.perform(get("/pois/7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("정아각 본점"))
        .andExpect(jsonPath("$.naverPlaceUrl").doesNotExist());

    verify(naverStore, never()).save(any());
  }

  @Test
  // 막히면 이 컨텍스트의 NaverLinks 가 60 초 쉰다 — 다음 시험에 새지 않게 컨텍스트를 버린다.
  @DirtiesContext
  @DisplayName("네이버가 막아도(429) 상세는 200 — naverPlaceUrl 만 빈다")
  void naverBlockedStillServesDetail() throws Exception {
    status.set(429);

    mvc.perform(get("/pois/7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.naverPlaceUrl").doesNotExist());

    verify(naverStore, never()).save(any());
  }

  @Test
  @DisplayName("저장된 번호가 숫자만이 아니면 naverPlaceUrl 은 비고 상세는 200")
  void storedNonNumericIdGivesNoUrl() throws Exception {
    rows.put(
        7L,
        new NaverCard(
            7L,
            true,
            null,
            NaverMatcher.RULE_VERSION,
            OffsetDateTime.now(),
            "5784380/../x",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            null));

    mvc.perform(get("/pois/7"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.naverPlaceUrl").doesNotExist());
    assertThat(searchHits.get()).isZero();
  }

  @Test
  @DisplayName("목록은 네이버를 부르지 않는다 — 상세를 열 때만")
  void listNeverLooksUp() throws Exception {
    when(pois.list(any())).thenReturn(new PoiStore.Page(List.of(), 0, java.util.Set.of(Lang.KO)));

    mvc.perform(get("/pois").param("bbox", "126.77,37.43,126.79,37.44")).andExpect(status().isOk());

    assertThat(searchHits.get()).isZero();
  }
}
