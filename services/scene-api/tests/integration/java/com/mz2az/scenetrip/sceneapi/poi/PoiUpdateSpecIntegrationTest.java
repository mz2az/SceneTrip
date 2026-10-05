package com.mz2az.scenetrip.sceneapi.poi;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 분기 갱신 합치기 SQL({@code seed/poi_update.sql})을 <b>명세만 보고</b> 검증한다 —
 * docs/project/plans/poi-i18n-image.md §8. 구현과 구현자의 시험은 보지 않았다.
 *
 * <p>픽스처 출처 id 는 {@code spec-}·{@code specother-}·{@code specnone-} 로 시작한다. 명세상 갱신은 입력에 있는 출처만 보므로
 * 적재 데이터는 건드리지 않고, 모든 것은 되돌리는 트랜잭션 안에서 돈다. 좌표는 바다 위(제주 남서쪽) — 실제 POI 가 없다.
 */
@DisplayName("poi_update.sql — 명세(§8)로 쓴 시험")
class PoiUpdateSpecIntegrationTest {
  private static final double LNG = 125.9;
  private static final double LAT = 32.9;

  private static JdbcClient jdbc;
  private static String updateSql;

  @BeforeAll
  static void setUp() throws IOException {
    jdbc = IntegrationDatabase.jdbcClient();
    updateSql = Files.readString(sqlPath(), StandardCharsets.UTF_8);
  }

  private static Path sqlPath() {
    Path relative = Path.of("services/scene-api/seed/poi_update.sql");
    if (Files.exists(relative)) {
      return relative;
    }
    String srcdir = System.getenv("TEST_SRCDIR");
    return Path.of(srcdir, "_main", relative.toString());
  }

  /** 한 POI 의 갱신 뒤 상태. */
  private record State(String sourceId, boolean closed) {}

  /** 픽스처를 만들고 SQL 을 돌린 뒤 이름 붙인 행들의 상태를 돌려준다. */
  private static final class Scenario {
    final Map<String, Long> ids = new HashMap<>();

    /** 기존 DB 의 행. {@code north} 미터만큼 기준점에서 북쪽. */
    void old(String key, String sourceId, String name, String category, double north) {
      old(key, sourceId, name, category, north, false);
    }

    void old(
        String key, String sourceId, String name, String category, double north, boolean closed) {
      long id =
          jdbc.sql(
                  "INSERT INTO poi (source_id, name, geom, category, category_group, closed_at)"
                      + " VALUES (:s, :n, ST_Project(ST_SetSRID(ST_MakePoint(:lng, :lat),"
                      + " 4326)::geography, :north, 0), :c, 'food', "
                      + (closed ? "TIMESTAMPTZ '2026-01-01 00:00+00'" : "NULL")
                      + ") RETURNING id")
              .param("s", sourceId)
              .param("n", name)
              .param("lng", LNG)
              .param("lat", LAT)
              .param("north", north)
              .param("c", category)
              .query(Long.class)
              .single();
      ids.put(key, id);
    }

    /** 새 판의 행(t_load). */
    void load(String sourceId, String name, String category, double north) {
      jdbc.sql(
              "INSERT INTO t_load (source_id, name, lat, lng, category)"
                  + " SELECT :s, :n, ST_Y(p::geometry), ST_X(p::geometry), :c FROM"
                  + " (SELECT ST_Project(ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,"
                  + " :north, 0) AS p) q")
          .param("s", sourceId)
          .param("n", name)
          .param("lng", LNG)
          .param("lat", LAT)
          .param("north", north)
          .param("c", category)
          .update();
    }
  }

  private record Result(Map<String, State> states, List<String> allSourceIds) {}

  private static Result run(Consumer<Scenario> build) {
    return IntegrationDatabase.rolledBack(
        () -> {
          IntegrationDatabase.execute(
              "CREATE TEMP TABLE t_load (source_id TEXT, name TEXT, lat DOUBLE PRECISION,"
                  + " lng DOUBLE PRECISION, category TEXT) ON COMMIT DROP");
          Scenario s = new Scenario();
          build.accept(s);
          IntegrationDatabase.execute(updateSql);
          Map<String, State> states = new HashMap<>();
          s.ids.forEach(
              (key, id) ->
                  states.put(
                      key,
                      jdbc.sql(
                              "SELECT source_id, closed_at IS NOT NULL AS closed FROM poi WHERE id"
                                  + " = :id")
                          .param("id", id)
                          .query(
                              (rs, n) ->
                                  new State(rs.getString("source_id"), rs.getBoolean("closed")))
                          .single()));
          List<String> all =
              jdbc.sql(
                      "SELECT source_id FROM poi WHERE source_id ~ '^spec(other|none)?-' ORDER BY"
                          + " source_id")
                  .query(String.class)
                  .list();
          return new Result(states, all);
        });
  }

