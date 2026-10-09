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
 * ADR 0020 (MZ2AZ-355) — 네이버 카드(상세) 스위치는 <b>기본도 꺼짐, 배포 설정도 꺼짐</b>이다. ADR 0021 (MZ2AZ-373) — 링크용 검색
 * 스위치 {@code scenetrip.naver.link-lookup.enabled} 만 배포 설정에서 켜져 있고, 그것을 끄면 검색도 나가지 않는다.
 *
 * <p>스프링이 실제로 빈을 만들게 해서 {@code @Value} 의 기본값과 배포되는 {@code application.yaml} 을 그대로 태운다. 주소는 언제나 이
 * 테스트의 가짜 서버로 바꿔 끼운다 — 스위치가 잘못 켜져도 진짜 네이버로는 나가지 않고, 가짜 서버의 요청 수로 드러난다.
 */
@DisplayName("NaverPlaceClient — 카드 스위치는 기본도 배포 설정도 꺼짐, 링크용 검색만 켬 (ADR 0020 · 0021)")
class NaverSwitchOffTest {

  private static HttpServer server;
  private static String base;
  private static final AtomicInteger hits = new AtomicInteger();
  private static final AtomicInteger searchHits = new AtomicInteger();
  private static final AtomicInteger detailHits = new AtomicInteger();

  @BeforeAll
  static void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          hits.incrementAndGet();
          String path = exchange.getRequestURI().getPath();
          if (path.startsWith("/graphql")) {
            searchHits.incrementAndGet();
          } else if (path.startsWith("/summary")) {
            detailHits.incrementAndGet();
          }
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
    searchHits.set(0);
    detailHits.set(0);
  }

  private static String[] fakeUrls() {
    return new String[] {
      "scenetrip.naver.search-url=" + base + "/graphql",
      "scenetrip.naver.detail-url=" + base + "/summary/{id}",
      "scenetrip.naver.timeout-ms=1000"
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
  @DisplayName("스위치 값이 하나도 없으면 꺼짐 — 요청이 하나도 나가지 않는다")
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
  @DisplayName("배포되는 application.yaml 로 빈을 만들면 — 상세는 나가지 않고, 링크용 검색만 나간다 (ADR 0021)")
  void shippedYamlSearchesButNeverDetails() throws IOException {
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
              NaverPlaceClient client = ctx.getBean(NaverPlaceClient.class);

              Outcome<Detail> detail = client.detail("1001");
              assertThat(detail.ok()).isFalse();
              assertThat(detail.blocked()).isFalse();
              assertThat(detailHits.get()).as("상세 경로로 나간 요청").isZero();

              Outcome<List<Candidate>> search = client.search("가게");
              assertThat(search.ok()).isTrue();
              assertThat(searchHits.get()).as("검색 경로로 나간 요청").isEqualTo(1);
              assertThat(detailHits.get()).isZero();
            });
  }

  @Test
  @DisplayName("배포 설정에서 링크 스위치만 끄면 검색도 나가지 않는다 (ADR 0021)")
  void shippedYamlWithLinkLookupOffIsSilent() throws IOException {
    List<PropertySource<?>> docs = shippedYaml();

    new ApplicationContextRunner()
        .withUserConfiguration(NaverPlaceClient.class)
        .withPropertyValues(fakeUrls())
        .withPropertyValues("scenetrip.naver.link-lookup.enabled=false")
        .withInitializer(
            ctx -> docs.forEach(d -> ctx.getEnvironment().getPropertySources().addLast(d)))
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed();
              assertOffAndSilent(ctx.getBean(NaverPlaceClient.class));
            });
  }

  @Test
  @DisplayName("배포되는 application.yaml — 링크 스위치 켬, 한 번 0.7 초, 막히면 60 초 (ADR 0021)")
  void shippedYamlLinkLookupValues() throws IOException {
    PropertySource<?> doc = shippedYaml().get(0);

    assertThat(String.valueOf(doc.getProperty("scenetrip.naver.link-lookup.enabled")))
        .isEqualToIgnoringCase("true");
    assertThat(String.valueOf(doc.getProperty("scenetrip.naver.timeout-ms"))).isEqualTo("700");
    assertThat(String.valueOf(doc.getProperty("scenetrip.naver.pause-seconds"))).isEqualTo("60");
    for (PropertySource<?> d : shippedYaml()) {
      Object value = d.getProperty("scenetrip.naver.link-lookup.enabled");
      if (value != null) {
        assertThat(String.valueOf(value)).as(d.getName()).isEqualToIgnoringCase("true");
      }
    }
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
