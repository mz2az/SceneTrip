package com.mz2az.scenetrip.sceneapi.poi.naver;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.poi.naver.NaverMatcher.Candidate;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverPlaceClient.Detail;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverPlaceClient.Outcome;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 가짜 네이버. JDK HttpServer 가 정해진 JSON 을 돌려주고 클라이언트가 그 주소를 본다.
 *
 * <p>응답 JSON 은 <b>모양만 흉내</b> 낸 것이다 — 네이버 데이터를 저장소에 두지 않는다. 검증하는 것은 칸을 제대로 읽는가, 실패를 던지지 않고 답하는가, 막힘을
 * 구분하는가다.
 */
@DisplayName("NaverPlaceClient — 가짜 네이버")
class NaverPlaceClientTest {

  private static HttpServer server;
  private static String base;

  /** 다음 응답. (상태 코드, 본문). 테스트마다 갈아 끼운다. */
  private static final AtomicReference<int[]> status = new AtomicReference<>(new int[] {200});

  private static final AtomicReference<String> body = new AtomicReference<>("{}");
  private static final AtomicReference<Long> delayMillis = new AtomicReference<>(0L);
  private static final AtomicReference<String> lastPath = new AtomicReference<>();
  private static final AtomicReference<String> lastUserAgent = new AtomicReference<>();

