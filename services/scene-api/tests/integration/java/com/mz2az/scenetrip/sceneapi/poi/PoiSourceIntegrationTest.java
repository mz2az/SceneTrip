package com.mz2az.scenetrip.sceneapi.poi;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 번호 기록 표(poi_source, V38)와 처음 적재 때 합쳐진 번호 넣기({@code seed/poi_source_merged.sql}) —
 * docs/project/plans/poi-load-cleanup.md §3·§8 2단계 (MZ2AZ-491).
 *
 * <p>마이그레이션 채움은 다시 돌릴 수 없으므로 DB 전체의 불변식으로 본다 — 모든 편의시설의 대표 번호가 기록돼 있고, 열린 편의시설의 대표 번호는 자기를 가리키며,
 * 갈래가 번호 모양과 맞고, 영문 상세는 {@code tour-en-<번호>} 짝으로 기록돼 있다. 합쳐진 번호 넣기는 psql 이 돌리는 바로 그 파일을 메타 명령만 걷어
 * JDBC 로 돌리고, {@code \copy} 가 채울 입력 표는 시험이 채운다. 픽스처 번호는 {@code it-src-} 꼴로만 만들고 트랜잭션은 끝나면 되돌린다. 명세만
 * 보고 쓴 시험.
 */
@DisplayName("번호 기록 — 마이그레이션 채움 불변식 · 합쳐진 번호 넣기 (실제 DB)")
class PoiSourceIntegrationTest {
  private static final Path MERGED_SQL = Path.of("services/scene-api/seed/poi_source_merged.sql");
  private static final Path MERGED_TSV = Path.of("services/scene-api/seed/poi_merged_2026-06.tsv");

  private static JdbcClient jdbc;
  private static String beforeCopy;
  private static String afterCopy;

  private final String tag = UUID.randomUUID().toString().substring(0, 8);

  @BeforeAll
  static void load() throws IOException {
    jdbc = IntegrationDatabase.jdbcClient();
    String sql = Files.readString(MERGED_SQL);
    int copy = sql.indexOf("\n\\copy t_merged");
    assertThat(copy).as("합쳐진 번호 SQL 에 입력 \\copy 가 있어야 한다").isPositive();
    beforeCopy = plain(sql.substring(0, copy));
    afterCopy = plain(sql.substring(copy + 1));
  }

  /** psql 메타 명령과 파일의 트랜잭션 문장을 걷어 낸다 — 시험이 트랜잭션을 쥔다. */
  private static String plain(String sql) {
    List<String> kept = new ArrayList<>();
    for (String line : sql.split("\n", -1)) {
      String t = line.strip();
      if (t.startsWith("\\")
          || t.equals("BEGIN;")
          || t.equals("COMMIT;")
          || t.equals("ROLLBACK;")) {
        continue;
      }
      kept.add(line);
    }
    return String.join("\n", kept);
  }

  // ── 마이그레이션 채움 (DB 전체 불변식) ─────────────────────────────────────────

  @Test
  @DisplayName("모든 편의시설의 대표 번호가 기록돼 있다 — 열린 편의시설은 자기를 가리킨다")
  void everyRepresentativeNumberIsRecorded() {
    IntegrationDatabase.requirePoiSeeded(jdbc);
    long unrecorded =
        jdbc.sql(
                """
                SELECT count(*) FROM poi p
                WHERE NOT EXISTS (SELECT 1 FROM poi_source s WHERE s.source_id = p.source_id)
                """)
            .query(Long.class)
            .single();
    long openPointingElsewhere =
        jdbc.sql(
                """
                SELECT count(*) FROM poi p
                JOIN poi_source s ON s.source_id = p.source_id
                WHERE p.closed_at IS NULL AND s.poi_id <> p.id
                """)
            .query(Long.class)
            .single();
    assertThat(unrecorded).as("기록이 없는 대표 번호").isZero();
    assertThat(openPointingElsewhere).as("다른 편의시설을 가리키는 열린 편의시설의 대표 번호").isZero();
  }

