package com.mz2az.scenetrip.sceneapi.limit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.limit.PaidQuota.Feature;
import com.mz2az.scenetrip.sceneapi.limit.PaidQuota.Grant;
import com.mz2az.scenetrip.sceneapi.limit.PaidQuota.LimitExceeded;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 유료 API 한도를 실제 PostgreSQL 위에서 <b>서버 두 대</b>({@link PaidQuota} 두 개가 같은 {@code usage_counter} 를 쓴다)로
 * — docs/project/plans/rate-limit.md §3 「서버가 여러 대여도 정확해야 하고」.
 *
 * <p>기대값은 명세(계약 「요청 한도」, 계획 §2·§3, application.yaml 의 숫자)에서 정했다. 표의 열 이름·창 이름에는 기대지 않는다 — 계획 §3 의
 * 「(누가, 무엇을, 어느 창) 하나에 한 줄」 만 쓴다: 한 사람의 줄들의 수를 그대로 본다.
 *
 * <p>시각은 오늘·내일(한국 날짜)로 잡는다. 오래된 줄 정리(이틀)가 시험 도중 줄을 지우지 않게.
 */
@DisplayName("PaidQuota — 서버 두 대가 한 DB 를 함께 쓸 때 (실제 DB)")
class PaidQuotaTwoPodsIntegrationTest {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private static final LocalDate TODAY = LocalDate.now(KST);

  private static JdbcClient jdbc;
  private static PaidQuota podA;
  private static PaidQuota podB;

