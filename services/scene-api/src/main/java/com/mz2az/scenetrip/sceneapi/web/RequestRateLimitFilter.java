package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 일반 요청의 분당 상한(계약 「요청 한도」, 계획 {@code rate-limit.md} §2·§3) — 넘으면 {@code 429 RATE_LIMITED}.
 *
 * <p><b>서버 메모리에서 센다.</b> 모든 요청마다 DB 에 쓰지 않으려고서다. 서버(파드)마다 따로 세므로 파드가 둘이면 실제 상한은 두 배가 되지만, 이 제한의 목적은
 * 폭주·버그를 막는 것이라 충분하다. 돈이 드는 챗봇·길찾기는 DB 로 정확히 센다({@code PaidQuota}).
 *
 * <p><b>누구로 세나:</b> 믿을 수 있는 액세스 토큰이면 그 계정(가입자 상한), 아니면 {@code X-Install-Id} 의 설치 UUID(비회원 상한), 둘 다
 * 없으면 접속 주소. 토큰이 틀렸어도 여기서는 거절하지 않는다 — 그 판단은 창구의 몫이다. 창은 고정 1 분(그 분의 0 초부터)이다.
 *
 * <p><b>가이드 에이전트가 대신 보낸 읽기</b>(ADR 0023, MZ2AZ-412)는 그 사용자 몫으로 센다. 에이전트는 자격 증명을 받지 않으므로 위 규칙으로는 모두
 * 에이전트 주소 한 칸이 된다. 그래서 이 필터가 앱 요청에 쓴 열쇠를 {@link #SUBJECT_ATTRIBUTE} 에 남기고({@code GuideController} 가
 * 에이전트에 넘긴다), 에이전트가 {@code X-Rate-Subject} 로 돌려 보낸 열쇠는 {@code X-Service-Token} 이 맞을 때만 믿는다. 열쇠는 칸을
 * 고를 뿐이다 — 계정을 정하는 {@code CurrentAccount} 는 이 헤더를 읽지 않는다.
 */
