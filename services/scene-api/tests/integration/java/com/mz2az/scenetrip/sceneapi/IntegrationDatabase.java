package com.mz2az.scenetrip.sceneapi;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 통합 테스트가 붙을 실제 PostgreSQL.
 *
 * <p>단위 테스트는 Store 를 가짜로 바꿔 끼우므로 SQL 이 한 줄도 실행되지 않는다. 컬럼 이름 오타나 {@code ST_DWithin} 의 인자 순서 같은 것은
 * 질의를 진짜로 태워 보기 전에는 아무도 알려 주지 않는다 — 이 레인이 그 자리를 메운다.
 *
 * <p>DB 는 {@code just cluster-up} 이 띄운 것을 그대로 쓴다 (tests/integration/README.md). 접속 정보는 {@code just
 * test-integration} 이 환경변수로 넣어 준다 — 포트포워드를 세우고 주소를 알려 주는 것까지가 그 레시피의 일이다.
 */
public final class IntegrationDatabase {

  private static final String URL_ENV = "SCENETRIP_TEST_JDBC_URL";
  private static final String USER_ENV = "SCENETRIP_TEST_DB_USER";
  private static final String PASSWORD_ENV = "SCENETRIP_TEST_DB_PASSWORD";

  private IntegrationDatabase() {}

  /**
   * 접속을 연다.
   *
   * <p>환경변수가 없으면 <b>건너뛰지 않고 실패한다.</b> 조용히 통과하면 "통합 테스트가 초록" 과 "통합 테스트가 아예 안 돌았다" 를 구분할 수 없게 된다.
   */
  public static JdbcClient jdbcClient() {
    String url = require(URL_ENV);
    DriverManagerDataSource dataSource = new DriverManagerDataSource(url);
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUsername(require(USER_ENV));
    dataSource.setPassword(require(PASSWORD_ENV));
    return JdbcClient.create(dataSource);
  }

  private static String require(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          name
              + " 환경변수가 없습니다. 이 테스트는 `just test-integration` 으로 실행하세요 —"
              + " 그 레시피가 클러스터의 DB 로 포트포워드를 세우고 접속 정보를 넣어 줍니다.");
    }
    return value;
  }

  /**
   * 적재된 데이터가 있는지 확인한다.
   *
   * <p>빈 DB 에서는 모든 검색이 0 건이라 어떤 단언도 통과해 버린다. 그것을 통과로 보고하는 것이 이 레인이 막아야 할 실패다.
   */
  public static void requireSeeded(JdbcClient jdbc) {
    long links = jdbc.sql("SELECT count(*) FROM place_content").query(Long.class).single();
    if (links == 0) {
      throw new IllegalStateException(
          "place_content 가 비어 있습니다. `just seed` 와 `just db-refresh-search` 를 먼저 실행하세요 —"
              + " 데이터가 없으면 검색 단언이 전부 공허하게 통과합니다.");
    }
  }

  /** 촬영작이 하나라도 있는 장소의 이름. 검색 대칭을 확인할 때 입력으로 쓴다. */
  public static String anyPlaceNameWithContent(JdbcClient jdbc) {
    return single(
        jdbc,
        """
        SELECT pi.name
        FROM place_i18n pi
        JOIN place_content pc ON pc.place_id = pi.place_id
        WHERE pi.lang = 'ko'
        ORDER BY pi.place_id
        LIMIT 1
        """,
        "촬영작이 연결된 장소");
  }

  /** 촬영지가 하나라도 있는 작품의 제목. */
  public static String anyContentTitleWithPlace(JdbcClient jdbc) {
    return single(
        jdbc,
        """
        SELECT ci.title
        FROM content_i18n ci
        JOIN place_content pc ON pc.content_id = ci.content_id
        WHERE ci.lang = 'ko'
        ORDER BY ci.content_id
        LIMIT 1
        """,
        "촬영지가 연결된 작품");
  }

  /** 촬영지가 있는 작품에 참여한 인물의 이름. */
  public static String anyPersonNameWithPlace(JdbcClient jdbc) {
    return single(
        jdbc,
        """
        SELECT pn.name
        FROM person_i18n pn
        JOIN content_cast cc ON cc.person_id = pn.person_id
        JOIN place_content pc ON pc.content_id = cc.content_id
        WHERE pn.lang = 'ko'
        ORDER BY pn.person_id
        LIMIT 1
        """,
        "촬영지 있는 작품에 참여한 인물");
  }

  /** 아무 장소 id 하나. 상세 조회 질의를 태워 보는 데 쓴다. */
  public static long anyPlaceId(JdbcClient jdbc) {
    List<Long> ids = jdbc.sql("SELECT id FROM place ORDER BY id LIMIT 1").query(Long.class).list();
    if (ids.isEmpty()) {
      throw new IllegalStateException("place 가 비어 있습니다 — `just seed` 를 먼저 실행하세요");
    }
    return ids.get(0);
  }

  /** 아무 작품 id 하나. */
  public static long anyContentId(JdbcClient jdbc) {
    List<Long> ids =
        jdbc.sql("SELECT id FROM content ORDER BY id LIMIT 1").query(Long.class).list();
    if (ids.isEmpty()) {
      throw new IllegalStateException("content 가 비어 있습니다 — `just seed` 를 먼저 실행하세요");
    }
    return ids.get(0);
  }

  private static String single(JdbcClient jdbc, String sql, String what) {
    List<String> rows = jdbc.sql(sql).query(String.class).list();
    if (rows.isEmpty()) {
      throw new IllegalStateException(what + " 이(가) 없습니다. `just seed` 로 적재한 뒤 다시 실행하세요.");
    }
    return rows.get(0);
  }
}
