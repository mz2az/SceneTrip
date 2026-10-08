package com.mz2az.scenetrip.sceneapi.limit;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 유료 API 한도 — 가이드 챗봇과 여행 중 길찾기(계약 「요청 한도」, 계획 {@code rate-limit.md} §2).
 *
 * <p>창이 둘이다 — 짧은 창(챗봇 1 시간, 길찾기 1 분)과 하루(한국 시간 자정). 외부 API 를 부르기 <b>직전</b>에 {@link #consume} 하고,
 * 제공자가 실패해 사용자가 아무것도 받지 못했으면 {@link #refund} 한다. 거절한 요청(400·404)은 그 전에 끝나므로 세지 않는다.
 *
 * <p>숫자는 요금제별 설정이다(지금은 {@code free} 하나). 유료화하면 계정의 요금제로 고르기만 하면 된다.
 */
@Component
public class PaidQuota {

  /** 한국 시간 자정에 하루가 다시 찬다. */
  static final ZoneId DAY_ZONE = ZoneId.of("Asia/Seoul");

  /** 비용이 드는 기능. 이름은 표의 {@code feature} 와 같다. */
  public enum Feature {
    GUIDE_CHAT("guide-chat", "GUIDE_LIMIT_REACHED"),
    NAVIGATION("navigation", "NAVIGATION_LIMIT_REACHED");

    final String column;
    final String code;

    Feature(String column, String code) {
      this.column = column;
      this.code = code;
    }
  }

  /** 한 번 쓴 기록 — 되돌릴 때 같은 창을 가리킨다. */
  public record Grant(Feature feature, String subject, List<Window> windows, Headers headers) {}

  /**
   * 창 하나 — 종류와 시작 시각, 크기, 한도.
   *
   * <p>{@code kind}({@code minute} · {@code hour} · {@code day})가 표의 열쇠에 들어간다. 시작 시각만으로는 한국 자정에 짧은
   * 창과 하루 창이 같은 순간에 시작해 한 줄을 함께 쓴다 — 00 시대에는 한 번 쓸 때 2 가 늘어 한도가 절반이 됐다(시험이 잡았다).
   */
  public record Window(String kind, OffsetDateTime start, Duration length, int limit) {

    String column(Feature feature) {
      return feature.column + ":" + kind;
    }
  }

  /** 응답 헤더 RateLimit-*(가장 빠듯한 창). */
  public record Headers(int limit, int remaining, long resetSeconds) {}

  private final UsageStore usage;
  private final int chatPerHour;
  private final int chatPerDay;
  private final int navigationPerMinute;
  private final int navigationPerDay;
  private final AtomicLong lastPurge = new AtomicLong();

  public PaidQuota(
      UsageStore usage,
      @Value("${scenetrip.limits.plans.free.guide-chat.per-hour:15}") int chatPerHour,
      @Value("${scenetrip.limits.plans.free.guide-chat.per-day:100}") int chatPerDay,
      @Value("${scenetrip.limits.plans.free.navigation.per-minute:10}") int navigationPerMinute,
      @Value("${scenetrip.limits.plans.free.navigation.per-day:300}") int navigationPerDay) {
    this.usage = usage;
    this.chatPerHour = chatPerHour;
    this.chatPerDay = chatPerDay;
    this.navigationPerMinute = navigationPerMinute;
    this.navigationPerDay = navigationPerDay;
  }

  /**
   * 하나 쓴다. 어느 창이든 넘으면 늘린 것을 되돌리고 {@link LimitExceeded} 를 던진다 — 넘은 시도는 세지 않는다.
   *
   * @return 쓴 기록과 응답 헤더
   */
  public Grant consume(UUID user, Feature feature) {
    return consume(user, feature, Instant.now());
  }

  Grant consume(UUID user, Feature feature, Instant now) {
    maybePurge(now);
    String subject = user.toString();
    List<Window> windows = windows(feature, now);
    int[] counts = new int[windows.size()];
    for (int i = 0; i < windows.size(); i++) {
      Window w = windows.get(i);
      counts[i] = usage.increment(subject, w.column(feature), w.start());
    }
    for (int i = 0; i < windows.size(); i++) {
      Window w = windows.get(i);
      if (counts[i] > w.limit()) {
        for (Window undo : windows) {
          usage.decrement(subject, undo.column(feature), undo.start());
        }
        long retry = secondsUntilEnd(w, now);
        throw new LimitExceeded(feature.code, new Headers(w.limit(), 0, retry), retry);
      }
    }
    // 남은 양이 가장 적은 창을 알린다.
    Headers tightest = null;
    for (int i = 0; i < windows.size(); i++) {
      Window w = windows.get(i);
      Headers h = new Headers(w.limit(), w.limit() - counts[i], secondsUntilEnd(w, now));
      if (tightest == null || h.remaining() < tightest.remaining()) {
        tightest = h;
      }
    }
    return new Grant(feature, subject, windows, tightest);
  }

  /** 제공자가 실패해 사용자가 아무것도 받지 못했다 — 쓴 것을 되돌린다. */
  public void refund(Grant grant) {
    for (Window w : grant.windows()) {
      usage.decrement(grant.subject(), w.column(grant.feature()), w.start());
    }
  }

  private List<Window> windows(Feature feature, Instant now) {
    OffsetDateTime utc = now.atOffset(ZoneOffset.UTC);
    OffsetDateTime dayStart = now.atZone(DAY_ZONE).truncatedTo(ChronoUnit.DAYS).toOffsetDateTime();
    return switch (feature) {
      case GUIDE_CHAT ->
          List.of(
              new Window(
                  "hour", utc.truncatedTo(ChronoUnit.HOURS), Duration.ofHours(1), chatPerHour),
              new Window("day", dayStart, Duration.ofDays(1), chatPerDay));
      case NAVIGATION ->
          List.of(
              new Window(
                  "minute",
                  utc.truncatedTo(ChronoUnit.MINUTES),
                  Duration.ofMinutes(1),
                  navigationPerMinute),
              new Window("day", dayStart, Duration.ofDays(1), navigationPerDay));
    };
  }

  private static long secondsUntilEnd(Window w, Instant now) {
    Instant end = w.start().toInstant().plus(w.length());
    return Math.max(1, Duration.between(now, end).toSeconds());
  }

  /** 한 시간에 한 번쯤 오래된 창을 지운다 — 따로 정리 작업을 두지 않는다. */
  private void maybePurge(Instant now) {
    long last = lastPurge.get();
    if (now.toEpochMilli() - last > Duration.ofHours(1).toMillis()
        && lastPurge.compareAndSet(last, now.toEpochMilli())) {
      usage.purgeOld();
    }
  }

  /** 한도를 넘었다. 컨트롤러가 429 로 바꾼다. */
  public static final class LimitExceeded extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String code;
    private final transient Headers headers;
    private final long retryAfterSeconds;

    LimitExceeded(String code, Headers headers, long retryAfterSeconds) {
      super(code);
      this.code = code;
      this.headers = headers;
      this.retryAfterSeconds = retryAfterSeconds;
    }

    public String code() {
      return code;
    }

    public Headers headers() {
      return headers;
    }

    public long retryAfterSeconds() {
      return retryAfterSeconds;
    }
  }
}
