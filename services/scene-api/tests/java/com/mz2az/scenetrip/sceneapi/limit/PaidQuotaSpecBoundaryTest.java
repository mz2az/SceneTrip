package com.mz2az.scenetrip.sceneapi.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.mz2az.scenetrip.sceneapi.limit.PaidQuota.Feature;
import com.mz2az.scenetrip.sceneapi.limit.PaidQuota.Grant;
import com.mz2az.scenetrip.sceneapi.limit.PaidQuota.LimitExceeded;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 유료 API 한도를 <b>명세에서</b> 거꾸로 짠 경계 시험 — 구현을 보지 않고 기대값을 정했다.
 *
 * <p>근거: 계약 scene-api-v1.yaml 「요청 한도」·{@code TooManyRequests}·헤더 설명,
 * docs/project/plans/rate-limit.md §2·§3, application.yaml 의 {@code scenetrip.limits.plans.free}(챗봇
 * 1 시간 15·하루 100, 길찾기 1 분 10·하루 300).
 *
 * <ul>
 *   <li>짧은 창은 고정 창(그 분·그 시간), 하루는 한국 시간 자정에 다시 찬다.
 *   <li>넘으면 429 {@code GUIDE_LIMIT_REACHED}·{@code NAVIGATION_LIMIT_REACHED}. {@code Retry-After} 는
 *       「몇 초 뒤에 다시 보내도 되는가. 하루 한도면 다음 자정(한국 시간)까지」.
 *   <li>{@code RateLimit-*} 는 「그 요청에 걸린 한도 중 가장 빠듯한 것」, Reset 은 「다시 차기까지 남은 초」.
 *   <li>우리가 거절한 요청은 세지 않는다. 외부 API 가 실패하면 되돌린다.
 * </ul>
 *
 * <p>시계는 {@code consume(user, feature, now)} 에 직접 넣는다. 저장소는 메모리({@link InMemoryUsageStore}).
 */
@DisplayName("PaidQuota — 명세 기준 경계 (챗봇 15/시간·100/일, 길찾기 10/분·300/일)")
class PaidQuotaSpecBoundaryTest {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  /** application.yaml scenetrip.limits.plans.free 의 숫자. */
  private static final int CHAT_PER_HOUR = 15;

  private static final int CHAT_PER_DAY = 100;
  private static final int NAV_PER_MINUTE = 10;
  private static final int NAV_PER_DAY = 300;

  private InMemoryUsageStore store;
  private PaidQuota quota;
  private final UUID alice = UUID.fromString("11111111-1111-4111-8111-111111111111");
  private final UUID bob = UUID.fromString("22222222-2222-4222-8222-222222222222");

  @BeforeEach
  void setUp() {
    store = new InMemoryUsageStore();
    quota = new PaidQuota(store, CHAT_PER_HOUR, CHAT_PER_DAY, NAV_PER_MINUTE, NAV_PER_DAY);
  }

  /** 2026-10-20(화) 한국 시각. 한국은 일광 절약 시간이 없다. */
  private static Instant kst(int day, int hour, int minute, int second) {
    return ZonedDateTime.of(2026, 10, day, hour, minute, second, 0, KST).toInstant();
  }

  private static long secondsBetween(Instant from, Instant to) {
    return Duration.between(from, to).getSeconds();
  }

  private LimitExceeded rejected(UUID user, Feature feature, Instant at) {
    Throwable t = catchThrowable(() -> quota.consume(user, feature, at));
    assertThat(t).as("%s %s 은 거절되어야 한다", feature, at).isInstanceOf(LimitExceeded.class);
    return (LimitExceeded) t;
  }

  /** 허용되어야 하는 한 번. 예외면 그 자리에서 실패. */
  private Grant ok(UUID user, Feature feature, Instant at) {
    try {
      return quota.consume(user, feature, at);
    } catch (LimitExceeded e) {
      throw new AssertionError(
          feature + " " + at + " 은 허용되어야 하는데 " + e.code() + " retryAfter=" + e.retryAfterSeconds(),
          e);
    }
  }

