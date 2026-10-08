package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 일반 분당 상한(계약 「요청 한도」 A) — 누구로 세는지(토큰 계정 → 설치 UUID → 주소), 넘으면 429 RATE_LIMITED 와 헤더, 액추에이터 제외, 유료
 * 창구는 자기 헤더를 싣도록 RateLimit 헤더를 비운다.
 *
 * <p>필터를 직접 부른다(서블릿 컨테이너 없이). 상한은 작게(비회원 2, 가입자 3) 만든다. 창이 「시계의 분」 이라 테스트가 분 경계를 넘으면 수가 다시 0 이 된다 —
 * 그래서 각 테스트는 분의 끝 10 초 안이면 다음 분까지 기다렸다 시작한다.
 */
@DisplayName("RequestRateLimitFilter — 일반 분당 상한")
class RequestRateLimitFilterTest {

  private static final int MEMBER = 3;
  private static final int GUEST = 2;
  private static final byte[] SECRET = filled(32, (byte) 7);
  private static final byte[] OTHER_SECRET = filled(32, (byte) 9);
  private static final String INSTALL = "3f2a7c10-8b4e-4f21-9a33-1c5d7e9b0a44";
  private static final UUID USER = UUID.fromString("9d1e4b52-6c07-4a8f-b3d1-2e6f80c4a915");

  private final AccessTokens tokens =
      new AccessTokens(SECRET, Duration.ofMinutes(30), Clock.systemUTC());
  private RequestRateLimitFilter filter;

  private static byte[] filled(int n, byte b) {
    byte[] out = new byte[n];
    Arrays.fill(out, b);
    return out;
  }

  @BeforeEach
  void setUp() throws InterruptedException {
    long intoMinute = System.currentTimeMillis() % 60_000;
    if (intoMinute > 50_000) {
      Thread.sleep(60_000 - intoMinute + 50);
    }
    filter = new RequestRateLimitFilter(tokens, MEMBER, GUEST);
  }

  /** 한 요청을 태운 결과 — 응답과, 다음(창구)까지 갔는지. */
  private record Result(MockHttpServletResponse response, boolean passed) {
    int status() {
      return response.getStatus();
    }

    String header(String name) {
      return response.getHeader(name);
    }
  }