  private final List<String> subjects = new ArrayList<>();

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    // 서버 두 대 — 각자의 PaidQuota·UsageStore·JdbcClient, 같은 DB. 숫자는 application.yaml 의 free 요금제.
    podA = new PaidQuota(new UsageStore(IntegrationDatabase.jdbcClient()), 15, 100, 10, 300);
    podB = new PaidQuota(new UsageStore(IntegrationDatabase.jdbcClient()), 15, 100, 10, 300);
  }

  @AfterEach
  void cleanUp() {
    for (String s : subjects) {
      jdbc.sql("DELETE FROM usage_counter WHERE subject = :s").param("s", s).update();
    }
  }

  private UUID user() {
    UUID u = UUID.randomUUID();
    // 계획 §3 「누구로 세나: 계정 id」 — 정리할 때 쓸 subject 는 첫 허가에서 읽는다. 혹시 몰라 UUID 문자열도 지운다.
    subjects.add(u.toString());
    return u;
  }

  private static Instant kst(LocalDate day, int hour, int minute, int second) {
    return day.atTime(hour, minute, second).atZone(KST).toInstant();
  }

  /** 한 사람의 모든 줄의 수(창 이름에 기대지 않는다). */
  private List<Integer> counts(String subject) {
    return jdbc.sql("SELECT count FROM usage_counter WHERE subject = :s ORDER BY count")
        .param("s", subject)
        .query(Integer.class)
        .list();
  }

  private Grant ok(PaidQuota pod, UUID user, Feature f, Instant at) {
    try {
      Grant g = pod.consume(user, f, at);
      if (!subjects.contains(g.subject())) {
        subjects.add(g.subject());
      }
      return g;
    } catch (LimitExceeded e) {
      throw new AssertionError(f + " " + at + " 은 허용되어야 하는데 " + e.code(), e);
    }
  }

  private static LimitExceeded rejected(PaidQuota pod, UUID user, Feature f, Instant at) {
    Throwable t = catchThrowable(() -> pod.consume(user, f, at));
    assertThat(t).as("%s %s 은 거절되어야 한다", f, at).isInstanceOf(LimitExceeded.class);
    return (LimitExceeded) t;
  }

  /** n 개를 한꺼번에 출발시켜(같은 순간), 결과를 모은다. 짝수 번째는 A, 홀수 번째는 B 서버. */
  private static List<Object> race(int n, java.util.function.Function<PaidQuota, Object> work)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(n);
    CountDownLatch go = new CountDownLatch(1);
    List<Future<Object>> futures = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      PaidQuota pod = i % 2 == 0 ? podA : podB;
      Callable<Object> c =
          () -> {
            go.await();
            return work.apply(pod);
          };
      futures.add(pool.submit(c));
    }
    go.countDown();
    List<Object> out = new ArrayList<>();
    for (Future<Object> f : futures) {
      out.add(f.get(60, TimeUnit.SECONDS));
    }
    pool.shutdown();
    return out;
  }

  private static Object tryConsume(PaidQuota pod, UUID user, Feature f, Instant at) {
    try {
      return pod.consume(user, f, at);
    } catch (LimitExceeded e) {
      return e;
    }
  }

  @Test
  @DisplayName(
      "챗봇 — 두 서버로 동시 40 개: 정확히 15 개만 통과, 25 개는 GUIDE_LIMIT_REACHED, 표의 두 창 모두 15, 다른 사람은 그대로")
  void twoPodsHourLimitIsExact() throws Exception {
    UUID u = user();
    Instant t = kst(TODAY, 10, 20, 0);

    List<Object> results = race(40, pod -> tryConsume(pod, u, Feature.GUIDE_CHAT, t));

    long grants = results.stream().filter(r -> r instanceof Grant).count();
    List<LimitExceeded> refusals =
        results.stream()
            .filter(r -> r instanceof LimitExceeded)
            .map(r -> (LimitExceeded) r)
            .toList();
    assertThat(grants).isEqualTo(15);
    assertThat(refusals).hasSize(25);
    assertThat(refusals).allSatisfy(e -> assertThat(e.code()).isEqualTo("GUIDE_LIMIT_REACHED"));
    // 시간 창 한 줄 + 하루 창 한 줄, 넘은 25 번은 어느 줄에도 남지 않는다
    String subject =
        ((Grant) results.stream().filter(r -> r instanceof Grant).findFirst().get()).subject();
    subjects.add(subject);
    assertThat(counts(subject)).containsExactly(15, 15);

    // 다른 사람은 같은 순간 처음부터
    UUID other = user();
    assertThat(ok(podB, other, Feature.GUIDE_CHAT, t).headers().remaining()).isEqualTo(14);

    // 다음 정시 — 하루 창이 넘은 시도로 부풀지 않았다면 다시 15 번
    Instant next = kst(TODAY, 11, 0, 0);
    for (int i = 0; i < 15; i++) {
      ok(i % 2 == 0 ? podA : podB, u, Feature.GUIDE_CHAT, next.plusSeconds(i));
    }
    rejected(podA, u, Feature.GUIDE_CHAT, next.plusSeconds(30));
    assertThat(counts(subject)).containsExactly(15, 15, 30);
  }

  @Test
  @DisplayName("길찾기 — 하루 298 번 쓴 뒤 새 분에 두 서버로 동시 30 개: 정확히 2 개만, 나머지는 Retry-After 한국 자정까지")
  void twoPodsDayLimitIsExact() throws Exception {
    UUID u = user();
    // KST 10:00 ~ 10:28 에 10 번씩(290), 10:29 에 8 번 → 298
    for (int minute = 0; minute < 29; minute++) {
      for (int s = 0; s < 10; s++) {
        ok(s % 2 == 0 ? podA : podB, u, Feature.NAVIGATION, kst(TODAY, 10, minute, s));
      }
    }
    for (int s = 0; s < 8; s++) {
      ok(podA, u, Feature.NAVIGATION, kst(TODAY, 10, 29, s));
    }

    Instant t = kst(TODAY, 10, 30, 0);
    List<Object> results = race(30, pod -> tryConsume(pod, u, Feature.NAVIGATION, t));

    assertThat(results.stream().filter(r -> r instanceof Grant).count()).isEqualTo(2);
    List<LimitExceeded> refusals =
        results.stream()
            .filter(r -> r instanceof LimitExceeded)
            .map(r -> (LimitExceeded) r)
            .toList();
    assertThat(refusals).hasSize(28);
    assertThat(refusals)
        .allSatisfy(e -> assertThat(e.code()).isEqualTo("NAVIGATION_LIMIT_REACHED"));

    // 표는 정확해야 한다 — Retry-After 확인보다 먼저 본다
    String subject = subjects.get(subjects.size() - 1);
    List<Integer> rows = counts(subject);
    assertThat(rows).contains(300).contains(8).contains(2);
    assertThat(rows.stream().mapToInt(Integer::intValue).sum()).isEqualTo(600);

    // 계약 RetryAfter: 「하루 한도면 다음 자정(한국 시간)까지」. 10:30 의 분 창은 2/10 뿐이므로 거절 사유는 하루뿐이다.
    long toMidnight = Duration.between(t, kst(TODAY.plusDays(1), 0, 0, 0)).getSeconds();
    assertThat(refusals.stream().map(LimitExceeded::retryAfterSeconds).toList())
        .as("28 개 거절의 Retry-After — 전부 %d 여야 한다", toMidnight)
        .containsOnly(toMidnight);
  }

  // 격리: 담당 jkh1447, 기한 유료 요금제 도입 전. 고치면 @Disabled 를 지운다.
  @Disabled("MZ2AZ-487 — 같은 사용자의 동시 요청이 잠깐 부풀린 짧은 창 수 때문에 남은 몫도 거절된다")
  @Test
  @DisplayName(
      "되풀이 경합 50 판 — 하루 남은 1 번(설정 분 10·하루 3, 2 번 쓴 뒤)에 두 서버로 동시 20 개: 판마다 정확히 1 개 통과(0 개도 2 개도"
          + " 아니다)")
  void repeatedRacesGrantExactlyTheRemainder() throws Exception {
    PaidQuota a = new PaidQuota(new UsageStore(IntegrationDatabase.jdbcClient()), 15, 100, 10, 3);
    PaidQuota b = new PaidQuota(new UsageStore(IntegrationDatabase.jdbcClient()), 15, 100, 10, 3);
    List<Long> grantsPerRound = new ArrayList<>();
    for (int round = 0; round < 50; round++) {
      UUID u = user();
      ok(a, u, Feature.NAVIGATION, kst(TODAY, 12 + round / 60, round % 60, 0));
      ok(b, u, Feature.NAVIGATION, kst(TODAY, 12 + round / 60, round % 60, 1));
      Instant t = kst(TODAY, 12 + round / 60, round % 60, 2);
      ExecutorService pool = Executors.newFixedThreadPool(20);
      CountDownLatch go = new CountDownLatch(1);
      List<Future<Object>> fs = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        PaidQuota pod = i % 2 == 0 ? a : b;
        fs.add(
            pool.submit(
                () -> {
                  go.await();
                  return tryConsume(pod, u, Feature.NAVIGATION, t);
                }));
      }
      go.countDown();
      long grants = 0;
      for (Future<Object> f : fs) {
        if (f.get(60, TimeUnit.SECONDS) instanceof Grant) {
          grants++;
        }
      }
      pool.shutdown();
      grantsPerRound.add(grants);
    }
    assertThat(grantsPerRound).as("판마다 통과 수").containsOnly(1L);
  }

  @Test
  @DisplayName("동시에 쓰고 되돌리기 — 절반이 제공자 실패로 되돌려도 표는 남긴 수와 같고, 남은 몫을 정확히 더 쓸 수 있다")
  void concurrentConsumeAndRefundStayExact() throws Exception {
    UUID u = user();
    Instant t = kst(TODAY, 14, 10, 0);
    java.util.concurrent.atomic.AtomicInteger idx = new java.util.concurrent.atomic.AtomicInteger();

    List<Object> results =
        race(
            40,
            pod -> {
              boolean failProvider = idx.getAndIncrement() % 2 == 0;
              Object r = tryConsume(pod, u, Feature.GUIDE_CHAT, t);
              if (r instanceof Grant g && failProvider) {
                pod.refund(g);
                return "refunded";
              }
              return r;
            });

    int kept = (int) results.stream().filter(r -> r instanceof Grant).count();
    assertThat(kept).isLessThanOrEqualTo(15);
    String subject =
        jdbc.sql("SELECT DISTINCT subject FROM usage_counter WHERE subject LIKE :s")
            .param("s", "%" + u + "%")
            .query(String.class)
            .single();
    subjects.add(subject);
    assertThat(counts(subject)).containsExactly(kept, kept);

    for (int i = 0; i < 15 - kept; i++) {
      ok(i % 2 == 0 ? podA : podB, u, Feature.GUIDE_CHAT, t.plusSeconds(1 + i));
    }
    rejected(podB, u, Feature.GUIDE_CHAT, t.plusSeconds(60));
    assertThat(counts(subject)).containsExactly(15, 15);
  }

  @Test
  @DisplayName("하루 재설정 — 실제 DB 에서도 한국 자정: 17~23 시에 100 번, 23:59:59 거절(1 초), 다음 날 00:00:00 허용")
  void dayResetsAtKstMidnightOnRealDb() {
    UUID u = user();
    int used = 0;
    for (int hour = 17; hour <= 23 && used < 100; hour++) {
      for (int i = 0; i < 15 && used < 100; i++) {
        ok(used % 2 == 0 ? podA : podB, u, Feature.GUIDE_CHAT, kst(TODAY, hour, i, 0));
        used++;
      }
    }
    LimitExceeded e = rejected(podA, u, Feature.GUIDE_CHAT, kst(TODAY, 23, 59, 59));
    assertThat(e.code()).isEqualTo("GUIDE_LIMIT_REACHED");
    assertThat(e.retryAfterSeconds()).isEqualTo(1);

    Grant g = ok(podB, u, Feature.GUIDE_CHAT, kst(TODAY.plusDays(1), 0, 0, 0));
    assertThat(g.headers().limit()).isEqualTo(15);
    assertThat(g.headers().remaining()).isEqualTo(14);
  }

  @Test
  @DisplayName("하루 재설정 — 실제 DB 에서 UTC 자정(KST 09:00)에는 다시 차지 않는다, Retry-After 15 시간")
  void dayDoesNotResetAtUtcMidnightOnRealDb() {
    UUID u = user();
    int used = 0;
    for (int hour = 2; hour <= 8 && used < 100; hour++) {
      for (int i = 0; i < 15 && used < 100; i++) {
        ok(podA, u, Feature.GUIDE_CHAT, kst(TODAY, hour, i, 0));
        used++;
      }
    }
    LimitExceeded e = rejected(podB, u, Feature.GUIDE_CHAT, kst(TODAY, 9, 0, 0));
    assertThat(e.code()).isEqualTo("GUIDE_LIMIT_REACHED");
    assertThat(e.retryAfterSeconds()).isEqualTo(15 * 3600);
  }

  @Test
  @DisplayName("하루 한도에 걸린 거절은 지금 시간 줄도 늘리지 않는다 — 14 시 10 번 뒤 20 번 거절, 14 시 줄은 10 그대로")
  void dayRejectionDoesNotTouchHourRow() {
    UUID u = user();
    for (int hour = 8; hour <= 13; hour++) {
      for (int i = 0; i < 15; i++) {
        ok(podA, u, Feature.GUIDE_CHAT, kst(TODAY, hour, i, 0));
      }
    }
    for (int i = 0; i < 10; i++) {
      ok(podB, u, Feature.GUIDE_CHAT, kst(TODAY, 14, i, 0));
    }
    for (int i = 0; i < 20; i++) {
      rejected(i % 2 == 0 ? podA : podB, u, Feature.GUIDE_CHAT, kst(TODAY, 14, 20 + i, 0));
    }
    String subject = subjects.get(subjects.size() - 1);
    assertThat(counts(subject)).containsExactly(10, 15, 15, 15, 15, 15, 15, 100);
  }
}
