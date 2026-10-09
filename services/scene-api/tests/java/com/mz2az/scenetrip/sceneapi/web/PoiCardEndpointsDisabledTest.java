package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.api.PoisApi;
import com.mz2az.scenetrip.sceneapi.api.model.PoiCategoryGroup;
import com.mz2az.scenetrip.sceneapi.api.model.PoiDetail;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.poi.PoiStore;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverCard;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverLinks;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverMatcher;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverPlaceClient;
import com.mz2az.scenetrip.sceneapi.poi.naver.PoiCardFetcher;
import com.mz2az.scenetrip.sceneapi.poi.naver.PoiCardFiller;
import com.mz2az.scenetrip.sceneapi.poi.naver.PoiCardService;
import com.mz2az.scenetrip.sceneapi.poi.naver.PoiNaverStore;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * ADR 0020 (MZ2AZ-355) — 카드 창구는 deprecated 지만 응답은 한다. 꺼진 채로 늘 「못 찾음」 이거나 pending 이다.
 *
 * <p>{@link PoisControllerTest} 와 달리 카드 서비스를 가짜로 바꾸지 않는다 — 컨트롤러 → {@link PoiCardService} → {@link
 * PoiCardFetcher} → {@link NaverPlaceClient} 를 진짜로 잇고, 스위치 값은 배포되는 {@code application.yaml} 의 것을
 * 그대로 읽는다. 뒤에서 채우는 일꾼({@link PoiCardFiller})도 진짜다. 가짜로 두는 것은 DB 쪽(POI 표, {@code poi_naver} 표)뿐이다.
 * 네이버 주소는 이 테스트의 가짜 서버로 바꿔 끼워 진짜 네이버로 나가지 않고 요청 수로 드러난다.
 *
 * <p>ADR 0021 뒤로는 배포 설정에서 링크용 검색이 켜져 있고 {@code poi_naver} 에 장소 번호 행이 쌓인다. 그래도 카드 창구는 표를 보지 않고 늘 「못
 * 찾음」·{@code pending} 이며 네이버를 부르지 않는다. 가짜 네이버는 「찾음」 을 답한다 — 무엇이든 부르면 요청 수로 드러난다.
 */
@WebMvcTest(PoisController.class)
@Import({
  LanguageConfiguration.class,
  PoiCardService.class,
  PoiCardFetcher.class,
  PoiCardFiller.class,
  NaverLinks.class,
  NaverPlaceClient.class
})
@DisplayName("카드 창구 — 꺼진 채 deprecated, 그래도 응답한다 (ADR 0020 · 0021)")
class PoiCardEndpointsDisabledTest {

  /** 분당 상한 필터(RequestRateLimitFilter)가 Bearer 토큰을 읽는다 — 이 시험은 토큰을 보내지 않는다. */
  @MockitoBean private AccessTokens rateLimitTokens;

  private static HttpServer server;
  private static final AtomicInteger hits = new AtomicInteger();
  private static final List<String> paths = new java.util.concurrent.CopyOnWriteArrayList<>();

  /** 무엇을 물어도 「바로 옆의 정아각 본점」 — 검색이 나가면 찾고, 상세가 나가면 그 다음 요청이 된다. */
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
          hits.incrementAndGet();
          paths.add(exchange.getRequestURI().getPath());
          exchange.getRequestBody().readAllBytes();
          byte[] bytes = FOUND.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
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
  @MockitoBean private ReviewViews reviews;

  @BeforeEach
  void setUp() {
    hits.set(0);
    paths.clear();
    PoiDetail poi =
        new PoiDetail(7L, "정아각 본점", "중식", PoiCategoryGroup.FOOD, 37.4375, 126.7819, List.of())
            .region("경기")
            .city("시흥시");
    when(pois.findDetail(eq(7L), any(), any())).thenReturn(Optional.of(poi));
    PoiDetail other =
        new PoiDetail(8L, "정아각 본점", "중식", PoiCategoryGroup.FOOD, 37.4375, 126.7819, List.of())
            .region("경기")
            .city("시흥시");
    when(pois.findDetail(eq(8L), any(), any())).thenReturn(Optional.of(other));
    when(pois.findDetail(eq(999L), any(), any())).thenReturn(Optional.empty());
    // ADR 0021 뒤의 표 — 7 은 링크 경로가 쌓은 장소 번호 행이 있고, 8 은 아직 없다.
    NaverCard linkRow =
        NaverCard.linkOnly(
            7L,
            "5784380",
            NaverMatcher.RULE_VERSION,
            "https://map.naver.com/p/entry/place/5784380");
    when(naverStore.find(anyLong(), anyString())).thenReturn(Optional.empty());
    when(naverStore.find(eq(7L), anyString())).thenReturn(Optional.of(linkRow));
    when(naverStore.findAll(anyCollection(), anyString())).thenReturn(Map.of(7L, linkRow));
    when(naverStore.save(any())).thenAnswer(inv -> inv.getArgument(0));
  }

