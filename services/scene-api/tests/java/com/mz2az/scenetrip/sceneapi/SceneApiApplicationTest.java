package com.mz2az.scenetrip.sceneapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * 서비스가 실제로 기동되는지 본다.
 *
 * <p>이 테스트가 ADR 0003 의 안전망이다. Bazel 이 만드는 uber jar 는 같은 경로의 리소스를 첫 번째 것만 남기고 버리는데, Spring 은 여러 jar
 * 에 흩어진 자동 구성 목록이 합쳐져야 동작한다. 병합이 어긋나면 애플리케이션은 뜨지만 자동 구성이 조용히 반만 걸린다 — 컨텍스트 로딩만으로는 그것을 잡지 못하므로, 자동
 * 구성으로만 생기는 빈이 실제로 있는지까지 확인한다.
 *
 * <p>Flyway 를 끄는 이유: 켜 두면 컨텍스트가 뜨는 순간 실제 DB 에 접속을 시도한다. 단위 레인은 DB 없이 돌아야 하므로(AGENTS.md §4.2) 여기서는
 * 끈다. DataSource 빈 자체는 그대로 만들어지지만 HikariCP 는 첫 쿼리 전까지 접속하지 않는다. 마이그레이션이 실제로 도는지는 이 레인이 답할 수 없는
 * 질문이고, {@link MigrationResourceTest} 가 그중 파일이 실려 있는지까지만 본다.
 */
@SpringBootTest(properties = "spring.flyway.enabled=false")
class SceneApiApplicationTest {

  @Autowired private ApplicationContext context;

  @Test
  @DisplayName("Spring 컨텍스트가 뜬다")
  void contextLoads() {
    assertThat(context).isNotNull();
  }

  @Test
  @DisplayName("actuator 자동 구성이 실제로 걸린다")
  void actuatorHealthEndpointIsAutoConfigured() {
    // healthEndpoint 는 spring-boot-starter-actuator 의 자동 구성으로만 만들어진다.
    // 자동 구성 목록이 병합되지 않았다면 이 빈이 없다.
    assertThat(context.containsBean("healthEndpoint")).isTrue();
  }
}
