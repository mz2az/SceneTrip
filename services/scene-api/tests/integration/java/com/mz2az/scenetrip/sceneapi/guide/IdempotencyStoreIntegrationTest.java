package com.mz2az.scenetrip.sceneapi.guide;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.guide.IdempotencyStore.Begin;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * 챗봇 멱등 키(V24 {@code idempotency_key}, 계약 Idempotency-Key)를 실제 PostgreSQL 에서 — 처음·처리 중·끝남·내용 다름의 네
 * 판정, 놓기, 죽은 처리(1 분), 24 시간 보관, 사용자별 분리, 동시에 온 같은 키.
 *
 * <p>계정은 {@link UserStore#resolve} 로 테스트마다 새로 만들고 끝나면 지운다 — 키 줄은 ON DELETE CASCADE 로 함께 지워진다.
 */
@DisplayName("IdempotencyStore — 챗봇 멱등 키 (실제 DB)")
class IdempotencyStoreIntegrationTest {

  private static final String HASH = "a".repeat(64);
  private static final String OTHER_HASH = "b".repeat(64);
  private static final String REPLY =
      "{\"reply\":\"안녕\",\"tookSeconds\":1.5,\"effects\":[],\"ui\":[]}";

  private static JdbcClient jdbc;
  private static UserStore users;
  private static IdempotencyStore store;
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final List<UUID> created = new ArrayList<>();
  private UUID user;
  private String key;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    users = new UserStore(jdbc);
    store = new IdempotencyStore(jdbc);
  }

  @BeforeEach
  void newUser() {
    user = newAccount();
    key = "turn-" + UUID.randomUUID();
  }

  @AfterEach
  void cleanUp() {
    for (UUID id : created) {
      jdbc.sql("DELETE FROM app_user WHERE id = CAST(:id AS UUID)")
          .param("id", id.toString())
          .update();
    }
  }

  private UUID newAccount() {
    UUID id = users.resolve(UUID.randomUUID());
    created.add(id);
    return id;
  }

  private static void age(UUID user, String key, String interval) {
    jdbc.sql(
            "UPDATE idempotency_key SET created_at = now() - CAST(:i AS INTERVAL)"
                + " WHERE user_id = CAST(:u AS UUID) AND key = :k")
        .param("i", interval)
        .param("u", user.toString())
        .param("k", key)
        .update();
  }

  private static List<String> states(UUID user, String key) {
    return jdbc.sql(
            "SELECT state FROM idempotency_key WHERE user_id = CAST(:u AS UUID) AND key = :k")
        .param("u", user.toString())
        .param("k", key)
        .query(String.class)
        .list();
  }

  @Test
  @DisplayName("처음 보는 키는 Started, 처리 중 줄이 생긴다")
  void firstIsStarted() {
    assertThat(store.begin(user, key, HASH)).isInstanceOf(Begin.Started.class);
    assertThat(states(user, key)).containsExactly("processing");
  }

  @Test
  @DisplayName("같은 키·같은 내용이 처리 중이면 InProgress")
  void sameWhileProcessingIsInProgress() {
    store.begin(user, key, HASH);

    assertThat(store.begin(user, key, HASH)).isInstanceOf(Begin.InProgress.class);
  }

  @Test
  @DisplayName("끝난 키는 저장한 응답 그대로 Replay — JSON 이 같다")
  void completedReplaysStoredResponse() throws Exception {
    store.begin(user, key, HASH);
    store.complete(user, key, REPLY);

    Begin again = store.begin(user, key, HASH);

    assertThat(again).isInstanceOf(Begin.Replay.class);
    String stored = ((Begin.Replay) again).responseJson();
    assertThat(JSON.readTree(stored)).isEqualTo(JSON.readTree(REPLY));
    assertThat(states(user, key)).containsExactly("completed");
  }

  @Test
  @DisplayName("같은 키에 다른 내용이면 Mismatch — 처리 중이어도, 끝났어도")
  void differentHashIsMismatch() {
    store.begin(user, key, HASH);
    assertThat(store.begin(user, key, OTHER_HASH)).isInstanceOf(Begin.Mismatch.class);

    store.complete(user, key, REPLY);
    assertThat(store.begin(user, key, OTHER_HASH)).isInstanceOf(Begin.Mismatch.class);
  }

  @Test
  @DisplayName("놓으면(abandon) 같은 키로 다시 Started")
  void abandonReleasesKey() {
    store.begin(user, key, HASH);
    store.abandon(user, key);

    assertThat(states(user, key)).isEmpty();
    assertThat(store.begin(user, key, HASH)).isInstanceOf(Begin.Started.class);
  }

  @Test
  @DisplayName("끝난 키는 abandon 으로 지워지지 않는다 — 여전히 Replay")
  void abandonDoesNotRemoveCompleted() {
    store.begin(user, key, HASH);
    store.complete(user, key, REPLY);
    store.abandon(user, key);

    assertThat(store.begin(user, key, HASH)).isInstanceOf(Begin.Replay.class);
  }

  @Test
  @DisplayName("처리 중인 채 1 분 넘은 키는 죽은 처리로 보고 다시 Started, 30 초면 아직 InProgress")
  void staleProcessingIsRestarted() {
    store.begin(user, key, HASH);
    age(user, key, "30 seconds");
    assertThat(store.begin(user, key, HASH)).isInstanceOf(Begin.InProgress.class);

    age(user, key, "61 seconds");
    assertThat(store.begin(user, key, HASH)).isInstanceOf(Begin.Started.class);
    assertThat(states(user, key)).containsExactly("processing");
  }

  @Test
  @DisplayName("끝난 키는 1 분이 지나도 Replay — 죽은 처리 규칙은 처리 중인 것에만")
  void oldCompletedStillReplays() {
    store.begin(user, key, HASH);
    store.complete(user, key, REPLY);
    age(user, key, "2 hours");

    assertThat(store.begin(user, key, HASH)).isInstanceOf(Begin.Replay.class);
  }

  @Test
  @DisplayName("24 시간 지난 키는 지워진다 — 다른 키의 begin 에서 정리되고, 같은 키는 다시 Started")
  void entriesOlderThanADayAreRemoved() {
    store.begin(user, key, HASH);
    store.complete(user, key, REPLY);
    age(user, key, "25 hours");

    store.begin(user, "other-" + key, HASH);
    assertThat(states(user, key)).isEmpty();

    assertThat(store.begin(user, key, HASH)).isInstanceOf(Begin.Started.class);
  }

  @Test
  @DisplayName("키는 사용자마다 따로다 — 두 사용자가 같은 키를 써도 둘 다 Started")
  void keysArePerUser() {
    UUID other = newAccount();

    assertThat(store.begin(user, key, HASH)).isInstanceOf(Begin.Started.class);
    assertThat(store.begin(other, key, OTHER_HASH)).isInstanceOf(Begin.Started.class);
    store.complete(user, key, REPLY);
    assertThat(store.begin(other, key, OTHER_HASH)).isInstanceOf(Begin.InProgress.class);
  }

  @Test
  @DisplayName("같은 키가 동시에 16 번 와도 Started 는 정확히 하나, 나머지는 InProgress")
  void concurrentBeginStartsOnce() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(16);
    CountDownLatch start = new CountDownLatch(1);
    try {
      List<Future<Begin>> futures = new ArrayList<>();
      for (int i = 0; i < 16; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return store.begin(user, key, HASH);
                }));
      }
      start.countDown();
      int started = 0;
      int inProgress = 0;
      for (Future<Begin> f : futures) {
        Begin b = f.get(30, TimeUnit.SECONDS);
        if (b instanceof Begin.Started) {
          started++;
        } else if (b instanceof Begin.InProgress) {
          inProgress++;
        }
      }
      assertThat(started).isEqualTo(1);
      assertThat(inProgress).isEqualTo(15);
    } finally {
      pool.shutdownNow();
    }
  }
}
