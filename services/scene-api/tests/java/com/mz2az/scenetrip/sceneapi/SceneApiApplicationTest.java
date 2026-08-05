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
 */
@SpringBootTest
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
