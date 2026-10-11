package com.mz2az.scenetrip.sceneapi.guide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mz2az.scenetrip.sceneapi.SceneApiApplication;
import com.mz2az.scenetrip.sceneapi.api.model.GuideChatReply;
import com.mz2az.scenetrip.sceneapi.api.model.GuideEffect;
import com.mz2az.scenetrip.sceneapi.api.model.GuideToolUse;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

/**
 * 챗봇 처리 기록(MZ2AZ-410) — DB 없이 볼 수 있는 규칙: 기록하다 실패해도 던지지 않는다, 보관 기간 설정(기본 90 일), 매일 도는 정리. 실제 SQL 은
 * 통합 레인(GuideTurnLogIntegrationTest)이 본다.
 */
@DisplayName("GuideTurnLog — 실패 무시·보관 설정·매일 정리")
class GuideTurnLogTest {

  private static JdbcClient failingJdbc() {
    JdbcClient jdbc = mock(JdbcClient.class);
    JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class, RETURNS_SELF);
    when(jdbc.sql(anyString())).thenReturn(spec);
    when(spec.update()).thenThrow(new DataAccessResourceFailureException("DB 가 내려갔다"));
    return jdbc;
  }

  private static GuideChatReply reply() {
    return new GuideChatReply(
        "답",
        List.of(new GuideToolUse("places_near", null)),
        List.of(),
        1.0,
        List.of(new GuideEffect("plan.draft")),
        List.of());
  }

  @Test
  @DisplayName("답한 턴을 적다 DB 가 실패해도 던지지 않는다")
  void answeredSwallowsDbFailure() {
    GuideTurnLog log = new GuideTurnLog(failingJdbc(), 90);

    assertThatCode(() -> log.answered("ko", reply(), 12)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("실패한 턴을 적다 DB 가 실패해도 던지지 않는다")
  void failedSwallowsDbFailure() {
    GuideTurnLog log = new GuideTurnLog(failingJdbc(), 90);

    assertThatCode(() -> log.failed("en", "GUIDE_UNAVAILABLE", 40_000)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("정리하다 DB 가 실패해도 던지지 않는다(스케줄러 스레드를 죽이지 않는다)")
  void purgeSwallowsDbFailure() {
    GuideTurnLog log = new GuideTurnLog(failingJdbc(), 90);

    assertThatCode(log::purge).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("답의 목록이 비어 있거나(null) 원소가 null 이어도 기록이 던지지 않는다 — 기록이 답을 바꾸면 안 된다")
  void malformedReplyDoesNotThrow() {
    GuideTurnLog log = new GuideTurnLog(failingJdbc(), 90);
    GuideChatReply nulls = new GuideChatReply();
    nulls.setToolsUsed(null);
    nulls.setPlaces(null);
    nulls.setEffects(null);
    List<GuideToolUse> tools = new ArrayList<>();
    tools.add(null);
    List<GuideEffect> effects = new ArrayList<>();
    effects.add(null);
    GuideChatReply nullElements =
        new GuideChatReply("답", tools, List.of(), 1.0, effects, List.of());

    assertThatCode(() -> log.answered("ko", nulls, 1)).doesNotThrowAnyException();
    assertThatCode(() -> log.answered("ko", nullElements, 1)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("보관 기간 — application.yaml 의 scenetrip.guide.turn-log-retention-days 는 90")
  void retentionInApplicationYaml() {
    YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ClassPathResource("application.yaml"));
    Properties props = yaml.getObject();

    assertThat(props.getProperty("scenetrip.guide.turn-log-retention-days")).isEqualTo("90");
  }

  @Test
  @DisplayName("보관 기간 — 설정이 없을 때의 기본값도 90")
  void retentionDefaultsTo90() {
    String value = null;
    for (Constructor<?> c : GuideTurnLog.class.getConstructors()) {
      for (Parameter p : c.getParameters()) {
        Value v = p.getAnnotation(Value.class);
        if (v != null && v.value().contains("scenetrip.guide.turn-log-retention-days")) {
          value = v.value();
        }
      }
    }

    assertThat(value).isEqualTo("${scenetrip.guide.turn-log-retention-days:90}");
  }

  @Test
  @DisplayName("정리는 하루 한 번 도는 스케줄이고, 앱에 스케줄링이 켜져 있다")
  void purgeIsScheduledDaily() throws Exception {
    Method purge = GuideTurnLog.class.getMethod("purge");
    Scheduled scheduled = purge.getAnnotation(Scheduled.class);
    assertThat(scheduled).isNotNull();

    String cron = scheduled.cron();
    // "${property:default}" 면 기본값을 꺼낸다.
    if (cron.startsWith("${")) {
      cron = cron.substring(cron.indexOf(':') + 1, cron.length() - 1);
    }
    CronExpression expr = CronExpression.parse(cron);
    LocalDateTime first = expr.next(LocalDateTime.of(2026, 10, 11, 0, 0));
    LocalDateTime second = expr.next(first);
    LocalDateTime third = expr.next(second);
    assertThat(java.time.Duration.between(first, second)).hasHours(24);
    assertThat(java.time.Duration.between(second, third)).hasHours(24);

    assertThat(SceneApiApplication.class.getAnnotation(EnableScheduling.class)).isNotNull();
  }
}
