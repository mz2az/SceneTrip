package com.mz2az.scenetrip.sceneapi.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mz2az.scenetrip.sceneapi.api.model.GuideChatReply;
import com.mz2az.scenetrip.sceneapi.api.model.GuideEffect;
import com.mz2az.scenetrip.sceneapi.api.model.GuidePlan;
import com.mz2az.scenetrip.sceneapi.api.model.GuidePlanReply;
import com.mz2az.scenetrip.sceneapi.api.model.GuideUiDirective;
import com.mz2az.scenetrip.sceneapi.api.model.Lang;
import com.mz2az.scenetrip.sceneapi.auth.AccessTokens;
import com.mz2az.scenetrip.sceneapi.guide.GuideAgentClient;
import com.mz2az.scenetrip.sceneapi.guide.GuideEffectApplier;
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
 * 가이드의 HTTP 계층 — 가입 판정이 에이전트 앞에 있는지, 응답을 옮겨 담지 않는지, effects 가 그 계정으로 처리기에 가는지, 실패가 계약의 상태 코드로 나가는지.
 * DB 도 에이전트도 없다.
 *
 * <p>한도(계약 「요청 한도」 B)는 진짜 {@link PaidQuota} 를 메모리 저장소({@link InMemoryUsageStore}) 위에 올려 본다 — 숫자는
 * application.yaml 의 {@code scenetrip.limits.plans.free.guide-chat.*}(시간 15, 하루 100) 그대로다. 멱등 키(C)는
 * 저장소를 목으로 두고 컨트롤러가 그 판정을 어떻게 응답으로 옮기는지 본다 — 저장소 자체의 판정은 통합 레인이 본다.
 */
@WebMvcTest(GuideController.class)
@Import({LanguageConfiguration.class, PaidQuota.class, GuideControllerTest.Usage.class})
class GuideControllerTest {

  /** 유료 한도의 저장소 — DB 대신 메모리. */
  @TestConfiguration
  static class Usage {
    @Bean
    InMemoryUsageStore usageStore() {
      return new InMemoryUsageStore();
    }
  }

  /** 분당 상한 필터(RequestRateLimitFilter)가 Bearer 토큰을 읽는다 — 이 시험은 토큰을 보내지 않는다. */
  @MockitoBean private AccessTokens rateLimitTokens;

  private static final String INSTALL_ID = "3f2a7c10-8b4e-4f21-9a33-1c5d7e9b0a44";
  private static final UUID USER = UUID.fromString("9d1e4b52-6c07-4a8f-b3d1-2e6f80c4a915");

  @Autowired private MockMvc mvc;

  @MockitoBean private GuideAgentClient agent;
  @MockitoBean private GuideEffectApplier effects;
  @MockitoBean private UserStore users;

  @MockitoBean private CurrentAccount accounts;
  @MockitoBean private IdempotencyStore idempotency;

