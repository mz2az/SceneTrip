package com.mz2az.scenetrip.sceneapi.web;

import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
 */
@Component
class RequestRateLimitFilter extends OncePerRequestFilter {

  private static final String BEARER = "Bearer ";

  /** 돈이 드는 창구는 자기 한도의 RateLimit 헤더를 싣는다 — 여기서 겹쳐 싣지 않는다. 분당 수는 똑같이 센다. */
  private static final Set<String> PAID_PATHS = Set.of("/v1/guide/chat", "/v1/navigation/next-leg");

  private final AccessTokens tokens;
  private final int memberPerMinute;
  private final int guestPerMinute;
  private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

  RequestRateLimitFilter(
      AccessTokens tokens,
      @Value("${scenetrip.limits.plans.free.requests-per-minute:120}") int memberPerMinute,
      @Value("${scenetrip.limits.guest-requests-per-minute:60}") int guestPerMinute) {
    this.tokens = tokens;
    this.memberPerMinute = memberPerMinute;
    this.guestPerMinute = guestPerMinute;
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
    long now = System.currentTimeMillis();
    long minute = now / 60_000;
    int used = count(subject.key(), minute);
    long reset = Math.max(1, ((minute + 1) * 60_000 - now) / 1000);

    if (used > subject.limit()) {
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