  private static void assertHeaders(PaidQuota.Headers h, int limit, int remaining, long reset) {
    assertThat(h.limit()).as("RateLimit-Limit").isEqualTo(limit);
    assertThat(h.remaining()).as("RateLimit-Remaining").isEqualTo(remaining);
    assertThat(h.resetSeconds()).as("RateLimit-Reset").isEqualTo(reset);
  }

  // ───────────────────────── 짧은 창 경계 ─────────────────────────

  /** 보통 시간 KST 10:00 · 하루 첫 시간 KST 00:00(UTC 15:00) · UTC 자정이 낀 KST 09:00 · 하루 마지막 KST 23:00. */
  private static final List<Instant> HOUR_STARTS =
      List.of(kst(20, 10, 0, 0), kst(20, 0, 0, 0), kst(20, 9, 0, 0), kst(20, 23, 0, 0));

  @Test
  @DisplayName(
      "챗봇 시간 창 — 15 번째까지 허용(헤더 15/남은/정시까지), 16 번째 GUIDE_LIMIT_REACHED·Retry-After 1, 다음 정시에 다시"
          + " (KST 10·00·09·23 시)")
  void chatHourBoundary() {
    for (Instant hourStart : HOUR_STARTS) {
      setUp();
      chatHourBoundaryAt(hourStart);
    }
  }

  private void chatHourBoundaryAt(Instant hourStart) {
    for (int i = 0; i < CHAT_PER_HOUR; i++) {
      Instant at = hourStart.plusSeconds(i * 239L);
      Grant g = ok(alice, Feature.GUIDE_CHAT, at);
      assertHeaders(g.headers(), CHAT_PER_HOUR, CHAT_PER_HOUR - 1 - i, 3600 - i * 239L);
    }

    LimitExceeded e = rejected(alice, Feature.GUIDE_CHAT, hourStart.plusSeconds(3599));
    assertThat(e.code()).isEqualTo("GUIDE_LIMIT_REACHED");
    assertThat(e.retryAfterSeconds()).as("Retry-After — 정시까지 1 초").isEqualTo(1);
    assertHeaders(e.headers(), CHAT_PER_HOUR, 0, 1);

    Grant next = ok(alice, Feature.GUIDE_CHAT, hourStart.plusSeconds(3600));
    assertThat(next.headers().limit()).isEqualTo(CHAT_PER_HOUR);
    assertThat(next.headers().remaining()).isEqualTo(CHAT_PER_HOUR - 1);
  }

  /** 보통 분 KST 10:20 · 하루 첫 분 KST 00:00 · UTC 자정 분 KST 09:00 · 하루 마지막 분 KST 23:59. */
  private static final List<Instant> MINUTE_STARTS =
      List.of(kst(20, 10, 20, 0), kst(20, 0, 0, 0), kst(20, 9, 0, 0), kst(20, 23, 59, 0));

  @Test
  @DisplayName(
      "길찾기 분 창 — 10 번째까지 허용, 11 번째 NAVIGATION_LIMIT_REACHED·Retry-After 1, 다음 분에 다시"
          + " (KST 10:20·00:00·09:00·23:59)")
  void navigationMinuteBoundary() {
    for (Instant minuteStart : MINUTE_STARTS) {
      setUp();
      navigationMinuteBoundaryAt(minuteStart);
    }
  }

  private void navigationMinuteBoundaryAt(Instant minuteStart) {
    for (int i = 0; i < NAV_PER_MINUTE; i++) {
      Grant g = ok(alice, Feature.NAVIGATION, minuteStart.plusSeconds(i * 5L));
      assertHeaders(g.headers(), NAV_PER_MINUTE, NAV_PER_MINUTE - 1 - i, 60 - i * 5L);
    }

    LimitExceeded e = rejected(alice, Feature.NAVIGATION, minuteStart.plusSeconds(59));
    assertThat(e.code()).isEqualTo("NAVIGATION_LIMIT_REACHED");
    assertThat(e.retryAfterSeconds()).isEqualTo(1);
    assertHeaders(e.headers(), NAV_PER_MINUTE, 0, 1);

    ok(alice, Feature.NAVIGATION, minuteStart.plusSeconds(60));
  }

  // ───────────────────────── 하루 창 경계 ─────────────────────────

