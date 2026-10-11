package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
  private static final UUID OTHER_USER = UUID.fromString("1b6a0e33-2f4d-4c8e-9a71-5d0c3e8f7b26");

  /** 서비스 비밀값 픽스처 — 진짜 값이 아니다. */
  private static final String SERVICE = "FIXTURE-service-token-0123456789abcdef";

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

  // ───────────── 에이전트가 넘긴 열쇠 (MZ2AZ-412, guide-rate-subject.md §5) ─────────────

  /**
   * {@code X-Service-Token} 이 설정값과 같고 {@code X-Rate-Subject} 형식이 맞을 때만 그 열쇠로 센다. 아니면 헤더를 무시하고 원래
   * 규칙(토큰 → 설치 UUID → 주소)으로 센다 — 거절하지 않는다.
   */
  @Nested
  @DisplayName("X-Rate-Subject — 서비스 비밀값이 맞을 때만 그 열쇠로 센다")
  class RateSubject {

    /** 에이전트 파드의 주소 — 위임 요청이 실제로 오는 곳. */
    private static final String AGENT_ADDR = "10.0.42.7";

    @BeforeEach
    void trusting() {
      filter = new RequestRateLimitFilter(tokens, MEMBER, GUEST, SERVICE);
    }

    private MockHttpServletRequest delegated(String subject, String serviceToken) {
      MockHttpServletRequest r = new MockHttpServletRequest("GET", "/v1/places");
      r.setRemoteAddr(AGENT_ADDR);
      if (serviceToken != null) {
        r.addHeader(RequestRateLimitFilter.SERVICE_TOKEN_HEADER, serviceToken);
      }
      if (subject != null) {
        r.addHeader(RequestRateLimitFilter.RATE_SUBJECT_HEADER, subject);
      }
      return r;
    }

    private MockHttpServletRequest delegated(String subject) {
      return delegated(subject, SERVICE);
    }

    private MockHttpServletRequest fromAgentWithoutHeaders() {
      MockHttpServletRequest r = new MockHttpServletRequest("GET", "/v1/places");
      r.setRemoteAddr(AGENT_ADDR);
      return r;
    }

    private Object subjectOf(MockHttpServletRequest r) {
      return r.getAttribute(RequestRateLimitFilter.SUBJECT_ATTRIBUTE);
    }

    @Test
    @DisplayName("헤더 이름은 계획 그대로 — X-Service-Token · X-Rate-Subject")
    void headerNames() {
      assertThat(RequestRateLimitFilter.SERVICE_TOKEN_HEADER).isEqualTo("X-Service-Token");
      assertThat(RequestRateLimitFilter.RATE_SUBJECT_HEADER).isEqualTo("X-Rate-Subject");
    }

    @Test
    @DisplayName("비밀값 일치 + user:<UUID> → 가입자 상한(3), 같은 사용자의 JWT 요청과 같은 칸")
    void trustedUserSubjectSharesTheJwtBucket() throws Exception {
      String token = tokens.issue(USER).value();

      Result first = send(delegated("user:" + USER));
      assertThat(first.passed()).isTrue();
      assertThat(first.header("RateLimit-Limit")).isEqualTo("3");
      assertThat(first.header("RateLimit-Remaining")).isEqualTo("2");

      Result viaJwt = send(withToken("/v1/courses", token));
      assertThat(viaJwt.header("RateLimit-Remaining")).isEqualTo("1");

      Result second = send(delegated("user:" + USER));
      assertThat(second.header("RateLimit-Remaining")).isEqualTo("0");

      assertLimited(send(withToken("/v1/courses", token)), MEMBER);
      assertLimited(send(delegated("user:" + USER)), MEMBER);

      // 에이전트 주소의 칸은 건드리지 않았다
      Result agentOwn = send(fromAgentWithoutHeaders());
      assertThat(agentOwn.passed()).isTrue();
      assertThat(agentOwn.header("RateLimit-Remaining")).isEqualTo("1");
    }

    @Test
    @DisplayName("user:<UUID> 의 대문자 UUID 도 같은 칸 — 소문자로 맞춘다")
    void upperCaseUuidLandsInTheSameBucket() throws Exception {
      send(delegated("user:" + USER.toString().toUpperCase()));
      Result viaJwt = send(withToken("/v1/courses", tokens.issue(USER).value()));
      assertThat(viaJwt.header("RateLimit-Remaining")).isEqualTo("1");

      MockHttpServletRequest r = delegated("user:" + USER.toString().toUpperCase());
      send(r);
      assertThat(subjectOf(r)).isEqualTo("user:" + USER);
    }

    @Test
    @DisplayName("다른 사용자의 열쇠는 다른 칸이다")
    void differentUsersAreDifferentBuckets() throws Exception {
      for (int i = 0; i < MEMBER; i++) {
        send(delegated("user:" + USER));
      }
      Result other = send(delegated("user:" + OTHER_USER));
      assertThat(other.passed()).isTrue();
      assertThat(other.header("RateLimit-Remaining")).isEqualTo("2");
    }

    @Test
    @DisplayName("비밀값 일치 + install:<UUID> → 비회원 상한(2), 같은 설치 UUID 의 앱 요청과 같은 칸")
    void trustedInstallSubjectSharesTheInstallBucket() throws Exception {
      Result first = send(delegated("install:" + INSTALL));
      assertThat(first.passed()).isTrue();
      assertThat(first.header("RateLimit-Limit")).isEqualTo("2");
      assertThat(first.header("RateLimit-Remaining")).isEqualTo("1");

      Result app = send(withInstall("/v1/places"));
      assertThat(app.header("RateLimit-Remaining")).isEqualTo("0");

      assertLimited(send(delegated("install:" + INSTALL)), GUEST);
      assertLimited(send(withInstall("/v1/places")), GUEST);
    }

    @Test
    @DisplayName("install:<UUID> 대문자도 같은 칸")
    void upperCaseInstallLandsInTheSameBucket() throws Exception {
      send(delegated("install:" + INSTALL.toUpperCase()));
      assertThat(send(withInstall("/v1/places")).header("RateLimit-Remaining")).isEqualTo("0");
    }

    @Test
    @DisplayName("비밀값 일치 + ip:<주소> → 비회원 상한, 그 주소에서 직접 온 요청과 같은 칸 — 에이전트 주소 칸이 아니다")
    void trustedIpSubjectCountsUnderThatAddress() throws Exception {
      Result first = send(delegated("ip:198.51.100.7"));
      assertThat(first.passed()).isTrue();
      assertThat(first.header("RateLimit-Limit")).isEqualTo("2");

      MockHttpServletRequest direct = new MockHttpServletRequest("GET", "/v1/places");
      direct.setRemoteAddr("198.51.100.7");
      assertThat(send(direct).header("RateLimit-Remaining")).isEqualTo("0");

      assertThat(send(fromAgentWithoutHeaders()).header("RateLimit-Remaining")).isEqualTo("1");
    }

    @Test
    @DisplayName("ip: 뒤는 IPv6 도 된다 — [0-9a-fA-F.:] 1~45 자")
    void ipv6SubjectsAreAccepted() throws Exception {
      for (String subject :
          new String[] {"ip:::1", "ip:2001:db8::1", "ip:2001:DB8::A", "ip:" + "1".repeat(45)}) {
        MockHttpServletRequest r = delegated(subject);
        Result result = send(r);
        assertThat(result.passed()).as(subject).isTrue();
        assertThat(subjectOf(r)).as(subject).isNotNull();
        assertThat(subjectOf(r).toString()).as(subject).startsWith("ip:");
        // 에이전트 주소로 세지 않았다
        assertThat(subjectOf(r)).as(subject).isNotEqualTo("ip:" + AGENT_ADDR);
      }
    }

    @Test
    @DisplayName("비밀값이 틀리거나 없으면 X-Rate-Subject 를 무시하고 기존 규칙으로 센다 — 거절하지 않는다")
    void wrongOrMissingTokenIgnoresSubject() throws Exception {
      for (String wrong :
          new String[] {
            null, "", "nope", SERVICE + "x", SERVICE.substring(1), SERVICE.toUpperCase()
          }) {
        MockHttpServletRequest r = delegated("user:" + USER, wrong);
        Result result = send(r);
        assertThat(result.passed()).as(String.valueOf(wrong)).isTrue();
        assertThat(result.status()).as(String.valueOf(wrong)).isEqualTo(200);
        assertThat(subjectOf(r)).as(String.valueOf(wrong)).isEqualTo("ip:" + AGENT_ADDR);
        // 매번 에이전트 주소 칸을 깎으므로 상한을 넘기지 않게 새 필터로 바꾼다
        filter = new RequestRateLimitFilter(tokens, MEMBER, GUEST, SERVICE);
      }
    }

    @Test
    @DisplayName("비밀값이 틀리면 기존 규칙 순서 그대로 — JWT 가 있으면 그 계정, 설치 UUID 가 있으면 그 설치")
    void wrongTokenFallsBackToJwtThenInstall() throws Exception {
      MockHttpServletRequest withJwt = withToken("/v1/courses", tokens.issue(USER).value());
      withJwt.addHeader(RequestRateLimitFilter.SERVICE_TOKEN_HEADER, "nope");
      withJwt.addHeader(RequestRateLimitFilter.RATE_SUBJECT_HEADER, "user:" + OTHER_USER);
      Result jwt = send(withJwt);
      assertThat(jwt.header("RateLimit-Limit")).isEqualTo("3");
      assertThat(subjectOf(withJwt)).isEqualTo("user:" + USER);

      MockHttpServletRequest withInstall = withInstall("/v1/places");
      withInstall.addHeader(RequestRateLimitFilter.RATE_SUBJECT_HEADER, "user:" + OTHER_USER);
      Result install = send(withInstall);
      assertThat(install.header("RateLimit-Limit")).isEqualTo("2");
      assertThat(subjectOf(withInstall)).isEqualTo("install:" + INSTALL);

      // OTHER_USER 칸은 비어 있다
      assertThat(send(delegated("user:" + OTHER_USER)).header("RateLimit-Remaining"))
          .isEqualTo("2");
    }

    @Test
    @DisplayName("비밀값 일치 + 형식이 틀린 열쇠(admin:x · user:not-a-uuid · 빈 값 …)는 무시하고 기존 규칙")
    void malformedSubjectIsIgnored() throws Exception {
      for (String bad :
          new String[] {
            "admin:x",
            "user:not-a-uuid",
            "",
            "user:",
            "install:",
            "install:not-a-uuid",
            "ip:",
            "ip:" + "1".repeat(46),
            "ip:198.51.100.7/32",
            "ip:host.example",
            "USER:" + USER,
            USER.toString(),
            "user:" + USER + "x",
            "user:" + USER + ",user:" + OTHER_USER,
          }) {
        MockHttpServletRequest r = delegated(bad);
        Result result = send(r);
        assertThat(result.passed()).as(bad).isTrue();
        assertThat(subjectOf(r)).as(bad).isEqualTo("ip:" + AGENT_ADDR);
        // 매번 에이전트 주소 칸을 깎으므로 상한을 넘기지 않게 새 필터로 바꾼다
        filter = new RequestRateLimitFilter(tokens, MEMBER, GUEST, SERVICE);
      }
    }

    @Test
    @DisplayName("설정의 비밀값이 비어 있으면 어떤 X-Service-Token 이 와도 무시한다 — 빈 값끼리 일치로 보지 않는다")
    void emptyConfiguredTokenAlwaysIgnores() throws Exception {
      filter = new RequestRateLimitFilter(tokens, MEMBER, GUEST, "");
      for (String sent : new String[] {"", SERVICE, "anything"}) {
        MockHttpServletRequest r = delegated("user:" + USER, sent);
        send(r);
        assertThat(subjectOf(r)).as("[" + sent + "]").isEqualTo("ip:" + AGENT_ADDR);
        filter = new RequestRateLimitFilter(tokens, MEMBER, GUEST, "");
      }
      // 헤더가 아예 없는 경우도
      MockHttpServletRequest noHeader = delegated("user:" + USER, null);
      send(noHeader);
      assertThat(subjectOf(noHeader)).isEqualTo("ip:" + AGENT_ADDR);
    }

    @Test
    @DisplayName("비밀값을 받지 않는 3 인자 생성자도 X-Rate-Subject 를 늘 무시한다")
    void legacyConstructorIgnores() throws Exception {
      filter = new RequestRateLimitFilter(tokens, MEMBER, GUEST);
      MockHttpServletRequest r = delegated("user:" + USER, "");
      send(r);
      assertThat(subjectOf(r)).isEqualTo("ip:" + AGENT_ADDR);
    }

    @Test
    @DisplayName("비밀값이 맞으면 위임 열쇠가 첫 규칙 — 같은 요청의 JWT 보다 앞선다")
    void trustedSubjectComesFirst() throws Exception {
      MockHttpServletRequest r = withToken("/v1/courses", tokens.issue(USER).value());
      r.addHeader(RequestRateLimitFilter.SERVICE_TOKEN_HEADER, SERVICE);
      r.addHeader(RequestRateLimitFilter.RATE_SUBJECT_HEADER, "install:" + INSTALL);
      Result result = send(r);
      assertThat(subjectOf(r)).isEqualTo("install:" + INSTALL);
      assertThat(result.header("RateLimit-Limit")).isEqualTo("2");
    }

    @Test
    @DisplayName("요청 속성에 쓴 열쇠를 남긴다 — JWT 는 user:, 설치 UUID 는 install:, 그 밖은 ip:")
    void storesTheKeyItUsed() throws Exception {
      MockHttpServletRequest jwt = withToken("/v1/courses", tokens.issue(USER).value());
      send(jwt);
      assertThat(subjectOf(jwt)).isEqualTo("user:" + USER);

      MockHttpServletRequest install = withInstall("/v1/places");
      send(install);
      assertThat(subjectOf(install)).isEqualTo("install:" + INSTALL);

      MockHttpServletRequest upper = get("/v1/places");
      upper.addHeader("X-Install-Id", INSTALL.toUpperCase());
      send(upper);
      assertThat(subjectOf(upper)).isEqualTo("install:" + INSTALL);

      MockHttpServletRequest bare = get("/v1/places");
      send(bare);
      assertThat(subjectOf(bare)).isEqualTo("ip:203.0.113.10");

      MockHttpServletRequest trusted = delegated("user:" + OTHER_USER);
      send(trusted);
      assertThat(subjectOf(trusted)).isEqualTo("user:" + OTHER_USER);
    }

    @Test
    @DisplayName("유료 창구(/v1/guide/chat)도 열쇠를 요청 속성에 남긴다 — GuideController 가 에이전트에 넘긴다")
    void paidPathStoresTheKey() throws Exception {
      MockHttpServletRequest chat = withToken("/v1/guide/chat", tokens.issue(USER).value());
      chat.setMethod("POST");
      send(chat);
      assertThat(subjectOf(chat)).isEqualTo("user:" + USER);

      MockHttpServletRequest plan = get("/v1/guide/plan");
      plan.setMethod("POST");
      send(plan);
      assertThat(subjectOf(plan)).isEqualTo("ip:203.0.113.10");
    }

    @Test
    @DisplayName("액추에이터는 세지 않으므로 열쇠도 남기지 않는다")
    void exemptPathsStoreNothing() throws Exception {
      MockHttpServletRequest r = withInstall("/v1/actuator/health");
      send(r);
      assertThat(subjectOf(r)).isNull();
    }
  }

  // ───────────── 429 로그 — 열쇠의 앞머리만, 칸마다 1 분에 한 번 ─────────────

  @Nested
  @ExtendWith(OutputCaptureExtension.class)
  @DisplayName("429 로그 — 앞머리만, 칸마다 분에 한 번")
  class LimitedLog {

    @BeforeEach
    void trusting() {
      filter = new RequestRateLimitFilter(tokens, MEMBER, GUEST, SERVICE);
    }

    private long filterLines(CapturedOutput output) {
      return output.getAll().lines().filter(l -> l.contains("RequestRateLimitFilter")).count();
    }

    @Test
    @DisplayName("같은 칸이 여러 번 429 여도 한 줄, 값(UUID·주소·비밀값)은 남기지 않는다")
    void logsPrefixOncePerBucket(CapturedOutput output) throws Exception {
      long before = filterLines(output);
      for (int i = 0; i < GUEST + 3; i++) {
        send(withInstall("/v1/places"));
      }
      long afterInstall = filterLines(output);
      assertThat(afterInstall - before).as("설치 칸의 429 로그 줄 수").isEqualTo(1);

      MockHttpServletRequest d;
      for (int i = 0; i < MEMBER + 2; i++) {
        d = new MockHttpServletRequest("GET", "/v1/places");
        d.setRemoteAddr("10.0.42.7");
        d.addHeader(RequestRateLimitFilter.SERVICE_TOKEN_HEADER, SERVICE);
        d.addHeader(RequestRateLimitFilter.RATE_SUBJECT_HEADER, "user:" + USER);
        send(d);
      }
      for (int i = 0; i < GUEST + 2; i++) {
        send(get("/v1/places"));
      }
      assertThat(filterLines(output) - afterInstall).as("다른 두 칸은 한 줄씩").isEqualTo(2);

      String all = output.getAll();
      assertThat(all).doesNotContain(INSTALL).doesNotContain(USER.toString());
      assertThat(all).doesNotContain("203.0.113.10").doesNotContain(SERVICE);
      String lines =
          String.join("\n", all.lines().filter(l -> l.contains("RequestRateLimitFilter")).toList());
      assertThat(lines).contains("install").contains("user").contains("ip");
    }
  }
}
