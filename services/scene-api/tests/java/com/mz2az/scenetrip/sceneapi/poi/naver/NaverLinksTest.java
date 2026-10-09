package com.mz2az.scenetrip.sceneapi.poi.naver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mz2az.scenetrip.sceneapi.api.model.PoiCategoryGroup;
import com.mz2az.scenetrip.sceneapi.api.model.PoiDetail;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR 0021 (MZ2AZ-373) — 편의시설의 네이버 장소 링크. 검색만 불러 장소 번호를 찾고, 찾았든 「없음」이든 저장해 다시 묻지 않으며, 「못 받음」은 저장하지
 * 않고, 막히면 한동안 묻지 않는다. 상세는 어떤 경우에도 부르지 않는다.
 *
 * <p>진짜 {@link NaverPlaceClient}·{@link PoiCardFetcher} 를 가짜 네이버(로컬 HTTP 서버)에 붙인다. 표({@code
 * poi_naver})는 메모리 맵으로 흉내 낸다 — SQL 은 통합 레인이 본다.
 */
@DisplayName("NaverLinks — 장소 번호로 만드는 네이버 링크 (ADR 0021)")
class NaverLinksTest {

  private static final long ID = 42L;
  private static final double LAT = 37.4375;
  private static final double LNG = 126.7819;
  private static final String PLACE = "https://map.naver.com/p/entry/place/";

  private static HttpServer server;
  private static String base;

  private static final AtomicInteger searchHits = new AtomicInteger();
  private static final AtomicInteger detailHits = new AtomicInteger();
  private static final AtomicInteger status = new AtomicInteger(200);
  private static final AtomicReference<String> body = new AtomicReference<>("{}");
  private static final AtomicReference<Long> delayMillis = new AtomicReference<>(0L);

  /** 검색 요청 순서대로 다른 본문을 주고 싶을 때. 비어 있으면 {@link #body}. */
  private static final List<String> scripted = new java.util.concurrent.CopyOnWriteArrayList<>();

  private PoiNaverStore store;
  private Map<Long, NaverCard> rows;