  @Test
  @DisplayName("챗봇 하루 — 시간마다 20 번 두드려도(넘은 5 번은 세지 않음) 정확히 100 번째까지 허용, 101 번째는 한국 자정까지")
  void chatDayBoundaryWithRejectedAttempts() {
    int granted = 0;
    // KST 08:00 ~ 13:59 — 여섯 시간 × 15 = 90. 시간마다 5 번은 시간 창에 걸린다.
    for (int hour = 8; hour <= 13; hour++) {
      for (int i = 0; i < 20; i++) {
        Instant at = kst(20, hour, i, 0);
        if (i < CHAT_PER_HOUR) {
          ok(alice, Feature.GUIDE_CHAT, at);
          granted++;
        } else {
          LimitExceeded e = rejected(alice, Feature.GUIDE_CHAT, at);
          assertThat(e.code()).isEqualTo("GUIDE_LIMIT_REACHED");
          assertThat(e.retryAfterSeconds())
              .as("시간 창에 걸린 것 — 다음 정시까지")
              .isEqualTo(secondsBetween(at, kst(20, hour + 1, 0, 0)));
        }
      }
    }
    assertThat(granted).isEqualTo(90);

    // KST 14 시 — 첫 번째: 시간 남은 14, 하루 남은 9 → 더 빠듯한 하루 창이 헤더에
    Instant first = kst(20, 14, 0, 0);
    Grant g = ok(alice, Feature.GUIDE_CHAT, first);
    assertHeaders(g.headers(), CHAT_PER_DAY, 9, secondsBetween(first, kst(21, 0, 0, 0)));
    for (int i = 1; i < 10; i++) {
      ok(alice, Feature.GUIDE_CHAT, kst(20, 14, i, 0));
    }
    // 100 번째까지 썼다. 시간 창은 아직 5 남았지만 하루가 닫혔다.
    Instant over = kst(20, 14, 10, 0);
    LimitExceeded e = rejected(alice, Feature.GUIDE_CHAT, over);
    assertThat(e.code()).isEqualTo("GUIDE_LIMIT_REACHED");
    long toMidnight = secondsBetween(over, kst(21, 0, 0, 0)); // 9 시간 50 분
    assertThat(toMidnight).isEqualTo(35_400);
    assertThat(e.retryAfterSeconds()).as("하루 한도 — 다음 한국 자정까지").isEqualTo(toMidnight);
    assertHeaders(e.headers(), CHAT_PER_DAY, 0, toMidnight);

    // 다음 정시에도, 자정 1 초 전에도 닫혀 있다
    assertThat(rejected(alice, Feature.GUIDE_CHAT, kst(20, 15, 0, 0)).retryAfterSeconds())
        .isEqualTo(9 * 3600);
    assertThat(rejected(alice, Feature.GUIDE_CHAT, kst(20, 23, 59, 59)).retryAfterSeconds())
        .isEqualTo(1);
    // 한국 자정에 다시 찬다
    Grant fresh = ok(alice, Feature.GUIDE_CHAT, kst(21, 0, 0, 0));
    assertHeaders(fresh.headers(), CHAT_PER_HOUR, 14, 3600);
  }

