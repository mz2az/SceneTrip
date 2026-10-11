package com.mz2az.scenetrip.sceneapi.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.api.model.GuideChatReply;
import com.mz2az.scenetrip.sceneapi.api.model.GuideEffect;
import com.mz2az.scenetrip.sceneapi.api.model.GuideToolUse;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.guide.GuideAgentClient;
import com.mz2az.scenetrip.sceneapi.guide.GuideEffectApplier;
import com.mz2az.scenetrip.sceneapi.guide.GuideTurnLog;
import com.mz2az.scenetrip.sceneapi.guide.IdempotencyStore;
import com.mz2az.scenetrip.sceneapi.limit.InMemoryUsageStore;
import com.mz2az.scenetrip.sceneapi.limit.PaidQuota;
import com.mz2az.scenetrip.sceneapi.user.UserStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * 챗봇 처리 기록(MZ2AZ-410)을 HTTP 계층에서 — 에이전트를 부른 턴만 한 번 적는지, 언어·결과 코드가 맞는지, 그 앞에서 거절된 턴은 적지 않는지. 기록 저장소는
 * 목이다. 실제로 무엇이 표에 들어가는지는 통합 레인이 본다.
 */
@WebMvcTest(GuideController.class)
@Import({LanguageConfiguration.class, PaidQuota.class, GuideControllerTurnLogTest.Usage.class})
@DisplayName("POST /guide/chat — 처리 기록을 남기는 턴과 남기지 않는 턴")
class GuideControllerTurnLogTest {

  @TestConfiguration
  static class Usage {
    @Bean
    InMemoryUsageStore usageStore() {
      return new InMemoryUsageStore();
    }
  }

  @MockitoBean private AccessTokens rateLimitTokens;

  private static final String INSTALL_ID = "3f2a7c10-8b4e-4f21-9a33-1c5d7e9b0a44";
  private static final UUID USER = UUID.fromString("9d1e4b52-6c07-4a8f-b3d1-2e6f80c4a915");

  @Autowired private MockMvc mvc;
  @Autowired private InMemoryUsageStore usage;
  @Autowired private JsonMapper json;

  @MockitoBean private GuideAgentClient agent;
  @MockitoBean private GuideEffectApplier effects;
  @MockitoBean private UserStore users;
  @MockitoBean private CurrentAccount accounts;
  @MockitoBean private IdempotencyStore idempotency;
  @MockitoBean private GuideTurnLog turns;

  private static final String CHAT_BODY =
      """
      {"sessionId":"0d4f7b1e-5c4a-4a1e-9b0e-6f1f6d2b8c11","latitude":37.5665,"longitude":126.978,
       "messages":[{"role":"user","content":"1번 담아 줘"}],
       "context":{"stops":[]}}
      """;

  private static GuideChatReply chatReply() {
    GuideEffect add = new GuideEffect("cart.add");
    add.setPlaceId(1187L);
    add.setName("서울중앙고");
    return new GuideChatReply(
        "서울중앙고를 담았어요.",
        List.of(new GuideToolUse("update_cart", null)),
        List.of(),
        3.4,
        List.of(add),
        List.of());
  }

  @BeforeEach
  void happyPathByDefault() throws InterruptedException {
    long intoHour = System.currentTimeMillis() % 3_600_000;
    if (intoHour > 3_590_000) {
      Thread.sleep(3_600_000 - intoHour + 50);
    }
    usage.clear();
    when(idempotency.begin(any(), anyString(), anyString()))
        .thenReturn(new IdempotencyStore.Begin.Started());
    when(accounts.resolve(UUID.fromString(INSTALL_ID))).thenReturn(USER);
    when(users.isRegistered(USER)).thenReturn(true);
    when(agent.chat(any(), any(), any())).thenReturn(chatReply());
  }

