package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.api.model.NextLeg;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.course.CourseStore;
import com.mz2az.scenetrip.sceneapi.limit.InMemoryUsageStore;
import com.mz2az.scenetrip.sceneapi.limit.PaidQuota;
import com.mz2az.scenetrip.sceneapi.navigation.NextLegPlanner;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 길찾기 하루 한도를 HTTP 로 — 계약 {@code TooManyRequests}: 429·{@code NAVIGATION_LIMIT_REACHED}·{@code
 * Retry-After}(「하루 한도면 다음 자정(한국 시간)까지」)·{@code RateLimit-*}(가장 빠듯한 한도).
 *
 * <p>하루 수를 설정({@code scenetrip.limits.plans.free.navigation.per-day})으로 3 까지 낮춰 실제 시계로 하루 창을 닫는다 —
 * 숫자가 설정을 따라가는지도 함께 본다(계획 §4 「숫자는 설정으로」). 분 창은 넉넉히(50) 두어 하루 창만 걸리게 한다.
 */
@WebMvcTest(NavigationController.class)
@Import({LanguageConfiguration.class, PaidQuota.class, NavigationDayLimitHttpTest.Usage.class})
@TestPropertySource(
    properties = {
      "scenetrip.limits.plans.free.navigation.per-minute=50",
      "scenetrip.limits.plans.free.navigation.per-day=3"
    })
class NavigationDayLimitHttpTest {

  @TestConfiguration
  static class Usage {
    @Bean
    InMemoryUsageStore usageStore() {
      return new InMemoryUsageStore();
    }
  }

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private static final String ALICE_INSTALL = "3f2a7c10-8b4e-4f21-9a33-1c5d7e9b0a44";
  private static final String BOB_INSTALL = "7b1c9e20-1d3f-4e55-8a77-0c2e4f6a8b99";
  private static final UUID ALICE = UUID.fromString("9d1e4b52-6c07-4a8f-b3d1-2e6f80c4a915");
  private static final UUID BOB = UUID.fromString("5a6b7c8d-9e0f-4a1b-8c2d-3e4f5a6b7c8d");
  private static final long COURSE = 7;
  private static final long ITEM = 3;

  @Autowired private MockMvc mvc;
  @Autowired private InMemoryUsageStore usage;

  @MockitoBean private AccessTokens rateLimitTokens;
  @MockitoBean private NextLegPlanner planner;
  @MockitoBean private CourseStore courses;
  @MockitoBean private UserStore users;
  @MockitoBean private CurrentAccount accounts;

  private static long secondsToKstMidnight() {
    Instant now = Instant.now();
    Instant midnight = LocalDate.now(KST).plusDays(1).atStartOfDay(KST).toInstant();
    return Duration.between(now, midnight).getSeconds();
  }

  @BeforeEach
  void setUp() throws InterruptedException {
    // 한국 자정 15 초 안이면 넘긴 뒤 시작한다 — 시험 도중 하루가 다시 차지 않게.
    long left = secondsToKstMidnight();
    if (left < 15) {
      Thread.sleep((left + 2) * 1000);
    }
    usage.clear();
    for (UUID u : List.of(ALICE, BOB)) {
      when(users.isRegistered(u)).thenReturn(true);
      when(courses.exists(u, COURSE)).thenReturn(true);
      when(courses.isActive(u, COURSE)).thenReturn(true);
    }
    when(accounts.resolve(UUID.fromString(ALICE_INSTALL))).thenReturn(ALICE);
    when(accounts.resolve(UUID.fromString(BOB_INSTALL))).thenReturn(BOB);
    when(courses.findItemLocation(COURSE, ITEM))
        .thenReturn(Optional.of(new CourseStore.ItemLocation(37.02, 127.0)));
    when(planner.plan(any(), any(), any()))
        .thenReturn(new NextLeg(25, 0, NextLeg.GuidanceLangEnum.KO, List.of()).walkMeters(700));
  }

  private static MockHttpServletRequestBuilder request(String installId) {
    return post("/navigation/next-leg")
        .header("X-Install-Id", installId)
        .header("Accept-Language", "ko")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"courseId\":7,\"itemId\":3,\"latitude\":37.0,\"longitude\":127.0}");
  }

  private static long header(MvcResult r, String name) {
    String v = r.getResponse().getHeader(name);
    assertThat(v).as(name).isNotNull().matches("[0-9]+");
    return Long.parseLong(v);
  }

  @Test
  @DisplayName(
      "하루 3 번째까지 200(헤더 Limit 3·Remaining 2→0), 4 번째 429 NAVIGATION_LIMIT_REACHED·Retry-After 한국"
          + " 자정까지, 카카오는 3 번만")
  void dayLimitOverHttp() throws Exception {
    for (int i = 0; i < 3; i++) {
      MvcResult r = mvc.perform(request(ALICE_INSTALL)).andExpect(status().isOk()).andReturn();
      assertThat(header(r, "RateLimit-Limit")).isEqualTo(3);
      assertThat(header(r, "RateLimit-Remaining")).isEqualTo(2 - i);
      long reset = header(r, "RateLimit-Reset");
      assertThat(reset).isBetween(secondsToKstMidnight() - 2, secondsToKstMidnight() + 2);
    }

    long before = secondsToKstMidnight();
    MvcResult r =
        mvc.perform(request(ALICE_INSTALL))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("NAVIGATION_LIMIT_REACHED"))
            .andReturn();
    long after = secondsToKstMidnight();
    assertThat(header(r, "Retry-After")).isBetween(after - 1, before + 1);
    assertThat(header(r, "RateLimit-Limit")).isEqualTo(3);
    assertThat(header(r, "RateLimit-Remaining")).isZero();
    assertThat(header(r, "RateLimit-Reset")).isBetween(after - 1, before + 1);

    verify(planner, times(3)).plan(any(), any(), any());
  }

  @Test
  @DisplayName("제공자 실패(503)는 하루 몫을 쓰지 않는다 — 503 다섯 번 뒤에도 3 번 쓰고 4 번째 429")
  void providerFailuresDoNotUseDailyQuota() throws Exception {
    when(planner.plan(any(), any(), any()))
        .thenThrow(ApiException.unavailable("ROUTING_UNAVAILABLE", "카카오 한도"));
    for (int i = 0; i < 5; i++) {
      mvc.perform(request(ALICE_INSTALL)).andExpect(status().isServiceUnavailable());
    }
    doReturn(new NextLeg(25, 0, NextLeg.GuidanceLangEnum.KO, List.of()).walkMeters(700))
        .when(planner)
        .plan(any(), any(), any());
    for (int i = 0; i < 3; i++) {
      mvc.perform(request(ALICE_INSTALL)).andExpect(status().isOk());
    }
    mvc.perform(request(ALICE_INSTALL))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("NAVIGATION_LIMIT_REACHED"));
  }

  @Test
  @DisplayName("사용자 분리 — alice 가 하루를 다 써도 bob 은 200·Remaining 2")
  void otherUserUnaffected() throws Exception {
    for (int i = 0; i < 3; i++) {
      mvc.perform(request(ALICE_INSTALL)).andExpect(status().isOk());
    }
    mvc.perform(request(ALICE_INSTALL)).andExpect(status().isTooManyRequests());
    MvcResult r = mvc.perform(request(BOB_INSTALL)).andExpect(status().isOk()).andReturn();
    assertThat(header(r, "RateLimit-Remaining")).isEqualTo(2);
  }
}
