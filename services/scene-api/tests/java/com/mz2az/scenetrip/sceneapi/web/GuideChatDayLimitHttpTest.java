package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.api.model.GuideChatReply;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.guide.GuideAgentClient;
import com.mz2az.scenetrip.sceneapi.guide.GuideEffectApplier;
import com.mz2az.scenetrip.sceneapi.guide.GuideTurnLog;
import com.mz2az.scenetrip.sceneapi.guide.IdempotencyStore;
import com.mz2az.scenetrip.sceneapi.limit.InMemoryUsageStore;
import com.mz2az.scenetrip.sceneapi.limit.PaidQuota;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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
 * 가이드 챗봇 하루 한도를 HTTP 로 — 계약 {@code TooManyRequests}: 429·{@code GUIDE_LIMIT_REACHED}·{@code
 * Retry-After}(「하루 한도면 다음 자정(한국 시간)까지」)·{@code RateLimit-*}.
 *
 * <p>하루 수를 설정으로 3 까지 낮추고 시간 창은 50 으로 넉넉히 둬 하루 창만 걸리게 한다. 멱등 키는 보내지 않는다(매 턴 새로 처리).
 */
@WebMvcTest(GuideController.class)
@Import({LanguageConfiguration.class, PaidQuota.class, GuideChatDayLimitHttpTest.Usage.class})
@TestPropertySource(
    properties = {
      "scenetrip.limits.plans.free.guide-chat.per-hour=50",
      "scenetrip.limits.plans.free.guide-chat.per-day=3"
    })
class GuideChatDayLimitHttpTest {

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

  private static final String CHAT_BODY =
      """
      {"sessionId":"0d4f7b1e-5c4a-4a1e-9b0e-6f1f6d2b8c11","latitude":37.5665,"longitude":126.978,
       "messages":[{"role":"user","content":"근처 카페"}],
       "context":{"stops":[]}}
      """;

  @Autowired private MockMvc mvc;
  @Autowired private InMemoryUsageStore usage;

  @MockitoBean private AccessTokens rateLimitTokens;
  @MockitoBean private GuideAgentClient agent;
  @MockitoBean private GuideEffectApplier effects;
  @MockitoBean private UserStore users;
  @MockitoBean private CurrentAccount accounts;
  @MockitoBean private IdempotencyStore idempotency;
  @MockitoBean private GuideTurnLog turns;

  private static long secondsToKstMidnight() {
    Instant midnight = LocalDate.now(KST).plusDays(1).atStartOfDay(KST).toInstant();
    return Duration.between(Instant.now(), midnight).getSeconds();
  }

  private static GuideChatReply reply() {
    return new GuideChatReply("근처에 카페가 있어요.", List.of(), List.of(), 1.2, List.of(), List.of());
  }

  @BeforeEach
  void setUp() throws InterruptedException {
    long left = secondsToKstMidnight();
    if (left < 15) {
      Thread.sleep((left + 2) * 1000);
    }
    usage.clear();
    when(idempotency.begin(any(), anyString(), anyString()))
        .thenReturn(new IdempotencyStore.Begin.Started());
    when(accounts.resolve(UUID.fromString(ALICE_INSTALL))).thenReturn(ALICE);
    when(accounts.resolve(UUID.fromString(BOB_INSTALL))).thenReturn(BOB);
    when(users.isRegistered(ALICE)).thenReturn(true);
    when(users.isRegistered(BOB)).thenReturn(true);
    when(agent.chat(any(), any(), any())).thenReturn(reply());
  }

  private static MockHttpServletRequestBuilder chat(String installId) {
    return post("/guide/chat")
        .header("X-Install-Id", installId)
        .header("Accept-Language", "ko")
        .contentType(MediaType.APPLICATION_JSON)
        .content(CHAT_BODY);
  }

  private static long header(MvcResult r, String name) {
    String v = r.getResponse().getHeader(name);
    assertThat(v).as(name).isNotNull().matches("[0-9]+");
    return Long.parseLong(v);
  }

  @Test
  @DisplayName(
      "하루 3 번째까지 200(Remaining 2→0), 4 번째 429 GUIDE_LIMIT_REACHED·Retry-After 한국 자정까지, 에이전트는 3 번만")
  void dayLimitOverHttp() throws Exception {
    for (int i = 0; i < 3; i++) {
      MvcResult r = mvc.perform(chat(ALICE_INSTALL)).andExpect(status().isOk()).andReturn();
      assertThat(header(r, "RateLimit-Limit")).isEqualTo(3);
      assertThat(header(r, "RateLimit-Remaining")).isEqualTo(2 - i);
    }
    long before = secondsToKstMidnight();
    MvcResult r =
        mvc.perform(chat(ALICE_INSTALL))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("GUIDE_LIMIT_REACHED"))
            .andExpect(jsonPath("$.message").isNotEmpty())
            .andReturn();
    long after = secondsToKstMidnight();
    assertThat(header(r, "Retry-After")).isBetween(after - 1, before + 1);
    assertThat(header(r, "RateLimit-Limit")).isEqualTo(3);
    assertThat(header(r, "RateLimit-Remaining")).isZero();
    assertThat(header(r, "RateLimit-Reset")).isBetween(after - 1, before + 1);

    verify(agent, times(3)).chat(any(), any(), any());
  }

  @Test
  @DisplayName("에이전트 503 은 하루 몫을 쓰지 않는다 — 503 다섯 번 뒤에도 3 번 쓰고 4 번째 429")
  void agentFailuresDoNotUseDailyQuota() throws Exception {
    when(agent.chat(any(), any(), any()))
        .thenThrow(ApiException.unavailable(GuideAgentClient.CODE_UNAVAILABLE, "닿지 않음"));
    for (int i = 0; i < 5; i++) {
      mvc.perform(chat(ALICE_INSTALL)).andExpect(status().isServiceUnavailable());
    }
    doReturn(reply()).when(agent).chat(any(), any(), any());
    for (int i = 0; i < 3; i++) {
      mvc.perform(chat(ALICE_INSTALL)).andExpect(status().isOk());
    }
    mvc.perform(chat(ALICE_INSTALL))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("GUIDE_LIMIT_REACHED"));
  }

  @Test
  @DisplayName("429 뒤 거절이 이어져도 다른 사용자(bob)는 200·Remaining 2")
  void otherUserUnaffected() throws Exception {
    for (int i = 0; i < 3; i++) {
      mvc.perform(chat(ALICE_INSTALL)).andExpect(status().isOk());
    }
    for (int i = 0; i < 5; i++) {
      mvc.perform(chat(ALICE_INSTALL)).andExpect(status().isTooManyRequests());
    }
    MvcResult r = mvc.perform(chat(BOB_INSTALL)).andExpect(status().isOk()).andReturn();
    assertThat(header(r, "RateLimit-Remaining")).isEqualTo(2);
  }
}
