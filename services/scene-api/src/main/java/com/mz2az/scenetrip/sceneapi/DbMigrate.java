package com.mz2az.scenetrip.sceneapi;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * 스키마 마이그레이션만 돌리는 명령.
 *
 * <p>앱은 기동할 때 Flyway 를 자동으로 돌린다. 그런데 <b>앱 없이 스키마만 필요한 자리</b>가 있다.
 *
 * <ul>
 *   <li>CI — 통합 테스트에 DB 가 필요하지만 서버를 띄울 이유는 없다 (ADR 0005)
 *   <li>운영 — 배포 전에 마이그레이션만 먼저 올려 두고 싶을 때
 * </ul>
 *
 * <p>그때 서버를 잠깐 띄웠다 끄는 방법도 있지만, 기동 대기와 종료 처리가 파이프라인의 불안정 요인이 된다. 이 클래스는 Flyway 만 부르고 끝난다.
 *
 * <p>마이그레이션 파일은 앱과 <b>같은 것</b>을 쓴다 — {@code src/main/resources/db/migration} 이 이 바이너리의 클래스패스에도 그대로
 * 들어간다. 두 벌이 되면 "앱은 올라가는데 CI 는 스키마가 다른" 상황이 생긴다.
 */
public final class DbMigrate {

  private DbMigrate() {}

  public static void main(String[] args) {
    String url = require("SCENETRIP_DB_URL");
    String user = require("SCENETRIP_DB_USER");
    String password = require("SCENETRIP_DB_PASSWORD");

    Flyway flyway =
        Flyway.configure()
            .dataSource(url, user, password)
            .locations("classpath:db/migration")
            // 빈 DB 에 baseline 을 찍지 않는다. 찍으면 V1 부터가 "이미 적용된 것" 으로
            // 간주되어 테이블이 하나도 없는 채로 성공해 버린다 (application.yaml 과 같은 이유).
            .baselineOnMigrate(false)
            .load();

    MigrateResult result = flyway.migrate();

    if (result.migrationsExecuted == 0) {
      // 적용한 것이 없으면 targetSchemaVersion 이 null 이다. 현재 버전을 보여 준다.
      System.out.println(
          "이미 최신입니다 — 적용할 마이그레이션이 없습니다 (schema " + result.initialSchemaVersion + ")");
    } else {
      System.out.println(
          "마이그레이션 "
              + result.migrationsExecuted
              + "개 적용 — schema "
              + result.initialSchemaVersion
              + " → "
              + result.targetSchemaVersion);
    }
  }

  /**
   * 접속 정보를 환경변수에서 읽는다.
   *
   * <p>없으면 <b>기본값으로 넘어가지 않고 실패한다.</b> 어느 DB 에 마이그레이션을 돌리는지는 짐작할 자리가 아니다 — 잘못 짚으면 엉뚱한 데이터베이스의 스키마를
   * 바꾼다.
   */
  private static String require(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          name + " 환경변수가 없습니다. 이 명령은 `just db-migrate` 로 실행하세요 — 그 레시피가 접속 정보를 정합니다.");
    }
    return value;
  }
}