  @Test
  @DisplayName("길찾기 하루 — 30 분 × 10 = 300 번째까지 허용, 301 번째는 분 창도 함께 찼어도 Retry-After 는 한국 자정까지")
  void navigationDayBoundaryWhenBothWindowsFillTogether() {
    // KST 20:00 ~ 20:29. 분마다 12 번 — 11·12 번째는 분 창에 걸린다(다음 분까지).
    for (int minute = 0; minute < 29; minute++) {
      for (int s = 0; s < 12; s++) {
        Instant at = kst(20, 20, minute, s);
        if (s < NAV_PER_MINUTE) {
          ok(alice, Feature.NAVIGATION, at);
        } else {
          LimitExceeded e = rejected(alice, Feature.NAVIGATION, at);
          assertThat(e.code()).isEqualTo("NAVIGATION_LIMIT_REACHED");
          assertThat(e.retryAfterSeconds()).isEqualTo(60 - s);
        }
      }
    }
    // 20:29 — 291..300 번째
    for (int s = 0; s < NAV_PER_MINUTE; s++) {
      ok(alice, Feature.NAVIGATION, kst(20, 20, 29, s));
    }

    // 301 번째: 분 창(10/10)과 하루 창(300/300)이 동시에 찼다. 1 분 뒤에 보내도 실패하므로 Retry-After 는 자정까지여야 한다.
    Instant over = kst(20, 20, 29, 10);
    LimitExceeded e = rejected(alice, Feature.NAVIGATION, over);
    long toMidnight = secondsBetween(over, kst(21, 0, 0, 0));
    assertThat(toMidnight).isEqualTo(12_650);
    assertThat(e.code()).isEqualTo("NAVIGATION_LIMIT_REACHED");
    assertThat(e.retryAfterSeconds())
        .as("하루 한도 — 다음 한국 자정까지(분 창의 50 초가 아니다)")
        .isEqualTo(toMidnight);
    assertThat(e.headers().remaining()).isZero();

    // 다음 분에도 닫혀 있다 — 분 창만 보고 열면 안 된다
    LimitExceeded nextMinute = rejected(alice, Feature.NAVIGATION, kst(20, 20, 30, 0));
    assertThat(nextMinute.retryAfterSeconds()).isEqualTo(12_600);
    assertHeaders(nextMinute.headers(), NAV_PER_DAY, 0, 12_600);

    ok(alice, Feature.NAVIGATION, kst(21, 0, 0, 0));
  }

  @Test
  @DisplayName("챗봇 — 시간·하루가 한꺼번에 차면(설정 5/시간·5/일) Retry-After 는 정시가 아니라 한국 자정까지")
  void chatBothWindowsFullRetryAfterIsMidnight() {
    PaidQuota tight = new PaidQuota(store, 5, 5, NAV_PER_MINUTE, NAV_PER_DAY);
    for (int i = 0; i < 5; i++) {
      tight.consume(alice, Feature.GUIDE_CHAT, kst(20, 10, i, 0));
    }
    Instant over = kst(20, 10, 30, 0);
    Throwable t = catchThrowable(() -> tight.consume(alice, Feature.GUIDE_CHAT, over));
    assertThat(t).isInstanceOf(LimitExceeded.class);
    LimitExceeded e = (LimitExceeded) t;
    assertThat(e.code()).isEqualTo("GUIDE_LIMIT_REACHED");
    assertThat(e.retryAfterSeconds()).isEqualTo(secondsBetween(over, kst(21, 0, 0, 0)));
  }

  @Test
  @DisplayName("챗봇 — 하루는 한국 자정에 찬다: UTC 자정(KST 09:00)에는 여전히 막히고 Retry-After 는 15 시간")
  void chatDayDoesNotResetAtUtcMidnight() {
    // KST 02:00 ~ 08:09 (UTC 전날 17:00 ~ 23:09) 에 100 번
    for (int hour = 2; hour <= 7; hour++) {
      for (int i = 0; i < CHAT_PER_HOUR; i++) {
        ok(alice, Feature.GUIDE_CHAT, kst(20, hour, i, 0));
      }
    }
    for (int i = 0; i < 10; i++) {
      ok(alice, Feature.GUIDE_CHAT, kst(20, 8, i, 0));
    }

    Instant utcMidnight = Instant.parse("2026-10-20T00:00:00Z");
    assertThat(utcMidnight).isEqualTo(kst(20, 9, 0, 0));
    LimitExceeded e = rejected(alice, Feature.GUIDE_CHAT, utcMidnight);
    assertThat(e.code()).isEqualTo("GUIDE_LIMIT_REACHED");
    assertThat(e.retryAfterSeconds()).isEqualTo(15 * 3600);
    assertHeaders(e.headers(), CHAT_PER_DAY, 0, 15 * 3600);

    ok(alice, Feature.GUIDE_CHAT, kst(21, 0, 0, 0));
  }