  private static MockHttpServletRequestBuilder chat(String acceptLanguage) {
    MockHttpServletRequestBuilder b =
        post("/guide/chat")
            .header("X-Install-Id", INSTALL_ID)
            .contentType(MediaType.APPLICATION_JSON)
            .content(CHAT_BODY);
    return acceptLanguage == null ? b : b.header("Accept-Language", acceptLanguage);
  }

  private static MockHttpServletRequestBuilder chatWithKey(String key) {
    return chat("ko").header("Idempotency-Key", key);
  }

  private void verifyNothingLogged() {
    verify(turns, never()).answered(any(), any(), anyLong());
    verify(turns, never()).failed(any(), any(), anyLong());
  }

  @Test
  @DisplayName("답한 턴 — 한 번, 그 언어로, 에이전트의 답 그대로, 걸린 시간 ≥ 0")
  void answeredTurnIsLoggedOnce() throws Exception {
    mvc.perform(chat("ko")).andExpect(status().isOk());

    ArgumentCaptor<GuideChatReply> reply = ArgumentCaptor.forClass(GuideChatReply.class);
    ArgumentCaptor<Long> took = ArgumentCaptor.forClass(Long.class);
    verify(turns, times(1)).answered(eq("ko"), reply.capture(), took.capture());
    verify(turns, never()).failed(any(), any(), anyLong());
    assertThat(reply.getValue()).isEqualTo(chatReply());
    assertThat(took.getValue()).isGreaterThanOrEqualTo(0L);
  }

  @Test
  @DisplayName("언어 — Accept-Language 그대로(ja·zh-Hant), 없으면 en")
  void languageFromHeaderDefaultEn() throws Exception {
    mvc.perform(chat("ja")).andExpect(status().isOk());
    mvc.perform(chat("zh-Hant")).andExpect(status().isOk());
    mvc.perform(chat(null)).andExpect(status().isOk());

    verify(turns).answered(eq("ja"), any(), anyLong());
    verify(turns).answered(eq("zh-Hant"), any(), anyLong());
    verify(turns).answered(eq("en"), any(), anyLong());
  }

  @Test
  @DisplayName("에이전트 503 — 실패 턴 하나, outcome GUIDE_UNAVAILABLE, 응답은 그대로 503")
  void agentUnavailableIsLoggedAsFailure() throws Exception {
    when(agent.chat(any(), any(), any()))
        .thenThrow(ApiException.unavailable(GuideAgentClient.CODE_UNAVAILABLE, "닿지 않음"));

    mvc.perform(chat("ko"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("GUIDE_UNAVAILABLE"));

    ArgumentCaptor<Long> took = ArgumentCaptor.forClass(Long.class);
    verify(turns, times(1)).failed(eq("ko"), eq("GUIDE_UNAVAILABLE"), took.capture());
    verify(turns, never()).answered(any(), any(), anyLong());
    assertThat(took.getValue()).isGreaterThanOrEqualTo(0L);
  }

  @Test
  @DisplayName("에이전트 400 — outcome 은 응답의 오류 코드(INVALID_PARAMETER)")
  void agentBadRequestIsLoggedWithItsCode() throws Exception {
    when(agent.chat(any(), any(), any()))
        .thenThrow(ApiException.badRequest("INVALID_PARAMETER", "모양이 맞지 않는다"));

    mvc.perform(chat("en"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));

    verify(turns, times(1)).failed(eq("en"), eq("INVALID_PARAMETER"), anyLong());
  }

  @Test
  @DisplayName("에이전트 호출이 결함으로 실패 — outcome 은 응답과 같은 INTERNAL_ERROR")
  void agentDefectIsLoggedAsInternalError() throws Exception {
    when(agent.chat(any(), any(), any())).thenThrow(new IllegalStateException("결함"));

    mvc.perform(chat("ko"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));

    verify(turns, times(1)).failed(eq("ko"), eq("INTERNAL_ERROR"), anyLong());
    verify(turns, never()).answered(any(), any(), anyLong());
  }

  @Test
  @DisplayName("에이전트는 답했고 effects 적용이 실패(500) — 에이전트를 부른 턴이니 답한 턴으로 한 번")
  void effectFailureStillLogsAnsweredTurn() throws Exception {
    doThrow(new IllegalStateException("결함")).when(effects).apply(any(), any());

    mvc.perform(chat("ko")).andExpect(status().isInternalServerError());

    verify(turns, times(1)).answered(eq("ko"), any(), anyLong());
    verify(turns, never()).failed(any(), any(), anyLong());
  }

  @Test
  @DisplayName("미가입(401) — 에이전트 전이라 기록 없음")
  void unregisteredIsNotLogged() throws Exception {
    when(users.isRegistered(USER)).thenReturn(false);

    mvc.perform(chat("ko")).andExpect(status().isUnauthorized());

    verifyNothingLogged();
  }

  @Test
  @DisplayName("X-Install-Id 없음·본문 틀림(400) — 기록 없음")
  void badRequestBeforeAgentIsNotLogged() throws Exception {
    mvc.perform(post("/guide/chat").contentType(MediaType.APPLICATION_JSON).content(CHAT_BODY))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/guide/chat")
                .header("X-Install-Id", INSTALL_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"0d4f7b1e-5c4a-4a1e-9b0e-6f1f6d2b8c11\"}"))
        .andExpect(status().isBadRequest());

    verifyNothingLogged();
  }