  @BeforeAll
  static void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    // 단일 스레드면 타임아웃 테스트가 다음 테스트를 막는다.
    server.setExecutor(Executors.newCachedThreadPool());
    server.createContext(
        "/",
        exchange -> {
          lastPath.set(exchange.getRequestURI().getPath());
          lastUserAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
          try {
            Thread.sleep(delayMillis.get());
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          }
          byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(status.get()[0], bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterAll
  static void stop() {
    server.stop(0);
  }

  private static NaverPlaceClient client(boolean enabled) {
    return new NaverPlaceClient(enabled, base + "/graphql", base + "/summary/{id}", 1);
  }

  private static void respond(int code, String json) {
    status.set(new int[] {code});
    body.set(json);
    delayMillis.set(0L);
  }

  @Test
  @DisplayName("검색 — 후보의 id·이름·좌표를 읽는다. 없는 좌표는 null")
  void parsesSearch() {
    respond(
        200,
        """
        {"data":{"placeList":{"businesses":{"total":2,"items":[
          {"id":"1001","name":"가게 하나","address":{"roadAddress":"서울 어딘가 1"},"coordinate":{"latitude":37.5,"longitude":127.0}},
          {"id":"1002","name":"가게 둘","address":{},"coordinate":{}}
        ]}}}}
        """);

    Outcome<List<Candidate>> out = client(true).search("가게");

    assertThat(out.ok()).isTrue();
    assertThat(out.value()).hasSize(2);
    assertThat(out.value().get(0)).isEqualTo(new Candidate("1001", "가게 하나", 37.5, 127.0));
    assertThat(out.value().get(1).lat()).isNull();
    assertThat(lastPath.get()).isEqualTo("/graphql");
    assertThat(lastUserAgent.get()).contains("Android");
  }

  @Test
  @DisplayName("검색 0 건은 실패가 아니라 빈 목록이다")
  void emptySearchIsNotFailure() {
    respond(200, "{\"data\":{\"placeList\":{\"businesses\":{\"total\":0,\"items\":[]}}}}");

    Outcome<List<Candidate>> out = client(true).search("아무것도");

    assertThat(out.ok()).isTrue();
    assertThat(out.value()).isEmpty();
  }

  @Test
  @DisplayName("상세 — 별점·리뷰 수·사진 3 장·영업시간. 사진은 3 장까지만")
  void parsesDetail() {
    respond(
        200,
        """
        {"data":{"placeDetail":{
          "name":"가게 하나","category":{"category":"칼국수,만두"},
          "address":{"roadAddress":"서울 어딘가 1","address":"서울 어딘가 지번"},
          "phone":"02-000-0000",
          "businessHours":{"description":"매일 10:00 - 22:00"},
          "visitorReviews":{"displayText":"방문자 리뷰 5,056","score":4.39},
          "blogReviews":{"total":812},
          "images":{"images":[{"origin":"https://img.example/1"},{"origin":"https://img.example/2"},{"origin":"https://img.example/3"},{"origin":"https://img.example/4"}]}
        }}}
        """);

    Outcome<Detail> out = client(true).detail("1001");

    assertThat(out.ok()).isTrue();
    Detail d = out.value();
    assertThat(d.name()).isEqualTo("가게 하나");
    assertThat(d.category()).isEqualTo("칼국수,만두");
    assertThat(d.address()).isEqualTo("서울 어딘가 1");
    assertThat(d.phone()).isEqualTo("02-000-0000");
    assertThat(d.hours()).isEqualTo("매일 10:00 - 22:00");
    assertThat(d.score()).isEqualTo(4.39);
    assertThat(d.reviewCount()).isEqualTo(5056);
    assertThat(d.blogReviews()).isEqualTo(812);
    assertThat(d.images()).hasSize(3);
    assertThat(d.url()).isEqualTo("https://map.naver.com/p/entry/place/1001");
    assertThat(lastPath.get()).isEqualTo("/summary/1001");
  }

  @Test
  @DisplayName("별점이 없으면 null — 0 으로 채우지 않는다. 전화는 virtualPhone 로 폴백")
  void keepsMissingScoreNull() {
    respond(
        200,
        """
        {"data":{"placeDetail":{"name":"체인 카페","visitorReviews":{"displayText":"방문자 리뷰 137","score":null},
          "virtualPhone":"0507-000-0000","images":{"images":[]}}}}
        """);

    Detail d = client(true).detail("2").value();

    assertThat(d.score()).isNull();
    assertThat(d.reviewCount()).isEqualTo(137);
    assertThat(d.phone()).isEqualTo("0507-000-0000");
    assertThat(d.images()).isEmpty();
  }

  @Test
  @DisplayName("상세가 비어 있으면 실패 — id 가 낡았거나 형식이 바뀐 것")
  void emptyDetailIsFailure() {
    respond(200, "{\"data\":{\"placeDetail\":null}}");

    Outcome<Detail> out = client(true).detail("3");

    assertThat(out.ok()).isFalse();
    assertThat(out.blocked()).isFalse();
    assertThat(out.why()).contains("비어");
  }

  @Test
  @DisplayName("403 · 429 는 막힌 것 — blocked. 던지지 않는다")
  void blockedIsSignalled() {
    for (int code : new int[] {403, 429}) {
      respond(code, "{}");

      Outcome<List<Candidate>> out = client(true).search("가게");

      assertThat(out.ok()).as("HTTP " + code).isFalse();
      assertThat(out.blocked()).as("HTTP " + code).isTrue();
    }
  }

  @Test
  @DisplayName("500 · 깨진 JSON 은 이번만 실패 — blocked 아님")
  void transientFailures() {
    respond(500, "{}");
    Outcome<List<Candidate>> a = client(true).search("가게");
    assertThat(a.ok()).isFalse();
    assertThat(a.blocked()).isFalse();

    respond(200, "이건 JSON 이 아니다");
    Outcome<List<Candidate>> b = client(true).search("가게");
    assertThat(b.ok()).isFalse();
    assertThat(b.blocked()).isFalse();
  }

  @Test
  @DisplayName("타임아웃도 이번만 실패 — 클라이언트는 timeout 1 초")
  void timeoutIsTransient() {
    respond(200, "{}");
    delayMillis.set(2_500L);

    Outcome<List<Candidate>> out = client(true).search("가게");

    assertThat(out.ok()).isFalse();
    assertThat(out.blocked()).isFalse();
    delayMillis.set(0L);
  }

  @Test
  @DisplayName("꺼져 있으면 요청 자체가 나가지 않는다")
  void disabledDoesNotCall() {
    lastPath.set(null);
    respond(200, "{}");

    Outcome<List<Candidate>> out = client(false).search("가게");

    assertThat(out.ok()).isFalse();
    assertThat(out.why()).contains("꺼져");
    assertThat(lastPath.get()).isNull();
  }

  @Test
  @DisplayName("리뷰 수 문구 파싱")
  void parsesReviewCount() {
    assertThat(NaverPlaceClient.parseReviewCount("방문자 리뷰 5,056")).isEqualTo(5056);
    assertThat(NaverPlaceClient.parseReviewCount("방문자 리뷰 7")).isEqualTo(7);
    assertThat(NaverPlaceClient.parseReviewCount("리뷰 없음")).isNull();
    assertThat(NaverPlaceClient.parseReviewCount(null)).isNull();
  }
}