  private static State linked(String sourceId) {
    return new State(sourceId, false);
  }

  private static State closed(String sourceId) {
    return new State(sourceId, true);
  }

  // ── §8-1 같은 source_id ────────────────────────────────────────────────

  @Test
  @DisplayName("같은 source_id 가 새 판에 있으면 그대로 두고 폐업 표시하지 않는다")
  void sameSourceIdKept() {
    Result r =
        run(
            s -> {
              s.old("a", "spec-1", "가나식당", "한식", 0);
              s.load("spec-1", "가나식당", "한식", 0);
            });
    assertThat(r.states().get("a")).isEqualTo(linked("spec-1"));
  }

  @Test
  @DisplayName("새 판에 있는 행은 근처의 새 번호로 갈아 끼우지 않는다")
  void presentRowIsNotRelinked() {
    Result r =
        run(
            s -> {
              s.old("a", "spec-1", "파하식당", "한식", 0);
              s.load("spec-1", "파하식당", "한식", 0);
              s.load("spec-2", "파하식당", "한식", 5);
            });
    assertThat(r.states().get("a")).isEqualTo(linked("spec-1"));
  }

  @Test
  @DisplayName("이미 DB 에 있고 새 판에도 있는 번호는 잇기 대상이 아니다")
  void existingIdIsNotALinkTarget() {
    Result r =
        run(
            s -> {
              s.old("gone", "spec-1", "카타식당", "한식", 0);
              s.old("stay", "spec-2", "카타식당", "한식", 5);
              s.load("spec-2", "카타식당", "한식", 5);
            });
    assertThat(r.states().get("gone")).isEqualTo(closed("spec-1"));
    assertThat(r.states().get("stay")).isEqualTo(linked("spec-2"));
  }

  // ── §8-2 규칙 1: 이름이 같다(분류 상관없음) ──────────────────────────────

  @Test
  @DisplayName("이름이 같으면 분류가 달라도 잇는다 — poi.id 는 그대로, source_id 만 바뀐다")
  void sameNameAnyCategory() {
    Result r =
        run(
            s -> {
              s.old("a", "spec-1", "황금포차", "한식", 0);
              s.load("spec-2", "황금포차", "요리 주점", 10);
            });
    assertThat(r.states().get("a")).isEqualTo(linked("spec-2"));
  }

  @Test
  @DisplayName("이름 비교는 소문자로, 공백·괄호를 지우고 한다")
  void normalizationSpacesParens() {
    Result r =
        run(
            s -> {
              s.old("a", "spec-1", "Cafe (Blue) Bird", "카페", 0);
              s.load("spec-2", "cafebluebird", "한식", 10);
            });
    assertThat(r.states().get("a")).isEqualTo(linked("spec-2"));
  }

  @Test
  @DisplayName("이름 비교는 ·.,&_/- 를 지우고 한다")
  void normalizationPunctuation() {
    Result r =
        run(
            s -> {
              s.old("a", "spec-1", "A·B.C,D&E_F/G-H", "카페", 0);
              s.load("spec-2", "abcdefgh", "한식", 10);
            });
    assertThat(r.states().get("a")).isEqualTo(linked("spec-2"));
  }

  // ── §8-2 거리 30 m ──────────────────────────────────────────────────

  @Test
  @DisplayName("30 m 안(29.5 m)이면 잇는다")
  void within30m() {
    Result r =
        run(
            s -> {
              s.old("a", "spec-1", "거리식당", "한식", 0);
              s.load("spec-2", "거리식당", "한식", 29.5);
            });
    assertThat(r.states().get("a")).isEqualTo(linked("spec-2"));
  }

  @Test
  @DisplayName("30 m 밖(30.5 m)이면 잇지 않고 폐업 표시한다")
  void beyond30m() {
    Result r =
        run(
            s -> {
              s.old("a", "spec-1", "거리식당", "한식", 0);
              s.load("spec-2", "거리식당", "한식", 30.5);
            });
    assertThat(r.states().get("a")).isEqualTo(closed("spec-1"));
  }

  // ── §8-2 규칙 2: 포함, 짧은 쪽 3 글자 이상, 같은 분류 ─────────────────────