  @Autowired private InMemoryUsageStore usage;
  @Autowired private JsonMapper json;

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
    GuideUiDirective focus = new GuideUiDirective("map.focus");
    focus.setPlaceIds(List.of(1187L));
    return new GuideChatReply(
        "서울중앙고를 담았어요.", List.of(), List.of(), 3.4, List.of(add), List.of(focus));
  }

  @BeforeEach
  void happyPathByDefault() throws InterruptedException {
    // 시간 창은 시계의 정시다. 테스트가 정시를 넘어가면 수가 다시 0 이 된다 — 끝 10 초면 다음 시간까지 기다린다.
    long intoHour = System.currentTimeMillis() % 3_600_000;
    if (intoHour > 3_590_000) {
      Thread.sleep(3_600_000 - intoHour + 50);
    }
    usage.clear();
    when(idempotency.begin(any(), anyString(), anyString()))
        .thenReturn(new IdempotencyStore.Begin.Started());
    when(accounts.resolve(UUID.fromString(INSTALL_ID))).thenReturn(USER);
    when(users.isRegistered(USER)).thenReturn(true);
    when(agent.chat(any(), any())).thenReturn(chatReply());
    GuidePlanReply planReply =
        new GuidePlanReply(new GuidePlan(List.of()), List.of(), List.of(), 0.02);
    when(agent.plan(any(), any())).thenReturn(planReply);
  }

  private static MockHttpServletRequestBuilder chat(String acceptLanguage) {
    return post("/guide/chat")
        .header("X-Install-Id", INSTALL_ID)
        .header("Accept-Language", acceptLanguage)
        .contentType(MediaType.APPLICATION_JSON)
        .content(CHAT_BODY);
  }

  @Test
  @DisplayName("정상 — 응답을 옮겨 담지 않는다(effects·ui 그대로), effects 는 그 계정으로 처리기에 간다")
  void happyPath() throws Exception {
    mvc.perform(chat("en"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reply").value("서울중앙고를 담았어요."))
        .andExpect(jsonPath("$.tookSeconds").value(3.4))
        .andExpect(jsonPath("$.effects[0].op").value("cart.add"))
        .andExpect(jsonPath("$.effects[0].placeId").value(1187))
        .andExpect(jsonPath("$.ui[0].op").value("map.focus"))
        .andExpect(jsonPath("$.ui[0].placeIds[0]").value(1187));

    verify(agent).chat(any(), eq(Lang.EN));
    verify(effects).apply(eq(USER), any());
  }

  @Test
  @DisplayName("미가입은 401 — 에이전트를 부르기 전이라 토큰이 안 나간다")
  void unregisteredIs401BeforeAgent() throws Exception {
    when(users.isRegistered(USER)).thenReturn(false);

    mvc.perform(chat("ko"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("SIGN_IN_REQUIRED"));

    verify(agent, never()).chat(any(), any());
    verify(effects, never()).apply(any(), any());
  }

  @Test
  @DisplayName("X-Install-Id 없으면 400 — 에이전트를 부르지 않는다")
  void missingInstallIdIs400() throws Exception {
    mvc.perform(post("/guide/chat").contentType(MediaType.APPLICATION_JSON).content(CHAT_BODY))
        .andExpect(status().isBadRequest());

    verify(agent, never()).chat(any(), any());
  }

  @Test
  @DisplayName("계약의 필수 필드(messages)가 없으면 400 — 생성된 @Valid 가 막는다")
  void invalidBodyIs400() throws Exception {
    mvc.perform(
            post("/guide/chat")
                .header("X-Install-Id", INSTALL_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"sessionId\":\"0d4f7b1e-5c4a-4a1e-9b0e-6f1f6d2b8c11\",\"latitude\":37.0,\"longitude\":127.0}"))
        .andExpect(status().isBadRequest());

    verify(agent, never()).chat(any(), any());
  }

  @Test
  @DisplayName("에이전트에 못 닿으면 503 GUIDE_UNAVAILABLE — 처리기는 안 불린다")
  void agentDownIs503() throws Exception {
    when(agent.chat(any(), any()))
        .thenThrow(
            ApiException.unavailable(GuideAgentClient.CODE_UNAVAILABLE, "가이드 에이전트에 연결하지 못했습니다"));

    mvc.perform(chat("ko"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("GUIDE_UNAVAILABLE"));

    verify(effects, never()).apply(any(), any());
  }

  @Test
  @DisplayName("에이전트의 400 은 그대로 400 — code·message 가 앱까지 간다")
  void agent400PassesThrough() throws Exception {
    when(agent.chat(any(), any()))
        .thenThrow(ApiException.badRequest("INVALID_PARAMETER", "context.plan: 모양이 맞지 않는다"));

    mvc.perform(chat("ko"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"))
        .andExpect(jsonPath("$.message").value("context.plan: 모양이 맞지 않는다"));
  }

  @Test
  @DisplayName("effects 적용이 결함이면 500 INTERNAL_ERROR — 「담았어요」 뒤에 200 을 주지 않는다")
  void effectFailureIs500() throws Exception {
    doThrow(new IllegalStateException("cart.add 에 placeId 가 없다")).when(effects).apply(any(), any());

    mvc.perform(chat("ko"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
  }

  @Test
  @DisplayName("마법사 — X-Install-Id 없이 200, 가입 판정도 처리기도 안 거친다")
  void planNeedsNoIdentity() throws Exception {
    mvc.perform(
            post("/guide/plan")
                .header("Accept-Language", "ja")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"titles\":[\"도깨비\"],\"days\":2,\"pace\":\"relaxed\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tookSeconds").value(0.02));

    verify(agent).plan(any(), eq(Lang.JA));
    verify(users, never()).isRegistered(any());
    verify(effects, never()).apply(any(), any());
  }

  @Test
  @DisplayName("마법사 — days 가 범위 밖(8)이면 400, 에이전트를 부르지 않는다")
  void planOutOfRangeIs400() throws Exception {
    mvc.perform(
            post("/guide/plan")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"titles\":[\"도깨비\"],\"days\":8}"))
        .andExpect(status().isBadRequest());

    verify(agent, never()).plan(any(), any());
  }

  // ───────────── 유료 한도 (계약 「요청 한도」) ─────────────

  private int used() {
    return usage.total(USER.toString(), "guide-chat");
  }

  private static MockHttpServletRequestBuilder chatWithKey(String key, String lang, String body) {
    return post("/guide/chat")
        .header("X-Install-Id", INSTALL_ID)
        .header("Accept-Language", lang)
        .header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  @Test
  @DisplayName("한도 — 성공 응답에 가장 빠듯한 창의 RateLimit 헤더(시간 15, 남은 14), 쓴 것은 시간·하루 두 창에 하나씩")
  void successCarriesQuotaHeaders() throws Exception {
    mvc.perform(chat("ko"))
        .andExpect(status().isOk())
        .andExpect(header().string("RateLimit-Limit", "15"))
        .andExpect(header().string("RateLimit-Remaining", "14"))
        .andExpect(
            header()
                .string("RateLimit-Reset", org.hamcrest.Matchers.matchesPattern("[1-9][0-9]{0,3}")))
        .andExpect(header().doesNotExist("Retry-After"));

    assertThatUsed(2);
  }

  @Test
  @DisplayName(
      "한도 — 시간당 16 번째는 429 GUIDE_LIMIT_REACHED, Retry-After·RateLimit-Remaining 0, 에이전트는 15 번만")
  void sixteenthChatIs429() throws Exception {
    for (int i = 0; i < 15; i++) {
      mvc.perform(chat("ko"))
          .andExpect(status().isOk())
          .andExpect(header().string("RateLimit-Remaining", Integer.toString(14 - i)));
    }

    mvc.perform(chat("ko"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("GUIDE_LIMIT_REACHED"))
        .andExpect(jsonPath("$.message").isNotEmpty())
        .andExpect(
            header().string("Retry-After", org.hamcrest.Matchers.matchesPattern("[1-9][0-9]{0,3}")))
        .andExpect(header().string("RateLimit-Limit", "15"))
        .andExpect(header().string("RateLimit-Remaining", "0"))
        .andExpect(header().exists("RateLimit-Reset"));

    verify(agent, times(15)).chat(any(), any());
    // 넘은 시도는 세지 않는다 — 시간 15 + 하루 15
    assertThatUsed(30);
  }

  @Test
  @DisplayName("한도 — 401(미가입)·400(헤더 없음·본문 틀림)은 세지 않는다")
  void rejectedEarlyIsNotCounted() throws Exception {
    when(users.isRegistered(USER)).thenReturn(false);
    mvc.perform(chat("ko")).andExpect(status().isUnauthorized());
    when(users.isRegistered(USER)).thenReturn(true);

    mvc.perform(post("/guide/chat").contentType(MediaType.APPLICATION_JSON).content(CHAT_BODY))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/guide/chat")
                .header("X-Install-Id", INSTALL_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionId\":\"0d4f7b1e-5c4a-4a1e-9b0e-6f1f6d2b8c11\"}"))
        .andExpect(status().isBadRequest());

    assertThatUsed(0);
  }

  @Test
  @DisplayName("한도 — 에이전트 503 이면 되돌린다(사용자 몫이 아니다)")
  void agentUnavailableIsRefunded() throws Exception {
    when(agent.chat(any(), any()))
        .thenThrow(ApiException.unavailable(GuideAgentClient.CODE_UNAVAILABLE, "닿지 않음"));

    mvc.perform(chat("ko")).andExpect(status().isServiceUnavailable());

    assertThatUsed(0);
  }

  @Test
  @DisplayName("한도 — effects 적용 실패(500)는 모델이 이미 답한 것이라 되돌리지 않는다")
  void effectFailureKeepsCount() throws Exception {
    doThrow(new IllegalStateException("결함")).when(effects).apply(any(), any());

    mvc.perform(chat("ko")).andExpect(status().isInternalServerError());

    assertThatUsed(2);
  }

  @Test
  @DisplayName("한도 — 실제 경로(/v1, 분당 필터 포함)에서도 응답 헤더는 일반 상한(60)이 아니라 챗봇 한도(15)다")
  void paidHeadersWinOverGeneralLimitThroughFilter() throws Exception {
    mvc.perform(
            post("/v1/guide/chat")
                .contextPath("/v1")
                .header("X-Install-Id", INSTALL_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(CHAT_BODY))
        .andExpect(status().isOk())
        .andExpect(header().string("RateLimit-Limit", "15"))
        .andExpect(header().string("RateLimit-Remaining", "14"));
  }

  @Test
  @DisplayName("분당 필터가 실제로 걸려 있다 — 유료가 아닌 /v1/guide/plan 은 일반 상한(비회원 60)의 RateLimit 헤더를 싣는다")
  void generalLimitHeadersOnNonPaidPath() throws Exception {
    mvc.perform(
            post("/v1/guide/plan")
                .contextPath("/v1")
                .header("X-Install-Id", INSTALL_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"titles\":[\"도깨비\"],\"days\":2}"))
        .andExpect(status().isOk())
        .andExpect(header().string("RateLimit-Limit", "60"))
        .andExpect(header().exists("RateLimit-Remaining"))
        .andExpect(header().exists("RateLimit-Reset"));
  }

  private void assertThatUsed(int expected) {
    org.assertj.core.api.Assertions.assertThat(used()).isEqualTo(expected);
  }

  // ───────────── 멱등 키 (계약 Idempotency-Key) ─────────────

  @Test
  @DisplayName("멱등 — 키가 없으면 판정을 거치지 않고 매번 처리한다(예전 동작)")
  void noKeyProcessesEveryTime() throws Exception {
    mvc.perform(chat("ko")).andExpect(status().isOk());
    mvc.perform(chat("ko")).andExpect(status().isOk());

    verify(idempotency, never()).begin(any(), any(), any());
    verify(agent, times(2)).chat(any(), any());
    assertThatUsed(4);
  }

  @Test
  @DisplayName("멱등 — 처음 보는 키는 처리하고 그 계정·키로 응답 JSON 을 저장한다, 한도는 한 번")
  void firstKeyIsProcessedAndStored() throws Exception {
    mvc.perform(chatWithKey("turn-1", "ko", CHAT_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reply").value("서울중앙고를 담았어요."))
        .andExpect(header().string("RateLimit-Remaining", "14"));

    ArgumentCaptor<String> stored = ArgumentCaptor.forClass(String.class);
    verify(idempotency).begin(eq(USER), eq("turn-1"), anyString());
    verify(idempotency).complete(eq(USER), eq("turn-1"), stored.capture());
    verify(idempotency, never()).abandon(any(), any());
    GuideChatReply roundTrip = json.readValue(stored.getValue(), GuideChatReply.class);
    org.assertj.core.api.Assertions.assertThat(roundTrip).isEqualTo(chatReply());
    assertThatUsed(2);
  }

  @Test
  @DisplayName("멱등 — 끝난 키면 저장한 답을 그대로 200 으로, 에이전트도 한도도 다시 쓰지 않는다")
  void completedKeyReplaysStoredReply() throws Exception {
    when(idempotency.begin(eq(USER), eq("turn-1"), anyString()))
        .thenReturn(new IdempotencyStore.Begin.Replay(json.writeValueAsString(chatReply())));

    mvc.perform(chatWithKey("turn-1", "ko", CHAT_BODY))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reply").value("서울중앙고를 담았어요."))
        .andExpect(jsonPath("$.effects[0].op").value("cart.add"))
        .andExpect(jsonPath("$.ui[0].placeIds[0]").value(1187));

    verify(agent, never()).chat(any(), any());
    verify(effects, never()).apply(any(), any());
    verify(idempotency, never()).complete(any(), any(), any());
    assertThatUsed(0);
  }

  @Test
  @DisplayName("멱등 — 같은 키가 처리 중이면 409 IDEMPOTENCY_IN_PROGRESS, 에이전트·한도 그대로")
  void inProgressIs409() throws Exception {
    when(idempotency.begin(eq(USER), eq("turn-1"), anyString()))
        .thenReturn(new IdempotencyStore.Begin.InProgress());

    mvc.perform(chatWithKey("turn-1", "ko", CHAT_BODY))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_IN_PROGRESS"));

    verify(agent, never()).chat(any(), any());
    verify(idempotency, never()).abandon(any(), any());
    assertThatUsed(0);
  }

  @Test
  @DisplayName("멱등 — 같은 키에 다른 내용이면 422 IDEMPOTENCY_KEY_REUSED")
  void mismatchIs422() throws Exception {
    when(idempotency.begin(eq(USER), eq("turn-1"), anyString()))
        .thenReturn(new IdempotencyStore.Begin.Mismatch());

    mvc.perform(chatWithKey("turn-1", "ko", CHAT_BODY))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

    verify(agent, never()).chat(any(), any());
    assertThatUsed(0);
  }

  @Test
  @DisplayName("멱등 — 지문은 본문과 언어로 정해진다: 같으면 같고, 언어나 본문이 다르면 다르다")
  void requestHashCoversBodyAndLanguage() throws Exception {
    String otherBody = CHAT_BODY.replace("1번 담아 줘", "2번 담아 줘");
    mvc.perform(chatWithKey("k", "ko", CHAT_BODY)).andExpect(status().isOk());
    mvc.perform(chatWithKey("k", "ko", CHAT_BODY)).andExpect(status().isOk());
    mvc.perform(chatWithKey("k", "en", CHAT_BODY)).andExpect(status().isOk());
    mvc.perform(chatWithKey("k", "ko", otherBody)).andExpect(status().isOk());

    ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
    verify(idempotency, times(4)).begin(eq(USER), eq("k"), hashes.capture());
    List<String> h = hashes.getAllValues();
    org.assertj.core.api.Assertions.assertThat(h.get(0)).isEqualTo(h.get(1));
    org.assertj.core.api.Assertions.assertThat(h.get(2)).isNotEqualTo(h.get(0));
    org.assertj.core.api.Assertions.assertThat(h.get(3)).isNotEqualTo(h.get(0));
  }

  @Test
  @DisplayName("멱등 — 에이전트가 실패하면(503) 키를 놓아 같은 키의 재시도가 다시 처리된다, 저장은 없다")
  void agentFailureReleasesKey() throws Exception {
    when(agent.chat(any(), any()))
        .thenThrow(ApiException.unavailable(GuideAgentClient.CODE_UNAVAILABLE, "닿지 않음"));

    mvc.perform(chatWithKey("turn-1", "ko", CHAT_BODY)).andExpect(status().isServiceUnavailable());

    verify(idempotency).abandon(USER, "turn-1");
    verify(idempotency, never()).complete(any(), any(), any());
    assertThatUsed(0);
  }

  @Test
  @DisplayName("멱등 — effects 적용이 실패해도(500) 키를 놓는다")
  void effectFailureReleasesKey() throws Exception {
    doThrow(new IllegalStateException("결함")).when(effects).apply(any(), any());

    mvc.perform(chatWithKey("turn-1", "ko", CHAT_BODY)).andExpect(status().isInternalServerError());

    verify(idempotency).abandon(USER, "turn-1");
    verify(idempotency, never()).complete(any(), any(), any());
  }

  @Test
  @DisplayName("멱등 — 한도를 넘어 429 면 키를 놓아 나중에 같은 키로 다시 처리할 수 있다")
  void quotaExceededReleasesKey() throws Exception {
    for (int i = 0; i < 15; i++) {
      mvc.perform(chat("ko")).andExpect(status().isOk());
    }

    mvc.perform(chatWithKey("turn-16", "ko", CHAT_BODY))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("GUIDE_LIMIT_REACHED"));

    verify(idempotency).abandon(USER, "turn-16");
    verify(idempotency, never()).complete(any(), any(), any());
  }

  @Test
  @DisplayName("멱등 — 빈 키는 키가 없는 것으로 본다")
  void blankKeyIsNoKey() throws Exception {
    mvc.perform(chatWithKey("   ", "ko", CHAT_BODY)).andExpect(status().isOk());

    verify(idempotency, never()).begin(any(), any(), any());
  }
}
