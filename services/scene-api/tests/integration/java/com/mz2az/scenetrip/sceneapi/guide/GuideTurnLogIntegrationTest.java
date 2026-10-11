package com.mz2az.scenetrip.sceneapi.guide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.GuideChatReply;
import com.mz2az.scenetrip.sceneapi.api.model.GuideEffect;
import com.mz2az.scenetrip.sceneapi.api.model.GuidePlace;
import com.mz2az.scenetrip.sceneapi.api.model.GuidePlaceSource;
import com.mz2az.scenetrip.sceneapi.api.model.GuideToolUse;
import com.mz2az.scenetrip.sceneapi.api.model.PoiCategoryGroup;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 챗봇 처리 기록(V29 {@code guide_turn}, MZ2AZ-410)을 실제 PostgreSQL 에서 — 무엇이 들어가는지, 무엇이 없는지, 오래된 줄의 정리.
 *
 * <p>공유 로컬 DB 다. 이 시험이 적은 줄은 시작 때의 최대 id 위만 지운다. 정리(purge)는 다른 줄도 지우므로 되돌리는 트랜잭션 안에서만 돌린다.
 */
@DisplayName("GuideTurnLog — 챗봇 처리 기록 (실제 DB)")
class GuideTurnLogIntegrationTest {

  private static JdbcClient jdbc;
  private long watermark;