  private Result send(MockHttpServletRequest request) throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    filter.doFilter(request, response, chain);
    return new Result(response, chain.getRequest() != null);
  }

  private static MockHttpServletRequest get(String uri) {
    MockHttpServletRequest r = new MockHttpServletRequest("GET", uri);
    r.setRemoteAddr("203.0.113.10");
    return r;
  }

  private static MockHttpServletRequest withInstall(String uri) {
    MockHttpServletRequest r = get(uri);
    r.addHeader("X-Install-Id", INSTALL);
    return r;
  }

  private MockHttpServletRequest withToken(String uri, String token) {
    MockHttpServletRequest r = withInstall(uri);
    r.addHeader("Authorization", "Bearer " + token);
    return r;
  }

  private static void assertLimited(Result r, int limit) throws Exception {
    assertThat(r.passed()).as("창구까지 가면 안 된다").isFalse();
    assertThat(r.status()).isEqualTo(429);
    assertThat(r.response().getContentType()).startsWith("application/json");
    String body = r.response().getContentAsString();
    assertThat(body).contains("\"code\":\"RATE_LIMITED\"").contains("\"message\":\"");
    long retry = Long.parseLong(r.header("Retry-After"));
    assertThat(retry).isBetween(1L, 60L);
    assertThat(r.header("RateLimit-Limit")).isEqualTo(Integer.toString(limit));
    assertThat(r.header("RateLimit-Remaining")).isEqualTo("0");
    assertThat(Long.parseLong(r.header("RateLimit-Reset"))).isBetween(1L, 60L);
  }

  @Test
  @DisplayName("설치 UUID 로 센다 — 비회원 상한(2) 안에서는 통과하고 RateLimit 헤더가 남은 양을 알린다, 넘으면 429 RATE_LIMITED")
  void guestByInstallId() throws Exception {
    Result first = send(withInstall("/v1/places"));
    assertThat(first.passed()).isTrue();
    assertThat(first.status()).isEqualTo(200);
    assertThat(first.header("RateLimit-Limit")).isEqualTo("2");
    assertThat(first.header("RateLimit-Remaining")).isEqualTo("1");
    long reset = Long.parseLong(first.header("RateLimit-Reset"));
    long expected = 60 - (System.currentTimeMillis() % 60_000) / 1000;
    assertThat(reset)
        .isBetween(1L, 60L)
        .isCloseTo(expected, org.assertj.core.data.Offset.offset(2L));

    Result second = send(withInstall("/v1/search"));
    assertThat(second.passed()).isTrue();
    assertThat(second.header("RateLimit-Remaining")).isEqualTo("0");

    assertLimited(send(withInstall("/v1/places")), GUEST);
  }

  @Test
  @DisplayName("Retry-After 는 다음 분까지 남은 초 — RateLimit-Reset 과 같다")
  void retryAfterIsSecondsToNextMinute() throws Exception {
    send(withInstall("/v1/places"));
    send(withInstall("/v1/places"));
    Result limited = send(withInstall("/v1/places"));

    long expected = 60 - (System.currentTimeMillis() % 60_000) / 1000;
    long retry = Long.parseLong(limited.header("Retry-After"));
    assertThat(retry).isCloseTo(expected, org.assertj.core.data.Offset.offset(2L));
    assertThat(limited.header("RateLimit-Reset")).isEqualTo(limited.header("Retry-After"));
  }

  @Test
  @DisplayName("믿을 수 있는 토큰이면 그 계정으로, 가입자 상한(3)")
  void memberByValidToken() throws Exception {
    String token = tokens.issue(USER).value();
    for (int i = 0; i < MEMBER; i++) {
      Result r = send(withToken("/v1/courses", token));
      assertThat(r.passed()).isTrue();
      assertThat(r.header("RateLimit-Limit")).isEqualTo("3");
      assertThat(r.header("RateLimit-Remaining")).isEqualTo(Integer.toString(MEMBER - 1 - i));
    }
    assertLimited(send(withToken("/v1/courses", token)), MEMBER);
  }

  @Test
  @DisplayName("Bearer 는 대소문자를 가리지 않는다 — 소문자 bearer 도 계정으로 센다")
  void bearerIsCaseInsensitive() throws Exception {
    MockHttpServletRequest r = get("/v1/courses");
    r.addHeader("Authorization", "bearer " + tokens.issue(USER).value());

    assertThat(send(r).header("RateLimit-Limit")).isEqualTo("3");
  }

  @Test
  @DisplayName("계정과 설치는 따로 센다 — 토큰 요청은 같은 설치 UUID 의 비회원 몫을 깎지 않는다")
  void accountAndInstallAreSeparateBuckets() throws Exception {
    String token = tokens.issue(USER).value();
    for (int i = 0; i < MEMBER; i++) {
      send(withToken("/v1/courses", token));
    }

    Result guest = send(withInstall("/v1/places"));
    assertThat(guest.passed()).isTrue();
    assertThat(guest.header("RateLimit-Remaining")).isEqualTo("1");
  }

  @Test
  @DisplayName("틀린 토큰은 여기서 거절하지 않는다 — 토큰이 없는 것처럼 설치 UUID 로 센다")
  void invalidTokenIsTreatedAsNoToken() throws Exception {
    String forged =
        new AccessTokens(OTHER_SECRET, Duration.ofMinutes(30), Clock.systemUTC())
            .issue(USER)
            .value();

    Result first = send(withToken("/v1/places", forged));
    assertThat(first.passed()).isTrue();
    assertThat(first.status()).isEqualTo(200);
    assertThat(first.header("RateLimit-Limit")).isEqualTo("2");

    Result garbage = send(withToken("/v1/places", "not-a-jwt"));
    assertThat(garbage.passed()).isTrue();
    assertThat(garbage.header("RateLimit-Remaining")).isEqualTo("0");

    // 같은 설치 UUID 의 몫이므로 토큰 없이 보내도 이미 다 썼다
    assertLimited(send(withInstall("/v1/places")), GUEST);
  }

  @Test
  @DisplayName("둘 다 없으면 접속 주소로 센다 — 주소가 다르면 따로, 형식이 틀린 설치 UUID 도 주소로")
  void fallsBackToClientAddress() throws Exception {
    send(get("/v1/places"));
    MockHttpServletRequest malformed = get("/v1/places");
    malformed.addHeader("X-Install-Id", "not-a-uuid");
    Result second = send(malformed);
    assertThat(second.passed()).isTrue();
    assertThat(second.header("RateLimit-Limit")).isEqualTo("2");
    assertThat(second.header("RateLimit-Remaining")).isEqualTo("0");

    assertLimited(send(get("/v1/places")), GUEST);

    MockHttpServletRequest other = get("/v1/places");
    other.setRemoteAddr("198.51.100.20");
    assertThat(send(other).passed()).isTrue();
    // 그 주소가 다 써도 설치 UUID 를 보낸 요청은 따로다
    assertThat(send(withInstall("/v1/places")).passed()).isTrue();
  }

  @Test
  @DisplayName("/v1/actuator 와 /v1 밖은 세지도 막지도 않는다 — 헤더도 없다")
  void actuatorAndNonV1AreExempt() throws Exception {
    for (int i = 0; i < 10; i++) {
      for (String uri :
          new String[] {
            "/v1/actuator/health", "/v1/actuator/prometheus", "/actuator/health", "/health"
          }) {
        Result r = send(withInstall(uri));
        assertThat(r.passed()).as(uri).isTrue();
        assertThat(r.header("RateLimit-Limit")).as(uri).isNull();
      }
    }
    // 위 요청들이 몫을 깎지 않았다
    assertThat(send(withInstall("/v1/places")).header("RateLimit-Remaining")).isEqualTo("1");
  }

  @Test
  @DisplayName("유료 창구(/v1/guide/chat · /v1/navigation/next-leg)는 RateLimit 헤더를 싣지 않지만 분당 수는 똑같이 센다")
  void paidPathsAreCountedWithoutHeaders() throws Exception {
    for (String uri : new String[] {"/v1/guide/chat", "/v1/navigation/next-leg"}) {
      MockHttpServletRequest r = withInstall(uri);
      r.setMethod("POST");
      Result result = send(r);
      assertThat(result.passed()).as(uri).isTrue();
      assertThat(result.header("RateLimit-Limit")).as(uri).isNull();
      assertThat(result.header("RateLimit-Remaining")).as(uri).isNull();
      assertThat(result.header("RateLimit-Reset")).as(uri).isNull();
    }
    // 두 번을 이미 셌다 — 다음은 429
    assertLimited(send(withInstall("/v1/places")), GUEST);

    // 유료 창구도 넘으면 429 RATE_LIMITED 와 헤더
    MockHttpServletRequest paid = withInstall("/v1/guide/chat");
    paid.setMethod("POST");
    assertLimited(send(paid), GUEST);
  }
}
