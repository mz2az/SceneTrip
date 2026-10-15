package com.mz2az.scenetrip.sceneapi.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 신고 기록 보관 기간(MZ2AZ-438) — 신고한 날부터 1 년(설정 {@code scenetrip.moderation.report-retention-days}, 기본
 * 365)이 지난 {@code content_report} 줄만 {@link ReportStore#purge()} 가 지운다. 기간 안의 줄은 남기고, DB 가 고장이어도
 * 던지지 않는다.
 *
 * <p>줄은 SQL 로 직접 넣고 {@code created_at} 을 기간 앞뒤로 옮긴다. 다음 질의가 봐야 하므로 커밋하고, 끝나면 제 줄을 지운다 — 대상 번호는 다른
 * 데이터와 겹치지 않게 큰 무작위 값을 쓴다.
 */
@DisplayName("신고 기록 보관 기간 — purge (실제 DB)")
class ReportPurgeIntegrationTest {

  private static JdbcClient jdbc;
  private static TransactionTemplate transactions;

  private final List<Long> created = new ArrayList<>();

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    transactions = IntegrationDatabase.transactions();
  }

  @AfterEach
  void cleanUp() {
    for (long id : created) {
      jdbc.sql("DELETE FROM content_report WHERE id = :id").param("id", id).update();
    }
  }

  @Test
  @DisplayName("기본 365 일 — 366 일 전·365 일+1 시간 전은 지우고, 364 일 전·365 일−1 시간 전·방금은 남긴다")
  void defaultRetentionDeletesOnlyOlderRows() {
    long older = report("366 days");
    long justOver = report("365 days 1 hour");
    long justUnder = report("364 days 23 hours");
    long within = report("364 days");
    long fresh = report("0 seconds");

    new ReportStore(jdbc, transactions, 365).purge();

    assertThat(exists(older)).isFalse();
    assertThat(exists(justOver)).isFalse();
    assertThat(exists(justUnder)).isTrue();
    assertThat(exists(within)).isTrue();
    assertThat(exists(fresh)).isTrue();
  }

  @Test
  @DisplayName("보관 기간은 설정을 따른다 — 30 일이면 31 일 전은 지우고 29 일 전은 남긴다. 다시 불러도 같다")
  void retentionFollowsSetting() {
    long older = report("31 days");
    long within = report("29 days");

    ReportStore store = new ReportStore(jdbc, transactions, 30);
    store.purge();
    store.purge();

    assertThat(exists(older)).isFalse();
    assertThat(exists(within)).isTrue();
  }

  @Test
  @DisplayName("DB 에 닿지 못해도 purge 는 던지지 않는다(로그만)")
  void purgeSwallowsDatabaseFailure() {
    DriverManagerDataSource broken =
        new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/nowhere?connectTimeout=1");
    broken.setDriverClassName("org.postgresql.Driver");
    ReportStore store =
        new ReportStore(
            JdbcClient.create(broken),
            new TransactionTemplate(new DataSourceTransactionManager(broken)),
            365);

    assertThatCode(store::purge).doesNotThrowAnyException();
  }

  /** {@code age} 만큼 지난 신고 한 줄(reporter 없음, 대상 번호는 무작위)을 커밋한다. */
  private long report(String age) {
    long id =
        jdbc.sql(
                """
                INSERT INTO content_report (target_type, target_id, reason, created_at)
                VALUES ('review', :t, 'spam', now() - CAST(:age AS interval))
                RETURNING id
                """)
            .param("t", ThreadLocalRandom.current().nextLong(1L << 50, Long.MAX_VALUE))
            .param("age", age)
            .query(Long.class)
            .single();
    created.add(id);
    return id;
  }

  private static boolean exists(long id) {
    return jdbc.sql("SELECT count(*) FROM content_report WHERE id = :id")
            .param("id", id)
            .query(Long.class)
            .single()
        == 1;
  }
}
