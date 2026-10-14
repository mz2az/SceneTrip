package com.mz2az.scenetrip.sceneapi.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link StorageDeletionJob} 을 실제 PostgreSQL 의 {@code storage_deletion}(V34) 위에서 돌린다 — 계획 {@code
 * post-photo-deletion.md} §2·§4.
 *
 * <p>명세에서 나온 기대: 오래된 것부터 {@link StorageDeletionJob#BATCH} 개를 지우고 지운 줄은 뺀다. 저장소가 실패하면 그 키의 {@code
 * attempts}·{@code last_error} 를 올리고 그 차례를 멈춘다(뒤 키는 건드리지 않는다). 실패 기록은 앞서 지운 줄과 함께 커밋된다 — 작업이 던지거나
 * 트랜잭션을 되돌림으로 표시하지 않는다. advisory lock({@code hashtext('storage_deletion')})을 다른 쪽이 쥐고 있으면 건너뛰고 0.
 * 저장소가 없으면 0.
 *
 * <p>저장소만 가짜다. 시험은 전부 되돌리는 트랜잭션 하나 안에서 돌고, 작업의 트랜잭션은 그 안에 합류한다 — 시험이 넣은 줄을 작업이 보고, 끝나면 아무것도 남지 않는다.
 * 다른 데서 쌓인 줄이 셈을 흐리지 않게 트랜잭션 안에서 표를 먼저 비운다(되돌려지므로 실제로는 지워지지 않는다).
 */
@DisplayName("StorageDeletionJob — 지울 목록을 저장소에서 지운다 (V34, 실제 DB)")
class StorageDeletionJobIntegrationTest {

  private static final Instant BASE = Instant.parse("2001-01-01T00:00:00Z");

