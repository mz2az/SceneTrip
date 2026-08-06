package com.mz2az.scenetrip.sceneapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * 마이그레이션 파일이 실제로 패키징되고, Flyway 가 받아들이는 이름을 갖고 있는지 본다.
 *
 * <p>이 테스트가 막는 것은 <b>조용한 실패</b>다. Flyway 는 마이그레이션을 하나도 찾지 못해도 오류를 내지 않는다 — 그냥 아무것도 하지 않고 넘어간다.
 * BUILD.bazel 의 resources glob 이 파일을 놓치거나 디렉터리 이름이 틀리면, 앱은 정상 기동하고 헬스체크도 초록인데 테이블이 하나도 없는 상태가 된다. 그
 * 상태는 첫 쿼리가 날아가서야 드러난다.
 *
 * <p>DB 없이 도는 단위 테스트다. 스키마가 <i>의미상</i> 맞는지(테이블이 서는지, 검색이 걸리는지)는 여기서 알 수 없다 — 그것은 실제 PostgreSQL 이
 * 필요하고, 지금은 {@code just deploy} 후 수동으로 확인한다. 계획 문서 §7 참조.
 */
class MigrationResourceTest {

  /** Flyway 의 버전 마이그레이션 이름 규칙. V + 버전 + 구분자 두 개 + 설명 + .sql */
  private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__[a-z0-9_]+\\.sql$");

  private static List<Resource> migrations() throws IOException {
    return List.of(
        new PathMatchingResourcePatternResolver().getResources("classpath*:db/migration/*.sql"));
  }

  @Test
  @DisplayName("마이그레이션이 클래스패스에 실린다")
  void migrationsArePackaged() throws IOException {
    assertThat(migrations())
        .as("db/migration 아래 .sql 이 하나도 잡히지 않으면 Flyway 는 조용히 아무것도 하지 않는다")
        .isNotEmpty();
  }

  @Test
  @DisplayName("파일 이름이 Flyway 규칙을 지킨다")
  void filenamesFollowFlywayConvention() throws IOException {
    for (Resource migration : migrations()) {
      assertThat(migration.getFilename())
          .as("Flyway 는 규칙에 맞지 않는 이름을 마이그레이션으로 보지 않고 무시한다")
          .matches(VERSIONED.pattern());
    }
  }

  @Test
  @DisplayName("버전이 1 부터 빠짐없이 이어진다")
  void versionsAreUniqueAndContiguous() throws IOException {
    List<Integer> versions = new ArrayList<>();
    for (Resource migration : migrations()) {
      Matcher matcher = VERSIONED.matcher(migration.getFilename());
      assertThat(matcher.matches()).isTrue();
      versions.add(Integer.parseInt(matcher.group(1)));
    }
    versions.sort(Integer::compareTo);

    // 중복 버전은 Flyway 가 기동 시 거부한다 — 배포 후가 아니라 여기서 걸리게 한다.
    assertThat(versions).doesNotHaveDuplicates();

    // 번호가 비면 대개 파일을 빠뜨린 것이다. 이미 배포된 환경에서는 그 자리에 나중에
    // 끼워 넣은 마이그레이션이 영영 실행되지 않는다 — Flyway 는 마지막으로 적용된
    // 버전보다 낮은 것을 건너뛴다.
    for (int i = 0; i < versions.size(); i++) {
      assertThat(versions.get(i)).as("버전 번호에 빈 자리가 있다").isEqualTo(i + 1);
    }
  }
}