  @Test
  @DisplayName("포함 — 구리갈매역 → 스타벅스 구리갈매역 (같은 분류) 은 잇는다")
  void containment() {
    Result r =
        run(
            s -> {
              s.old("a", "spec-1", "구리갈매역", "카페", 0);
              s.load("spec-2", "스타벅스 구리갈매역", "카페", 10);
            });
    assertThat(r.states().get("a")).isEqualTo(linked("spec-2"));
  }

  @Test
  @DisplayName("포함 — 짧은 쪽이 정확히 3 글자면 잇는다")
  void containmentThreeChars() {
    Result r =
        run(
            s -> {
              s.old("a", "spec-1", "갈매역", "카페", 0);
              s.load("spec-2", "스타벅스갈매역", "카페", 10);
            });
    assertThat(r.states().get("a")).isEqualTo(linked("spec-2"));
  }

  @Test
  @DisplayName("포함 — 짧은 쪽이 2 글자면 잇지 않는다")
  void containmentTwoChars() {
    Result r =
        run(
            s -> {
              s.old("a", "spec-1", "갈매", "카페", 0);
              s.load("spec-2", "스타벅스갈매", "카페", 10);
            });
    assertThat(r.states().get("a")).isEqualTo(closed("spec-1"));
  }

  @Test
  @DisplayName("포함 — 분류가 다르면 잇지 않는다")
  void containmentDifferentCategory() {
    Result r =
        run(
            s -> {
              s.old("a", "spec-1", "구리갈매역", "카페", 0);
              s.load("spec-2", "스타벅스 구리갈매역", "한식", 10);
            });
    assertThat(r.states().get("a")).isEqualTo(closed("spec-1"));
  }

  // ── §8-2 규칙 3: 편집 거리 비율 ≥ 0.8, 같은 분류 ─────────────────────────

  @Test
  @DisplayName("비슷함 — 피자스쿨신풍점 → 피자스쿨신풍역점 (0.875) 은 잇는다")
  void similar() {
    Result r =
        run(
            s -> {
              s.old("a", "spec-1", "피자스쿨신풍점", "피자", 0);
              s.load("spec-2", "피자스쿨신풍역점", "피자", 10);
            });
    assertThat(r.states().get("a")).isEqualTo(linked("spec-2"));
  }

  @Test
  @DisplayName("비슷함 — 비율이 정확히 0.8 (5 글자 중 1 글자 다름) 이면 잇는다")
  void similarBoundary() {
    Result r =
        run(
            s -> {
              s.old("a", "spec-1", "가나다라마", "한식", 0);
              s.load("spec-2", "가나다라바", "한식", 10);
            });
    assertThat(r.states().get("a")).isEqualTo(linked("spec-2"));
  }

  @Test
  @DisplayName("비슷함 — 둘둘치킨망양점 → 자담치킨망양점 (0.71) 은 잇지 않는다")
  void notSimilarEnough() {
    Result r =
        run(
            s -> {
              s.old("a", "spec-1", "둘둘치킨망양점", "치킨", 0);
              s.load("spec-2", "자담치킨망양점", "치킨", 10);
            });
    assertThat(r.states().get("a")).isEqualTo(closed("spec-1"));
  }

  @Test
  @DisplayName("비슷함 — 분류가 다르면 잇지 않는다")
  void similarDifferentCategory() {
    Result r =
        run(
            s -> {
              s.old("a", "spec-1", "피자스쿨신풍점", "피자", 0);
              s.load("spec-2", "피자스쿨신풍역점", "양식", 10);
            });
    assertThat(r.states().get("a")).isEqualTo(closed("spec-1"));
  }

  @Test
  @DisplayName("이름이 전혀 다르면 폐업 표시한다")
  void unrelatedName() {
    Result r =
        run(
            s -> {
              s.old("a", "spec-1", "할머니순대국밥", "한식", 0);
              s.load("spec-2", "스타벅스", "한식", 3);
            });
    assertThat(r.states().get("a")).isEqualTo(closed("spec-1"));
  }

  // ── §8-2 후보가 여럿: 규칙 순서 → 가까운 순, 서로 최선인 쌍만 ─────────────────

