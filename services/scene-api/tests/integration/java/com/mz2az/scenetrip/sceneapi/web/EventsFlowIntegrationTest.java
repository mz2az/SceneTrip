package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.mz2az.scenetrip.sceneapi.IntegrationDatabase;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.api.model.UsageEntityType;
import com.mz2az.scenetrip.sceneapi.api.model.UsageEvent;
import com.mz2az.scenetrip.sceneapi.api.model.UsageEventType;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.popularity.UsageEventStore;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * {@code POST /events}(계약 1.11.0) 를 컨트롤러부터 실제 DB 까지 — 스프링 컨텍스트 없이 진짜 Store·진짜 토큰 검증으로 태운다.
 *
 * <p>계약: 「비회원도 보낸다(X-Install-Id). 토큰이 있으면 가입 사용자로 적는다. 언어는 Accept-Language 로 적는다. 검색어는 기기·사용자와 잇지
 * 않고 적는다」, {@code query} 「앞뒤 공백은 서버가 지운다」, 응답은 언제나 204.
 */
@DisplayName("POST /events — 컨트롤러에서 DB 까지 (실제 DB)")
class EventsFlowIntegrationTest {

  private static final byte[] SECRET = new byte[32];

  static {
    Arrays.fill(SECRET, (byte) 7);
  }

  private static JdbcClient jdbc;
  private static UserStore users;
  private static long content;

  private final MockHttpServletRequest request = new MockHttpServletRequest();
  private final AccessTokens tokens =
      new AccessTokens(SECRET, Duration.ofMinutes(30), Clock.systemUTC());
  private EventsController controller;

  @BeforeAll
  static void connect() {
    jdbc = IntegrationDatabase.jdbcClient();
    IntegrationDatabase.requireSeeded(jdbc);
    users = new UserStore(jdbc);
    content = IntegrationDatabase.anyContentId(jdbc);
  }

  private EventsController controller() {
    if (controller == null) {
      controller =
          new EventsController(
              new UsageEventStore(jdbc), new CurrentAccount(request, tokens, users));
    }
    return controller;
  }

  private static UUID registeredUser() {
    return jdbc.sql(
            "INSERT INTO app_user (id, registered_at) VALUES (gen_random_uuid(), now()) RETURNING"
                + " id")
        .query(UUID.class)
        .single();
  }

  private static UsageEvent viewContent(long id) {
    return new UsageEvent(UsageEventType.VIEW).entityType(UsageEntityType.CONTENT).entityId(id);
  }

  @Test
  @DisplayName("비회원 조회 — 204, 사용자 없이 기기·언어로 적는다")
  void guestView() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID install = UUID.randomUUID();

          ResponseEntity<Void> response =
              controller().recordEvent(install, viewContent(content), Lang.JA);

          assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
          assertThat(response.getBody()).isNull();
          var row =
              jdbc.sql("SELECT user_id, lang::text AS lang FROM entity_view WHERE install_id = :i")
                  .param("i", install)
                  .query()
                  .singleRow();
          assertThat(row.get("user_id")).isNull();
          assertThat(row.get("lang")).isEqualTo("ja");
          return null;
        });
  }

  @Test
  @DisplayName("토큰이 있으면 가입 사용자로 적는다")
  void signedInView() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID user = registeredUser();
          UUID install = UUID.randomUUID();
          request.addHeader("Authorization", "Bearer " + tokens.issue(user).value());

          ResponseEntity<Void> response =
              controller().recordEvent(install, viewContent(content), Lang.KO);

          assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
          UUID stored =
              jdbc.sql("SELECT user_id FROM entity_view WHERE install_id = :i")
                  .param("i", install)
                  .query(UUID.class)
                  .single();
          assertThat(stored).isEqualTo(user);
          return null;
        });
  }

  @Test
  @DisplayName("같은 기기가 같은 날 같은 작품을 두 번 열어도 204 이고 한 번으로 센다")
  void repeatedViewIsCountedOnce() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID install = UUID.randomUUID();

          assertThat(
                  controller().recordEvent(install, viewContent(content), Lang.KO).getStatusCode())
              .isEqualTo(HttpStatus.NO_CONTENT);
          assertThat(
                  controller().recordEvent(install, viewContent(content), Lang.KO).getStatusCode())
              .isEqualTo(HttpStatus.NO_CONTENT);

          long rows =
              jdbc.sql("SELECT count(*) FROM entity_view WHERE install_id = :i")
                  .param("i", install)
                  .query(Long.class)
                  .single();
          assertThat(rows).isEqualTo(1);
          return null;
        });
  }

  @Test
  @DisplayName("없는 작품을 열었다고 보내도 204 — 적지는 않는다")
  void unknownTargetStill204() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID install = UUID.randomUUID();
          long missing =
              jdbc.sql("SELECT max(id) + 1000000 FROM content").query(Long.class).single();

          ResponseEntity<Void> response =
              controller().recordEvent(install, viewContent(missing), Lang.KO);

          assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
          long rows =
              jdbc.sql("SELECT count(*) FROM entity_view WHERE install_id = :i")
                  .param("i", install)
                  .query(Long.class)
                  .single();
          assertThat(rows).isZero();
          return null;
        });
  }

  @Test
  @DisplayName("검색은 앞뒤 공백을 지우고 기기·사용자 없이 적는다 — 가입 사용자가 보내도")
  void searchIsTrimmedAndUnlinked() {
    IntegrationDatabase.rolledBack(
        () -> {
          UUID user = registeredUser();
          request.addHeader("Authorization", "Bearer " + tokens.issue(user).value());
          long before =
              jdbc.sql("SELECT COALESCE(max(id), 0) FROM search_log").query(Long.class).single();

          ResponseEntity<Void> response =
              controller()
                  .recordEvent(
                      UUID.randomUUID(),
                      new UsageEvent(UsageEventType.SEARCH).query("  남산타워 야경 ").resultCount(0),
                      Lang.EN);

          assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
          var row =
              jdbc.sql(
                      "SELECT query, result_count, lang::text AS lang FROM search_log WHERE id >"
                          + " :b")
                  .param("b", before)
                  .query()
                  .singleRow();
          assertThat(row.get("query")).isEqualTo("남산타워 야경");
          assertThat(((Number) row.get("result_count")).intValue()).isZero();
          assertThat(row.get("lang")).isEqualTo("en");
          return null;
        });
  }
}