  @Test
  @DisplayName("한도 초과(429) — 그 턴은 기록 없음, 앞의 15 턴만 15 줄")
  void quotaExceededIsNotLogged() throws Exception {
    for (int i = 0; i < 15; i++) {
      mvc.perform(chat("ko")).andExpect(status().isOk());
    }
    mvc.perform(chat("ko")).andExpect(status().isTooManyRequests());

    verify(turns, times(15)).answered(any(), any(), anyLong());
    verify(turns, never()).failed(any(), any(), anyLong());
  }

  @Test
  @DisplayName("멱등 재생(200) — 저장한 답을 돌려줄 뿐이라 기록 없음")
  void replayIsNotLogged() throws Exception {
    when(idempotency.begin(eq(USER), eq("turn-1"), anyString()))
        .thenReturn(new IdempotencyStore.Begin.Replay(json.writeValueAsString(chatReply())));

    mvc.perform(chatWithKey("turn-1")).andExpect(status().isOk());

    verifyNothingLogged();
  }

  @Test
  @DisplayName("멱등 처리 중(409)·내용 다름(422) — 기록 없음")
  void inProgressAndMismatchAreNotLogged() throws Exception {
    when(idempotency.begin(eq(USER), eq("turn-1"), anyString()))
        .thenReturn(new IdempotencyStore.Begin.InProgress());
    when(idempotency.begin(eq(USER), eq("turn-2"), anyString()))
        .thenReturn(new IdempotencyStore.Begin.Mismatch());

    mvc.perform(chatWithKey("turn-1")).andExpect(status().isConflict());
    mvc.perform(chatWithKey("turn-2")).andExpect(status().isUnprocessableEntity());

    verifyNothingLogged();
  }

  @Test
  @DisplayName("기록 저장소에는 언어·답·시간 또는 언어·코드·시간만 간다 — 사용자·기기·멱등 키·질문은 넘기지 않는다")
  void onlyNonIdentifyingDataReachesTheLog() throws Exception {
    mvc.perform(chatWithKey("turn-xyz")).andExpect(status().isOk());

    ArgumentCaptor<String> lang = ArgumentCaptor.forClass(String.class);
    verify(turns).answered(lang.capture(), any(), anyLong());
    assertThat(lang.getValue())
        .isEqualTo("ko")
        .doesNotContain(USER.toString())
        .doesNotContain(INSTALL_ID)
        .doesNotContain("turn-xyz")
        .doesNotContain("1번 담아 줘");
  }
}