  @Test
  @DisplayName("옛 가게 둘·새 번호 하나 — 더 가까운 쪽만 잇고 다른 쪽은 폐업")
  void oneToOneNearestOld() {
    Result r =
        run(
            s -> {
              s.old("far", "spec-1", "가나식당", "한식", 0);
              s.old("near", "spec-2", "가나식당", "한식", 20);
              s.load("spec-3", "가나식당", "한식", 15);
            });
    assertThat(r.states().get("near")).isEqualTo(linked("spec-3"));
    assertThat(r.states().get("far")).isEqualTo(closed("spec-1"));
  }

  @Test
  @DisplayName("옛 가게 하나·새 번호 둘 — 더 가까운 새 번호로 잇는다")
  void oneToOneNearestNew() {
    Result r =
        run(
            s -> {
              s.old("a", "spec-1", "마바식당", "한식", 0);
              s.load("spec-2", "마바식당", "한식", 20);
              s.load("spec-3", "마바식당", "한식", 5);
            });
    assertThat(r.states().get("a")).isEqualTo(linked("spec-3"));
    assertThat(r.allSourceIds()).doesNotContain("spec-2");
  }

  @Test
  @DisplayName("규칙 순서가 거리보다 먼저 — 20 m 의 같은 이름이 2 m 의 포함을 이긴다")
  void ruleOrderBeforeDistance() {
    Result r =
        run(
            s -> {
              s.old("same", "spec-1", "다라식당", "한식", 20);
              s.old("contains", "spec-2", "다라식당본점", "한식", 2);
              s.load("spec-3", "다라식당", "한식", 0);
            });
    assertThat(r.states().get("same")).isEqualTo(linked("spec-3"));
    assertThat(r.states().get("contains")).isEqualTo(closed("spec-2"));
  }

  // ── §8-2 같은 출처끼리만, 입력에 없는 출처는 건드리지 않는다 ──────────────────

  @Test
  @DisplayName("다른 출처의 새 번호와는 잇지 않는다 — 한 출처 이름이 다른 출처의 앞부분일 때(spec ↔ specother)")
  void otherSourceNotCompared() {
    Result r =
        run(
            s -> {
              s.old("a", "spec-1", "사아식당", "한식", 0);
              s.old("keep", "spec-9", "유지식당", "한식", 500);
              s.load("spec-9", "유지식당", "한식", 500);
              s.load("specother-1", "사아식당", "한식", 5);
            });
    assertThat(r.states().get("a")).isEqualTo(closed("spec-1"));
    assertThat(r.states().get("keep")).isEqualTo(linked("spec-9"));
  }

  @Test
  @DisplayName("다른 출처의 새 번호와는 잇지 않는다 — 출처 이름이 서로의 앞부분이 아닐 때(spec ↔ qother)")
  void otherSourceNotComparedDisjointPrefix() {
    Result r =
        run(
            s -> {
              s.old("a", "spec-1", "사아식당", "한식", 0);
              s.old("keep", "spec-9", "유지식당", "한식", 500);
              s.load("spec-9", "유지식당", "한식", 500);
              s.load("qother-1", "사아식당", "한식", 5);
            });
    assertThat(r.states().get("a")).isEqualTo(closed("spec-1"));
  }

  @Test
  @DisplayName("다른 출처의 새 번호와는 잇지 않는다 — 대문자 출처(SPECA ↔ SPECB, 'MA' 꼴)")
  void otherSourceNotComparedUppercase() {
    Result r =
        run(
            s -> {
              s.old("a", "SPECA0001", "사아식당", "한식", 0);
              s.old("keep", "SPECA0009", "유지식당", "한식", 500);
              s.load("SPECA0009", "유지식당", "한식", 500);
              s.load("SPECB0001", "사아식당", "한식", 5);
            });
    assertThat(r.states().get("a")).isEqualTo(closed("SPECA0001"));
  }

  @Test
  @DisplayName("입력에 없는 출처의 POI 는 폐업도, 잇기도 하지 않는다")
  void absentSourceUntouched() {
    Result r =
        run(
            s -> {
              s.old("untouched", "specnone-1", "자차식당", "한식", 0);
              s.load("spec-5", "자차식당", "한식", 5);
            });
    assertThat(r.states().get("untouched")).isEqualTo(linked("specnone-1"));
  }

  @Test
  @DisplayName("이미 폐업 표시된 가게가 계속 없으면 폐업 그대로다")
  void alreadyClosedStaysClosed() {
    Result r =
        run(
            s -> {
              s.old("a", "spec-1", "닫힌식당", "한식", 0, true);
              s.load("spec-2", "전혀다른곳", "한식", 500);
            });
    assertThat(r.states().get("a")).isEqualTo(closed("spec-1"));
  }
}