  @BeforeAll
  static void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext(
        "/",
        exchange -> {
          String path = exchange.getRequestURI().getPath();
          int n = 0;
          if (path.startsWith("/graphql")) {
            n = searchHits.incrementAndGet();
          } else {
            detailHits.incrementAndGet();
          }
          exchange.getRequestBody().readAllBytes();
          try {
            Thread.sleep(delayMillis.get());
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          }
          String reply = n > 0 && n <= scripted.size() ? scripted.get(n - 1) : body.get();
          byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          try {
            exchange.sendResponseHeaders(status.get(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(bytes);
            }
          } catch (IOException ignored) {
            // 클라이언트가 타임아웃으로 먼저 끊었다.
          }
        });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterAll
  static void stop() {
    server.stop(0);
  }

  @BeforeEach
  void reset() {
    searchHits.set(0);
    detailHits.set(0);
    status.set(200);
    body.set(empty());
    delayMillis.set(0L);
    scripted.clear();

    rows = new ConcurrentHashMap<>();
    store = mock(PoiNaverStore.class);
    when(store.find(anyLong(), anyString()))
        .thenAnswer(
            inv -> {
              NaverCard c = rows.get((Long) inv.getArgument(0));
              return Optional.ofNullable(c).filter(r -> r.ruleVersion().equals(inv.getArgument(1)));
            });
    when(store.save(any()))
        .thenAnswer(
            inv -> {
              NaverCard c = inv.getArgument(0);
              rows.put(c.poiId(), c);
              return c;
            });
  }

  @AfterEach
  void neverDetail() {
    assertThat(detailHits.get()).as("상세(사진·평점·영업시간) 호출 — 어떤 경우에도 0").isZero();
  }

  // ── 준비 ────────────────────────────────────────────────────────────────

  private static PoiDetail poi() {
    return new PoiDetail(ID, "정아각 본점", "중식", PoiCategoryGroup.FOOD, LAT, LNG, List.of())
        .region("경기")
        .city("시흥시");
  }

  private static String empty() {
    return "{\"data\":{\"placeList\":{\"businesses\":{\"total\":0,\"items\":[]}}}}";
  }

  /** 우리 편의시설과 약 11 m 떨어진 같은 이름의 후보 하나. */
  private static String nearHit(String id) {
    return candidates(id, "정아각 본점", LAT + 0.0001, LNG);
  }

  private static String candidates(String id, String name, double lat, double lng) {
    return """
    {"data":{"placeList":{"businesses":{"total":1,"items":[
      {"id":"%s","name":"%s","address":{"roadAddress":"경기 시흥시 어딘가 1"},
       "coordinate":{"latitude":%s,"longitude":%s}}
    ]}}}}
    """
        .formatted(id, name, lat, lng);
  }

  private static NaverPlaceClient client(int timeoutMillis) {
    return new NaverPlaceClient(
        false, true, base + "/graphql", base + "/summary/{id}", timeoutMillis);
  }

  private NaverLinks links(boolean enabled, long pauseSeconds) {
    return new NaverLinks(enabled, store, new PoiCardFetcher(client(700)), pauseSeconds);
  }

  private NaverLinks links() {
    return links(true, 60);
  }

  // ── 찾음 ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("찾으면 https://map.naver.com/p/entry/place/{번호} — 번호를 저장하고, 두 번째는 네이버를 부르지 않는다")
  void foundGivesPlaceUrlAndIsStored() {
    body.set(nearHit("5784380"));
    NaverLinks links = links();

    Optional<URI> first = links.placeUrl(poi());

    assertThat(first).contains(URI.create(PLACE + "5784380"));
    assertThat(searchHits.get()).isEqualTo(1);
    NaverCard saved = rows.get(ID);
    assertThat(saved).as("저장된 행").isNotNull();
    assertThat(saved.found()).isTrue();
    assertThat(saved.naverId()).isEqualTo("5784380");
    assertThat(saved.ruleVersion()).isEqualTo(NaverMatcher.RULE_VERSION);

    Optional<URI> second = links.placeUrl(poi());

    assertThat(second).contains(URI.create(PLACE + "5784380"));
    assertThat(searchHits.get()).as("두 번째 열 때 검색 수").isEqualTo(1);
  }

  @Test
  @DisplayName("저장은 장소 번호 하나 — 사진·평점·영업시간 등 상세 칸은 비어 있다")
  void storedRowIsLinkOnly() {
    body.set(nearHit("5784380"));

    links().placeUrl(poi());

    NaverCard saved = rows.get(ID);
    assertThat(saved.naverId()).isEqualTo("5784380");
    assertThat(saved.name()).isNull();
    assertThat(saved.category()).isNull();
    assertThat(saved.address()).isNull();
    assertThat(saved.phone()).isNull();
    assertThat(saved.hours()).isNull();
    assertThat(saved.score()).isNull();
    assertThat(saved.reviewCount()).isNull();
    assertThat(saved.blogReviews()).isNull();
    assertThat(saved.images()).isEmpty();
  }

  @Test
  @DisplayName("이미 저장된 번호가 있으면 네이버를 부르지 않고 번호로 링크를 만든다 — 옛 행의 url 칸은 믿지 않는다")
  void storedIdBuildsLinkWithoutCalling() {
    rows.put(
        ID,
        new NaverCard(
            ID,
            true,
            null,
            NaverMatcher.RULE_VERSION,
            OffsetDateTime.now(),
            "11679241",
            "옛 카드 이름",
            null,
            null,
            null,
            null,
            4.5,
            10,
            null,
            List.of(),
            "https://elsewhere.example/whatever"));

    Optional<URI> url = links().placeUrl(poi());

    assertThat(url).contains(URI.create(PLACE + "11679241"));
    assertThat(searchHits.get()).isZero();
  }

  @Test
  @DisplayName("첫 검색이 먼 곳만 주면 시군구를 붙여 한 번 더 — 검색은 최대 두 번")
  void retriesOnceWithDistrict() {
    // 첫 검색: 같은 이름이지만 약 111 km 떨어진 지점. 두 번째: 바로 옆.
    scripted.add(candidates("999", "정아각 본점", LAT + 1.0, LNG));
    scripted.add(nearHit("5784380"));

    Optional<URI> url = links().placeUrl(poi());

    assertThat(url).contains(URI.create(PLACE + "5784380"));
    assertThat(searchHits.get()).isEqualTo(2);
  }

  // ── 없음 ────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("「없음」은 저장하고 다시 묻지 않는다 — naverPlaceUrl 은 비어 있다")
  void notFoundIsStoredAndNotReasked() {
    body.set(empty());
    NaverLinks links = links();

    Optional<URI> first = links.placeUrl(poi());

    assertThat(first).isEmpty();
    assertThat(searchHits.get()).as("재검색 포함 최대 두 번").isBetween(1, 2);
    assertThat(rows.get(ID)).isNotNull();
    assertThat(rows.get(ID).found()).isFalse();
    int afterFirst = searchHits.get();

    assertThat(links.placeUrl(poi())).isEmpty();
    assertThat(searchHits.get()).as("두 번째 열 때 검색 수").isEqualTo(afterFirst);
  }

  @Test
  @DisplayName("이름은 같아도 먼 곳뿐이면 「없음」 — 링크를 만들지 않는다")
  void farCandidateIsNotFound() {
    body.set(candidates("999", "정아각 본점", LAT + 1.0, LNG));

    assertThat(links().placeUrl(poi())).isEmpty();
    assertThat(searchHits.get()).isLessThanOrEqualTo(2);
    assertThat(rows.get(ID).found()).isFalse();
  }

  // ── 못 받음 ─────────────────────────────────────────────────────────────

  @Test
  @DisplayName("5xx 는 저장하지 않는다 — 비어 있고, 다음에 열면 다시 묻는다")
  void serverErrorIsNotStored() {
    status.set(503);
    NaverLinks links = links();

    assertThat(links.placeUrl(poi())).isEmpty();
    assertThat(rows).isEmpty();
    verify(store, never()).save(any());
    int afterFirst = searchHits.get();
    assertThat(afterFirst).isEqualTo(1);

    status.set(200);
    body.set(nearHit("5784380"));
    assertThat(links.placeUrl(poi())).contains(URI.create(PLACE + "5784380"));
    assertThat(searchHits.get()).isEqualTo(afterFirst + 1);
  }

  @Test
  @DisplayName("타임아웃은 저장하지 않는다 — 비어 있고, 다음에 다시 묻는다")
  void timeoutIsNotStored() {
    delayMillis.set(1_500L);
    NaverLinks links = new NaverLinks(true, store, new PoiCardFetcher(client(200)), 60);

    long t0 = System.nanoTime();
    Optional<URI> url = links.placeUrl(poi());
    long tookMillis = (System.nanoTime() - t0) / 1_000_000;

    assertThat(url).isEmpty();
    assertThat(rows).isEmpty();
    verify(store, never()).save(any());
    assertThat(tookMillis).as("한 번 기다리는 상한을 넘겨 기다리지 않는다").isLessThan(1_400L);

    delayMillis.set(0L);
    body.set(nearHit("5784380"));
    assertThat(links.placeUrl(poi())).contains(URI.create(PLACE + "5784380"));
  }

  @Test
  @DisplayName("막힘 403 — 저장하지 않고, 쉬는 동안은 다른 편의시설도 묻지 않는다")
  void blocked403PausesLookups() {
    blockedPausesLookups(403);
  }

  @Test
  @DisplayName("막힘 429 — 저장하지 않고, 쉬는 동안은 다른 편의시설도 묻지 않는다")
  void blocked429PausesLookups() {
    blockedPausesLookups(429);
  }

  private void blockedPausesLookups(int code) {
    status.set(code);
    NaverLinks links = links();

    assertThat(links.placeUrl(poi())).isEmpty();
    assertThat(rows).isEmpty();
    assertThat(searchHits.get()).isEqualTo(1);

    status.set(200);
    body.set(nearHit("5784380"));
    PoiDetail other =
        new PoiDetail(43L, "정아각 본점", "중식", PoiCategoryGroup.FOOD, LAT, LNG, List.of())
            .region("경기")
            .city("시흥시");
    assertThat(links.placeUrl(poi())).isEmpty();
    assertThat(links.placeUrl(other)).isEmpty();
    assertThat(searchHits.get()).as("쉬는 동안 검색 수").isEqualTo(1);
    assertThat(rows).as("쉬는 동안의 빈 답도 저장하지 않는다").isEmpty();
  }

  @Test
  @DisplayName("막힘 — 쉬는 시간이 지나면 다시 묻는다 (대조군)")
  void blockedResumesAfterPause() throws InterruptedException {
    status.set(429);
    NaverLinks links = links(true, 1);

    assertThat(links.placeUrl(poi())).isEmpty();
    status.set(200);
    body.set(nearHit("5784380"));
    assertThat(links.placeUrl(poi())).isEmpty();
    assertThat(searchHits.get()).isEqualTo(1);

    Thread.sleep(1_200L);

    assertThat(links.placeUrl(poi())).contains(URI.create(PLACE + "5784380"));
    assertThat(searchHits.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("5xx 는 막힘이 아니다 — 쉬지 않고 바로 다음에 다시 묻는다")
  void serverErrorDoesNotPause() {
    status.set(500);
    NaverLinks links = links();

    links.placeUrl(poi());
    links.placeUrl(poi());

    assertThat(searchHits.get()).isEqualTo(2);
  }

  // ── 꺼짐 · 이상한 번호 ──────────────────────────────────────────────────

  @Test
  @DisplayName("링크 스위치가 꺼져 있으면 비어 있고, 표도 네이버도 보지 않는다")
  void switchOffIsSilent() {
    body.set(nearHit("5784380"));
    rows.put(ID, NaverCard.linkOnly(ID, "5784380", NaverMatcher.RULE_VERSION, PLACE + "5784380"));

    Optional<URI> url = links(false, 60).placeUrl(poi());

    assertThat(url).isEmpty();
    assertThat(searchHits.get()).isZero();
    verify(store, never()).save(any());
  }

  @Test
  @DisplayName("저장된 번호가 숫자만이 아니면 비어 있다 — 네이버도 다시 부르지 않는다")
  void storedNonNumericIdGivesNoLink() {
    for (String naverId : List.of("abc", "123abc", "12 34", "123/../evil", "-5", "1.5", "")) {
      rows.clear();
      storedNonNumeric(naverId);
    }
  }

  private void storedNonNumeric(String naverId) {
    rows.put(
        ID,
        new NaverCard(
            ID,
            true,
            null,
            NaverMatcher.RULE_VERSION,
            OffsetDateTime.now(),
            naverId,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            List.of(),
            PLACE + naverId));

    assertThat(links().placeUrl(poi())).as("저장된 번호 \"" + naverId + "\"").isEmpty();
    assertThat(searchHits.get()).as("저장된 번호 \"" + naverId + "\"").isZero();
  }

  @Test
  @DisplayName("검색이 숫자가 아닌 번호를 주면 링크를 만들지 않는다")
  void searchedNonNumericIdGivesNoLink() {
    for (String naverId : List.of("abc", "5784380?x=1", "../5784380")) {
      rows.clear();
      body.set(nearHit(naverId));

      assertThat(links().placeUrl(poi())).as("검색이 준 번호 \"" + naverId + "\"").isEmpty();
    }
  }

  @Test
  @DisplayName("옛 판(rule_version)의 행은 없는 것으로 보고 다시 찾는다")
  void oldRuleVersionIsReasked() {
    rows.put(ID, NaverCard.linkOnly(ID, "1", "v0-old", PLACE + "1"));
    body.set(nearHit("5784380"));

    assertThat(links().placeUrl(poi())).contains(URI.create(PLACE + "5784380"));
    assertThat(searchHits.get()).isEqualTo(1);
  }
}