  /** 한 줄. */
  record Row(
      String lang,
      String outcome,
      List<String> tools,
      Integer placeCount,
      List<String> effects,
      int tookMs,
      boolean recent) {}

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
  }

  @BeforeEach
  void mark() {
    watermark = jdbc.sql("SELECT coalesce(max(id), 0) FROM guide_turn").query(Long.class).single();
  }

  @AfterEach
  void cleanUp() {
    jdbc.sql("DELETE FROM guide_turn WHERE id > :w").param("w", watermark).update();
  }

  private static List<String> strings(ResultSet rs, String column) throws SQLException {
    Array a = rs.getArray(column);
    return a == null ? null : Arrays.asList((String[]) a.getArray());
  }

  private List<Row> mine() {
    return jdbc.sql(
            """
            SELECT lang, outcome, tools, place_count, effects, took_ms,
                   created_at > now() - interval '1 minute' AS recent
            FROM guide_turn WHERE id > :w ORDER BY id
            """)
        .param("w", watermark)
        .query(
            (rs, i) ->
                new Row(
                    rs.getString("lang"),
                    rs.getString("outcome"),
                    strings(rs, "tools"),
                    (Integer) rs.getObject("place_count"),
                    strings(rs, "effects"),
                    rs.getInt("took_ms"),
                    rs.getBoolean("recent")))
        .list();
  }

  private static GuidePlace place(long id) {
    return new GuidePlace(
        id, "곳" + id, "카페", PoiCategoryGroup.SIGHT, 37.5, 127.0, GuidePlaceSource.PLACE);
  }

  @Test
  @DisplayName("답한 턴 — 언어, ok, 도구 이름(쓴 순서), 장소 수, 효과 op, 걸린 시간, 지금 시각")
  void answeredRow() {
    GuideChatReply reply =
        new GuideChatReply(
            "경복궁 근처에 이런 곳이 있어요.",
            List.of(
                new GuideToolUse("search_places", null),
                new GuideToolUse("places_near", null),
                new GuideToolUse("search_places", null)),
            List.of(place(1), place(2), place(3)),
            2.5,
            List.of(new GuideEffect("cart.add"), new GuideEffect("plan.draft")),
            List.of());

    new GuideTurnLog(jdbc, 90).answered("ja", reply, 2_480);

    assertThat(mine())
        .containsExactly(
            new Row(
                "ja",
                "ok",
                List.of("search_places", "places_near", "search_places"),
                3,
                List.of("cart.add", "plan.draft"),
                2_480,
                true));
  }

  @Test
  @DisplayName("못 찾은 답 — 장소 0, 효과 없음(빈 배열)이 그대로 남아 README 의 질의에 걸린다")
  void notFoundCandidate() {
    GuideChatReply reply =
        new GuideChatReply(
            "찾지 못했어요.",
            List.of(new GuideToolUse("places_near", null)),
            List.of(),
            1.0,
            List.of(),
            List.of());

    new GuideTurnLog(jdbc, 90).answered("ko", reply, 900);

    assertThat(mine())
        .containsExactly(new Row("ko", "ok", List.of("places_near"), 0, List.of(), 900, true));
    Integer hits =
        jdbc.sql(
                """
                SELECT count(*) FROM guide_turn
                WHERE id > :w AND outcome = 'ok' AND place_count = 0 AND effects = '{}'
                  AND created_at > now() - interval '7 days'
                """)
            .param("w", watermark)
            .query(Integer.class)
            .single();
    assertThat(hits).isEqualTo(1);
  }

  @Test
  @DisplayName("실패한 턴 — outcome 은 오류 코드, 장소 수 NULL, 도구·효과는 빈 배열")
  void failedRow() {
    new GuideTurnLog(jdbc, 90).failed("zh-Hant", "GUIDE_UNAVAILABLE", 40_001);

    assertThat(mine())
        .containsExactly(
            new Row("zh-Hant", "GUIDE_UNAVAILABLE", List.of(), null, List.of(), 40_001, true));
  }

  @Test
  @DisplayName("걸린 시간은 0 이상 — 음수가 와도 줄은 남는다")
  void tookMsNeverNegative() {
    new GuideTurnLog(jdbc, 90).failed("en", "GUIDE_UNAVAILABLE", -5);

    List<Row> rows = mine();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).tookMs()).isGreaterThanOrEqualTo(0);
  }

  @Test
  @DisplayName("표에는 대화 원문·사용자·기기·멱등 키를 담을 칸이 없다")
  void noColumnForContentOrIdentity() {
    List<String> columns =
        jdbc.sql(
                """
                SELECT column_name FROM information_schema.columns
                WHERE table_name = 'guide_turn' AND table_schema = current_schema()
                ORDER BY ordinal_position
                """)
            .query(String.class)
            .list();

    assertThat(columns)
        .containsExactly(
            "id", "created_at", "lang", "outcome", "tools", "place_count", "effects", "took_ms");
  }

  @Test
  @DisplayName("답의 문장은 어느 칸에도 들어가지 않는다")
  void replyTextIsNotStored() {
    String secret = "제 전화번호는 010-1234-5678 이고 숙소는 서울역 앞이에요";
    GuideChatReply reply =
        new GuideChatReply(
            secret,
            List.of(new GuideToolUse("places_near", null)),
            List.of(),
            1.0,
            List.of(),
            List.of());

    new GuideTurnLog(jdbc, 90).answered("ko", reply, 10);

    String whole =
        jdbc.sql("SELECT string_agg(t::text, ' ') FROM guide_turn t WHERE id > :w")
            .param("w", watermark)
            .query(String.class)
            .single();
    assertThat(whole).isNotNull().doesNotContain("010-1234-5678").doesNotContain("서울역");
  }

  @Test
  @DisplayName("기록이 실패해도(DB 에 못 닿음) 던지지 않는다")
  void unreachableDbDoesNotThrow() {
    DriverManagerDataSource dead =
        new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/nowhere?connectTimeout=1");
    GuideTurnLog log = new GuideTurnLog(JdbcClient.create(dead), 90);
    GuideChatReply reply = new GuideChatReply("답", List.of(), List.of(), 1.0, List.of(), List.of());

    assertThatCode(() -> log.answered("ko", reply, 1)).doesNotThrowAnyException();
    assertThatCode(() -> log.failed("ko", "GUIDE_UNAVAILABLE", 1)).doesNotThrowAnyException();
    assertThatCode(log::purge).doesNotThrowAnyException();
  }

  private void insertAged(String outcome, String age) {
    jdbc.sql(
            "INSERT INTO guide_turn (created_at, lang, outcome, took_ms)"
                + " VALUES (now() - CAST(:age AS INTERVAL), 'ko', :o, 1)")
        .param("age", age)
        .param("o", outcome)
        .update();
  }

  private List<String> remainingOutcomes() {
    return jdbc.sql("SELECT outcome FROM guide_turn WHERE id > :w ORDER BY id")
        .param("w", watermark)
        .query(String.class)
        .list();
  }

  @Test
  @DisplayName("정리 — 보관 기간(90 일)보다 오래된 줄만 지운다 (되돌리는 트랜잭션 안)")
  void purgeDeletesOnlyOlderThanRetention() {
    List<String> left =
        IntegrationDatabase.rolledBack(
            () -> {
              insertAged("old-91d", "91 days");
              insertAged("old-90d1h", "90 days 1 hour");
              insertAged("keep-89d", "89 days");
              insertAged("keep-now", "0 seconds");
              new GuideTurnLog(jdbc, 90).purge();
              return remainingOutcomes();
            });

    assertThat(left).containsExactly("keep-89d", "keep-now");
    assertThat(remainingOutcomes()).isEmpty(); // 되돌렸다
  }

  @Test
  @DisplayName("정리 — 보관 기간은 설정값을 따른다(7 일) (되돌리는 트랜잭션 안)")
  void purgeFollowsConfiguredRetention() {
    List<String> left =
        IntegrationDatabase.rolledBack(
            () -> {
              insertAged("old-8d", "8 days");
              insertAged("keep-6d", "6 days");
              new GuideTurnLog(jdbc, 7).purge();
              return remainingOutcomes();
            });

    assertThat(left).containsExactly("keep-6d");
  }
}
