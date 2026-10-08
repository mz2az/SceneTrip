package com.mz2az.scenetrip.sceneapi.poi.naver;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.poi.naver.NaverMatcher.Candidate;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverPlaceClient.Detail;
import com.mz2az.scenetrip.sceneapi.poi.naver.NaverPlaceClient.Outcome;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * ADR 0020 (MZ2AZ-355) — 네이버 비공식 조회 스위치는 <b>기본도 꺼짐, 배포 설정도 꺼짐</b>이다.
 *
 * <p>스프링이 실제로 빈을 만들게 해서 {@code @Value} 의 기본값과 배포되는 {@code application.yaml} 을 그대로 태운다. 주소는 언제나 이
 * 테스트의 가짜 서버로 바꿔 끼운다 — 스위치가 잘못 켜져도 진짜 네이버로는 나가지 않고, 가짜 서버의 요청 수로 드러난다.
 */
@DisplayName("NaverPlaceClient — 스위치는 기본도 배포 설정도 꺼짐 (ADR 0020)")
class NaverSwitchOffTest {

  private static HttpServer server;
  private static String base;
  private static final AtomicInteger hits = new AtomicInteger();

  @BeforeAll
  static void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          hits.incrementAndGet();
          byte[] bytes =
              "{\"data\":{\"placeList\":{\"businesses\":{\"total\":0,\"items\":[]}}}}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, bytes.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
          }
        });
    server.start();
    base = "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @AfterAll
  static void stop() {
    server.stop(0);
  }

  @BeforeEach
  void reset() {
    hits.set(0);
  }

  private static String[] fakeUrls() {
    return new String[] {
      "scenetrip.naver.search-url=" + base + "/graphql",
      "scenetrip.naver.detail-url=" + base + "/summary/{id}",
      "scenetrip.naver.timeout-seconds=1"
    };
  }

  private static List<PropertySource<?>> shippedYaml() throws IOException {
    return new YamlPropertySourceLoader()
        .load("shipped-application-yaml", new ClassPathResource("application.yaml"));
  }

  /** 꺼진 클라이언트의 두 호출 — 요청이 나가지 않고 「못 받음」 으로 답한다. */
  private static void assertOffAndSilent(NaverPlaceClient client) {
    Outcome<List<Candidate>> search = client.search("가게");
    Outcome<Detail> detail = client.detail("1001");

    assertThat(search.ok()).isFalse();
    assertThat(search.value()).isNull();
    assertThat(search.blocked()).isFalse();
    assertThat(search.why()).contains("꺼져");
    assertThat(detail.ok()).isFalse();
    assertThat(detail.value()).isNull();
    assertThat(detail.blocked()).isFalse();
    assertThat(detail.why()).contains("꺼져");
    assertThat(hits.get()).as("가짜 서버가 받은 요청 수").isZero();
  }

  @Test
  @DisplayName("scenetrip.naver.enabled 가 없으면 꺼짐 — 요청이 하나도 나가지 않는다")
  void offWhenPropertyAbsent() {
    new ApplicationContextRunner()
        .withUserConfiguration(NaverPlaceClient.class)
        .withPropertyValues(fakeUrls())
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx.getEnvironment().getProperty("scenetrip.naver.enabled")).isNull();
              assertOffAndSilent(ctx.getBean(NaverPlaceClient.class));
            });
  }

  @Test
  @DisplayName("배포되는 application.yaml 은 false 다 — 어느 문서에서도 true 가 아니다")
  void shippedYamlSaysFalse() throws IOException {
    List<PropertySource<?>> docs = shippedYaml();

    assertThat(docs).isNotEmpty();
    assertThat(docs.get(0).getProperty("scenetrip.naver.enabled")).as("첫 문서의 값").isNotNull();
    for (PropertySource<?> doc : docs) {
      Object value = doc.getProperty("scenetrip.naver.enabled");
      if (value != null) {
        assertThat(String.valueOf(value)).as(doc.getName()).isEqualToIgnoringCase("false");
      }
    }
  }

  @Test
  @DisplayName("배포되는 application.yaml 로 빈을 만들어도 꺼짐 — 요청이 하나도 나가지 않는다")
  void offWithShippedYaml() throws IOException {
    List<PropertySource<?>> docs = shippedYaml();

    new ApplicationContextRunner()
        .withUserConfiguration(NaverPlaceClient.class)
        // 주소만 가짜로 덮는다(우선순위 높음). 스위치 값은 yaml 의 것을 그대로 읽는다.
        .withPropertyValues(fakeUrls())
        .withInitializer(
            ctx -> docs.forEach(d -> ctx.getEnvironment().getPropertySources().addLast(d)))
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertThat(ctx.getEnvironment().getProperty("scenetrip.naver.enabled", Boolean.class))
                  .isFalse();
              assertOffAndSilent(ctx.getBean(NaverPlaceClient.class));
            });
  }

  @Test
  @DisplayName("명시적으로 true 일 때만 요청이 나간다 — 위의 「0 건」 이 공허하지 않다는 대조군")
  void onlyExplicitTrueCalls() {
    new ApplicationContextRunner()
        .withUserConfiguration(NaverPlaceClient.class)
        .withPropertyValues(fakeUrls())
        .withPropertyValues("scenetrip.naver.enabled=true")
        .run(
            ctx -> {
              Outcome<List<Candidate>> out = ctx.getBean(NaverPlaceClient.class).search("가게");

              assertThat(out.ok()).isTrue();
              assertThat(hits.get()).isEqualTo(1);
            });
  }
}
