package com.mz2az.scenetrip.sceneapi.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 리뷰 보관 사본의 보관 기간(MZ2AZ-466) — {@code archived_at} 이 기간(설정 {@code
 * scenetrip.review.archive-retention-days}, 기본 90 일)보다 오래된 {@code review_archive} 줄만 {@link
 * ReviewArchivePurge#purge()} 가 지우고, 그 줄의 {@code photo_keys} 를 전부 지울 목록({@code storage_deletion})에
 * 올린다. 기간 안의 줄과 그 사진은 남는다. 키가 겹쳐도 오류가 아니고, DB 가 고장이어도 던지지 않는다.
 *
 * <p>전부 되돌리는 트랜잭션 하나 안에서 돈다 — purge 는 같은 DataSource 의 접속을 써서 이 트랜잭션에 들어온다. 그래서 다른 오래된 보관 줄이 같이 지워져도
 * 끝나면 되돌아간다.
 */
@DisplayName("리뷰 보관 사본 보관 기간 — purge (실제 DB)")
class ReviewArchivePurgeIntegrationTest {

  private static JdbcClient jdbc;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
  }

  @Test
  @DisplayName("기본 90 일 — 91 일 전·90 일+1 시간 전은 지우고 키를 올린다. 89 일 23 시간 전·89 일 전·방금은 남고 키도 오르지 않는다")
  void defaultRetentionBoundaries() {
    IntegrationDatabase.rolledBack(
        () -> {
          List<String> olderKeys = List.of(key("older-1"), key("older-2"));
          List<String> overKeys = List.of(key("over"));
          List<String> underKeys = List.of(key("under"));
          List<String> withinKeys = List.of(key("within"));
          List<String> freshKeys = List.of(key("fresh"));
          long older = archive("91 days", olderKeys);
          long justOver = archive("90 days 1 hour", overKeys);
          long noPhotosOld = archive("120 days", List.of());
          long justUnder = archive("89 days 23 hours", underKeys);
          long within = archive("89 days", withinKeys);
          long fresh = archive("0 seconds", freshKeys);

          new ReviewArchivePurge(jdbc, 90).purge();

          assertThat(exists(older)).isFalse();
          assertThat(exists(justOver)).isFalse();
          assertThat(exists(noPhotosOld)).isFalse();
          assertThat(exists(justUnder)).isTrue();
          assertThat(exists(within)).isTrue();
          assertThat(exists(fresh)).isTrue();
          assertThat(listed(olderKeys)).containsExactlyInAnyOrderElementsOf(olderKeys);
          assertThat(listed(overKeys)).containsExactlyElementsOf(overKeys);
          assertThat(listed(underKeys)).isEmpty();
          assertThat(listed(withinKeys)).isEmpty();
          assertThat(listed(freshKeys)).isEmpty();
          return null;
        });
  }

  @Test
  @DisplayName("보관 기간은 설정을 따른다 — 30 일이면 31 일 전은 지우고 29 일 전은 남긴다. 다시 불러도 같다")
  void retentionFollowsSetting() {
    IntegrationDatabase.rolledBack(
        () -> {
          List<String> olderKeys = List.of(key("o30"));
          List<String> withinKeys = List.of(key("w30"));
          long older = archive("31 days", olderKeys);
          long within = archive("29 days", withinKeys);

          ReviewArchivePurge purge = new ReviewArchivePurge(jdbc, 30);
          purge.purge();
          purge.purge();

          assertThat(exists(older)).isFalse();
          assertThat(exists(within)).isTrue();
          assertThat(listed(olderKeys)).containsExactlyElementsOf(olderKeys);
          assertThat(listed(withinKeys)).isEmpty();
          return null;
        });
  }

  @Test
  @DisplayName("키가 겹쳐도 오류가 아니다 — 이미 목록에 있는 키(줄 그대로), 지워질 두 줄이 같은 키를 가진 경우 모두 지워지고 키는 한 번씩")
  void duplicateKeysAreNotAnError() {
    IntegrationDatabase.rolledBack(
        () -> {
          String already = key("already");
          String shared = key("shared");
          String own = key("own");
          jdbc.sql(
                  "INSERT INTO storage_deletion (storage_key, created_at, attempts, last_error)"
                      + " VALUES (:k, TIMESTAMPTZ '2001-02-03 04:05:06+00', 2, :e)")
              .param("k", already)
              .param("e", "앞선 실패(시험)")
              .update();
          long a = archive("100 days", List.of(already, shared));
          long b = archive("100 days", List.of(shared, own));

          new ReviewArchivePurge(jdbc, 90).purge();

          assertThat(exists(a)).isFalse();
          assertThat(exists(b)).isFalse();
          assertThat(listed(List.of(already, shared, own)))
              .containsExactlyInAnyOrder(already, shared, own);
          assertThat(
                  jdbc.sql(
                          "SELECT attempts || '|' || coalesce(last_error, '') FROM storage_deletion"
                              + " WHERE storage_key = :k")
                      .param("k", already)
                      .query(String.class)
                      .single())
              .isEqualTo("2|앞선 실패(시험)");
          return null;
        });
  }

  @Test
  @DisplayName("DB 에 닿지 못해도 purge 는 던지지 않는다(로그만)")
  void purgeSwallowsDatabaseFailure() {
    DriverManagerDataSource broken =
        new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/nowhere?connectTimeout=1");
    broken.setDriverClassName("org.postgresql.Driver");
    ReviewArchivePurge purge = new ReviewArchivePurge(JdbcClient.create(broken), 90);

    assertThatCode(purge::purge).doesNotThrowAnyException();
  }

  // ───────────── 도우미 ─────────────

  /** {@code age} 만큼 전에 보관된 사본 한 줄. 사용자·대상은 무작위(외래 키 없음). */
  private static long archive(String age, List<String> keys) {
    return jdbc.sql(
            """
            INSERT INTO review_archive (archived_at, reason, review_id, place_id, user_id, rating,
                                        created_at, updated_at, photo_keys)
            VALUES (now() - CAST(:age AS interval), 'it-archive-purge', -1, -1, CAST(:u AS UUID), 3,
                    now(), now(), CAST(:keys AS text[]))
            RETURNING id
            """)
        .param("age", age)
        .param("u", UUID.randomUUID().toString())
        .param("keys", "{" + String.join(",", keys) + "}")
        .query(Long.class)
        .single();
  }

  private String key(String name) {
    return "reviews/it-archive-purge-" + tag + "-" + name + ".jpg";
  }

  private static boolean exists(long id) {
    return jdbc.sql("SELECT count(*) FROM review_archive WHERE id = :id")
            .param("id", id)
            .query(Long.class)
            .single()
        == 1;
  }

  private static List<String> listed(List<String> keys) {
    return jdbc.sql("SELECT storage_key FROM storage_deletion WHERE storage_key IN (:k)")
        .param("k", keys)
        .query(String.class)
        .list();
  }
}