  /** 뒤에서 도는 일꾼이 있으므로 응답 뒤에도 잠시 지켜본다 — 그 사이 한 건이라도 나가면 실패. */
  private static void assertNoNaverCallsFor(long millis) throws InterruptedException {
    long until = System.currentTimeMillis() + millis;
    while (System.currentTimeMillis() < until) {
      assertThat(hits.get()).as("가짜 네이버가 받은 요청 수 — 경로 %s", paths).isZero();
      Thread.sleep(50);
    }
    assertThat(hits.get()).as("가짜 네이버가 받은 요청 수 — 경로 %s", paths).isZero();
  }

  @Test
  @DisplayName("단건 — 있는 POI 는 200 · found=false, 네이버 칸은 하나도 없고 요청도 나가지 않는다")
  void singleCardIsNotFound() throws Exception {
    mvc.perform(get("/pois/7/card"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.poiId").value(7))
        .andExpect(jsonPath("$.found").value(false))
        .andExpect(jsonPath("$.why").isNotEmpty())
        .andExpect(jsonPath("$.name").doesNotExist())
        .andExpect(jsonPath("$.images").isEmpty())
        .andExpect(jsonPath("$.naverUrl").doesNotExist())
        .andExpect(jsonPath("$.score").doesNotExist())
        .andExpect(jsonPath("$.hours").doesNotExist())
        .andExpect(jsonPath("$.reviewCount").doesNotExist());

    assertNoNaverCallsFor(1_500);
    // 「못 받음」 은 표에 쓰지 않는다 — 꺼진 결과가 「없음」 으로 굳지 않는다.
    verify(naverStore, never()).save(any());
  }

  @Test
  @DisplayName("단건 — 표에 장소 번호 행이 있어도 found=false, 네이버 링크를 카드로 내주지 않는다 (ADR 0021)")
  void singleCardIgnoresLinkRows() throws Exception {
    mvc.perform(get("/pois/7/card"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.found").value(false))
        .andExpect(jsonPath("$.naverUrl").doesNotExist());
    mvc.perform(get("/pois/8/card"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.found").value(false))
        .andExpect(jsonPath("$.naverUrl").doesNotExist());

    assertNoNaverCallsFor(1_500);
  }

  @Test
  @DisplayName("단건 — 없는 POI 는 여전히 404 POI_NOT_FOUND")
  void singleCardMissingPoi() throws Exception {
    mvc.perform(get("/pois/999/card"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("POI_NOT_FOUND"));

    assertThat(hits.get()).isZero();
  }

  @Test
  @DisplayName("여럿 — 있는 POI 는 표에 장소 번호 행이 있어도 pending, 없는 POI 는 found=false. 네이버 데이터도 요청도(뒤에서도) 없다")
  void batchIsPendingOrNotFound() throws Exception {
    when(pois.existingIds(List.of(7L, 8L, 999L))).thenReturn(Set.of(7L, 8L));

    mvc.perform(get("/pois/cards").param("ids", "7,8,999"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(3))
        .andExpect(jsonPath("$.items[0].poiId").value(7))
        .andExpect(jsonPath("$.items[0].pending").value(true))
        .andExpect(jsonPath("$.items[1].poiId").value(8))
        .andExpect(jsonPath("$.items[1].pending").value(true))
        .andExpect(jsonPath("$.items[2].poiId").value(999))
        .andExpect(jsonPath("$.items[2].found").value(false))
        .andExpect(jsonPath("$.items[*].name").isEmpty())
        .andExpect(jsonPath("$.items[*].naverUrl").isEmpty())
        .andExpect(jsonPath("$.items[*].images[*]").isEmpty())
        .andExpect(jsonPath("$.items[*].score").isEmpty());

    // 7 은 표에 장소 번호 행이 있고 8 은 없다 — 둘 다 pending 이고, 뒤에서도 네이버를 부르지 않는다.
    assertNoNaverCallsFor(2_000);
  }

  @Test
  @DisplayName("계약 — getPoiCard · listPoiCards 는 deprecated, 다른 창구는 아니다")
  void contractMarksCardOperationsDeprecated() {
    for (Method m : PoisApi.class.getMethods()) {
      String name = m.getName();
      if (name.equals("getPoiCard") || name.equals("listPoiCards")) {
        assertThat(m.isAnnotationPresent(Deprecated.class)).as(name + " @Deprecated").isTrue();
        assertThat(operationDeprecated(m)).as(name + " @Operation(deprecated)").isTrue();
      } else if (name.equals("getPoi") || name.equals("listPois")) {
        assertThat(m.isAnnotationPresent(Deprecated.class)).as(name + " 는 아니다").isFalse();
      }
    }
    assertThat(PoisApi.class.getMethods())
        .extracting(Method::getName)
        .contains("getPoiCard", "listPoiCards", "getPoi");
  }

  /** 생성된 {@code @Operation(deprecated = …)} 를 이름으로 읽는다 — 그 주석 라이브러리를 컴파일 의존에 넣지 않으려고. */
  private static boolean operationDeprecated(Method m) {
    for (Annotation a : m.getAnnotations()) {
      if (a.annotationType().getSimpleName().equals("Operation")) {
        try {
          return (Boolean) a.annotationType().getMethod("deprecated").invoke(a);
        } catch (ReflectiveOperationException e) {
          throw new AssertionError(e);
        }
      }
    }
    throw new AssertionError(m.getName() + " 에 @Operation 이 없다");
  }
}
