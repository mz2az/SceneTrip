package com.mz2az.scenetrip.sceneapi.poi;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code seed/poi_update.sql} — 분기 갱신의 「같은 가게 잇기」 와 「폐업 표시」(docs/project/plans/poi-i18n-image.md
 * §8).
 *
 * <p>적재가 psql 로 돌리는 바로 그 파일을 JDBC 로 돌린다. 그래서 파일은 psql 메타 명령 없는 순수 SQL 이어야 한다. 픽스처는 출처 {@code upd}
 * 로만 만든다 — 갱신은 입력에 있는 출처의 POI 만 보므로 적재된 95 만 행은 건드리지 않는다. 트랜잭션은 끝나면 되돌린다.
 */
@DisplayName("POI 분기 갱신 — 같은 가게 잇기·폐업 표시")
class PoiUpdateIntegrationTest {
  private static final Path UPDATE_SQL = Path.of("services/scene-api/seed/poi_update.sql");

  private static JdbcClient jdbc;
  private static String updateSql;

  @BeforeAll
  static void connect() throws IOException {
    jdbc = IntegrationDatabase.jdbcClient();
    updateSql = Files.readString(UPDATE_SQL);
  }

  /** 옛 판의 행 하나. 좌표는 위도 37.6 · 경도 127.0 근처, dx 미터만큼 동쪽. */
  private record Old(String sourceId, String name, String category, double dxMeters) {}

  /** 새 판의 행 하나. */
  private record New(String sourceId, String name, String category, double dxMeters) {}

  /** 갱신을 돌린 뒤 옛 행마다 (지금 source_id, 폐업 여부). 키는 옛 source_id. */
  private static Map<String, String> run(Old[] olds, New[] news) {
    return IntegrationDatabase.rolledBack(
        () -> {
          Map<String, Long> ids = new LinkedHashMap<>();
          for (Old o : olds) {
            ids.put(
                o.sourceId(),
                jdbc.sql(
                        """
                        INSERT INTO poi (source_id, name, geom, category, category_group)
                        VALUES (:s, :n, ST_SetSRID(ST_MakePoint(:lng, 37.6), 4326)::geography, :c, 'food')
                        RETURNING id
                        """)
                    .param("s", o.sourceId())
                    .param("n", o.name())
                    .param("lng", 127.0 + o.dxMeters() / 88_000.0)
                    .param("c", o.category())
                    .query(Long.class)
                    .single());
          }
          IntegrationDatabase.execute(
              """
              CREATE TEMP TABLE t_load (
                  source_id TEXT, name TEXT, lat DOUBLE PRECISION, lng DOUBLE PRECISION, category TEXT
              ) ON COMMIT DROP
              """);
          for (New n : news) {
            jdbc.sql("INSERT INTO t_load VALUES (:s, :n, 37.6, :lng, :c)")
                .param("s", n.sourceId())
                .param("n", n.name())
                .param("lng", 127.0 + n.dxMeters() / 88_000.0)
                .param("c", n.category())
                .update();
          }
          IntegrationDatabase.execute(updateSql);
          Map<String, String> out = new LinkedHashMap<>();
          ids.forEach(
              (oldSource, id) ->
                  out.put(
                      oldSource,
                      jdbc.sql(
                              "SELECT source_id || CASE WHEN closed_at IS NULL THEN '' ELSE ' 폐업'"
                                  + " END FROM poi WHERE id = :id")
                          .param("id", id)
                          .query(String.class)
                          .single()));
          return out;
        });
  }

  @Test
  @DisplayName("같은 번호가 새 판에 있으면 그대로 — 잇지도 닫지도 않는다")
  void sameSourceIdIsUntouched() {
    var out =
        run(
            new Old[] {new Old("upd-1", "동네김밥", "분식", 0)},
            new New[] {new New("upd-1", "동네김밥", "분식", 0)});
    assertThat(out).containsEntry("upd-1", "upd-1");
  }

  @Test
  @DisplayName("이름이 같으면 분류가 바뀌어도 잇는다 — poi.id 는 그대로, 번호만 새것")
  void sameNameLinksAcrossCategory() {
    var out =
        run(
            new Old[] {new Old("upd-1", "황금포차", "백반/한정식", 0)},
            new New[] {new New("upd-2", "황금포차", "요리 주점", 5)});
    assertThat(out).containsEntry("upd-1", "upd-2");
  }

  @Test
  @DisplayName("한쪽 이름이 다른 쪽에 들어 있고 분류가 같으면 잇는다")
  void containedNameLinks() {
    var out =
        run(
            new Old[] {new Old("upd-1", "구리갈매역", "카페", 0)},
            new New[] {new New("upd-2", "스타벅스 구리갈매역", "카페", 0)});
    assertThat(out).containsEntry("upd-1", "upd-2");
  }

  @Test
  @DisplayName("편집 거리 비율 0.8 이상이고 분류가 같으면 잇는다")
  void similarNameLinks() {
    var out =
        run(
            new Old[] {new Old("upd-1", "피자스쿨 신풍점", "피자", 0)},
            new New[] {new New("upd-2", "피자스쿨신풍역점", "피자", 0)});
    assertThat(out).containsEntry("upd-1", "upd-2");
  }

  @Test
  @DisplayName("같은 자리라도 이름이 다르면 잇지 않는다 — 옛 가게는 폐업")
  void differentNameIsClosed() {
    var out =
        run(
            new Old[] {new Old("upd-1", "둘둘치킨 망양점", "치킨", 0)},
            new New[] {new New("upd-2", "자담치킨 망양점", "치킨", 0)});
    assertThat(out).containsEntry("upd-1", "upd-1 폐업");
  }

  @Test
  @DisplayName("짧은 쪽이 2 글자 이하인 포함은 잇지 않는다")
  void shortContainedNameIsNotLinked() {
    var out =
        run(
            new Old[] {new Old("upd-1", "기", "카페", 0)},
            new New[] {new New("upd-2", "기웆", "카페", 0)});
    assertThat(out).containsEntry("upd-1", "upd-1 폐업");
  }

  @Test
  @DisplayName("30 m 밖이면 이름이 같아도 잇지 않는다")
  void farAwayIsNotLinked() {
    var out =
        run(
            new Old[] {new Old("upd-1", "동네김밥", "분식", 0)},
            new New[] {new New("upd-2", "동네김밥", "분식", 60)});
    assertThat(out).containsEntry("upd-1", "upd-1 폐업");
  }

  @Test
  @DisplayName("새 번호 하나는 옛 가게 하나에만 — 서로에게 최선인 쌍만 잇는다")
  void oneNewLinksToOneOld() {
    var out =
        run(
            new Old[] {new Old("upd-1", "동네김밥", "분식", 0), new Old("upd-2", "동네김밥", "분식", 10)},
            new New[] {new New("upd-3", "동네김밥", "분식", 0)});
    assertThat(out).containsEntry("upd-1", "upd-3").containsEntry("upd-2", "upd-2 폐업");
  }

  @Test
  @DisplayName("입력에 없는 출처의 POI 는 건드리지 않는다")
  void otherSourceIsUntouched() {
    var out =
        run(
            new Old[] {new Old("upd-1", "동네김밥", "분식", 0), new Old("updother-1", "관광지", "관광지", 500)},
            new New[] {new New("upd-1", "동네김밥", "분식", 0)});
    assertThat(out).containsEntry("updother-1", "updother-1");
  }
}
