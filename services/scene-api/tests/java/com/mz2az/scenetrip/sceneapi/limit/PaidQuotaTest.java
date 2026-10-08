package com.mz2az.scenetrip.sceneapi.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mz2az.scenetrip.sceneapi.limit.PaidQuota.Feature;
import com.mz2az.scenetrip.sceneapi.limit.PaidQuota.LimitExceeded;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 유료 한도(계약 「요청 한도」) — 창의 경계(정시·분·한국 시간 자정), 넘은 시도는 세지 않음, 되돌리기, 가장 빠듯한 창의 헤더, 동시 요청.
 *
 * <p>시각은 패키지 전용 {@code consume(user, feature, now)} 로 정한다. 저장소는 메모리 가짜 — SQL 은 통합 레인이 본다.
 */
@DisplayName("PaidQuota — 유료 API 한도")
class PaidQuotaTest {

  private static final UUID USER = UUID.fromString("9d1e4b52-6c07-4a8f-b3d1-2e6f80c4a915");
  private static final UUID OTHER = UUID.fromString("1b2c3d4e-0000-4000-8000-000000000002");

  /** 한국 시간 2026-10-08 14:20:30. */
  private static final Instant NOW = Instant.parse("2026-10-08T05:20:30Z");

  /** 그날(한국 시간 10 월 8 일)이 시작한 순간 = UTC 10 월 7 일 15 시. */
  private static final OffsetDateTime KST_DAY_START = OffsetDateTime.parse("2026-10-07T15:00Z");

  private static final OffsetDateTime HOUR_START = OffsetDateTime.parse("2026-10-08T05:00Z");
  private static final OffsetDateTime MINUTE_START = OffsetDateTime.parse("2026-10-08T05:20Z");

  private final InMemoryUsageStore usage = new InMemoryUsageStore();

  private PaidQuota quota(int chatHour, int chatDay, int navMinute, int navDay) {
    return new PaidQuota(usage, chatHour, chatDay, navMinute, navDay);
  }

  private PaidQuota defaults() {
    return quota(15, 100, 10, 300);
  }

  /** 표의 feature 값 — 기능과 창 종류(V24 의 열쇠에 창 종류가 들어가 짧은 창과 하루 창이 한 줄을 나누지 않는다). */
  private static final String CHAT_HOUR = "guide-chat:hour";

  private static final String CHAT_DAY = "guide-chat:day";
  private static final String NAV_MINUTE = "navigation:minute";
  private static final String NAV_DAY = "navigation:day";

  private String chat() {
    return "guide-chat";
  }

  // ───────────── 가이드 챗봇 ─────────────

  @Test
  @DisplayName("챗봇 — 정시 창과 한국 시간 하루 창 둘 다 하나씩 센다")
  void chatCountsHourAndKstDay() {
    defaults().consume(USER, Feature.GUIDE_CHAT, NOW);

    assertThat(usage.count(USER.toString(), CHAT_HOUR, HOUR_START)).isEqualTo(1);
    assertThat(usage.count(USER.toString(), CHAT_DAY, KST_DAY_START)).isEqualTo(1);
    assertThat(usage.total(USER.toString(), chat())).isEqualTo(2);
  }

  @Test
  @DisplayName("챗봇 — 응답 헤더는 가장 빠듯한 창(기본값이면 시간 창 15, 남은 14, 정시까지 2370 초)")
  void chatHeadersShowTightestWindow() {
    PaidQuota.Grant grant = defaults().consume(USER, Feature.GUIDE_CHAT, NOW);

    assertThat(grant.headers()).isEqualTo(new PaidQuota.Headers(15, 14, 2370));
  }

  @Test
  @DisplayName("챗봇 — 시간당 16 번째는 GUIDE_LIMIT_REACHED, Retry-After 는 정시까지, 넘은 시도는 세지 않는다")
  void chatHourLimit() {
    PaidQuota quota = defaults();
    for (int i = 0; i < 15; i++) {
      quota.consume(USER, Feature.GUIDE_CHAT, NOW.plusSeconds(i));
    }

    assertThatThrownBy(() -> quota.consume(USER, Feature.GUIDE_CHAT, NOW))
        .isInstanceOfSatisfying(
            LimitExceeded.class,
            e -> {
              assertThat(e.code()).isEqualTo("GUIDE_LIMIT_REACHED");
              assertThat(e.retryAfterSeconds()).isEqualTo(2370);
              assertThat(e.headers()).isEqualTo(new PaidQuota.Headers(15, 0, 2370));
            });
    // 넘은 시도는 어느 창에도 남지 않는다
    assertThat(usage.count(USER.toString(), CHAT_HOUR, HOUR_START)).isEqualTo(15);
    assertThat(usage.count(USER.toString(), CHAT_DAY, KST_DAY_START)).isEqualTo(15);
  }