  @Test
  @DisplayName("갈래가 번호 모양과 맞다 — MA… 은 store, tour-en-… 은 tour_en, 나머지 tour-… 은 tour")
  void sourceMatchesNumberShape() {
    IntegrationDatabase.requirePoiSeeded(jdbc);
    long wrong =
        jdbc.sql(
                """
                SELECT count(*) FROM poi_source
                WHERE (source_id LIKE 'MA%') <> (source = 'store')
                   OR (source_id LIKE 'tour-en-%') <> (source = 'tour_en')
                   OR (source_id LIKE 'tour-%' AND source_id NOT LIKE 'tour-en-%') <> (source = 'tour')
                """)
            .query(Long.class)
            .single();
    assertThat(wrong).isZero();
  }

  @Test
  @DisplayName("열린 편의시설의 영문 상세는 tour-en-<번호> 로 그 편의시설에 기록돼 있다")
  void englishDetailsArePaired() {
    long unpaired =
        jdbc.sql(
                """
                SELECT count(*) FROM poi_detail d
                JOIN poi p ON p.id = d.poi_id AND p.closed_at IS NULL
                WHERE d.lang = 'en'
                  AND NOT EXISTS (
                      SELECT 1 FROM poi_source s
                      WHERE s.source_id = 'tour-en-' || d.tour_content_id
                        AND s.poi_id = d.poi_id AND s.source = 'tour_en')
                """)
            .query(Long.class)
            .single();
    assertThat(unpaired).isZero();
  }

  // ── 합쳐진 번호 파일 ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("합쳐진 번호 파일 — 22,818 줄, 번호는 한 번씩, 자기 자신·이어진 대상(사슬)이 없다")
  void mergedFileIsWellFormed() throws IOException {
    List<String> lines = Files.readAllLines(MERGED_TSV);
    assertThat(lines.get(0)).isEqualTo("source_id\tmerged_into");
    Set<String> sources = new HashSet<>();
    Set<String> targets = new HashSet<>();
    for (String line : lines.subList(1, lines.size())) {
      String[] f = line.split("\t", -1);
      assertThat(f).as(line).hasSize(2);
      assertThat(f[0]).as(line).isNotBlank().isNotEqualTo(f[1]);
      assertThat(f[1]).as(line).isNotBlank();
      assertThat(sources.add(f[0])).as("번호가 두 번: " + f[0]).isTrue();
      targets.add(f[1]);
    }
    assertThat(sources).hasSize(22_818);
    // 대상을 한 번에 번호로 찾으므로, 대상이 다시 합쳐진 번호면 그 줄은 이번에 못 찾는다
    targets.retainAll(sources);
    assertThat(targets).as("다른 줄에서 또 합쳐진 대상").isEmpty();
  }

  // ── 합쳐진 번호 넣기 ──────────────────────────────────────────────────────────

  private void runMerged(String... pairs) {
    IntegrationDatabase.execute("DROP TABLE IF EXISTS pg_temp.t_merged, pg_temp.t_merged_done;");
    IntegrationDatabase.execute(beforeCopy);
    for (int i = 0; i < pairs.length; i += 2) {
      jdbc.sql("INSERT INTO t_merged (source_id, merged_into) VALUES (:s, :m)")
          .param("s", pairs[i])
          .param("m", pairs[i + 1])
          .update();
    }
    IntegrationDatabase.execute(afterCopy);
  }

  /** 대표 번호와 그 기록을 가진 편의시설. */
  private long poi(String sourceId, String source) {
    long id =
        jdbc.sql(
                """
                INSERT INTO poi (source_id, name, geom, category, category_group)
                VALUES (:s, :s, ST_SetSRID(ST_MakePoint(124.2, 33.4), 4326)::geography, '시험', 'food')
                RETURNING id
                """)
            .param("s", sourceId)
            .query(Long.class)
            .single();
    record(sourceId, id, source);
    return id;
  }

  private static void record(String sourceId, long poiId, String source) {
    jdbc.sql("INSERT INTO poi_source (source_id, poi_id, source) VALUES (:s, :p, :src)")
        .param("s", sourceId)
        .param("p", poiId)
        .param("src", source)
        .update();
  }