  @Test
  @DisplayName("길찾기 — 하루는 한국 자정에 찬다: 300 번을 UTC 23:30~23:59 에 쓰면 UTC 자정에도 막힌다")
  void navigationDayDoesNotResetAtUtcMidnight() {
    for (int minute = 30; minute < 60; minute++) {
      for (int s = 0; s < NAV_PER_MINUTE; s++) {
        ok(alice, Feature.NAVIGATION, kst(20, 8, minute, s));
      }
    }
    LimitExceeded e = rejected(alice, Feature.NAVIGATION, Instant.parse("2026-10-20T00:00:00Z"));
    assertThat(e.code()).isEqualTo("NAVIGATION_LIMIT_REACHED");
    assertThat(e.retryAfterSeconds()).isEqualTo(15 * 3600);
  }

  @Test
  @DisplayName("한국 자정부터 — 챗봇은 00 시에 15 번, 하루 합계 정확히 100(시간 창과 하루 창이 한 수를 나눠 쓰지 않는다)")
  void chatFullDayFromKstMidnightIsExactly100() {
    int granted = 0;
    for (int hour = 0; hour < 7; hour++) {
      for (int i = 0; i < CHAT_PER_HOUR; i++) {
        Instant at = kst(20, hour, i, 0);
        if (granted < CHAT_PER_DAY) {
          ok(alice, Feature.GUIDE_CHAT, at);
          granted++;
        } else {
          assertThat(rejected(alice, Feature.GUIDE_CHAT, at).retryAfterSeconds())
              .isEqualTo(secondsBetween(at, kst(21, 0, 0, 0)));
        }
      }
    }
    assertThat(granted).isEqualTo(CHAT_PER_DAY);
  }

  @Test
  @DisplayName("한국 자정부터 — 길찾기는 00:00 분에 10 번, 30 분 뒤 정확히 300, 301 번째 거절")
  void navigationFullDayFromKstMidnightIsExactly300() {
    for (int minute = 0; minute < 30; minute++) {
      for (int s = 0; s < NAV_PER_MINUTE; s++) {
        Grant g = ok(alice, Feature.NAVIGATION, kst(20, 0, minute, s));
        if (minute == 0 && s == 0) {
          assertHeaders(g.headers(), NAV_PER_MINUTE, 9, 60);
        }
      }
    }
    LimitExceeded e = rejected(alice, Feature.NAVIGATION, kst(20, 0, 30, 0));
    assertThat(e.retryAfterSeconds())
        .isEqualTo(secondsBetween(kst(20, 0, 30, 0), kst(21, 0, 0, 0)));
  }

  @Test
  @DisplayName("거절된 시도는 하루 몫을 쓰지 않는다 — 한 시간에 500 번 거절당해도 그날 남은 85 번을 다 쓴다")
  void massRejectionsDoNotEatDailyQuota() {
    for (int i = 0; i < CHAT_PER_HOUR; i++) {
      ok(alice, Feature.GUIDE_CHAT, kst(20, 10, 0, i));
    }
    for (int i = 0; i < 500; i++) {
      rejected(alice, Feature.GUIDE_CHAT, kst(20, 10, 1 + i / 60, i % 60));
    }
    int granted = CHAT_PER_HOUR;
    for (int hour = 11; hour <= 17 && granted < CHAT_PER_DAY; hour++) {
      for (int i = 0; i < CHAT_PER_HOUR && granted < CHAT_PER_DAY; i++) {
        ok(alice, Feature.GUIDE_CHAT, kst(20, hour, i, 0));
        granted++;
      }
    }
    assertThat(granted).isEqualTo(CHAT_PER_DAY);
    assertThat(rejected(alice, Feature.GUIDE_CHAT, kst(20, 18, 0, 0)).retryAfterSeconds())
        .isEqualTo(6 * 3600);
  }

  // ───────────────────────── 되돌리기 ─────────────────────────

  @Test
  @DisplayName("되돌리기 — 시간 창이 찬 뒤 하나 되돌리면 정확히 한 번 더, 그다음은 다시 거절")
  void refundGivesBackExactlyOne() {
    Grant last = null;
    for (int i = 0; i < CHAT_PER_HOUR; i++) {
      last = ok(alice, Feature.GUIDE_CHAT, kst(20, 10, i, 0));
    }
    rejected(alice, Feature.GUIDE_CHAT, kst(20, 10, 20, 0));
    quota.refund(last);
    ok(alice, Feature.GUIDE_CHAT, kst(20, 10, 21, 0));
    rejected(alice, Feature.GUIDE_CHAT, kst(20, 10, 22, 0));
  }

