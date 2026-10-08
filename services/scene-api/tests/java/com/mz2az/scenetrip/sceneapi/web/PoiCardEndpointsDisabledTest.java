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
import com.mz2az.scenetrip.sceneapi.poi.PoiStore;
import com.mz2az.scenetrip.sceneapi.poi.naver.CardFiller;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverPlaceClient;
import com.mz2az.scenetrip.sceneapi.poi.naver.PoiCardFetcher;
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
 * 그대로 읽는다. 가짜로 두는 것은 DB 쪽(POI 표, V23 이 비운 {@code poi_naver} 표)과 뒤에서 채우는 일꾼뿐이다. 네이버 주소는 이 테스트의 가짜
 * 서버로 바꿔 끼워 스위치가 잘못 켜져도 진짜 네이버로 나가지 않고 요청 수로 드러난다.
 */
@WebMvcTest(PoisController.class)
@Import({
  LanguageConfiguration.class,
  PoiCardService.class,
  PoiCardFetcher.class,
  NaverPlaceClient.class
})
@DisplayName("카드 창구 — 꺼진 채 deprecated, 그래도 응답한다 (ADR 0020)")
class PoiCardEndpointsDisabledTest {

  private static HttpServer server;
  private static final AtomicInteger hits = new AtomicInteger();

  @BeforeAll
  static void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          hits.incrementAndGet();
          byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
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
  @MockitoBean private CardFiller filler;
  @MockitoBean private ReviewViews reviews;

  @BeforeEach
  void setUp() {
    hits.set(0);
    PoiDetail poi =
        new PoiDetail(7L, "정아각 본점", "중식", PoiCategoryGroup.FOOD, 37.4375, 126.7819, List.of())
            .region("경기")
            .city("시흥시");
    when(pois.findDetail(eq(7L), any(), any())).thenReturn(Optional.of(poi));
    when(pois.findDetail(eq(999L), any(), any())).thenReturn(Optional.empty());
    // V23 뒤의 표 — 비어 있다.
    when(naverStore.find(anyLong(), anyString())).thenReturn(Optional.empty());
    when(naverStore.findAll(anyCollection(), anyString())).thenReturn(Map.of());
    when(filler.retryAfterSeconds()).thenReturn(5);
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

    assertThat(hits.get()).as("가짜 네이버가 받은 요청 수").isZero();
    // 「못 받음」 은 표에 쓰지 않는다 — 꺼진 결과가 「없음」 으로 굳지 않는다.
    verify(naverStore, never()).save(any());
    verify(filler, never()).noteBlocked();
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
  @DisplayName("여럿 — 있는 POI 는 pending, 없는 POI 는 found=false. 네이버 데이터도 요청도 없다")
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

    assertThat(hits.get()).isZero();
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