  /** 기록 한 줄 — 「poi_id|source|active」, 없으면 null. */
  private static String recordOf(String sourceId) {
    return jdbc.sql(
            "SELECT poi_id || '|' || source || '|' || active FROM poi_source WHERE source_id = :s")
        .param("s", sourceId)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  private List<Map<String, Object>> fixtureRecords() {
    return jdbc.sql(
            """
            SELECT source_id, poi_id, source, active, updated_at FROM poi_source
            WHERE source_id LIKE '%' || :t || '%' ORDER BY source_id
            """)
        .param("t", tag)
        .query()
        .listOfRows();
  }

  @Test
  @DisplayName("합쳐진 번호를 대상 번호의 편의시설에 기록한다 — 갈래는 번호 모양대로, 대상이 없는 줄은 넣지 않는다")
  void recordsMergedNumbersOnTarget() {
    IntegrationDatabase.rolledBack(
        () -> {
          String target = "MAit-src-" + tag + "-T";
          long t = poi(target, "store");
          String tour = "tour-it-src-" + tag + "-1";
          String store = "MAit-src-" + tag + "-2";
          String bus = "busstop-it-src-" + tag + "-3";
          String orphan = "tour-it-src-" + tag + "-4";

          runMerged(tour, target, store, target, bus, target, orphan, "MAit-src-" + tag + "-없음");

          assertThat(recordOf(tour)).isEqualTo(t + "|tour|true");
          assertThat(recordOf(store)).isEqualTo(t + "|store|true");
          assertThat(recordOf(bus)).isEqualTo(t + "|transit|true");
          assertThat(recordOf(orphan)).isNull();
          // 편의시설은 만들지 않는다
          assertThat(
                  jdbc.sql("SELECT count(*) FROM poi WHERE source_id LIKE '%' || :t || '%'")
                      .param("t", tag)
                      .query(Long.class)
                      .single())
              .isEqualTo(1L);
          return null;
        });
  }

  @Test
  @DisplayName("대상은 기록으로 찾는다 — 대상 번호가 대표 번호가 아니라 이미 기록된 다른 번호여도 그 편의시설로")
  void findsTargetThroughRecordNotRepresentative() {
    IntegrationDatabase.rolledBack(
        () -> {
          long t = poi("MAit-src-" + tag + "-T", "store");
          String alias = "tour-it-src-" + tag + "-alias";
          record(alias, t, "tour");
          String merged = "MAit-src-" + tag + "-m";

          runMerged(merged, alias);

          assertThat(recordOf(merged)).isEqualTo(t + "|store|true");
          return null;
        });
  }

  @Test
  @DisplayName("이미 기록된 번호는 그대로 — 다른 편의시설로 옮기지 않는다")
  void keepsExistingRecord() {
    IntegrationDatabase.rolledBack(
        () -> {
          long t = poi("MAit-src-" + tag + "-T", "store");
          long other = poi("MAit-src-" + tag + "-O", "store");
          String already = "tour-it-src-" + tag + "-a";
          record(already, other, "tour");

          runMerged(already, "MAit-src-" + tag + "-T");

          assertThat(recordOf(already)).isEqualTo(other + "|tour|true");
          assertThat(t).isNotEqualTo(other);
          return null;
        });
  }

  @Test
  @DisplayName("몇 번을 돌려도 같다 — 두 번째는 아무 기록도 바꾸지 않는다")
  void rerunChangesNothing() {
    IntegrationDatabase.rolledBack(
        () -> {
          String target = "MAit-src-" + tag + "-T";
          poi(target, "store");
          String[] input = {
            "tour-it-src-" + tag + "-1", target,
            "MAit-src-" + tag + "-2", target,
            "tour-it-src-" + tag + "-x", "MAit-src-" + tag + "-없음"
          };

          runMerged(input);
          List<Map<String, Object>> first = fixtureRecords();
          runMerged(input);

          assertThat(first).hasSize(3);
          assertThat(fixtureRecords()).isEqualTo(first);
          return null;
        });
  }
}
