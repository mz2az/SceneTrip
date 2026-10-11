package com.mz2az.scenetrip.sceneapi.popularity;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 사용 기록의 원본(계약 {@code POST /events}, 계획 {@code popularity.md} §2 「조회 중복」·§6 {@code
 * entity_view}·{@code search_log})을 진짜 PostgreSQL 에 적어 본다.
 *
 * <p>모든 시험은 {@link IntegrationDatabase#rolledBack} 안에서 돈다 — 공유하는 로컬 DB 에 행이 남지 않는다.
 */
@DisplayName("UsageEventStore — 사용 기록 원본 (실제 DB)")
class UsageEventStoreIntegrationTest {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  private static JdbcClient jdbc;
  private static UsageEventStore store;
  private static long content;
  private static long otherContent;
  private static long place;
  private static long poi;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    IntegrationDatabase.requireSeeded(jdbc);
    IntegrationDatabase.requirePoiSeeded(jdbc);
    store = new UsageEventStore(jdbc);
    List<Long> contents =
        jdbc.sql("SELECT id FROM content ORDER BY id LIMIT 2").query(Long.class).list();
    content = contents.get(0);
    otherContent = contents.get(1);
    place = IntegrationDatabase.anyPlaceId(jdbc);
    poi = jdbc.sql("SELECT id FROM poi ORDER BY id LIMIT 1").query(Long.class).single();
  }

  private static long views(String type, long id) {
    return jdbc.sql("SELECT count(*) FROM entity_view WHERE entity_type = :t AND entity_id = :id")
        .param("t", type)
        .param("id", id)
        .query(Long.class)
        .single();
  }

  private static long viewsBy(UUID install) {
    return jdbc.sql("SELECT count(*) FROM entity_view WHERE install_id = :i")
        .param("i", install)
        .query(Long.class)
        .single();
  }

  private static long maxId(String table) {
    return jdbc.sql("SELECT COALESCE(max(id), 0) FROM " + table).query(Long.class).single();
  }

  private static UUID newUser() {
    return jdbc.sql("INSERT INTO app_user (id) VALUES (gen_random_uuid()) RETURNING id")
        .query(UUID.class)
        .single();
  }

  @Test
  @DisplayName("상세 조회를 적는다 — 대상·기기·언어·한국 날짜")
  void recordsView() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID install = UUID.randomUUID();

          assertThat(store.recordView(UsageEventStore.Entity.CONTENT, content, install, null, "ja"))
              .isTrue();

          var row =
              jdbc.sql(
                      "SELECT entity_type, entity_id, user_id, lang::text AS lang, day FROM"
                          + " entity_view WHERE install_id = :i")
                  .param("i", install)
                  .query()
                  .singleRow();
          assertThat(row.get("entity_type")).isEqualTo("content");
          assertThat(((Number) row.get("entity_id")).longValue()).isEqualTo(content);
          assertThat(row.get("user_id")).as("비회원은 사용자 없이").isNull();
          assertThat(row.get("lang")).isEqualTo("ja");
          // 「같은 날」 은 한국 날짜다(계획 §6 `day`, 저장소 설명).
          assertThat(((java.sql.Date) row.get("day")).toLocalDate()).isEqualTo(LocalDate.now(KST));
          return null;
        });
  }

  @Test
  @DisplayName("촬영지·편의시설 조회도 적는다")
  void recordsPlaceAndPoiViews() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID install = UUID.randomUUID();
          long placeBefore = views("place", place);
          long poiBefore = views("poi", poi);

          assertThat(store.recordView(UsageEventStore.Entity.PLACE, place, install, null, "ko"))
              .isTrue();
          assertThat(store.recordView(UsageEventStore.Entity.POI, poi, install, null, "ko"))
              .isTrue();

          assertThat(views("place", place)).isEqualTo(placeBefore + 1);
          assertThat(views("poi", poi)).isEqualTo(poiBefore + 1);
          return null;
        });
  }

  @Test
  @DisplayName("가입 사용자의 조회는 사용자를 함께 적는다")
  void recordsSignedInUser() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID user = newUser();
          UUID install = UUID.randomUUID();

          store.recordView(UsageEventStore.Entity.PLACE, place, install, user, "en");

          UUID stored =
              jdbc.sql("SELECT user_id FROM entity_view WHERE install_id = :i")
                  .param("i", install)
                  .query(UUID.class)
                  .single();
          assertThat(stored).isEqualTo(user);
          return null;
        });
  }

  @Test
  @DisplayName("같은 기기·같은 대상·같은 날은 한 번 — 두 번째는 조용히 버린다")
  void sameDeviceSameTargetSameDayOnce() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID install = UUID.randomUUID();

          assertThat(store.recordView(UsageEventStore.Entity.CONTENT, content, install, null, "ko"))
              .isTrue();
          assertThat(store.recordView(UsageEventStore.Entity.CONTENT, content, install, null, "ko"))
              .isFalse();
          // 언어가 달라도 같은 기기·같은 대상·같은 날이다.
          assertThat(store.recordView(UsageEventStore.Entity.CONTENT, content, install, null, "en"))
              .isFalse();

          assertThat(viewsBy(install)).isEqualTo(1);
          return null;
        });
  }

  @Test
  @DisplayName("다른 기기는 따로 센다")
  void otherDeviceCountsSeparately() {
    IntegrationDatabase.rolledBack(
        () -> {
          long before = views("content", content);

          store.recordView(UsageEventStore.Entity.CONTENT, content, UUID.randomUUID(), null, "ko");
          store.recordView(UsageEventStore.Entity.CONTENT, content, UUID.randomUUID(), null, "ko");

          assertThat(views("content", content)).isEqualTo(before + 2);
          return null;
        });
  }

  @Test
  @DisplayName("같은 기기라도 다른 대상은 따로 센다 — 종류가 달라도")
  void otherTargetCountsSeparately() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID install = UUID.randomUUID();

          assertThat(store.recordView(UsageEventStore.Entity.CONTENT, content, install, null, "ko"))
              .isTrue();
          assertThat(
                  store.recordView(
                      UsageEventStore.Entity.CONTENT, otherContent, install, null, "ko"))
              .isTrue();
          assertThat(store.recordView(UsageEventStore.Entity.PLACE, place, install, null, "ko"))
              .isTrue();

          assertThat(viewsBy(install)).isEqualTo(3);
          return null;
        });
  }

  @Test
  @DisplayName("같은 기기·같은 대상이라도 어제 본 것과 오늘 본 것은 따로다")
  void otherDayCountsSeparately() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID install = UUID.randomUUID();
          jdbc.sql(
                  """
                  INSERT INTO entity_view (entity_type, entity_id, install_id, lang, day)
                  VALUES ('content', :id, :i, 'ko', (now() AT TIME ZONE 'Asia/Seoul')::date - 1)
                  """)
              .param("id", content)
              .param("i", install)
              .update();

          assertThat(store.recordView(UsageEventStore.Entity.CONTENT, content, install, null, "ko"))
              .isTrue();

          assertThat(viewsBy(install)).isEqualTo(2);
          return null;
        });
  }

  @Test
  @DisplayName("없는 대상 id 는 적지 않는다 — 작품·촬영지·편의시설 모두")
  void unknownTargetIsNotRecorded() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID install = UUID.randomUUID();
          long missingContent = maxId("content") + 1_000_000;
          long missingPlace = maxId("place") + 1_000_000;
          long missingPoi = maxId("poi") + 1_000_000;

          assertThat(
                  store.recordView(
                      UsageEventStore.Entity.CONTENT, missingContent, install, null, "ko"))
              .isFalse();
          assertThat(
                  store.recordView(UsageEventStore.Entity.PLACE, missingPlace, install, null, "ko"))
              .isFalse();
          assertThat(store.recordView(UsageEventStore.Entity.POI, missingPoi, install, null, "ko"))
              .isFalse();

          assertThat(viewsBy(install)).isZero();
          return null;
        });
  }

  @Test
  @DisplayName("다른 종류의 id 로는 적지 않는다 — 작품 id 를 편의시설로 보내면 그 편의시설이 있어야 한다")
  void idIsCheckedAgainstItsOwnKind() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID install = UUID.randomUUID();
          // 작품에는 있지만 촬영지에는 없는 id
          Long contentOnly =
              jdbc.sql(
                      "SELECT c.id FROM content c WHERE NOT EXISTS (SELECT 1 FROM place p WHERE"
                          + " p.id = c.id) ORDER BY c.id LIMIT 1")
                  .query(Long.class)
                  .optional()
                  .orElse(null);
          if (contentOnly == null) {
            return null; // 적재 데이터가 우연히 겹치면 이 확인은 뜻이 없다
          }

          assertThat(
                  store.recordView(UsageEventStore.Entity.PLACE, contentOnly, install, null, "ko"))
              .isFalse();
          assertThat(viewsBy(install)).isZero();
          return null;
        });
  }

  @Test
  @DisplayName("확정한 검색을 적는다 — 검색어·결과 수·언어, 기기·사용자 없이")
  void recordsSearch() {
    IntegrationDatabase.rolledBack(
        () -> {
          long before = maxId("search_log");

          store.recordSearch("도깨비 촬영지", 0, "zh-Hant");

          var row =
              jdbc.sql(
                      "SELECT query, result_count, lang::text AS lang FROM search_log WHERE id >"
                          + " :b")
                  .param("b", before)
                  .query()
                  .singleRow();
          assertThat(row.get("query")).isEqualTo("도깨비 촬영지");
          assertThat(((Number) row.get("result_count")).intValue()).isZero();
          assertThat(row.get("lang")).isEqualTo("zh-Hant");
          return null;
        });
  }

  @Test
  @DisplayName("search_log 에는 기기·사용자를 담을 칸이 없다 — 검색어는 기기와 잇지 않는다")
  void searchLogHasNoDeviceColumns() {
    List<String> columns =
        jdbc.sql(
                "SELECT column_name FROM information_schema.columns"
                    + " WHERE table_schema = current_schema() AND table_name = 'search_log'")
            .query(String.class)
            .list();

    assertThat(columns).contains("query", "result_count", "lang", "created_at");
    assertThat(columns).doesNotContain("install_id", "user_id", "device_id");
  }
}