@Component
class RequestRateLimitFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(RequestRateLimitFilter.class);

  private static final String BEARER = "Bearer ";

  /** 서비스끼리의 비밀값. 게이트웨이가 바깥 요청에서 지운다(helm {@code config.yaml}). */
  static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

  /** 에이전트가 「이 열쇠로 세라」 고 돌려 보내는 헤더. 값은 이 필터가 만든 열쇠 그대로다. */
  static final String RATE_SUBJECT_HEADER = "X-Rate-Subject";

  /** 이 요청에 쓴 열쇠 — {@code GuideController} 가 읽어 에이전트에 넘긴다. */
  static final String SUBJECT_ATTRIBUTE = RequestRateLimitFilter.class.getName() + ".subject";

  /**
   * 믿을 수 있는 열쇠의 모양 — 이 필터가 만드는 셋뿐이다. 주소는 IPv4·IPv6 에 쓰이는 글자만, 길이는 IPv6 최대(45)까지. 그 밖은 무시한다 — 비밀값이
   * 맞아도 임의의 문자열로 칸을 끝없이 만들게 두지 않는다.
   */
  private static final Pattern RATE_SUBJECT =
      Pattern.compile(
          "(user|install):([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
              + "[0-9a-fA-F]{12})|ip:([0-9a-fA-F.:]{1,45})");

  /** 돈이 드는 창구는 자기 한도의 RateLimit 헤더를 싣는다 — 여기서 겹쳐 싣지 않는다. 분당 수는 똑같이 센다. */
  private static final Set<String> PAID_PATHS = Set.of("/v1/guide/chat", "/v1/navigation/next-leg");

  private final AccessTokens tokens;
  private final int memberPerMinute;
  private final int guestPerMinute;

  /** 비어 있으면 null — 그때는 {@code X-Rate-Subject} 를 늘 무시한다(빈 값끼리 같다고 보지 않는다). */
  private final byte[] serviceToken;

  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  /**
   * 설정값을 받아 필터를 만든다.
   *
   * @param serviceToken 서비스 비밀값({@code SCENETRIP_SERVICE_TOKEN}). 비어 있어도 뜬다 — 에이전트의 읽기가 다시 주소 한 칸으로
   *     세어질 뿐이라 기동 때 경고만 남긴다.
   */
  @Autowired
  RequestRateLimitFilter(
      AccessTokens tokens,
      @Value("${scenetrip.limits.plans.free.requests-per-minute:120}") int memberPerMinute,
      @Value("${scenetrip.limits.guest-requests-per-minute:60}") int guestPerMinute,
      @Value("${scenetrip.guide.service-token:}") String serviceToken) {
    this.tokens = tokens;
    this.memberPerMinute = memberPerMinute;
    this.guestPerMinute = guestPerMinute;
    String token = serviceToken == null ? "" : serviceToken.strip();
    this.serviceToken = token.isEmpty() ? null : token.getBytes(StandardCharsets.UTF_8);
    if (this.serviceToken == null) {
      log.warn("scenetrip.guide.service-token 이 비어 있다 — 에이전트의 읽기가 에이전트 주소 한 칸으로 세어진다(ADR 0023)");
    }
  }

  /** 서비스 비밀값 없이 — 에이전트 열쇠를 다루지 않는 시험용. */
  RequestRateLimitFilter(AccessTokens tokens, int memberPerMinute, int guestPerMinute) {
    this(tokens, memberPerMinute, guestPerMinute, "");
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    // 헬스체크·관리 창구는 세지 않는다 — 쿠버네티스 프로브가 한도를 깎으면 안 된다.
    return !path.startsWith("/v1/") || path.startsWith("/v1/actuator");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    Subject subject = subject(request);
    request.setAttribute(SUBJECT_ATTRIBUTE, subject.key());
    long now = System.currentTimeMillis();
    long minute = now / 60_000;
    int used = count(subject.key(), minute);
    long reset = Math.max(1, ((minute + 1) * 60_000 - now) / 1000);

    if (used > subject.limit()) {
      if (used == subject.limit() + 1) {
        // 칸마다 그 분의 첫 거절만 — 폭주 중에 로그가 같이 폭주하지 않게. 열쇠 자체(계정 UUID·주소)는 남기지 않는다.
        log.info("요청 한도 초과: {} 칸, 분당 {}", kind(subject.key()), subject.limit());
      }
      response.setStatus(429);
      response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(reset));
      setRateLimit(response, subject.limit(), 0, reset);
      response.setContentType("application/json");
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      response
          .getWriter()
          .write(
              "{\"code\":\"RATE_LIMITED\",\"message\":\"요청이 너무 많습니다 — "
                  + reset
                  + " 초 뒤에 다시 보내세요\"}");
      return;
    }
    if (!PAID_PATHS.contains(request.getRequestURI())) {
      setRateLimit(response, subject.limit(), subject.limit() - used, reset);
    }
    chain.doFilter(request, response);
  }

  /** 이번 분의 수를 하나 늘리고 돌려준다. 분이 바뀌었으면 새로 센다. */
  private int count(String key, long minute) {
    Bucket b =
        buckets.compute(
            key, (k, old) -> old == null || old.minute != minute ? new Bucket(minute) : old);
    int n = b.count.incrementAndGet();
    // 지난 분의 칸은 가끔 치운다 — 열쇠가 끝없이 늘지 않게.
    if (buckets.size() > 10_000) {
      buckets.entrySet().removeIf(e -> e.getValue().minute < minute);
    }
    return n;
  }

  private Subject subject(HttpServletRequest request) {
    Subject delegated = delegated(request);
    if (delegated != null) {
      return delegated;
    }
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authorization != null
        && authorization.length() > BEARER.length()
        && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
      try {
        UUID user = tokens.verify(authorization.substring(BEARER.length()).strip());
        return new Subject("user:" + user, memberPerMinute);
      } catch (RuntimeException ignored) {
        // 틀린 토큰은 아래로 — 거절은 창구가 한다
      }
    }
    String install = request.getHeader("X-Install-Id");
    if (install != null) {
      try {
        return new Subject("install:" + UUID.fromString(install.strip()), guestPerMinute);
      } catch (IllegalArgumentException ignored) {
        // 형식이 틀린 설치 UUID 는 주소로 센다
      }
    }
    return new Subject("ip:" + request.getRemoteAddr(), guestPerMinute);
  }

  /**
   * 가이드 에이전트가 사용자 대신 보낸 요청이면 그 사용자의 열쇠, 아니면 null.
   *
   * <p>비밀값이 틀리거나 없거나 열쇠 모양이 틀리면 거절하지 않고 null — 그러면 원래 규칙으로 센다. 읽기 창구는 누구나 부를 수 있고, 무시하면 이 헤더가 없던 때와
   * 같아질 뿐이다.
   */
  private Subject delegated(HttpServletRequest request) {
    if (serviceToken == null) {
      return null;
    }
    String given = request.getHeader(SERVICE_TOKEN_HEADER);
    String subject = request.getHeader(RATE_SUBJECT_HEADER);
    if (given == null || subject == null) {
      return null;
    }
    // 상수 시간 비교 — 앞에서부터 맞은 글자 수만큼 시간이 달라지면 응답 시간으로 한 글자씩 알아낼 수 있다.
    if (!MessageDigest.isEqual(serviceToken, given.strip().getBytes(StandardCharsets.UTF_8))) {
      return null;
    }
    Matcher m = RATE_SUBJECT.matcher(subject.strip());
    if (!m.matches()) {
      return null;
    }
    if (m.group(1) != null) {
      // UUID 는 소문자 정규형으로 — 이 필터가 만드는 열쇠(UUID.toString)와 같은 칸이어야 한다.
      String key = m.group(1) + ":" + UUID.fromString(m.group(2));
      return new Subject(key, m.group(1).equals("user") ? memberPerMinute : guestPerMinute);
    }
    return new Subject("ip:" + m.group(3), guestPerMinute);
  }

  /** 열쇠의 앞머리 — 로그에는 이것만. */
  private static String kind(String key) {
    int colon = key.indexOf(':');
    return colon < 0 ? key : key.substring(0, colon);
  }

  private static void setRateLimit(
      HttpServletResponse response, int limit, int remaining, long reset) {
    response.setHeader("RateLimit-Limit", Integer.toString(limit));
    response.setHeader("RateLimit-Remaining", Integer.toString(Math.max(0, remaining)));
    response.setHeader("RateLimit-Reset", Long.toString(reset));
  }

  private record Subject(String key, int limit) {}

  private static final class Bucket {
    final long minute;
    final AtomicInteger count = new AtomicInteger();

    Bucket(long minute) {
      this.minute = minute;
    }
  }
}
