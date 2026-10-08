package com.mz2az.scenetrip.sceneapi.limit;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 유료 API 사용량(V24 {@code usage_counter})을 실제 PostgreSQL 에서 — 원자적 UPSERT 의 늘리기·줄이기, 동시 요청, 오래된 창 정리,
 * 그리고 {@link PaidQuota} 가 그 위에서 한도를 넘기지 않는지.
 *
 * <p>행은 이 테스트만 쓰는 subject(무작위 UUID)로 만들고 끝나면 지운다.
 */
@DisplayName("UsageStore — 사용량 표 (실제 DB)")
class UsageStoreIntegrationTest {

  private static JdbcClient jdbc;
  private static UsageStore store;

  private final List<String> subjects = new ArrayList<>();

  /** 오늘 UTC 05:30 — 한국 14:30, 어느 창의 경계에서도 멀다. */
  private static final Instant MID_DAY =
      LocalDate.now(ZoneOffset.UTC).atTime(5, 30).toInstant(ZoneOffset.UTC);

  private static final OffsetDateTime WINDOW = MID_DAY.atOffset(ZoneOffset.UTC);

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    store = new UsageStore(jdbc);
  }

  @AfterEach
  void cleanUp() {
    for (String s : subjects) {
      jdbc.sql("DELETE FROM usage_counter WHERE subject = :s").param("s", s).update();
    }
  }

  private String subject() {
    String s = UUID.randomUUID().toString();
    subjects.add(s);
    return s;
  }

  private static List<Integer> rows(String subject) {
    return jdbc.sql("SELECT count FROM usage_counter WHERE subject = :s ORDER BY window_start")
        .param("s", subject)
        .query(Integer.class)
        .list();
  }

  private static int count(String subject, String feature, OffsetDateTime window) {
    return jdbc.sql(
            "SELECT count FROM usage_counter"
                + " WHERE subject = :s AND feature = :f AND window_start = :w")
        .param("s", subject)
        .param("f", feature)
        .param("w", window)
        .query(Integer.class)
        .optional()
        .orElse(0);
  }

  @Test
  @DisplayName("늘리기는 늘린 뒤의 수를 돌려준다 — 1, 2, 3")
  void incrementReturnsNewCount() {
    String s = subject();

    assertThat(store.increment(s, "guide-chat:hour", WINDOW)).isEqualTo(1);
    assertThat(store.increment(s, "guide-chat:hour", WINDOW)).isEqualTo(2);
    assertThat(store.increment(s, "guide-chat:hour", WINDOW)).isEqualTo(3);
    assertThat(count(s, "guide-chat:hour", WINDOW)).isEqualTo(3);
  }

  @Test
  @DisplayName("창·기능·주체가 다르면 다른 줄이다")
  void separateRowsPerWindowFeatureSubject() {
    String s = subject();
    String other = subject();

    store.increment(s, "guide-chat:hour", WINDOW);
    store.increment(s, "guide-chat:hour", WINDOW.plusHours(1));
    store.increment(s, "navigation:minute", WINDOW);
    store.increment(other, "guide-chat:hour", WINDOW);

    assertThat(rows(s)).hasSize(3).containsOnly(1);
    assertThat(rows(other)).containsExactly(1);
  }

  @Test
  @DisplayName("같은 순간을 다른 오프셋으로 적어도 같은 창이다(timestamptz)")
  void sameInstantDifferentOffsetIsSameWindow() {
    String s = subject();

    store.increment(s, "guide-chat:hour", WINDOW);
    int second =
        store.increment(s, "guide-chat:hour", WINDOW.withOffsetSameInstant(ZoneOffset.ofHours(9)));

    assertThat(second).isEqualTo(2);
  }

  @Test
  @DisplayName("줄이기는 0 아래로 내려가지 않고, 없는 창을 줄이면 아무 줄도 생기지 않는다")
  void decrementStopsAtZero() {
    String s = subject();
    store.increment(s, "guide-chat:hour", WINDOW);

    store.decrement(s, "guide-chat:hour", WINDOW);
    store.decrement(s, "guide-chat:hour", WINDOW);
    store.decrement(s, "navigation:minute", WINDOW);

    assertThat(count(s, "guide-chat:hour", WINDOW)).isZero();
    assertThat(rows(s)).containsExactly(0);
    assertThat(store.increment(s, "guide-chat:hour", WINDOW)).isEqualTo(1);
  }

  @Test
  @DisplayName("동시에 64 번 늘려도 잃어버리는 수가 없다 — 돌려받은 수가 정확히 1..64")
  void concurrentIncrementsAreAtomic() throws Exception {
    String s = subject();
    List<Integer> results =
        runConcurrently(64, () -> store.increment(s, "navigation:minute", WINDOW));

    Set<Integer> distinct = new TreeSet<>(results);
    assertThat(distinct).containsExactlyElementsOf(IntStream.rangeClosed(1, 64).boxed().toList());
    assertThat(count(s, "navigation:minute", WINDOW)).isEqualTo(64);
  }

  @Test
  @DisplayName("PaidQuota — 같은 사용자의 동시 요청 40 개가 시간 한도 10 을 넘기지 않는다, 표에도 10")
  void quotaHoldsUnderConcurrency() throws Exception {
    UUID user = UUID.randomUUID();
    subjects.add(user.toString());
    PaidQuota quota = new PaidQuota(store, 10, 100, 10, 300);

    List<Boolean> granted =
        runConcurrently(
            40,
            () -> {
              try {
                quota.consume(user, PaidQuota.Feature.GUIDE_CHAT, MID_DAY);
                return true;
              } catch (PaidQuota.LimitExceeded e) {
                return false;
              }
            });

    assertThat(granted.stream().filter(b -> b).count()).isEqualTo(10);
    OffsetDateTime hour = OffsetDateTime.parse(LocalDate.now(ZoneOffset.UTC) + "T05:00Z");
    assertThat(count(user.toString(), "guide-chat:hour", hour)).isEqualTo(10);
  }

  @Test
  @DisplayName("PaidQuota — 쓰면 짧은 창과 하루 창이 각각 한 줄씩 생기고, 되돌리면 둘 다 0")
  void quotaWritesTwoRowsAndRefunds() {
    UUID user = UUID.randomUUID();
    subjects.add(user.toString());
    PaidQuota quota = new PaidQuota(store, 15, 100, 10, 300);

    PaidQuota.Grant grant = quota.consume(user, PaidQuota.Feature.NAVIGATION, MID_DAY);
    assertThat(rows(user.toString())).containsExactly(1, 1);
    List<String> features =
        jdbc.sql("SELECT feature FROM usage_counter WHERE subject = :s ORDER BY feature")
            .param("s", user.toString())
            .query(String.class)
            .list();
    assertThat(features).containsExactly("navigation:day", "navigation:minute");

    quota.refund(grant);
    assertThat(rows(user.toString())).containsExactly(0, 0);
  }

  @Test
  @DisplayName("PaidQuota — 한국 자정 직후 첫 시간에도 시간 창과 하루 창은 따로 센다(두 줄)")
  void firstKstHourKeepsWindowsSeparate() {
    UUID user = UUID.randomUUID();
    subjects.add(user.toString());
    PaidQuota quota = new PaidQuota(store, 15, 100, 10, 300);
    // 오늘 한국 00:10 = UTC 전날 15:10
    Instant kstJustAfterMidnight =
        LocalDate.now(ZoneOffset.UTC).minusDays(1).atTime(15, 10).toInstant(ZoneOffset.UTC);

    quota.consume(user, PaidQuota.Feature.GUIDE_CHAT, kstJustAfterMidnight);

    assertThat(rows(user.toString())).containsExactly(1, 1);
    // 두 줄은 같은 시각(한국 자정)에 시작하고 feature 의 창 종류로 갈린다
    OffsetDateTime midnight =
        LocalDate.now(ZoneOffset.UTC).minusDays(1).atTime(15, 0).atOffset(ZoneOffset.UTC);
    assertThat(count(user.toString(), "guide-chat:hour", midnight)).isEqualTo(1);
    assertThat(count(user.toString(), "guide-chat:day", midnight)).isEqualTo(1);
  }

  @Test
  @DisplayName("오래된 창 정리 — 이틀 넘은 줄만 지운다")
  void purgeOldRemovesOnlyOlderThanTwoDays() {
    String s = subject();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    store.increment(s, "guide-chat:hour", now.minusDays(3));
    store.increment(s, "guide-chat:hour", now.minusDays(1));
    store.increment(s, "guide-chat:hour", now);

    store.purgeOld();

    assertThat(rows(s)).hasSize(2);
    assertThat(count(s, "guide-chat:hour", now.minusDays(3))).isZero();
  }

  private static <T> List<T> runConcurrently(int n, Callable<T> work) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(Math.min(n, 16));
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<T>> futures = new ArrayList<>();
      for (int i = 0; i < n; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return work.call();
                }));
      }
      start.countDown();
      List<T> out = new ArrayList<>();
      for (Future<T> f : futures) {
        out.add(f.get(30, TimeUnit.SECONDS));
      }
      return out;
    } finally {
      pool.shutdownNow();
    }
  }
}