  @Test
  @DisplayName("되돌리기 — 하루가 찬 뒤 앞 시간의 것을 되돌리면 하루 몫이 하나 돌아온다(지금 시간 창은 여유)")
  void refundReturnsDailySlot() {
    Grant early = null;
    for (int hour = 8; hour <= 13; hour++) {
      for (int i = 0; i < CHAT_PER_HOUR; i++) {
        Grant g = ok(alice, Feature.GUIDE_CHAT, kst(20, hour, i, 0));
        if (early == null) {
          early = g;
        }
      }
    }
    for (int i = 0; i < 10; i++) {
      ok(alice, Feature.GUIDE_CHAT, kst(20, 14, i, 0));
    }
    rejected(alice, Feature.GUIDE_CHAT, kst(20, 14, 30, 0));
    quota.refund(early);
    ok(alice, Feature.GUIDE_CHAT, kst(20, 14, 31, 0));
    LimitExceeded e = rejected(alice, Feature.GUIDE_CHAT, kst(20, 14, 32, 0));
    assertThat(e.retryAfterSeconds())
        .isEqualTo(secondsBetween(kst(20, 14, 32, 0), kst(21, 0, 0, 0)));
  }

  @Test
  @DisplayName("되돌리기 — 창이 넘어간 뒤 되돌려도 그 요청의 원래 창에서 뺀다(새 시간 창에 공짜 한 번이 생기지 않는다)")
  void lateRefundTargetsOriginalHour() {
    Grant old = ok(alice, Feature.GUIDE_CHAT, kst(20, 10, 59, 59));
    for (int i = 0; i < CHAT_PER_HOUR; i++) {
      ok(alice, Feature.GUIDE_CHAT, kst(20, 11, 0, i));
    }
    quota.refund(old);
    LimitExceeded e = rejected(alice, Feature.GUIDE_CHAT, kst(20, 11, 0, 31));
    assertThat(e.headers().remaining()).isZero();
  }

  @Test
  @DisplayName("되돌리기 — 한국 자정을 넘긴 뒤 어제 것을 되돌려도 오늘 300 은 그대로(301 번째 거절)")
  void lateRefundAcrossKstMidnightTargetsYesterday() {
    Grant yesterday = ok(alice, Feature.NAVIGATION, kst(20, 23, 59, 59));
    for (int minute = 0; minute < 30; minute++) {
      for (int s = 0; s < NAV_PER_MINUTE; s++) {
        ok(alice, Feature.NAVIGATION, kst(21, 0, minute, s));
      }
    }
    quota.refund(yesterday);
    LimitExceeded e = rejected(alice, Feature.NAVIGATION, kst(21, 0, 30, 1));
    assertThat(e.code()).isEqualTo("NAVIGATION_LIMIT_REACHED");
  }

  @Test
  @DisplayName("되돌리기 — 길찾기 분 창이 찬 뒤 하나 되돌리면 같은 분에 정확히 한 번 더")
  void navigationRefundInSameMinute() {
    Grant g = null;
    for (int s = 0; s < NAV_PER_MINUTE; s++) {
      g = ok(alice, Feature.NAVIGATION, kst(20, 10, 0, s));
    }
    quota.refund(g);
    ok(alice, Feature.NAVIGATION, kst(20, 10, 0, 30));
    rejected(alice, Feature.NAVIGATION, kst(20, 10, 0, 31));
  }

  // ───────────────────────── 분리 ─────────────────────────

  @Test
  @DisplayName("사용자 분리 — alice 가 시간·분 창을 다 써도 bob 은 같은 순간 처음부터(헤더 남은 14·9)")
  void perUserIsolation() {
    Instant t = kst(20, 10, 0, 0);
    for (int i = 0; i < CHAT_PER_HOUR; i++) {
      ok(alice, Feature.GUIDE_CHAT, t.plusSeconds(i));
    }
    for (int i = 0; i < NAV_PER_MINUTE; i++) {
      ok(alice, Feature.NAVIGATION, t.plusSeconds(i));
    }
    rejected(alice, Feature.GUIDE_CHAT, t.plusSeconds(30));
    rejected(alice, Feature.NAVIGATION, t.plusSeconds(30));

    assertThat(ok(bob, Feature.GUIDE_CHAT, t.plusSeconds(30)).headers().remaining()).isEqualTo(14);
    assertThat(ok(bob, Feature.NAVIGATION, t.plusSeconds(30)).headers().remaining()).isEqualTo(9);
  }