  private static JdbcClient jdbc;
  private static TransactionTemplate transactions;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);
  private final RecordingStorage storage = new RecordingStorage();
  private final StorageDeletionJob job =
      new StorageDeletionJob(jdbc(), IntegrationDatabase.transactions(), storage);

  @BeforeAll
  static void connect() {
    jdbc();
    transactions = IntegrationDatabase.transactions();
  }

  private static JdbcClient jdbc() {
    if (jdbc == null) {
      jdbc = IntegrationDatabase.jdbcClient();
    }
    return jdbc;
  }

  // ───────────── 지우기 ─────────────

  @Test
  @DisplayName("목록의 키를 저장소에서 지우고 줄을 뺀다 — 지운 개수를 돌려준다")
  void deletesListedKeysAndRemovesRows() {
    rolledBack(
        status -> {
          List<String> keys = List.of(key("a"), key("b"), key("c"));
          for (int i = 0; i < keys.size(); i++) {
            enqueue(keys.get(i), i);
            storage.files.add(keys.get(i));
          }

          assertThat(job.run()).isEqualTo(3);

          assertThat(storage.deleted).containsExactlyElementsOf(keys);
          assertThat(storage.files).doesNotContainAnyElementsOf(keys);
          assertThat(rows()).isZero();
          return null;
        });
  }

  @Test
  @DisplayName("목록이 비어 있으면 0 이고 저장소를 부르지 않는다")
  void emptyListIsNoop() {
    rolledBack(
        status -> {
          assertThat(job.run()).isZero();
          assertThat(storage.calls).isEmpty();
          return null;
        });
  }

  @Test
  @DisplayName("저장소에 이미 없는 키도 지운 것으로 친다 — 줄이 빠진다")
  void missingFileStillCounts() {
    rolledBack(
        status -> {
          String gone = key("gone");
          enqueue(gone, 0);

          assertThat(job.run()).isEqualTo(1);
          assertThat(storage.deleted).containsExactly(gone);
          assertThat(rows()).isZero();
          return null;
        });
  }

  @Test
  @DisplayName("한 차례에 BATCH(20) 개까지, 오래된 것부터 — 넣은 순서와 상관없이 created_at 순")
  void batchOfOldestFirst() {
    rolledBack(
        status -> {
          int total = StorageDeletionJob.BATCH + 5;
          List<String> byAge = new ArrayList<>();
          for (int i = 0; i < total; i++) {
            byAge.add(key("k" + i));
          }
          // 새것부터 넣는다 — 넣은 순서(물리 순서)로 읽으면 틀리게.
          for (int i = total - 1; i >= 0; i--) {
            enqueue(byAge.get(i), i);
          }

          assertThat(job.run()).isEqualTo(StorageDeletionJob.BATCH);
          assertThat(storage.deleted)
              .containsExactlyInAnyOrderElementsOf(byAge.subList(0, StorageDeletionJob.BATCH));
          assertThat(remainingKeys())
              .containsExactlyInAnyOrderElementsOf(byAge.subList(StorageDeletionJob.BATCH, total));

          storage.deleted.clear();
          assertThat(job.run()).isEqualTo(5);
          assertThat(storage.deleted)
              .containsExactlyInAnyOrderElementsOf(byAge.subList(StorageDeletionJob.BATCH, total));
          assertThat(rows()).isZero();

          assertThat(job.run()).isZero();
          return null;
        });
  }

  @Test
  @DisplayName("한 차례 안에서도 오래된 것부터 지운다")
  void deletesInAgeOrderWithinRun() {
    rolledBack(
        status -> {
          String old = key("old");
          String mid = key("mid");
          String young = key("young");
          enqueue(young, 2);
          enqueue(old, 0);
          enqueue(mid, 1);

          assertThat(job.run()).isEqualTo(3);
          assertThat(storage.deleted).containsExactly(old, mid, young);
          return null;
        });
  }

  // ───────────── 실패 ─────────────

  @Test
  @DisplayName(
      "저장소가 실패하면 그 키는 남아 attempts 1·last_error 가 기록되고, 그 차례는 멈춘다 — 앞서 지운 줄은 빠지고 뒤 키는 손대지 않는다")
  void failureStopsRunAndRecords() {
    rolledBack(
        status -> {
          String first = key("1");
          String broken = key("2");
          String after = key("3");
          enqueue(first, 0);
          enqueue(broken, 1);
          enqueue(after, 2);
          storage.failing.add(broken);

          assertThat(job.run()).as("실패 앞에서 지운 것만 센다").isEqualTo(1);

          assertThat(storage.calls).as("실패한 뒤 남은 키를 두드리지 않는다").containsExactly(first, broken);
          assertThat(remainingKeys()).containsExactlyInAnyOrder(broken, after);
          assertThat(attempts(broken)).isEqualTo(1);
          assertThat(lastError(broken)).isNotNull().contains(RecordingStorage.MESSAGE);
          assertThat(attempts(after)).isZero();
          assertThat(lastError(after)).isNull();
          // 실패 기록이 앞서 지운 줄과 함께 커밋되려면 작업이 트랜잭션을 되돌림으로 표시하면 안 된다.
          assertThat(status.isRollbackOnly())
              .as("실패 기록은 커밋되어야 한다 — 작업이 트랜잭션을 되돌림으로 표시했다")
              .isFalse();
          return null;
        });
  }

  @Test
  @DisplayName("가장 오래된 키에서 실패하면 0 이고 아무 줄도 빠지지 않는다")
  void failureOnFirstKey() {
    rolledBack(
        status -> {
          String broken = key("1");
          String other = key("2");
          enqueue(broken, 0);
          enqueue(other, 1);
          storage.failing.add(broken);

          assertThat(job.run()).isZero();
          assertThat(storage.calls).containsExactly(broken);
          assertThat(remainingKeys()).containsExactlyInAnyOrder(broken, other);
          assertThat(attempts(broken)).isEqualTo(1);
          assertThat(status.isRollbackOnly()).isFalse();
          return null;
        });
  }

  @Test
  @DisplayName("실패할 때마다 attempts 가 오르고 last_error 는 마지막 것 — 저장소가 살아나면 다음 차례에 지워진다")
  void retriesOnNextRun() {
    rolledBack(
        status -> {
          String broken = key("1");
          String other = key("2");
          enqueue(broken, 0);
          enqueue(other, 1);
          storage.failing.add(broken);

          assertThat(job.run()).isZero();
          storage.message = "두 번째 실패";
          assertThat(job.run()).isZero();
          assertThat(attempts(broken)).isEqualTo(2);
          assertThat(lastError(broken)).contains("두 번째 실패");
          assertThat(storage.calls).containsExactly(broken, broken);

          storage.failing.clear();
          storage.calls.clear();
          assertThat(job.run()).isEqualTo(2);
          assertThat(storage.calls).containsExactly(broken, other);
          assertThat(rows()).isZero();
          return null;
        });
  }

  // ───────────── 저장소 없음 · 잠금 ─────────────

  @Test
  @DisplayName("저장소가 없는 환경(available=false)이면 0 이고 저장소를 부르지 않으며 줄은 그대로다")
  void unavailableStorage() {
    rolledBack(
        status -> {
          enqueue(key("a"), 0);
          storage.available = false;

          assertThat(job.run()).isZero();
          assertThat(storage.calls).isEmpty();
          assertThat(rows()).isEqualTo(1);
          assertThat(attempts(key("a"))).isZero();
          return null;
        });
  }

  @Test
  @DisplayName("다른 쪽(다른 파드)이 advisory lock 을 쥐고 있으면 그 차례를 건너뛴다 — 0, 저장소 호출 없음, 줄 그대로")
  void skipsWhenLockHeldElsewhere() throws SQLException {
    try (Connection other = rawConnection()) {
      other.setAutoCommit(false);
      try (Statement st = other.createStatement()) {
        st.execute("SELECT pg_advisory_xact_lock(hashtext('storage_deletion'))");
      }
      rolledBack(
          status -> {
            enqueue(key("a"), 0);
            enqueue(key("b"), 1);

            assertThat(job.run()).isZero();
            assertThat(storage.calls).isEmpty();
            assertThat(rows()).isEqualTo(2);
            assertThat(attempts(key("a"))).isZero();
            return null;
          });
      other.rollback();
    }
  }

  @Test
  @DisplayName("작업이 도는 동안에는 advisory lock 을 쥐고 있다 — 다른 쪽이 같은 잠금을 잡지 못한다")
  void holdsLockWhileRunning() {
    rolledBack(
        status -> {
          enqueue(key("a"), 0);
          List<Boolean> othersGotLock = new ArrayList<>();
          storage.onDelete =
              k -> {
                try (Connection other = rawConnection();
                    Statement st = other.createStatement();
                    ResultSet rs =
                        st.executeQuery(
                            "SELECT pg_try_advisory_xact_lock(hashtext('storage_deletion'))")) {
                  rs.next();
                  othersGotLock.add(rs.getBoolean(1));
                } catch (SQLException e) {
                  throw new IllegalStateException(e);
                }
              };

          assertThat(job.run()).isEqualTo(1);
          assertThat(othersGotLock).containsExactly(false);
          return null;
        });
  }

  // ───────────── 도우미 ─────────────

  /** 되돌리는 트랜잭션 하나 안에서 돌린다. 되돌림 표시는 끝에서 한다 — 작업이 표시했는지 시험이 볼 수 있게. */
  private static <T> T rolledBack(Function<TransactionStatus, T> work) {
    return transactions.execute(
        status -> {
          try {
            jdbc.sql("DELETE FROM storage_deletion").update();
            return work.apply(status);
          } finally {
            status.setRollbackOnly();
          }
        });
  }

  private String key(String name) {
    return "posts/it-del-" + tag + "-" + name + ".jpg";
  }

  /** 목록에 넣는다 — {@code ageRank} 가 작을수록 오래된 줄. */
  private static void enqueue(String key, int ageRank) {
    jdbc.sql(
            "INSERT INTO storage_deletion (storage_key, created_at)"
                + " VALUES (:k, CAST(:t AS TIMESTAMPTZ))")
        .param("k", key)
        .param("t", BASE.plusSeconds(ageRank * 60L).toString())
        .update();
  }

  private static long rows() {
    return jdbc.sql("SELECT count(*) FROM storage_deletion").query(Long.class).single();
  }

  private static List<String> remainingKeys() {
    return jdbc.sql("SELECT storage_key FROM storage_deletion").query(String.class).list();
  }

  private static int attempts(String key) {
    return jdbc.sql("SELECT attempts FROM storage_deletion WHERE storage_key = :k")
        .param("k", key)
        .query(Integer.class)
        .single();
  }

  private static String lastError(String key) {
    return jdbc.sql("SELECT last_error FROM storage_deletion WHERE storage_key = :k")
        .param("k", key)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  /** 시험 트랜잭션과 다른 접속 — 다른 파드 노릇. */
  private static Connection rawConnection() throws SQLException {
    return DriverManager.getConnection(
        System.getenv("SCENETRIP_TEST_JDBC_URL"),
        System.getenv("SCENETRIP_TEST_DB_USER"),
        System.getenv("SCENETRIP_TEST_DB_PASSWORD"));
  }

  /** 가짜 저장소 — 지우기 호출을 순서대로 적고, {@link #failing} 에 든 키는 실패한다. 없는 키를 지워도 성공(명세). */
  private static final class RecordingStorage implements PhotoStorage {
    static final String MESSAGE = "저장소 고장(시험)";

    final Set<String> files = new HashSet<>();
    final Set<String> failing = new HashSet<>();
    final List<String> calls = new ArrayList<>();
    final List<String> deleted = new ArrayList<>();
    boolean available = true;
    String message = MESSAGE;
    java.util.function.Consumer<String> onDelete = k -> {};

    @Override
    public boolean available() {
      return available;
    }

    @Override
    public URI viewUrl(String storageKey) {
      return URI.create("https://signed.example/" + storageKey);
    }

    @Override
    public PresignedUpload presignUpload(
        String storageKey, String contentType, long bytes, Duration expiresIn) {
      return new PresignedUpload(
          URI.create("https://bucket.example/" + storageKey),
          Map.of("Content-Type", contentType),
          Instant.now().plus(expiresIn));
    }

    @Override
    public void move(String fromKey, String toKey) {
      throw new AssertionError("지우기 작업이 move 를 부르면 안 된다");
    }

    @Override
    public void delete(String storageKey) {
      calls.add(storageKey);
      onDelete.accept(storageKey);
      if (failing.contains(storageKey)) {
        throw new IllegalStateException(message);
      }
      files.remove(storageKey);
      deleted.add(storageKey);
    }
  }
}