  @Test
  @DisplayName("챗봇 — 거절이 이어져도 수가 늘지 않아 다음 정시에 바로 쓸 수 있다")
  void rejectedAttemptsDoNotAccumulate() {
    PaidQuota quota = quota(2, 100, 10, 300);
    quota.consume(USER, Feature.GUIDE_CHAT, NOW);
    quota.consume(USER, Feature.GUIDE_CHAT, NOW);
    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> quota.consume(USER, Feature.GUIDE_CHAT, NOW))
          .isInstanceOf(LimitExceeded.class);
    }

    assertThat(usage.count(USER.toString(), CHAT_DAY, KST_DAY_START)).isEqualTo(2);
    PaidQuota.Grant next =
        quota.consume(USER, Feature.GUIDE_CHAT, Instant.parse("2026-10-08T06:00:00Z"));
    assertThat(next.headers().remaining()).isEqualTo(1);
  }

  @Test
  @DisplayName("챗봇 — 시간 창은 시계의 정시다(05:59:59 와 06:00:00 은 다른 창)")
  void hourWindowIsClockHour() {
    PaidQuota quota = quota(1, 100, 10, 300);
    quota.consume(USER, Feature.GUIDE_CHAT, Instant.parse("2026-10-08T05:59:59Z"));

    assertThat(
            quota
                .consume(USER, Feature.GUIDE_CHAT, Instant.parse("2026-10-08T06:00:00Z"))
                .headers()
                .remaining())
        .isEqualTo(0);
  }

  @Test
  @DisplayName("챗봇 — 하루 한도를 넘으면 Retry-After 는 한국 시간 자정까지")
  void chatDayLimitResetsAtKstMidnight() {
    PaidQuota quota = quota(1000, 3, 10, 300);
    quota.consume(USER, Feature.GUIDE_CHAT, Instant.parse("2026-10-08T00:10:00Z"));
    quota.consume(USER, Feature.GUIDE_CHAT, Instant.parse("2026-10-08T02:10:00Z"));
    quota.consume(USER, Feature.GUIDE_CHAT, Instant.parse("2026-10-08T04:10:00Z"));

    // 05:20:30Z → 다음 한국 자정(15:00Z)까지 9 시간 39 분 30 초
    assertThatThrownBy(() -> quota.consume(USER, Feature.GUIDE_CHAT, NOW))
        .isInstanceOfSatisfying(
            LimitExceeded.class,
            e -> {
              assertThat(e.code()).isEqualTo("GUIDE_LIMIT_REACHED");
              assertThat(e.retryAfterSeconds()).isEqualTo(34_770);
              assertThat(e.headers().limit()).isEqualTo(3);
              assertThat(e.headers().remaining()).isZero();
            });
    // 하루 창에서 넘었어도 시간 창에 남은 흔적이 없다
    assertThat(usage.count(USER.toString(), CHAT_HOUR, HOUR_START)).isZero();
  }

  @Test
  @DisplayName("챗봇 — 한국 시간 자정(UTC 15:00)에 하루가 다시 찬다. UTC 자정이 아니다")
  void dayBoundaryIsKstNotUtc() {
    PaidQuota quota = quota(1000, 1, 10, 300);
    quota.consume(USER, Feature.GUIDE_CHAT, Instant.parse("2026-10-08T14:59:00Z"));

    // 한국 23:59:59.5 — 자정까지 0.5 초라도 Retry-After 는 1 이상
    assertThatThrownBy(
            () ->
                quota.consume(USER, Feature.GUIDE_CHAT, Instant.parse("2026-10-08T14:59:59.500Z")))
        .isInstanceOfSatisfying(
            LimitExceeded.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(1));
    // 한국 자정이 지나면 새 날이다. 01 시(16:00Z)로 보는 이유: 첫 시간(15:xxZ)은 시간 창과 하루 창의 시작이 같아
    // 따로 본다(firstKstHourCountsOnce).
    quota.consume(USER, Feature.GUIDE_CHAT, Instant.parse("2026-10-08T16:00:00Z"));

    // UTC 자정은 경계가 아니다: 한국 08:59 와 09:01 은 같은 날
    PaidQuota other = quota(1000, 1, 10, 300);
    other.consume(OTHER, Feature.GUIDE_CHAT, Instant.parse("2026-10-07T23:59:00Z"));
    assertThatThrownBy(
            () -> other.consume(OTHER, Feature.GUIDE_CHAT, Instant.parse("2026-10-08T00:01:00Z")))
        .isInstanceOf(LimitExceeded.class);
  }

  @Test
  @DisplayName("헤더는 남은 양이 가장 적은 창 — 하루가 더 빠듯하면 하루 창의 한도·남은 양·재설정")
  void tightestWindowCanBeTheDay() {
    PaidQuota quota = quota(15, 5, 10, 300);

    PaidQuota.Grant grant = quota.consume(USER, Feature.GUIDE_CHAT, NOW);

    assertThat(grant.headers()).isEqualTo(new PaidQuota.Headers(5, 4, 34_770));
  }

  // ───────────── 길찾기 ─────────────

  @Test
  @DisplayName("길찾기 — 분당 11 번째는 NAVIGATION_LIMIT_REACHED, Retry-After 는 다음 분까지(30 초)")
  void navigationMinuteLimit() {
    PaidQuota quota = defaults();
    for (int i = 0; i < 10; i++) {
      PaidQuota.Grant g = quota.consume(USER, Feature.NAVIGATION, NOW);
      assertThat(g.headers()).isEqualTo(new PaidQuota.Headers(10, 9 - i, 30));
    }

    assertThatThrownBy(() -> quota.consume(USER, Feature.NAVIGATION, NOW))
        .isInstanceOfSatisfying(
            LimitExceeded.class,
            e -> {
              assertThat(e.code()).isEqualTo("NAVIGATION_LIMIT_REACHED");
              assertThat(e.retryAfterSeconds()).isEqualTo(30);
              assertThat(e.headers()).isEqualTo(new PaidQuota.Headers(10, 0, 30));
            });
    assertThat(usage.count(USER.toString(), NAV_MINUTE, MINUTE_START)).isEqualTo(10);
    assertThat(usage.count(USER.toString(), NAV_DAY, KST_DAY_START)).isEqualTo(10);
    // 다음 분(시계의 분)에는 다시 쓴다
    quota.consume(USER, Feature.NAVIGATION, Instant.parse("2026-10-08T05:21:00Z"));
  }

  @Test
  @DisplayName("길찾기 — 하루 한도(기본 300)를 넘으면 NAVIGATION_LIMIT_REACHED, 자정까지")
  void navigationDayLimit() {
    PaidQuota quota = defaults();
    // 한국 00:01 부터 — 첫 1 분은 분 창과 하루 창의 시작이 같아 따로 본다(firstKstMinuteCountsOnce).
    Instant t = Instant.parse("2026-10-07T15:01:00Z");
    for (int i = 0; i < 300; i++) {
      // 분마다 10 번 아래로 흩어 하루 창만 찬다
      quota.consume(USER, Feature.NAVIGATION, t.plusSeconds(60L * (i / 5)));
    }

    assertThatThrownBy(() -> quota.consume(USER, Feature.NAVIGATION, NOW))
        .isInstanceOfSatisfying(
            LimitExceeded.class,
            e -> {
              assertThat(e.code()).isEqualTo("NAVIGATION_LIMIT_REACHED");
              assertThat(e.headers().limit()).isEqualTo(300);
              assertThat(e.retryAfterSeconds()).isEqualTo(34_770);
            });
  }

  // ───────────── 되돌리기·분리 ─────────────

  @Test
  @DisplayName("되돌리기 — 쓴 두 창이 모두 하나씩 줄어 다시 쓸 수 있다")
  void refundRestoresBothWindows() {
    PaidQuota quota = quota(1, 1, 10, 300);
    PaidQuota.Grant grant = quota.consume(USER, Feature.GUIDE_CHAT, NOW);

    quota.refund(grant);

    assertThat(usage.total(USER.toString(), chat())).isZero();
    quota.consume(USER, Feature.GUIDE_CHAT, NOW);
  }

  @Test
  @DisplayName("사용자·기능마다 따로 센다")
  void separatePerUserAndFeature() {
    PaidQuota quota = quota(1, 100, 1, 300);
    quota.consume(USER, Feature.GUIDE_CHAT, NOW);

    quota.consume(OTHER, Feature.GUIDE_CHAT, NOW);
    quota.consume(USER, Feature.NAVIGATION, NOW);
    assertThatThrownBy(() -> quota.consume(USER, Feature.GUIDE_CHAT, NOW))
        .isInstanceOf(LimitExceeded.class);
  }

  @Test
  @DisplayName("동시 요청 50 개가 한도 10 을 넘기지 않는다 — 정확히 10 개만 통과")
  void concurrentRequestsNeverExceedLimit() throws Exception {
    PaidQuota quota = quota(10, 100, 10, 300);
    ExecutorService pool = Executors.newFixedThreadPool(16);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Boolean>> results = new ArrayList<>();
    try {
      for (int i = 0; i < 50; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    quota.consume(USER, Feature.GUIDE_CHAT, NOW);
                    return true;
                  } catch (LimitExceeded e) {
                    return false;
                  }
                }));
      }
      start.countDown();
      int granted = 0;
      for (Future<Boolean> f : results) {
        if (f.get(10, TimeUnit.SECONDS)) {
          granted++;
        }
      }
      assertThat(granted).isEqualTo(10);
      assertThat(usage.count(USER.toString(), CHAT_HOUR, HOUR_START)).isEqualTo(10);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("오래된 창 정리는 한 시간에 한 번쯤만 부른다")
  void purgeAtMostHourly() {
    PaidQuota quota = defaults();
    quota.consume(USER, Feature.GUIDE_CHAT, NOW);
    quota.consume(USER, Feature.GUIDE_CHAT, NOW.plusSeconds(600));
    assertThat(usage.purges()).isEqualTo(1);

    quota.consume(USER, Feature.GUIDE_CHAT, NOW.plusSeconds(3601));
    assertThat(usage.purges()).isEqualTo(2);
  }

  // ───────────── 짧은 창과 하루 창의 시작이 같은 순간 ─────────────
  //
  // 한국 자정(UTC 15:00)에는 챗봇의 시간 창과 길찾기의 분 창이 하루 창과 같은 시각에 시작한다. 창 하나에 줄 하나라는 약속(V24 의 기본키
  // (subject, feature, window_start))대로라면 그래도 두 창은 따로 세어져야 한다 — 한 번 쓰면 시간 창 1, 하루 창 1.

  @Test
  @DisplayName("한국 자정 직후 첫 시간 — 챗봇 두 번이면 시간 창 남은 13(한 번에 하나씩만 센다)")
  void firstKstHourCountsOnce() {
    PaidQuota quota = defaults();
    Instant t = Instant.parse("2026-10-08T15:10:00Z");
    quota.consume(USER, Feature.GUIDE_CHAT, t);

    PaidQuota.Grant second = quota.consume(USER, Feature.GUIDE_CHAT, t.plusSeconds(1));

    assertThat(second.headers()).isEqualTo(new PaidQuota.Headers(15, 13, 2999));
    // 시간 창과 하루 창이 같은 순간(15:00Z)에 시작해도 줄은 둘이고 각각 2
    OffsetDateTime midnight = OffsetDateTime.parse("2026-10-08T15:00Z");
    assertThat(usage.count(USER.toString(), CHAT_HOUR, midnight)).isEqualTo(2);
    assertThat(usage.count(USER.toString(), CHAT_DAY, midnight)).isEqualTo(2);
    assertThat(usage.total(USER.toString(), chat())).isEqualTo(4);
  }

  @Test
  @DisplayName("한국 자정 직후 첫 시간 — 시간당 15 번을 다 쓸 수 있다")
  void firstKstHourAllowsFullHourlyLimit() {
    PaidQuota quota = defaults();
    Instant t = Instant.parse("2026-10-08T15:10:00Z");
    for (int i = 0; i < 15; i++) {
      quota.consume(USER, Feature.GUIDE_CHAT, t.plusSeconds(i));
    }
    // 16 번째는 시간 창에서 막힌다 — 넘은 시도는 세지 않는다
    assertThatThrownBy(() -> quota.consume(USER, Feature.GUIDE_CHAT, t.plusSeconds(20)))
        .isInstanceOfSatisfying(
            LimitExceeded.class, e -> assertThat(e.headers().limit()).isEqualTo(15));
    assertThat(usage.count(USER.toString(), CHAT_HOUR, OffsetDateTime.parse("2026-10-08T15:00Z")))
        .isEqualTo(15);
  }

  @Test
  @DisplayName("한국 자정 직후 첫 1 분 — 길찾기 10 번을 다 쓸 수 있고, 두 번 뒤 남은 8")
  void firstKstMinuteAllowsFullLimit() {
    PaidQuota quota = defaults();
    Instant t = Instant.parse("2026-10-08T15:00:10Z");
    quota.consume(USER, Feature.NAVIGATION, t);
    assertThat(quota.consume(USER, Feature.NAVIGATION, t).headers().remaining()).isEqualTo(8);
    for (int i = 2; i < 10; i++) {
      quota.consume(USER, Feature.NAVIGATION, t);
    }
    OffsetDateTime midnight = OffsetDateTime.parse("2026-10-08T15:00Z");
    assertThat(usage.count(USER.toString(), NAV_MINUTE, midnight)).isEqualTo(10);
    assertThat(usage.count(USER.toString(), NAV_DAY, midnight)).isEqualTo(10);
    // 11 번째는 분 창에서 막힌다
    assertThatThrownBy(() -> quota.consume(USER, Feature.NAVIGATION, t))
        .isInstanceOfSatisfying(
            LimitExceeded.class, e -> assertThat(e.code()).isEqualTo("NAVIGATION_LIMIT_REACHED"));
  }
}