  @Test
  @DisplayName("기능 분리 — 챗봇 하루 100 을 다 써도 같은 사람의 길찾기는 10 번 그대로, 반대도 같다")
  void perFeatureIsolation() {
    for (int hour = 0; hour < 7; hour++) {
      for (int i = 0; i < CHAT_PER_HOUR && hour * CHAT_PER_HOUR + i < CHAT_PER_DAY; i++) {
        ok(alice, Feature.GUIDE_CHAT, kst(20, hour, i, 0));
      }
    }
    rejected(alice, Feature.GUIDE_CHAT, kst(20, 10, 0, 0));
    for (int s = 0; s < NAV_PER_MINUTE; s++) {
      Grant g = ok(alice, Feature.NAVIGATION, kst(20, 10, 0, s));
      assertThat(g.headers().limit()).isEqualTo(NAV_PER_MINUTE);
    }
    rejected(alice, Feature.NAVIGATION, kst(20, 10, 0, 30));
    // alice 의 두 기능이 다 막혀도 bob 의 챗봇은 영향 없음
    ok(bob, Feature.GUIDE_CHAT, kst(20, 10, 0, 31));
  }

  // ───────────────────────── Retry-After 의 뜻 ─────────────────────────

  @Test
  @DisplayName(
      "Retry-After 는 「그 초 뒤에 보내면 된다」 — 1 이상, 그만큼 뒤에 보내면 허용, 1 초 덜 기다리면 아직 거절"
          + " (챗봇 정시 0.6 초 전·길찾기 다음 분 0.3 초 전·분 시작 10.25 초 뒤)")
  void retryAfterIsHonest() {
    Instant start = kst(20, 10, 0, 0);
    setUp();
    retryAfterIsHonestAt(Feature.GUIDE_CHAT, start, Duration.ofMillis(3_599_400));
    setUp();
    retryAfterIsHonestAt(Feature.NAVIGATION, start, Duration.ofMillis(59_700));
    setUp();
    retryAfterIsHonestAt(Feature.NAVIGATION, start, Duration.ofMillis(10_250));
  }

  private void retryAfterIsHonestAt(Feature feature, Instant windowStart, Duration rejectAt) {
    int limit = feature == Feature.GUIDE_CHAT ? CHAT_PER_HOUR : NAV_PER_MINUTE;
    for (int i = 0; i < limit; i++) {
      ok(alice, feature, windowStart.plusMillis(i));
    }
    Instant at = windowStart.plus(rejectAt);
    LimitExceeded e = rejected(alice, feature, at);
    long r = e.retryAfterSeconds();
    assertThat(r).as("Retry-After 0 이면 앱이 곧바로 다시 보내 또 거절된다").isGreaterThanOrEqualTo(1);
    if (r > 1) {
      rejected(alice, feature, at.plusSeconds(r - 1));
    }
    ok(alice, feature, at.plusSeconds(r));
  }

  @Test
  @DisplayName("Retry-After — 하루 한도에서 자정 0.1 초 전이면 1, 1 초 뒤(다음 날)에 허용")
  void retryAfterAtLastSubSecondOfDay() {
    for (int minute = 0; minute < 30; minute++) {
      for (int s = 0; s < NAV_PER_MINUTE; s++) {
        ok(alice, Feature.NAVIGATION, kst(20, 23, minute, s));
      }
    }
    Instant at = kst(20, 23, 59, 59).plusMillis(900);
    LimitExceeded e = rejected(alice, Feature.NAVIGATION, at);
    assertThat(e.retryAfterSeconds()).isEqualTo(1);
    ok(alice, Feature.NAVIGATION, at.plusSeconds(1));
  }
}
